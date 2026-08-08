package com.mobileclaw.app.ai

import java.util.concurrent.ConcurrentHashMap

/**
 * 智能超时管理器 —— 根据任务复杂度和历史数据动态调整超时阈值。
 *
 * 核心问题：当前使用固定 60 秒超时，对简单任务（截图只需 3 秒）过于宽松，
 * 对复杂任务（打开微信发消息可能需要 30 秒）又可能不够。
 *
 * 动态策略：
 * - MICRO/SIMPLE 任务：15 秒超时（快速失败，快速重试）
 * - MEDIUM 任务：30 秒超时
 * - COMPLEX 任务：90 秒超时（给充足时间完成多步操作）
 * - 基于历史数据微调：如果某类任务历史平均耗时 20 秒，超时设为 30 秒（+50%余量）
 *
 * 分阶段超时：
 * - AI 调用阶段：根据复杂度设不同超时（简单 10 秒，复杂 30 秒）
 * - 动作执行阶段：根据动作类型设不同超时（SCREEN_WAIT 有独立超时）
 * - 整体任务超时：动态计算，基于预估步数 × 单步平均耗时 × 安全系数
 */
class SmartTimeoutManager {

    /** 任务类型对应的超时配置。 */
    data class TimeoutConfig(
        val aiCallTimeoutMs: Long,
        val actionTimeoutMs: Long,
        val totalTaskTimeoutMs: Long
    )

    /** 历史任务耗时记录（按复杂度分类）。 */
    private data class DurationRecord(
        var totalDuration: Long = 0L,
        var count: Int = 0,
        var maxDuration: Long = 0L,
        var minDuration: Long = Long.MAX_VALUE
    ) {
        val avgDuration: Long get() = if (count > 0) totalDuration / count else 0L
    }

    /** 按复杂度分类的耗时记录。 */
    private val durationRecords = ConcurrentHashMap<String, DurationRecord>()

    /** 默认超时配置（按复杂度）。 */
    private val defaultTimeouts = mapOf(
        "MICRO" to TimeoutConfig(8_000L, 5_000L, 15_000L),
        "SIMPLE" to TimeoutConfig(10_000L, 8_000L, 20_000L),
        "MEDIUM" to TimeoutConfig(15_000L, 15_000L, 40_000L),
        "COMPLEX" to TimeoutConfig(30_000L, 20_000L, 90_000L),
        "UNLIMITED" to TimeoutConfig(30_000L, 20_000L, 120_000L)
    )

    /** 安全系数：超时 = 平均耗时 × 安全系数。 */
    private val safetyFactor = 1.5f

    /** 最小超时（毫秒）。 */
    private val minTimeoutMs = 5_000L

    /** 最大超时（毫秒）。 */
    private val maxTimeoutMs = 180_000L

    /**
     * 获取指定复杂度的超时配置。
     *
     * 如果有历史数据，基于平均耗时动态计算；否则使用默认值。
     *
     * @param complexity 任务复杂度
     * @return 超时配置
     */
    fun getTimeout(complexity: TaskComplexityAnalyzer.Complexity): TimeoutConfig {
        val defaults = defaultTimeouts[complexity.name]
            ?: defaultTimeouts["MEDIUM"]!!

        val record = durationRecords[complexity.name]
        if (record == null || record.count < 3) {
            // 历史数据不足，使用默认值
            return defaults
        }

        // 基于历史数据动态计算
        val avgMs = record.avgDuration
        val dynamicTotal = (avgMs * safetyFactor).toLong().coerceIn(minTimeoutMs, maxTimeoutMs)

        // AI 调用超时：总超时的 1/3
        val dynamicAICall = (dynamicTotal / 3).coerceAtLeast(5_000L)
        // 动作执行超时：总超时的 2/3
        val dynamicAction = (dynamicTotal * 2 / 3).coerceAtLeast(5_000L)

        return TimeoutConfig(
            aiCallTimeoutMs = minOf(dynamicAICall, defaults.aiCallTimeoutMs.coerceAtLeast(dynamicAICall)),
            actionTimeoutMs = minOf(dynamicAction, defaults.actionTimeoutMs.coerceAtLeast(dynamicAction)),
            totalTaskTimeoutMs = dynamicTotal
        )
    }

    /**
     * 记录任务执行耗时。
     *
     * @param complexity 任务复杂度
     * @param durationMs 实际耗时
     */
    fun recordDuration(complexity: TaskComplexityAnalyzer.Complexity, durationMs: Long) {
        if (durationMs <= 0) return

        durationRecords.compute(complexity.name) { _, existing ->
            if (existing == null) {
                DurationRecord(
                    totalDuration = durationMs,
                    count = 1,
                    maxDuration = durationMs,
                    minDuration = durationMs
                )
            } else {
                existing.apply {
                    totalDuration += durationMs
                    count++
                    maxDuration = maxOf(maxDuration, durationMs)
                    minDuration = minOf(minDuration, durationMs)
                }
            }
        }
    }

    /**
     * 获取预估任务耗时。
     *
     * @param complexity 任务复杂度
     * @return 预估耗时（毫秒），无历史数据返回默认值
     */
    fun getEstimatedDuration(complexity: TaskComplexityAnalyzer.Complexity): Long {
        val record = durationRecords[complexity.name]
        return record?.avgDuration ?: when (complexity) {
            TaskComplexityAnalyzer.Complexity.MICRO -> 3_000L
            TaskComplexityAnalyzer.Complexity.SIMPLE -> 5_000L
            TaskComplexityAnalyzer.Complexity.MEDIUM -> 15_000L
            TaskComplexityAnalyzer.Complexity.COMPLEX -> 30_000L
            TaskComplexityAnalyzer.Complexity.UNLIMITED -> 45_000L
        }
    }

    /**
     * 检查任务是否可能超时。
     *
     * @param complexity 任务复杂度
     * @param elapsedMs 已耗时
     * @return true 表示已超过预估耗时的 80%，可能即将超时
     */
    fun isLikelyTimeout(complexity: TaskComplexityAnalyzer.Complexity, elapsedMs: Long): Boolean {
        val estimated = getEstimatedDuration(complexity)
        return elapsedMs > estimated * 0.8f
    }

    /** 获取统计摘要。 */
    fun getSummary(): String {
        if (durationRecords.isEmpty()) return "超时管理: 无历史数据"

        return buildString {
            append("超时管理: ")
            durationRecords.forEach { (complexity, record) ->
                append("$complexity=${record.avgDuration / 1000}s(${record.count}次) ")
            }
        }
    }

    /** 清空所有历史数据。 */
    fun clear() {
        durationRecords.clear()
    }
}
