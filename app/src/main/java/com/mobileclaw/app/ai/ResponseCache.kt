package com.mobileclaw.app.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * 响应缓存 —— 缓存近期相同查询的 AI 响应，避免重复 API 调用。
 *
 * 核心问题：用户经常重复发送相同或相似的指令（如连续发"截图"、"返回"），
 * 每次都调用 AI API 既浪费 Token 又增加延迟。
 *
 * 缓存策略：
 * - 以「用户指令 + 前台应用 + 复杂度等级」为缓存键
 * - 缓存有效期 30 秒（屏幕状态可能已变化）
 * - 最大缓存 20 条（LRU 淘汰）
 * - 仅缓存 SIMPLE 和 MICRO 级别的响应（复杂任务上下文多变，不宜缓存）
 * - 屏幕文本变化时不使用缓存（确保 AI 看到最新屏幕）
 *
 * 统计功能：
 * - 记录缓存命中次数和未命中次数
 * - 计算缓存命中率和节省的 Token 数
 */
class ResponseCache {

    /** 缓存条目。 */
    private data class CacheEntry(
        val result: ClawCommandResult,
        val timestamp: Long,
        val userInput: String,
        val currentApp: String?
    )

    /** 并发安全的缓存 Map。 */
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 缓存有效期（毫秒）。 */
    private val ttlMs: Long = 30_000L

    /** 最大缓存条目数。 */
    private val maxEntries: Int = 20

    /** 统计：缓存命中次数。 */
    @Volatile
    var hitCount: Int = 0
        private set

    /** 统计：缓存未命中次数。 */
    @Volatile
    var missCount: Int = 0
        private set

    /** 统计：节省的 Token 估算。 */
    @Volatile
    var savedTokens: Int = 0
        private set

    /**
     * 构建缓存键。
     * 键 = 用户指令归一化 + 前台应用 + 复杂度等级
     */
    private fun buildKey(
        userInput: String,
        currentApp: String?,
        complexity: TaskComplexityAnalyzer.Complexity
    ): String {
        val normalizedInput = userInput.trim().lowercase()
            .replace(Regex("\\s+"), " ")
        return "$normalizedInput|${currentApp ?: "unknown"}|${complexity.name}"
    }

    /**
     * 查询缓存。
     *
     * @param userInput 用户指令
     * @param currentApp 当前前台应用
     * @param complexity 任务复杂度
     * @param screenTextChanged 屏幕文本是否发生变化（变化时不使用缓存）
     * @return 缓存的结果，未命中返回 null
     */
    fun get(
        userInput: String,
        currentApp: String?,
        complexity: TaskComplexityAnalyzer.Complexity,
        screenTextChanged: Boolean = false
    ): ClawCommandResult? {
        // 仅缓存 SIMPLE 和 MICRO 级别
        if (complexity != TaskComplexityAnalyzer.Complexity.SIMPLE &&
            complexity != TaskComplexityAnalyzer.Complexity.MICRO) {
            missCount++
            return null
        }

        // 屏幕文本变化时不使用缓存
        if (screenTextChanged) {
            missCount++
            return null
        }

        val key = buildKey(userInput, currentApp, complexity)
        val entry = cache[key] ?: run {
            missCount++
            return null
        }

        // 检查是否过期
        val age = System.currentTimeMillis() - entry.timestamp
        if (age > ttlMs) {
            cache.remove(key)
            missCount++
            return null
        }

        hitCount++
        // 估算节省的 Token（简单任务约 200-400 Token）
        savedTokens += when (complexity) {
            TaskComplexityAnalyzer.Complexity.MICRO -> 150
            TaskComplexityAnalyzer.Complexity.SIMPLE -> 300
            else -> 0
        }
        android.util.Log.d("ResponseCache", "缓存命中: $key (节省~${savedTokens} Token)")
        return entry.result
    }

    /**
     * 存入缓存。
     *
     * @param userInput 用户指令
     * @param currentApp 当前前台应用
     * @param complexity 任务复杂度
     * @param result AI 返回的结果
     */
    fun put(
        userInput: String,
        currentApp: String?,
        complexity: TaskComplexityAnalyzer.Complexity,
        result: ClawCommandResult
    ) {
        // 仅缓存 SIMPLE 和 MICRO 级别
        if (complexity != TaskComplexityAnalyzer.Complexity.SIMPLE &&
            complexity != TaskComplexityAnalyzer.Complexity.MICRO) {
            return
        }

        // 不缓存包含 ANSWER 的纯聊天回答
        if (result.isAnswerOnly && result.actions.firstOrNull()?.text?.let {
                it.length > 100 || it.contains("抱歉") || it.contains("无法")
            } == true) {
            return
        }

        val key = buildKey(userInput, currentApp, complexity)
        cache[key] = CacheEntry(
            result = result,
            timestamp = System.currentTimeMillis(),
            userInput = userInput,
            currentApp = currentApp
        )

        // LRU 淘汰：超过最大条目数时移除最旧的
        if (cache.size > maxEntries) {
            val oldest = cache.entries.minByOrNull { it.value.timestamp }
            oldest?.let { cache.remove(it.key) }
        }
    }

    /** 清空缓存。 */
    fun clear() {
        cache.clear()
        hitCount = 0
        missCount = 0
        savedTokens = 0
    }

    /** 获取缓存命中率。 */
    fun hitRate(): Float {
        val total = hitCount + missCount
        return if (total > 0) hitCount.toFloat() / total else 0f
    }

    /** 获取缓存统计摘要。 */
    fun getSummary(): String {
        val total = hitCount + missCount
        val rate = if (total > 0) "%.1f%%".format(hitRate() * 100) else "N/A"
        return "缓存: ${cache.size}/$maxEntries 条 | 命中: $hitCount/$total ($rate) | 节省Token: $savedTokens"
    }
}
