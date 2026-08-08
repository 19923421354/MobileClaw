package com.mobileclaw.app.ai

import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 性能指标类型。
 *
 * 每种类型对应 MobileClaw 执行流水线中的一个关键性能维度。
 * 其中部分指标「越低越好」（耗时类），部分「越高越好」（成功率），
 * 退化判定逻辑会根据指标方向自动反转，详见 [PerformanceBaseline.isHigherBetter]。
 */
enum class MetricType {
    /** 任务总耗时（毫秒）：从用户输入指令到任务完成的端到端时间。 */
    TASK_DURATION,

    /** AI 调用延迟（毫秒）：单次 AI 接口请求的往返响应时间。 */
    AI_LATENCY,

    /** 动作执行时间（毫秒）：单个屏幕操作（点击、滑动、输入等）的执行耗时。 */
    ACTION_TIME,

    /** 成功率（0.0~1.0）：任务或动作执行成功的比例，越高越好。 */
    SUCCESS_RATE,

    /** Token 用量：单次 AI 调用消耗的 Token 总数（含输入与输出）。 */
    TOKEN_USAGE
}

/**
 * 趋势方向。
 *
 * 由 [PerformanceBaseline.getTrend] 返回，基于近期样本的线性回归斜率
 * 与变异系数综合判定。
 *
 * - [IMPROVING] 改善中：性能指标正朝着好的方向发展。
 * - [STABLE] 稳定：指标波动很小，无明显上升或下降趋势。
 * - [DEGRADING] 退化中：指标正朝着差的方向发展。
 * - [VOLATILE] 波动剧烈：数据波动过大（变异系数超阈值），无法判定稳定趋势。
 */
enum class TrendDirection {
    IMPROVING,
    STABLE,
    DEGRADING,
    VOLATILE
}

/**
 * 性能状态等级。
 *
 * 由当前性能与基线的比值判定，是 [DegradationAlert.severity] 和
 * [PerformanceReport.status] 的取值类型。
 *
 * 判定规则（耗时类指标，越低越好）：
 * - [EXCELLENT] 优秀：当前 < 基线的 0.7 倍（显著改善）。
 * - [GOOD] 良好：当前 < 基线的 0.9 倍（轻微改善）。
 * - [NORMAL] 正常：当前在基线的 0.9~1.5 倍之间。
 * - [DEGRADED] 退化：当前 > 基线的 1.5 倍。
 * - [CRITICAL] 严重：当前 > 基线的 2.0 倍。
 *
 * 成功率类指标的判定方向相反，详见 [PerformanceBaseline.determineStatus]。
 */
enum class PerformanceStatus {
    EXCELLENT,
    GOOD,
    NORMAL,
    DEGRADED,
    CRITICAL
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 单条指标采样记录。
 *
 * 每次 [PerformanceBaseline.recordMetric] 调用都会生成一条采样，
 * 包含时间戳、指标值和可选的分类标签。分类标签用于按应用包名、
 * 任务复杂度等维度分组分析，默认为 "default"。
 *
 * @param timestamp 采样时间戳（epoch 毫秒）。
 * @param value 指标值（耗时类为毫秒，成功率为 0.0~1.0，Token 用量为整数）。
 * @param category 采样分类标签，用于分组分析。
 */
data class MetricSample(
    val timestamp: Long,
    val value: Double,
    val category: String = "default"
)

/**
 * 性能基线统计摘要。
 *
 * 从一批样本中计算的统计量，用于表征「正常」性能水平。
 * 基线由 [PerformanceBaseline.getBaseline] 从首批样本建立，
 * 也由 [PerformanceBaseline.getCurrentPerformance] 从近期样本计算当前性能。
 *
 * @param avg 平均值（算术平均）。
 * @param min 最小值。
 * @param max 最大值。
 * @param stdDev 标准差，反映数据的离散程度。
 * @param sampleCount 参与计算的样本数。
 */
data class Baseline(
    val avg: Double,
    val min: Double,
    val max: Double,
    val stdDev: Double,
    val sampleCount: Int
)

/**
 * 性能退化告警。
 *
 * 当 [PerformanceBaseline.detectDegradation] 检测到性能退化时生成，
 * 包含告警级别、人类可读的描述信息和检测时间。
 * 改善检测（[PerformanceBaseline.detectImprovement]）也复用此类，
 * 此时 severity 为 [PerformanceStatus.EXCELLENT] 或 [PerformanceStatus.GOOD]。
 *
 * @param metric 退化的指标类型。
 * @param severity 严重程度（[PerformanceStatus.DEGRADED] 或 [PerformanceStatus.CRITICAL]）。
 * @param message 人类可读的告警描述（中文）。
 * @param detectedAt 检测时间戳（epoch 毫秒）。
 */
data class DegradationAlert(
    val metric: MetricType,
    val severity: PerformanceStatus,
    val message: String,
    val detectedAt: Long
)

/**
 * 性能报告条目。
 *
 * 由 [PerformanceBaseline.generateReport] 为每个指标生成一条，
 * 汇总基线、当前性能、趋势、状态和告警信息。
 *
 * @param metric 指标类型。
 * @param baseline 基线统计（从首批样本建立），数据不足时 sampleCount 为 0。
 * @param current 当前性能统计（从近期样本计算）。
 * @param trend 趋势方向。
 * @param status 性能状态等级。
 * @param alert 告警信息（无告警时为空字符串）。
 */
data class PerformanceReport(
    val metric: MetricType,
    val baseline: Baseline,
    val current: Baseline,
    val trend: TrendDirection,
    val status: PerformanceStatus,
    val alert: String
)

/**
 * 时间窗口对比结果。
 *
 * 由 [PerformanceBaseline.compareTodayVsYesterday] 和
 * [PerformanceBaseline.compareThisWeekVsLastWeek] 返回，
 * 用于对比两个时间段的性能差异。
 *
 * @param metric 指标类型。
 * @param currentAvg 当前窗口的平均值。
 * @param previousAvg 对比窗口的平均值。
 * @param changePercent 变化百分比（正值表示上升，负值表示下降）。
 * @param direction 变化方向（改善 / 稳定 / 退化）。
 */
data class TimeWindowComparison(
    val metric: MetricType,
    val currentAvg: Double,
    val previousAvg: Double,
    val changePercent: Double,
    val direction: TrendDirection
)

// =============================================================================
//  PerformanceBaseline —— 性能基线监控器
// =============================================================================

/**
 * PerformanceBaseline —— 性能基线监控器
 *
 * 为 MobileClaw 的执行流水线提供持续的性能指标监控与退化检测能力。核心功能包括：
 *
 * 1. **多指标采集**：跟踪 5 类关键性能指标（任务耗时、AI 延迟、动作耗时、成功率、Token 用量），
 *    每次调用 [recordMetric] 记录一条采样。
 * 2. **基线建立**：从首批 N 个样本（默认 20）建立性能基线，包含均值、极值、标准差。
 * 3. **退化检测**：将近期性能与基线对比，当耗时超出基线 1.5 倍（退化）或 2 倍（严重）时
 *    自动生成告警。成功率类指标的判定方向相反（低于基线 0.7 倍为退化）。
 * 4. **改善检测**：当性能显著优于基线时也产生提示，帮助确认优化效果。
 * 5. **趋势分析**：基于简单线性回归计算近期样本的斜率，判定性能是改善、稳定、退化还是波动剧烈。
 * 6. **时间窗口对比**：支持「今天 vs 昨天」「本周 vs 上周」的横向对比，发现周期性变化。
 * 7. **报告生成**：一键生成所有指标的性能报告，含状态、趋势和告警信息。
 *
 * ### 退化判定阈值
 *
 * | 指标方向     | 严重(CRITICAL) | 退化(DEGRADED) | 正常(NORMAL) | 良好(GOOD) | 优秀(EXCELLENT) |
 * |--------------|----------------|-----------------|--------------|------------|------------------|
 * | 越低越好     | > 2.0x 基线    | > 1.5x 基线     | 0.9x~1.5x    | < 0.9x     | < 0.7x           |
 * | 越高越好     | < 0.5x 基线    | < 0.7x 基线     | 0.7x~1.1x    | > 1.1x     | > 1.3x           |
 *
 * ### 线程安全
 *
 * 使用 [ConcurrentHashMap] 存储各指标的采样列表，列表级别的读写操作通过
 * `synchronized` 块保护，确保多线程环境下 [recordMetric] 与查询方法可安全并发。
 *
 * ### 典型调用流程
 * ```
 * val baseline = PerformanceBaseline()
 * // 执行过程中持续记录指标
 * baseline.recordMetric(MetricType.TASK_DURATION, 5200.0, "复杂任务")
 * baseline.recordMetric(MetricType.AI_LATENCY, 1800.0)
 * baseline.recordMetric(MetricType.SUCCESS_RATE, 1.0)
 * baseline.recordMetric(MetricType.TOKEN_USAGE, 850.0)
 * // ... 积累 20+ 样本后 ...
 * // 检测退化
 * val alert = baseline.detectDegradation(MetricType.AI_LATENCY)
 * alert?.let { Log.w("PerformanceBaseline", it.message) }
 * // 生成完整报告
 * val reports = baseline.generateReport()
 * // 时间窗口对比
 * val comparison = baseline.compareTodayVsYesterday(MetricType.TASK_DURATION)
 * ```
 */
class PerformanceBaseline {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 建立基线所需的样本数（默认从前 20 个采样建立基线）。 */
        private const val BASELINE_SAMPLE_COUNT = 20

        /** 当前性能窗口大小（取最近 20 个采样计算当前性能）。 */
        private const val CURRENT_WINDOW_SIZE = 20

        /** 基线有效的最小样本数，低于此数时 getBaseline 返回 null。 */
        private const val MIN_BASELINE_SAMPLES = 5

        /** 趋势分析所需的最小样本数。 */
        private const val MIN_TREND_SAMPLES = 5

        /** 每个指标最多保留的采样数，超出时淘汰最早的样本。 */
        private const val MAX_SAMPLES_PER_METRIC = 1000

        /** 退化阈值：当前值 > 基线均值的 1.5 倍时判定为退化。 */
        private const val DEGRADED_THRESHOLD = 1.5

        /** 严重退化阈值：当前值 > 基线均值的 2.0 倍时判定为严重。 */
        private const val CRITICAL_THRESHOLD = 2.0

        /** 优秀阈值：当前值 < 基线均值的 0.7 倍时判定为优秀（改善）。 */
        private const val EXCELLENT_THRESHOLD = 0.7

        /** 良好阈值：当前值 < 基线均值的 0.9 倍时判定为良好（轻微改善）。 */
        private const val GOOD_THRESHOLD = 0.9

        /** 成功率优秀阈值：当前值 > 基线均值的 1.3 倍时判定为优秀。 */
        private const val SUCCESS_EXCELLENT_THRESHOLD = 1.3

        /** 成功率良好阈值：当前值 > 基线均值的 1.1 倍时判定为良好。 */
        private const val SUCCESS_GOOD_THRESHOLD = 1.1

        /** 成功率退化阈值：当前值 < 基线均值的 0.7 倍时判定为退化。 */
        private const val SUCCESS_DEGRADED_THRESHOLD = 0.7

        /** 成功率严重退化阈值：当前值 < 基线均值的 0.5 倍时判定为严重。 */
        private const val SUCCESS_CRITICAL_THRESHOLD = 0.5

        /** 变异系数阈值：stdDev/avg 超过此值时判定为波动剧烈。 */
        private const val VOLATILITY_THRESHOLD = 0.3

        /** 趋势斜率阈值：归一化斜率的绝对值低于此值时判定为稳定。 */
        private const val TREND_SLOPE_THRESHOLD = 0.05

        /** 一天的毫秒数。 */
        private val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        /** 一周的毫秒数。 */
        private val MILLIS_PER_WEEK = 7L * MILLIS_PER_DAY
    }

    // =========================================================================
    //  状态字段
    // =========================================================================

    /**
     * 各指标的采样列表，使用 [ConcurrentHashMap] 保证多线程安全。
     *
     * 采用懒初始化：首次记录某指标时才创建对应的列表。
     * 列表级别的读写操作通过 `synchronized` 块保护。
     */
    private val samples: ConcurrentHashMap<MetricType, MutableList<MetricSample>> =
        ConcurrentHashMap()

    // =========================================================================
    //  核心方法 —— 采样与查询
    // =========================================================================

    /**
     * 记录一条性能指标采样。
     *
     * 线程安全，可在任意线程调用。采样自动附加当前时间戳。
     * 当某指标的采样数超过 [MAX_SAMPLES_PER_METRIC] 时，自动淘汰最早的样本。
     *
     * @param type 指标类型。
     * @param value 指标值（耗时类为毫秒，成功率为 0.0~1.0，Token 用量为整数）。
     * @param category 采样分类标签（如应用包名、任务复杂度等），默认 "default"。
     */
    fun recordMetric(type: MetricType, value: Double, category: String = "default") {
        val sample = MetricSample(
            timestamp = System.currentTimeMillis(),
            value = value,
            category = category
        )
        val list = samples.computeIfAbsent(type) { mutableListOf() }
        synchronized(list) {
            list.add(sample)
            while (list.size > MAX_SAMPLES_PER_METRIC) {
                list.removeAt(0)
            }
        }
    }

    /**
     * 获取指定指标的基线统计。
     *
     * 基线从前 [BASELINE_SAMPLE_COUNT] 个采样建立。如果总采样数不足
     * [MIN_BASELINE_SAMPLES]，返回 null 表示基线尚未建立。
     *
     * @param type 指标类型。
     * @return 基线统计，数据不足时返回 null。
     */
    fun getBaseline(type: MetricType): Baseline? {
        val all = getAllSamples(type)
        if (all.size < MIN_BASELINE_SAMPLES) return null
        val baselineSamples = all.take(BASELINE_SAMPLE_COUNT)
        return computeBaseline(baselineSamples)
    }

    /**
     * 获取指定指标的当前性能统计。
     *
     * 从最近 [CURRENT_WINDOW_SIZE] 个采样计算当前性能。
     * 此方法不会返回 null，但当无数据时 sampleCount 为 0。
     *
     * @param type 指标类型。
     * @return 当前性能统计。
     */
    fun getCurrentPerformance(type: MetricType): Baseline {
        val recent = getRecentSamples(type, CURRENT_WINDOW_SIZE)
        if (recent.isEmpty()) {
            return Baseline(avg = 0.0, min = 0.0, max = 0.0, stdDev = 0.0, sampleCount = 0)
        }
        return computeBaseline(recent)
    }

    /**
     * 获取指定指标已记录的采样总数。
     *
     * @param type 指标类型。
     * @return 采样数量。
     */
    fun getSampleCount(type: MetricType): Int {
        val list = samples[type] ?: return 0
        return synchronized(list) { list.size }
    }

    // =========================================================================
    //  核心方法 —— 退化与改善检测
    // =========================================================================

    /**
     * 检测指定指标的性能退化。
     *
     * 将当前性能与基线对比：
     * - 耗时类指标（越低越好）：当前均值 > 基线 1.5 倍 → 退化，> 2 倍 → 严重。
     * - 成功率类指标（越高越好）：当前均值 < 基线 0.7 倍 → 退化，< 0.5 倍 → 严重。
     *
     * 基线未建立（样本不足）或性能正常时返回 null。
     *
     * @param type 指标类型。
     * @return 退化告警，无退化时返回 null。
     */
    fun detectDegradation(type: MetricType): DegradationAlert? {
        val baseline = getBaseline(type) ?: return null
        val current = getCurrentPerformance(type)
        if (current.sampleCount == 0) return null

        val status = determineStatus(type, current.avg, baseline.avg)
        if (status != PerformanceStatus.DEGRADED && status != PerformanceStatus.CRITICAL) {
            return null
        }

        return DegradationAlert(
            metric = type,
            severity = status,
            message = generateAlertMessage(type, status, current.avg, baseline.avg, isImprovement = false),
            detectedAt = System.currentTimeMillis()
        )
    }

    /**
     * 检测指定指标的性能改善。
     *
     * 与 [detectDegradation] 互补，当性能显著优于基线时返回提示：
     * - 耗时类指标：当前均值 < 基线 0.7 倍 → 优秀，< 0.9 倍 → 良好。
     * - 成功率类指标：当前均值 > 基线 1.3 倍 → 优秀，> 1.1 倍 → 良好。
     *
     * 基线未建立或性能正常时返回 null。
     *
     * @param type 指标类型。
     * @return 改善告警（severity 为 EXCELLENT 或 GOOD），无改善时返回 null。
     */
    fun detectImprovement(type: MetricType): DegradationAlert? {
        val baseline = getBaseline(type) ?: return null
        val current = getCurrentPerformance(type)
        if (current.sampleCount == 0) return null

        val status = determineStatus(type, current.avg, baseline.avg)
        if (status != PerformanceStatus.EXCELLENT && status != PerformanceStatus.GOOD) {
            return null
        }

        return DegradationAlert(
            metric = type,
            severity = status,
            message = generateAlertMessage(type, status, current.avg, baseline.avg, isImprovement = true),
            detectedAt = System.currentTimeMillis()
        )
    }

    // =========================================================================
    //  核心方法 —— 趋势分析与报告生成
    // =========================================================================

    /**
     * 获取指定指标的趋势方向。
     *
     * 基于最近 [CURRENT_WINDOW_SIZE] 个采样的简单线性回归斜率和变异系数判定：
     * - 变异系数 > [VOLATILITY_THRESHOLD] → [TrendDirection.VOLATILE]
     * - 归一化斜率绝对值 < [TREND_SLOPE_THRESHOLD] → [TrendDirection.STABLE]
     * - 斜率方向与指标方向结合判定 → [TrendDirection.IMPROVING] 或 [TrendDirection.DEGRADING]
     *
     * 采样数不足 [MIN_TREND_SAMPLES] 时返回 [TrendDirection.STABLE]。
     *
     * @param type 指标类型。
     * @return 趋势方向。
     */
    fun getTrend(type: MetricType): TrendDirection {
        val recent = getRecentSamples(type, CURRENT_WINDOW_SIZE)
        if (recent.size < MIN_TREND_SAMPLES) return TrendDirection.STABLE

        val values = recent.map { it.value }
        val mean = computeMean(values)
        if (mean == 0.0) return TrendDirection.STABLE

        val stdDev = computeStdDev(values, mean)
        val cv = abs(stdDev / mean)

        // 变异系数过高，判定为波动剧烈
        if (cv > VOLATILITY_THRESHOLD) return TrendDirection.VOLATILE

        // 计算线性回归斜率（以采样序号为 x 轴）
        val slope = computeTrendSlope(recent)
        val normalizedSlope = slope / abs(mean)

        // 斜率接近零，判定为稳定
        if (abs(normalizedSlope) < TREND_SLOPE_THRESHOLD) return TrendDirection.STABLE

        val higherBetter = isHigherBetter(type)
        return when {
            higherBetter && slope > 0 -> TrendDirection.IMPROVING
            higherBetter && slope < 0 -> TrendDirection.DEGRADING
            !higherBetter && slope < 0 -> TrendDirection.IMPROVING
            !higherBetter && slope > 0 -> TrendDirection.DEGRADING
            else -> TrendDirection.STABLE
        }
    }

    /**
     * 生成所有指标的性能报告。
     *
     * 遍历全部 [MetricType]，为每个指标生成一条 [PerformanceReport]，
     * 包含基线、当前性能、趋势、状态和告警信息。
     * 数据不足的指标也会包含在报告中，状态为 [PerformanceStatus.NORMAL]，
     * 告警信息标注「数据不足」。
     *
     * @return 性能报告列表，每项对应一个指标。
     */
    fun generateReport(): List<PerformanceReport> {
        return MetricType.entries.map { type ->
            val baseline = getBaseline(type)
                ?: Baseline(avg = 0.0, min = 0.0, max = 0.0, stdDev = 0.0, sampleCount = 0)
            val current = getCurrentPerformance(type)
            val trend = getTrend(type)

            if (baseline.sampleCount == 0 || current.sampleCount == 0) {
                PerformanceReport(
                    metric = type,
                    baseline = baseline,
                    current = current,
                    trend = TrendDirection.STABLE,
                    status = PerformanceStatus.NORMAL,
                    alert = "数据不足（基线${baseline.sampleCount}条 / 当前${current.sampleCount}条），暂无法判定"
                )
            } else {
                val status = determineStatus(type, current.avg, baseline.avg)
                val alert = if (status == PerformanceStatus.DEGRADED || status == PerformanceStatus.CRITICAL) {
                    generateAlertMessage(type, status, current.avg, baseline.avg, isImprovement = false)
                } else if (status == PerformanceStatus.EXCELLENT || status == PerformanceStatus.GOOD) {
                    generateAlertMessage(type, status, current.avg, baseline.avg, isImprovement = true)
                } else {
                    ""
                }
                PerformanceReport(
                    metric = type,
                    baseline = baseline,
                    current = current,
                    trend = trend,
                    status = status,
                    alert = alert
                )
            }
        }
    }

    /**
     * 生成性能监控摘要，适用于日志输出与 UI 展示。
     *
     * 包含每个指标的：状态图标、当前均值、基线均值、趋势方向、采样数。
     *
     * @return 多行摘要字符串。
     */
    fun getSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("===== PerformanceBaseline 性能监控摘要 =====")
        sb.appendLine()

        for (type in MetricType.entries) {
            val baseline = getBaseline(type)
            val current = getCurrentPerformance(type)
            val trend = getTrend(type)
            val sampleCount = getSampleCount(type)

            sb.append("【${formatMetricName(type)}】")

            if (baseline == null || current.sampleCount == 0) {
                sb.append(" 数据不足（${sampleCount}条采样）")
            } else {
                val status = determineStatus(type, current.avg, baseline.avg)
                sb.append(" 状态=${status.name}")
                sb.append(" 当前=${formatValue(type, current.avg)}")
                sb.append(" 基线=${formatValue(type, baseline.avg)}")
                sb.append(" 趋势=${trend.name}")
                sb.append(" 采样=${sampleCount}条")
            }
            sb.appendLine()
        }

        sb.appendLine()
        sb.append("退化阈值: 耗时>1.5x=退化 >2x=严重 | 成功率<0.7x=退化 <0.5x=严重")
        sb.appendLine("=============================================")
        return sb.toString()
    }

    // =========================================================================
    //  时间窗口对比方法
    // =========================================================================

    /**
     * 对比指定指标今天与昨天的性能。
     *
     * 分别计算今天（0 点至今）和昨天（昨日 0 点至 24 点）的采样均值，
     * 生成变化百分比和趋势方向。
     *
     * @param type 指标类型。
     * @return 时间窗口对比结果，任一窗口无数据时返回 null。
     */
    fun compareTodayVsYesterday(type: MetricType): TimeWindowComparison? {
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now)
        val yesterdayStart = todayStart - MILLIS_PER_DAY

        val all = getAllSamples(type)
        val todaySamples = all.filter { it.timestamp >= todayStart }
        val yesterdaySamples = all.filter { it.timestamp >= yesterdayStart && it.timestamp < todayStart }

        if (todaySamples.isEmpty() || yesterdaySamples.isEmpty()) return null

        val todayAvg = computeMean(todaySamples.map { it.value })
        val yesterdayAvg = computeMean(yesterdaySamples.map { it.value })

        return buildTimeWindowComparison(type, todayAvg, yesterdayAvg)
    }

    /**
     * 对比指定指标本周与上周的性能。
     *
     * 以周一为每周起始，分别计算本周和上周的采样均值。
     *
     * @param type 指标类型。
     * @return 时间窗口对比结果，任一窗口无数据时返回 null。
     */
    fun compareThisWeekVsLastWeek(type: MetricType): TimeWindowComparison? {
        val now = System.currentTimeMillis()
        val thisWeekStart = startOfWeek(now)
        val lastWeekStart = thisWeekStart - MILLIS_PER_WEEK

        val all = getAllSamples(type)
        val thisWeekSamples = all.filter { it.timestamp >= thisWeekStart }
        val lastWeekSamples = all.filter { it.timestamp >= lastWeekStart && it.timestamp < thisWeekStart }

        if (thisWeekSamples.isEmpty() || lastWeekSamples.isEmpty()) return null

        val thisWeekAvg = computeMean(thisWeekSamples.map { it.value })
        val lastWeekAvg = computeMean(lastWeekSamples.map { it.value })

        return buildTimeWindowComparison(type, thisWeekAvg, lastWeekAvg)
    }

    // =========================================================================
    //  管理方法
    // =========================================================================

    /**
     * 清空所有指标的采样数据。
     *
     * 适用于测试或需要重新建立基线的场景。
     */
    fun clear() {
        samples.clear()
    }

    /**
     * 清空指定指标的采样数据。
     *
     * @param type 指标类型。
     */
    fun clearMetric(type: MetricType) {
        samples.remove(type)
    }

    // =========================================================================
    //  内部辅助方法 —— 采样获取
    // =========================================================================

    /**
     * 获取指定指标的全部采样（线程安全拷贝）。
     *
     * @param type 指标类型。
     * @return 采样列表的副本（按时间顺序）。
     */
    private fun getAllSamples(type: MetricType): List<MetricSample> {
        val list = samples[type] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /**
     * 获取指定指标最近的 N 个采样。
     *
     * @param type 指标类型。
     * @param count 要获取的采样数。
     * @return 最近的采样列表（按时间顺序）。
     */
    private fun getRecentSamples(type: MetricType, count: Int): List<MetricSample> {
        val all = getAllSamples(type)
        if (all.size <= count) return all
        return all.takeLast(count)
    }

    // =========================================================================
    //  内部辅助方法 —— 统计计算
    // =========================================================================

    /**
     * 从一批采样计算基线统计摘要。
     *
     * @param sampleList 采样列表。
     * @return 包含均值、极值、标准差和样本数的基线统计。
     */
    private fun computeBaseline(sampleList: List<MetricSample>): Baseline {
        if (sampleList.isEmpty()) {
            return Baseline(avg = 0.0, min = 0.0, max = 0.0, stdDev = 0.0, sampleCount = 0)
        }
        val values = sampleList.map { it.value }
        val avg = computeMean(values)
        val stdDev = computeStdDev(values, avg)
        return Baseline(
            avg = avg,
            min = values.min(),
            max = values.max(),
            stdDev = stdDev,
            sampleCount = values.size
        )
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
     * 以采样序号（0, 1, 2, ...）为 x 轴，指标值为 y 轴，
     * 使用最小二乘法计算斜率。斜率为正表示值随时间上升，
     * 斜率为负表示值随时间下降。
     *
     * @param sampleList 采样列表（按时间顺序）。
     * @return 回归斜率，采样数 < 2 时返回 0.0。
     */
    private fun computeTrendSlope(sampleList: List<MetricSample>): Double {
        val n = sampleList.size
        if (n < 2) return 0.0

        // x 为采样序号 (0, 1, 2, ..., n-1)，y 为指标值
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        for (i in 0 until n) {
            val x = i.toDouble()
            val y = sampleList[i].value
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
    //  内部辅助方法 —— 状态判定
    // =========================================================================

    /**
     * 判断指定指标是否「越高越好」。
     *
     * 成功率类指标值越高代表性能越好，其余指标（耗时、延迟、Token 用量）
     * 值越低代表性能越好。
     *
     * @param type 指标类型。
     * @return true 表示越高越好（如成功率），false 表示越低越好（如耗时）。
     */
    private fun isHigherBetter(type: MetricType): Boolean {
        return type == MetricType.SUCCESS_RATE
    }

    /**
     * 根据当前均值与基线均值的比值判定性能状态。
     *
     * 耗时类指标（越低越好）：
     * - ratio > 2.0 → CRITICAL
     * - ratio > 1.5 → DEGRADED
     * - ratio < 0.7 → EXCELLENT
     * - ratio < 0.9 → GOOD
     * - 其他 → NORMAL
     *
     * 成功率类指标（越高越好）：
     * - ratio < 0.5 → CRITICAL
     * - ratio < 0.7 → DEGRADED
     * - ratio > 1.3 → EXCELLENT
     * - ratio > 1.1 → GOOD
     * - 其他 → NORMAL
     *
     * @param type 指标类型。
     * @param currentAvg 当前性能均值。
     * @param baselineAvg 基线均值。
     * @return 性能状态等级。
     */
    private fun determineStatus(
        type: MetricType,
        currentAvg: Double,
        baselineAvg: Double
    ): PerformanceStatus {
        if (baselineAvg == 0.0) return PerformanceStatus.NORMAL

        val ratio = currentAvg / baselineAvg
        return if (isHigherBetter(type)) {
            when {
                ratio < SUCCESS_CRITICAL_THRESHOLD -> PerformanceStatus.CRITICAL
                ratio < SUCCESS_DEGRADED_THRESHOLD -> PerformanceStatus.DEGRADED
                ratio > SUCCESS_EXCELLENT_THRESHOLD -> PerformanceStatus.EXCELLENT
                ratio > SUCCESS_GOOD_THRESHOLD -> PerformanceStatus.GOOD
                else -> PerformanceStatus.NORMAL
            }
        } else {
            when {
                ratio > CRITICAL_THRESHOLD -> PerformanceStatus.CRITICAL
                ratio > DEGRADED_THRESHOLD -> PerformanceStatus.DEGRADED
                ratio < EXCELLENT_THRESHOLD -> PerformanceStatus.EXCELLENT
                ratio < GOOD_THRESHOLD -> PerformanceStatus.GOOD
                else -> PerformanceStatus.NORMAL
            }
        }
    }

    // =========================================================================
    //  内部辅助方法 —— 告警与格式化
    // =========================================================================

    /**
     * 生成人类可读的告警消息。
     *
     * @param type 指标类型。
     * @param status 性能状态。
     * @param currentAvg 当前均值。
     * @param baselineAvg 基线均值。
     * @param isImprovement true 表示改善告警，false 表示退化告警。
     * @return 中文告警消息。
     */
    private fun generateAlertMessage(
        type: MetricType,
        status: PerformanceStatus,
        currentAvg: Double,
        baselineAvg: Double,
        isImprovement: Boolean
    ): String {
        val metricName = formatMetricName(type)
        val currentStr = formatValue(type, currentAvg)
        val baselineStr = formatValue(type, baselineAvg)
        val ratio = if (baselineAvg != 0.0) currentAvg / baselineAvg else 0.0
        val ratioStr = "%.2f".format(ratio) + "x"

        return if (isImprovement) {
            val action = when (status) {
                PerformanceStatus.EXCELLENT -> "显著改善"
                PerformanceStatus.GOOD -> "有所改善"
                else -> "改善"
            }
            "$metricName 性能$action：当前 $currentStr，基线 $baselineStr ($ratioStr)，状态=${status.name}"
        } else {
            val action = when (status) {
                PerformanceStatus.CRITICAL -> "严重退化"
                PerformanceStatus.DEGRADED -> "性能退化"
                else -> "异常"
            }
            val suggestion = generateSuggestion(type, status)
            "$metricName $action：当前 $currentStr，基线 $baselineStr ($ratioStr)，状态=${status.name}。建议：$suggestion"
        }
    }

    /**
     * 根据指标类型和退化程度生成优化建议。
     *
     * @param type 指标类型。
     * @param status 性能状态。
     * @return 中文建议文本。
     */
    private fun generateSuggestion(type: MetricType, status: PerformanceStatus): String {
        val urgent = if (status == PerformanceStatus.CRITICAL) "（紧急）" else ""
        return when (type) {
            MetricType.TASK_DURATION ->
                "检查任务执行链路是否存在瓶颈$urgent，关注动作执行次数和重试频率"
            MetricType.AI_LATENCY ->
                "检查网络连接和 AI 服务状态$urgent，确认 API 端点可用性和模型负载"
            MetricType.ACTION_TIME ->
                "检查无障碍服务响应速度$urgent，确认设备性能和后台进程占用"
            MetricType.SUCCESS_RATE ->
                "检查失败任务的模式与错误类型$urgent，分析是否需要调整重试策略"
            MetricType.TOKEN_USAGE ->
                "检查系统提示词长度和屏幕文本截断$urgent，优化上下文构建策略"
        }
    }

    /**
     * 获取指标的中文显示名称。
     *
     * @param type 指标类型。
     * @return 中文名称。
     */
    private fun formatMetricName(type: MetricType): String {
        return when (type) {
            MetricType.TASK_DURATION -> "任务耗时"
            MetricType.AI_LATENCY -> "AI延迟"
            MetricType.ACTION_TIME -> "动作耗时"
            MetricType.SUCCESS_RATE -> "成功率"
            MetricType.TOKEN_USAGE -> "Token用量"
        }
    }

    /**
     * 根据指标类型格式化数值为人类可读字符串。
     *
     * 耗时类指标自动转换为秒（>=1000ms 时），成功率转为百分比，Token 取整。
     *
     * @param type 指标类型。
     * @param value 数值。
     * @return 格式化后的字符串。
     */
    private fun formatValue(type: MetricType, value: Double): String {
        return when (type) {
            MetricType.TASK_DURATION,
            MetricType.AI_LATENCY,
            MetricType.ACTION_TIME -> {
                if (value >= 1000.0) {
                    "%.2fs".format(value / 1000.0)
                } else {
                    "${value.roundToLong()}ms"
                }
            }
            MetricType.SUCCESS_RATE -> "${"%.1f".format(value * 100)}%"
            MetricType.TOKEN_USAGE -> "${value.roundToLong()} tokens"
        }
    }

    // =========================================================================
    //  内部辅助方法 —— 时间窗口计算
    // =========================================================================

    /**
     * 构建时间窗口对比结果。
     *
     * @param type 指标类型。
     * @param currentAvg 当前窗口均值。
     * @param previousAvg 对比窗口均值。
     * @return 时间窗口对比结果。
     */
    private fun buildTimeWindowComparison(
        type: MetricType,
        currentAvg: Double,
        previousAvg: Double
    ): TimeWindowComparison {
        val changePercent = if (previousAvg != 0.0) {
            ((currentAvg - previousAvg) / previousAvg) * 100.0
        } else {
            0.0
        }

        val direction = determineWindowDirection(type, currentAvg, previousAvg)
        return TimeWindowComparison(
            metric = type,
            currentAvg = currentAvg,
            previousAvg = previousAvg,
            changePercent = changePercent,
            direction = direction
        )
    }

    /**
     * 根据两个窗口的均值对比判定趋势方向。
     *
     * @param type 指标类型。
     * @param currentAvg 当前窗口均值。
     * @param previousAvg 对比窗口均值。
     * @return 趋势方向。
     */
    private fun determineWindowDirection(
        type: MetricType,
        currentAvg: Double,
        previousAvg: Double
    ): TrendDirection {
        if (previousAvg == 0.0) return TrendDirection.STABLE
        val ratio = currentAvg / previousAvg
        val higherBetter = isHigherBetter(type)

        return when {
            abs(ratio - 1.0) < 0.05 -> TrendDirection.STABLE
            higherBetter && currentAvg > previousAvg -> TrendDirection.IMPROVING
            higherBetter && currentAvg < previousAvg -> TrendDirection.DEGRADING
            !higherBetter && currentAvg < previousAvg -> TrendDirection.IMPROVING
            !higherBetter && currentAvg > previousAvg -> TrendDirection.DEGRADING
            else -> TrendDirection.STABLE
        }
    }

    /**
     * 获取指定时间戳所在日的 0 点时间戳。
     *
     * @param timestamp 时间戳（epoch 毫秒）。
     * @return 当天 0 点的时间戳。
     */
    private fun startOfDay(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    /**
     * 获取指定时间戳所在周的周一 0 点时间戳。
     *
     * 以周一为每周的第一天（符合中文区域习惯）。
     *
     * @param timestamp 时间戳（epoch 毫秒）。
     * @return 当周周一 0 点的时间戳。
     */
    private fun startOfWeek(timestamp: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = startOfDay(timestamp)
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return calendar.timeInMillis
    }
}
