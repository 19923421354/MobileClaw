package com.mobileclaw.app.ai

/**
 * 上下文裁剪器 —— 智能裁剪发送给 AI 的上下文，在节省 Token 的同时保留关键信息。
 *
 * 核心问题：[IntelligentContextBuilder] 构建的上下文虽已做初步筛选，但在 Token
 * 预算紧张时仍需进一步压缩。与 [EnhancedFeedbackCompressor]（压缩执行反馈）和
 * [DifferentialScreenText]（仅发送屏幕差异）互补，本裁剪器针对三类上下文分别优化：
 *
 * 1. 屏幕文本裁剪：去除重复行、UI 装饰文本（状态栏/导航栏），优先保留可操作元素
 * 2. 对话历史裁剪：保留最近 N 条完整记录，将更早的记录压缩为单行摘要
 * 3. 系统信息裁剪：根据任务类型只保留相关系统信息行
 *
 * Token 估算：中文约 1.5 Token/字，英文约 0.25 Token/字符（即 4 字符 ≈ 1 Token）。
 *
 * 使用方式：
 * ```
 * val pruner = ContextPruner()
 * val result = pruner.pruneScreenText(screenText, maxTokens = 200)
 * // result.text 即为裁剪后的文本，result.savingsPercent 为节省百分比
 *
 * // 按总预算分配三类上下文
 * val (screen, history, system) = pruner.pruneWithContextBudget(
 *     screenText, historyText, systemInfoText, totalBudget = 800
 * )
 * ```
 */
class ContextPruner(
    private val config: PruneConfig = PruneConfig()
) {

    /**
     * 裁剪配置。
     *
     * @param maxTokens 总 Token 预算（[pruneWithContextBudget] 的默认上限）
     * @param screenTextRatio 屏幕文本占预算的比例（0-1）
     * @param historyRatio 对话历史占预算的比例（0-1）
     * @param systemInfoRatio 系统信息占预算的比例（0-1）
     */
    data class PruneConfig(
        val maxTokens: Int = DEFAULT_TOTAL_BUDGET,
        val screenTextRatio: Float = 0.5f,
        val historyRatio: Float = 0.3f,
        val systemInfoRatio: Float = 0.2f
    )

    /**
     * 裁剪结果。
     *
     * @param text 裁剪后的文本
     * @param originalTokens 原始文本的 Token 估算
     * @param prunedTokens 裁剪后文本的 Token 估算
     * @param savingsPercent 节省百分比（0-100）
     */
    data class PruneResult(
        val text: String,
        val originalTokens: Int,
        val prunedTokens: Int,
        val savingsPercent: Float
    )

    /**
     * 屏幕文本裁剪详情。
     *
     * @param keptText 保留的文本（预算不足时按可操作性优先排序并截断）
     * @param removedLines 被规则移除的行列表（样板/纯数字/单字符/重复行）
     * @param actionableElements 识别到的可操作元素行列表
     */
    data class ScreenTextPruneResult(
        val keptText: String,
        val removedLines: List<String>,
        val actionableElements: List<String>
    )

    companion object {
        /** 默认总 Token 预算。 */
        private const val DEFAULT_TOTAL_BUDGET = 800

        /** 中文每字 Token 系数。 */
        private const val CHINESE_TOKEN_RATIO = 1.5

        /** 英文每字符 Token 系数（4 字符 ≈ 1 Token）。 */
        private const val ENGLISH_TOKEN_RATIO = 0.25

        /** 对话历史摘要行预留的 Token 预算。 */
        private const val HISTORY_SUMMARY_RESERVE = 25

        /** 对话历史摘要中每条记录的指令截断长度。 */
        private const val HISTORY_SUMMARY_CMD_MAX_CHARS = 15

        /** UI 装饰样板文本（精确匹配后移除）。 */
        private val BOILERPLATE_EXACT = setOf(
            "状态栏", "导航栏", "更多", "返回", "展开", "收起"
        )

        /** 可操作动作动词（行中包含即视为可操作元素，优先保留）。 */
        private val ACTION_VERBS = listOf(
            "点击", "搜索", "发送", "确定", "取消", "登录", "退出", "注销",
            "删除", "添加", "新增", "保存", "提交", "编辑", "修改", "分享",
            "下载", "上传", "安装", "打开", "关闭", "下一步", "上一步",
            "完成", "继续", "扫码", "扫描", "收藏", "点赞", "评论", "购买",
            "支付", "刷新", "复制", "粘贴", "选择", "切换", "开启", "停止",
            "播放", "暂停", "录制", "拍照", "拍摄", "发布", "发表", "回复",
            "转发", "关注", "举报", "反馈", "联系", "拨打", "接听", "挂断",
            "导航", "定位", "查找", "申请", "预约", "订阅", "确认"
        )

        // ---- 系统信息关键词分组（用于按任务类型裁剪） ----

        /** 应用/前台相关关键词。 */
        private val APP_KEYWORDS = setOf(
            "设备", "前台", "应用", "包名", "当前", "桌面", "App", "app", "常用应用"
        )

        /** 屏幕元素相关关键词。 */
        private val SCREEN_KEYWORDS = setOf(
            "屏幕", "元素", "可点击", "输入框", "Screen", "页面", "EditText", "按钮"
        )

        /** 系统控制相关关键词。 */
        private val SYSTEM_CONTROL_KEYWORDS = setOf(
            "音量", "亮度", "蓝牙", "wifi", "WiFi", "热点", "飞行", "省电",
            "旋转", "勿扰", "SYSTEM_", "操作", "可用操作"
        )

        /** 电池相关关键词。 */
        private val BATTERY_KEYWORDS = setOf("电量", "充电", "Battery")

        /** 内存相关关键词。 */
        private val MEMORY_KEYWORDS = setOf("内存", "Memory", "可用内存")

        /** 存储相关关键词。 */
        private val STORAGE_KEYWORDS = setOf("存储", "Storage")

        /** CPU 相关关键词。 */
        private val CPU_KEYWORDS = setOf("CPU", "cpu", "使用率")

        /** 通知相关关键词。 */
        private val NOTIFICATION_KEYWORDS = setOf("通知", "Notif")

        /** 读取信息类任务需要保留的关键词（全部信息）。 */
        private val READ_INFO_KEYWORDS = APP_KEYWORDS + BATTERY_KEYWORDS +
            MEMORY_KEYWORDS + STORAGE_KEYWORDS + CPU_KEYWORDS + NOTIFICATION_KEYWORDS
    }

    // =========================================================================
    //  Token 估算
    // =========================================================================

    /**
     * 估算文本的 Token 数量。
     *
     * 粗略估算：中文约 1.5 Token/字，英文约 0.25 Token/字符。
     * 中文字符判定涵盖 CJK 统一汉字、扩展 A 区、CJK 标点与全角字符。
     *
     * @param text 待估算文本
     * @return 估算的 Token 数量
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var chineseCount = 0
        for (c in text) {
            if (isChineseChar(c)) chineseCount++
        }
        val otherCount = text.length - chineseCount
        return (chineseCount * CHINESE_TOKEN_RATIO + otherCount * ENGLISH_TOKEN_RATIO).toInt()
    }

    /** 判断字符是否为中文字符（含 CJK 统一汉字、扩展 A 区、CJK 标点与全角字符）。 */
    private fun isChineseChar(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF ||  // CJK 统一汉字
               code in 0x3400..0x4DBF ||  // CJK 扩展 A
               code in 0x3000..0x303F ||  // CJK 标点符号
               code in 0xFF00..0xFFEF     // 全角字符
    }

    // =========================================================================
    //  屏幕文本裁剪
    // =========================================================================

    /**
     * 裁剪屏幕文本。
     *
     * 裁剪策略：
     * 1. 移除纯数字行、单字符行、UI 装饰样板行（状态栏/导航栏/更多/返回等）
     * 2. 对完全相同的行去重（保留首次出现）
     * 3. 将含动作动词的行（点击/搜索/发送/确定/取消等）排在前面优先保留
     * 4. 按预算 [maxTokens] 截断，保留前 N 个 Token
     *
     * 注意：预算充足时保持原始顺序以保留空间上下文；预算不足时才将可操作元素前置。
     *
     * @param text 原始屏幕文本
     * @param maxTokens 最大 Token 预算
     * @return 裁剪结果
     */
    fun pruneScreenText(text: String, maxTokens: Int): PruneResult {
        val detailed = pruneScreenTextDetailed(text, maxTokens)
        val originalTokens = estimateTokens(text)
        val prunedTokens = estimateTokens(detailed.keptText)
        return PruneResult(
            text = detailed.keptText,
            originalTokens = originalTokens,
            prunedTokens = prunedTokens,
            savingsPercent = computeSavings(originalTokens, prunedTokens)
        )
    }

    /**
     * 裁剪屏幕文本（返回详情，包含被移除行与可操作元素）。
     *
     * @param text 原始屏幕文本
     * @param maxTokens 最大 Token 预算
     * @return 屏幕文本裁剪详情
     */
    fun pruneScreenTextDetailed(text: String, maxTokens: Int): ScreenTextPruneResult {
        if (text.isBlank()) {
            return ScreenTextPruneResult("", emptyList(), emptyList())
        }

        val rawLines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val removedLines = mutableListOf<String>()
        val seen = HashSet<String>()
        val kept = mutableListOf<String>()

        // 1. 过滤无用行 + 去重
        for (line in rawLines) {
            when {
                isBoilerplate(line) -> removedLines.add(line)
                isPureNumber(line) -> removedLines.add(line)
                isSingleChar(line) -> removedLines.add(line)
                !seen.add(line) -> removedLines.add(line)  // 重复行
                else -> kept.add(line)
            }
        }

        // 2. 识别可操作元素
        val actionable = kept.filter { containsActionVerb(it) }
        val nonActionable = kept.filter { !containsActionVerb(it) }

        // 3. 按预算截断：预算充足时保持原始顺序，不足时可操作元素优先
        val orderedKeep = kept.joinToString("\n")
        val keptText = if (estimateTokens(orderedKeep) <= maxTokens) {
            orderedKeep
        } else {
            truncateLinesToTokens(actionable + nonActionable, maxTokens)
        }

        return ScreenTextPruneResult(
            keptText = keptText,
            removedLines = removedLines,
            actionableElements = actionable
        )
    }

    /** 判断一行是否为 UI 装饰样板文本。 */
    private fun isBoilerplate(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed in BOILERPLATE_EXACT) return true
        // 状态栏/导航栏相关描述行
        if (trimmed.startsWith("状态栏") || trimmed.startsWith("导航栏")) return true
        // 纯时间格式（如 12:30、08:05）
        if (trimmed.matches(Regex("\\d{1,2}:\\d{2}"))) return true
        // 纯百分比（如 80%）
        if (trimmed.matches(Regex("\\d{1,3}%"))) return true
        return false
    }

    /** 判断一行是否为纯数字（含小数点）。 */
    private fun isPureNumber(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.matches(Regex("\\d+(\\.\\d+)?"))
    }

    /** 判断一行是否为单个字符。 */
    private fun isSingleChar(line: String): Boolean {
        return line.trim().length <= 1
    }

    /** 判断一行是否包含动作动词。 */
    private fun containsActionVerb(line: String): Boolean {
        return ACTION_VERBS.any { line.contains(it) }
    }

    // =========================================================================
    //  对话历史裁剪
    // =========================================================================

    /**
     * 裁剪对话历史。
     *
     * 裁剪策略：
     * - 保留最近的若干条完整记录（从最新向前贪心保留，直到预算耗尽）
     * - 将更早的记录压缩为单行摘要，格式如「[更早N条] cmd1->成功, cmd2->失败」
     * - 摘要行预留少量 Token，确保不被完整记录挤掉
     * - 至少保留最近一条记录（超预算时截断），避免丢失最新上下文
     *
     * @param entries 对话历史条目列表（按时间正序，越靠后越新）
     * @param maxTokens 最大 Token 预算
     * @return 裁剪结果
     */
    fun pruneConversationHistory(
        entries: List<ConversationMemory.MemoryEntry>,
        maxTokens: Int
    ): PruneResult {
        val lines = entries.map { formatHistoryEntry(it) }
        return pruneHistoryLines(lines, maxTokens)
    }

    /** 将单条对话历史条目格式化为文本行。 */
    private fun formatHistoryEntry(entry: ConversationMemory.MemoryEntry): String {
        val resultStr = if (entry.success) "成功" else "失败"
        val actions = entry.actions.joinToString(",").take(80)
        val cmd = entry.userCommand.take(50)
        return "[$cmd] 动作:$actions -> $resultStr"
    }

    /**
     * 按行裁剪对话历史文本（保留最近行，摘要更早行）。
     *
     * @param lines 历史行列表（按时间正序，越靠后越新）
     * @param maxTokens 最大 Token 预算
     * @return 裁剪结果
     */
    private fun pruneHistoryLines(lines: List<String>, maxTokens: Int): PruneResult {
        if (lines.isEmpty()) {
            return PruneResult("", 0, 0, 0f)
        }

        val originalText = lines.joinToString("\n")
        val originalTokens = estimateTokens(originalText)

        // 全部能放下时直接返回
        if (originalTokens <= maxTokens) {
            return PruneResult(originalText, originalTokens, originalTokens, 0f)
        }

        // 从最近往前贪心保留完整行，预留摘要行空间
        val kept = mutableListOf<String>()
        var usedTokens = 0
        val effectiveBudget = (maxTokens - HISTORY_SUMMARY_RESERVE).coerceAtLeast(1)
        for (i in lines.lastIndex downTo 0) {
            val lineTokens = estimateTokens(lines[i])
            if (usedTokens + lineTokens > effectiveBudget) break
            kept.add(0, lines[i])
            usedTokens += lineTokens
        }

        // 至少保留最近一条（截断至预算），避免丢失最新上下文
        if (kept.isEmpty()) {
            val last = lines.last()
            kept.add(truncateToTokenBudget(last, maxTokens))
        }

        // 未保留的更早记录压缩为单行摘要
        val summarizedCount = lines.size - kept.size
        val summaryLine = if (summarizedCount > 0) {
            buildHistorySummary(lines.take(summarizedCount))
        } else null

        val sb = StringBuilder()
        if (summaryLine != null) {
            sb.append(summaryLine).append("\n")
        }
        kept.forEach { sb.append(it).append("\n") }

        val prunedText = sb.toString().trim()
        val prunedTokens = estimateTokens(prunedText)
        return PruneResult(
            text = prunedText,
            originalTokens = originalTokens,
            prunedTokens = prunedTokens,
            savingsPercent = computeSavings(originalTokens, prunedTokens)
        )
    }

    /** 将多条历史记录压缩为单行摘要。 */
    private fun buildHistorySummary(lines: List<String>): String {
        // 尝试从格式化行中提取指令与结果；提取失败则截断原文
        val parts = lines.map { line ->
            val cmdMatch = Regex("\\[(.+?)\\]").find(line)
            val cmd = cmdMatch?.groupValues?.getOrNull(1)
                ?.take(HISTORY_SUMMARY_CMD_MAX_CHARS)
                ?: line.take(HISTORY_SUMMARY_CMD_MAX_CHARS)
            val result = when {
                line.contains("成功") -> "成功"
                line.contains("失败") -> "失败"
                else -> "?"
            }
            "$cmd->$result"
        }
        return "[更早${lines.size}条] ${parts.joinToString(", ")}"
    }

    // =========================================================================
    //  系统信息裁剪
    // =========================================================================

    /**
     * 根据任务类型裁剪系统信息文本。
     *
     * 裁剪策略：
     * - 根据任务类型确定相关的关键词集合
     * - 优先保留含相关关键词的行，再按预算补充其他行
     * - READ_INFO 类任务保留全部信息行；NAVIGATE 类任务仅保留最少信息
     *
     * 任务类型与保留信息对应关系：
     * - OPEN_APP：应用/前台信息
     * - SEND_MESSAGE / SEARCH：应用 + 屏幕元素信息
     * - NAVIGATE：仅当前应用信息
     * - SYSTEM_CONTROL：系统控制操作 + 电池
     * - READ_INFO：内存/存储/电池/CPU/通知等全部信息
     * - MULTI_STEP：应用 + 屏幕 + 电池 + 内存
     * - UNKNOWN：应用 + 电池（中等信息量）
     *
     * @param phoneStateText 系统信息文本（多行）
     * @param taskType 任务类型
     * @param maxTokens 最大 Token 预算
     * @return 裁剪结果
     */
    fun pruneSystemInfo(
        phoneStateText: String,
        taskType: IntelligentContextBuilder.TaskType,
        maxTokens: Int
    ): PruneResult {
        val originalTokens = estimateTokens(phoneStateText)
        if (phoneStateText.isBlank()) {
            return PruneResult("", 0, 0, 0f)
        }
        // 预算充足时直接返回原文
        if (originalTokens <= maxTokens) {
            return PruneResult(phoneStateText, originalTokens, originalTokens, 0f)
        }

        val relevantKeywords = getRelevantKeywords(taskType)
        val lines = phoneStateText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 1. 按相关性分组
        val relevantLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()
        for (line in lines) {
            if (relevantKeywords.any { line.contains(it, ignoreCase = true) }) {
                relevantLines.add(line)
            } else {
                otherLines.add(line)
            }
        }

        // 2. 相关行优先，再补充其他行，按预算截断
        val ordered = relevantLines + otherLines
        val prunedText = truncateLinesToTokens(ordered, maxTokens)
        val prunedTokens = estimateTokens(prunedText)
        return PruneResult(
            text = prunedText,
            originalTokens = originalTokens,
            prunedTokens = prunedTokens,
            savingsPercent = computeSavings(originalTokens, prunedTokens)
        )
    }

    /** 根据任务类型获取相关的系统信息关键词集合。 */
    private fun getRelevantKeywords(
        taskType: IntelligentContextBuilder.TaskType
    ): Set<String> {
        return when (taskType) {
            IntelligentContextBuilder.TaskType.OPEN_APP -> APP_KEYWORDS
            IntelligentContextBuilder.TaskType.SEND_MESSAGE -> APP_KEYWORDS + SCREEN_KEYWORDS
            IntelligentContextBuilder.TaskType.SEARCH -> APP_KEYWORDS + SCREEN_KEYWORDS
            IntelligentContextBuilder.TaskType.NAVIGATE -> setOf("当前应用", "当前前台", "桌面")
            IntelligentContextBuilder.TaskType.SYSTEM_CONTROL -> SYSTEM_CONTROL_KEYWORDS + BATTERY_KEYWORDS
            IntelligentContextBuilder.TaskType.READ_INFO -> READ_INFO_KEYWORDS
            IntelligentContextBuilder.TaskType.MULTI_STEP ->
                APP_KEYWORDS + SCREEN_KEYWORDS + BATTERY_KEYWORDS + MEMORY_KEYWORDS
            IntelligentContextBuilder.TaskType.UNKNOWN -> APP_KEYWORDS + BATTERY_KEYWORDS
        }
    }

    // =========================================================================
    //  预算分配裁剪
    // =========================================================================

    /**
     * 按总预算分配裁剪三类上下文。
     *
     * 根据 [PruneConfig] 中的比例将 [totalBudget] 分配给屏幕文本、对话历史与系统信息，
     * 分别裁剪后返回三个结果。系统信息默认按 UNKNOWN 任务类型裁剪。
     *
     * 分配方式：三个比例归一化后按比例取整，取整余数补给系统信息预算，
     * 避免因取整导致总预算浪费。
     *
     * @param screenText 屏幕文本
     * @param historyText 对话历史文本（多行，每行一条记录）
     * @param systemInfoText 系统信息文本
     * @param totalBudget 总 Token 预算
     * @return 三类上下文的裁剪结果（屏幕、历史、系统信息）
     */
    fun pruneWithContextBudget(
        screenText: String,
        historyText: String,
        systemInfoText: String,
        totalBudget: Int
    ): Triple<PruneResult, PruneResult, PruneResult> {
        // 按比例分配预算（归一化后取整，余数补给系统信息）
        val ratioSum = config.screenTextRatio + config.historyRatio + config.systemInfoRatio
        val safeSum = if (ratioSum <= 0f) 1f else ratioSum
        val screenBudget = (totalBudget * (config.screenTextRatio / safeSum)).toInt()
        val historyBudget = (totalBudget * (config.historyRatio / safeSum)).toInt()
        val systemBudget = (totalBudget - screenBudget - historyBudget).coerceAtLeast(0)

        val screenResult = pruneScreenText(screenText, screenBudget)

        // 对话历史文本按行裁剪（保留最近行，摘要更早行）
        val historyLines = historyText.lines().filter { it.isNotBlank() }
        val historyResult = pruneHistoryLines(historyLines, historyBudget)

        val systemResult = pruneSystemInfo(
            systemInfoText,
            IntelligentContextBuilder.TaskType.UNKNOWN,
            systemBudget
        )

        return Triple(screenResult, historyResult, systemResult)
    }

    // =========================================================================
    //  通用工具方法
    // =========================================================================

    /**
     * 按预算逐行累加截断文本。
     *
     * 依次加入行，当加入下一行会超出预算时停止。若首行即超出预算，
     * 则对该行做字符级截断后返回，避免出现空结果。
     *
     * @param lines 已排序的行列表
     * @param maxTokens 最大 Token 预算
     * @return 截断后的文本
     */
    private fun truncateLinesToTokens(lines: List<String>, maxTokens: Int): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        var usedTokens = 0
        for (line in lines) {
            val lineTokens = estimateTokens(line)
            if (usedTokens + lineTokens > maxTokens) {
                // 还未保留任何行时，至少保留当前行的截断版本
                if (sb.isEmpty()) {
                    return truncateToTokenBudget(line, maxTokens)
                }
                break
            }
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(line)
            usedTokens += lineTokens
        }
        return sb.toString()
    }

    /**
     * 按预算对单段文本做字符级截断。
     *
     * 逐字符累加 Token，达到预算即停止，并在截断处追加省略号。
     *
     * @param text 原始文本
     * @param maxTokens 最大 Token 预算
     * @return 截断后的文本
     */
    private fun truncateToTokenBudget(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (estimateTokens(text) <= maxTokens) return text
        val sb = StringBuilder()
        var tokens = 0.0
        for (c in text) {
            val charTokens = if (isChineseChar(c)) CHINESE_TOKEN_RATIO else ENGLISH_TOKEN_RATIO
            if (tokens + charTokens > maxTokens) break
            sb.append(c)
            tokens += charTokens
        }
        val result = sb.toString()
        return if (result.length < text.length) "$result…" else result
    }

    /** 计算节省百分比（0-100），结果不会为负。 */
    private fun computeSavings(originalTokens: Int, prunedTokens: Int): Float {
        if (originalTokens <= 0) return 0f
        val saved = (originalTokens - prunedTokens).coerceAtLeast(0)
        return (saved.toFloat() / originalTokens) * 100f
    }
}
