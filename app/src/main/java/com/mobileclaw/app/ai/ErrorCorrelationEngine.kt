package com.mobileclaw.app.ai

import android.util.Log
import com.mobileclaw.app.ai.ActionType
import com.mobileclaw.app.ai.ClawAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 错误记录。
 *
 * 描述 MobileClaw 执行某动作时发生的一次错误，是错误关联引擎的最小分析单元。
 *
 * @property timestamp   错误发生时间戳（毫秒）
 * @property appContext  错误发生时的前台应用上下文（通常为应用包名）
 * @property actionType  出错动作的类型，对应 [ActionType]
 * @property errorMessage 原始错误信息文本（用于关键词提取与相似度计算）
 * @property errorCode   错误码（如 "NET_001"、"TIMEOUT_002"），无则为空字符串
 * @property wasResolved 该错误是否已被解决
 * @property resolution  解决方案描述（已解决时填写，未解决则为空字符串）
 */
data class ErrorRecord(
    val timestamp: Long,
    val appContext: String,
    val actionType: ActionType,
    val errorMessage: String,
    val errorCode: String,
    val wasResolved: Boolean,
    val resolution: String
)

/**
 * 根因。
 *
 * 对一个错误聚类的根因分析结果，包含根因描述、支撑证据、置信度与受影响动作。
 *
 * @property description      根因的自然语言描述
 * @property evidence          支撑该根因的证据列表（如共现次数、涉及应用、共同关键词等）
 * @property confidence        根因置信度（0.0-1.0），越高越可信
 * @property affectedActions   受该根因影响的动作类型列表（可能超出聚类自身的动作类型）
 */
data class RootCause(
    val description: String,
    val evidence: List<String>,
    val confidence: Double,
    val affectedActions: List<ActionType>
)

/**
 * 错误聚类。
 *
 * 一组「相同应用 + 相同动作类型 + 错误信息关键词高度相似」的错误记录，
 * 代表一个重复出现的错误模式。每个聚类附带根因、置信度与修复建议。
 *
 * @property id            聚类唯一标识（稳定可复现，用于 [ErrorCorrelationEngine.findRootCause] 查找）
 * @property pattern       错误模式签名（动作类型 + 应用 + 共同关键词）
 * @property errors        聚类包含的错误记录列表
 * @property rootCause     该聚类的根因分析结果（可能为 null，如证据不足时）
 * @property confidence    聚类模式置信度（0.0-1.0），反映该模式是否为真实重复模式
 * @property suggestedFix  建议的修复方案（基于历史解决经验或通用兜底建议）
 */
data class ErrorCluster(
    val id: String,
    val pattern: String,
    val errors: List<ErrorRecord>,
    val rootCause: RootCause?,
    val confidence: Double,
    val suggestedFix: String
)

/**
 * 错误相关性。
 *
 * 描述两种不同错误类型之间的时序共现关系。例如「网络错误常先于超时错误出现」。
 *
 * 注意：[errorType1] 为先发生的错误类型，[errorType2] 为后发生的错误类型，
 * [correlationScore] 表示「在 [errorType1] 出现后，[errorType2] 随之出现的条件概率」，
 * 即 `P(errorType2 | errorType1) = coOccurrenceCount / count(errorType1)`。
 *
 * @property errorType1        先发生的错误类型
 * @property errorType2        后发生的错误类型
 * @property correlationScore  相关性得分（0.0-1.0），条件概率
 * @property coOccurrenceCount  共现次数（仅在 ≥ [ErrorCorrelationEngine.MIN_COOCCURRENCE_COUNT] 时上报）
 */
data class ErrorCorrelation(
    val errorType1: String,
    val errorType2: String,
    val correlationScore: Double,
    val coOccurrenceCount: Int
)

/**
 * 错误预测。
 *
 * 预测某个动作在某个应用上下文中的失败概率及原因。
 *
 * @property actionType           被预测的动作类型
 * @property appContext            应用上下文
 * @property failureProbability   失败概率（0.0-1.0），基于历史未解决错误占比计算
 * @property reason               预测原因说明（中文，含历史统计与常见错误类型）
 */
data class ErrorPrediction(
    val actionType: ActionType,
    val appContext: String,
    val failureProbability: Double,
    val reason: String
)

// =============================================================================
//  ErrorCorrelationEngine —— 错误关联引擎
// =============================================================================

/**
 * ErrorCorrelationEngine —— 错误关联引擎
 *
 * 跨任务关联错误记录，发现重复模式、定位根因并预测失败，为执行引擎提供
 * 基于历史经验的错误洞察与修复建议。
 *
 * 核心理念：单条错误信息价值有限，但将大量历史错误关联起来分析，就能揭示
 * 隐藏的根因与规律——例如「网络错误频繁先于超时错误出现」「某应用执行点击
 * 操作时总是元素未找到」。本引擎从错误记录中提取模式、聚类、关联与根因，
 * 并据此预测失败概率与建议修复方案。
 *
 * 六大核心能力：
 * 1. **错误模式检测**：发现重复出现的错误模式（相同应用 + 相同动作类型 + 相似错误信息）。
 * 2. **错误聚类**：将相似错误按「相同 actionType + 错误信息关键词重叠度 ≥ 60%」归为一簇。
 * 3. **根因分析**：为每个聚类识别最可能的根因，输出证据、置信度与受影响动作。
 * 4. **错误预测**：基于历史模式预测某动作在某应用中的失败概率。
 * 5. **错误关联**：发现不同错误类型间的时序共现关系（如网络错误常先于超时错误），
 *    仅报告共现次数 ≥ [MIN_COOCCURRENCE_COUNT]（3）的显著关联。
 * 6. **自动修复建议**：基于「同类错误曾被何种方案解决」的历史，给出修复建议；
 *    无历史经验时给出按错误类型分类的通用兜底建议。
 *
 * ### 线程安全
 * 所有存储均使用 [ConcurrentHashMap]，计数使用 [AtomicInteger] / [AtomicLong]，
 * 可被多线程并发调用（典型场景：执行线程记录错误、分析线程周期性聚类）。
 *
 * ### 容量与淘汰
 * 最多保留 [MAX_ERROR_RECORDS]（500）条错误记录，超出时按 LRU
 * （最久未访问）策略淘汰。访问时间在记录写入与 [getTopErrors] 读取时更新。
 *
 * ### 典型调用流程
 * ```
 * val engine = ErrorCorrelationEngine()
 * // 从 ClawAction 记录错误（自动推导动作类型与应用上下文）
 * engine.recordError(action, "connection reset by peer", "NET_001")
 * // 记录已解决的错误（携带解决方案，供后续 suggestFix 学习）
 * engine.recordError(ErrorRecord(
 *     timestamp = System.currentTimeMillis(),
 *     appContext = "com.tencent.mm",
 *     actionType = ActionType.APP_OPEN,
 *     errorMessage = "timeout",
 *     errorCode = "TIMEOUT_001",
 *     wasResolved = true,
 *     resolution = "将超时时长增加至 5 秒后成功"
 * ))
 * // 聚类分析并查找根因
 * val clusters = engine.analyzeClusters()
 * val cause = clusters.firstOrNull()?.let { engine.findRootCause(it.id) }
 * // 预测失败概率
 * val prediction = engine.predictFailure(ActionType.APP_OPEN, "com.tencent.mm")
 * // 查找错误关联
 * val correlations = engine.findCorrelations()
 * // 建议修复方案
 * val fix = engine.suggestFix("network", "com.tencent.mm")
 * // 输出统计
 * println(engine.getErrorStats())
 * ```
 */
class ErrorCorrelationEngine {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 错误记录最大保留数量，超出时按 LRU 淘汰。 */
        private const val MAX_ERROR_RECORDS = 500

        /** 错误关联上报的最小共现次数阈值，低于此值不视为显著关联。 */
        private const val MIN_COOCCURRENCE_COUNT = 3

        /** 聚类相似度阈值：错误信息关键词 Jaccard 重叠度 ≥ 此值才算相似。 */
        private const val SIMILARITY_THRESHOLD = 0.6

        /** 构成一个「重复模式」所需的最小错误记录数。 */
        private const val MIN_CLUSTER_SIZE = 2

        /** 共现时间窗口（毫秒）：两错误在该时间窗口内先后出现视为共现。 */
        private const val CO_OCCURRENCE_WINDOW_MS = 5 * 60 * 1000L

        /** 置信度饱和所需的最小聚类规模（达到 10 条即满分规模因子）。 */
        private const val CONFIDENCE_SIZE_SATURATION = 10
    }

    /** 日志标签。 */
    private val tag = "ErrorCorrelationEngine"

    // =========================================================================
    //  存储结构（全部线程安全）
    // =========================================================================

    /**
     * 错误记录存储，键 = 记录唯一 ID，值 = [ErrorRecord]。
     *
     * 使用 [ConcurrentHashMap] 保证多线程并发记录与分析的安全。
     */
    private val errorRecords: ConcurrentHashMap<String, ErrorRecord> = ConcurrentHashMap()

    /**
     * 记录访问时间（毫秒），用于 LRU 淘汰。键与 [errorRecords] 一致。
     *
     * 在记录写入与 [getTopErrors] 读取时更新，淘汰时移除最久未访问的条目。
     */
    private val accessTimes: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /**
     * 聚类缓存，键 = 聚类 ID，值 = [ErrorCluster]。
     *
     * 由 [analyzeClusters] 填充，供 [findRootCause] 查询。每次重新分析会清空重建。
     */
    private val clusterCache: ConcurrentHashMap<String, ErrorCluster> = ConcurrentHashMap()

    /** 记录 ID 自增计数器，保证唯一性。 */
    private val idCounter = AtomicLong(0)

    // =========================================================================
    //  统计计数
    // =========================================================================

    /** 累计记录的错误总数（含已淘汰）。 */
    private val totalRecorded = AtomicInteger(0)

    /** 累计已解决的错误总数。 */
    private val totalResolved = AtomicInteger(0)

    /** 累计因 LRU 淘汰的错误总数。 */
    private val evictionCount = AtomicInteger(0)

    // =========================================================================
    //  记录错误
    // =========================================================================

    /**
     * 记录一条错误。
     *
     * 将错误存入线程安全存储，更新访问时间，并在超出 [MAX_ERROR_RECORDS] 时
     * 触发 LRU 淘汰。同时累加全局统计计数。
     *
     * @param error 错误记录
     */
    fun recordError(error: ErrorRecord) {
        val id = "err-${idCounter.incrementAndGet()}"
        val now = System.currentTimeMillis()
        errorRecords[id] = error
        accessTimes[id] = now

        totalRecorded.incrementAndGet()
        if (error.wasResolved) totalResolved.incrementAndGet()

        evictIfNeeded()

        Log.d(tag, "记录错误: ${error.actionType.name}@${error.appContext} " +
                "code=${error.errorCode} (当前 ${errorRecords.size}/$MAX_ERROR_RECORDS)")
    }

    /**
     * 从 [ClawAction] 构建并记录错误（便捷重载）。
     *
     * 自动从动作推导 [ErrorRecord.actionType]（取自 [ClawAction.type]）与
     * [ErrorRecord.appContext]（优先使用传入的 [appContext]，其次取动作的包名/名称，
     * 兜底为 "unknown"）。若动作类型无法解析，则跳过记录并输出告警。
     *
     * @param action        出错的动作
     * @param errorMessage  原始错误信息
     * @param errorCode     错误码，默认空字符串
     * @param wasResolved   是否已解决，默认 false
     * @param resolution    解决方案描述，默认空字符串
     * @param appContext     应用上下文，为 null 时自动推导
     * @return 构建并记录的 [ErrorRecord]；动作类型无法解析时返回 null
     */
    fun recordError(
        action: ClawAction,
        errorMessage: String,
        errorCode: String = "",
        wasResolved: Boolean = false,
        resolution: String = "",
        appContext: String? = null
    ): ErrorRecord? {
        val actionType = action.type
        if (actionType == null) {
            Log.w(tag, "无法解析动作类型，跳过记录: actionName=${action.actionName}, msg=$errorMessage")
            return null
        }
        val ctx = appContext ?: action.packageName ?: action.name ?: "unknown"
        val record = ErrorRecord(
            timestamp = System.currentTimeMillis(),
            appContext = ctx,
            actionType = actionType,
            errorMessage = errorMessage,
            errorCode = errorCode,
            wasResolved = wasResolved,
            resolution = resolution
        )
        recordError(record)
        return record
    }

    // =========================================================================
    //  聚类分析
    // =========================================================================

    /**
     * 分析错误聚类。
     *
     * 聚类规则：
     * 1. 按 (actionType, appContext) 分组（满足「相同应用 + 相同动作类型 = 同类错误」）。
     * 2. 组内按错误信息关键词的 Jaccard 相似度聚类，重叠度 ≥ [SIMILARITY_THRESHOLD]（60%）
     *    的错误归入同一簇（贪心单链聚类）。
     * 3. 仅保留成员数 ≥ [MIN_CLUSTER_SIZE] 的聚类（代表重复出现的模式）。
     *
     * 每个聚类会计算根因、置信度与修复建议，并缓存到 [clusterCache] 供
     * [findRootCause] 查询。返回结果按聚类规模降序排列。
     *
     * @return 错误聚类列表（按规模降序）
     */
    fun analyzeClusters(): List<ErrorCluster> {
        // 清空旧缓存，从最新记录重建
        clusterCache.clear()

        val records = errorRecords.values.toList()
        if (records.isEmpty()) return emptyList()

        // 1. 按 (actionType, appContext) 分组
        val byContext = records.groupBy { it.actionType to it.appContext }

        val clusters = ArrayList<ErrorCluster>()
        var index = 0

        for ((key, group) in byContext) {
            val (actionType, appContext) = key

            // 2. 组内按消息相似度聚类
            val subClusters = clusterBySimilarity(group)

            for (members in subClusters) {
                // 3. 过滤过小的簇（不构成重复模式）
                if (members.size < MIN_CLUSTER_SIZE) continue

                index++
                val keywordSets = members.map { extractKeywords(it.errorMessage) }
                val commonKeywords = computeFrequentKeywords(keywordSets, SIMILARITY_THRESHOLD)

                val signature = patternSignature(actionType, appContext, commonKeywords, members)
                val id = "${actionType.name}@${appContext}::$signature"
                val pattern = "${actionType.name}@${appContext}|${commonKeywords.sorted().joinToString(",")}"

                val rootCause = buildRootCause(members, records, commonKeywords)
                val errorType = classifyErrorType(members.first().errorMessage, members.first().errorCode)
                val suggestedFix = suggestFix(errorType, appContext)
                    ?: buildGenericFix(errorType)
                val confidence = computePatternConfidence(members, commonKeywords)

                val cluster = ErrorCluster(
                    id = id,
                    pattern = pattern,
                    errors = members.sortedByDescending { it.timestamp },
                    rootCause = rootCause,
                    confidence = confidence,
                    suggestedFix = suggestedFix
                )
                clusters.add(cluster)
            }
        }

        // 缓存聚类供 findRootCause 查询
        for (cluster in clusters) {
            clusterCache[cluster.id] = cluster
        }

        Log.d(tag, "聚类分析完成: ${clusters.size} 个聚类 (来自 ${records.size} 条记录)")
        return clusters.sortedByDescending { it.errors.size }
    }

    // =========================================================================
    //  根因分析
    // =========================================================================

    /**
     * 查找指定聚类的根因。
     *
     * 依赖 [analyzeClusters] 的聚类结果。若尚未分析过，会自动触发一次分析。
     *
     * @param clusterId 聚类 ID（来自 [analyzeClusters] 返回的 [ErrorCluster.id]）
     * @return 根因分析结果；聚类不存在或证据不足时返回 null
     */
    fun findRootCause(clusterId: String): RootCause? {
        ensureClustersAnalyzed()
        return clusterCache[clusterId]?.rootCause
    }

    // =========================================================================
    //  错误关联
    // =========================================================================

    /**
     * 查找不同错误类型间的时序共现关联。
     *
     * 关联规则：
     * - 按应用上下文分组，组内按时间排序。
     * - 若相邻两条错误（不同类型）的时间间隔 ≤ [CO_OCCURRENCE_WINDOW_MS]（5 分钟），
     *   计为一次有向共现（先者类型 → 后者类型）。
     * - 仅上报共现次数 ≥ [MIN_COOCCURRENCE_COUNT]（3）的关联。
     * - [ErrorCorrelation.correlationScore] = 共现次数 / 先者类型总出现次数
     *   （即「给定先者错误，后者随之出现的条件概率」）。
     *
     * @return 显著关联列表（按共现次数降序）
     */
    fun findCorrelations(): List<ErrorCorrelation> {
        val records = errorRecords.values.toList()
        if (records.size < 2) return emptyList()

        // 各错误类型的总出现次数
        val typeCounts = HashMap<String, Int>()
        for (record in records) {
            val t = classifyErrorType(record.errorMessage, record.errorCode)
            typeCounts.merge(t, 1, Int::plus)
        }

        // 有向共现计数：(先者类型 -> 后者类型) -> 次数
        val coOccurrence = HashMap<Pair<String, String>, Int>()

        // 按应用上下文分组，组内按时间排序统计窗口内共现
        val byApp = records.groupBy { it.appContext }
        for ((_, group) in byApp) {
            val sorted = group.sortedBy { it.timestamp }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                // 超出时间窗口不算共现
                if (curr.timestamp - prev.timestamp > CO_OCCURRENCE_WINDOW_MS) continue

                val typePrev = classifyErrorType(prev.errorMessage, prev.errorCode)
                val typeCurr = classifyErrorType(curr.errorMessage, curr.errorCode)
                // 仅关联「不同」错误类型
                if (typePrev == typeCurr) continue

                coOccurrence.merge(typePrev to typeCurr, 1, Int::plus)
            }
        }

        val result = ArrayList<ErrorCorrelation>()
        for ((pair, count) in coOccurrence) {
            if (count < MIN_COOCCURRENCE_COUNT) continue
            val srcCount = typeCounts[pair.first] ?: 0
            val score = if (srcCount > 0) count.toDouble() / srcCount else 0.0
            result.add(
                ErrorCorrelation(
                    errorType1 = pair.first,
                    errorType2 = pair.second,
                    correlationScore = score,
                    coOccurrenceCount = count
                )
            )
        }

        Log.d(tag, "关联分析完成: ${result.size} 个显著关联 (共现阈值=$MIN_COOCCURRENCE_COUNT)")
        return result.sortedByDescending { it.coOccurrenceCount }
    }

    // =========================================================================
    //  错误预测
    // =========================================================================

    /**
     * 预测某动作在某应用上下文中的失败概率。
     *
     * 基于历史记录计算：`失败概率 = 未解决错误数 / 该(动作,应用)历史错误总数`。
     * 无历史记录时返回 null。
     *
     * @param actionType  动作类型
     * @param appContext   应用上下文
     * @return 错误预测；无历史数据时返回 null
     */
    fun predictFailure(actionType: ActionType, appContext: String): ErrorPrediction? {
        val records = errorRecords.values
            .filter { it.actionType == actionType && it.appContext == appContext }

        if (records.isEmpty()) {
            Log.d(tag, "预测失败: 无历史记录 ${actionType.name}@$appContext")
            return null
        }

        val total = records.size
        val unresolved = records.count { !it.wasResolved }
        val probability = unresolved.toDouble() / total

        val reason = buildString {
            append("历史记录 ${total} 条，未解决 ${unresolved} 条，")
            append("失败率 ${"%.1f".format(probability * 100)}%")
            if (unresolved == 0) {
                append("（暂无失败记录，风险较低）")
            } else {
                // 列出最常见的未解决错误类型
                val topType = records.filter { !it.wasResolved }
                    .groupingBy { classifyErrorType(it.errorMessage, it.errorCode) }
                    .eachCount()
                    .maxByOrNull { it.value }
                if (topType != null) {
                    append("，常见错误: ${topType.key}(${topType.value}次)")
                }
            }
        }

        return ErrorPrediction(
            actionType = actionType,
            appContext = appContext,
            failureProbability = probability,
            reason = reason
        )
    }

    // =========================================================================
    //  修复建议
    // =========================================================================

    /**
     * 建议修复方案。
     *
     * 基于「同类错误曾被何种方案解决」的历史经验：在指定应用上下文中，
     * 查找已解决的同类（相同错误类型）错误，返回最常用的解决方案。
     * 无历史解决经验时返回 null（调用方可改用聚类中的通用兜底建议）。
     *
     * @param errorType  错误类型（由 [classifyErrorType] 归类，如 "network"、"timeout"）
     * @param appContext  应用上下文
     * @return 最常用的成功解决方案；无匹配时返回 null
     */
    fun suggestFix(errorType: String, appContext: String): String? {
        val resolved = errorRecords.values.filter {
            it.wasResolved &&
                    it.appContext == appContext &&
                    classifyErrorType(it.errorMessage, it.errorCode) == errorType &&
                    it.resolution.isNotBlank()
        }

        if (resolved.isEmpty()) return null

        // 取出现次数最多的解决方案
        val top = resolved.groupingBy { it.resolution }
            .eachCount()
            .maxByOrNull { it.value }

        return top?.key
    }

    // =========================================================================
    //  统计与查询
    // =========================================================================

    /**
     * 获取错误统计摘要（人类可读字符串）。
     *
     * 包含：记录总数与淘汰数、已解决/未解决数、按错误类型与动作类型的分布、
     * 聚类数与显著关联数。
     *
     * @return 统计摘要字符串
     */
    fun getErrorStats(): String {
        val records = errorRecords.values.toList()
        val sb = StringBuilder()

        sb.appendLine("===== ErrorCorrelationEngine 错误统计 =====")
        sb.appendLine("当前记录: ${records.size}/$MAX_ERROR_RECORDS (累计淘汰 ${evictionCount.get()})")
        sb.appendLine("累计记录: ${totalRecorded.get()} | 累计已解决: ${totalResolved.get()}")
        val resolved = records.count { it.wasResolved }
        sb.appendLine("当前已解决: $resolved | 未解决: ${records.size - resolved}")
        sb.appendLine()

        sb.appendLine("-- 按错误类型 --")
        val byType = records.groupingBy { classifyErrorType(it.errorMessage, it.errorCode) }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
        if (byType.isEmpty()) {
            sb.appendLine("  (无)")
        } else {
            for (e in byType) sb.appendLine("  ${e.key}: ${e.value}")
        }
        sb.appendLine()

        sb.appendLine("-- 按动作类型 --")
        val byAction = records.groupingBy { it.actionType.name }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
        if (byAction.isEmpty()) {
            sb.appendLine("  (无)")
        } else {
            for (e in byAction) sb.appendLine("  ${e.key}: ${e.value}")
        }
        sb.appendLine()

        val clusters = analyzeClusters()
        val correlations = findCorrelations()
        sb.appendLine("聚类数: ${clusters.size}")
        sb.appendLine("显著关联数(共现>=$MIN_COOCCURRENCE_COUNT): ${correlations.size}")
        sb.appendLine("==========================================")

        return sb.toString()
    }

    /**
     * 获取出现频次最高的错误记录。
     *
     * 按 (actionType, appContext, errorCode) 分组统计频次，返回每组中最新的一条
     * 记录作为代表，按频次降序取前 [limit] 条。同时更新返回记录的访问时间（LRU）。
     *
     * @param limit 返回的最大条数
     * @return 高频错误记录列表（按频次降序）
     */
    fun getTopErrors(limit: Int): List<ErrorRecord> {
        val entries = errorRecords.entries.toList()
        if (entries.isEmpty()) return emptyList()

        // 按 (actionType, appContext, errorCode) 分组
        val groups = entries.groupBy {
            Triple(it.value.actionType, it.value.appContext, it.value.errorCode)
        }

        val now = System.currentTimeMillis()
        return groups.values
            .sortedByDescending { it.size }
            .take(limit.coerceAtLeast(0))
            .map { group ->
                // 取组内最新记录作为代表，并更新其访问时间（LRU）
                val rep = group.maxByOrNull { it.value.timestamp }!!
                accessTimes[rep.key] = now
                rep.value
            }
    }

    // =========================================================================
    //  内部辅助方法 —— 错误分类
    // =========================================================================

    /**
     * 将错误信息与错误码分类为错误类型字符串。
     *
     * 优先依据错误码前缀快速分类，其次依据中英文关键字匹配。
     * 返回的分类标识用于错误关联与修复建议。
     *
     * @param errorMessage 错误信息文本
     * @param errorCode    错误码
     * @return 错误类型标识（如 "network"、"timeout"、"permission_denied" 等）
     */
    private fun classifyErrorType(errorMessage: String, errorCode: String): String {
        val code = errorCode.trim()

        // 1. 依据错误码前缀快速分类
        when {
            code.startsWith("NET", ignoreCase = true) -> return "network"
            code.startsWith("TIMEOUT", ignoreCase = true) -> return "timeout"
            code.startsWith("PERM", ignoreCase = true) -> return "permission_denied"
            code.startsWith("ELEM", ignoreCase = true) -> return "element_not_found"
            code.startsWith("APP", ignoreCase = true) -> return "app_not_installed"
            code.startsWith("UI", ignoreCase = true) -> return "ui_not_ready"
        }

        // 2. 依据关键字分类
        val msg = errorMessage.lowercase().trim()
        if (msg.isEmpty()) return "unknown"

        // 权限拒绝（最高优先级）
        if (containsAny(msg, "permission", "权限", "denied", "拒绝", "forbidden", "禁止",
                "unauthorized", "未授权", "access denied")) {
            return "permission_denied"
        }
        // 网络错误
        if (containsAny(msg, "network", "网络", "connection", "连接", "socket", "unreachable",
                "无法访问", "断网", "unknown host", "host unresolved", "reset by peer",
                "connection reset", "连接重置", "ssl", "握手", "eof")) {
            return "network"
        }
        // 超时
        if (containsAny(msg, "timeout", "超时", "timed out", "time out", "deadline", "截止",
                "expired", "已过期")) {
            return "timeout"
        }
        // 应用未安装
        if (containsAny(msg, "not installed", "未安装", "no such package", "package not found",
                "no activity", "not exist", "不存在")) {
            return "app_not_installed"
        }
        // UI 未就绪
        if (containsAny(msg, "not ready", "未就绪", "loading", "加载中", "still loading",
                "not visible", "不可见", "not loaded", "未加载", "animating", "动画",
                "busy", "繁忙", "not responding", "无响应", "not idle", "非空闲")) {
            return "ui_not_ready"
        }
        // 元素未找到
        if (containsAny(msg, "not found", "未找到", "找不到", "no such element", "element",
                "元素", "stale", "no element", "not present", "不在线")) {
            return "element_not_found"
        }

        return "unknown"
    }

    /**
     * 取 [ErrorRecord] 对应的错误类型字符串（便捷封装）。
     */
    private fun classifyErrorType(record: ErrorRecord): String =
        classifyErrorType(record.errorMessage, record.errorCode)

    // =========================================================================
    //  内部辅助方法 —— 关键词与相似度
    // =========================================================================

    /**
     * 从错误信息中提取关键词集合。
     *
     * 提取策略：
     * - 英文/数字 token（长度 ≥ 2），统一小写。
     * - 中文连续片段切分为 2-gram，以提升中文匹配粒度。
     *
     * @param text 错误信息文本
     * @return 关键词集合
     */
    private fun extractKeywords(text: String): Set<String> {
        val lower = text.lowercase().trim()
        val result = mutableSetOf<String>()

        // 英文/数字 token
        Regex("[a-z0-9]+").findAll(lower)
            .map { it.value }
            .filter { it.length >= 2 }
            .forEach { result.add(it) }

        // 中文片段切分为 2-gram
        Regex("[\\u4e00-\\u9fa5]+").findAll(lower)
            .map { it.value }
            .forEach { seg ->
                if (seg.length <= 2) {
                    result.add(seg)
                } else {
                    for (i in 0..seg.length - 2) {
                        result.add(seg.substring(i, i + 2))
                    }
                }
            }

        return result
    }

    /**
     * 计算两个关键词集合的 Jaccard 相似度（交集 / 并集）。
     *
     * @return 相似度（0.0-1.0）；两者皆空时返回 0.0
     */
    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val union = a.union(b).size
        if (union == 0) return 0.0
        return a.intersect(b).size.toDouble() / union
    }

    /**
     * 计算在多个关键词集合中出现频次达标的「共同关键词」。
     *
     * @param keywordSets 关键词集合列表
     * @param threshold   出现频次阈值（占集合数的比例，如 0.6 表示需在 ≥60% 的集合中出现）
     * @return 满足频次要求的关键词集合
     */
    private fun computeFrequentKeywords(
        keywordSets: List<Set<String>>,
        threshold: Double
    ): Set<String> {
        if (keywordSets.isEmpty()) return emptySet()

        val counts = HashMap<String, Int>()
        for (set in keywordSets) {
            for (kw in set) counts.merge(kw, 1, Int::plus)
        }

        // 需在 ≥ threshold 比例的集合中出现（向上取整，至少 1）
        val need = Math.ceil(keywordSets.size * threshold).toInt().coerceAtLeast(1)
        return counts.filter { it.value >= need }.keys
    }

    /**
     * 组内贪心单链聚类：以每条记录为种子，吸收与之 Jaccard ≥ [SIMILARITY_THRESHOLD] 的未分配记录。
     *
     * @param records 同一 (actionType, appContext) 组内的错误记录
     * @return 聚类列表（每个子列表为一簇）
     */
    private fun clusterBySimilarity(records: List<ErrorRecord>): List<List<ErrorRecord>> {
        val result = ArrayList<List<ErrorRecord>>()
        val assigned = HashSet<Int>()
        val indexed = records.mapIndexed { i, r -> i to r }

        // 预计算关键词，避免重复提取
        val keywords = indexed.associate { (i, r) -> i to extractKeywords(r.errorMessage) }

        for ((i, r) in indexed) {
            if (i in assigned) continue
            val cluster = ArrayList<ErrorRecord>()
            cluster.add(r)
            assigned.add(i)

            val kwsR = keywords[i]!!
            for ((j, other) in indexed) {
                if (j == i || j in assigned) continue
                if (jaccard(kwsR, keywords[j]!!) >= SIMILARITY_THRESHOLD) {
                    cluster.add(other)
                    assigned.add(j)
                }
            }
            result.add(cluster)
        }
        return result
    }

    // =========================================================================
    //  内部辅助方法 —— 根因与置信度
    // =========================================================================

    /**
     * 为一个聚类构建根因分析结果。
     *
     * @param members        聚类成员记录
     * @param allRecords     全部错误记录（用于查找跨动作的受影响范围）
     * @param commonKeywords 聚类共同关键词
     * @return 根因分析结果
     */
    private fun buildRootCause(
        members: List<ErrorRecord>,
        allRecords: List<ErrorRecord>,
        commonKeywords: Set<String>
    ): RootCause {
        val actionType = members.first().actionType
        val appContexts = members.map { it.appContext }.distinct()

        // 主要错误类型
        val typeCounts = members.groupingBy { classifyErrorType(it) }.eachCount()
        val dominantType = typeCounts.maxByOrNull { it.value }?.key ?: "unknown"
        val dominantCount = typeCounts[dominantType] ?: 0

        // 受影响动作：在全部记录中查找错误信息相似的动作类型（可能跨应用）
        val affectedActions = findAffectedActions(commonKeywords, allRecords, actionType)

        // 证据列表
        val evidence = ArrayList<String>()
        evidence.add("聚类包含 ${members.size} 条相似错误记录")
        evidence.add("主要错误类型: $dominantType")
        evidence.add("涉及应用: ${appContexts.joinToString()}")
        if (commonKeywords.isNotEmpty()) {
            evidence.add("共同关键词: ${commonKeywords.sorted().joinToString(", ")}")
        }
        val resolved = members.count { it.wasResolved }
        evidence.add("已解决 ${resolved}/${members.size} 条")
        val timestamps = members.map { it.timestamp }
        evidence.add("时间跨度: ${formatTime(timestamps.min())} ~ ${formatTime(timestamps.max())}")

        // 根因描述
        val description = "应用 ${appContexts.joinToString()} 在执行「${actionType.description}」时" +
                "频繁出现 ${dominantType} 类错误（共 ${members.size} 条），" +
                "建议优先排查 ${dominantType} 相关因素"

        // 置信度：模式置信度 + 主导错误类型占比的加权融合
        val patternConfidence = computePatternConfidence(members, commonKeywords)
        val dominantPrevalence = dominantCount.toDouble() / members.size
        val rootCauseConfidence =
            (0.6 * patternConfidence + 0.4 * dominantPrevalence).coerceIn(0.0, 1.0)

        return RootCause(
            description = description,
            evidence = evidence,
            confidence = rootCauseConfidence,
            affectedActions = affectedActions
        )
    }

    /**
     * 计算聚类的模式置信度。
     *
     * = 0.5 × 规模因子 + 0.5 × 一致性因子
     * - 规模因子：min(成员数 / [CONFIDENCE_SIZE_SATURATION], 1.0)
     * - 一致性因子：成员错误信息与共同关键词的平均 Jaccard 相似度
     *
     * @param members        聚类成员
     * @param commonKeywords 共同关键词
     * @return 模式置信度（0.0-1.0）
     */
    private fun computePatternConfidence(
        members: List<ErrorRecord>,
        commonKeywords: Set<String>
    ): Double {
        val sizeFactor = minOf(members.size.toDouble() / CONFIDENCE_SIZE_SATURATION, 1.0)
        val consistency = if (members.isEmpty() || commonKeywords.isEmpty()) {
            0.0
        } else {
            var sum = 0.0
            for (r in members) {
                sum += jaccard(extractKeywords(r.errorMessage), commonKeywords)
            }
            sum / members.size
        }
        return (0.5 * sizeFactor + 0.5 * consistency).coerceIn(0.0, 1.0)
    }

    /**
     * 查找受某错误模式影响的动作类型。
     *
     * 在全部记录中查找错误信息与该模式共同关键词 Jaccard ≥ [SIMILARITY_THRESHOLD]
     * 的记录，收集其动作类型（去重，保序）。聚类自身的动作类型始终包含在内。
     *
     * @param patternKeywords 模式共同关键词
     * @param allRecords      全部错误记录
     * @param selfActionType  聚类自身的动作类型（确保包含）
     * @return 受影响动作类型列表
     */
    private fun findAffectedActions(
        patternKeywords: Set<String>,
        allRecords: List<ErrorRecord>,
        selfActionType: ActionType
    ): List<ActionType> {
        val result = LinkedHashSet<ActionType>()
        result.add(selfActionType)

        if (patternKeywords.isNotEmpty()) {
            for (r in allRecords) {
                if (jaccard(extractKeywords(r.errorMessage), patternKeywords) >= SIMILARITY_THRESHOLD) {
                    result.add(r.actionType)
                }
            }
        }
        return result.toList()
    }

    // =========================================================================
    //  内部辅助方法 —— 修复建议与格式化
    // =========================================================================

    /**
     * 构建按错误类型分类的通用兜底修复建议。
     *
     * 当无历史解决经验时使用。
     *
     * @param errorType 错误类型
     * @return 通用修复建议
     */
    private fun buildGenericFix(errorType: String): String {
        return when (errorType) {
            "network" -> "检查网络连接后重试，或切换至稳定网络环境"
            "timeout" -> "适当增加超时时长，或拆分任务降低单步耗时"
            "permission_denied" -> "前往系统设置授予所需权限（无障碍/存储等）后重试"
            "element_not_found" -> "确认目标元素已渲染，尝试滚动查找或增加等待时间"
            "app_not_installed" -> "确认目标应用已安装，或提供可替代的应用"
            "ui_not_ready" -> "增加界面加载等待时间后重试"
            else -> "记录错误详情并重试，必要时通知用户"
        }
    }

    /**
     * 生成聚类模式签名（用于构造稳定可复现的聚类 ID）。
     *
     * 优先使用共同关键词的排序拼接；关键词为空时退化为代表消息的哈希值。
     */
    private fun patternSignature(
        actionType: ActionType,
        appContext: String,
        commonKeywords: Set<String>,
        members: List<ErrorRecord>
    ): String {
        if (commonKeywords.isNotEmpty()) {
            return commonKeywords.sorted().joinToString("_")
        }
        // 关键词为空时用代表消息哈希兜底，保证 ID 唯一性
        val rep = members.first().errorMessage
        return "msg_${rep.hashCode()}"
    }

    /**
     * 格式化时间戳为可读字符串（MM-dd HH:mm:ss）。
     *
     * 每次调用新建 [SimpleDateFormat] 实例以避免线程安全问题。
     */
    private fun formatTime(ts: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss").format(Date(ts))

    // =========================================================================
    //  内部辅助方法 —— LRU 淘汰与缓存维护
    // =========================================================================

    /**
     * 在记录数超过 [MAX_ERROR_RECORDS] 时按 LRU 策略淘汰最久未访问的记录。
     */
    private fun evictIfNeeded() {
        while (errorRecords.size > MAX_ERROR_RECORDS) {
            val oldest = accessTimes.entries.minByOrNull { it.value }
            if (oldest == null) break
            errorRecords.remove(oldest.key)
            accessTimes.remove(oldest.key)
            evictionCount.incrementAndGet()
        }
    }

    /**
     * 确保聚类已分析：若聚类缓存为空则触发一次 [analyzeClusters]。
     */
    private fun ensureClustersAnalyzed() {
        if (clusterCache.isEmpty()) {
            analyzeClusters()
        }
    }

    // =========================================================================
    //  内部辅助方法 —— 通用工具
    // =========================================================================

    /**
     * 判断文本是否包含任意一个关键字（调用前应已将文本转为小写）。
     */
    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { it in text }
    }

    // =========================================================================
    //  重置
    // =========================================================================

    /**
     * 清空所有错误记录、聚类缓存与统计计数。
     *
     * 适用于测试或需要清除历史分析的场景。
     */
    fun clear() {
        errorRecords.clear()
        accessTimes.clear()
        clusterCache.clear()
        totalRecorded.set(0)
        totalResolved.set(0)
        evictionCount.set(0)
        Log.d(tag, "已清空所有错误记录与统计")
    }
}
