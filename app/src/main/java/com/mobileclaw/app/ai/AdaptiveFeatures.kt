package com.mobileclaw.app.ai

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 自适应时序系统 —— 学习应用加载时间，动态调整等待时长。
 *
 * 核心问题：固定等待时间（如 SCREEN_WAIT{ms:2000}）对快应用浪费等待，
 * 对慢应用又不够。本系统记录每个应用的加载耗时，后续操作自动使用学到的时长。
 *
 * 特性：
 * - 记录每个应用的首次加载耗时
 * - 使用指数移动平均（EMA）平滑更新估计值
 * - 对未知应用使用保守的默认值
 * - 支持全局时序统计
 *
 * 使用方式：
 * ```
 * val timing = AdaptiveTiming()
 * val waitMs = timing.getRecommendedWait("com.tencent.mm") // 获取建议等待时间
 * timing.recordLoadTime("com.tencent.mm", 1500)            // 记录实际加载时间
 * ```
 */
class AdaptiveTiming {

    /** 应用加载时间记录。 */
    private data class LoadTimeRecord(
        var emaTime: Long,       // 指数移动平均估计值
        var minTime: Long,       // 历史最小值
        var maxTime: Long,       // 历史最大值
        var sampleCount: Int     // 样本数
    )

    /** 各应用的加载时间记录。 */
    private val records = ConcurrentHashMap<String, LoadTimeRecord>()

    /** EMA 平滑因子（0~1，越小越平滑）。 */
    private val alpha = 0.3f

    /** 未知应用的默认等待时间（毫秒）。 */
    private val defaultWaitMs = 2000L

    /** 最小等待时间（毫秒）。 */
    private val minWaitMs = 500L

    /** 最大等待时间（毫秒）。 */
    private val maxWaitMs = 5000L

    /**
     * 获取建议的等待时间。
     *
     * 根据历史记录返回该应用的估计加载时间，
     * 未知应用返回默认值。
     *
     * @param appPackage 应用包名
     * @return 建议等待时间（毫秒）
     */
    fun getRecommendedWait(appPackage: String?): Long {
        if (appPackage.isNullOrEmpty()) return defaultWaitMs
        val record = records[appPackage] ?: return defaultWaitMs
        // 在 EMA 估计值基础上加 20% 余量，确保加载完成
        val recommended = (record.emaTime * 1.2).toLong()
        return recommended.coerceIn(minWaitMs, maxWaitMs)
    }

    /**
     * 记录应用的实际加载时间。
     *
     * 使用指数移动平均（EMA）平滑更新，避免单次异常值影响过大。
     *
     * @param appPackage 应用包名
     * @param loadTimeMs 实际加载时间（毫秒）
     */
    fun recordLoadTime(appPackage: String, loadTimeMs: Long) {
        if (appPackage.isEmpty() || loadTimeMs <= 0) return

        records.compute(appPackage) { _, existing ->
            if (existing == null) {
                LoadTimeRecord(
                    emaTime = loadTimeMs,
                    minTime = loadTimeMs,
                    maxTime = loadTimeMs,
                    sampleCount = 1
                )
            } else {
                existing.emaTime = (alpha * loadTimeMs + (1 - alpha) * existing.emaTime).toLong()
                existing.minTime = minOf(existing.minTime, loadTimeMs)
                existing.maxTime = maxOf(existing.maxTime, loadTimeMs)
                existing.sampleCount++
                existing
            }
        }
    }

    /**
     * 智能等待：等待应用加载完成，而非固定时间。
     *
     * 通过轮询检查前台应用是否切换来判断加载是否完成，
     * 一旦检测到目标应用在前台就立即返回，避免不必要的等待。
     *
     * @param targetPackage 目标应用包名
     * @param systemInfo 系统信息采集器
     * @return 实际等待时间（毫秒）
     */
    suspend fun smartWait(
        targetPackage: String,
        systemInfo: SystemInfoCollector
    ): Long {
        val maxWait = getRecommendedWait(targetPackage)
        val startTime = System.currentTimeMillis()
        val pollInterval = 300L

        while (System.currentTimeMillis() - startTime < maxWait) {
            delay(pollInterval)
            val state = systemInfo.getCurrentState()
            if (state.currentAppPackage?.startsWith(targetPackage.substringBefore(".", targetPackage)) == true) {
                val actualTime = System.currentTimeMillis() - startTime
                recordLoadTime(targetPackage, actualTime)
                return actualTime
            }
        }

        // 超时，记录最大等待时间
        recordLoadTime(targetPackage, maxWait)
        return maxWait
    }

    /**
     * 获取所有应用的加载时间统计。
     */
    fun getStats(): Map<String, String> {
        return records.mapValues { (_, record) ->
            "avg=${record.emaTime}ms min=${record.minTime}ms max=${record.maxTime}ms n=${record.sampleCount}"
        }
    }

    /**
     * 获取统计摘要（用于 UI 展示）。
     */
    fun getSummary(): String {
        if (records.isEmpty()) return "暂无时序数据"
        return buildString {
            appendLine("已学习 ${records.size} 个应用的加载时序:")
            records.entries.sortedByDescending { it.value.sampleCount }.take(10).forEach { (pkg, record) ->
                val shortPkg = pkg.substringAfterLast(".")
                appendLine("  $shortPkg: avg=${record.emaTime}ms (n=${record.sampleCount})")
            }
        }
    }

    /** 清空所有记录。 */
    fun clear() {
        records.clear()
    }
}

/**
 * 主动屏幕分析器 —— 分析屏幕状态，检测问题和建议操作。
 *
 * 功能：
 * - 检测弹窗对话框（权限请求、更新提示等）
 * - 检测加载中状态（转圈、进度条）
 * - 检测错误页面
 * - 检测可操作元素并建议下一步
 */
object ProactiveScreenAnalyzer {

    /** 屏幕分析结果。 */
    data class AnalysisResult(
        val hasDialog: Boolean,
        val dialogType: DialogType?,
        val isLoading: Boolean,
        val hasError: Boolean,
        val suggestedAction: String?,
        val blockingText: String?
    )

    /** 弹窗类型。 */
    enum class DialogType {
        PERMISSION_REQUEST,   // 权限请求
        UPDATE_PROMPT,        // 更新提示
        LOGIN_REQUIRED,       // 需要登录
        NETWORK_ERROR,        // 网络错误
        GENERIC_DIALOG,       // 通用对话框
        ADVERTISEMENT         // 广告弹窗
    }

    /** 权限请求关键词。 */
    private val permissionKeywords = listOf("允许", "拒绝", "授权", "权限", "permit", "allow", "deny")

    /** 更新提示关键词。 */
    private val updateKeywords = listOf("更新", "升级", "立即更新", "update", "upgrade", "新版本")

    /** 登录关键词。 */
    private val loginKeywords = listOf("登录", "注册", "login", "sign in", "sign up", "请先登录")

    /** 网络错误关键词。 */
    private val networkErrorKeywords = listOf("网络异常", "网络错误", "连接失败", "网络不可用", "network error", "no connection")

    /** 加载中关键词。 */
    private val loadingKeywords = listOf("加载中", "正在加载", "loading", "请稍候", "正在获取")

    /** 广告关键词。 */
    private val adKeywords = listOf("跳过", "广告", "skip", "ad", "关闭广告", "立即下载")

    /** 错误页面关键词。 */
    private val errorKeywords = listOf("出错了", "页面不存在", "404", "500", "服务器错误", "加载失败", "retry", "重试")

    /**
     * 分析当前屏幕状态。
     *
     * @param screenText 屏幕上的文本
     * @param uiElements UI 元素列表
     * @return 分析结果
     */
    fun analyze(
        screenText: String,
        uiElements: List<ScreenStateCache.UiElementInfo>
    ): AnalysisResult {
        val lowerText = screenText.lowercase()

        // 检测弹窗
        val dialogType = detectDialog(lowerText, uiElements)
        val hasDialog = dialogType != null

        // 检测加载中
        val isLoading = loadingKeywords.any { lowerText.contains(it.lowercase()) }

        // 检测错误页面
        val hasError = errorKeywords.any { lowerText.contains(it.lowercase()) }

        // 生成建议
        val suggestedAction = when {
            hasDialog -> when (dialogType) {
                DialogType.PERMISSION_REQUEST -> "检测到权限请求弹窗，建议点击「允许」授权"
                DialogType.UPDATE_PROMPT -> "检测到更新提示弹窗，建议点击「稍后」或「关闭」跳过"
                DialogType.LOGIN_REQUIRED -> "检测到需要登录，建议告知用户需要先登录"
                DialogType.NETWORK_ERROR -> "检测到网络错误，建议检查网络连接后重试"
                DialogType.ADVERTISEMENT -> "检测到广告弹窗，建议点击「跳过」或「关闭」"
                DialogType.GENERIC_DIALOG -> "检测到弹窗对话框，建议根据内容选择操作"
                null -> null
            }
            isLoading -> "页面正在加载中，建议等待加载完成后再操作"
            hasError -> "检测到错误页面，建议返回或重试"
            else -> null
        }

        // 获取阻塞文本
        val blockingText = when {
            hasDialog -> dialogType?.name
            isLoading -> "LOADING"
            hasError -> "ERROR_PAGE"
            else -> null
        }

        return AnalysisResult(
            hasDialog = hasDialog,
            dialogType = dialogType,
            isLoading = isLoading,
            hasError = hasError,
            suggestedAction = suggestedAction,
            blockingText = blockingText
        )
    }

    /**
     * 检测弹窗类型。
     */
    private fun detectDialog(
        screenText: String,
        uiElements: List<ScreenStateCache.UiElementInfo>
    ): DialogType? {
        // 检查可点击元素中的按钮文本
        val clickableTexts = uiElements
            .filter { it.isClickable }
            .mapNotNull { it.text.ifEmpty { it.contentDescription } }
            .joinToString(" ")
            .lowercase()

        val allText = "$screenText $clickableTexts"

        return when {
            permissionKeywords.any { allText.contains(it.lowercase()) } -> DialogType.PERMISSION_REQUEST
            updateKeywords.any { allText.contains(it.lowercase()) } -> DialogType.UPDATE_PROMPT
            loginKeywords.any { allText.contains(it.lowercase()) } -> DialogType.LOGIN_REQUIRED
            networkErrorKeywords.any { allText.contains(it.lowercase()) } -> DialogType.NETWORK_ERROR
            adKeywords.any { allText.contains(it.lowercase()) } -> DialogType.ADVERTISEMENT
            // 通用对话框检测：有「确定」「取消」等按钮
            clickableTexts.contains("确定") || clickableTexts.contains("取消") ||
            clickableTexts.contains("confirm") || clickableTexts.contains("cancel") -> DialogType.GENERIC_DIALOG
            else -> null
        }
    }

    /**
     * 根据分析结果生成自动处理动作。
     *
     * 对于已知的弹窗类型，自动生成处理动作（如点击「允许」、「跳过」等）。
     *
     * @param analysis 分析结果
     * @param uiElements UI 元素列表
     * @return 自动处理动作列表（可能为空）
     */
    fun generateAutoActions(
        analysis: AnalysisResult,
        uiElements: List<ScreenStateCache.UiElementInfo>
    ): List<ClawAction> {
        if (!analysis.hasDialog) return emptyList()

        val actions = mutableListOf<ClawAction>()

        when (analysis.dialogType) {
            DialogType.PERMISSION_REQUEST -> {
                // 找到「允许」按钮并点击
                val allowButton = uiElements.firstOrNull { el ->
                    el.isClickable && (
                        el.text.contains("允许") ||
                        el.contentDescription?.contains("允许") == true ||
                        el.text.contains("allow") ||
                        el.text.contains("始终允许")
                    )
                }
                if (allowButton != null) {
                    actions.add(ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = kotlinx.serialization.json.JsonObject(mapOf(
                            "text" to kotlinx.serialization.json.JsonPrimitive(allowButton.text.ifEmpty { allowButton.contentDescription ?: "允许" })
                        )),
                        description = "自动点击「允许」授权"
                    ))
                }
            }

            DialogType.ADVERTISEMENT -> {
                // 找到「跳过」按钮并点击
                val skipButton = uiElements.firstOrNull { el ->
                    el.isClickable && (
                        el.text.contains("跳过") ||
                        el.contentDescription?.contains("跳过") == true ||
                        el.text.contains("skip")
                    )
                }
                if (skipButton != null) {
                    actions.add(ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = kotlinx.serialization.json.JsonObject(mapOf(
                            "text" to kotlinx.serialization.json.JsonPrimitive(skipButton.text.ifEmpty { skipButton.contentDescription ?: "跳过" })
                        )),
                        description = "自动点击「跳过」广告"
                    ))
                }
            }

            DialogType.UPDATE_PROMPT -> {
                // 找到「稍后」或「关闭」按钮并点击
                val dismissButton = uiElements.firstOrNull { el ->
                    el.isClickable && (
                        el.text.contains("稍后") ||
                        el.text.contains("关闭") ||
                        el.text.contains("取消") ||
                        el.contentDescription?.contains("稍后") == true ||
                        el.contentDescription?.contains("关闭") == true
                    )
                }
                if (dismissButton != null) {
                    actions.add(ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = kotlinx.serialization.json.JsonObject(mapOf(
                            "text" to kotlinx.serialization.json.JsonPrimitive(dismissButton.text.ifEmpty { dismissButton.contentDescription ?: "关闭" })
                        )),
                        description = "自动关闭更新提示"
                    ))
                }
            }

            else -> {
                // 其他弹窗不自动处理，避免误操作
            }
        }

        return actions
    }
}
