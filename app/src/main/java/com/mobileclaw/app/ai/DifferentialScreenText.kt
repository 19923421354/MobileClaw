package com.mobileclaw.app.ai

/**
 * 差异屏幕文本 —— 仅发送屏幕文本的变化部分，大幅减少 Token 消耗。
 *
 * 核心问题：多步编排中，每轮都发送完整的屏幕文本（可能 400-1200 字符），
 * 但大部分内容在相邻轮次间是相同的。仅发送变化部分可节省 60-80% 的屏幕文本 Token。
 *
 * 差异策略：
 * - 首轮：发送完整屏幕文本
 * - 后续轮次：仅发送新增/消失的文本行
 * - 格式：[新增] xxx / [消失] yyy / [不变] (省略)
 *
 * 限制：
 * - 差异文本超过原文本 70% 时不使用差异（说明页面完全变了）
 * - 最多保留 5 行差异（避免差异过多反而更费 Token）
 */
object DifferentialScreenText {

    /** 上一轮的屏幕文本行集合。 */
    private var prevLines: Set<String> = emptySet()

    /** 上一轮的完整屏幕文本。 */
    private var prevFullText: String = ""

    /** 是否已初始化（首轮必须发送完整文本）。 */
    private var initialized: Boolean = false

    /** 差异文本超过原文本此比例时不使用差异。 */
    private const val DIFF_RATIO_THRESHOLD = 0.7f

    /** 最大差异行数。 */
    private const val MAX_DIFF_LINES = 5

    /**
     * 构建差异屏幕文本。
     *
     * @param currentScreenText 当前完整屏幕文本
     * @param screenTextLimit 屏幕文本截断长度
     * @return 差异文本（首轮或差异过大时返回截断的完整文本）
     */
    fun build(currentScreenText: String, screenTextLimit: Int): String {
        if (currentScreenText.isBlank()) {
            reset()
            return ""
        }

        // 首轮：发送完整文本
        if (!initialized) {
            initialized = true
            prevLines = currentScreenText.lines().filter { it.isNotBlank() }.toSet()
            prevFullText = currentScreenText
            return currentScreenText.take(screenTextLimit)
        }

        val currentLines = currentScreenText.lines().filter { it.isNotBlank() }.toSet()

        // 计算差异
        val added = currentLines - prevLines  // 新增的行
        val removed = prevLines - currentLines  // 消失的行
        val common = currentLines.intersect(prevLines)  // 不变的行

        // 如果差异太大（页面几乎完全变了），直接发送完整文本
        val diffLineCount = added.size + removed.size
        val totalLineCount = currentLines.size.coerceAtLeast(1)
        val diffRatio = diffLineCount.toFloat() / totalLineCount

        if (diffRatio > DIFF_RATIO_THRESHOLD || diffLineCount > MAX_DIFF_LINES * 2) {
            // 页面变化太大，发送完整文本
            prevLines = currentLines
            prevFullText = currentScreenText
            return currentScreenText.take(screenTextLimit)
        }

        // 构建差异文本
        val diffText = buildString {
            if (added.isNotEmpty()) {
                append("新增:")
                added.take(MAX_DIFF_LINES).forEach { line ->
                    append("[${line.trim().take(50)}]")
                }
            }
            if (removed.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("消失:")
                removed.take(MAX_DIFF_LINES).forEach { line ->
                    append("[${line.trim().take(50)}]")
                }
            }
            if (common.isNotEmpty() && diffLineCount > 0) {
                if (isNotEmpty()) append(" ")
                append("(其余${common.size}行不变)")
            }
        }

        // 更新状态
        prevLines = currentLines
        prevFullText = currentScreenText

        // 如果差异文本为空（没有变化），返回简短提示
        return if (diffText.isBlank()) {
            "(屏幕未变化)"
        } else {
            diffText
        }
    }

    /**
     * 获取上一轮的完整屏幕文本（用于需要完整上下文的场景）。
     */
    fun getPreviousFullText(): String = prevFullText

    /**
     * 重置差异状态（任务结束时调用）。
     */
    fun reset() {
        prevLines = emptySet()
        prevFullText = ""
        initialized = false
    }

    /**
     * 估算差异模式节省的 Token 数。
     *
     * @param fullTextLength 完整文本长度
     * @param diffTextLength 差异文本长度
     * @return 节省的 Token 数（估算）
     */
    fun estimateSavedTokens(fullTextLength: Int, diffTextLength: Int): Int {
        return ((fullTextLength - diffTextLength) / 3).coerceAtLeast(0)
    }
}
