package com.mobileclaw.app.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话摘要器 —— 将对话历史压缩为简洁的结构化摘要。
 *
 * 核心问题：[ConversationMemory] 保留的原始记录条目包含完整指令、动作列表、
 * 执行结果与手机状态快照，随着对话轮次增加，直接引用会消耗大量 Token。
 * 本摘要器在保留关键信息的前提下，将历史压缩为结构化摘要，供系统提示词
 * 引用或供 UI 展示。
 *
 * 与 [ContextPruner]（按预算裁剪文本）和 [EnhancedFeedbackCompressor]（压缩
 * 单轮执行反馈）互补：本摘要器关注的是**跨多轮对话**的语义压缩，而非单条
 * 文本的长度裁剪。
 *
 * 核心能力：
 * 1. 任务结果单行化：将完成的任务结果压缩为「[时间] 指令 -> 结果」的一行摘要
 * 2. 相关任务批量合并：同一应用或同类指令合并为批量摘要（如「打开了3个应用」）
 * 3. 关键事实提取：从指令中提取「打开了微信」「发送了3条消息」等关键事实
 * 4. 未完成/待办追踪：识别失败、需要登录、需要重试等待办任务
 * 5. 时间锚定摘要：生成「最近5分钟/30分钟/1小时」内发生事情的结构化摘要
 * 6. Token 感知：所有摘要保证不超过指定 Token 预算
 *
 * Token 估算：中文约 1.5 Token/字，英文约 0.25 Token/字符（与 [ContextPruner] 一致）。
 *
 * 使用方式：
 * ```
 * val summarizer = ConversationSummarizer()
 * val digest = summarizer.summarize(memory.entries)
 * // digest.keyFacts 包含提取的关键事实
 * // digest.followUps 包含待办任务
 *
 * // 生成最近 30 分钟的摘要
 * val now = System.currentTimeMillis()
 * val rangeSummary = summarizer.summarizeTimeRange(
 *     memory.entries, now - 30 * 60 * 1000, now
 * )
 *
 * // 按 Token 预算压缩为文本（可直接放入系统提示词）
 * val compact = summarizer.compressHistory(memory.entries, maxTokens = 200)
 * ```
 */
class ConversationSummarizer {

    // =========================================================================
    //  枚举与数据类
    // =========================================================================

    /**
     * 任务分类。
     *
     * 用于将相似任务归组并生成批量摘要。
     */
    enum class TaskCategory {
        /** 应用导航：打开/关闭/切换应用。 */
        APP_NAVIGATION,

        /** 通讯消息：发送消息、拨打电话、回复等。 */
        COMMUNICATION,

        /** 媒体控制：播放/暂停/切歌/音视频控制。 */
        MEDIA,

        /** 系统设置：音量、亮度、蓝牙、WiFi 等系统开关与参数。 */
        SYSTEM_SETTINGS,

        /** 信息查询：读取通知、电量、存储、系统信息等。 */
        INFORMATION,

        /** 娱乐：视频、游戏、音乐播放等娱乐内容。 */
        ENTERTAINMENT,

        /** 未知类型：无法归入以上类别的任务。 */
        UNKNOWN
    }

    /**
     * 单条任务摘要。
     *
     * @property timestamp  任务执行时间戳（毫秒）
     * @property command    原始用户指令（截断至 [COMMAND_MAX_CHARS] 字符）
     * @property result     执行结果摘要（如「成功」「失败:未找到」）
     * @property appContext 涉及的应用上下文（如「微信」「设置」），无则 null
     * @property category   任务分类
     */
    data class TaskSummary(
        val timestamp: Long,
        val command: String,
        val result: String,
        val appContext: String?,
        val category: TaskCategory
    )

    /**
     * 时间范围统计。
     *
     * @property totalTasks        范围内任务总数
     * @property successCount      成功任务数
     * @property failedCount       失败任务数
     * @property categoryBreakdown 按分类统计的任务数量分布
     */
    data class RangeStats(
        val totalTasks: Int,
        val successCount: Int,
        val failedCount: Int,
        val categoryBreakdown: Map<TaskCategory, Int>
    )

    /**
     * 对话摘要（整体摘要）。
     *
     * @property totalTasks  任务总数
     * @property successCount 成功任务数
     * @property failedCount  失败任务数
     * @property summaries    每条任务的摘要列表
     * @property keyFacts     提取的关键事实（如「打开了微信」「发送了3条消息」）
     * @property followUps    待办/未完成任务列表
     * @property generatedAt  摘要生成时间戳（毫秒）
     * @property tokenCount   摘要文本的 Token 估算
     */
    data class ConversationDigest(
        val totalTasks: Int,
        val successCount: Int,
        val failedCount: Int,
        val summaries: List<TaskSummary>,
        val keyFacts: List<String>,
        val followUps: List<String>,
        val generatedAt: Long,
        val tokenCount: Int
    )

    /**
     * 时间范围摘要（时间锚定摘要）。
     *
     * @property startTime  范围起始时间戳（毫秒）
     * @property endTime     范围结束时间戳（毫秒）
     * @property summaries   范围内每条任务的摘要
     * @property stats       范围内任务统计
     */
    data class TimeRangeSummary(
        val startTime: Long,
        val endTime: Long,
        val summaries: List<TaskSummary>,
        val stats: RangeStats
    )

    // =========================================================================
    //  常量
    // =========================================================================

    companion object {
        /** 中文每字 Token 系数。 */
        private const val CHINESE_TOKEN_RATIO = 1.5

        /** 英文每字符 Token 系数（4 字符 ≈ 1 Token）。 */
        private const val ENGLISH_TOKEN_RATIO = 0.25

        /** 指令截断长度（字符）。 */
        private const val COMMAND_MAX_CHARS = 30

        /** 批量摘要中最小合并条数（少于该数不合并）。 */
        private const val BATCH_MERGE_MIN = 2

        /** 压缩历史时的默认 Token 预算。 */
        private const val DEFAULT_COMPRESS_BUDGET = 200

        /** 时间格式化模板。 */
        private const val TIME_FORMAT = "HH:mm"

        // ---- 任务分类关键词 ----

        /** 应用导航关键词。 */
        private val APP_NAV_KEYWORDS = listOf(
            "打开", "启动", "开启", "运行", "关闭应用", "切换应用",
            "launch", "open", "close app"
        )

        /** 通讯消息关键词。 */
        private val COMMUNICATION_KEYWORDS = listOf(
            "发送", "发消息", "发信息", "发给", "回复", "拨打电话",
            "打电话", "通话", "send message", "call", "reply"
        )

        /** 媒体控制关键词。 */
        private val MEDIA_KEYWORDS = listOf(
            "播放", "暂停", "下一首", "上一首", "上一曲", "切歌",
            "快进", "快退", "music", "play", "pause", "next track"
        )

        /** 系统设置关键词。 */
        private val SYSTEM_SETTINGS_KEYWORDS = listOf(
            "音量", "亮度", "蓝牙", "wifi", "WiFi", "热点", "飞行模式",
            "省电", "旋转", "勿扰", "锁屏", "设置", "volume", "brightness"
        )

        /** 信息查询关键词。 */
        private val INFORMATION_KEYWORDS = listOf(
            "通知", "电量", "存储", "内存", "系统信息", "设备信息",
            "查询", "查看", "读取", "通知栏", "battery", "notification"
        )

        /** 娱乐内容关键词。 */
        private val ENTERTAINMENT_KEYWORDS = listOf(
            "视频", "游戏", "音乐", "抖音", "B站", "快手", "追剧",
            "看电影", "刷", "video", "game", "movie"
        )

        // ---- 关键事实提取正则 ----

        /** 打开应用模式：「打开/启动/开启/运行 + 应用名」。 */
        private val APP_OPEN_FACT_REGEX = Regex(
            "(?:打开|启动|开启|运行|launch|open)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?(?:里|中|里面)?",
            RegexOption.IGNORE_CASE
        )

        /** 发送消息模式：「发送/发 + N条 + 消息」或「给X发消息」。 */
        private val SEND_MESSAGE_REGEX = Regex(
            "(?:发送|发|回复)\\s*(\\d+)?\\s*(?:条)?\\s*(?:消息|信息|文本)",
            RegexOption.IGNORE_CASE
        )

        /** 搜索模式：「搜索/查找 + 关键词」。 */
        private val SEARCH_FACT_REGEX = Regex(
            "(?:搜索|查找|搜一下|search)\\s*[「「【]?(.+?)[」」】]?$",
            RegexOption.IGNORE_CASE
        )

        /** 音量调整模式。 */
        private val VOLUME_REGEX = Regex(
            "(音量|声音|volume).{0,4}(大|小|加|减|调|增|降|高|低)",
            RegexOption.IGNORE_CASE
        )

        /** 亮度调整模式。 */
        private val BRIGHTNESS_REGEX = Regex(
            "(亮度|brightness).{0,4}(调|增|降|高|低|亮|暗)",
            RegexOption.IGNORE_CASE
        )

        /** 电话拨打模式。 */
        private val CALL_REGEX = Regex(
            "(?:拨打|打|call)\\s*电话\\s*(?:给|给)?\\s*[「「【]?(.+?)[」」】]?",
            RegexOption.IGNORE_CASE
        )

        // ---- 待办/未完成检测模式 ----

        /** 标记任务未完成的关键词。 */
        private val FOLLOWUP_PATTERNS = listOf(
            "未完成", "失败", "需要登录", "请重试", "未找到", "超时",
            "需要授权", "需要权限", "请稍后重试", "无法完成", "中断",
            "尚未", "还没", "待处理", "需要确认"
        )

        /** 常见应用名称（用于应用上下文提取）。 */
        private val KNOWN_APPS = listOf(
            "微信", "抖音", "QQ", "支付宝", "淘宝", "快手", "B站", "小红书",
            "美团", "京东", "拼多多", "知乎", "微博", "钉钉", "网易云音乐",
            "QQ音乐", "高德地图", "百度地图", "今日头条", "腾讯视频", "爱奇艺",
            "设置", "飞书", "企业微信", "百度", "夸克", "豆包", "滴滴", "携程",
            "12306", "天猫", "饿了么", "大众点评", "百度网盘", "Keep",
            "喜马拉雅", "WPS", "相机", "电话", "短信", "日历", "时钟"
        )
    }

    // =========================================================================
    //  状态缓存
    // =========================================================================

    /** 最近一次生成的对话摘要（供 [getSummary] 引用）。 */
    @Volatile
    private var lastDigest: ConversationDigest? = null

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
    //  任务分类
    // =========================================================================

    /**
     * 根据用户指令和动作列表推断任务分类。
     *
     * 分类策略：按优先级匹配关键词，先匹配到的类别优先返回。
     * 优先级：通讯消息 > 媒体控制 > 系统设置 > 应用导航 > 信息查询 > 娱乐 > 未知。
     * （通讯与媒体等具体操作优先于「打开应用」这一通用前缀，避免「打开微信发消息」
     * 被误判为应用导航。）
     *
     * @param command 用户指令
     * @param actions 动作列表（动作名称或描述）
     * @return 任务分类
     */
    fun categorizeTask(command: String, actions: List<String>): TaskCategory {
        val combined = (command + " " + actions.joinToString(" ")).lowercase()

        // 通讯消息（含动作类型判断）
        if (COMMUNICATION_KEYWORDS.any { combined.contains(it.lowercase()) } ||
            actions.any { it.contains("SCREEN_INPUT", ignoreCase = true) && combined.contains("消息") }
        ) {
            return TaskCategory.COMMUNICATION
        }

        // 媒体控制
        if (MEDIA_KEYWORDS.any { combined.contains(it.lowercase()) } ||
            actions.any { it.contains("MEDIA_CONTROL", ignoreCase = true) }
        ) {
            return TaskCategory.MEDIA
        }

        // 系统设置
        if (SYSTEM_SETTINGS_KEYWORDS.any { combined.contains(it.lowercase()) } ||
            actions.any {
                it.contains("SYSTEM_SET_VOLUME", ignoreCase = true) ||
                it.contains("SYSTEM_SET_BRIGHTNESS", ignoreCase = true)
            }
        ) {
            return TaskCategory.SYSTEM_SETTINGS
        }

        // 应用导航
        if (APP_NAV_KEYWORDS.any { combined.contains(it.lowercase()) } ||
            actions.any { it.contains("APP_OPEN", ignoreCase = true) }
        ) {
            return TaskCategory.APP_NAVIGATION
        }

        // 信息查询
        if (INFORMATION_KEYWORDS.any { combined.contains(it.lowercase()) } ||
            actions.any {
                it.contains("SYSTEM_GET_INFO", ignoreCase = true) ||
                it.contains("NOTIFY_READ", ignoreCase = true) ||
                it.contains("SCREEN_GET_TEXT", ignoreCase = true)
            }
        ) {
            return TaskCategory.INFORMATION
        }

        // 娱乐
        if (ENTERTAINMENT_KEYWORDS.any { combined.contains(it.lowercase()) }) {
            return TaskCategory.ENTERTAINMENT
        }

        return TaskCategory.UNKNOWN
    }

    // =========================================================================
    //  应用上下文提取
    // =========================================================================

    /**
     * 从指令和动作中提取涉及的应用名称。
     *
     * 优先从指令中匹配已知应用名，其次尝试从动作文本中提取包名末段。
     *
     * @param command 用户指令
     * @param actions 动作列表
     * @return 应用名称（如「微信」），无匹配返回 null
     */
    private fun extractAppContext(command: String, actions: List<String>): String? {
        // 1. 从指令中匹配已知应用名
        for (app in KNOWN_APPS) {
            if (command.contains(app)) return app
        }
        // 2. 从动作文本中查找已知应用名
        val actionsText = actions.joinToString(" ")
        for (app in KNOWN_APPS) {
            if (actionsText.contains(app)) return app
        }
        // 3. 尝试从动作文本中提取 APP_OPEN 的目标（如 "打开XXX"）
        APP_OPEN_FACT_REGEX.find(command)?.let { match ->
            val name = match.groupValues.getOrNull(1)?.trim()?.take(10)
            if (!name.isNullOrEmpty() && name.length >= 2) return name
        }
        return null
    }

    // =========================================================================
    //  关键事实提取
    // =========================================================================

    /**
     * 从对话历史中提取关键事实。
     *
     * 关键事实是对「发生了什么」的简练描述，格式如：
     * - 「打开了微信」
     * - 「发送了3条消息」
     * - 「搜索了猫咪」
     * - 「调整了音量」
     * - 「拨打了电话给张三」
     *
     * 每条记忆条目最多提取一个最相关的关键事实，按时间顺序输出。
     * 相同事实会被合并并计数（如两次「打开了微信」合并为「打开了微信x2」）。
     *
     * @param entries 对话历史条目列表
     * @return 关键事实列表（已去重合并）
     */
    fun extractKeyFacts(entries: List<ConversationMemory.MemoryEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()

        // 按出现顺序收集，随后合并相同事实
        val factCounts = linkedMapOf<String, Int>()

        for (entry in entries) {
            val fact = extractSingleFact(entry)
            if (fact != null) {
                factCounts[fact] = (factCounts[fact] ?: 0) + 1
            }
        }

        // 合并计数：出现多次的事实追加「xN」
        return factCounts.map { (fact, count) ->
            if (count > 1) "$fact x$count" else fact
        }
    }

    /**
     * 从单条记忆条目中提取一个关键事实。
     *
     * 按优先级匹配：发送消息 > 搜索 > 打开应用 > 音量 > 亮度 > 电话 > 其他。
     *
     * @param entry 对话记忆条目
     * @return 关键事实文本，无匹配返回 null
     */
    private fun extractSingleFact(entry: ConversationMemory.MemoryEntry): String? {
        val cmd = entry.userCommand

        // 发送消息
        SEND_MESSAGE_REGEX.find(cmd)?.let { match ->
            val count = match.groupValues.getOrNull(1)
            return if (!count.isNullOrEmpty()) "发送了${count}条消息"
            else "发送了消息"
        }
        if (cmd.contains("回复") && (cmd.contains("消息") || cmd.contains("信息"))) {
            return "回复了消息"
        }

        // 搜索
        SEARCH_FACT_REGEX.find(cmd)?.let { match ->
            val keyword = match.groupValues.getOrNull(1)?.trim()
            if (!keyword.isNullOrEmpty()) return "搜索了${keyword.take(10)}"
        }
        if (cmd.contains("搜索") || cmd.contains("查找")) {
            // 未能提取具体关键词时给出泛化事实
            return "执行了搜索"
        }

        // 打开应用
        APP_OPEN_FACT_REGEX.find(cmd)?.let { match ->
            val appName = match.groupValues.getOrNull(1)?.trim()
            if (!appName.isNullOrEmpty() && appName.length >= 2) {
                return "打开了${appName.take(10)}"
            }
        }

        // 音量调整
        if (VOLUME_REGEX.containsMatchIn(cmd) || cmd.contains("音量")) {
            return "调整了音量"
        }

        // 亮度调整
        if (BRIGHTNESS_REGEX.containsMatchIn(cmd) || cmd.contains("亮度")) {
            return "调整了亮度"
        }

        // 电话拨打
        CALL_REGEX.find(cmd)?.let { match ->
            val target = match.groupValues.getOrNull(1)?.trim()
            return if (!target.isNullOrEmpty()) "拨打了电话给${target.take(10)}"
            else "拨打了电话"
        }

        // 媒体控制
        if (cmd.contains("播放") || cmd.contains("暂停")) return "控制了媒体播放"
        if (cmd.contains("下一首") || cmd.contains("上一首") || cmd.contains("切歌")) return "切换了歌曲"

        // 系统设置
        if (cmd.contains("蓝牙")) return "切换了蓝牙"
        if (cmd.contains("WiFi", ignoreCase = true) || cmd.contains("wifi", ignoreCase = true)) return "切换了WiFi"
        if (cmd.contains("飞行模式")) return "切换了飞行模式"
        if (cmd.contains("截图") || cmd.contains("截屏")) return "截取了屏幕"
        if (cmd.contains("锁屏")) return "锁定了屏幕"

        // 信息查询
        if (cmd.contains("通知")) return "查看了通知"
        if (cmd.contains("电量")) return "查询了电量"
        if (cmd.contains("系统信息") || cmd.contains("设备信息")) return "查询了系统信息"

        // 通用回退：截断指令作为事实
        return null
    }

    // =========================================================================
    //  待办/未完成任务识别
    // =========================================================================

    /**
     * 识别未完成或需要后续跟进的任务。
     *
     * 检测策略：遍历所有失败的任务，结合 [MemoryEntry.summary] 与
     * [MemoryEntry.userCommand] 中的关键词判断是否需要跟进。
     * 匹配模式包括：「未完成」「失败」「需要登录」「请重试」「未找到」
     * 「超时」「需要授权」「需要权限」「无法完成」「中断」等。
     *
     * 输出格式：「[时间] 指令 -> 待办原因」。
     *
     * @param entries 对话历史条目列表
     * @return 待办任务描述列表
     */
    fun identifyFollowUps(entries: List<ConversationMemory.MemoryEntry>): List<String> {
        if (entries.isEmpty()) return emptyList()

        val followUps = mutableListOf<String>()
        val timeFmt = SimpleDateFormat(TIME_FORMAT, Locale.getDefault())

        for (entry in entries) {
            // 仅关注失败或含待办关键词的任务
            val needFollowUp = !entry.success || containsFollowUpKeyword(entry.summary) ||
                containsFollowUpKeyword(entry.userCommand)
            if (!needFollowUp) continue

            val reason = identifyFollowUpReason(entry)
            val timeStr = timeFmt.format(Date(entry.timestamp))
            val cmd = entry.userCommand.take(COMMAND_MAX_CHARS)
            followUps.add("[$timeStr] $cmd -> $reason")
        }

        return followUps
    }

    /** 判断文本中是否包含待办关键词。 */
    private fun containsFollowUpKeyword(text: String): Boolean {
        return FOLLOWUP_PATTERNS.any { text.contains(it) }
    }

    /**
     * 识别单条任务的待办原因。
     *
     * @param entry 对话记忆条目
     * @return 待办原因简述
     */
    private fun identifyFollowUpReason(entry: ConversationMemory.MemoryEntry): String {
        val summary = entry.summary
        return when {
            summary.contains("需要登录") || summary.contains("登录") -> "需要登录"
            summary.contains("需要授权") || summary.contains("需要权限") || summary.contains("权限") -> "需要授权"
            summary.contains("请重试") || summary.contains("稍后重试") -> "请重试"
            summary.contains("未找到") -> "未找到目标元素"
            summary.contains("超时") -> "执行超时"
            summary.contains("未安装") -> "应用未安装"
            summary.contains("网络") -> "网络错误"
            summary.contains("中断") -> "执行中断"
            summary.contains("未完成") -> "未完成"
            !entry.success && summary.contains("失败") -> "执行失败"
            !entry.success -> "执行失败"
            else -> "待确认"
        }
    }

    // =========================================================================
    //  任务摘要生成
    // =========================================================================

    /**
     * 将单条记忆条目转换为任务摘要。
     *
     * @param entry 对话记忆条目
     * @return 任务摘要
     */
    private fun toTaskSummary(entry: ConversationMemory.MemoryEntry): TaskSummary {
        val category = categorizeTask(entry.userCommand, entry.actions)
        val appContext = extractAppContext(entry.userCommand, entry.actions)
        val result = if (entry.success) "成功" else "失败:${entry.summary.take(15)}"
        return TaskSummary(
            timestamp = entry.timestamp,
            command = entry.userCommand.take(COMMAND_MAX_CHARS),
            result = result,
            appContext = appContext,
            category = category
        )
    }

    /**
     * 生成单条任务摘要的一行文本。
     *
     * 格式：「[HH:mm] 指令 -> 结果」。
     *
     * @param summary 任务摘要
     * @return 单行文本
     */
    private fun formatTaskSummaryLine(summary: TaskSummary): String {
        val timeStr = SimpleDateFormat(TIME_FORMAT, Locale.getDefault())
            .format(Date(summary.timestamp))
        return "[$timeStr] ${summary.command} -> ${summary.result}"
    }

    // =========================================================================
    //  整体摘要
    // =========================================================================

    /**
     * 生成对话历史的整体摘要。
     *
     * 处理流程：
     * 1. 将每条记忆条目转换为 [TaskSummary]
     * 2. 统计成功/失败数量
     * 3. 提取关键事实（[extractKeyFacts]）
     * 4. 识别待办任务（[identifyFollowUps]）
     * 5. 估算摘要文本的 Token 数量
     *
     * 生成的摘要会缓存到 [lastDigest]，供 [getSummary] 引用。
     *
     * @param entries 对话历史条目列表
     * @return 对话摘要
     */
    fun summarize(entries: List<ConversationMemory.MemoryEntry>): ConversationDigest {
        if (entries.isEmpty()) {
            val emptyDigest = ConversationDigest(
                totalTasks = 0,
                successCount = 0,
                failedCount = 0,
                summaries = emptyList(),
                keyFacts = emptyList(),
                followUps = emptyList(),
                generatedAt = System.currentTimeMillis(),
                tokenCount = 0
            )
            lastDigest = emptyDigest
            return emptyDigest
        }

        val summaries = entries.map { toTaskSummary(it) }
        val successCount = entries.count { it.success }
        val failedCount = entries.size - successCount
        val keyFacts = extractKeyFacts(entries)
        val followUps = identifyFollowUps(entries)

        // 估算整体摘要文本的 Token 数
        val digestText = buildDigestText(summaries, keyFacts, followUps)
        val tokenCount = estimateTokens(digestText)

        val digest = ConversationDigest(
            totalTasks = entries.size,
            successCount = successCount,
            failedCount = failedCount,
            summaries = summaries,
            keyFacts = keyFacts,
            followUps = followUps,
            generatedAt = System.currentTimeMillis(),
            tokenCount = tokenCount
        )
        lastDigest = digest
        return digest
    }

    /**
     * 构建摘要文本（用于 Token 估算与 [getSummary]）。
     *
     * @param summaries 任务摘要列表
     * @param keyFacts  关键事实列表
     * @param followUps 待办任务列表
     * @return 摘要文本
     */
    private fun buildDigestText(
        summaries: List<TaskSummary>,
        keyFacts: List<String>,
        followUps: List<String>
    ): String {
        return buildString {
            // 批量合并后的摘要行
            val batchedLines = buildBatchedSummaryLines(summaries)
            for (line in batchedLines) {
                appendLine(line)
            }
            if (keyFacts.isNotEmpty()) {
                appendLine("关键事实: ${keyFacts.joinToString("; ")}")
            }
            if (followUps.isNotEmpty()) {
                appendLine("待办: ${followUps.joinToString("; ")}")
            }
        }.trim()
    }

    // =========================================================================
    //  批量合并
    // =========================================================================

    /**
     * 将相关任务（同一应用或同一分类）合并为批量摘要行。
     *
     * 合并策略：
     * - 同一应用上下文且同分类的连续任务合并为「[时间] 打开微信等N个任务(成功M/失败N)」
     * - 同分类但不同应用的任务合并为「[分类] N个任务(成功M/失败N)」
     * - 不足 [BATCH_MERGE_MIN] 条的分组保持单行输出
     *
     * @param summaries 任务摘要列表（按时间正序）
     * @return 合并后的摘要行列表
     */
    private fun buildBatchedSummaryLines(summaries: List<TaskSummary>): List<String> {
        if (summaries.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        val timeFmt = SimpleDateFormat(TIME_FORMAT, Locale.getDefault())

        // 1. 按应用上下文 + 分类分组（保持顺序）
        val groups = groupByContext(summaries)

        for (group in groups) {
            if (group.size < BATCH_MERGE_MIN) {
                // 不足合并阈值，逐条输出
                for (s in group) {
                    lines.add(formatTaskSummaryLine(s))
                }
            } else {
                // 合并为批量摘要
                val first = group.first()
                val last = group.last()
                val successCount = group.count { it.result.startsWith("成功") }
                val failedCount = group.size - successCount
                val timeRange = if (first.timestamp == last.timestamp) {
                    timeFmt.format(Date(first.timestamp))
                } else {
                    "${timeFmt.format(Date(first.timestamp))}~${timeFmt.format(Date(last.timestamp))}"
                }
                val contextLabel = first.appContext ?: first.category.displayName()
                lines.add(
                    "[$timeRange] ${contextLabel}等${group.size}个任务(成功$successCount/失败$failedCount)"
                )
            }
        }

        return lines
    }

    /**
     * 按应用上下文与分类对任务摘要分组（保持原始顺序）。
     *
     * 相邻且具有相同 appContext+category 的任务归为一组；
     * appContext 为 null 时按 category 分组。
     *
     * @param summaries 任务摘要列表
     * @return 分组后的列表（每组是一个子列表）
     */
    private fun groupByContext(summaries: List<TaskSummary>): List<List<TaskSummary>> {
        val groups = mutableListOf<MutableList<TaskSummary>>()
        for (s in summaries) {
            val lastGroup = groups.lastOrNull()
            if (lastGroup != null) {
                val rep = lastGroup.first()
                val sameContext = rep.appContext == s.appContext
                val sameCategory = rep.category == s.category
                if (sameContext && sameCategory) {
                    lastGroup.add(s)
                    continue
                }
            }
            groups.add(mutableListOf(s))
        }
        return groups
    }

    // =========================================================================
    //  时间范围摘要
    // =========================================================================

    /**
     * 生成指定时间范围内的对话摘要（时间锚定摘要）。
     *
     * 过滤出时间戳在 [startTime] 与 [endTime] 之间的条目，生成摘要与统计。
     * 可用于回答「最近5分钟/30分钟/1小时做了什么」。
     *
     * 注意：[startTime] 应小于等于 [endTime]，范围边界为闭区间。
     *
     * @param entries    对话历史条目列表
     * @param startTime  范围起始时间戳（毫秒）
     * @param endTime    范围结束时间戳（毫秒）
     * @return 时间范围摘要
     */
    fun summarizeTimeRange(
        entries: List<ConversationMemory.MemoryEntry>,
        startTime: Long,
        endTime: Long
    ): TimeRangeSummary {
        val filtered = entries.filter { it.timestamp in startTime..endTime }
        val summaries = filtered.map { toTaskSummary(it) }

        val successCount = filtered.count { it.success }
        val failedCount = filtered.size - successCount
        val categoryBreakdown = summaries.groupingBy { it.category }.eachCount()

        val stats = RangeStats(
            totalTasks = filtered.size,
            successCount = successCount,
            failedCount = failedCount,
            categoryBreakdown = categoryBreakdown
        )

        return TimeRangeSummary(
            startTime = startTime,
            endTime = endTime,
            summaries = summaries,
            stats = stats
        )
    }

    /**
     * 生成最近指定时长内的对话摘要。
     *
     * [durationMs] 为从当前时间向前回溯的时长（毫秒）。
     * 例如 `summarizeRecent(entries, 5 * 60 * 1000)` 生成最近 5 分钟摘要。
     *
     * @param entries    对话历史条目列表
     * @param durationMs 回溯时长（毫秒）
     * @return 时间范围摘要
     */
    fun summarizeRecent(
        entries: List<ConversationMemory.MemoryEntry>,
        durationMs: Long
    ): TimeRangeSummary {
        val now = System.currentTimeMillis()
        return summarizeTimeRange(entries, now - durationMs, now)
    }

    /**
     * 将时间范围摘要格式化为可读文本。
     *
     * @param summary 时间范围摘要
     * @return 可读文本
     */
    fun formatTimeRangeSummary(summary: TimeRangeSummary): String {
        val timeFmt = SimpleDateFormat(TIME_FORMAT, Locale.getDefault())
        val startStr = timeFmt.format(Date(summary.startTime))
        val endStr = timeFmt.format(Date(summary.endTime))

        return buildString {
            appendLine("==[$startStr~$endStr] 摘要==")
            if (summary.summaries.isEmpty()) {
                appendLine("无任务记录")
            } else {
                val batchedLines = buildBatchedSummaryLines(summary.summaries)
                for (line in batchedLines) {
                    appendLine(line)
                }
                appendLine(
                    "统计: 共${summary.stats.totalTasks}个任务" +
                    "(成功${summary.stats.successCount}/失败${summary.stats.failedCount})"
                )
                if (summary.stats.categoryBreakdown.isNotEmpty()) {
                    val breakdown = summary.stats.categoryBreakdown.entries
                        .sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key.displayName()}${it.value}" }
                    appendLine("分类: $breakdown")
                }
            }
            appendLine("==摘要结束==")
        }.trim()
    }

    // =========================================================================
    //  历史压缩
    // =========================================================================

    /**
     * 将对话历史压缩为不超过 [maxTokens] 的文本。
     *
     * 压缩策略：
     * 1. 先生成批量合并后的摘要行（同应用/同分类合并）
     * 2. 提取关键事实与待办任务
     * 3. 按优先级逐行累加：待办 > 关键事实 > 批量摘要行
     * 4. 超出预算时对最后加入的行做字符级截断
     *
     * 保证输出始终不超过 [maxTokens]（除非单行极短仍需截断）。
     *
     * @param entries   对话历史条目列表
     * @param maxTokens 最大 Token 预算，默认 [DEFAULT_COMPRESS_BUDGET]
     * @return 压缩后的文本
     */
    fun compressHistory(
        entries: List<ConversationMemory.MemoryEntry>,
        maxTokens: Int = DEFAULT_COMPRESS_BUDGET
    ): String {
        if (entries.isEmpty()) return ""

        val summaries = entries.map { toTaskSummary(it) }
        val keyFacts = extractKeyFacts(entries)
        val followUps = identifyFollowUps(entries)
        val batchedLines = buildBatchedSummaryLines(summaries)

        // 按优先级构建行列表：待办 > 关键事实 > 批量摘要
        val prioritizedLines = mutableListOf<String>()
        if (followUps.isNotEmpty()) {
            prioritizedLines.add("待办: ${followUps.joinToString("; ")}")
        }
        if (keyFacts.isNotEmpty()) {
            prioritizedLines.add("关键事实: ${keyFacts.joinToString("; ")}")
        }
        prioritizedLines.addAll(batchedLines)

        // 逐行累加，超出预算时截断
        return truncateLinesToTokens(prioritizedLines, maxTokens)
    }

    // =========================================================================
    //  摘要获取
    // =========================================================================

    /**
     * 获取最近一次生成的对话摘要文本。
     *
     * 依赖 [summarize] 缓存的 [lastDigest]。若尚未调用过 [summarize]，
     * 返回空字符串。
     *
     * @return 摘要文本，无缓存时返回空字符串
     */
    fun getSummary(): String {
        val digest = lastDigest ?: return ""
        return buildDigestText(digest.summaries, digest.keyFacts, digest.followUps)
    }

    /**
     * 获取最近一次生成的对话摘要对象。
     *
     * @return 对话摘要，无缓存时返回 null
     */
    fun getLastDigest(): ConversationDigest? = lastDigest

    // =========================================================================
    //  通用工具方法
    // =========================================================================

    /**
     * 按预算逐行累加截断文本。
     *
     * 依次加入行，当加入下一行会超出预算时停止。若首行即超出预算，
     * 则对该行做字符级截断后返回，避免出现空结果。
     *
     * @param lines     已排序的行列表
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
                // 尝试把剩余预算填满当前行
                val remaining = maxTokens - usedTokens
                if (remaining > 0) {
                    val partial = truncateToTokenBudget(line, remaining)
                    if (partial.isNotEmpty()) {
                        sb.append("\n").append(partial)
                    }
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
     * @param text      原始文本
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
}

// =============================================================================
//  TaskCategory 扩展
// =============================================================================

/**
 * [ConversationSummarizer.TaskCategory] 的中文显示名称扩展。
 */
private fun ConversationSummarizer.TaskCategory.displayName(): String = when (this) {
    ConversationSummarizer.TaskCategory.APP_NAVIGATION -> "应用导航"
    ConversationSummarizer.TaskCategory.COMMUNICATION -> "通讯"
    ConversationSummarizer.TaskCategory.MEDIA -> "媒体"
    ConversationSummarizer.TaskCategory.SYSTEM_SETTINGS -> "系统设置"
    ConversationSummarizer.TaskCategory.INFORMATION -> "信息查询"
    ConversationSummarizer.TaskCategory.ENTERTAINMENT -> "娱乐"
    ConversationSummarizer.TaskCategory.UNKNOWN -> "其他"
}
