package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt

// =============================================================================
//  PerformanceMonitor —— 实时性能监控与优化系统
// =============================================================================

/**
 * PerformanceMonitor —— 实时性能监控与优化系统
 *
 * 为 MobileClaw 的执行流水线提供端到端的性能监控、瓶颈定位与优化建议能力。
 * 核心功能包括：
 *
 * 1. **指标采集**：通过 [recordMetric] 采集每个动作的执行指标（耗时、Token、成功率、重试次数），
 *    通过 [recordResourceSnapshot] 采集系统资源快照（内存、CPU、电池影响）。
 * 2. **瓶颈检测**：[detectBottlenecks] 自动识别慢动作、高频失败、高 Token 消耗、高重试、高内存占用。
 * 3. **趋势分析**：[getTrend] 基于线性回归斜率判定各指标是改善、退化、稳定还是数据不足。
 * 4. **资源追踪**：持续记录系统资源使用情况，关联分析资源压力对动作性能的影响。
 * 5. **优化建议**：[getOptimizationSuggestions] 基于瓶颈生成具体可执行的优化建议（缓存、削减 Token 等）。
 * 6. **告警系统**：[checkAlerts] 在性能指标偏离阈值时按 INFO/WARNING/CRITICAL 三级告警。
 * 7. **基准对比**：[compareWithBenchmark] 将当前性能与历史基准逐项对比，量化改进幅度。
 * 8. **报告生成**：[getPerformanceReport] 一键生成综合性能报告。
 *
 * ### 瓶颈与告警阈值
 *
 * | 指标         | 严重(CRITICAL) | 警告(WARNING) | 提示(INFO)  |
 * |--------------|----------------|---------------|-------------|
 * | 耗时         | > 10s          | > 5s          | > 3s        |
 * | 成功率       | < 50%          | < 70%         | < 85%       |
 * | Token 用量   | > 4000         | > 2000        | > 1200      |
 * | 重试次数     | > 5            | > 3           | > 1         |
 * | 内存占用     | > 500MB        | > 300MB       | > 200MB     |
 *
 * ### 线程安全
 *
 * - 动作指标使用 [ConcurrentHashMap] 存储（按 actionName 分组），列表级读写通过 `synchronized` 保护。
 * - 资源快照使用 [ConcurrentLinkedDeque]，无锁并发安全。
 * - 统计计数使用 [AtomicLong] + @Volatile，保证可见性。
 * - 异步监控通过 [CoroutineScope]（默认 [Dispatchers.IO]）执行，可在任意线程调用。
 *
 * ### 典型调用流程
 * ```
 * val monitor = PerformanceMonitor()
 * // 执行动作后记录指标
 * monitor.recordMetric(action, result, durationMs = 2300, tokenUsage = 850, retryCount = 0)
 * // 采集资源快照
 * monitor.recordResourceSnapshot(memoryMb = 180, cpuPercent = 35.0, batteryImpact = 0.3)
 * // 检测瓶颈
 * val bottlenecks = monitor.detectBottlenecks()
 * // 获取趋势
 * val trend = monitor.getTrend(PerformanceMonitor.MetricType.DURATION)
 * // 获取优化建议
 * val suggestions = monitor.getOptimizationSuggestions()
 * // 检查告警
 * val alerts = monitor.checkAlerts()
 * // 与基准对比
 * val comparison = monitor.compareWithBenchmark(benchmark)
 * // 生成报告
 * val report = monitor.getPerformanceReport()
 * // 启动后台自动监控（周期采样 + 自动告警）
 * val job = monitor.startAutoMonitoring(sampler = { sampleSystemResources() })
 * ```
 *
 * 注：本类的 [MetricType] / [TrendDirection] 等枚举与数据类均以嵌套类型形式定义在类内部，
 * 与同包内 [PerformanceBaseline] 的同名顶层类型相互独立，避免命名冲突。
 *
 * @param scope 协程作用域，用于异步监控任务，默认 [Dispatchers.IO]。
 */
class PerformanceMonitor(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val tag = "PerformanceMonitor"

    // =========================================================================
    //  嵌套枚举定义
    // =========================================================================

    /**
     * 性能指标类型。
     *
     * 每种类型对应 PerformanceMonitor 追踪的一个关键性能维度。
     * 其中部分指标「越低越好」（耗时、Token、重试、内存），部分「越高越好」（成功率），
     * 趋势判定与瓶颈检测逻辑会根据指标方向自动反转，详见 [isHigherBetter]。
     *
     * - [DURATION] 执行耗时：单次动作从开始到结束的耗时（毫秒），越低越好。
     * - [TOKEN_USAGE] Token 用量：单次动作执行过程中消耗的 AI Token 数，越低越好。
     * - [SUCCESS_RATE] 成功率：动作执行成功的比例（0.0~1.0），越高越好。
     * - [RETRY_COUNT] 重试次数：动作执行失败后的重试次数，越低越好。
     * - [MEMORY_USAGE] 内存占用：系统资源快照中的应用内存占用（MB），越低越好。
     */
    enum class MetricType {
        /** 执行耗时（毫秒）：单次动作的端到端耗时，越低越好。 */
        DURATION,

        /** Token 用量：单次动作消耗的 AI Token 数，越低越好。 */
        TOKEN_USAGE,

        /** 成功率（0.0~1.0）：动作执行成功的比例，越高越好。 */
        SUCCESS_RATE,

        /** 重试次数：动作失败后的重试次数，越低越好。 */
        RETRY_COUNT,

        /** 内存占用（MB）：系统资源快照中的应用内存占用，越低越好。 */
        MEMORY_USAGE
    }

    /**
     * 性能趋势方向。
     *
     * 由 [getTrend] 基于近期采样的线性回归斜率综合判定。
     *
     * - [IMPROVING] 改善中：性能指标正朝着好的方向发展。
     * - [DEGRADING] 退化中：性能指标正朝着差的方向发展。
     * - [STABLE] 稳定：指标波动很小，无明显上升或下降趋势。
     * - [INSUFFICIENT_DATA] 数据不足：采样数低于阈值，无法判定趋势。
     */
    enum class TrendDirection {
        /** 改善中：性能指标正朝着好的方向发展。 */
        IMPROVING,

        /** 退化中：性能指标正朝着差的方向发展。 */
        DEGRADING,

        /** 稳定：指标波动很小，无明显上升或下降趋势。 */
        STABLE,

        /** 数据不足：采样数低于阈值，无法判定趋势。 */
        INSUFFICIENT_DATA
    }

    /**
     * 告警级别。
     *
     * 由 [checkAlerts] 根据指标偏离阈值的程度判定，
     * 是 [PerformanceAlert.level] 和 [Bottleneck.severity] 的取值类型。
     *
     * - [INFO] 提示：性能轻微偏离，建议关注。
     * - [WARNING] 警告：性能明显偏离，建议采取优化措施。
     * - [CRITICAL] 严重：性能严重偏离，需要立即处理。
     */
    enum class AlertLevel {
        /** 提示：性能轻微偏离，建议关注。 */
        INFO,

        /** 警告：性能明显偏离，建议采取优化措施。 */
        WARNING,

        /** 严重：性能严重偏离，需要立即处理。 */
        CRITICAL
    }

    /**
     * 优化建议类型。
     *
     * 由 [getOptimizationSuggestions] 根据瓶颈分析结果生成，
     * 是 [OptimizationSuggestion.type] 的取值类型，每类建议对应一种具体的优化手段。
     *
     * - [CACHE_MORE] 加强缓存：对高频重复动作启用缓存，避免重复计算。
     * - [REDUCE_TOKENS] 削减 Token：精简系统提示词与上下文，降低 AI Token 消耗。
     * - [OPTIMIZE_ACTION] 优化动作：针对慢动作进行专项优化（如减少等待、合并步骤）。
     * - [BATCH_OPERATIONS] 批量操作：将多个细粒度动作合并为批量操作，减少往返开销。
     * - [SKIP_REDUNDANT] 跳过冗余：识别并跳过冗余的截图/等待等动作。
     */
    enum class SuggestionType {
        /** 加强缓存：对高频重复动作启用缓存，避免重复计算。 */
        CACHE_MORE,

        /** 削减 Token：精简系统提示词与上下文，降低 AI Token 消耗。 */
        REDUCE_TOKENS,

        /** 优化动作：针对慢动作进行专项优化（如减少等待、合并步骤）。 */
        OPTIMIZE_ACTION,

        /** 批量操作：将多个细粒度动作合并为批量操作，减少往返开销。 */
        BATCH_OPERATIONS,

        /** 跳过冗余：识别并跳过冗余的截图/等待等动作。 */
        SKIP_REDUNDANT
    }

    // =========================================================================
    //  嵌套数据类定义
    // =========================================================================

    /**
     * 单条动作执行指标记录。
     *
     * 每次调用 [recordMetric] 都会生成一条记录，
     * 包含动作信息、执行耗时、Token 用量、是否成功、重试次数和时间戳。
     * 同一动作（按 actionName 区分）的多条记录聚合成时序数据，
     * 用于瓶颈检测、趋势分析和优化建议生成。
     *
     * @param actionName 动作名称（对应 [ClawAction.actionName]，如 "SCREEN_CLICK_TEXT"）。
     * @param actionType 动作类型（[ClawAction.type] 解析结果），无法识别时为 null。
     * @param timestamp 记录时间戳（epoch 毫秒）。
     * @param durationMs 执行耗时（毫秒）。
     * @param tokenUsage 本次动作消耗的 AI Token 数。
     * @param success 是否执行成功。
     * @param retryCount 失败后的重试次数（首次成功为 0）。
     */
    data class ActionMetric(
        val actionName: String,
        val actionType: ActionType?,
        val timestamp: Long,
        val durationMs: Long,
        val tokenUsage: Int,
        val success: Boolean,
        val retryCount: Int
    )

    /**
     * 系统资源性能快照。
     *
     * 由 [recordResourceSnapshot] 采集，记录某一时刻的系统资源占用情况。
     * 用于追踪内存、CPU、电池等系统级资源随时间的变化趋势，
     * 并与动作级指标关联分析（如内存持续走高可能拖慢动作执行）。
     *
     * @param timestamp 快照时间戳（epoch 毫秒）。
     * @param memoryUsageMb 应用内存占用（MB）。
     * @param cpuUsagePercent CPU 使用率（0.0~100.0）。
     * @param batteryImpact 电池影响估算值（0.0~1.0，越大越耗电）。
     * @param totalActions 截至快照时的累计动作数。
     * @param successRate 截至快照时的累计成功率（0.0~1.0）。
     * @param avgDurationMs 截至快照时的平均动作耗时（毫秒）。
     * @param totalTokens 截至快照时的累计 Token 消耗。
     */
    data class PerformanceSnapshot(
        val timestamp: Long,
        val memoryUsageMb: Long,
        val cpuUsagePercent: Double,
        val batteryImpact: Double,
        val totalActions: Int,
        val successRate: Double,
        val avgDurationMs: Double,
        val totalTokens: Int
    )

    /**
     * 性能瓶颈描述。
     *
     * 由 [detectBottlenecks] 检测生成，标识一个具体的性能问题点，
     * 包含瓶颈类型、关联动作、当前值、阈值和严重程度。瓶颈类型覆盖：
     * 慢动作（DURATION）、高频失败（SUCCESS_RATE）、高 Token 消耗（TOKEN_USAGE）、
     * 高重试（RETRY_COUNT）、高内存占用（MEMORY_USAGE）。
     *
     * @param metricType 瓶颈对应的指标类型。
     * @param actionName 关联动作名称（内存瓶颈等全局问题时为 null）。
     * @param description 人类可读的瓶颈描述（中文）。
     * @param severity 严重程度（[AlertLevel]）。
     * @param currentValue 当前指标值。
     * @param thresholdValue 触发阈值。
     * @param detectedAt 检测时间戳（epoch 毫秒）。
     */
    data class Bottleneck(
        val metricType: MetricType,
        val actionName: String?,
        val description: String,
        val severity: AlertLevel,
        val currentValue: Double,
        val thresholdValue: Double,
        val detectedAt: Long
    )

    /**
     * 优化建议。
     *
     * 由 [getOptimizationSuggestions] 基于瓶颈分析结果生成，
     * 每条建议包含建议类型、标题、详细说明、预估改善幅度和优先级。
     * 优先级越小越紧急（1 为最高）。预估改善幅度为正数百分比，表示执行建议后
     * 预期可获得的性能提升比例。
     *
     * @param type 建议类型（[SuggestionType]）。
     * @param title 建议标题（简短中文）。
     * @param description 建议详细说明（中文）。
     * @param estimatedImprovement 预估改善幅度（百分比，如 25.0 表示约 25% 提升）。
     * @param priority 优先级（1 最高，5 最低）。
     * @param relatedActionName 关联动作名称（无具体关联时为 null）。
     */
    data class OptimizationSuggestion(
        val type: SuggestionType,
        val title: String,
        val description: String,
        val estimatedImprovement: Double,
        val priority: Int,
        val relatedActionName: String?
    )

    /**
     * 性能告警。
     *
     * 由 [checkAlerts] 在性能指标偏离阈值时生成，
     * 包含告警级别、关联指标、告警消息、当前值和阈值。
     * 与 [Bottleneck] 的区别在于：Bottleneck 侧重定位「问题在哪」，
     * PerformanceAlert 侧重「问题有多严重」，二者互补。
     *
     * @param level 告警级别（[AlertLevel]）。
     * @param metricType 关联的指标类型。
     * @param message 人类可读的告警消息（中文）。
     * @param value 当前指标值。
     * @param threshold 触发阈值。
     * @param timestamp 告警时间戳（epoch 毫秒）。
     */
    data class PerformanceAlert(
        val level: AlertLevel,
        val metricType: MetricType,
        val message: String,
        val value: Double,
        val threshold: Double,
        val timestamp: Long
    )

    /**
     * 性能基准。
     *
     * 作为 [compareWithBenchmark] 的对比基准，
     * 记录一组「标杆」性能数据，通常来自历史优良表现或离线测试。
     * 当前性能与基准逐项对比，得出改善/退化结论。
     *
     * @param name 基准名称（如 "v1.2 优化后基线"）。
     * @param avgDurationMs 基准平均动作耗时（毫秒）。
     * @param successRate 基准成功率（0.0~1.0）。
     * @param avgTokenUsage 基准平均 Token 消耗。
     * @param avgRetryCount 基准平均重试次数。
     * @param memoryUsageMb 基准内存占用（MB）。
     * @param createdAt 基准创建时间戳（epoch 毫秒）。
     */
    data class Benchmark(
        val name: String,
        val avgDurationMs: Double,
        val successRate: Double,
        val avgTokenUsage: Int,
        val avgRetryCount: Double,
        val memoryUsageMb: Long,
        val createdAt: Long
    )

    /**
     * 基准对比结果。
     *
     * 由 [compareWithBenchmark] 返回，逐项对比当前性能与基准的差异，
     * 并给出整体结论。变化百分比中，正值表示当前高于基准，负值表示低于基准；
     * 对于「越低越好」的指标（耗时、Token、重试、内存），负值代表改善。
     *
     * @param benchmarkName 对比的基准名称。
     * @param durationChangePercent 耗时变化百分比。
     * @param successRateChangePercent 成功率变化百分比。
     * @param tokenUsageChangePercent Token 消耗变化百分比。
     * @param retryCountChangePercent 重试次数变化百分比。
     * @param memoryChangePercent 内存占用变化百分比。
     * @param overall 整体结论（[TrendDirection]）。
     * @param summary 人类可读的对比摘要（中文）。
     */
    data class BenchmarkComparison(
        val benchmarkName: String,
        val durationChangePercent: Double,
        val successRateChangePercent: Double,
        val tokenUsageChangePercent: Double,
        val retryCountChangePercent: Double,
        val memoryChangePercent: Double,
        val overall: TrendDirection,
        val summary: String
    )

    // =========================================================================
    //  常量定义（配置）
    // =========================================================================

    companion object {
        /** 每个动作最多保留的指标记录数，超出时淘汰最早的记录。 */
        private const val MAX_METRICS_PER_ACTION = 500

        /** 最多保留的资源快照数，超出时淘汰最早的快照。 */
        private const val MAX_SNAPSHOTS = 200

        /** 趋势分析取近期的样本数。 */
        private const val TREND_SAMPLE_SIZE = 10

        /** 趋势分析所需的最小样本数，低于此数返回 INSUFFICIENT_DATA。 */
        private const val MIN_TREND_SAMPLES = 3

        /** 趋势斜率阈值：归一化斜率绝对值低于此值时判定为稳定。 */
        private const val TREND_SLOPE_THRESHOLD = 0.05

        /** 变异系数阈值：stdDev/avg 超过此值时判定为波动剧烈（视为稳定无法判定方向）。 */
        private const val VOLATILITY_THRESHOLD = 0.4

        // ---- 耗时阈值（毫秒）----

        /** 慢动作提示阈值（INFO）。 */
        private const val DURATION_INFO_THRESHOLD_MS = 3000L

        /** 慢动作警告阈值（WARNING）。 */
        private const val DURATION_WARNING_THRESHOLD_MS = 5000L

        /** 慢动作严重阈值（CRITICAL）。 */
        private const val DURATION_CRITICAL_THRESHOLD_MS = 10000L

        // ---- 成功率阈值（0.0~1.0）----

        /** 成率提示阈值（低于此值提示）。 */
        private const val SUCCESS_RATE_INFO_THRESHOLD = 0.85

        /** 成功率警告阈值。 */
        private const val SUCCESS_RATE_WARNING_THRESHOLD = 0.70

        /** 成功率严重阈值。 */
        private const val SUCCESS_RATE_CRITICAL_THRESHOLD = 0.50

        // ---- Token 用量阈值 ----

        /** Token 用量提示阈值。 */
        private const val TOKEN_INFO_THRESHOLD = 1200

        /** Token 用量警告阈值。 */
        private const val TOKEN_WARNING_THRESHOLD = 2000

        /** Token 用量严重阈值。 */
        private const val TOKEN_CRITICAL_THRESHOLD = 4000

        // ---- 重试次数阈值 ----

        /** 重试次数提示阈值。 */
        private const val RETRY_INFO_THRESHOLD = 1.0

        /** 重试次数警告阈值。 */
        private const val RETRY_WARNING_THRESHOLD = 3.0

        /** 重试次数严重阈值。 */
        private const val RETRY_CRITICAL_THRESHOLD = 5.0

        // ---- 内存占用阈值（MB）----

        /** 内存占用提示阈值。 */
        private const val MEMORY_INFO_THRESHOLD_MB = 200L

        /** 内存占用警告阈值。 */
        private const val MEMORY_WARNING_THRESHOLD_MB = 300L

        /** 内存占用严重阈值。 */
        private const val MEMORY_CRITICAL_THRESHOLD_MB = 500L

        // ---- 优化建议相关阈值 ----

        /** 高频动作阈值：同一动作累计执行次数达到此值时考虑缓存建议。 */
        private const val HIGH_FREQUENCY_ACTION_THRESHOLD = 10

        /** 细粒度动作判定阈值（毫秒）：耗时低于此值视为细粒度动作，可考虑批量。 */
        private const val FINE_GRAINED_ACTION_THRESHOLD_MS = 500L

        /** 批量建议触发阈值：细粒度动作累计数达到此值时建议批量。 */
        private const val BATCH_SUGGESTION_THRESHOLD = 15

        /** 冗余动作建议触发阈值：截图/等待类动作累计占比达到此比例时建议跳过冗余。 */
        private const val REDUNDANT_RATIO_THRESHOLD = 0.3

        /** 默认自动监控采样间隔（毫秒）。 */
        private const val DEFAULT_MONITORING_INTERVAL_MS = 30_000L
    }

    // =========================================================================
    //  状态字段
    // =========================================================================

    /**
     * 各动作的指标记录，使用 [ConcurrentHashMap] 保证多线程安全。
     *
     * 键为动作名称（[ClawAction.actionName]），值为该动作的时序指标列表。
     * 列表级别的读写操作通过 `synchronized` 块保护。
     */
    private val metrics: ConcurrentHashMap<String, MutableList<ActionMetric>> =
        ConcurrentHashMap()

    /**
     * 系统资源快照队列，使用 [ConcurrentLinkedDeque] 保证无锁并发安全。
     *
     * 按时间顺序存储，容量上限为 [MAX_SNAPSHOTS]，超出时淘汰最早的快照。
     */
    private val snapshots: ConcurrentLinkedDeque<PerformanceSnapshot> = ConcurrentLinkedDeque()

    /** 当前对比基准（可通过 [setBenchmark] 设置，[compareWithBenchmark] 默认使用）。 */
    @Volatile
    private var currentBenchmark: Benchmark? = null

    /** 累计动作总数（线程安全）。 */
    private val totalActionCount = AtomicLong(0L)

    /** 累计成功动作数（线程安全）。 */
    private val totalSuccessCount = AtomicLong(0L)

    /** 累计 Token 消耗（线程安全）。 */
    private val totalTokenCount = AtomicLong(0L)

    /** 自动监控任务句柄。 */
    @Volatile
    private var monitoringJob: Job? = null

    // =========================================================================
    //  核心方法 —— 指标采集
    // =========================================================================

    /**
     * 记录一条动作执行指标。
     *
     * 线程安全，可在任意线程调用。自动从 [ClawAction] 提取动作名称与类型，
     * 从 [ClawActionResult] 提取成功状态，并附加当前时间戳。
     * 同时更新累计统计计数（总动作数、成功数、Token 消耗）。
     * 当某动作的记录数超过 [MAX_METRICS_PER_ACTION] 时，自动淘汰最早的记录。
     *
     * @param action 执行的动作。
     * @param result 动作执行结果。
     * @param durationMs 执行耗时（毫秒）。
     * @param tokenUsage 本次动作消耗的 AI Token 数。
     * @param retryCount 失败后的重试次数（首次成功为 0）。
     */
    fun recordMetric(
        action: ClawAction,
        result: ClawActionResult,
        durationMs: Long,
        tokenUsage: Int,
        retryCount: Int
    ) {
        val metric = ActionMetric(
            actionName = action.actionName,
            actionType = action.type,
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs,
            tokenUsage = tokenUsage,
            success = result.success,
            retryCount = retryCount
        )

        val list = metrics.computeIfAbsent(action.actionName) { mutableListOf() }
        synchronized(list) {
            list.add(metric)
            while (list.size > MAX_METRICS_PER_ACTION) {
                list.removeAt(0)
            }
        }

        // 更新累计统计计数
        totalActionCount.incrementAndGet()
        if (result.success) {
            totalSuccessCount.incrementAndGet()
        }
        totalTokenCount.addAndGet(tokenUsage.toLong())
    }

    /**
     * 记录一条系统资源快照。
     *
     * 线程安全，可在任意线程调用。快照自动附加当前时间戳和累计统计信息，
     * 并入队到快照队列，超出 [MAX_SNAPSHOTS] 时淘汰最早的快照。
     *
     * @param memoryUsageMb 应用内存占用（MB）。
     * @param cpuUsagePercent CPU 使用率（0.0~100.0）。
     * @param batteryImpact 电池影响估算值（0.0~1.0，越大越耗电）。
     */
    fun recordResourceSnapshot(
        memoryUsageMb: Long,
        cpuUsagePercent: Double,
        batteryImpact: Double
    ) {
        val total = totalActionCount.get()
        val success = totalSuccessCount.get()
        val tokens = totalTokenCount.get()
        val successRate = if (total > 0) success.toDouble() / total else 0.0
        val avgDuration = computeOverallAvgDuration()

        val snapshot = PerformanceSnapshot(
            timestamp = System.currentTimeMillis(),
            memoryUsageMb = memoryUsageMb,
            cpuUsagePercent = cpuUsagePercent,
            batteryImpact = batteryImpact,
            totalActions = total.toInt(),
            successRate = successRate,
            avgDurationMs = avgDuration,
            totalTokens = tokens.toInt()
        )

        snapshots.add(snapshot)
        while (snapshots.size > MAX_SNAPSHOTS) {
            snapshots.pollFirst()
        }
    }

    /**
     * 获取指定动作的指标记录（线程安全拷贝）。
     *
     * @param actionName 动作名称；为 null 时返回全部动作的指标（按时间合并排序）。
     * @return 指标记录列表的副本（按时间顺序）。
     */
    fun getMetrics(actionName: String? = null): List<ActionMetric> {
        return if (actionName != null) {
            val list = metrics[actionName] ?: return emptyList()
            synchronized(list) { list.toList() }
        } else {
            // 合并全部动作的指标并按时间戳排序
            val all = ArrayList<ActionMetric>()
            for ((_, list) in metrics) {
                synchronized(list) { all.addAll(list) }
            }
            all.sortedBy { it.timestamp }
        }
    }

    // =========================================================================
    //  核心方法 —— 瓶颈检测
    // =========================================================================

    /**
     * 检测当前性能瓶颈。
     *
     * 遍历全部动作的聚合指标与最新资源快照，按阈值检测以下五类瓶颈：
     * 1. 慢动作（DURATION）：平均耗时超过耗时阈值。
     * 2. 高频失败（SUCCESS_RATE）：成功率低于成功率阈值。
     * 3. 高 Token 消耗（TOKEN_USAGE）：平均 Token 超过 Token 阈值。
     * 4. 高重试（RETRY_COUNT）：平均重试次数超过重试阈值。
     * 5. 高内存占用（MEMORY_USAGE）：最新快照内存超过内存阈值。
     *
     * 严重程度依据各指标的三级阈值判定（INFO/WARNING/CRITICAL）。
     * 结果按严重程度从高到低排序。
     *
     * @return 瓶颈列表（无瓶颈时为空）。
     */
    fun detectBottlenecks(): List<Bottleneck> {
        val bottlenecks = mutableListOf<Bottleneck>()
        val now = System.currentTimeMillis()

        // 遍历每个动作，检测动作级瓶颈
        for ((actionName, list) in metrics) {
            val samples = synchronized(list) { list.toList() }
            if (samples.isEmpty()) continue

            val avgDuration = samples.map { it.durationMs.toDouble() }.average()
            val successRate = samples.count { it.success }.toDouble() / samples.size
            val avgTokens = samples.map { it.tokenUsage.toDouble() }.average()
            val avgRetry = samples.map { it.retryCount.toDouble() }.average()

            // 慢动作检测
            val durationSeverity = severityForDuration(avgDuration)
            if (durationSeverity != null) {
                bottlenecks.add(
                    Bottleneck(
                        metricType = MetricType.DURATION,
                        actionName = actionName,
                        description = "动作「$actionName」平均耗时 ${formatDuration(avgDuration)}，" +
                            "超过${severityName(durationSeverity)}阈值",
                        severity = durationSeverity,
                        currentValue = avgDuration,
                        thresholdValue = thresholdForDuration(durationSeverity).toDouble(),
                        detectedAt = now
                    )
                )
            }

            // 高频失败检测
            val successSeverity = severityForSuccessRate(successRate)
            if (successSeverity != null) {
                bottlenecks.add(
                    Bottleneck(
                        metricType = MetricType.SUCCESS_RATE,
                        actionName = actionName,
                        description = "动作「$actionName」成功率 ${formatPercent(successRate)}，" +
                            "低于${severityName(successSeverity)}阈值",
                        severity = successSeverity,
                        currentValue = successRate,
                        thresholdValue = thresholdForSuccessRate(successSeverity),
                        detectedAt = now
                    )
                )
            }

            // 高 Token 消耗检测
            val tokenSeverity = severityForToken(avgTokens)
            if (tokenSeverity != null) {
                bottlenecks.add(
                    Bottleneck(
                        metricType = MetricType.TOKEN_USAGE,
                        actionName = actionName,
                        description = "动作「$actionName」平均消耗 ${avgTokens.toInt()} tokens，" +
                            "超过${severityName(tokenSeverity)}阈值",
                        severity = tokenSeverity,
                        currentValue = avgTokens,
                        thresholdValue = thresholdForToken(tokenSeverity).toDouble(),
                        detectedAt = now
                    )
                )
            }

            // 高重试检测
            val retrySeverity = severityForRetry(avgRetry)
            if (retrySeverity != null) {
                bottlenecks.add(
                    Bottleneck(
                        metricType = MetricType.RETRY_COUNT,
                        actionName = actionName,
                        description = "动作「$actionName」平均重试 ${"%.1f".format(avgRetry)} 次，" +
                            "超过${severityName(retrySeverity)}阈值",
                        severity = retrySeverity,
                        currentValue = avgRetry,
                        thresholdValue = thresholdForRetry(retrySeverity),
                        detectedAt = now
                    )
                )
            }
        }

        // 内存占用检测（基于最新快照）
        val latestSnapshot = snapshots.peekLast()
        if (latestSnapshot != null) {
            val memorySeverity = severityForMemory(latestSnapshot.memoryUsageMb)
            if (memorySeverity != null) {
                bottlenecks.add(
                    Bottleneck(
                        metricType = MetricType.MEMORY_USAGE,
                        actionName = null,
                        description = "内存占用 ${latestSnapshot.memoryUsageMb}MB，" +
                            "超过${severityName(memorySeverity)}阈值",
                        severity = memorySeverity,
                        currentValue = latestSnapshot.memoryUsageMb.toDouble(),
                        thresholdValue = thresholdForMemory(memorySeverity).toDouble(),
                        detectedAt = now
                    )
                )
            }
        }

        // 按严重程度排序：CRITICAL > WARNING > INFO
        return bottlenecks.sortedBy { it.severity.ordinal }
    }

    // =========================================================================
    //  核心方法 —— 趋势分析
    // =========================================================================

    /**
     * 获取指定指标的趋势方向。
     *
     * 基于近期 [TREND_SAMPLE_SIZE] 个采样的简单线性回归斜率和变异系数判定：
     * - 采样数不足 [MIN_TREND_SAMPLES] → [TrendDirection.INSUFFICIENT_DATA]
     * - 变异系数 > [VOLATILITY_THRESHOLD] → [TrendDirection.STABLE]（波动过大无法判定方向）
     * - 归一化斜率绝对值 < [TREND_SLOPE_THRESHOLD] → [TrendDirection.STABLE]
     * - 斜率方向结合指标方向 → [TrendDirection.IMPROVING] 或 [TrendDirection.DEGRADING]
     *
     * @param metricType 指标类型。
     * @param actionName 动作名称；为 null 时使用全部动作的聚合数据。
     *                   内存指标（MEMORY_USAGE）忽略此参数，使用资源快照。
     * @return 趋势方向。
     */
    fun getTrend(metricType: MetricType, actionName: String? = null): TrendDirection {
        val series = extractMetricSeries(metricType, actionName)
        if (series.size < MIN_TREND_SAMPLES) return TrendDirection.INSUFFICIENT_DATA

        val recent = series.takeLast(TREND_SAMPLE_SIZE)
        val mean = computeMean(recent)
        if (mean == 0.0) return TrendDirection.STABLE

        val stdDev = computeStdDev(recent, mean)
        val cv = abs(stdDev / mean)

        // 变异系数过高，波动剧烈，视为稳定（无法判定方向）
        if (cv > VOLATILITY_THRESHOLD) return TrendDirection.STABLE

        // 计算线性回归斜率（以采样序号为 x 轴）
        val slope = computeTrendSlope(recent)
        val normalizedSlope = slope / abs(mean)

        // 斜率接近零，判定为稳定
        if (abs(normalizedSlope) < TREND_SLOPE_THRESHOLD) return TrendDirection.STABLE

        val higherBetter = isHigherBetter(metricType)
        return when {
            higherBetter && slope > 0 -> TrendDirection.IMPROVING
            higherBetter && slope < 0 -> TrendDirection.DEGRADING
            !higherBetter && slope < 0 -> TrendDirection.IMPROVING
            !higherBetter && slope > 0 -> TrendDirection.DEGRADING
            else -> TrendDirection.STABLE
        }
    }

    // =========================================================================
    //  核心方法 —— 优化建议
    // =========================================================================

    /**
     * 基于瓶颈分析生成优化建议。
     *
     * 综合 [detectBottlenecks] 的结果与动作频率统计，生成具体可执行的优化建议：
     * - [SuggestionType.OPTIMIZE_ACTION]：针对慢动作建议专项优化。
     * - [SuggestionType.REDUCE_TOKENS]：针对高 Token 消耗建议精简上下文。
     * - [SuggestionType.CACHE_MORE]：针对高频重复动作建议启用缓存。
     * - [SuggestionType.BATCH_OPERATIONS]：针对大量细粒度动作建议批量执行。
     * - [SuggestionType.SKIP_REDUNDANT]：针对冗余截图/等待动作建议跳过。
     *
     * 建议按优先级（越小越紧急）排序。
     *
     * @return 优化建议列表（无建议时为空）。
     */
    fun getOptimizationSuggestions(): List<OptimizationSuggestion> {
        val suggestions = mutableListOf<OptimizationSuggestion>()
        val bottlenecks = detectBottlenecks()

        // 1. 慢动作 → 优化动作建议
        bottlenecks.filter { it.metricType == MetricType.DURATION }.forEach { b ->
            val action = b.actionName ?: return@forEach
            val improvement = estimateImprovement(b)
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.OPTIMIZE_ACTION,
                    title = "优化慢动作「$action」",
                    description = "动作「$action」平均耗时 ${formatDuration(b.currentValue)}，" +
                        "建议减少不必要的等待、合并连续步骤或优化无障碍操作路径。" +
                        "可结合 ActionPreheater 预热后续屏幕状态以缩短感知耗时。",
                    estimatedImprovement = improvement,
                    priority = if (b.severity == AlertLevel.CRITICAL) 1 else 2,
                    relatedActionName = action
                )
            )
        }

        // 2. 高 Token 消耗 → 削减 Token 建议
        bottlenecks.filter { it.metricType == MetricType.TOKEN_USAGE }.forEach { b ->
            val action = b.actionName
            val improvement = estimateImprovement(b)
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.REDUCE_TOKENS,
                    title = "削减 Token 消耗" + (action?.let { "（$it）" } ?: ""),
                    description = (action?.let { "动作「$it」" } ?: "整体") +
                        "平均消耗 ${b.currentValue.toInt()} tokens，" +
                        "建议精简系统提示词、截断屏幕文本、启用 ContextPruner 裁剪历史上下文，" +
                        "并对相似屏幕复用缓存响应。",
                    estimatedImprovement = improvement,
                    priority = if (b.severity == AlertLevel.CRITICAL) 1 else 3,
                    relatedActionName = action
                )
            )
        }

        // 3. 高频重复动作 → 缓存建议
        for ((actionName, list) in metrics) {
            val count = synchronized(list) { list.size }
            if (count >= HIGH_FREQUENCY_ACTION_THRESHOLD) {
                // 仅在尚未因其他瓶颈覆盖时补充缓存建议
                suggestions.add(
                    OptimizationSuggestion(
                        type = SuggestionType.CACHE_MORE,
                        title = "为高频动作「$actionName」启用缓存",
                        description = "动作「$actionName」已累计执行 $count 次，" +
                            "建议对相同参数的结果启用 ResponseCache / ScreenStateCache 缓存，" +
                            "避免重复 AI 调用与屏幕采集。",
                        estimatedImprovement = 20.0,
                        priority = 3,
                        relatedActionName = actionName
                    )
                )
                break // 每类建议给出最具代表性的一条即可
            }
        }

        // 4. 大量细粒度动作 → 批量操作建议
        val fineGrainedCount = countFineGrainedActions()
        if (fineGrainedCount >= BATCH_SUGGESTION_THRESHOLD) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.BATCH_OPERATIONS,
                    title = "合并细粒度动作为批量操作",
                    description = "检测到 $fineGrainedCount 个耗时低于" +
                        "${FINE_GRAINED_ACTION_THRESHOLD_MS}ms 的细粒度动作，" +
                        "建议使用 SmartActionBatcher 将相邻同类操作合并，" +
                        "减少执行流水线往返开销。",
                    estimatedImprovement = 15.0,
                    priority = 4,
                    relatedActionName = null
                )
            )
        }

        // 5. 冗余截图/等待动作 → 跳过冗余建议
        val redundantRatio = computeRedundantRatio()
        if (redundantRatio >= REDUNDANT_RATIO_THRESHOLD) {
            suggestions.add(
                OptimizationSuggestion(
                    type = SuggestionType.SKIP_REDUNDANT,
                    title = "跳过冗余的截图/等待动作",
                    description = "截图与等待类动作占比达 ${formatPercent(redundantRatio)}，" +
                        "建议启用 ScreenStateCache 复用近期截图、使用 SmartTimeoutManager " +
                        "动态缩短等待时间，跳过无变化的冗余采集。",
                    estimatedImprovement = 10.0,
                    priority = 4,
                    relatedActionName = null
                )
            )
        }

        // 按优先级排序
        return suggestions.sortedBy { it.priority }
    }

    // =========================================================================
    //  核心方法 —— 告警系统
    // =========================================================================

    /**
     * 检查性能告警。
     *
     * 基于当前指标与阈值的偏离程度，按 INFO/WARNING/CRITICAL 三级生成告警。
     * 与 [detectBottlenecks] 共享阈值逻辑，但输出形式侧重「严重程度 + 消息」，
     * 适用于日志输出、UI 提示和自动化告警回调。
     *
     * @return 告警列表（无告警时为空），按级别从高到低排序。
     */
    fun checkAlerts(): List<PerformanceAlert> {
        val alerts = mutableListOf<PerformanceAlert>()
        val now = System.currentTimeMillis()

        // 动作级告警
        for ((actionName, list) in metrics) {
            val samples = synchronized(list) { list.toList() }
            if (samples.isEmpty()) continue

            val avgDuration = samples.map { it.durationMs.toDouble() }.average()
            val successRate = samples.count { it.success }.toDouble() / samples.size
            val avgTokens = samples.map { it.tokenUsage.toDouble() }.average()
            val avgRetry = samples.map { it.retryCount.toDouble() }.average()

            severityForDuration(avgDuration)?.let { sev ->
                alerts.add(
                    PerformanceAlert(
                        level = sev,
                        metricType = MetricType.DURATION,
                        message = "动作「$actionName」平均耗时 ${formatDuration(avgDuration)}，" +
                            "达到${severityName(sev)}级别",
                        value = avgDuration,
                        threshold = thresholdForDuration(sev).toDouble(),
                        timestamp = now
                    )
                )
            }

            severityForSuccessRate(successRate)?.let { sev ->
                alerts.add(
                    PerformanceAlert(
                        level = sev,
                        metricType = MetricType.SUCCESS_RATE,
                        message = "动作「$actionName」成功率 ${formatPercent(successRate)}，" +
                            "达到${severityName(sev)}级别",
                        value = successRate,
                        threshold = thresholdForSuccessRate(sev),
                        timestamp = now
                    )
                )
            }

            severityForToken(avgTokens)?.let { sev ->
                alerts.add(
                    PerformanceAlert(
                        level = sev,
                        metricType = MetricType.TOKEN_USAGE,
                        message = "动作「$actionName」平均消耗 ${avgTokens.toInt()} tokens，" +
                            "达到${severityName(sev)}级别",
                        value = avgTokens,
                        threshold = thresholdForToken(sev).toDouble(),
                        timestamp = now
                    )
                )
            }

            severityForRetry(avgRetry)?.let { sev ->
                alerts.add(
                    PerformanceAlert(
                        level = sev,
                        metricType = MetricType.RETRY_COUNT,
                        message = "动作「$actionName」平均重试 ${"%.1f".format(avgRetry)} 次，" +
                            "达到${severityName(sev)}级别",
                        value = avgRetry,
                        threshold = thresholdForRetry(sev),
                        timestamp = now
                    )
                )
            }
        }

        // 内存告警
        val latestSnapshot = snapshots.peekLast()
        if (latestSnapshot != null) {
            severityForMemory(latestSnapshot.memoryUsageMb)?.let { sev ->
                alerts.add(
                    PerformanceAlert(
                        level = sev,
                        metricType = MetricType.MEMORY_USAGE,
                        message = "内存占用 ${latestSnapshot.memoryUsageMb}MB，" +
                            "达到${severityName(sev)}级别",
                        value = latestSnapshot.memoryUsageMb.toDouble(),
                        threshold = thresholdForMemory(sev).toDouble(),
                        timestamp = now
                    )
                )
            }
        }

        return alerts.sortedBy { it.level.ordinal }
    }

    // =========================================================================
    //  核心方法 —— 基准对比
    // =========================================================================

    /**
     * 设置当前对比基准。
     *
     * 设置后 [compareWithBenchmark] 在不传参时默认使用该基准。
     *
     * @param benchmark 基准对象。
     */
    fun setBenchmark(benchmark: Benchmark) {
        currentBenchmark = benchmark
    }

    /**
     * 将当前性能与基准逐项对比。
     *
     * 计算当前平均耗时、成功率、平均 Token、平均重试、最新内存与基准的差异百分比，
     * 并综合判定整体结论（IMPROVING/DEGRADING/STABLE/INSUFFICIENT_DATA）。
     * 对于「越低越好」的指标，当前值低于基准代表改善。
     *
     * @param benchmark 对比基准；为 null 时使用 [setBenchmark] 设置的当前基准。
     * @return 基准对比结果，无数据或无基准时 overall 为 INSUFFICIENT_DATA。
     */
    fun compareWithBenchmark(benchmark: Benchmark? = null): BenchmarkComparison {
        val target = benchmark ?: currentBenchmark
            ?: return BenchmarkComparison(
                benchmarkName = "无",
                durationChangePercent = 0.0,
                successRateChangePercent = 0.0,
                tokenUsageChangePercent = 0.0,
                retryCountChangePercent = 0.0,
                memoryChangePercent = 0.0,
                overall = TrendDirection.INSUFFICIENT_DATA,
                summary = "未设置对比基准，无法对比"
            )

        val allMetrics = getMetrics(null)
        if (allMetrics.isEmpty() && snapshots.isEmpty()) {
            return BenchmarkComparison(
                benchmarkName = target.name,
                durationChangePercent = 0.0,
                successRateChangePercent = 0.0,
                tokenUsageChangePercent = 0.0,
                retryCountChangePercent = 0.0,
                memoryChangePercent = 0.0,
                overall = TrendDirection.INSUFFICIENT_DATA,
                summary = "当前无性能数据，无法与基准「${target.name}」对比"
            )
        }

        val currentDuration = if (allMetrics.isNotEmpty()) {
            allMetrics.map { it.durationMs.toDouble() }.average()
        } else 0.0
        val currentSuccess = if (allMetrics.isNotEmpty()) {
            allMetrics.count { it.success }.toDouble() / allMetrics.size
        } else 0.0
        val currentTokens = if (allMetrics.isNotEmpty()) {
            allMetrics.map { it.tokenUsage.toDouble() }.average()
        } else 0.0
        val currentRetry = if (allMetrics.isNotEmpty()) {
            allMetrics.map { it.retryCount.toDouble() }.average()
        } else 0.0
        val currentMemory = snapshots.peekLast()?.memoryUsageMb?.toDouble() ?: 0.0

        val durationChange = percentChange(currentDuration, target.avgDurationMs)
        val successChange = percentChange(currentSuccess, target.successRate)
        val tokenChange = percentChange(currentTokens, target.avgTokenUsage.toDouble())
        val retryChange = percentChange(currentRetry, target.avgRetryCount)
        val memoryChange = percentChange(currentMemory, target.memoryUsageMb.toDouble())

        // 综合判定：统计改善/退化的维度数
        // 越低越好的指标：负变化 = 改善；越高越好的指标：正变化 = 改善
        var improvingCount = 0
        var degradingCount = 0
        if (durationChange < -5.0) improvingCount++ else if (durationChange > 5.0) degradingCount++
        if (successChange > 5.0) improvingCount++ else if (successChange < -5.0) degradingCount++
        if (tokenChange < -5.0) improvingCount++ else if (tokenChange > 5.0) degradingCount++
        if (retryChange < -5.0) improvingCount++ else if (retryChange > 5.0) degradingCount++
        if (memoryChange < -5.0) improvingCount++ else if (memoryChange > 5.0) degradingCount++

        val overall = when {
            improvingCount > degradingCount && improvingCount >= 2 -> TrendDirection.IMPROVING
            degradingCount > improvingCount && degradingCount >= 2 -> TrendDirection.DEGRADING
            improvingCount == 0 && degradingCount == 0 -> TrendDirection.STABLE
            else -> TrendDirection.STABLE
        }

        val summary = buildString {
            append("与基准「${target.name}」对比：")
            append("耗时${formatSignedPercent(durationChange)}，")
            append("成功率${formatSignedPercent(successChange)}，")
            append("Token${formatSignedPercent(tokenChange)}，")
            append("重试${formatSignedPercent(retryChange)}，")
            append("内存${formatSignedPercent(memoryChange)}。")
            append("整体${when (overall) {
                TrendDirection.IMPROVING -> "优于基准"
                TrendDirection.DEGRADING -> "劣于基准"
                TrendDirection.STABLE -> "与基准持平"
                TrendDirection.INSUFFICIENT_DATA -> "数据不足"
            }}")
        }

        return BenchmarkComparison(
            benchmarkName = target.name,
            durationChangePercent = durationChange,
            successRateChangePercent = successChange,
            tokenUsageChangePercent = tokenChange,
            retryCountChangePercent = retryChange,
            memoryChangePercent = memoryChange,
            overall = overall,
            summary = summary
        )
    }

    // =========================================================================
    //  核心方法 —— 报告生成
    // =========================================================================

    /**
     * 生成综合性能报告。
     *
     * 汇总瓶颈、趋势、告警、优化建议与基准对比，生成多行可读报告，
     * 适用于日志输出与 UI 展示。
     *
     * @return 多行性能报告字符串。
     */
    fun getPerformanceReport(): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════ PerformanceMonitor 性能报告 ═══════════════")

        // 总体统计
        val total = totalActionCount.get()
        val success = totalSuccessCount.get()
        val tokens = totalTokenCount.get()
        val successRate = if (total > 0) success.toDouble() / total else 0.0
        sb.appendLine("【总体统计】动作数=$total | 成功率=${formatPercent(successRate)} | " +
            "累计Token=$tokens | 快照数=${snapshots.size}")
        sb.appendLine()

        // 趋势
        sb.appendLine("【性能趋势】")
        for (type in MetricType.entries) {
            val trend = getTrend(type)
            sb.append("  ${formatMetricName(type)}: ${trend.name}")
            // 动作级趋势取最具代表性的（样本最多的动作）
            if (type != MetricType.MEMORY_USAGE) {
                val topAction = metrics.entries.maxByOrNull { e ->
                    synchronized(e.value) { e.value.size }
                }?.key
                if (topAction != null) {
                    val actionTrend = getTrend(type, topAction)
                    sb.append("（动作「$topAction」: ${actionTrend.name}）")
                }
            }
            sb.appendLine()
        }
        sb.appendLine()

        // 瓶颈
        val bottlenecks = detectBottlenecks()
        sb.appendLine("【性能瓶颈】共 ${bottlenecks.size} 项")
        if (bottlenecks.isEmpty()) {
            sb.appendLine("  无明显瓶颈")
        } else {
            bottlenecks.take(10).forEach { b ->
                sb.appendLine("  [${b.severity.name}] ${b.description}")
            }
            if (bottlenecks.size > 10) {
                sb.appendLine("  ... 其余 ${bottlenecks.size - 10} 项省略")
            }
        }
        sb.appendLine()

        // 告警
        val alerts = checkAlerts()
        sb.appendLine("【性能告警】共 ${alerts.size} 条")
        if (alerts.isEmpty()) {
            sb.appendLine("  无告警")
        } else {
            alerts.take(10).forEach { a ->
                sb.appendLine("  [${a.level.name}] ${a.message}")
            }
            if (alerts.size > 10) {
                sb.appendLine("  ... 其余 ${alerts.size - 10} 条省略")
            }
        }
        sb.appendLine()

        // 优化建议
        val suggestions = getOptimizationSuggestions()
        sb.appendLine("【优化建议】共 ${suggestions.size} 条")
        if (suggestions.isEmpty()) {
            sb.appendLine("  暂无优化建议")
        } else {
            suggestions.forEach { s ->
                sb.appendLine("  [P${s.priority}] ${s.title}（预估提升${s.estimatedImprovement.toInt()}%）")
                sb.appendLine("        ${s.description}")
            }
        }
        sb.appendLine()

        // 基准对比
        val comparison = compareWithBenchmark()
        sb.appendLine("【基准对比】${comparison.summary}")
        sb.appendLine()

        sb.appendLine("═══════════════════════════════════════════════════════════")
        return sb.toString()
    }

    // =========================================================================
    //  异步方法 —— 自动监控
    // =========================================================================

    /**
     * 启动后台自动监控。
     *
     * 在 [scope] 中周期性调用 [sampler] 采集系统资源快照并记录，
     * 同时执行告警检查，将 CRITICAL 级告警输出到日志。
     * 重复调用会先取消已有监控任务再启动新的。
     *
     * @param sampler 资源采样函数，返回 [PerformanceSnapshot] 所需的核心字段封装；
     *                返回 null 时跳过本次采样。实际取内存/CPU/电池值。
     *                为简化签名，sampler 返回 Triple(内存MB, CPU%, 电池影响)。
     * @param intervalMs 采样间隔（毫秒），默认 [DEFAULT_MONITORING_INTERVAL_MS]。
     * @return 监控任务句柄，可用于取消。
     */
    fun startAutoMonitoring(
        sampler: () -> Triple<Long, Double, Double>?,
        intervalMs: Long = DEFAULT_MONITORING_INTERVAL_MS
    ): Job {
        stopMonitoring()
        val job = scope.launch {
            while (true) {
                try {
                    val sampled = sampler()
                    if (sampled != null) {
                        recordResourceSnapshot(
                            memoryUsageMb = sampled.first,
                            cpuUsagePercent = sampled.second,
                            batteryImpact = sampled.third
                        )
                    }
                    // 检查告警并输出严重告警
                    val alerts = checkAlerts()
                    alerts.filter { it.level == AlertLevel.CRITICAL }.forEach { alert ->
                        Log.w(tag, "[严重告警] ${alert.message}")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "自动监控采样异常", e)
                }
                delay(intervalMs)
            }
        }
        monitoringJob = job
        return job
    }

    /**
     * 异步执行性能诊断。
     *
     * 在 [Dispatchers.Default] 上执行瓶颈检测，完成后回调主调方，
     * 避免在 UI 线程阻塞。适用于指标积累较多时的离线分析。
     *
     * @param onComplete 诊断完成回调，参数为瓶颈列表。
     * @return 诊断任务句柄。
     */
    fun runDiagnosticsAsync(onComplete: (List<Bottleneck>) -> Unit): Job {
        return scope.launch(Dispatchers.Default) {
            val bottlenecks = detectBottlenecks()
            onComplete(bottlenecks)
        }
    }

    /**
     * 停止后台自动监控。
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    // =========================================================================
    //  管理方法
    // =========================================================================

    /**
     * 清空所有动作指标与资源快照，重置累计统计计数。
     *
     * 适用于测试或需要重新开始监控的场景。不会清除已设置的基准。
     */
    fun clear() {
        metrics.clear()
        snapshots.clear()
        totalActionCount.set(0L)
        totalSuccessCount.set(0L)
        totalTokenCount.set(0L)
    }

    /**
     * 清空指定动作的指标记录。
     *
     * @param actionName 动作名称。
     */
    fun clearMetric(actionName: String) {
        metrics.remove(actionName)
    }

    /**
     * 获取已记录的动作种类数。
     *
     * @return 动作种类数。
     */
    fun getActionTypeCount(): Int = metrics.size

    /**
     * 获取资源快照数量。
     *
     * @return 快照数量。
     */
    fun getSnapshotCount(): Int = snapshots.size

    // =========================================================================
    //  内部辅助方法 —— 指标序列提取与统计计算
    // =========================================================================

    /**
     * 提取指定指标的时序数值序列。
     *
     * 内存指标（MEMORY_USAGE）从资源快照提取；其余指标从动作指标提取。
     *
     * @param metricType 指标类型。
     * @param actionName 动作名称（null 表示全部聚合）。
     * @return 数值序列（按时间顺序）。
     */
    private fun extractMetricSeries(
        metricType: MetricType,
        actionName: String?
    ): List<Double> {
        if (metricType == MetricType.MEMORY_USAGE) {
            return snapshots.map { it.memoryUsageMb.toDouble() }
        }

        val samples = if (actionName != null) {
            getMetrics(actionName)
        } else {
            getMetrics(null)
        }

        return when (metricType) {
            MetricType.DURATION -> samples.map { it.durationMs.toDouble() }
            MetricType.TOKEN_USAGE -> samples.map { it.tokenUsage.toDouble() }
            MetricType.SUCCESS_RATE -> samples.map { if (it.success) 1.0 else 0.0 }
            MetricType.RETRY_COUNT -> samples.map { it.retryCount.toDouble() }
            MetricType.MEMORY_USAGE -> emptyList() // 已在上方处理
        }
    }

    /**
     * 计算全部动作的整体平均耗时。
     *
     * @return 平均耗时（毫秒），无数据时返回 0.0。
     */
    private fun computeOverallAvgDuration(): Double {
        var sum = 0.0
        var count = 0
        for ((_, list) in metrics) {
            synchronized(list) {
                for (m in list) {
                    sum += m.durationMs
                    count++
                }
            }
        }
        return if (count > 0) sum / count else 0.0
    }

    /**
     * 计算算术平均值。
     *
     * @param values 数值列表。
     * @return 平均值，空列表返回 0.0。
     */
    private fun computeMean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.sum() / values.size
    }

    /**
     * 计算总体标准差。
     *
     * @param values 数值列表。
     * @param mean 均值（可预计算传入，避免重复计算）。
     * @return 标准差，样本数 <= 1 时返回 0.0。
     */
    private fun computeStdDev(values: List<Double>, mean: Double): Double {
        if (values.size <= 1) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    /**
     * 计算简单线性回归斜率。
     *
     * 以采样序号（0, 1, 2, ...）为 x 轴，指标值为 y 轴，使用最小二乘法计算斜率。
     * 斜率为正表示值随时间上升，为负表示下降。
     *
     * @param values 数值序列（按时间顺序）。
     * @return 回归斜率，采样数 < 2 时返回 0.0。
     */
    private fun computeTrendSlope(values: List<Double>): Double {
        val n = values.size
        if (n < 2) return 0.0

        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            val y = values[i]
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        val denominator = n * sumX2 - sumX * sumX
        if (denominator == 0.0) return 0.0

        return (n * sumXY - sumX * sumY) / denominator
    }

    // =========================================================================
    //  内部辅助方法 —— 阈值与严重程度判定
    // =========================================================================

    /**
     * 判断指定指标是否「越高越好」。
     *
     * 成功率越高代表性能越好，其余指标（耗时、Token、重试、内存）越低越好。
     *
     * @param type 指标类型。
     * @return true 表示越高越好（如成功率），false 表示越低越好（如耗时）。
     */
    private fun isHigherBetter(type: MetricType): Boolean {
        return type == MetricType.SUCCESS_RATE
    }

    /** 耗时阈值判定，返回最高达到的严重级别，未超阈值返回 null。 */
    private fun severityForDuration(avgMs: Double): AlertLevel? {
        return when {
            avgMs >= DURATION_CRITICAL_THRESHOLD_MS -> AlertLevel.CRITICAL
            avgMs >= DURATION_WARNING_THRESHOLD_MS -> AlertLevel.WARNING
            avgMs >= DURATION_INFO_THRESHOLD_MS -> AlertLevel.INFO
            else -> null
        }
    }

    /** 成功率阈值判定，返回最低达到的严重级别，未低于阈值返回 null。 */
    private fun severityForSuccessRate(rate: Double): AlertLevel? {
        return when {
            rate <= SUCCESS_RATE_CRITICAL_THRESHOLD -> AlertLevel.CRITICAL
            rate <= SUCCESS_RATE_WARNING_THRESHOLD -> AlertLevel.WARNING
            rate <= SUCCESS_RATE_INFO_THRESHOLD -> AlertLevel.INFO
            else -> null
        }
    }

    /** Token 用量阈值判定，返回最高达到的严重级别，未超阈值返回 null。 */
    private fun severityForToken(avgTokens: Double): AlertLevel? {
        return when {
            avgTokens >= TOKEN_CRITICAL_THRESHOLD -> AlertLevel.CRITICAL
            avgTokens >= TOKEN_WARNING_THRESHOLD -> AlertLevel.WARNING
            avgTokens >= TOKEN_INFO_THRESHOLD -> AlertLevel.INFO
            else -> null
        }
    }

    /** 重试次数阈值判定，返回最高达到的严重级别，未超阈值返回 null。 */
    private fun severityForRetry(avgRetry: Double): AlertLevel? {
        return when {
            avgRetry >= RETRY_CRITICAL_THRESHOLD -> AlertLevel.CRITICAL
            avgRetry >= RETRY_WARNING_THRESHOLD -> AlertLevel.WARNING
            avgRetry >= RETRY_INFO_THRESHOLD -> AlertLevel.INFO
            else -> null
        }
    }

    /** 内存占用阈值判定，返回最高达到的严重级别，未超阈值返回 null。 */
    private fun severityForMemory(memoryMb: Long): AlertLevel? {
        return when {
            memoryMb >= MEMORY_CRITICAL_THRESHOLD_MB -> AlertLevel.CRITICAL
            memoryMb >= MEMORY_WARNING_THRESHOLD_MB -> AlertLevel.WARNING
            memoryMb >= MEMORY_INFO_THRESHOLD_MB -> AlertLevel.INFO
            else -> null
        }
    }

    /** 获取耗时指标对应级别的阈值。 */
    private fun thresholdForDuration(level: AlertLevel): Long {
        return when (level) {
            AlertLevel.INFO -> DURATION_INFO_THRESHOLD_MS
            AlertLevel.WARNING -> DURATION_WARNING_THRESHOLD_MS
            AlertLevel.CRITICAL -> DURATION_CRITICAL_THRESHOLD_MS
        }
    }

    /** 获取成功率指标对应级别的阈值。 */
    private fun thresholdForSuccessRate(level: AlertLevel): Double {
        return when (level) {
            AlertLevel.INFO -> SUCCESS_RATE_INFO_THRESHOLD
            AlertLevel.WARNING -> SUCCESS_RATE_WARNING_THRESHOLD
            AlertLevel.CRITICAL -> SUCCESS_RATE_CRITICAL_THRESHOLD
        }
    }

    /** 获取 Token 用量指标对应级别的阈值。 */
    private fun thresholdForToken(level: AlertLevel): Int {
        return when (level) {
            AlertLevel.INFO -> TOKEN_INFO_THRESHOLD
            AlertLevel.WARNING -> TOKEN_WARNING_THRESHOLD
            AlertLevel.CRITICAL -> TOKEN_CRITICAL_THRESHOLD
        }
    }

    /** 获取重试次数指标对应级别的阈值。 */
    private fun thresholdForRetry(level: AlertLevel): Double {
        return when (level) {
            AlertLevel.INFO -> RETRY_INFO_THRESHOLD
            AlertLevel.WARNING -> RETRY_WARNING_THRESHOLD
            AlertLevel.CRITICAL -> RETRY_CRITICAL_THRESHOLD
        }
    }

    /** 获取内存占用指标对应级别的阈值。 */
    private fun thresholdForMemory(level: AlertLevel): Long {
        return when (level) {
            AlertLevel.INFO -> MEMORY_INFO_THRESHOLD_MB
            AlertLevel.WARNING -> MEMORY_WARNING_THRESHOLD_MB
            AlertLevel.CRITICAL -> MEMORY_CRITICAL_THRESHOLD_MB
        }
    }

    /** 告警级别的中文名称。 */
    private fun severityName(level: AlertLevel): String {
        return when (level) {
            AlertLevel.INFO -> "提示"
            AlertLevel.WARNING -> "警告"
            AlertLevel.CRITICAL -> "严重"
        }
    }

    // =========================================================================
    //  内部辅助方法 —— 优化建议统计
    // =========================================================================

    /**
     * 根据瓶颈严重程度估算优化后的预估改善幅度（百分比）。
     *
     * @param bottleneck 瓶颈。
     * @return 预估改善百分比。
     */
    private fun estimateImprovement(bottleneck: Bottleneck): Double {
        return when (bottleneck.severity) {
            AlertLevel.CRITICAL -> 40.0
            AlertLevel.WARNING -> 25.0
            AlertLevel.INFO -> 15.0
        }
    }

    /**
     * 统计细粒度动作（耗时低于 [FINE_GRAINED_ACTION_THRESHOLD_MS]）的总数。
     *
     * @return 细粒度动作数。
     */
    private fun countFineGrainedActions(): Int {
        var count = 0
        for ((_, list) in metrics) {
            synchronized(list) {
                count += list.count { it.durationMs < FINE_GRAINED_ACTION_THRESHOLD_MS }
            }
        }
        return count
    }

    /**
     * 计算冗余动作（截图/等待类）在全部动作中的占比。
     *
     * @return 占比（0.0~1.0），无数据时返回 0.0。
     */
    private fun computeRedundantRatio(): Double {
        var total = 0
        var redundant = 0
        for ((_, list) in metrics) {
            synchronized(list) {
                for (m in list) {
                    total++
                    val t = m.actionType
                    if (t == ActionType.SCREEN_SCREENSHOT || t == ActionType.SCREEN_WAIT) {
                        redundant++
                    }
                }
            }
        }
        return if (total > 0) redundant.toDouble() / total else 0.0
    }

    // =========================================================================
    //  内部辅助方法 —— 格式化
    // =========================================================================

    /**
     * 获取指标的中文显示名称。
     *
     * @param type 指标类型。
     * @return 中文名称。
     */
    private fun formatMetricName(type: MetricType): String {
        return when (type) {
            MetricType.DURATION -> "执行耗时"
            MetricType.TOKEN_USAGE -> "Token用量"
            MetricType.SUCCESS_RATE -> "成功率"
            MetricType.RETRY_COUNT -> "重试次数"
            MetricType.MEMORY_USAGE -> "内存占用"
        }
    }

    /**
     * 格式化耗时为人类可读字符串。
     *
     * @param ms 耗时（毫秒）。
     * @return 格式化后的字符串（>=1000ms 转为秒）。
     */
    private fun formatDuration(ms: Double): String {
        return if (ms >= 1000.0) {
            "%.2fs".format(ms / 1000.0)
        } else {
            "${ms.toLong()}ms"
        }
    }

    /**
     * 格式化比例为百分比字符串。
     *
     * @param ratio 比例（0.0~1.0）。
     * @return 百分比字符串（如 "85.0%"）。
     */
    private fun formatPercent(ratio: Double): String {
        return "${"%.1f".format(ratio * 100)}%"
    }

    /**
     * 格式化带符号的百分比变化字符串。
     *
     * @param percent 百分比变化值。
     * @return 带正负号的百分比字符串（如 "+12.3%" / "-8.1%"）。
     */
    private fun formatSignedPercent(percent: Double): String {
        val sign = if (percent >= 0) "+" else ""
        return "$sign${"%.1f".format(percent)}%"
    }

    /**
     * 计算当前值相对基准值的变化百分比。
     *
     * @param current 当前值。
     * @param baseline 基准值。
     * @return 变化百分比（正值表示当前高于基准）。
     */
    private fun percentChange(current: Double, baseline: Double): Double {
        if (baseline == 0.0) return 0.0
        return ((current - baseline) / baseline) * 100.0
    }
}
