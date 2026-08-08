package com.mobileclaw.app.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// =============================================================================
//  ApiHealthMonitor - API 健康检测与自动切换
// =============================================================================

/**
 * API 健康检测器。
 *
 * 持续监控 AI API 的响应状态，当检测到连续失败时自动标记为不健康，
 * 并支持配置备用 API 进行自动切换。
 *
 * 特性：
 * - 滑动窗口统计成功率
 * - 连续失败超阈值时标记不健康
 * - 支持配置多个备用 API（自动故障转移）
 * - 健康恢复检测（定期探测）
 */
class ApiHealthMonitor {

    /** API 配置备份（用于故障转移）。 */
    data class ApiBackup(
        val name: String,
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val priority: Int = 0
    )

    /** 健康状态。 */
    enum class HealthStatus {
        HEALTHY,       // 健康
        DEGRADED,      // 降级（偶发失败）
        UNHEALTHY,     // 不健康（连续失败）
        RECOVERING     // 恢复中
    }

    @Volatile
    var status: HealthStatus = HealthStatus.HEALTHY
        private set

    /** 备用 API 列表（按优先级排序）。 */
    private val backups = mutableListOf<ApiBackup>()

    /** 当前使用的备用 API 索引（-1 表示使用主 API）。 */
    @Volatile
    private var currentBackupIndex: Int = -1

    /** 滑动窗口：最近 N 次请求的结果。 */
    private val recentResults = ArrayDeque<Boolean>(WINDOW_SIZE)

    /** 连续失败次数。 */
    private val consecutiveFailures = AtomicInteger(0)

    /** 总请求数。 */
    private val totalRequests = AtomicLong(0)

    /** 总失败数。 */
    private val totalFailures = AtomicLong(0)

    /** 最后一次检测时间。 */
    @Volatile
    private var lastCheckTime: Long = 0

    /**
     * 添加备用 API 配置。
     */
    fun addBackup(backup: ApiBackup) {
        synchronized(backups) {
            backups.add(backup)
            backups.sortByDescending { it.priority }
        }
    }

    /**
     * 清除所有备用 API。
     */
    fun clearBackups() {
        synchronized(backups) { backups.clear() }
        currentBackupIndex = -1
    }

    /**
     * 记录一次请求结果。
     *
     * @param success 是否成功
     * @param latencyMs 响应延迟（毫秒），用于监控
     */
    fun recordResult(success: Boolean, latencyMs: Long = 0) {
        totalRequests.incrementAndGet()
        if (!success) totalFailures.incrementAndGet()

        synchronized(recentResults) {
            if (recentResults.size >= WINDOW_SIZE) {
                recentResults.removeFirst()
            }
            recentResults.addLast(success)
        }

        if (success) {
            consecutiveFailures.set(0)
            if (status == HealthStatus.UNHEALTHY || status == HealthStatus.RECOVERING) {
                status = HealthStatus.RECOVERING
                // 恢复检测：连续2次成功后恢复健康
                val recentSuccesses = synchronized(recentResults) {
                    recentResults.toList().takeLast(2).all { it }
                }
                if (recentSuccesses) {
                    status = HealthStatus.HEALTHY
                    currentBackupIndex = -1 // 恢复到主 API
                }
            } else if (status == HealthStatus.DEGRADED) {
                val successRate = calculateSuccessRate()
                if (successRate > 0.8f) status = HealthStatus.HEALTHY
            }
        } else {
            val failures = consecutiveFailures.incrementAndGet()
            when {
                failures >= CRITICAL_FAILURE_THRESHOLD -> {
                    status = HealthStatus.UNHEALTHY
                    tryFailover()
                }
                failures >= DEGRADED_FAILURE_THRESHOLD -> {
                    status = HealthStatus.DEGRADED
                }
            }
        }
    }

    /** 计算滑动窗口内的成功率。 */
    private fun calculateSuccessRate(): Float {
        return synchronized(recentResults) {
            if (recentResults.isEmpty()) 1.0f
            else recentResults.count { it }.toFloat() / recentResults.size
        }
    }

    /** 尝试故障转移到备用 API。 */
    private fun tryFailover() {
        synchronized(backups) {
            if (backups.isNotEmpty() && currentBackupIndex < backups.size - 1) {
                currentBackupIndex++
            }
        }
    }

    /**
     * 获取当前应使用的 API 配置（主 API 或故障转移到的备用 API）。
     *
     * @return 备用 API 配置，null 表示使用主 API
     */
    fun getCurrentApi(): ApiBackup? {
        return if (currentBackupIndex >= 0) {
            synchronized(backups) {
                backups.getOrNull(currentBackupIndex)
            }
        } else null
    }

    /** 获取健康统计摘要。 */
    fun getHealthSummary(): String {
        val rate = calculateSuccessRate()
        val total = totalRequests.get()
        val fails = totalFailures.get()
        return "状态:${status.name} 成功率:${"%.1f".format(rate * 100)}% " +
               "请求:$total 失败:$fails 连续失败:${consecutiveFailures.get()}"
    }

    companion object {
        private const val WINDOW_SIZE = 20
        private const val DEGRADED_FAILURE_THRESHOLD = 3
        private const val CRITICAL_FAILURE_THRESHOLD = 5
    }
}

// =============================================================================
//  TokenTracker - Token 用量统计与分析
// =============================================================================

/**
 * Token 用量跟踪器。
 *
 * 统计每次 AI 请求的 Token 消耗（输入/输出/总计），
 * 提供按时间、按任务类型、按模型维度的分析。
 *
 * 特性：
 * - 按会话/天/任务维度统计
 * - 估算 Token 消耗（基于字符数近似计算）
 * - 提供节省 Token 的效果分析
 * - 支持导出统计数据
 */
class TokenTracker {

    /** 单次请求的 Token 记录。 */
    @Serializable
    data class TokenRecord(
        val timestamp: Long = System.currentTimeMillis(),
        val taskDescription: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val model: String,
        val complexity: String,
        val promptLevel: String,
        val success: Boolean
    ) {
        val totalTokens: Int get() = inputTokens + outputTokens
    }

    /** 会话统计摘要。 */
    data class SessionSummary(
        val totalRequests: Int,
        val totalInputTokens: Int,
        val totalOutputTokens: Int,
        val totalTokens: Int,
        val averageTokensPerRequest: Int,
        val successRate: Float,
        val estimatedSavingPercent: Float,
        val byComplexity: Map<String, Int>,
        val byModel: Map<String, Int>
    )

    private val _records = mutableListOf<TokenRecord>()
    val records: List<TokenRecord> get() = synchronized(_records) { _records.toList() }

    /** 基线 Token 消耗（无智能模式时的预估消耗）。 */
    private val baselineTokensPerRequest = 2000

    /**
     * 记录一次 AI 请求的 Token 消耗。
     *
     * @param taskDescription 任务描述
     * @param inputText 输入文本（用于估算 Token）
     * @param outputText 输出文本
     * @param model 使用的模型
     * @param complexity 任务复杂度
     * @param promptLevel 提示词级别
     * @param success 是否成功
     */
    fun record(
        taskDescription: String,
        inputText: String,
        outputText: String,
        model: String,
        complexity: String,
        promptLevel: String,
        success: Boolean
    ) {
        val inputTokens = estimateTokens(inputText)
        val outputTokens = estimateTokens(outputText)
        synchronized(_records) {
            _records.add(TokenRecord(
                timestamp = System.currentTimeMillis(),
                taskDescription = taskDescription.take(50),
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                model = model,
                complexity = complexity,
                promptLevel = promptLevel,
                success = success
            ))
            // 限制记录数量
            if (_records.size > MAX_RECORDS) {
                _records.removeAt(0)
            }
        }
    }

    /**
     * 估算文本的 Token 数量。
     * 中文约1字=1.5 Token，英文约4字符=1 Token。
     */
    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val chineseChars = text.count { it.code in 0x4E00..0x9FFF }
        val otherChars = text.length - chineseChars
        return (chineseChars * 1.5 + otherChars / 4.0).toInt().coerceAtLeast(1)
    }

    /**
     * 获取当前会话的统计摘要。
     */
    fun getSessionSummary(): SessionSummary {
        val recs = records
        if (recs.isEmpty()) {
            return SessionSummary(0, 0, 0, 0, 0, 0f, 0f, emptyMap(), emptyMap())
        }

        val totalInput = recs.sumOf { it.inputTokens }
        val totalOutput = recs.sumOf { it.outputTokens }
        val total = totalInput + totalOutput
        val successCount = recs.count { it.success }
        val actualTotal = total
        val baselineTotal = recs.size * baselineTokensPerRequest
        val saving = if (baselineTotal > 0) {
            ((baselineTotal - actualTotal).toFloat() / baselineTotal * 100).coerceAtLeast(0f)
        } else 0f

        val byComplexity = recs.groupingBy { it.complexity }.fold(0) { acc, r -> acc + r.totalTokens }
        val byModel = recs.groupingBy { it.model }.fold(0) { acc, r -> acc + r.totalTokens }

        return SessionSummary(
            totalRequests = recs.size,
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalTokens = total,
            averageTokensPerRequest = if (recs.isNotEmpty()) total / recs.size else 0,
            successRate = if (recs.isNotEmpty()) successCount.toFloat() / recs.size else 0f,
            estimatedSavingPercent = saving,
            byComplexity = byComplexity,
            byModel = byModel
        )
    }

    /**
     * 获取今日统计摘要。
     */
    fun getTodaySummary(): SessionSummary {
        val todayStart = getTodayStartMillis()
        val todayRecords = synchronized(_records) {
            _records.filter { it.timestamp >= todayStart }
        }
        if (todayRecords.isEmpty()) {
            return SessionSummary(0, 0, 0, 0, 0, 0f, 0f, emptyMap(), emptyMap())
        }

        val totalInput = todayRecords.sumOf { it.inputTokens }
        val totalOutput = todayRecords.sumOf { it.outputTokens }
        val total = totalInput + totalOutput
        val successCount = todayRecords.count { it.success }
        val baselineTotal = todayRecords.size * baselineTokensPerRequest
        val saving = if (baselineTotal > 0) {
            ((baselineTotal - total).toFloat() / baselineTotal * 100).coerceAtLeast(0f)
        } else 0f

        return SessionSummary(
            totalRequests = todayRecords.size,
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalTokens = total,
            averageTokensPerRequest = if (todayRecords.isNotEmpty()) total / todayRecords.size else 0,
            successRate = if (todayRecords.isNotEmpty()) successCount.toFloat() / todayRecords.size else 0f,
            estimatedSavingPercent = saving,
            byComplexity = todayRecords.groupingBy { it.complexity }.fold(0) { acc, r -> acc + r.totalTokens },
            byModel = todayRecords.groupingBy { it.model }.fold(0) { acc, r -> acc + r.totalTokens }
        )
    }

    /** 清空记录。 */
    fun clear() {
        synchronized(_records) { _records.clear() }
    }

    private fun getTodayStartMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        private const val MAX_RECORDS = 500
    }
}

// =============================================================================
//  TaskScheduler - 定时任务/计划任务
// =============================================================================

/**
 * 定时任务调度器。
 *
 * 支持延迟执行和定时执行用户指令：
 * - "10分钟后打开微信" -> 延迟10分钟执行
 * - "每天早上8点打开支付宝" -> 每日定时执行
 *
 * 特性：
 * - 一次性延迟任务
 * - 周期性定时任务（cron 简化版）
 * - 任务取消和管理
 * - 任务执行历史记录
 */
class TaskScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    /** 定时任务定义。 */
    data class ScheduledTask(
        val id: String,
        val name: String,
        val command: String,
        val executeAt: Long, // 执行时间戳
        val isRepeating: Boolean = false,
        val repeatIntervalMs: Long = 0, // 重复间隔（0=不重复）
        val isEnabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 任务执行记录。 */
    data class TaskExecution(
        val taskId: String,
        val taskName: String,
        val executeTime: Long,
        val success: Boolean,
        val result: String
    )

    private val _tasks = ConcurrentHashMap<String, ScheduledTask>()
    val tasks: List<ScheduledTask> get() = _tasks.values.toList()

    private val _executions = mutableListOf<TaskExecution>()
    val executions: List<TaskExecution> get() = synchronized(_executions) { _executions.toList() }

    /** 任务执行回调。 */
    var onExecute: ((String) -> Unit)? = null

    /** 正在运行的调度协程。 */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /**
     * 添加延迟任务。
     *
     * @param name 任务名称
     * @param command 要执行的指令
     * @param delayMs 延迟毫秒数
     * @return 任务 ID
     */
    fun scheduleDelayed(name: String, command: String, delayMs: Long): String {
        val taskId = "task_${System.currentTimeMillis()}_${_tasks.size}"
        val task = ScheduledTask(
            id = taskId,
            name = name,
            command = command,
            executeAt = System.currentTimeMillis() + delayMs
        )
        _tasks[taskId] = task
        scheduleExecution(task)
        return taskId
    }

    /**
     * 添加周期性任务。
     *
     * @param name 任务名称
     * @param command 要执行的指令
     * @param intervalMs 重复间隔毫秒数
     * @param firstDelayMs 首次执行延迟
     * @return 任务 ID
     */
    fun scheduleRepeating(
        name: String,
        command: String,
        intervalMs: Long,
        firstDelayMs: Long = intervalMs
    ): String {
        val taskId = "task_${System.currentTimeMillis()}_${_tasks.size}"
        val task = ScheduledTask(
            id = taskId,
            name = name,
            command = command,
            executeAt = System.currentTimeMillis() + firstDelayMs,
            isRepeating = true,
            repeatIntervalMs = intervalMs
        )
        _tasks[taskId] = task
        scheduleExecution(task)
        return taskId
    }

    /**
     * 取消任务。
     */
    fun cancelTask(taskId: String) {
        _tasks.remove(taskId)
        runningJobs[taskId]?.cancel()
        runningJobs.remove(taskId)
    }

    /**
     * 暂停任务。
     */
    fun pauseTask(taskId: String) {
        _tasks[taskId]?.let { task ->
            _tasks[taskId] = task.copy(isEnabled = false)
            runningJobs[taskId]?.cancel()
            runningJobs.remove(taskId)
        }
    }

    /**
     * 恢复任务。
     */
    fun resumeTask(taskId: String) {
        _tasks[taskId]?.let { task ->
            if (!task.isEnabled) {
                val updatedTask = task.copy(isEnabled = true, executeAt = System.currentTimeMillis())
                _tasks[taskId] = updatedTask
                scheduleExecution(updatedTask)
            }
        }
    }

    /** 获取所有待执行任务。 */
    fun getPendingTasks(): List<ScheduledTask> =
        _tasks.values.filter { it.isEnabled && it.executeAt > System.currentTimeMillis() }
            .sortedBy { it.executeAt }

    /** 调度任务执行。 */
    private fun scheduleExecution(task: ScheduledTask) {
        val delay = (task.executeAt - System.currentTimeMillis()).coerceAtLeast(0)
        val job = scope.launch {
            delay(delay)
            executeTask(task)
        }
        runningJobs[task.id] = job
    }

    /** 执行任务。 */
    private suspend fun executeTask(task: ScheduledTask) {
        val result = try {
            onExecute?.invoke(task.command)
            TaskExecution(
                taskId = task.id,
                taskName = task.name,
                executeTime = System.currentTimeMillis(),
                success = true,
                result = "执行完成"
            )
        } catch (e: Exception) {
            TaskExecution(
                taskId = task.id,
                taskName = task.name,
                executeTime = System.currentTimeMillis(),
                success = false,
                result = "执行失败: ${e.message}"
            )
        }

        synchronized(_executions) {
            _executions.add(result)
            if (_executions.size > 100) _executions.removeAt(0)
        }

        // 如果是周期性任务，安排下一次执行
        if (task.isRepeating && task.isEnabled) {
            val nextTask = task.copy(executeAt = System.currentTimeMillis() + task.repeatIntervalMs)
            _tasks[task.id] = nextTask
            scheduleExecution(nextTask)
        } else {
            _tasks.remove(task.id)
            runningJobs.remove(task.id)
        }
    }

    /** 关闭调度器。 */
    fun shutdown() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        _tasks.clear()
    }
}

// =============================================================================
//  ActionDependencyAnalyzer - 动作依赖分析器
// =============================================================================

/**
 * 动作依赖分析器。
 *
 * 分析一批动作之间的依赖关系，将可以并行执行的动作分组。
 *
 * 依赖规则：
 * - APP_OPEN 之后的动作都依赖它（必须等应用打开）
 * - SCREEN_INPUT 依赖前面的 SCREEN_CLICK_TEXT（需要先点击输入框）
 * - SCREEN_WAIT 之后的所有动作可以并行（如果彼此独立）
 * - SCREEN_SWIPE 之后的动作依赖它（需要等滑动完成）
 * - APP_CLOSE 与其他动作互斥
 * - 系统信息查询（SYSTEM_GET_INFO）可以与任何非冲突动作并行
 */
object ActionDependencyAnalyzer {

    /**
     * 分析动作列表，将可并行执行的动作分组。
     *
     * @param actions 待分析的动作列表
     * @return 分组列表，每组内的动作可以并行执行
     */
    fun analyzeParallelGroups(actions: List<ClawAction>): List<List<ClawAction>> {
        if (actions.size <= 1) return listOf(actions)

        val groups = mutableListOf<MutableList<ClawAction>>()
        val blockingTypes = setOf(
            ActionType.APP_OPEN, ActionType.APP_CLOSE, ActionType.APP_SEARCH,
            ActionType.APP_INSTALL, ActionType.APP_UNINSTALL,
            ActionType.SCREEN_SWIPE, ActionType.SCREEN_SCROLL_TO_TEXT,
            ActionType.SCREEN_INPUT, ActionType.SCREEN_KEY,
            ActionType.SCREEN_FIND_AND_CLICK, ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_LONG_CLICK, ActionType.SCREEN_DOUBLE_CLICK,
            ActionType.SCREEN_CLICK,
            ActionType.SYSTEM_SET_VOLUME, ActionType.SYSTEM_SET_BRIGHTNESS,
            ActionType.MEDIA_CONTROL, ActionType.CLIPBOARD_PASTE
        )

        var currentGroup = mutableListOf<ClawAction>()

        for (action in actions) {
            if (action.type == ActionType.ANSWER) {
                // ANSWER 总是独立一组
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup)
                    currentGroup = mutableListOf()
                }
                groups.add(mutableListOf(action))
                continue
            }

            if (action.type in blockingTypes) {
                // 阻塞型动作：先提交当前组，然后单独执行此动作
                if (currentGroup.isNotEmpty()) {
                    groups.add(currentGroup)
                    currentGroup = mutableListOf()
                }
                groups.add(mutableListOf(action))
            } else {
                // 非阻塞型动作：可以与同组动作并行
                currentGroup.add(action)
            }
        }

        if (currentGroup.isNotEmpty()) {
            groups.add(currentGroup)
        }

        return groups
    }

    /**
     * 判断两个动作是否可以并行执行。
     */
    fun canParallel(a: ClawAction, b: ClawAction): Boolean {
        // 相同类型的屏幕操作不能并行（会冲突）
        if (a.type == b.type) return false
        // 任何包含 ANSWER 的不能并行
        if (a.type == ActionType.ANSWER || b.type == ActionType.ANSWER) return false
        // 应用管理与屏幕操作不能并行
        val appTypes = setOf(ActionType.APP_OPEN, ActionType.APP_CLOSE, ActionType.APP_SEARCH)
        val screenTypes = setOf(
            ActionType.SCREEN_CLICK, ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_INPUT, ActionType.SCREEN_SWIPE,
            ActionType.SCREEN_KEY, ActionType.SCREEN_FIND_AND_CLICK
        )
        if (a.type in appTypes && b.type in screenTypes) return false
        if (b.type in appTypes && a.type in screenTypes) return false
        // 系统信息查询可以与等待并行
        if ((a.type == ActionType.SYSTEM_GET_INFO && b.type == ActionType.SCREEN_WAIT) ||
            (b.type == ActionType.SYSTEM_GET_INFO && a.type == ActionType.SCREEN_WAIT)) return true
        // 截图可以与等待并行
        if ((a.type == ActionType.SCREEN_SCREENSHOT && b.type == ActionType.SCREEN_WAIT) ||
            (b.type == ActionType.SCREEN_SCREENSHOT && a.type == ActionType.SCREEN_WAIT)) return true
        // 两个都是非阻塞型
        val nonBlocking = setOf(
            ActionType.SCREEN_WAIT, ActionType.SCREEN_SCREENSHOT,
            ActionType.SYSTEM_GET_INFO, ActionType.NOTIFY_READ,
            ActionType.SCREEN_GET_TEXT, ActionType.SCREEN_TEXT_EXISTS
        )
        return a.type in nonBlocking && b.type in nonBlocking
    }
}

// =============================================================================
//  TaskExecutionMetrics - 任务执行指标收集
// =============================================================================

/**
 * 任务执行指标收集器。
 *
 * 收集每次任务执行的耗时、成功率、动作数等指标，
 * 用于性能分析和优化建议。
 */
class TaskExecutionMetrics {

    @Serializable
    data class TaskMetric(
        val timestamp: Long = System.currentTimeMillis(),
        val userCommand: String,
        val totalTimeMs: Long,
        val aiCalls: Int,
        val actionsExecuted: Int,
        val actionsSucceeded: Int,
        val actionsFailed: Int,
        val iterations: Int,
        val quickCommandUsed: Boolean,
        val smartRecoveryUsed: Boolean,
        val success: Boolean
    )

    private val _metrics = mutableListOf<TaskMetric>()
    val metrics: List<TaskMetric> get() = synchronized(_metrics) { _metrics.toList() }

    /** 记录一次任务执行的指标。 */
    fun record(metric: TaskMetric) {
        synchronized(_metrics) {
            _metrics.add(metric)
            if (_metrics.size > MAX_METRICS) _metrics.removeAt(0)
        }
    }

    /** 获取平均任务耗时。 */
    fun getAverageTimeMs(): Long {
        val m = metrics
        return if (m.isEmpty()) 0L else m.sumOf { it.totalTimeMs } / m.size
    }

    /** 获取成功率。 */
    fun getSuccessRate(): Float {
        val m = metrics
        return if (m.isEmpty()) 0f else m.count { it.success }.toFloat() / m.size
    }

    /** 获取快捷指令使用率。 */
    fun getQuickCommandUsageRate(): Float {
        val m = metrics
        return if (m.isEmpty()) 0f else m.count { it.quickCommandUsed }.toFloat() / m.size
    }

    /** 获取智能恢复使用率。 */
    fun getSmartRecoveryUsageRate(): Float {
        val m = metrics
        return if (m.isEmpty()) 0f else m.count { it.smartRecoveryUsed }.toFloat() / m.size
    }

    /** 获取性能摘要。 */
    fun getPerformanceSummary(): String {
        val m = metrics
        if (m.isEmpty()) return "暂无执行记录"

        val avgTime = getAverageTimeMs()
        val successRate = getSuccessRate()
        val qcRate = getQuickCommandUsageRate()
        val srRate = getSmartRecoveryUsageRate()
        val totalActions = m.sumOf { it.actionsExecuted }
        val totalAICalls = m.sumOf { it.aiCalls }

        return buildString {
            appendLine("任务总数: ${m.size}")
            appendLine("平均耗时: ${avgTime / 1000.0}s")
            appendLine("成功率: ${"%.1f".format(successRate * 100)}%")
            appendLine("总动作数: $totalActions")
            appendLine("AI调用数: $totalAICalls")
            appendLine("快捷指令率: ${"%.1f".format(qcRate * 100)}%")
            appendLine("智能恢复率: ${"%.1f".format(srRate * 100)}%")
        }
    }

    /** 清空指标。 */
    fun clear() {
        synchronized(_metrics) { _metrics.clear() }
    }

    companion object {
        private const val MAX_METRICS = 200
    }
}
