package com.mobileclaw.app.ai

import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户画像 —— 学习并存储用户使用习惯，实现个性化手机控制。
 *
 * 核心理念：每个用户的使用习惯各不相同——有人偏爱简洁回复，有人需要详细指引；
 * 有人常用微信搜索，有人习惯在抖音刷视频。本系统持续学习用户行为模式，
 * 将个性化信息注入 AI 提示词，让 AI 的回复和操作更贴合用户预期。
 *
 * 六大画像维度：
 * 1. 常用应用追踪：记录每个应用的打开次数、最后使用时间和平均会话时长，
 *    识别用户最常用的应用（如微信、抖音、支付宝）。
 * 2. 使用时段模式：将一天划分为六个时段，统计各时段的活跃度，
 *    判断用户是"早起型"还是"夜猫型"。
 * 3. 常见操作序列：记录连续指令的转移关系（如"打开微信→发消息"），
 *    发现用户的高频操作链，用于预判下一步操作。
 * 4. 语言偏好检测：根据指令中的中英文字符比例，判断用户偏好中文还是英文，
 *    指导 AI 使用匹配的语言回复。
 * 5. 回复详略偏好：根据指令长度分布，判断用户偏好简洁还是详细的回复风格，
 *    动态调整 AI 回复的详尽程度。
 * 6. 应用级行为模式：记录每个应用内的典型操作（如抖音多滑动、微信多搜索），
 *    实现应用感知的个性化建议。
 *
 * 线程安全：所有存储使用 [ConcurrentHashMap]，统计计数使用 @Volatile，
 * 操作序列追踪使用同步锁保证原子性。
 *
 * 容量限制：最多 200 条应用统计、50 条偏好、30 条操作序列，超出时按 LRU 淘汰。
 */
class UserProfile {

    // ==========================================================================
    // 数据类定义
    // ==========================================================================

    /**
     * 应用使用统计。
     *
     * @property packageName        应用包名
     * @property openCount          打开次数
     * @property lastUsed           最后使用时间戳（毫秒）
     * @property avgSessionDuration 平均会话时长（毫秒，使用 EMA 平滑）
     */
    data class AppUsageStat(
        val packageName: String,
        var openCount: Int = 0,
        var lastUsed: Long = System.currentTimeMillis(),
        var avgSessionDuration: Long = 0L
    )

    /**
     * 时段使用统计。
     *
     * @property slot          使用时段
     * @property activityCount 活跃次数（该时段内的指令计数）
     */
    data class TimeSlotUsage(
        val slot: TimeSlot,
        var activityCount: Int = 0
    )

    /**
     * 用户偏好。
     *
     * @property key        偏好键（如 "language"、"response_style"）
     * @property value      偏好值
     * @property confidence 置信度（0-1，样本越多越高）
     */
    data class UserPreference(
        val key: String,
        var value: String,
        var confidence: Float = 0f
    )

    /**
     * 操作序列模式（连续指令的转移关系）。
     *
     * @property sequence 归一化后的指令序列（如 ["打开微信", "发消息"]）
     * @property count    出现次数
     * @property lastUsed 最后出现时间戳（毫秒）
     */
    data class OperationSequence(
        val sequence: List<String>,
        var count: Int = 0,
        var lastUsed: Long = System.currentTimeMillis()
    )

    /**
     * 应用级行为模式。
     *
     * @property packageName 应用包名
     * @property behaviors    行为类型 -> 出现次数（如 "scroll" -> 15, "search" -> 3）
     */
    data class AppBehaviorPattern(
        val packageName: String,
        val behaviors: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
    )

    // ==========================================================================
    // 枚举定义
    // ==========================================================================

    /**
     * 使用时段。
     *
     * 将一天划分为六个时段，用于统计用户的活跃时间分布。
     *
     * @property startHour   起始小时（含）
     * @property endHour     结束小时（不含）
     * @property displayName 中文显示名
     */
    enum class TimeSlot(val startHour: Int, val endHour: Int, val displayName: String) {
        /** 清晨：5-8 点。 */
        EARLY_MORNING(5, 8, "清晨"),
        /** 上午：8-12 点。 */
        MORNING(8, 12, "上午"),
        /** 中午：12-14 点。 */
        NOON(12, 14, "中午"),
        /** 下午：14-18 点。 */
        AFTERNOON(14, 18, "下午"),
        /** 晚上：18-22 点。 */
        EVENING(18, 22, "晚上"),
        /** 深夜：22-5 点（跨午夜）。 */
        NIGHT(22, 5, "深夜");

        companion object {
            /**
             * 根据小时数（0-23）获取对应时段。
             *
             * @param hour 小时数（24 小时制）
             * @return 对应的时段
             */
            fun fromHour(hour: Int): TimeSlot {
                return when (hour) {
                    in 5..7 -> EARLY_MORNING
                    in 8..11 -> MORNING
                    in 12..13 -> NOON
                    in 14..17 -> AFTERNOON
                    in 18..21 -> EVENING
                    else -> NIGHT // 22, 23, 0, 1, 2, 3, 4
                }
            }
        }
    }

    /**
     * 回复详略偏好。
     *
     * 基于用户指令长度分布推断：短指令占比高则偏好简洁，长指令占比高则偏好详细。
     *
     * @property displayName 中文显示名
     */
    enum class ResponseStyle(val displayName: String) {
        /** 简洁：用户倾向短指令，回复应精简直接。 */
        CONCISE("简洁"),
        /** 详细：用户倾向长指令，回复可充分展开。 */
        DETAILED("详细"),
        /** 未知：样本不足，无法判断。 */
        UNKNOWN("未知")
    }

    /**
     * 语言偏好。
     *
     * 基于指令中的中英文字符比例推断。
     *
     * @property displayName 中文显示名
     */
    enum class LanguagePreference(val displayName: String) {
        /** 中文。 */
        CHINESE("中文"),
        /** 英文。 */
        ENGLISH("英文"),
        /** 中英混合。 */
        MIXED("混合"),
        /** 未知：样本不足。 */
        UNKNOWN("未知")
    }

    // ==========================================================================
    // 存储结构（线程安全）
    // ==========================================================================

    /** 应用使用统计（包名 -> 统计）。 */
    private val appUsageStats = ConcurrentHashMap<String, AppUsageStat>()

    /** 时段使用统计（时段 -> 统计）。 */
    private val timeSlotUsages = ConcurrentHashMap<TimeSlot, TimeSlotUsage>()

    /** 用户偏好（键 -> 偏好）。 */
    private val preferences = ConcurrentHashMap<String, UserPreference>()

    /** 操作序列模式（序列键 -> 模式）。 */
    private val operationSequences = ConcurrentHashMap<String, OperationSequence>()

    /** 应用级行为模式（包名 -> 行为模式）。 */
    private val appBehaviorPatterns = ConcurrentHashMap<String, AppBehaviorPattern>()

    // ==========================================================================
    // 命令统计（语言、详略偏好）
    // ==========================================================================

    /** 总指令数。 */
    @Volatile
    private var totalCommandCount = 0

    /** 中文指令数。 */
    @Volatile
    private var chineseCommandCount = 0

    /** 英文指令数。 */
    @Volatile
    private var englishCommandCount = 0

    /** 中英混合指令数。 */
    @Volatile
    private var mixedCommandCount = 0

    /** 指令总长度（字符数），用于计算平均长度。 */
    @Volatile
    private var totalCommandLength = 0L

    /** 短指令次数（低于阈值）。 */
    @Volatile
    private var shortCommandCount = 0

    /** 长指令次数（高于阈值）。 */
    @Volatile
    private var longCommandCount = 0

    /** 上一条指令（用于操作序列追踪）。 */
    @Volatile
    private var lastCommand: String? = null

    /** 上一条指令的时间戳。 */
    @Volatile
    private var lastCommandTime: Long = 0L

    /** 操作序列追踪同步锁。 */
    private val sequenceLock = Any()

    // ==========================================================================
    // 常量
    // ==========================================================================

    /** 最大应用统计条目数。 */
    private val maxAppStats = 200

    /** 最大偏好条目数。 */
    private val maxPreferences = 50

    /** 最大操作序列条目数。 */
    private val maxSequences = 30

    /** 短指令阈值（字符数），低于此值视为短指令。 */
    private val shortCommandThreshold = 15

    /** 操作序列的时间窗口（毫秒），两条指令间隔超过此值不视为连续。 */
    private val sequenceTimeWindowMs = 120_000L

    /** 语言偏好的最小样本数。 */
    private val minLanguageSamples = 5

    /** 详略偏好的最小样本数。 */
    private val minStyleSamples = 5

    /** EMA 平滑系数（用于更新平均会话时长）。 */
    private val emaAlpha = 0.3f

    companion object {
        /** 偏好键：语言偏好。 */
        private const val KEY_LANGUAGE = "language"

        /** 偏好键：回复详略偏好。 */
        private const val KEY_RESPONSE_STYLE = "response_style"
    }

    // ==========================================================================
    // 公共方法：记录用户行为
    // ==========================================================================

    /**
     * 记录应用使用情况。
     *
     * 每次用户打开或使用某应用时调用，更新打开次数、最后使用时间，
     * 并使用 EMA（指数移动平均）平滑更新平均会话时长。
     *
     * 线程安全：使用 [ConcurrentHashMap.compute] 保证原子更新。
     *
     * @param packageName 应用包名
     * @param durationMs  本次会话时长（毫秒）
     */
    fun recordAppUsage(packageName: String, durationMs: Long) {
        if (packageName.isBlank()) return

        appUsageStats.compute(packageName) { _, existing ->
            if (existing == null) {
                AppUsageStat(
                    packageName = packageName,
                    openCount = 1,
                    lastUsed = System.currentTimeMillis(),
                    avgSessionDuration = durationMs
                )
            } else {
                existing.apply {
                    openCount++
                    lastUsed = System.currentTimeMillis()
                    // 使用 EMA 更新平均会话时长
                    avgSessionDuration = if (avgSessionDuration == 0L) {
                        durationMs
                    } else {
                        (emaAlpha * durationMs + (1 - emaAlpha) * avgSessionDuration).toLong()
                    }
                }
            }
        }

        // LRU 淘汰：超过最大条目数时移除最久未使用的
        if (appUsageStats.size > maxAppStats) {
            val oldest = appUsageStats.entries.minByOrNull { it.value.lastUsed }
            oldest?.let { appUsageStats.remove(it.key) }
        }
    }

    /**
     * 记录用户指令。
     *
     * 综合分析指令内容，更新以下画像维度：
     * - 语言偏好（中英文字符比例）
     * - 回复详略偏好（指令长度分布）
     * - 使用时段（当前时间所属时段的活跃计数）
     * - 操作序列（与上一条指令的转移关系）
     * - 应用级行为模式（如提供 currentApp 则推断操作类型）
     *
     * @param command    用户原始指令
     * @param currentApp 当前前台应用包名（可选，用于应用级行为分析）
     */
    fun recordCommand(command: String, currentApp: String? = null) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        val now = System.currentTimeMillis()
        totalCommandCount++

        // 1. 语言偏好检测
        val language = detectLanguage(trimmed)
        when (language) {
            LanguagePreference.CHINESE -> chineseCommandCount++
            LanguagePreference.ENGLISH -> englishCommandCount++
            LanguagePreference.MIXED -> mixedCommandCount++
            LanguagePreference.UNKNOWN -> {}
        }
        updateLanguagePreference()

        // 2. 回复详略偏好
        val length = trimmed.length
        totalCommandLength += length
        if (length <= shortCommandThreshold) {
            shortCommandCount++
        } else {
            longCommandCount++
        }
        updateResponseStylePreference()

        // 3. 使用时段统计
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val slot = TimeSlot.fromHour(hour)
        timeSlotUsages.compute(slot) { _, existing ->
            if (existing == null) {
                TimeSlotUsage(slot = slot, activityCount = 1)
            } else {
                existing.apply { activityCount++ }
            }
        }

        // 4. 操作序列追踪 + 更新上一条指令（加锁保证原子性）
        synchronized(sequenceLock) {
            val prevCommand = lastCommand
            val prevTime = lastCommandTime
            if (prevCommand != null && prevCommand != trimmed &&
                now - prevTime <= sequenceTimeWindowMs
            ) {
                val seqKey = "${normalizeCommand(prevCommand)} -> ${normalizeCommand(trimmed)}"
                operationSequences.compute(seqKey) { _, existing ->
                    if (existing == null) {
                        OperationSequence(
                            sequence = listOf(prevCommand, trimmed),
                            count = 1,
                            lastUsed = now
                        )
                    } else {
                        existing.apply {
                            count++
                            lastUsed = now
                        }
                    }
                }
                // LRU 淘汰
                if (operationSequences.size > maxSequences) {
                    val oldest = operationSequences.entries.minByOrNull { it.value.lastUsed }
                    oldest?.let { operationSequences.remove(it.key) }
                }
            }
            lastCommand = trimmed
            lastCommandTime = now
        }

        // 5. 应用级行为模式
        if (currentApp != null) {
            recordAppBehavior(currentApp, trimmed)
        }
    }

    // ==========================================================================
    // 公共方法：查询用户画像
    // ==========================================================================

    /**
     * 获取最常用应用列表。
     *
     * 按打开次数降序排列。
     *
     * @param limit 返回条目数上限
     * @return 应用使用统计列表（按打开次数降序）
     */
    fun getFavoriteApps(limit: Int = 10): List<AppUsageStat> {
        return appUsageStats.values
            .sortedByDescending { it.openCount }
            .take(limit)
    }

    /**
     * 获取活跃时段列表。
     *
     * 按活跃次数降序排列，可用于判断用户的作息类型。
     *
     * @return 时段使用统计列表（按活跃次数降序）
     */
    fun getActiveTimeSlots(): List<TimeSlotUsage> {
        return timeSlotUsages.values
            .sortedByDescending { it.activityCount }
    }

    /**
     * 获取高频操作序列列表。
     *
     * 按出现次数降序排列。
     *
     * @param limit 返回条目数上限
     * @return 操作序列模式列表（按出现次数降序）
     */
    fun getOperationSequences(limit: Int = 10): List<OperationSequence> {
        return operationSequences.values
            .sortedByDescending { it.count }
            .take(limit)
    }

    /**
     * 获取指定应用的行为模式。
     *
     * @param packageName 应用包名
     * @return 行为模式，不存在返回 null
     */
    fun getAppBehaviorPattern(packageName: String): AppBehaviorPattern? {
        return appBehaviorPatterns[packageName]
    }

    /**
     * 获取所有应用行为模式。
     *
     * @return 应用行为模式列表
     */
    fun getAllAppBehaviorPatterns(): List<AppBehaviorPattern> {
        return appBehaviorPatterns.values.toList()
    }

    /**
     * 获取语言偏好。
     *
     * @return 当前检测到的语言偏好，样本不足时返回 [LanguagePreference.UNKNOWN]
     */
    fun getLanguagePreference(): LanguagePreference {
        val pref = preferences[KEY_LANGUAGE]
        return pref?.value?.let { runCatching { LanguagePreference.valueOf(it) }.getOrNull() }
            ?: LanguagePreference.UNKNOWN
    }

    /**
     * 获取回复详略偏好。
     *
     * @return 当前检测到的回复风格，样本不足时返回 [ResponseStyle.UNKNOWN]
     */
    fun getResponseStyle(): ResponseStyle {
        val pref = preferences[KEY_RESPONSE_STYLE]
        return pref?.value?.let { runCatching { ResponseStyle.valueOf(it) }.getOrNull() }
            ?: ResponseStyle.UNKNOWN
    }

    /**
     * 获取用户画像摘要。
     *
     * 汇总所有维度的统计信息，用于 UI 展示和调试。
     *
     * @return 多行摘要文本
     */
    fun getProfileSummary(): String {
        return buildString {
            appendLine("===== 用户画像 =====")

            // 常用应用
            val topApps = getFavoriteApps(3)
            append("常用应用: ")
            if (topApps.isEmpty()) {
                appendLine("无数据")
            } else {
                appendLine(topApps.joinToString("、") { "${it.packageName}(${it.openCount}次)" })
            }

            // 活跃时段
            val topSlots = getActiveTimeSlots().take(3)
            append("活跃时段: ")
            if (topSlots.isEmpty()) {
                appendLine("无数据")
            } else {
                appendLine(topSlots.joinToString("、") { "${it.slot.displayName}(${it.activityCount}次)" })
            }

            // 语言偏好
            val language = getLanguagePreference()
            appendLine("语言偏好: ${language.displayName} (中文${chineseCommandCount}/英文${englishCommandCount}/混合${mixedCommandCount})")

            // 详略偏好
            val style = getResponseStyle()
            val avgLen = if (totalCommandCount > 0) totalCommandLength / totalCommandCount else 0
            appendLine("回复偏好: ${style.displayName} (短${shortCommandCount}/长${longCommandCount}/均长${avgLen})")

            // 操作序列
            appendLine("操作序列: ${operationSequences.size} 条")
            operationSequences.values
                .sortedByDescending { it.count }
                .take(3)
                .forEach { seq ->
                    appendLine("  ${seq.sequence.joinToString(" → ")} (${seq.count}次)")
                }

            // 应用行为模式
            appendLine("应用行为: ${appBehaviorPatterns.size} 个应用")
            appBehaviorPatterns.forEach { (pkg, pattern) ->
                val topBehavior = pattern.behaviors.maxByOrNull { it.value }
                if (topBehavior != null) {
                    appendLine("  $pkg: ${describeBehavior(topBehavior.key)}(${topBehavior.value}次)")
                }
            }

            append("总指令数: $totalCommandCount")
        }
    }

    // ==========================================================================
    // 公共方法：个性化提示词
    // ==========================================================================

    /**
     * 个性化 AI 提示词。
     *
     * 根据已学习的用户画像，向基础提示词中注入个性化提示信息，
     * 帮助 AI 更好地适应用户的语言习惯、回复偏好和使用模式。
     *
     * 注入内容包括：
     * - 语言偏好提示（用中文/英文回复）
     * - 回复详略提示（简洁/详细）
     * - 常用应用提示（优先匹配用户常用应用）
     * - 应用级行为模式提示（当前应用的典型操作）
     * - 活跃时段提示
     * - 高频操作链提示
     *
     * 若画像数据不足（无任何可注入信息），则原样返回基础提示词。
     *
     * @param basePrompt 基础系统提示词
     * @param currentApp 当前前台应用包名（可选，用于注入应用级行为提示）
     * @return 注入个性化信息后的提示词
     */
    fun personalizePrompt(basePrompt: String, currentApp: String? = null): String {
        val hints = mutableListOf<String>()

        // 1. 语言偏好
        when (getLanguagePreference()) {
            LanguagePreference.CHINESE -> hints.add("用户偏好中文交流，请使用中文回复")
            LanguagePreference.ENGLISH -> hints.add("User prefers English, please respond in English")
            LanguagePreference.MIXED -> hints.add("用户中英文混用，请根据用户指令语言匹配回复语言")
            LanguagePreference.UNKNOWN -> {}
        }

        // 2. 回复详略偏好
        when (getResponseStyle()) {
            ResponseStyle.CONCISE -> hints.add("用户偏好简洁回复，请尽量精简，避免冗余解释")
            ResponseStyle.DETAILED -> hints.add("用户偏好详细回复，可提供充分的操作说明和步骤指引")
            ResponseStyle.UNKNOWN -> {}
        }

        // 3. 常用应用
        val favoriteApps = getFavoriteApps(5)
        if (favoriteApps.isNotEmpty()) {
            val appHint = favoriteApps.joinToString("、") { "${it.packageName}(${it.openCount}次)" }
            hints.add("用户常用应用: $appHint")
        }

        // 4. 应用级行为模式
        if (currentApp != null) {
            val pattern = appBehaviorPatterns[currentApp]
            if (pattern != null && pattern.behaviors.isNotEmpty()) {
                val topBehavior = pattern.behaviors.maxByOrNull { it.value }
                if (topBehavior != null) {
                    hints.add(
                        "用户在 $currentApp 中常执行「${describeBehavior(topBehavior.key)}」" +
                            "（${topBehavior.value}次），可优先考虑此操作模式"
                    )
                }
            }
        }

        // 5. 活跃时段
        val activeSlots = getActiveTimeSlots()
        if (activeSlots.isNotEmpty()) {
            val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val currentSlot = TimeSlot.fromHour(currentHour)
            val topSlot = activeSlots.first()
            if (topSlot.slot == currentSlot) {
                hints.add("用户当前处于最活跃时段（${currentSlot.displayName}），响应可更积极")
            }
        }

        // 6. 高频操作链
        val topSeq = operationSequences.values.maxByOrNull { it.count }
        if (topSeq != null && topSeq.count >= 2) {
            hints.add("用户高频操作链: ${topSeq.sequence.joinToString(" → ")}（${topSeq.count}次）")
        }

        // 无可注入信息时原样返回
        if (hints.isEmpty()) return basePrompt

        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine("== 用户个性化提示 ==")
            hints.forEach { appendLine("- $it") }
        }
    }

    // ==========================================================================
    // 私有方法：语言检测与偏好更新
    // ==========================================================================

    /**
     * 检测指令的语言。
     *
     * 统计中文字符和英文字母的比例：
     * - 中文字符占比 >= 60% → 中文
     * - 英文字母占比 >= 60% → 英文
     * - 两者都有一定占比 → 混合
     * - 无字母字符 → 未知
     *
     * @param text 待检测文本
     * @return 检测到的语言偏好
     */
    private fun detectLanguage(text: String): LanguagePreference {
        var chineseCount = 0
        var englishCount = 0
        var letterTotal = 0

        for (ch in text) {
            val code = ch.code
            when {
                code in 0x4E00..0x9FFF -> {
                    chineseCount++
                    letterTotal++
                }
                ch in 'a'..'z' || ch in 'A'..'Z' -> {
                    englishCount++
                    letterTotal++
                }
            }
        }

        if (letterTotal == 0) return LanguagePreference.UNKNOWN

        val chineseRatio = chineseCount.toFloat() / letterTotal
        val englishRatio = englishCount.toFloat() / letterTotal

        return when {
            chineseRatio >= 0.6f -> LanguagePreference.CHINESE
            englishRatio >= 0.6f -> LanguagePreference.ENGLISH
            chineseCount > 0 && englishCount > 0 -> LanguagePreference.MIXED
            chineseCount > 0 -> LanguagePreference.CHINESE
            englishCount > 0 -> LanguagePreference.ENGLISH
            else -> LanguagePreference.UNKNOWN
        }
    }

    /**
     * 更新语言偏好（含置信度）。
     *
     * 样本数达到阈值后才写入偏好，置信度 = 主导语言指令数 / 总指令数。
     */
    private fun updateLanguagePreference() {
        if (totalCommandCount < minLanguageSamples) return

        val language = when {
            chineseCommandCount > englishCommandCount * 2 -> LanguagePreference.CHINESE
            englishCommandCount > chineseCommandCount * 2 -> LanguagePreference.ENGLISH
            chineseCommandCount > 0 && englishCommandCount > 0 -> LanguagePreference.MIXED
            chineseCommandCount > 0 -> LanguagePreference.CHINESE
            else -> LanguagePreference.ENGLISH
        }

        val dominant = maxOf(chineseCommandCount, englishCommandCount, mixedCommandCount)
        val confidence = dominant.toFloat() / totalCommandCount

        preferences[KEY_LANGUAGE] = UserPreference(
            key = KEY_LANGUAGE,
            value = language.name,
            confidence = confidence
        )
        enforcePreferenceLimit()
    }

    /**
     * 更新回复详略偏好（含置信度）。
     *
     * 判定规则：
     * - 短指令数 > 长指令数 × 2 → 简洁
     * - 长指令数 > 短指令数 × 2 → 详细
     * - 接近均衡时，按平均长度兜底判定
     *
     * 置信度 = 主导风格指令数 / 总指令数。
     */
    private fun updateResponseStylePreference() {
        if (totalCommandCount < minStyleSamples) return

        val style = if (shortCommandCount > longCommandCount * 2) {
            ResponseStyle.CONCISE
        } else if (longCommandCount > shortCommandCount * 2) {
            ResponseStyle.DETAILED
        } else {
            // 接近均衡时，按平均指令长度兜底
            val avgLength = if (totalCommandCount > 0) {
                totalCommandLength / totalCommandCount
            } else {
                0
            }
            if (avgLength <= shortCommandThreshold) ResponseStyle.CONCISE else ResponseStyle.DETAILED
        }

        val dominant = maxOf(shortCommandCount, longCommandCount)
        val confidence = dominant.toFloat() / totalCommandCount

        preferences[KEY_RESPONSE_STYLE] = UserPreference(
            key = KEY_RESPONSE_STYLE,
            value = style.name,
            confidence = confidence
        )
        enforcePreferenceLimit()
    }

    // ==========================================================================
    // 私有方法：应用级行为模式
    // ==========================================================================

    /**
     * 记录应用级行为模式。
     *
     * 从指令中推断操作类型（搜索、滑动、发送消息等），记录到对应应用的行为统计。
     *
     * @param packageName 应用包名
     * @param command     用户指令
     */
    private fun recordAppBehavior(packageName: String, command: String) {
        val behavior = inferBehavior(command) ?: return

        appBehaviorPatterns.compute(packageName) { _, existing ->
            if (existing == null) {
                AppBehaviorPattern(packageName = packageName).also { pattern ->
                    pattern.behaviors[behavior] = 1
                }
            } else {
                existing.apply {
                    behaviors.merge(behavior, 1) { old, inc -> old + inc }
                }
            }
        }
    }

    /**
     * 从指令中推断操作行为类型。
     *
     * 通过关键词匹配识别用户的意图操作：
     * - search：搜索、查找、查一下等
     * - scroll：滑动、滚动、浏览、刷等
     * - send_message：发送、回复、说等
     * - play：播放、听等
     * - input：输入、填写、编辑等
     * - click：点击、按等
     * - screenshot：截图、截屏等
     *
     * @param command 用户指令
     * @return 行为类型字符串，无法推断返回 null
     */
    private fun inferBehavior(command: String): String? {
        val lower = command.lowercase()
        return when {
            // 搜索类
            lower.contains("搜索") || lower.contains("查找") || lower.contains("搜一下") ||
                lower.contains("查一下") || lower.contains("search") -> "search"
            // 滑动/浏览类
            lower.contains("滑动") || lower.contains("滚动") || lower.contains("翻") ||
                lower.contains("浏览") || lower.contains("刷") || lower.contains("scroll") -> "scroll"
            // 发送消息类
            lower.contains("发送") || lower.contains("发消息") || lower.contains("发信息") ||
                lower.contains("回复") || lower.contains("说") || lower.contains("send") -> "send_message"
            // 播放类
            lower.contains("播放") || lower.contains("听") || lower.contains("play") -> "play"
            // 输入类
            lower.contains("输入") || lower.contains("填写") || lower.contains("编辑") ||
                lower.contains("type") || lower.contains("input") -> "input"
            // 点击类
            lower.contains("点击") || lower.contains("click") || lower.contains("按") -> "click"
            // 截图类
            lower.contains("截图") || lower.contains("截屏") || lower.contains("screenshot") -> "screenshot"
            else -> null
        }
    }

    /**
     * 将行为类型转换为中文描述。
     *
     * @param behavior 行为类型字符串
     * @return 中文描述
     */
    private fun describeBehavior(behavior: String): String {
        return when (behavior) {
            "search" -> "搜索"
            "scroll" -> "滑动浏览"
            "send_message" -> "发送消息"
            "play" -> "播放"
            "input" -> "输入文字"
            "click" -> "点击"
            "screenshot" -> "截图"
            else -> behavior
        }
    }

    // ==========================================================================
    // 私有方法：工具函数
    // ==========================================================================

    /**
     * 归一化指令（用于操作序列键生成）。
     *
     * 转小写、合并空白、截断长度，保证相似指令生成相同的键。
     *
     * @param command 原始指令
     * @return 归一化后的指令
     */
    private fun normalizeCommand(command: String): String {
        return command.trim().lowercase()
            .replace(Regex("[，。！？,.!?\\s]+"), " ")
            .take(30)
    }

    /**
     * 强制偏好条目数不超过上限。
     *
     * 超出时移除置信度最低的条目（偏好无时间戳，按置信度淘汰）。
     */
    private fun enforcePreferenceLimit() {
        if (preferences.size <= maxPreferences) return
        val sorted = preferences.entries.sortedBy { it.value.confidence }
        val toRemove = sorted.take(preferences.size - maxPreferences)
        toRemove.forEach { preferences.remove(it.key) }
    }

    // ==========================================================================
    // 维护方法
    // ==========================================================================

    /** 清空所有画像数据。 */
    fun clear() {
        appUsageStats.clear()
        timeSlotUsages.clear()
        preferences.clear()
        operationSequences.clear()
        appBehaviorPatterns.clear()
        totalCommandCount = 0
        chineseCommandCount = 0
        englishCommandCount = 0
        mixedCommandCount = 0
        totalCommandLength = 0L
        shortCommandCount = 0
        longCommandCount = 0
        lastCommand = null
        lastCommandTime = 0L
    }

    /**
     * 清理过期数据。
     *
     * 移除超过指定天数未使用的应用统计和操作序列，
     * 保持画像数据反映用户近期习惯。
     *
     * @param maxAgeDays 最大保留天数（默认 30 天）
     */
    fun cleanup(maxAgeDays: Int = 30) {
        val now = System.currentTimeMillis()
        val maxAge = maxAgeDays * 24 * 60 * 60 * 1000L

        // 清理过期应用统计
        val appIterator = appUsageStats.entries.iterator()
        while (appIterator.hasNext()) {
            val entry = appIterator.next()
            if (now - entry.value.lastUsed > maxAge) {
                appIterator.remove()
            }
        }

        // 清理过期操作序列
        val seqIterator = operationSequences.entries.iterator()
        while (seqIterator.hasNext()) {
            val entry = seqIterator.next()
            if (now - entry.value.lastUsed > maxAge) {
                seqIterator.remove()
            }
        }
    }
}
