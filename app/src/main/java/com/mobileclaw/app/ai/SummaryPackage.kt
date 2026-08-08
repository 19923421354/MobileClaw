package com.mobileclaw.app.ai

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 总结打包器 —— 将对话历史压缩为结构化文本/文件。
 *
 * **核心功能**：
 * 1. 将对话历史打包为**结构化文本**，可直接粘贴到新 AI 的输入框中
 * 2. 将对话历史打包为**文件**，可发送/分享给新 AI
 * 3. 打包格式经过精心设计，确保新 AI 能**完美接续上下文**继续执行任务
 * 4. 支持设置打包内容的**详细程度**（字数控制）
 * 5. 支持导出为**纯文本格式**（通用）或**Markdown 格式**（更结构化）
 *
 * **打包格式说明**：
 * 打包后的内容包含以下部分，确保新 AI 能无缝接续：
 * ```
 * ═══ 会话上下文传递 ═══
 * 生成时间: 2024-01-01 14:30
 * 总任务数: 12 | 成功: 10 | 失败: 2
 * 当前模式: 智能节省模式
 *
 * **对话摘要**:
 * - [14:20] 打开微信 → 成功
 * - [14:25] 给张三发消息 → 成功
 * ...
 *
 * **关键信息**:
 * - 已打开微信并进入聊天界面
 * - 当前停留在与张三的聊天窗口
 *
 * **待办事项**:
 * - 需要在微信中查找文件
 *
 * **会话状态**:
 * - 当前应用: 微信
 * - 已打开: 微信, 设置
 * ═══ 上下文传递结束 ═══
 * ```
 */
class SummaryPackage {

    companion object {
        private const val TAG = "SummaryPackage"
        private const val PACKAGE_DIR = "summary_packages"
        private const val HEADER_SEPARATOR = "═══ 会话上下文传递 ═══"
        private const val FOOTER_SEPARATOR = "═══ 上下文传递结束 ═══"

        /** 时间格式化器 */
        private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    // =========================================================================
    //  数据类
    // =========================================================================

    /**
     * 打包后的上下文包。
     *
     * @property text      纯文本格式的上下文（可直接粘贴到 AI 输入框）
     * @property markdown  Markdown 格式的上下文（更结构化）
     * @property lineCount 文本行数
     * @property charCount 字符数
     * @property estimatedTokens 估算 Token 数
     * @property generatedAt 生成时间戳
     */
    data class ContextPackage(
        val text: String,
        val markdown: String,
        val lineCount: Int,
        val charCount: Int,
        val estimatedTokens: Int,
        val generatedAt: Long = System.currentTimeMillis()
    )

    // =========================================================================
    //  打包方法
    // =========================================================================

    /**
     * 将对话记忆打包为可传递的上下文包。
     *
     * @param entries     对话历史条目
     * @param digest      对话摘要（可选，如果已有则直接使用）
     * @param mode        当前总结模式
     * @param wordCount   目标字数
     * @param sessionInfo 会话信息（当前应用、已打开应用等）
     * @return 打包好的上下文包
     */
    fun packageContext(
        entries: List<ConversationMemory.MemoryEntry>,
        digest: ConversationSummarizer.ConversationDigest? = null,
        mode: SummarySettings.SummaryMode = SummarySettings.SummaryMode.SMART_SAVING,
        wordCount: SummarySettings.SummaryWordCount = SummarySettings.SummaryWordCount.MEDIUM,
        sessionInfo: SessionInfo = SessionInfo()
    ): ContextPackage {
        // 生成摘要数据
        val summarizer = ConversationSummarizer()
        val effectiveDigest = digest ?: summarizer.summarize(entries)

        // 根据字数控制生成不同详细度的摘要
        val maxLinePerSection = when (wordCount.targetWords) {
            50 -> 1
            100 -> 2
            200 -> 3
            500 -> 5
            1000 -> 10
            else -> 3
        }

        // 构建纯文本格式
        val text = buildTextFormat(effectiveDigest, sessionInfo, maxLinePerSection)

        // 构建 Markdown 格式
        val markdown = buildMarkdownFormat(effectiveDigest, sessionInfo, maxLinePerSection)

        // 估算 Token
        val estimatedTokens = summarizer.estimateTokens(text)

        return ContextPackage(
            text = text,
            markdown = markdown,
            lineCount = text.lines().size,
            charCount = text.length,
            estimatedTokens = estimatedTokens
        )
    }

    /**
     * 将会话上下文打包为可直接复制的字符串。
     * 新 AI 收到这个字符串后，能完美理解之前的上下文并继续执行任务。
     */
    private fun buildTextFormat(
        digest: ConversationSummarizer.ConversationDigest,
        sessionInfo: SessionInfo,
        maxLines: Int
    ): String {
        return buildString {
            appendLine(HEADER_SEPARATOR)
            appendLine("生成时间: ${dateTimeFormatter.format(Date(digest.generatedAt))}")
            appendLine("总任务数: ${digest.totalTasks} | 成功: ${digest.successCount} | 失败: ${digest.failedCount}")
            if (sessionInfo.currentApp != null) {
                appendLine("当前应用: ${sessionInfo.currentApp}")
            }
            appendLine()

            // 关键信息（最优先保留）
            if (digest.keyFacts.isNotEmpty()) {
                appendLine("【关键信息】")
                digest.keyFacts.take(maxLines).forEach { fact ->
                    appendLine("  • $fact")
                }
                if (digest.keyFacts.size > maxLines) {
                    appendLine("  ...(共${digest.keyFacts.size}条)")
                }
                appendLine()
            }

            // 待办事项（第二优先）
            if (digest.followUps.isNotEmpty()) {
                appendLine("【待办事项】")
                digest.followUps.take(maxLines).forEach { task ->
                    appendLine("  • $task")
                }
                if (digest.followUps.size > maxLines) {
                    appendLine("  ...(共${digest.followUps.size}条)")
                }
                appendLine()
            }

            // 已完成任务摘要
            if (digest.summaries.isNotEmpty()) {
                appendLine("【已完成任务】")
                digest.summaries.take(maxLines).forEach { s ->
                    val timeStr = timeFormatter.format(Date(s.timestamp))
                    val appTag = if (s.appContext != null) "[${s.appContext}] " else ""
                    appendLine("  • [$timeStr] $appTag${s.command.take(40)} → ${s.result}")
                }
                if (digest.summaries.size > maxLines) {
                    appendLine("  ...(共${digest.summaries.size}条)")
                }
                appendLine()
            }

            // 会话状态
            if (sessionInfo.currentApp != null || sessionInfo.openedApps.isNotEmpty()) {
                appendLine("【会话状态】")
                if (sessionInfo.currentApp != null) {
                    appendLine("  当前应用: ${sessionInfo.currentApp}")
                }
                if (sessionInfo.openedApps.isNotEmpty()) {
                    appendLine("  已打开: ${sessionInfo.openedApps.joinToString(", ")}")
                }
                appendLine()
            }

            // 使用说明（让新 AI 知道如何接续）
            appendLine("【使用说明】")
            appendLine("  以上是之前的会话摘要。请根据以上上下文继续执行用户的新指令。")
            appendLine("  如果用户提到了之前已打开的应用或正在进行的操作，请直接继续。")

            appendLine()
            appendLine(FOOTER_SEPARATOR)
        }
    }

    /**
     * 构建 Markdown 格式的上下文包。
     * 比纯文本更结构化，适合在 Markdown 编辑器中查看。
     */
    private fun buildMarkdownFormat(
        digest: ConversationSummarizer.ConversationDigest,
        sessionInfo: SessionInfo,
        maxLines: Int
    ): String {
        return buildString {
            appendLine("# 会话上下文传递")
            appendLine()
            appendLine("> 生成时间: ${dateTimeFormatter.format(Date(digest.generatedAt))}")
            appendLine("> 总任务数: ${digest.totalTasks} | 成功: ${digest.successCount} | 失败: ${digest.failedCount}")
            if (sessionInfo.currentApp != null) {
                appendLine("> 当前应用: `${sessionInfo.currentApp}`")
            }
            appendLine()

            // 关键信息
            if (digest.keyFacts.isNotEmpty()) {
                appendLine("## 关键信息")
                digest.keyFacts.take(maxLines).forEach { fact ->
                    appendLine("- $fact")
                }
                if (digest.keyFacts.size > maxLines) {
                    appendLine("- *...（共${digest.keyFacts.size}条，已截断）*")
                }
                appendLine()
            }

            // 待办事项
            if (digest.followUps.isNotEmpty()) {
                appendLine("## 待办事项")
                digest.followUps.take(maxLines).forEach { task ->
                    appendLine("- **$task**")
                }
                if (digest.followUps.size > maxLines) {
                    appendLine("- *...（共${digest.followUps.size}条，已截断）*")
                }
                appendLine()
            }

            // 已完成任务
            if (digest.summaries.isNotEmpty()) {
                appendLine("## 已完成任务")
                appendLine("| 时间 | 应用 | 指令 | 结果 |")
                appendLine("|------|------|------|------|")
                digest.summaries.take(maxLines).forEach { s ->
                    val timeStr = timeFormatter.format(Date(s.timestamp))
                    val app = s.appContext ?: "-"
                    appendLine("| $timeStr | $app | ${s.command.take(30)} | ${s.result} |")
                }
                if (digest.summaries.size > maxLines) {
                    appendLine("| ... | ... | ...（共${digest.summaries.size}条）| ... |")
                }
                appendLine()
            }

            // 会话状态
            if (sessionInfo.currentApp != null || sessionInfo.openedApps.isNotEmpty()) {
                appendLine("## 会话状态")
                if (sessionInfo.currentApp != null) {
                    appendLine("- **当前应用**: `${sessionInfo.currentApp}`")
                }
                if (sessionInfo.openedApps.isNotEmpty()) {
                    appendLine("- **已打开应用**: ${sessionInfo.openedApps.joinToString(", ") { "`$it`" }}")
                }
                appendLine()
            }

            // 使用说明
            appendLine("---")
            appendLine("*以上是之前的会话摘要。新 AI 收到后，应基于以上上下文继续执行用户的新指令，保持会话的连贯性。*")
        }
    }

    // =========================================================================
    //  导出方法
    // =========================================================================

    /**
     * 将上下文包保存为文件。
     *
     * @param context  Android 上下文
     * @param pkg      上下文包
     * @param fileName 文件名（不含扩展名），默认自动生成
     * @return 保存的文件路径，失败返回 null
     */
    fun saveToFile(
        context: Context,
        pkg: ContextPackage,
        fileName: String? = null
    ): File? {
        return try {
            val dir = File(context.cacheDir, PACKAGE_DIR)
            dir.mkdirs()

            val name = fileName ?: "context_package_${System.currentTimeMillis()}"
            val file = File(dir, "$name.txt")

            file.writeText(pkg.text)
            Log.d(TAG, "上下文包已保存到: ${file.absolutePath} (${pkg.charCount} 字符, ${pkg.estimatedTokens} Token)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "保存上下文包失败", e)
            null
        }
    }

    /**
     * 将上下文包保存为 Markdown 文件。
     */
    fun saveToMarkdownFile(
        context: Context,
        pkg: ContextPackage,
        fileName: String? = null
    ): File? {
        return try {
            val dir = File(context.cacheDir, PACKAGE_DIR)
            dir.mkdirs()

            val name = fileName ?: "context_package_${System.currentTimeMillis()}"
            val file = File(dir, "$name.md")

            file.writeText(pkg.markdown)
            Log.d(TAG, "Markdown 上下文包已保存到: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "保存 Markdown 上下文包失败", e)
            null
        }
    }

    /**
     * 获取所有已保存的上下文包文件列表。
     */
    fun getSavedPackages(context: Context): List<File> {
        val dir = File(context.cacheDir, PACKAGE_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.name.endsWith(".txt") || file.name.endsWith(".md") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * 清理过期的上下文包文件（默认保留最近 7 天）。
     */
    fun cleanExpiredPackages(context: Context, maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L) {
        val dir = File(context.cacheDir, PACKAGE_DIR)
        if (!dir.exists()) return
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > maxAgeMs) {
                file.delete()
                Log.d(TAG, "已清理过期上下文包: ${file.name}")
            }
        }
    }

    // =========================================================================
    //  辅助数据类
    // =========================================================================

    /**
     * 会话信息。
     *
     * @property currentApp 当前前台应用包名
     * @property openedApps 本次会话中已打开的应用列表
     * @property batteryPercent 电量百分比
     * @property networkType 网络类型（如 WiFi/4G/5G）
     */
    data class SessionInfo(
        val currentApp: String? = null,
        val openedApps: List<String> = emptyList(),
        val batteryPercent: Int = -1,
        val networkType: String? = null
    )
}