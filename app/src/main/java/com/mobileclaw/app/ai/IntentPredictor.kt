package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 意图预测器 —— 基于多维信号预测用户的下一个可能操作。
 *
 * 核心理念：用户的手机操作存在强烈的规律性——早晨习惯查看消息、
 * 午间习惯点外卖、晚间习惯刷短视频。如果能提前预测用户接下来要做什么，
 * 就可以预热相关应用、预采集屏幕状态、甚至直接给出快捷指令建议，
 * 从而将「用户开口 → 系统执行」的感知延迟进一步压缩。
 *
 * 预测信号来源（加权融合，权重之和为 1.0）：
 * 1. 时段模式（权重 0.35）：一天 24 小时中，每个小时用户最常执行的指令。
 *    例如 8 点常「打开微信」、12 点常「打开美团」、21 点常「打开抖音」。
 * 2. 应用上下文序列（权重 0.35）：在某个应用中，用户紧接着最常执行的指令。
 *    例如在微信中常「搜索联系人」、在抖音中常「搜索」。
 * 3. 指令频率（权重 0.15）：全局范围内用户最常执行的指令（长期偏好基线）。
 * 4. 近期历史（权重 0.15）：最近若干次操作中重复出现的指令（短期惯性）。
 *
 * 时段划分（用于模式聚合与可读性说明）：
 * - 早晨日常（6-8 点）：查看消息、日程、新闻
 * - 工作时段（9-17 点）：通讯、办公、搜索
 * - 晚间休闲（18-21 点）：娱乐、购物、社交
 * - 夜间模式（22-5 点）：阅读、音乐、助眠
 *
 * 衰减与淘汰：
 * - 模式权重随「最后使用时间」线性衰减，7 天未使用则权重降为 0（失去影响力）。
 * - 定时清理协程定期移除已完全衰减的陈旧模式，释放存储空间。
 * - 全部模式总量上限 100 条，超出时按 LRU（最久未使用）淘汰最旧条目。
 *
 * 线程安全：
 * - 所有存储使用 [ConcurrentHashMap] / [ConcurrentLinkedDeque]，可被多线程并发调用。
 * - 统计计数使用 @Volatile + private set，保证可见性的同时禁止外部修改。
 * - 典型场景：UI 线程记录指令、后台协程执行预测与定时清理。
 *
 * 使用方式：
 * ```
 * val predictor = IntentPredictor()
 * // 每次执行指令后记录
 * predictor.recordCommand("打开微信", System.currentTimeMillis(), "com.android.launcher")
 * // 需要预测时调用
 * val result = predictor.predictNext("com.tencent.mm", 21)
 * result?.let { showSuggestion(it.predictedCommand, it.confidence) }
 * ```
 */
class IntentPredictor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val tag = "IntentPredictor"

    // ============================================================
    // 数据类定义
    // ============================================================

    /** 预测结果。 */
    data class PredictionResult(
        /** 预测的指令文本。 */
        val predictedCommand: String,
        /** 置信度（0.0-1.0），越高越可信。 */
        val confidence: Float,
        /** 预测依据的人类可读说明（如「时段匹配(晚间休闲) + 应用上下文(微信)」）。 */
        val reason: String
    )

    /** 时段模式记录：某个小时点某条指令的出现频率。 */
    data class TimePattern(
        /** 小时（0-23）。 */
        val hourOfDay: Int,
        /** 指令文本。 */
        val command: String,
        /** 出现次数。 */
        var frequency: Int = 0,
        /** 最后一次记录的时间戳（毫秒），用于衰减计算。 */
        var lastUsed: Long = System.currentTimeMillis()
    )

    /** 应用上下文序列记录：从某个应用上下文出发，紧接着执行的指令频率。 */
    data class AppTransition(
        /** 起始应用上下文（包名或名称，桌面记为 "desktop"）。 */
        val fromApp: String,
        /** 紧接着执行的指令（目标指令/动作）。 */
        val toApp: String,
        /** 出现次数。 */
        var frequency: Int = 0,
        /** 最后一次记录的时间戳（毫秒），用于衰减计算。 */
        var lastUsed: Long = System.currentTimeMillis()
    )

    /** 全局指令频率记录。 */
    data class CommandFrequency(
        val command: String,
        var count: Int = 0,
        var lastUsed: Long = System.currentTimeMillis()
    )

    /** 近期历史条目（内部使用）。 */
    private data class HistoryEntry(
        val command: String,
        val timestamp: Long,
        val appContext: String?
    )

    /** 评分中间结果（内部使用）。 */
    private data class ScoredCandidate(
        val command: String,
        val score: Float,
        val reason: String
    )

    // ============================================================
    // 时段定义
    // ============================================================

    /** 一天中的时段划分，用于时段模式聚合与可读性说明。 */
    enum class TimeSegment(val displayName: String) {
        /** 早晨日常（6-8 点）：查看消息、日程、新闻。 */
        MORNING_ROUTINE("早晨日常"),

        /** 工作时段（9-17 点）：通讯、办公、搜索。 */
        WORK_HOURS("工作时段"),

        /** 晚间休闲（18-21 点）：娱乐、购物、社交。 */
        EVENING_LEISURE("晚间休闲"),

        /** 夜间模式（22-5 点）：阅读、音乐、助眠。 */
        NIGHT_MODE("夜间模式");

        companion object {
            /** 根据小时（0-23）获取所属时段。 */
            fun forHour(hour: Int): TimeSegment = when (hour) {
                in 6..8 -> MORNING_ROUTINE
                in 9..17 -> WORK_HOURS
                in 18..21 -> EVENING_LEISURE
                else -> NIGHT_MODE
            }
        }
    }

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 时段模式存储，键 = "hour|command"。 */
    private val timePatterns = ConcurrentHashMap<String, TimePattern>()

    /** 应用上下文序列存储，键 = "fromApp|toApp"。 */
    private val appTransitions = ConcurrentHashMap<String, AppTransition>()

    /** 全局指令频率存储，键 = command。 */
    private val commandFrequencies = ConcurrentHashMap<String, CommandFrequency>()

    /** 近期操作历史（队首为最新，最多保留 [maxHistorySize] 条）。 */
    private val recentHistory = ConcurrentLinkedDeque<HistoryEntry>()

    // ============================================================
    // 配置常量
    // ============================================================

    /** 模式总量上限（LRU 淘汰）。 */
    private val maxPatterns = 100

    /** 近期历史最大保留条数。 */
    private val maxHistorySize = 50

    /** 衰减周期（天）：超过则权重降为 0。 */
    private val decayDays = 7L

    /** 一天的毫秒数。 */
    private val dayMs = 24L * 60 * 60 * 1000

    /** 定时清理间隔（毫秒），默认 6 小时。 */
    private val cleanupIntervalMs = 6L * 60 * 60 * 1000

    /** 最低有效置信度阈值，低于此值视为无可靠预测。 */
    private val minConfidence = 0.1f

    // 信号权重（之和为 1.0）
    private val weightTime = 0.35f
    private val weightApp = 0.35f
    private val weightFrequency = 0.15f
    private val weightRecent = 0.15f

    // ============================================================
    // 统计计数
    // ============================================================

    /** 累计记录指令次数。 */
    @Volatile
    var totalRecorded: Int = 0
        private set

    /** 累计预测次数。 */
    @Volatile
    var totalPredictions: Int = 0
        private set

    /** 预测命中次数（由外部通过 [markHit] 反馈）。 */
    @Volatile
    var hitCount: Int = 0
        private set

    /** 定时清理协程 Job。 */
    private var cleanupJob: Job? = null

    // ============================================================
    // 初始化与生命周期
    // ============================================================

    init {
        startPeriodicCleanup()
    }

    /** 启动定时清理协程，周期性移除已衰减的陈旧模式。 */
    private fun startPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                try {
                    cleanup()
                } catch (e: Exception) {
                    Log.w(tag, "定时清理异常: ${e.message}")
                }
            }
        }
    }

    /**
     * 取消定时清理协程（通常在组件销毁时调用）。
     * 注意：此方法不会清空已学习的模式数据。
     */
    fun dispose() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    // ============================================================
    // 记录
    // ============================================================

    /**
     * 记录一次用户指令，用于学习行为模式。
     *
     * 该方法是预测器的唯一数据输入，每次用户执行指令后都应调用。
     * 内部会同时更新时段模式、应用上下文序列、全局频率与近期历史。
     *
     * @param command 指令文本（如「打开微信」「搜索猫咪」）
     * @param timestamp 执行时间戳（毫秒）
     * @param appContext 执行时的前台应用包名/名称，可为 null（桌面）
     */
    fun recordCommand(command: String, timestamp: Long, appContext: String?) {
        val normalized = command.trim()
        if (normalized.isEmpty()) return

        val hour = hourOf(timestamp)
        val appKey = appContext?.trim()?.takeIf { it.isNotEmpty() } ?: "desktop"

        totalRecorded++

        // 1. 时段模式：记录「该小时 + 该指令」的出现次数
        val timeKey = "$hour|$normalized"
        timePatterns.compute(timeKey) { _, existing ->
            if (existing == null) {
                TimePattern(hour, normalized, 1, timestamp)
            } else {
                existing.apply {
                    frequency++
                    lastUsed = timestamp
                }
            }
        }

        // 2. 应用上下文序列：记录「在该应用中紧接着执行该指令」的次数
        val transitionKey = "$appKey|$normalized"
        appTransitions.compute(transitionKey) { _, existing ->
            if (existing == null) {
                AppTransition(appKey, normalized, 1, timestamp)
            } else {
                existing.apply {
                    frequency++
                    lastUsed = timestamp
                }
            }
        }

        // 3. 全局指令频率
        commandFrequencies.compute(normalized) { _, existing ->
            if (existing == null) {
                CommandFrequency(normalized, 1, timestamp)
            } else {
                existing.apply {
                    count++
                    lastUsed = timestamp
                }
            }
        }

        // 4. 近期历史（队首为最新），超出容量时从队尾丢弃最旧条目
        recentHistory.addFirst(HistoryEntry(normalized, timestamp, appContext))
        while (recentHistory.size > maxHistorySize) {
            recentHistory.pollLast()
        }

        // 5. 模式总量超限时按 LRU 淘汰
        evictIfNeeded()

        Log.d(tag, "记录指令: $normalized @${hour}时 [$appKey]")
    }

    // ============================================================
    // 预测
    // ============================================================

    /**
     * 预测用户的下一个可能指令。
     *
     * 预测流程：
     * 1. 从四个信号源收集候选指令集合。
     * 2. 对每个候选计算加权得分（时段 + 应用上下文 + 频率 + 近期）。
     * 3. 对时段、应用、频率信号应用时间衰减（7 天衰减至 0）。
     * 4. 取最高分候选，若达到 [minConfidence] 阈值则返回，否则返回 null。
     *
     * @param currentApp 当前前台应用包名/名称，可为 null（桌面）
     * @param timeOfDay 当前小时（0-23，超出会自动取模）
     * @return 预测结果，数据不足或置信度过低时返回 null
     */
    fun predictNext(currentApp: String?, timeOfDay: Int): PredictionResult? {
        totalPredictions++
        val appKey = currentApp?.trim()?.takeIf { it.isNotEmpty() } ?: "desktop"
        val hour = ((timeOfDay % 24) + 24) % 24

        val ranked = scoreCandidates(hour, appKey)
        val best = ranked.firstOrNull() ?: return null

        if (best.score < minConfidence) {
            Log.d(tag, "预测置信度不足: ${best.command} (${best.score})")
            return null
        }

        Log.d(tag, "预测: ${best.command} (${"%.1f".format(best.score * 100)}%) - ${best.reason}")
        return PredictionResult(best.command, best.score, best.reason)
    }

    /**
     * 预测排名前 N 的候选指令（用于展示多个快捷建议）。
     *
     * @param currentApp 当前前台应用包名/名称，可为 null（桌面）
     * @param timeOfDay 当前小时（0-23）
     * @param n 返回的最大条数，默认 3
     * @return 按置信度降序排列的预测结果列表（仅包含达到阈值者）
     */
    fun predictTopN(currentApp: String?, timeOfDay: Int, n: Int = 3): List<PredictionResult> {
        val appKey = currentApp?.trim()?.takeIf { it.isNotEmpty() } ?: "desktop"
        val hour = ((timeOfDay % 24) + 24) % 24
        return scoreCandidates(hour, appKey)
            .take(n)
            .filter { it.score >= minConfidence }
            .map { PredictionResult(it.command, it.score, it.reason) }
    }

    /**
     * 对所有候选指令进行加权评分。
     *
     * 评分公式：score = timeScore×0.35 + appScore×0.35 + freqScore×0.15 + recentScore×0.15
     * 各分项均归一化到 [0,1]，因此总分也落在 [0,1] 区间，可直接作为置信度。
     *
     * @param hour 当前小时（0-23）
     * @param appKey 当前应用上下文键
     * @return 按得分降序排列的候选列表
     */
    private fun scoreCandidates(hour: Int, appKey: String): List<ScoredCandidate> {
        // 1. 收集候选指令（合并四个信号源的去重集合）
        val candidates = LinkedHashSet<String>()
        timePatterns.values.forEach { if (it.hourOfDay == hour) candidates.add(it.command) }
        appTransitions.values.forEach { if (it.fromApp == appKey) candidates.add(it.toApp) }
        candidates.addAll(commandFrequencies.keys)
        recentHistory.forEach { candidates.add(it.command) }
        if (candidates.isEmpty()) return emptyList()

        // 2. 预计算各信号的归一化分母
        // 时段：精确小时的最大频率
        val maxTimeFreq = timePatterns.values
            .filter { it.hourOfDay == hour }
            .maxOfOrNull { it.frequency } ?: 0

        // 时段：同时段（回退用）按指令聚合的衰减频率之和
        val segmentHours = hoursInSegment(TimeSegment.forHour(hour)).toSet()
        val segmentSums = HashMap<String, Float>()
        timePatterns.values.forEach { tp ->
            if (tp.hourOfDay in segmentHours) {
                segmentSums.merge(tp.command, tp.frequency * decayFactor(tp.lastUsed)) { a, b -> a + b }
            }
        }
        val maxSegmentSum = segmentSums.values.maxOrNull() ?: 0f

        // 应用上下文：从当前应用出发的最大转移频率
        val maxAppFreq = appTransitions.values
            .filter { it.fromApp == appKey }
            .maxOfOrNull { it.frequency } ?: 0

        // 全局频率：所有指令中的最大计数
        val maxGlobalFreq = commandFrequencies.values.maxOfOrNull { it.count } ?: 0

        // 近期历史
        val historyList = recentHistory.toList()
        val historySize = historyList.size.coerceAtLeast(1)

        // 3. 逐个候选评分
        val results = ArrayList<ScoredCandidate>(candidates.size)
        for (cmd in candidates) {
            val timeScore = computeTimeScore(cmd, hour, maxTimeFreq, segmentSums, maxSegmentSum)
            val appScore = computeAppScore(cmd, appKey, maxAppFreq)
            val freqScore = computeFreqScore(cmd, maxGlobalFreq)
            val recentScore = historyList.count { it.command == cmd }.toFloat() / historySize

            val score = timeScore * weightTime +
                    appScore * weightApp +
                    freqScore * weightFrequency +
                    recentScore * weightRecent

            if (score <= 0f) continue
            results.add(
                ScoredCandidate(
                    command = cmd,
                    score = score.coerceIn(0f, 1f),
                    reason = buildReason(cmd, timeScore, appScore, freqScore, recentScore, hour, appKey)
                )
            )
        }
        return results.sortedByDescending { it.score }
    }

    /**
     * 计算时段信号得分。
     *
     * 优先使用精确小时数据；当当前小时无任何历史时，
     * 回退到同时段聚合（权重减半，体现跨小时的不确定性）。
     */
    private fun computeTimeScore(
        cmd: String,
        hour: Int,
        maxTimeFreq: Int,
        segmentSums: Map<String, Float>,
        maxSegmentSum: Float
    ): Float {
        // 精确小时命中
        if (maxTimeFreq > 0) {
            val tp = timePatterns["$hour|$cmd"] ?: return 0f
            return tp.frequency * decayFactor(tp.lastUsed) / maxTimeFreq
        }
        // 回退到同时段聚合（权重减半）
        if (maxSegmentSum > 0f) {
            val sum = segmentSums[cmd] ?: 0f
            return (sum / maxSegmentSum) * 0.5f
        }
        return 0f
    }

    /** 计算应用上下文信号得分。 */
    private fun computeAppScore(cmd: String, appKey: String, maxAppFreq: Int): Float {
        if (maxAppFreq <= 0) return 0f
        val at = appTransitions["$appKey|$cmd"] ?: return 0f
        return at.frequency * decayFactor(at.lastUsed) / maxAppFreq
    }

    /** 计算全局频率信号得分。 */
    private fun computeFreqScore(cmd: String, maxGlobalFreq: Int): Float {
        if (maxGlobalFreq <= 0) return 0f
        val cf = commandFrequencies[cmd] ?: return 0f
        return cf.count * decayFactor(cf.lastUsed) / maxGlobalFreq
    }

    /**
     * 构建预测依据的可读说明。
     * 仅列出贡献度较高（>0.05）的信号来源，便于 UI 展示与调试。
     */
    private fun buildReason(
        cmd: String,
        timeScore: Float,
        appScore: Float,
        freqScore: Float,
        recentScore: Float,
        hour: Int,
        appKey: String
    ): String {
        val parts = ArrayList<String>()
        val segment = TimeSegment.forHour(hour)
        if (timeScore > 0.05f) parts.add("时段匹配(${segment.displayName})")
        if (appScore > 0.05f) parts.add("应用上下文($appKey)")
        if (freqScore > 0.05f) parts.add("高频偏好")
        if (recentScore > 0.05f) parts.add("近期重复")
        val detail = if (parts.isEmpty()) "综合推断" else parts.joinToString(" + ")
        return "$detail → $cmd"
    }

    // ============================================================
    // 衰减、淘汰与清理
    // ============================================================

    /**
     * 计算时间衰减因子。
     *
     * 线性衰减：刚使用时为 1.0，经过 [decayDays] 天降为 0.0。
     * 超过 [decayDays] 的模式贡献为 0，随后由 [cleanup] 移除。
     *
     * @param lastUsed 模式最后使用时间戳
     * @return 衰减因子（0.0-1.0）
     */
    private fun decayFactor(lastUsed: Long): Float {
        val ageDays = (System.currentTimeMillis() - lastUsed).toDouble() / dayMs
        val factor = 1.0 - (ageDays / decayDays).coerceIn(0.0, 1.0)
        return factor.toFloat()
    }

    /**
     * 模式总量超限时按 LRU 淘汰最旧条目。
     * 仅在每次 [recordCommand] 后调用，通常只需移除少量条目。
     */
    private fun evictIfNeeded() {
        val total = timePatterns.size + appTransitions.size + commandFrequencies.size
        if (total <= maxPatterns) return
        val toRemove = total - maxPatterns
        collectSlots()
            .sortedBy { it.first }
            .take(toRemove)
            .forEach { it.second() }
        Log.d(tag, "LRU 淘汰: 移除 $toRemove 条最旧模式")
    }

    /**
     * 清理已完全衰减的陈旧模式（超过 [decayDays] 天未使用）。
     * 由定时协程周期性调用，也可手动触发。
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val maxAge = decayDays * dayMs
        var removed = 0
        for ((lastUsed, remover) in collectSlots()) {
            if (now - lastUsed > maxAge) {
                remover()
                removed++
            }
        }
        if (removed > 0) {
            Log.d(tag, "清理过期模式: $removed 条")
        }
    }

    /**
     * 收集所有模式的「最后使用时间 + 移除回调」，用于 LRU 淘汰与过期清理。
     * 返回 Pair 列表：first = lastUsed，second = 移除该条目的回调。
     */
    private fun collectSlots(): List<Pair<Long, () -> Unit>> {
        val slots = ArrayList<Pair<Long, () -> Unit>>(
            timePatterns.size + appTransitions.size + commandFrequencies.size
        )
        timePatterns.forEach { (k, v) -> slots.add(v.lastUsed to { timePatterns.remove(k) }) }
        appTransitions.forEach { (k, v) -> slots.add(v.lastUsed to { appTransitions.remove(k) }) }
        commandFrequencies.forEach { (k, v) -> slots.add(v.lastUsed to { commandFrequencies.remove(k) }) }
        return slots
    }

    // ============================================================
    // 统计与查询
    // ============================================================

    /**
     * 反馈预测命中（当预测的指令与用户实际执行的下一个指令一致时调用）。
     * 用于计算预测准确率。
     */
    fun markHit() {
        hitCount++
    }

    /**
     * 获取预测器统计摘要（用于 UI 展示与调试）。
     *
     * 包含模式总量、各存储维度条目数、高频指令 Top3、预测命中率。
     */
    fun getPredictionSummary(): String {
        val total = timePatterns.size + appTransitions.size + commandFrequencies.size
        val topCommands = commandFrequencies.values
            .sortedByDescending { it.count }
            .take(3)
            .joinToString("、") { "${it.command}(${it.count})" }
            .ifEmpty { "无" }
        val hitRate = if (totalPredictions > 0) {
            "%.1f%%".format(hitCount.toFloat() / totalPredictions * 100)
        } else {
            "N/A"
        }
        return "预测器: 模式${total}/${maxPatterns} | 时段${timePatterns.size} " +
                "应用${appTransitions.size} 频率${commandFrequencies.size} " +
                "历史${recentHistory.size} | 常用: $topCommands | 命中率: $hitRate"
    }

    /** 获取所有时段模式（按频率降序，用于 UI 展示）。 */
    fun getTimePatterns(): List<TimePattern> =
        timePatterns.values.sortedByDescending { it.frequency }

    /** 获取所有应用上下文序列（按频率降序，用于 UI 展示）。 */
    fun getAppTransitions(): List<AppTransition> =
        appTransitions.values.sortedByDescending { it.frequency }

    /** 获取全局指令频率列表（按计数降序，用于 UI 展示）。 */
    fun getCommandFrequencies(): List<CommandFrequency> =
        commandFrequencies.values.sortedByDescending { it.count }

    /** 获取近期历史指令列表（按时间倒序，最新的在前）。 */
    fun getRecentCommands(): List<String> =
        recentHistory.map { it.command }

    /** 获取预测命中率（0.0-1.0）。 */
    fun hitRate(): Float {
        val total = totalPredictions
        return if (total > 0) hitCount.toFloat() / total else 0f
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 从时间戳提取本地小时（0-23）。 */
    private fun hourOf(timestamp: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return calendar.get(Calendar.HOUR_OF_DAY)
    }

    /** 获取某时段包含的所有小时列表（夜间模式跨午夜，需拼接两段）。 */
    private fun hoursInSegment(segment: TimeSegment): List<Int> = when (segment) {
        TimeSegment.MORNING_ROUTINE -> (6..8).toList()
        TimeSegment.WORK_HOURS -> (9..17).toList()
        TimeSegment.EVENING_LEISURE -> (18..21).toList()
        TimeSegment.NIGHT_MODE -> (22..23).toList() + (0..5).toList()
    }

    // ============================================================
    // 重置
    // ============================================================

    /** 清空所有学习数据与统计计数（定时清理协程不会被取消）。 */
    fun clear() {
        timePatterns.clear()
        appTransitions.clear()
        commandFrequencies.clear()
        recentHistory.clear()
        totalRecorded = 0
        totalPredictions = 0
        hitCount = 0
        Log.d(tag, "已清空所有预测数据")
    }
}
