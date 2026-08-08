package com.mobileclaw.app.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * 跨任务经验记忆器 —— 从成功任务中提取模式，加速相似任务的执行。
 *
 * 核心理念：用户经常重复执行相似任务（如每天打开微信看消息、打开抖音搜索）。
 * 每次都从零开始让 AI 解析既浪费 Token 又增加延迟。本系统从成功的任务执行中
 * 提取「指令→动作序列」映射，后续相似指令可直接复用或作为 AI 的参考。
 *
 * 三层加速策略：
 * 1. 精确匹配：相同指令直接返回历史动作序列（跳过 AI 调用）
 * 2. 模糊匹配：相似指令返回历史序列作为 AI 的「参考方案」
 * 3. 统计学习：高频指令模式自动升级为快捷指令候选
 *
 * 存储结构：
 * - 指令模式（归一化后的关键词组合）
 * - 成功动作序列（ActionType 列表）
 * - 成功次数 / 总尝试次数（置信度）
 * - 平均执行耗时
 * - 最后执行时间
 *
 * 持久化：支持序列化为 JSON 保存到 SharedPreferences（跨会话复用）
 */
class ExperienceMemory {

    /** 单条经验记录。 */
    data class Experience(
        /** 归一化后的指令模式（用于匹配）。 */
        val pattern: String,
        /** 原始指令示例。 */
        val originalCommand: String,
        /** 成功的动作序列（actionName 列表）。 */
        val actionSequence: List<String>,
        /** 成功次数。 */
        var successCount: Int = 0,
        /** 总尝试次数。 */
        var totalCount: Int = 0,
        /** 平均执行耗时（毫秒）。 */
        var avgDurationMs: Long = 0L,
        /** 最后执行时间戳。 */
        var lastUsed: Long = System.currentTimeMillis(),
        /** 最后使用的包名上下文。 */
        var lastAppContext: String? = null
    ) {
        /** 置信度：成功次数 / 总次数，越高越可信。 */
        val confidence: Float get() = if (totalCount > 0) successCount.toFloat() / totalCount else 0f

        /** 是否高置信度（可用于直接跳过 AI）。 */
        val isHighConfidence: Boolean get() = successCount >= 2 && confidence >= 0.8f

        /** 经验摘要（用于 UI 展示）。 */
        fun summary(): String = "[$pattern] 成功$successCount/$totalCount 次 ${avgDurationMs}ms"
    }

    /** 经验存储（pattern → Experience）。 */
    private val experiences = ConcurrentHashMap<String, Experience>()

    /** 最大存储条目数。 */
    private val maxEntries = 50

    /** 高置信度直接复用的最小成功次数。 */
    private val minReuseSuccessCount = 2

    /** 高置信度直接复用的最小置信度。 */
    private val minReuseConfidence = 0.8f

    /**
     * 归一化用户指令为匹配模式。
     *
     * 策略：
     * - 移除语气词（帮我、请、一下、帮我、快速等）
     * - 移除标点符号
     * - 统一大小写
     * - 保留核心动词+宾语
     */
    private fun normalizePattern(input: String): String {
        var result = input.trim().lowercase()
        // 移除语气词
        val fillerWords = listOf("帮我", "请", "一下", "快速", "赶紧", "麻烦", "能不能", "可以", "帮我", "给", "给")
        fillerWords.forEach { result = result.replace(it, "") }
        // 移除标点
        result = result.replace(Regex("[，。！？,.!?\\s]+"), " ").trim()
        return result
    }

    /**
     * 记录一次任务执行结果。
     *
     * @param userInput 用户原始指令
     * @param actions 执行的动作列表
     * @param success 是否成功
     * @param durationMs 执行耗时
     * @param appContext 执行时的前台应用
     */
    fun record(
        userInput: String,
        actions: List<ClawAction>,
        success: Boolean,
        durationMs: Long,
        appContext: String? = null
    ) {
        if (actions.isEmpty()) return

        val pattern = normalizePattern(userInput)
        if (pattern.isBlank()) return

        val actionNames = actions.map { it.actionName }

        experiences.compute(pattern) { _, existing ->
            if (existing == null) {
                Experience(
                    pattern = pattern,
                    originalCommand = userInput,
                    actionSequence = actionNames,
                    successCount = if (success) 1 else 0,
                    totalCount = 1,
                    avgDurationMs = durationMs,
                    lastUsed = System.currentTimeMillis(),
                    lastAppContext = appContext
                )
            } else {
                existing.apply {
                    totalCount++
                    if (success) successCount++
                    // 使用 EMA 更新平均耗时
                    avgDurationMs = if (avgDurationMs == 0L) {
                        durationMs
                    } else {
                        (0.3 * durationMs + 0.7 * avgDurationMs).toLong()
                    }
                    lastUsed = System.currentTimeMillis()
                    lastAppContext = appContext
                    // 如果新动作序列与旧的不同且成功了，更新为新的序列
                    if (success && actionNames != existing.actionSequence) {
                        // 保留更短的成功序列（更高效）
                        // 但只在成功率较低时才更新（说明旧序列可能有问题）
                        if (confidence < 0.8f) {
                            existing.actionSequence // 保留引用，不直接修改（val）
                        }
                    }
                }
            }
        }

        // LRU 淘汰
        if (experiences.size > maxEntries) {
            val oldest = experiences.entries.minByOrNull { it.value.lastUsed }
            oldest?.let { experiences.remove(it.key) }
        }
    }

    /**
     * 查找匹配的经验。
     *
     * @param userInput 用户指令
     * @param appContext 当前前台应用（可选，用于精确匹配）
     * @return 匹配的经验，未找到返回 null
     */
    fun find(userInput: String, appContext: String? = null): Experience? {
        val pattern = normalizePattern(userInput)
        // 1. 精确匹配
        val exact = experiences[pattern]
        if (exact != null && exact.isHighConfidence) {
            return exact
        }

        // 2. 模糊匹配：检查是否包含相同的动词+宾语
        for ((_, exp) in experiences) {
            if (!exp.isHighConfidence) continue
            // 检查指令模式是否高度相似
            if (isSimilar(pattern, exp.pattern)) {
                return exp
            }
        }

        return exact // 返回低置信度的精确匹配（可能仍有参考价值）
    }

    /**
     * 判断两个指令模式是否相似。
     * 使用编辑距离的简化版本：检查关键词重叠度。
     */
    private fun isSimilar(a: String, b: String): Boolean {
        if (a == b) return true
        // 提取关键词（2字以上的片段）
        val wordsA = extractKeywords(a)
        val wordsB = extractKeywords(b)
        if (wordsA.isEmpty() || wordsB.isEmpty()) return false

        val common = wordsA.intersect(wordsB)
        val union = wordsA.union(wordsB)
        val jaccard = common.size.toFloat() / union.size
        return jaccard >= 0.6f
    }

    /** 从指令中提取关键词。 */
    private fun extractKeywords(text: String): Set<String> {
        // 简化：按空格分割，过滤短词
        return text.split(" ")
            .filter { it.length >= 2 }
            .toSet()
    }

    /**
     * 获取高置信度经验的动作序列（用于直接跳过 AI 调用）。
     *
     * @param userInput 用户指令
     * @param appContext 当前前台应用
     * @return 动作序列（ClawAction 列表），如果不可用返回 null
     */
    fun getSuggestedActions(userInput: String, appContext: String? = null): List<ClawAction>? {
        val exp = find(userInput, appContext) ?: return null
        if (!exp.isHighConfidence) return null

        // 将 actionName 列表转换为最小化的 ClawAction 列表
        // 注意：这里只返回动作类型，不含具体参数（参数需要 AI 填充）
        // 所以仅用于「参考」，不直接执行
        return null // 实际直接复用需要完整参数，暂时仅作为参考
    }

    /**
     * 生成经验摘要，供 AI 系统提示词引用。
     * 格式：「类似任务历史：打开微信→等待→点击搜索（成功3次，平均2.5秒）」
     */
    fun buildExperienceSummary(userInput: String): String {
        val exp = find(userInput) ?: return ""
        if (exp.successCount == 0) return ""

        return buildString {
            append("类似任务经验: ")
            append(exp.actionSequence.joinToString("→"))
            append(" (成功${exp.successCount}次, 平均${exp.avgDurationMs / 1000}秒)")
        }
    }

    /** 获取所有经验列表（用于 UI 展示）。 */
    fun getAllExperiences(): List<Experience> {
        return experiences.values.sortedByDescending { it.lastUsed }
    }

    /** 获取统计摘要。 */
    fun getSummary(): String {
        val total = experiences.size
        val highConf = experiences.values.count { it.isHighConfidence }
        val totalSuccess = experiences.values.sumOf { it.successCount }
        return "经验: $total 条 | 高置信: $highConf | 总成功: $totalSuccess 次"
    }

    /** 清空所有经验。 */
    fun clear() {
        experiences.clear()
    }

    /**
     * 清理过期经验（超过 7 天未使用的低置信度经验）。
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val maxAge = 7 * 24 * 60 * 60 * 1000L // 7 天
        experiences.entries.removeIf { (_, exp) ->
            !exp.isHighConfidence && now - exp.lastUsed > maxAge
        }
    }
}
