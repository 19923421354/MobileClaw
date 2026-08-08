package com.mobileclaw.app.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 动作执行预热器 —— 预判下一步操作并提前采集状态，减少感知延迟。
 *
 * 核心问题：用户指令发出后，系统需要「采集状态→调用AI→执行动作→采集反馈→调用AI...」
 * 每轮的状态采集（屏幕文本、前台应用等）耗时 100-300ms，如果能在 AI 思考期间
 * 并行预采集下一轮需要的状态，就能将总延迟降低 20-40%。
 *
 * 预热策略：
 * 1. AI 调用期间：并行采集当前屏幕状态（供下一轮 continueCommand 使用）
 * 2. 动作执行后：立即预采集执行后的屏幕状态（不等 AI 请求就开始采集）
 * 3. 应用打开后：启动加载检测协程，检测到加载完成立即采集状态
 *
 * 缓存策略：
 * - 预采集的状态有 2 秒 TTL（超时后重新采集）
 * - 如果动作执行改变了屏幕，自动失效缓存
 * - 仅缓存屏幕文本和 UI 元素，不缓存系统信息（变化快）
 */
class ActionPreheater(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    /** 预采集的屏幕状态缓存。 */
    data class PreheatedState(
        val screenText: String,
        val currentApp: String?,
        val timestamp: Long
    ) {
        /** 是否过期。 */
        fun isStale(maxAgeMs: Long = 2000): Boolean {
            return System.currentTimeMillis() - timestamp > maxAgeMs
        }
    }

    /** 预采集状态缓存。 */
    @Volatile
    private var cachedState: PreheatedState? = null

    /** 预采集协程 Job。 */
    private var preheatJob: Job? = null

    /** 应用加载检测协程 Job。 */
    private var loadingWatchJob: Job? = null

    /** 预采集是否启用。 */
    @Volatile
    var enabled: Boolean = true

    /** 预采集统计：成功次数。 */
    @Volatile
    var hitCount: Int = 0
        private set

    /** 预采集统计：未命中次数。 */
    @Volatile
    var missCount: Int = 0
        private set

    /**
     * 在 AI 调用期间预采集屏幕状态。
     *
     * 由 ClawController 在调用 gateway.sendCommand 前调用，
     * 在 AI 思考期间并行采集屏幕状态。
     *
     * @param systemInfo 系统信息采集器
     * @param delayMs 预采集延迟（等待动作执行后的屏幕变化）
     */
    fun preheatDuringAICall(
        systemInfo: SystemInfoCollector,
        delayMs: Long = 500
    ) {
        if (!enabled) return

        preheatJob?.cancel()
        preheatJob = scope.launch {
            // 延迟一小段时间，等屏幕可能的变化生效
            delay(delayMs)
            try {
                val state = withContext(Dispatchers.IO) { systemInfo.getCurrentState() }
                cachedState = PreheatedState(
                    screenText = state.currentScreenText,
                    currentApp = state.currentAppPackage,
                    timestamp = System.currentTimeMillis()
                )
                android.util.Log.d("ActionPreheater", "预采集完成: ${state.currentScreenText.length} 字符")
            } catch (e: Exception) {
                android.util.Log.w("ActionPreheater", "预采集失败: ${e.message}")
            }
        }
    }

    /**
     * 获取预采集的屏幕状态。
     *
     * @return 预采集状态（如果未过期），否则返回 null
     */
    fun getCachedState(): PreheatedState? {
        val state = cachedState
        if (state == null || state.isStale()) {
            missCount++
            return null
        }
        hitCount++
        cachedState = null // 取用后清除
        return state
    }

    /**
     * 启动应用加载检测。
     *
     * 打开应用后启动此检测，通过轮询前台应用判断加载是否完成，
     * 完成后立即预采集屏幕状态。
     *
     * @param systemInfo 系统信息采集器
     * @param targetPackage 目标应用包名
     * @param maxWaitMs 最大等待时间
     */
    fun watchAppLoading(
        systemInfo: SystemInfoCollector,
        targetPackage: String,
        maxWaitMs: Long = 5000
    ) {
        if (!enabled || targetPackage.isBlank()) return

        loadingWatchJob?.cancel()
        loadingWatchJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var lastApp: String? = null

            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                delay(300)
                try {
                    val state = withContext(Dispatchers.IO) { systemInfo.getCurrentState() }
                    val currentApp = state.currentAppPackage

                    // 检测到目标应用已在前台
                    if (currentApp == targetPackage) {
                        // 等待 UI 稳定（连续两次采集应用不变）
                        if (lastApp == currentApp) {
                            // UI 稳定，预采集状态
                            cachedState = PreheatedState(
                                screenText = state.currentScreenText,
                                currentApp = currentApp,
                                timestamp = System.currentTimeMillis()
                            )
                            android.util.Log.d("ActionPreheater", "应用加载检测: $targetPackage 已稳定，预采集完成")
                            return@launch
                        }
                        lastApp = currentApp
                    } else {
                        lastApp = currentApp
                    }
                } catch (e: Exception) {
                    // 忽略采集错误
                }
            }
            android.util.Log.d("ActionPreheater", "应用加载检测超时: $targetPackage")
        }
    }

    /**
     * 失效缓存（动作执行后屏幕可能变化时调用）。
     */
    fun invalidate() {
        cachedState = null
    }

    /**
     * 取消所有预采集操作。
     */
    fun cancelAll() {
        preheatJob?.cancel()
        loadingWatchJob?.cancel()
        cachedState = null
    }

    /** 获取统计摘要。 */
    fun getSummary(): String {
        val total = hitCount + missCount
        val rate = if (total > 0) "%.1f%%".format(hitCount.toFloat() / total * 100) else "N/A"
        return "预热: 命中$hitCount/未中$missCount ($rate)"
    }

    /** 清空统计。 */
    fun clearStats() {
        hitCount = 0
        missCount = 0
    }
}
