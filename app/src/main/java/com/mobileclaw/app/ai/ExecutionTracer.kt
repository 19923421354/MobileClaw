package com.mobileclaw.app.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 执行轨迹记录器 —— 记录完整的任务执行过程，支持回放和调试。
 *
 * 记录每个任务的完整执行链路：用户指令 -> AI响应 -> 动作执行 -> 验证结果 -> 最终结果。
 * 当用户报告"打不开""发不了"等问题时，可通过轨迹回放定位问题根因。
 *
 * 特性：
 * - 完整链路记录：从用户输入到最终结果的每一步
 * - 紧凑存储：限制轨迹数量和大小，避免内存膨胀
 * - 问题检测：自动标记失败步骤和可疑模式
 * - 导出摘要：生成可读的执行报告
 */
class ExecutionTracer {

    /** 单步执行记录。 */
    data class TraceStep(
        val timestamp: Long,
        val phase: Phase,
        val action: String,
        val result: String,
        val success: Boolean,
        val durationMs: Long,
        val metadata: Map<String, String> = emptyMap()
    )

    /** 执行阶段。 */
    enum class Phase {
        USER_INPUT,       // 用户输入
        STATE_COLLECT,    // 状态采集
        AI_CALL,          // AI 调用
        AI_RESPONSE,      // AI 响应解析
        ACTION_EXECUTE,   // 动作执行
        ACTION_VERIFY,    // 动作验证
        RECOVERY,         // 智能恢复
        PROACTIVE,        // 主动分析
        FINAL_RESULT      // 最终结果
    }

    /** 单个任务的完整轨迹。 */
    data class TaskTrace(
        val taskId: String,
        val userCommand: String,
        val startTime: Long,
        val steps: MutableList<TraceStep>,
        var endTime: Long = 0,
        var success: Boolean = false,
        var finalAnswer: String = ""
    )

    /** 所有任务轨迹（最多保留 MAX_TRACES 条）。 */
    private val _traces = mutableListOf<TaskTrace>()
    val traces: List<TaskTrace> get() = synchronized(_traces) { _traces.toList() }

    /** 当前正在记录的轨迹。 */
    @Volatile
    private var currentTrace: TaskTrace? = null

    /** 时间格式化器。 */
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 最大保留轨迹数。 */
    private val maxTraces = 20

    /** 最大单轨迹步数。 */
    private val maxStepsPerTrace = 100

    /**
     * 开始记录新任务。
     */
    fun startTask(userCommand: String): String {
        val taskId = "task_${System.currentTimeMillis()}"
        val trace = TaskTrace(
            taskId = taskId,
            userCommand = userCommand,
            startTime = System.currentTimeMillis(),
            steps = mutableListOf()
        )
        currentTrace = trace
        synchronized(_traces) {
            _traces.add(trace)
            while (_traces.size > maxTraces) {
                _traces.removeAt(0)
            }
        }
        return taskId
    }

    /**
     * 记录一个执行步骤。
     */
    fun recordStep(
        phase: Phase,
        action: String,
        result: String,
        success: Boolean,
        durationMs: Long = 0,
        metadata: Map<String, String> = emptyMap()
    ) {
        val trace = currentTrace ?: return
        val step = TraceStep(
            timestamp = System.currentTimeMillis(),
            phase = phase,
            action = action.take(200),
            result = result.take(300),
            success = success,
            durationMs = durationMs,
            metadata = metadata
        )
        synchronized(trace.steps) {
            trace.steps.add(step)
            if (trace.steps.size > maxStepsPerTrace) {
                trace.steps.removeAt(0)
            }
        }
    }

    /**
     * 结束当前任务记录。
     */
    fun endTask(success: Boolean, finalAnswer: String) {
        val trace = currentTrace ?: return
        trace.endTime = System.currentTimeMillis()
        trace.success = success
        trace.finalAnswer = finalAnswer.take(500)
        currentTrace = null
    }

    /**
     * 获取最近一次任务的执行报告。
     */
    fun getLastReport(): String {
        val trace = synchronized(_traces) { _traces.lastOrNull() } ?: return "暂无执行记录"
        return formatTraceReport(trace)
    }

    /**
     * 获取最近失败任务的报告（用于问题诊断）。
     */
    fun getLastFailedReport(): String {
        val trace = synchronized(_traces) {
            _traces.lastOrNull { !it.success && it.endTime > 0 }
        } ?: return "暂无失败记录"
        return formatTraceReport(trace)
    }

    /**
     * 格式化轨迹为可读报告。
     */
    private fun formatTraceReport(trace: TaskTrace): String {
        val totalTime = trace.endTime - trace.startTime
        val successCount = trace.steps.count { it.success }
        val failCount = trace.steps.count { !it.success }

        return buildString {
            appendLine("═══ 执行轨迹报告 ═══")
            appendLine("指令: ${trace.userCommand.take(80)}")
            appendLine("结果: ${if (trace.success) "成功" else "失败"}")
            appendLine("耗时: ${totalTime}ms | 步骤: ${trace.steps.size} | 成功: $successCount | 失败: $failCount")
            appendLine()

            trace.steps.forEachIndexed { index, step ->
                val time = timeFormatter.format(Date(step.timestamp))
                val status = if (step.success) "OK" else "FAIL"
                val duration = if (step.durationMs > 0) " (${step.durationMs}ms)" else ""
                appendLine("[$time] ${step.phase} $status$duration")
                appendLine("  动作: ${step.action}")
                if (!step.success || step.phase == Phase.AI_RESPONSE) {
                    appendLine("  结果: ${step.result}")
                }
            }

            if (trace.finalAnswer.isNotBlank()) {
                appendLine()
                appendLine("最终回答: ${trace.finalAnswer}")
            }
        }
    }

    /**
     * 检测执行中的问题模式。
     *
     * 返回检测到的问题列表（如"AI连续返回ANSWER""动作反复失败"等）。
     */
    fun detectProblems(): List<String> {
        val trace = synchronized(_traces) { _traces.lastOrNull() } ?: return emptyList()
        val problems = mutableListOf<String>()

        // 检测：AI 连续返回 ANSWER（虚假完成）
        val aiAnswers = trace.steps.filter { it.phase == Phase.AI_RESPONSE && it.action.contains("ANSWER") }
        if (aiAnswers.size >= 2) {
            problems.add("AI连续${aiAnswers.size}次返回ANSWER，可能存在虚假完成")
        }

        // 检测：同一动作反复失败
        val failedActions = trace.steps.filter { !it.success && it.phase == Phase.ACTION_EXECUTE }
        val actionGroups = failedActions.groupBy { it.action.take(30) }
        actionGroups.forEach { (action, steps) ->
            if (steps.size >= 2) {
                problems.add("动作「$action」连续失败${steps.size}次")
            }
        }

        // 检测：总耗时过长
        val totalTime = trace.endTime - trace.startTime
        if (totalTime > 30000) {
            problems.add("任务总耗时${totalTime / 1000}秒，可能存在性能瓶颈")
        }

        // 检测：AI调用次数过多
        val aiCalls = trace.steps.count { it.phase == Phase.AI_CALL }
        if (aiCalls >= 5) {
            problems.add("AI调用${aiCalls}次，可能存在循环问题")
        }

        return problems
    }

    /**
     * 清空所有轨迹。
     */
    fun clear() {
        synchronized(_traces) { _traces.clear() }
        currentTrace = null
    }

    /**
     * 获取轨迹数量。
     */
    fun size(): Int = synchronized(_traces) { _traces.size }
}
