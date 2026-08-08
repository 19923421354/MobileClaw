package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// =============================================================================
//  AutomationChain - 自动化任务链调度器
// =============================================================================

/**
 * AutomationChain —— 自动化任务链调度器。
 *
 * 核心理念：将多个 [ClawAction] 编排为可复用、可调度、可监控的「任务链」，支持
 * 依赖关系、条件分支、循环、等待、检查点，并通过触发器实现定时/事件/条件自动执行。
 * 用户只需定义一次链，即可让手机在满足条件时自动完成一整套复杂操作。
 *
 * 七大核心能力：
 * 1. 链定义 —— 以 [ChainStep] 列表描述一条任务链，步骤之间存在依赖关系（[ChainStep.dependencies]），
 *    只有依赖全部完成后当前步骤才会执行。每个步骤拥有独立的类型（[StepType]）与错误策略（[ErrorStrategy]）。
 * 2. 触发器 —— [Trigger] 定义链何时自动执行：时间触发（每天 08:00）、间隔触发（每 30 分钟）、
 *    事件触发（指定应用打开 / 屏幕出现指定文本）、条件触发（电量、网络状态、充电、时间范围）、手动触发。
 * 3. 条件执行 —— [StepType.CONDITION] 步骤对 [ChainCondition] 求值，成立时执行 then 分支，
 *    否则执行 else 分支，实现 if-then-else 逻辑。分支步骤通过 id 引用链中已有的步骤。
 * 4. 循环支持 —— [StepType.LOOP] 步骤可按固定次数（[ChainStep.loopCount]）或终止条件
 *    （[ChainStep.untilCondition]）重复执行循环体，带最大迭代保护防止死循环。
 * 5. 链持久化 —— 链与触发器可导出为 JSON 字符串或写入目录文件，重启后重新导入即可恢复，实现 recurring 自动化。
 * 6. 链监控 —— 实时跟踪每条执行的 [ChainStatus]（IDLE/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED），
 *    支持暂停、恢复、取消；可查询活跃链与历史执行记录。
 * 7. 错误处理 —— 每步可定义 [ErrorStrategy]：RETRY（指数退避重试）、SKIP（跳过继续）、
 *    ABORT（中止整链）、ALTERNATIVE（执行替代动作）；步骤重试耗尽后回退到链级全局策略。
 *
 * 线程安全：
 * - 链定义、触发器、执行实例均存于 [ConcurrentHashMap]，可被多线程并发调用。
 * - 单条执行的复合状态读写通过 `synchronized(execution)` 保证原子性。
 * - 全局计数使用 @Volatile + private set，保证可见性同时禁止外部修改。
 * - 执行引擎运行于独立的协程中（[scope]），暂停通过轮询状态实现，取消通过协程 [Job.cancel] 实现。
 * - 典型场景：UI 线程查询状态、后台触发器线程检查触发、协程执行引擎推进步骤。
 *
 * 使用方式：
 * ```
 * val chain = AutomationChain()
 * // 注入动作执行器与条件上下文提供者（通常由 ClawController 实现）
 * chain.actionExecutor = ActionExecutor { action -> controller.executeAction(action) }
 * chain.contextProvider = ConditionContextProvider { controller.collectConditionContext() }
 *
 * // 定义一条链：打开微信 -> 等待 -> 点击「发现」
 * val chainId = chain.createChain("微信日常", "打开微信发现页", listOf(
 *     ChainStep(id = "s1", type = StepType.ACTION, name = "打开微信",
 *         action = ClawAction(ActionType.APP_OPEN.name,
 *             JsonObject(mapOf("packageName" to JsonPrimitive("com.tencent.mm"))), "打开微信"),
 *         errorStrategy = ErrorStrategy.RETRY, maxRetries = 3),
 *     ChainStep(id = "s2", type = StepType.WAIT, name = "等待加载", waitMs = 2000L, dependencies = listOf("s1")),
 *     ChainStep(id = "s3", type = StepType.ACTION, name = "点击发现",
 *         action = ClawAction(ActionType.SCREEN_CLICK_TEXT.name,
 *             JsonObject(mapOf("text" to JsonPrimitive("发现"))), "点击发现"),
 *         dependencies = listOf("s2"))
 * ))
 *
 * // 添加每日 08:00 触发器
 * chain.addTrigger(Trigger(id = "t1", type = TriggerType.TIME, name = "每日早晨",
 *     chainId = chainId, timeExpression = "08:00"))
 * chain.startTriggerMonitor()
 *
 * // 手动启动一次
 * val execId = chain.startChain(chainId)!!
 * // 暂停 / 恢复 / 取消
 * chain.pauseChain(execId)
 * chain.resumeChain(execId)
 * chain.cancelChain(execId)
 * // 查询状态
 * println(chain.getChainStatus(execId))
 * // 持久化
 * chain.saveToDirectory(File("/data/.../chains"))
 * ```
 *
 * @param scope 外部传入的协程作用域（可选），默认自带一个 SupervisorJob 作用域
 */
class AutomationChain(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private companion object {
        const val TAG = "AutomationChain"

        // —— 容量上限 ——
        /** 最大链定义数量。 */
        const val MAX_CHAINS = 200
        /** 最大触发器数量。 */
        const val MAX_TRIGGERS = 300
        /** 最大同时存在的执行记录数量（含历史）。 */
        const val MAX_ACTIVE_EXECUTIONS = 500
        /** 已结束执行记录的保留条数（超出则清理最早的）。 */
        const val EXECUTION_HISTORY_RETENTION = 100

        // —— 执行引擎参数 ——
        /** 循环步骤的最大迭代次数，防止死循环。 */
        const val MAX_LOOP_ITERATIONS = 1000
        /** 暂停状态轮询间隔（毫秒）。 */
        const val PAUSE_POLL_INTERVAL_MS = 200L
        /** 单步动作默认超时（毫秒）。 */
        const val DEFAULT_STEP_TIMEOUT_MS = 30_000L
        /** 默认最大重试次数。 */
        const val DEFAULT_MAX_RETRIES = 3
        /** 默认重试退避基础时长（毫秒）。 */
        const val DEFAULT_RETRY_DELAY_MS = 1000L
        /** 默认单链最大并发执行数。 */
        const val DEFAULT_MAX_CONCURRENT_EXECUTIONS = 1

        // —— 触发器参数 ——
        /** 触发器后台检查间隔（毫秒）。 */
        const val TRIGGER_CHECK_INTERVAL_MS = 10_000L
        /** 事件/条件触发器的冷却时间（毫秒），避免短时间内重复触发。 */
        const val TRIGGER_COOLDOWN_MS = 60_000L

        /** JSON 编解码实例（用于链/触发器持久化）。 */
        val json: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 触发器类型。
     * - TIME：时间触发，在每天指定时间点（如 "08:00"）触发。
     * - INTERVAL：间隔触发，按固定间隔循环触发（如每 30 分钟）。
     * - EVENT：事件触发，指定应用打开或屏幕出现指定文本时触发。
     * - CONDITION：条件触发，电量/网络/充电/时间范围等条件满足时触发。
     * - MANUAL：手动触发，仅通过 [startChain] 主动启动。
     */
    enum class TriggerType {
        TIME,
        INTERVAL,
        EVENT,
        CONDITION,
        MANUAL
    }

    /**
     * 链执行状态。
     * - IDLE：已创建未启动。
     * - RUNNING：运行中。
     * - PAUSED：已暂停（可恢复）。
     * - COMPLETED：已成功完成。
     * - FAILED：已失败（某步中止或异常）。
     * - CANCELLED：已取消。
     */
    enum class ChainStatus {
        IDLE,
        RUNNING,
        PAUSED,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * 错误处理策略（步骤级）。
     * - RETRY：按指数退避重试，最多 [ChainStep.maxRetries] 次。
     * - SKIP：跳过该步，链继续执行。
     * - ABORT：立即中止整条链。
     * - ALTERNATIVE：执行 [ChainStep.alternativeAction] 替代动作。
     */
    enum class ErrorStrategy {
        RETRY,
        SKIP,
        ABORT,
        ALTERNATIVE
    }

    /**
     * 步骤类型。
     * - ACTION：执行一个 [ClawAction]。
     * - CONDITION：条件判断，执行 then/else 分支。
     * - LOOP：循环执行循环体步骤。
     * - WAIT：纯等待（[ChainStep.waitMs]）。
     * - CHECKPOINT：检查点，仅记录进度，不执行动作。
     */
    enum class StepType {
        ACTION,
        CONDITION,
        LOOP,
        WAIT,
        CHECKPOINT
    }

    /**
     * 条件类型 —— 描述 [ChainCondition] 检测的内容，用于条件执行与条件触发。
     * - APP_OPENED：指定应用处于前台（[ChainCondition.target] 为包名）。
     * - SCREEN_TEXT：屏幕包含指定文本（[ChainCondition.target] 为文本）。
     * - BATTERY_LEVEL：电量与 [ChainCondition.value] 比较（[ChainCondition.operator]）。
     * - NETWORK_STATE：网络类型（wifi/mobile/none）匹配。
     * - CHARGING：是否充电（[ChainCondition.value] 为 "true"/"false"）。
     * - TIME_RANGE：当前时间在范围内（[ChainCondition.value] 格式 "HH:mm-HH:mm"）。
     * - CUSTOM：自定义（[ChainCondition.value] 为 "true" 即成立，便于外部预算）。
     */
    enum class ConditionType {
        APP_OPENED,
        SCREEN_TEXT,
        BATTERY_LEVEL,
        NETWORK_STATE,
        CHARGING,
        TIME_RANGE,
        CUSTOM
    }

    // ============================================================
    // 外部依赖接口
    // ============================================================

    /**
     * 动作执行器接口 —— 由外部（如 [ClawController]）实现，负责真正执行 [ClawAction]。
     * 链中的 ACTION 步骤通过它执行动作并获取结果。
     */
    interface ActionExecutor {
        /** 执行指定动作并返回结果。 */
        suspend fun execute(action: ClawAction): ClawActionResult
    }

    /**
     * 条件上下文提供者接口 —— 由外部实现，提供运行时手机状态，
     * 供 [ChainCondition] 求值与事件/条件触发器判断。
     */
    interface ConditionContextProvider {
        /** 返回当前条件上下文快照。 */
        suspend fun current(): ConditionContext
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 条件求值上下文 —— 封装运行时手机状态，供 [ChainCondition.evaluate] 判断。
     *
     * @property foregroundPackage 当前前台应用包名
     * @property screenText 当前屏幕可见文本
     * @property batteryPercent 电量百分比（0-100）
     * @property isCharging 是否充电中
     * @property networkType 网络类型（"wifi" / "mobile" / "none"）
     * @property currentTimestamp 当前时间戳（毫秒）
     */
    data class ConditionContext(
        val foregroundPackage: String? = null,
        val screenText: String = "",
        val batteryPercent: Int = 100,
        val isCharging: Boolean = false,
        val networkType: String = "none",
        val currentTimestamp: Long = System.currentTimeMillis()
    )

    /**
     * 链条件 —— 用于条件执行（if-then-else）与条件触发。
     *
     * @property type 条件类型
     * @property target 目标：包名 / 文本 / 网络类型等
     * @property operator 操作符：==, !=, >, <, >=, <=, contains
     * @property value 比较值（电量数值 / 充电布尔 / 时间范围等）
     * @property negate 是否对求值结果取反
     */
    data class ChainCondition(
        val type: ConditionType,
        val target: String = "",
        val operator: String = "==",
        val value: String = "",
        val negate: Boolean = false
    ) {
        /**
         * 基于运行时上下文求值条件是否成立。
         * @param context 当前手机状态上下文
         * @return 条件是否满足（已应用 [negate] 取反）
         */
        fun evaluate(context: ConditionContext): Boolean {
            val raw: Boolean = when (type) {
                ConditionType.APP_OPENED -> context.foregroundPackage == target
                ConditionType.SCREEN_TEXT -> target.isNotEmpty() && context.screenText.contains(target)
                ConditionType.BATTERY_LEVEL -> {
                    val expected = value.toDoubleOrNull()
                    if (expected == null) {
                        false
                    } else {
                        val actual = context.batteryPercent.toDouble()
                        when (operator) {
                            ">", "gt" -> actual > expected
                            "<", "lt" -> actual < expected
                            ">=", "ge" -> actual >= expected
                            "<=", "le" -> actual <= expected
                            "!=", "ne" -> actual != expected
                            else -> actual == expected
                        }
                    }
                }
                ConditionType.NETWORK_STATE -> when (operator) {
                    "!=", "ne" -> context.networkType != target
                    "contains" -> context.networkType.contains(target)
                    else -> context.networkType == target
                }
                ConditionType.CHARGING -> {
                    val expected = value.toBooleanStrictOrNull()
                        ?: target.toBooleanStrictOrNull()
                        ?: true
                    context.isCharging == expected
                }
                ConditionType.TIME_RANGE -> isInTimeRange(context.currentTimestamp, value)
                ConditionType.CUSTOM -> value.equals("true", ignoreCase = true)
            }
            return if (negate) !raw else raw
        }

        companion object {
            /** 判断当前时间戳是否落在 "HH:mm-HH:mm" 范围内（支持跨午夜）。 */
            private fun isInTimeRange(timestamp: Long, range: String): Boolean {
                val parts = range.split("-")
                if (parts.size != 2) return false
                val start = toMinutes(parts[0])
                val end = toMinutes(parts[1])
                if (start < 0 || end < 0) return false
                val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
                val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                return if (start <= end) now in start..end else now >= start || now <= end
            }

            /** 将 "HH:mm" 解析为当日分钟数，非法返回 -1。 */
            private fun toMinutes(expr: String): Int {
                val p = expr.trim().split(":")
                val h = p.getOrNull(0)?.toIntOrNull() ?: return -1
                val m = p.getOrNull(1)?.toIntOrNull() ?: 0
                if (h !in 0..23 || m !in 0..59) return -1
                return h * 60 + m
            }
        }
    }

    /**
     * 链步骤 —— 任务链中的一个执行单元。
     *
     * 说明：所有步骤（含条件分支、循环体内的步骤）均平铺存储于 [ChainDefinition.steps]，
     * 通过 id 互相引用。CONDITION 通过 [thenStepIds]/[elseStepIds] 引用分支步骤，
     * LOOP 通过 [loopBodyStepIds] 引用循环体步骤。
     *
     * @property id 步骤唯一标识
     * @property type 步骤类型
     * @property name 步骤名称
     * @property description 步骤描述
     * @property action 关联的动作（ACTION/WAIT 类型使用）
     * @property condition 关联的条件（CONDITION 类型，或 LOOP 的终止条件使用 untilCondition）
     * @property thenStepIds 条件成立时执行的步骤 id 列表
     * @property elseStepIds 条件不成立时执行的步骤 id 列表
     * @property loopBodyStepIds 循环体步骤 id 列表（LOOP 类型）
     * @property loopCount 循环次数（>0 表示按次数循环；0 表示依赖 untilCondition）
     * @property untilCondition 循环终止条件（满足即退出循环；与 loopCount 二选一）
     * @property waitMs 等待时长（毫秒，WAIT 类型）
     * @property dependencies 前置依赖步骤 id（全部完成后才执行本步）
     * @property errorStrategy 错误处理策略
     * @property maxRetries RETRY 策略的最大重试次数
     * @property retryDelayMs 重试退避基础时长（毫秒，实际按 2^n 指数退避）
     * @property alternativeAction ALTERNATIVE 策略的替代动作
     * @property timeoutMs 单步动作执行超时（毫秒）
     * @property enabled 是否启用（禁用的步骤会被跳过）
     */
    data class ChainStep(
        val id: String,
        val type: StepType,
        val name: String,
        val description: String = "",
        val action: ClawAction? = null,
        val condition: ChainCondition? = null,
        val thenStepIds: List<String> = emptyList(),
        val elseStepIds: List<String> = emptyList(),
        val loopBodyStepIds: List<String> = emptyList(),
        val loopCount: Int = 0,
        val untilCondition: ChainCondition? = null,
        val waitMs: Long = 0L,
        val dependencies: List<String> = emptyList(),
        val errorStrategy: ErrorStrategy = ErrorStrategy.SKIP,
        val maxRetries: Int = DEFAULT_MAX_RETRIES,
        val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
        val alternativeAction: ClawAction? = null,
        val timeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
        val enabled: Boolean = true
    )

    /**
     * 触发器 —— 定义链何时被自动触发执行。
     *
     * @property id 触发器唯一标识
     * @property type 触发器类型
     * @property name 触发器名称
     * @property chainId 关联的链 id
     * @property timeExpression TIME 类型的触发时间（"HH:mm" 或 "HH:mm:ss"）
     * @property intervalMs INTERVAL 类型的间隔毫秒数
     * @property eventTarget EVENT 类型的目标（含 "." 视为包名，否则视为屏幕文本）
     * @property condition CONDITION 类型的触发条件
     * @property enabled 是否启用
     * @property createdAt 创建时间戳
     * @property lastTriggeredAt 最近一次触发时间戳
     * @property nextTriggerAt 下次预计触发时间戳（TIME/INTERVAL 使用）
     */
    data class Trigger(
        val id: String,
        val type: TriggerType,
        val name: String,
        val chainId: String,
        val timeExpression: String = "",
        val intervalMs: Long = 0L,
        val eventTarget: String = "",
        val condition: ChainCondition? = null,
        val enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis(),
        var lastTriggeredAt: Long = 0L,
        var nextTriggerAt: Long = 0L
    )

    /**
     * 链定义 —— 描述一条自动化任务链的完整结构。
     *
     * @property id 链唯一标识
     * @property name 链名称
     * @property description 链描述
     * @property steps 步骤列表（平铺存储，含分支/循环体步骤）
     * @property triggerIds 关联的触发器 id 列表
     * @property maxConcurrentExecutions 单链最大并发执行数
     * @property globalErrorStrategy 链级全局错误策略（步骤重试耗尽后回退使用）
     * @property repeatable 完成后是否可再次触发
     * @property priority 优先级（预留，数值越大越优先）
     * @property createdAt 创建时间戳
     * @property lastModified 最后修改时间戳
     * @property enabled 是否启用
     */
    data class ChainDefinition(
        val id: String,
        val name: String,
        val description: String = "",
        val steps: List<ChainStep>,
        val triggerIds: MutableList<String> = mutableListOf(),
        val maxConcurrentExecutions: Int = DEFAULT_MAX_CONCURRENT_EXECUTIONS,
        val globalErrorStrategy: ErrorStrategy = ErrorStrategy.ABORT,
        val repeatable: Boolean = true,
        val priority: Int = 0,
        val createdAt: Long = System.currentTimeMillis(),
        var lastModified: Long = System.currentTimeMillis(),
        var enabled: Boolean = true
    )

    /**
     * 链执行实例 —— 记录一次链执行的运行时状态。
     *
     * 说明：内部可变集合（completedStepIds/stepResults/loopIterations）仅由执行引擎协程
     * 单线程修改；其他线程读取时应注意其非原子性，状态查询请使用 [AutomationChain.getChainStatus]。
     *
     * @property executionId 执行实例唯一标识
     * @property chainId 关联的链 id
     * @property chainName 链名称（快照）
     * @property status 当前执行状态
     * @property currentStepId 当前正在执行的步骤 id
     * @property currentStepIndex 当前步骤在所属步骤列表中的下标
     * @property completedStepIds 已完成的步骤 id 集合
     * @property stepResults 步骤 id -> 执行结果
     * @property triggeredBy 触发来源（"manual" 或 "trigger:名称"）
     * @property startTime 开始时间戳
     * @property endTime 结束时间戳
     * @property errorMessage 失败/取消原因
     * @property loopIterations 步骤 id -> 循环已执行次数
     */
    data class ChainExecution(
        val executionId: String,
        val chainId: String,
        val chainName: String,
        var status: ChainStatus = ChainStatus.IDLE,
        var currentStepId: String? = null,
        var currentStepIndex: Int = -1,
        val completedStepIds: MutableSet<String> = mutableSetOf(),
        val stepResults: MutableMap<String, ClawActionResult> = mutableMapOf(),
        val triggeredBy: String = "",
        val startTime: Long = System.currentTimeMillis(),
        var endTime: Long = 0L,
        var errorMessage: String? = null,
        val loopIterations: MutableMap<String, Int> = mutableMapOf()
    )

    /** 执行引擎处理一个步骤列表后的结果。 */
    private enum class StepListResult {
        /** 子列表正常完成，调用方可继续。 */
        CONTINUE,
        /** 某步失败且策略为中止，链应失败。 */
        ABORTED,
        /** 协程被取消。 */
        CANCELLED
    }

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 链定义存储（id -> ChainDefinition）。 */
    private val chains = ConcurrentHashMap<String, ChainDefinition>()

    /** 触发器存储（id -> Trigger）。 */
    private val triggers = ConcurrentHashMap<String, Trigger>()

    /** 执行实例存储（executionId -> ChainExecution）。 */
    private val executions = ConcurrentHashMap<String, ChainExecution>()

    /** 执行实例协程 Job（executionId -> Job），用于取消。 */
    private val executionJobs = ConcurrentHashMap<String, Job>()

    /** 触发器后台监控协程 Job。 */
    @Volatile
    private var monitorJob: Job? = null

    // ============================================================
    // 全局统计计数
    // ============================================================

    /** 累计启动的执行次数。 */
    @Volatile
    var totalExecutionsStarted: Int = 0
        private set

    /** 累计完成的执行次数。 */
    @Volatile
    var totalExecutionsCompleted: Int = 0
        private set

    /** 累计失败的执行次数。 */
    @Volatile
    var totalExecutionsFailed: Int = 0
        private set

    /** 累计取消的执行次数。 */
    @Volatile
    var totalExecutionsCancelled: Int = 0
        private set

    /** 累计触发的次数（触发器激活导致的启动）。 */
    @Volatile
    var totalTriggersFired: Int = 0
        private set

    // ============================================================
    // 外部依赖（可注入）
    // ============================================================

    /** 动作执行器，由外部注入；未注入时 ACTION 步骤将失败。 */
    @Volatile
    var actionExecutor: ActionExecutor? = null

    /** 条件上下文提供者，由外部注入；未注入时条件/事件/条件触发器无法求值。 */
    @Volatile
    var contextProvider: ConditionContextProvider? = null

    /** 日志时间格式化器。 */
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** id 生成计数器。 */
    private val chainIdCounter = AtomicLong(0)
    private val triggerIdCounter = AtomicLong(0)
    private val executionIdCounter = AtomicLong(0)

    // ============================================================
    // 初始化
    // ============================================================

    init {
        registerDefaultTemplates()
    }

    /** 注册预置示例链，演示各类步骤类型的用法。 */
    private fun registerDefaultTemplates() {
        // —— 示例1：微信日常打开（ACTION + WAIT + CHECKPOINT）——
        createChain(
            ChainDefinition(
                id = "demo_wechat_routine",
                name = "微信日常打开",
                description = "打开微信并点击「发现」页",
                steps = listOf(
                    ChainStep(
                        id = "w1", type = StepType.ACTION, name = "打开微信",
                        action = ClawAction(
                            actionName = ActionType.APP_OPEN.name,
                            params = JsonObject(mapOf("packageName" to JsonPrimitive("com.tencent.mm"))),
                            description = "打开微信"
                        ),
                        errorStrategy = ErrorStrategy.RETRY, maxRetries = 3
                    ),
                    ChainStep(
                        id = "w2", type = StepType.WAIT, name = "等待加载",
                        waitMs = 2000L, dependencies = listOf("w1")
                    ),
                    ChainStep(
                        id = "w3", type = StepType.ACTION, name = "点击发现",
                        action = ClawAction(
                            actionName = ActionType.SCREEN_CLICK_TEXT.name,
                            params = JsonObject(mapOf("text" to JsonPrimitive("发现"))),
                            description = "点击发现"
                        ),
                        dependencies = listOf("w2")
                    ),
                    ChainStep(
                        id = "w4", type = StepType.CHECKPOINT, name = "完成检查点",
                        dependencies = listOf("w3")
                    )
                )
            )
        )

        // —— 示例2：循环刷新（LOOP + 循环体）——
        createChain(
            ChainDefinition(
                id = "demo_refresh_loop",
                name = "循环刷新",
                description = "向下滑动刷新 5 次",
                steps = listOf(
                    ChainStep(
                        id = "r1", type = StepType.LOOP, name = "刷新循环",
                        loopCount = 5, loopBodyStepIds = listOf("r1a", "r1b")
                    ),
                    ChainStep(
                        id = "r1a", type = StepType.ACTION, name = "下滑刷新",
                        action = ClawAction(
                            actionName = ActionType.SCREEN_SWIPE.name,
                            params = JsonObject(mapOf("direction" to JsonPrimitive("UP"))),
                            description = "向上滑动以刷新"
                        )
                    ),
                    ChainStep(
                        id = "r1b", type = StepType.WAIT, name = "刷新等待",
                        waitMs = 800L
                    )
                )
            )
        )

        // —— 示例3：条件截图（CONDITION 分支）——
        createChain(
            ChainDefinition(
                id = "demo_conditional_screenshot",
                name = "条件截图",
                description = "电量大于 20% 时截图，否则等待充电",
                steps = listOf(
                    ChainStep(
                        id = "c1", type = StepType.CONDITION, name = "电量判断",
                        condition = ChainCondition(
                            type = ConditionType.BATTERY_LEVEL,
                            operator = ">",
                            value = "20"
                        ),
                        thenStepIds = listOf("c1a"),
                        elseStepIds = listOf("c1b")
                    ),
                    ChainStep(
                        id = "c1a", type = StepType.ACTION, name = "截图",
                        action = ClawAction(
                            actionName = ActionType.SCREEN_SCREENSHOT.name,
                            params = JsonObject(emptyMap()),
                            description = "截取当前屏幕"
                        )
                    ),
                    ChainStep(
                        id = "c1b", type = StepType.WAIT, name = "等待充电",
                        waitMs = 5000L
                    )
                )
            )
        )
    }

    // ============================================================
    // id 生成
    // ============================================================

    /** 生成下一个链 id。 */
    private fun nextChainId(): String = "chain-${chainIdCounter.incrementAndGet()}"

    /** 生成下一个触发器 id。 */
    private fun nextTriggerId(): String = "trigger-${triggerIdCounter.incrementAndGet()}"

    /** 生成下一个执行实例 id。 */
    private fun nextExecutionId(): String = "exec-${executionIdCounter.incrementAndGet()}"

    // ============================================================
    // 链管理（创建 / 查询 / 删除）
    // ============================================================

    /**
     * 创建（注册）一条任务链。
     *
     * @param definition 链定义（id 为空时自动生成）
     * @return 链 id
     */
    fun createChain(definition: ChainDefinition): String {
        val id = if (definition.id.isBlank()) nextChainId() else definition.id
        val toStore = if (definition.id == id) definition else definition.copy(id = id)
        registerChainInternal(toStore)
        Log.i(TAG, "创建链[${toStore.name}]: $id (${toStore.steps.size}步)")
        return id
    }

    /**
     * 创建链的便捷重载（自动生成 id）。
     *
     * @param name 链名称
     * @param description 链描述
     * @param steps 步骤列表
     * @return 链 id
     */
    fun createChain(name: String, description: String = "", steps: List<ChainStep>): String {
        return createChain(ChainDefinition(id = nextChainId(), name = name, description = description, steps = steps))
    }

    /** 内部注册链，处理容量上限与淘汰。 */
    private fun registerChainInternal(chain: ChainDefinition): Boolean {
        if (chain.id.isBlank()) return false
        val isNew = !chains.containsKey(chain.id)
        if (isNew && chains.size >= MAX_CHAINS) {
            // 淘汰策略：优先禁用项，其次最久未修改
            val victim = chains.values
                .sortedWith(compareBy<ChainDefinition> { it.enabled }.thenBy { it.lastModified })
                .firstOrNull()
            if (victim != null) {
                chains.remove(victim.id)
                Log.d(TAG, "链数达上限($MAX_CHAINS)，淘汰: ${victim.name}")
            } else {
                Log.w(TAG, "链注册失败：已达上限且无可淘汰项")
                return false
            }
        }
        chains[chain.id] = chain
        return true
    }

    /** 获取指定链定义。 */
    fun getChain(chainId: String): ChainDefinition? = chains[chainId]

    /** 获取所有链定义（按最后修改时间降序）。 */
    fun getAllChains(): List<ChainDefinition> =
        chains.values.sortedByDescending { it.lastModified }

    /** 获取指定执行实例。 */
    fun getExecution(executionId: String): ChainExecution? = executions[executionId]

    /** 获取全部执行实例（按开始时间降序）。 */
    fun getAllExecutions(): List<ChainExecution> =
        executions.values.sortedByDescending { it.startTime }

    /**
     * 删除一条链及其关联触发器。
     * @return true 表示删除成功
     */
    fun removeChain(chainId: String): Boolean {
        val removed = chains.remove(chainId) != null
        if (removed) {
            // 同步移除关联触发器
            val related = triggers.values.filter { it.chainId == chainId }.toList()
            for (t in related) {
                triggers.remove(t.id)
            }
            Log.i(TAG, "删除链 $chainId 及 ${related.size} 个关联触发器")
        }
        return removed
    }

    // ============================================================
    // 执行控制（启动 / 暂停 / 恢复 / 取消 / 状态查询）
    // ============================================================

    /**
     * 启动一条链的执行。
     *
     * @param chainId 链 id
     * @param triggeredBy 触发来源描述
     * @return 执行实例 id；启动失败返回 null（链不存在/已禁用/达并发上限）
     */
    fun startChain(chainId: String, triggeredBy: String = "manual"): String? {
        val chain = chains[chainId]
        if (chain == null) {
            Log.w(TAG, "启动链失败：未知链 $chainId")
            return null
        }
        if (!chain.enabled) {
            Log.w(TAG, "启动链失败：链已禁用 ${chain.name}")
            return null
        }
        // 并发上限检查
        val activeCount = executions.values.count {
            it.chainId == chainId && isRunningStatus(statusOf(it))
        }
        if (activeCount >= chain.maxConcurrentExecutions) {
            Log.w(TAG, "启动链失败：已达最大并发数 ${chain.maxConcurrentExecutions} (${chain.name})")
            return null
        }
        // 执行记录容量检查与清理
        if (executions.size >= MAX_ACTIVE_EXECUTIONS) {
            cleanupFinishedExecutions()
            if (executions.size >= MAX_ACTIVE_EXECUTIONS) {
                Log.w(TAG, "启动链失败：执行记录数达上限 $MAX_ACTIVE_EXECUTIONS")
                return null
            }
        }
        val execution = ChainExecution(
            executionId = nextExecutionId(),
            chainId = chainId,
            chainName = chain.name,
            triggeredBy = triggeredBy
        )
        executions[execution.executionId] = execution
        updateStatus(execution, ChainStatus.RUNNING)
        totalExecutionsStarted++
        val job = scope.launch { executeChain(execution) }
        executionJobs[execution.executionId] = job
        Log.i(TAG, "启动链执行[${chain.name}]: ${execution.executionId} (触发: $triggeredBy)")
        return execution.executionId
    }

    /**
     * 暂停指定执行。
     * @return true 表示暂停成功（原状态为 RUNNING）
     */
    fun pauseChain(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        if (statusOf(execution) != ChainStatus.RUNNING) return false
        updateStatus(execution, ChainStatus.PAUSED)
        Log.i(TAG, "已暂停链执行: $executionId")
        return true
    }

    /**
     * 恢复指定执行。
     * @return true 表示恢复成功（原状态为 PAUSED）
     */
    fun resumeChain(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        if (statusOf(execution) != ChainStatus.PAUSED) return false
        updateStatus(execution, ChainStatus.RUNNING)
        Log.i(TAG, "已恢复链执行: $executionId")
        return true
    }

    /**
     * 取消指定执行。
     * @return true 表示取消成功（原状态为运行态）
     */
    fun cancelChain(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        val status = statusOf(execution)
        if (isTerminalStatus(status)) return false
        updateStatus(execution, ChainStatus.CANCELLED)
        executionJobs[executionId]?.cancel()
        Log.i(TAG, "已取消链执行: $executionId")
        return true
    }

    /**
     * 查询指定执行的状态。
     * @return 状态；执行不存在返回 null
     */
    fun getChainStatus(executionId: String): ChainStatus? =
        executions[executionId]?.let { statusOf(it) }

    /**
     * 获取所有活跃执行（RUNNING 或 PAUSED）。
     */
    fun getActiveChains(): List<ChainExecution> =
        executions.values.filter { isRunningStatus(statusOf(it)) }.toList()

    // ============================================================
    // 执行引擎
    // ============================================================

    /**
     * 执行一条链的完整流程（在协程中运行）。
     * 负责推进步骤列表、设置最终状态、更新统计计数。
     */
    private suspend fun executeChain(execution: ChainExecution) {
        val chain = chains[execution.chainId]
        if (chain == null) {
            updateStatus(execution, ChainStatus.FAILED)
            execution.errorMessage = "链定义不存在: ${execution.chainId}"
            execution.endTime = System.currentTimeMillis()
            executionJobs.remove(execution.executionId)
            return
        }
        try {
            updateStatus(execution, ChainStatus.RUNNING)
            val result = executeStepList(execution, chain.steps)
            execution.endTime = System.currentTimeMillis()
            when (result) {
                StepListResult.CONTINUE -> {
                    updateStatus(execution, ChainStatus.COMPLETED)
                    totalExecutionsCompleted++
                    Log.i(TAG, "链执行完成[${chain.name}]: ${execution.executionId}")
                }
                StepListResult.ABORTED -> {
                    updateStatus(execution, ChainStatus.FAILED)
                    if (execution.errorMessage.isNullOrBlank()) {
                        execution.errorMessage = "链因步骤失败而中止"
                    }
                    totalExecutionsFailed++
                    Log.w(TAG, "链执行失败[${chain.name}]: ${execution.executionId} - ${execution.errorMessage}")
                }
                StepListResult.CANCELLED -> {
                    updateStatus(execution, ChainStatus.CANCELLED)
                    if (execution.errorMessage.isNullOrBlank()) {
                        execution.errorMessage = "链被取消"
                    }
                    totalExecutionsCancelled++
                }
            }
        } catch (e: CancellationException) {
            execution.endTime = System.currentTimeMillis()
            updateStatus(execution, ChainStatus.CANCELLED)
            execution.errorMessage = "链被取消"
            totalExecutionsCancelled++
            throw e
        } catch (e: Throwable) {
            execution.endTime = System.currentTimeMillis()
            updateStatus(execution, ChainStatus.FAILED)
            execution.errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            totalExecutionsFailed++
            Log.e(TAG, "链执行异常[${chain.name}]", e)
        } finally {
            executionJobs.remove(execution.executionId)
        }
    }

    /**
     * 执行一个步骤列表（可递归调用以处理条件分支与循环体）。
     *
     * @param execution 执行实例
     * @param steps 待执行的步骤列表
     * @return 列表处理结果
     */
    private suspend fun executeStepList(
        execution: ChainExecution,
        steps: List<ChainStep>
    ): StepListResult {
        for ((index, step) in steps.withIndex()) {
            ensureActive()
            checkPauseAndCancellation(execution)
            execution.currentStepId = step.id
            execution.currentStepIndex = index
            // 跳过禁用步骤
            if (!step.enabled) continue
            // 依赖检查
            if (step.dependencies.isNotEmpty() &&
                !step.dependencies.all { it in execution.completedStepIds }
            ) {
                Log.w(TAG, "步骤[${step.name}]依赖未满足，跳过: ${step.dependencies}")
                execution.stepResults[step.id] = ClawActionResult.failure("依赖未满足，已跳过")
                continue
            }
            // 分发处理
            val result = processStep(execution, step)
            if (result != StepListResult.CONTINUE) return result
        }
        return StepListResult.CONTINUE
    }

    /** 分发单个步骤到对应处理器。 */
    private suspend fun processStep(
        execution: ChainExecution,
        step: ChainStep
    ): StepListResult = when (step.type) {
        StepType.ACTION -> processActionStep(execution, step)
        StepType.WAIT -> {
            processWaitStep(execution, step)
            StepListResult.CONTINUE
        }
        StepType.CONDITION -> processConditionStep(execution, step)
        StepType.LOOP -> processLoopStep(execution, step)
        StepType.CHECKPOINT -> {
            execution.stepResults[step.id] = ClawActionResult.success("检查点已记录: ${step.name}")
            execution.completedStepIds.add(step.id)
            Log.d(TAG, "到达检查点[${step.name}]")
            StepListResult.CONTINUE
        }
    }

    /**
     * 处理 ACTION 步骤：执行动作，按 [ErrorStrategy] 处理失败。
     */
    private suspend fun processActionStep(
        execution: ChainExecution,
        step: ChainStep
    ): StepListResult {
        val action = step.action
        val executor = actionExecutor
        // 缺失动作或执行器 -> 视为失败，应用全局策略
        if (action == null || executor == null) {
            val reason = if (action == null) "步骤未关联动作" else "未配置动作执行器"
            val failResult = ClawActionResult.failure(reason)
            execution.stepResults[step.id] = failResult
            Log.w(TAG, "步骤[${step.name}]无法执行: $reason")
            return applyGlobalErrorStrategy(execution, step, failResult)
        }
        // 首次执行
        var result = runActionWithTimeout(executor, action, step.timeoutMs)
        var attempts = 1
        // 失败时按策略处理
        while (!result.success) {
            ensureActive()
            when (step.errorStrategy) {
                ErrorStrategy.RETRY -> {
                    if (attempts >= step.maxRetries) break
                    val backoff = step.retryDelayMs * (1L shl (attempts - 1))
                    Log.d(TAG, "步骤[${step.name}]第${attempts}次失败，${backoff}ms后重试: ${result.message}")
                    delay(backoff)
                    result = runActionWithTimeout(executor, action, step.timeoutMs)
                    attempts++
                }
                ErrorStrategy.ALTERNATIVE -> {
                    val alt = step.alternativeAction
                    if (alt != null && attempts == 1) {
                        Log.d(TAG, "步骤[${step.name}]失败，尝试替代动作: ${alt.description}")
                        result = runActionWithTimeout(executor, alt, step.timeoutMs)
                        attempts++
                        // 回到 while 重新判定成功与否
                    } else {
                        break
                    }
                }
                ErrorStrategy.SKIP, ErrorStrategy.ABORT -> break
            }
        }
        execution.stepResults[step.id] = result
        if (result.success) {
            execution.completedStepIds.add(step.id)
            return StepListResult.CONTINUE
        }
        // 失败收尾
        return when (step.errorStrategy) {
            ErrorStrategy.SKIP -> {
                Log.w(TAG, "步骤[${step.name}]失败已跳过: ${result.message}")
                StepListResult.CONTINUE
            }
            ErrorStrategy.RETRY -> {
                // 重试耗尽，回退到全局策略
                applyGlobalErrorStrategy(execution, step, result)
            }
            ErrorStrategy.ABORT -> {
                execution.errorMessage = "步骤[${step.name}]失败且策略为中止: ${result.message}"
                StepListResult.ABORTED
            }
            ErrorStrategy.ALTERNATIVE -> {
                execution.errorMessage = "步骤[${step.name}]及替代动作均失败: ${result.message}"
                StepListResult.ABORTED
            }
        }
    }

    /**
     * 应用链级全局错误策略（步骤重试耗尽或缺失执行器时调用）。
     * @return CONTINUE 表示链继续；ABORTED 表示链中止
     */
    private fun applyGlobalErrorStrategy(
        execution: ChainExecution,
        step: ChainStep,
        result: ClawActionResult
    ): StepListResult {
        val global = chains[execution.chainId]?.globalErrorStrategy ?: ErrorStrategy.ABORT
        return when (global) {
            ErrorStrategy.ABORT -> {
                execution.errorMessage = "步骤[${step.name}]失败，全局策略中止: ${result.message}"
                StepListResult.ABORTED
            }
            ErrorStrategy.SKIP, ErrorStrategy.RETRY, ErrorStrategy.ALTERNATIVE -> {
                // 链级无替代动作可执行；RETRY 已在步骤级尝试过，统一按跳过处理
                Log.w(TAG, "步骤[${step.name}]失败，全局策略跳过: ${result.message}")
                StepListResult.CONTINUE
            }
        }
    }

    /** 在超时限制内执行单个动作。 */
    private suspend fun runActionWithTimeout(
        executor: ActionExecutor,
        action: ClawAction,
        timeoutMs: Long
    ): ClawActionResult {
        return try {
            withTimeoutOrNull(timeoutMs) { executor.execute(action) }
                ?: ClawActionResult.failure("动作执行超时(${timeoutMs}ms)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ClawActionResult.failure("动作执行异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 处理 WAIT 步骤：分片等待以支持暂停响应。 */
    private suspend fun processWaitStep(
        execution: ChainExecution,
        step: ChainStep
    ) {
        val ms = step.waitMs
        execution.stepResults[step.id] = ClawActionResult.success("等待${ms}ms")
        var remaining = ms
        while (remaining > 0) {
            ensureActive()
            checkPauseAndCancellation(execution)
            val chunk = minOf(remaining, PAUSE_POLL_INTERVAL_MS)
            delay(chunk)
            remaining -= chunk
        }
        execution.completedStepIds.add(step.id)
    }

    /** 处理 CONDITION 步骤：求值条件并执行对应分支。 */
    private suspend fun processConditionStep(
        execution: ChainExecution,
        step: ChainStep
    ): StepListResult {
        val condition = step.condition
        val met = if (condition != null) {
            val ctx = contextProvider?.current() ?: ConditionContext()
            condition.evaluate(ctx)
        } else true
        val branchIds = if (met) step.thenStepIds else step.elseStepIds
        val branchSteps = resolveSteps(execution.chainId, branchIds)
        execution.stepResults[step.id] = ClawActionResult.success(
            "条件${if (met) "成立" else "不成立"}，执行${if (met) "then" else "else"}分支"
        )
        Log.d(TAG, "条件步骤[${step.name}]${if (met) "成立" else "不成立"}")
        val result = executeStepList(execution, branchSteps)
        if (result == StepListResult.CONTINUE) {
            execution.completedStepIds.add(step.id)
        }
        return result
    }

    /** 处理 LOOP 步骤：按次数或终止条件重复执行循环体。 */
    private suspend fun processLoopStep(
        execution: ChainExecution,
        step: ChainStep
    ): StepListResult {
        val body = resolveSteps(execution.chainId, step.loopBodyStepIds)
        var iteration = 0
        execution.loopIterations[step.id] = 0
        while (true) {
            ensureActive()
            checkPauseAndCancellation(execution)
            // 次数终止
            if (step.loopCount > 0 && iteration >= step.loopCount) break
            // 条件终止
            if (step.untilCondition != null) {
                val ctx = contextProvider?.current() ?: ConditionContext()
                if (step.untilCondition.evaluate(ctx)) break
            }
            // 无循环条件则仅执行一次
            if (step.loopCount <= 0 && step.untilCondition == null && iteration >= 1) break
            // 死循环保护
            if (iteration >= MAX_LOOP_ITERATIONS) {
                Log.w(TAG, "循环[${step.name}]达到最大迭代次数($MAX_LOOP_ITERATIONS)，强制终止")
                break
            }
            val result = executeStepList(execution, body)
            if (result != StepListResult.CONTINUE) return result
            iteration++
            execution.loopIterations[step.id] = iteration
        }
        execution.stepResults[step.id] = ClawActionResult.success("循环完成，共${iteration}次")
        execution.completedStepIds.add(step.id)
        return StepListResult.CONTINUE
    }

    /** 根据步骤 id 列表从链定义中解析出实际步骤（保持顺序）。 */
    private fun resolveSteps(chainId: String, ids: List<String>): List<ChainStep> {
        val chain = chains[chainId] ?: return emptyList()
        val byId = chain.steps.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * 检查当前协程是否仍处于活跃状态，被取消时抛出 [CancellationException]。
     *
     * [kotlinx.coroutines.ensureActive] 是协程上下文的扩展函数，需要显式接收者；
     * 此处封装为成员级 suspend 函数，使执行引擎各处可无接收者直接调用。
     */
    private suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
    }

    /**
     * 暂停与取消检查：若处于 PAUSED 则轮询等待恢复，若协程被取消则抛出异常。
     */
    private suspend fun checkPauseAndCancellation(execution: ChainExecution) {
        while (statusOf(execution) == ChainStatus.PAUSED) {
            ensureActive()
            delay(PAUSE_POLL_INTERVAL_MS)
        }
        ensureActive()
    }

    // ============================================================
    // 状态读写辅助
    // ============================================================

    /** 线程安全地读取执行状态。 */
    private fun statusOf(execution: ChainExecution): ChainStatus =
        synchronized(execution) { execution.status }

    /** 线程安全地更新执行状态。 */
    private fun updateStatus(execution: ChainExecution, status: ChainStatus) {
        synchronized(execution) { execution.status = status }
    }

    /** 是否为运行态（RUNNING 或 PAUSED）。 */
    private fun isRunningStatus(status: ChainStatus): Boolean =
        status == ChainStatus.RUNNING || status == ChainStatus.PAUSED

    /** 是否为终态（COMPLETED / FAILED / CANCELLED）。 */
    private fun isTerminalStatus(status: ChainStatus): Boolean =
        status == ChainStatus.COMPLETED || status == ChainStatus.FAILED || status == ChainStatus.CANCELLED

    /** 清理已结束的执行记录，保留最近的若干条。 */
    private fun cleanupFinishedExecutions() {
        val terminal = executions.values.filter { isTerminalStatus(statusOf(it)) }
        val toRemove = terminal.sortedBy { it.endTime }.dropLast(EXECUTION_HISTORY_RETENTION)
        for (e in toRemove) {
            executions.remove(e.executionId)
            executionJobs.remove(e.executionId)
        }
        if (toRemove.isNotEmpty()) {
            Log.d(TAG, "清理 ${toRemove.size} 条已结束执行记录")
        }
    }

    // ============================================================
    // 触发器管理
    // ============================================================

    /**
     * 添加一个触发器。
     *
     * @param trigger 触发器定义（id 为空时自动生成；关联链必须存在）
     * @return true 表示添加成功
     */
    fun addTrigger(trigger: Trigger): Boolean {
        val id = if (trigger.id.isBlank()) nextTriggerId() else trigger.id
        val toStore = if (trigger.id == id) trigger else trigger.copy(id = id)
        if (!chains.containsKey(toStore.chainId)) {
            Log.w(TAG, "添加触发器失败：关联链不存在 ${toStore.chainId}")
            return false
        }
        if (triggers.size >= MAX_TRIGGERS && !triggers.containsKey(id)) {
            Log.w(TAG, "添加触发器失败：触发器数达上限 $MAX_TRIGGERS")
            return false
        }
        triggers[id] = toStore
        // 关联到链的 triggerIds 快照
        val chain = chains[toStore.chainId]
        if (chain != null) {
            synchronized(chain) {
                if (id !in chain.triggerIds) chain.triggerIds.add(id)
            }
        }
        // 初始化 INTERVAL 触发器的下次触发时间
        if (toStore.type == TriggerType.INTERVAL && toStore.intervalMs > 0L && toStore.nextTriggerAt <= 0L) {
            toStore.nextTriggerAt = System.currentTimeMillis() + toStore.intervalMs
        }
        Log.i(TAG, "添加触发器[${toStore.name}]: type=${toStore.type}, chain=${toStore.chainId}")
        return true
    }

    /**
     * 移除一个触发器。
     * @return true 表示移除成功
     */
    fun removeTrigger(triggerId: String): Boolean {
        val trigger = triggers.remove(triggerId) ?: return false
        val chain = chains[trigger.chainId]
        if (chain != null) {
            synchronized(chain) { chain.triggerIds.remove(triggerId) }
        }
        Log.i(TAG, "移除触发器: ${trigger.name}")
        return true
    }

    /** 获取所有触发器。 */
    fun getTriggers(): List<Trigger> = triggers.values.toList()

    /** 获取指定链关联的触发器。 */
    fun getTriggersForChain(chainId: String): List<Trigger> =
        triggers.values.filter { it.chainId == chainId }.toList()

    /**
     * 启动触发器后台监控。
     * 周期性检查所有启用的触发器，满足条件时自动启动关联链。
     */
    fun startTriggerMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            Log.i(TAG, "触发器监控已启动 (间隔 ${TRIGGER_CHECK_INTERVAL_MS}ms)")
            while (isActive) {
                try {
                    checkTriggers()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "触发器检查异常", e)
                }
                delay(TRIGGER_CHECK_INTERVAL_MS)
            }
            Log.i(TAG, "触发器监控已停止")
        }
    }

    /** 停止触发器后台监控。 */
    fun stopTriggerMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        Log.i(TAG, "触发器监控已停止")
    }

    /** 触发器是否正在监控。 */
    fun isTriggerMonitorRunning(): Boolean = monitorJob?.isActive == true

    /**
     * 检查所有触发器，满足条件时触发关联链。
     * 由后台监控周期调用，也可手动调用以立即检查。
     */
    suspend fun checkTriggers() {
        val now = System.currentTimeMillis()
        for (trigger in triggers.values) {
            if (!trigger.enabled) continue
            val chain = chains[trigger.chainId] ?: continue
            if (!chain.enabled) continue
            try {
                when (trigger.type) {
                    TriggerType.TIME -> {
                        if (trigger.nextTriggerAt <= 0L) {
                            trigger.nextTriggerAt = computeNextTimeTrigger(trigger.timeExpression, now)
                        }
                        if (trigger.nextTriggerAt != Long.MAX_VALUE && now >= trigger.nextTriggerAt) {
                            fireTrigger(trigger)
                            trigger.lastTriggeredAt = now
                            trigger.nextTriggerAt = computeNextTimeTrigger(trigger.timeExpression, now + 60_000L)
                        }
                    }
                    TriggerType.INTERVAL -> {
                        if (trigger.intervalMs <= 0L) continue
                        if (trigger.nextTriggerAt <= 0L) {
                            trigger.nextTriggerAt = now + trigger.intervalMs
                        }
                        if (now >= trigger.nextTriggerAt) {
                            fireTrigger(trigger)
                            trigger.lastTriggeredAt = now
                            trigger.nextTriggerAt = now + trigger.intervalMs
                        }
                    }
                    TriggerType.EVENT -> {
                        val ctx = contextProvider?.current() ?: continue
                        if (evaluateEvent(trigger, ctx) &&
                            now - trigger.lastTriggeredAt > TRIGGER_COOLDOWN_MS
                        ) {
                            fireTrigger(trigger)
                            trigger.lastTriggeredAt = now
                        }
                    }
                    TriggerType.CONDITION -> {
                        val cond = trigger.condition ?: continue
                        val ctx = contextProvider?.current() ?: continue
                        if (cond.evaluate(ctx) &&
                            now - trigger.lastTriggeredAt > TRIGGER_COOLDOWN_MS
                        ) {
                            fireTrigger(trigger)
                            trigger.lastTriggeredAt = now
                        }
                    }
                    TriggerType.MANUAL -> {
                        // 手动触发，不自动检查
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "触发器[${trigger.name}]检查异常", e)
            }
        }
    }

    /** 激活触发器，启动关联链。 */
    private fun fireTrigger(trigger: Trigger) {
        totalTriggersFired++
        Log.i(TAG, "触发器[${trigger.name}]激活，启动链 ${trigger.chainId}")
        startChain(trigger.chainId, triggeredBy = "trigger:${trigger.name}")
    }

    /**
     * 评估事件触发器条件。
     * eventTarget 含 "." 视为应用包名（前台包名匹配），否则视为屏幕文本（包含匹配）。
     */
    private fun evaluateEvent(trigger: Trigger, ctx: ConditionContext): Boolean {
        val target = trigger.eventTarget
        if (target.isBlank()) return false
        return if (target.contains('.')) {
            ctx.foregroundPackage == target
        } else {
            ctx.screenText.contains(target)
        }
    }

    /**
     * 计算时间表达式（"HH:mm" 或 "HH:mm:ss"）在 [after] 之后的下一次触发时间戳。
     * 若表达式非法返回 [Long.MAX_VALUE]。
     */
    private fun computeNextTimeTrigger(expression: String, after: Long): Long {
        val parts = expression.trim().split(":")
        if (parts.isEmpty()) return Long.MAX_VALUE
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return Long.MAX_VALUE
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return Long.MAX_VALUE
        val cal = Calendar.getInstance().apply {
            timeInMillis = after
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= after) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    // ============================================================
    // 链持久化（JSON 导入/导出 + 文件存取）
    // ============================================================

    /**
     * 将指定链导出为 JSON 字符串。
     * @return JSON 字符串；链不存在返回 null
     */
    fun exportChain(chainId: String): String? {
        val chain = chains[chainId] ?: return null
        return json.encodeToString(JsonObject.serializer(), chain.toJsonObject())
    }

    /**
     * 从 JSON 字符串导入一条链。
     * @return 链 id；导入失败返回 null
     */
    fun importChain(jsonStr: String): String? {
        return try {
            val obj = json.decodeFromString(JsonObject.serializer(), jsonStr)
            val chain = jsonObjectToChainDefinition(obj)
            if (chain.id.isBlank()) return null
            chain.lastModified = System.currentTimeMillis()
            registerChainInternal(chain)
            Log.i(TAG, "导入链[${chain.name}]: ${chain.id}")
            chain.id
        } catch (e: Throwable) {
            Log.e(TAG, "导入链失败", e)
            null
        }
    }

    /**
     * 导出所有链与触发器为单个 JSON 字符串。
     */
    fun exportAll(): String {
        val obj = JsonObject(mapOf(
            "chains" to JsonArray(chains.values.map { it.toJsonObject() }),
            "triggers" to JsonArray(triggers.values.map { it.toJsonObject() })
        ))
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * 从 JSON 字符串导入所有链与触发器（会覆盖同 id 项）。
     * @return 成功导入的链数量
     */
    fun importAll(jsonStr: String): Int {
        var count = 0
        try {
            val obj = json.decodeFromString(JsonObject.serializer(), jsonStr)
            (obj["chains"] as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.let { jsonObjectToChainDefinition(it) }?.let { chain ->
                    if (chain.id.isNotBlank()) {
                        chain.lastModified = System.currentTimeMillis()
                        if (registerChainInternal(chain)) count++
                    }
                }
            }
            (obj["triggers"] as? JsonArray)?.forEach { el ->
                (el as? JsonObject)?.let { jsonObjectToTrigger(it) }?.let { addTrigger(it) }
            }
            Log.i(TAG, "批量导入完成: $count 条链")
        } catch (e: Throwable) {
            Log.e(TAG, "批量导入失败", e)
        }
        return count
    }

    /**
     * 将所有链与触发器保存到指定目录。
     * 每条链保存为 "<id>.chain.json"，触发器保存为 "_triggers.json"。
     * @return 成功保存的链数量
     */
    fun saveToDirectory(dir: File): Int {
        if (!dir.exists()) dir.mkdirs()
        var count = 0
        for (chain in chains.values) {
            val jsonStr = exportChain(chain.id) ?: continue
            runCatching { File(dir, "${chain.id}.chain.json").writeText(jsonStr) }
                .onFailure { Log.e(TAG, "保存链[${chain.name}]失败", it) }
            count++
        }
        val triggersObj = JsonObject(mapOf(
            "triggers" to JsonArray(triggers.values.map { it.toJsonObject() })
        ))
        runCatching {
            File(dir, "_triggers.json").writeText(
                json.encodeToString(JsonObject.serializer(), triggersObj)
            )
        }.onFailure { Log.e(TAG, "保存触发器失败", it) }
        Log.i(TAG, "已保存 $count 条链到 ${dir.absolutePath}")
        return count
    }

    /**
     * 从指定目录加载所有链与触发器。
     * @return 成功加载的链数量
     */
    fun loadFromDirectory(dir: File): Int {
        if (!dir.exists()) return 0
        var count = 0
        dir.listFiles { f -> f.name.endsWith(".chain.json") }?.forEach { f ->
            val jsonStr = runCatching { f.readText() }.getOrNull() ?: return@forEach
            if (importChain(jsonStr) != null) count++
        }
        val triggersFile = File(dir, "_triggers.json")
        if (triggersFile.exists()) {
            runCatching {
                val obj = json.decodeFromString(JsonObject.serializer(), triggersFile.readText())
                (obj["triggers"] as? JsonArray)?.forEach { el ->
                    (el as? JsonObject)?.let { jsonObjectToTrigger(it) }?.let { addTrigger(it) }
                }
            }.onFailure { Log.e(TAG, "加载触发器失败", it) }
        }
        Log.i(TAG, "从 ${dir.absolutePath} 加载了 $count 条链")
        return count
    }

    // ============================================================
    // JSON 编解码辅助
    // ============================================================

    /** [ClawAction] -> [JsonObject]。 */
    private fun ClawAction.toJsonObject(): JsonObject = JsonObject(mapOf(
        "action" to JsonPrimitive(actionName),
        "params" to params,
        "description" to JsonPrimitive(description)
    ))

    /** [JsonObject] -> [ClawAction]。 */
    private fun jsonObjectToClawAction(obj: JsonObject): ClawAction = ClawAction(
        actionName = obj["action"]?.jsonPrimitive?.contentOrNull ?: "",
        params = obj["params"] as? JsonObject ?: JsonObject(emptyMap()),
        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
    )

    /** [ChainCondition] -> [JsonObject]。 */
    private fun ChainCondition.toJsonObject(): JsonObject = JsonObject(mapOf(
        "type" to JsonPrimitive(type.name),
        "target" to JsonPrimitive(target),
        "operator" to JsonPrimitive(operator),
        "value" to JsonPrimitive(value),
        "negate" to JsonPrimitive(negate)
    ))

    /** [JsonObject] -> [ChainCondition]。 */
    private fun jsonObjectToChainCondition(obj: JsonObject): ChainCondition = ChainCondition(
        type = runCatching {
            ConditionType.valueOf(obj["type"]?.jsonPrimitive?.contentOrNull ?: "CUSTOM")
        }.getOrDefault(ConditionType.CUSTOM),
        target = obj["target"]?.jsonPrimitive?.contentOrNull ?: "",
        operator = obj["operator"]?.jsonPrimitive?.contentOrNull ?: "==",
        value = obj["value"]?.jsonPrimitive?.contentOrNull ?: "",
        negate = obj["negate"]?.jsonPrimitive?.booleanOrNull ?: false
    )

    /** [ChainStep] -> [JsonObject]。 */
    private fun ChainStep.toJsonObject(): JsonObject {
        val map = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "type" to JsonPrimitive(type.name),
            "name" to JsonPrimitive(name),
            "description" to JsonPrimitive(description),
            "thenStepIds" to JsonArray(thenStepIds.map { JsonPrimitive(it) }),
            "elseStepIds" to JsonArray(elseStepIds.map { JsonPrimitive(it) }),
            "loopBodyStepIds" to JsonArray(loopBodyStepIds.map { JsonPrimitive(it) }),
            "loopCount" to JsonPrimitive(loopCount),
            "waitMs" to JsonPrimitive(waitMs),
            "dependencies" to JsonArray(dependencies.map { JsonPrimitive(it) }),
            "errorStrategy" to JsonPrimitive(errorStrategy.name),
            "maxRetries" to JsonPrimitive(maxRetries),
            "retryDelayMs" to JsonPrimitive(retryDelayMs),
            "timeoutMs" to JsonPrimitive(timeoutMs),
            "enabled" to JsonPrimitive(enabled)
        )
        action?.let { map["action"] = it.toJsonObject() }
        condition?.let { map["condition"] = it.toJsonObject() }
        untilCondition?.let { map["untilCondition"] = it.toJsonObject() }
        alternativeAction?.let { map["alternativeAction"] = it.toJsonObject() }
        return JsonObject(map)
    }

    /** [JsonObject] -> [ChainStep]。 */
    private fun jsonObjectToChainStep(obj: JsonObject): ChainStep = ChainStep(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        type = runCatching {
            StepType.valueOf(obj["type"]?.jsonPrimitive?.contentOrNull ?: "ACTION")
        }.getOrDefault(StepType.ACTION),
        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
        action = (obj["action"] as? JsonObject)?.let { jsonObjectToClawAction(it) },
        condition = (obj["condition"] as? JsonObject)?.let { jsonObjectToChainCondition(it) },
        thenStepIds = stringList(obj, "thenStepIds"),
        elseStepIds = stringList(obj, "elseStepIds"),
        loopBodyStepIds = stringList(obj, "loopBodyStepIds"),
        loopCount = obj["loopCount"]?.jsonPrimitive?.intOrNull ?: 0,
        untilCondition = (obj["untilCondition"] as? JsonObject)?.let { jsonObjectToChainCondition(it) },
        waitMs = obj["waitMs"]?.jsonPrimitive?.longOrNull ?: 0L,
        dependencies = stringList(obj, "dependencies"),
        errorStrategy = runCatching {
            ErrorStrategy.valueOf(obj["errorStrategy"]?.jsonPrimitive?.contentOrNull ?: "SKIP")
        }.getOrDefault(ErrorStrategy.SKIP),
        maxRetries = obj["maxRetries"]?.jsonPrimitive?.intOrNull ?: DEFAULT_MAX_RETRIES,
        retryDelayMs = obj["retryDelayMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_RETRY_DELAY_MS,
        alternativeAction = (obj["alternativeAction"] as? JsonObject)?.let { jsonObjectToClawAction(it) },
        timeoutMs = obj["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_STEP_TIMEOUT_MS,
        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
    )

    /** [Trigger] -> [JsonObject]。 */
    private fun Trigger.toJsonObject(): JsonObject {
        val map = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "type" to JsonPrimitive(type.name),
            "name" to JsonPrimitive(name),
            "chainId" to JsonPrimitive(chainId),
            "timeExpression" to JsonPrimitive(timeExpression),
            "intervalMs" to JsonPrimitive(intervalMs),
            "eventTarget" to JsonPrimitive(eventTarget),
            "enabled" to JsonPrimitive(enabled),
            "createdAt" to JsonPrimitive(createdAt),
            "lastTriggeredAt" to JsonPrimitive(lastTriggeredAt),
            "nextTriggerAt" to JsonPrimitive(nextTriggerAt)
        )
        condition?.let { map["condition"] = it.toJsonObject() }
        return JsonObject(map)
    }

    /** [JsonObject] -> [Trigger]。 */
    private fun jsonObjectToTrigger(obj: JsonObject): Trigger = Trigger(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        type = runCatching {
            TriggerType.valueOf(obj["type"]?.jsonPrimitive?.contentOrNull ?: "MANUAL")
        }.getOrDefault(TriggerType.MANUAL),
        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
        chainId = obj["chainId"]?.jsonPrimitive?.contentOrNull ?: "",
        timeExpression = obj["timeExpression"]?.jsonPrimitive?.contentOrNull ?: "",
        intervalMs = obj["intervalMs"]?.jsonPrimitive?.longOrNull ?: 0L,
        eventTarget = obj["eventTarget"]?.jsonPrimitive?.contentOrNull ?: "",
        condition = (obj["condition"] as? JsonObject)?.let { jsonObjectToChainCondition(it) },
        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        lastTriggeredAt = obj["lastTriggeredAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        nextTriggerAt = obj["nextTriggerAt"]?.jsonPrimitive?.longOrNull ?: 0L
    )

    /** [ChainDefinition] -> [JsonObject]。 */
    private fun ChainDefinition.toJsonObject(): JsonObject = JsonObject(mapOf(
        "id" to JsonPrimitive(id),
        "name" to JsonPrimitive(name),
        "description" to JsonPrimitive(description),
        "steps" to JsonArray(steps.map { it.toJsonObject() }),
        "triggerIds" to JsonArray(triggerIds.map { JsonPrimitive(it) }),
        "maxConcurrentExecutions" to JsonPrimitive(maxConcurrentExecutions),
        "globalErrorStrategy" to JsonPrimitive(globalErrorStrategy.name),
        "repeatable" to JsonPrimitive(repeatable),
        "priority" to JsonPrimitive(priority),
        "createdAt" to JsonPrimitive(createdAt),
        "lastModified" to JsonPrimitive(lastModified),
        "enabled" to JsonPrimitive(enabled)
    ))

    /** [JsonObject] -> [ChainDefinition]。 */
    private fun jsonObjectToChainDefinition(obj: JsonObject): ChainDefinition = ChainDefinition(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
        steps = (obj["steps"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.let { o -> jsonObjectToChainStep(o) } }
            ?: emptyList(),
        triggerIds = stringList(obj, "triggerIds").toMutableList(),
        maxConcurrentExecutions = obj["maxConcurrentExecutions"]?.jsonPrimitive?.intOrNull
            ?: DEFAULT_MAX_CONCURRENT_EXECUTIONS,
        globalErrorStrategy = runCatching {
            ErrorStrategy.valueOf(obj["globalErrorStrategy"]?.jsonPrimitive?.contentOrNull ?: "ABORT")
        }.getOrDefault(ErrorStrategy.ABORT),
        repeatable = obj["repeatable"]?.jsonPrimitive?.booleanOrNull ?: true,
        priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: 0,
        createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        lastModified = obj["lastModified"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
    )

    /** 从 JSON 对象中读取字符串数组字段。 */
    private fun stringList(obj: JsonObject, key: String): List<String> =
        (obj[key] as? JsonArray)?.map { it.jsonPrimitive.contentOrNull ?: "" } ?: emptyList()

    // ============================================================
    // 统计与重置
    // ============================================================

    /**
     * 获取自动化链的综合统计摘要（用于 UI 展示与调试）。
     */
    fun getSummary(): String {
        val active = getActiveChains().size
        val runningChains = chains.values.count { it.enabled }
        return buildString {
            append("自动化链统计: 链${chains.size}个(启用${runningChains}), ")
            append("触发器${triggers.size}个, ")
            append("活跃执行${active}个, ")
            append("历史执行${executions.size}条 | ")
            append("累计: 启动${totalExecutionsStarted}, ")
            append("成功${totalExecutionsCompleted}, ")
            append("失败${totalExecutionsFailed}, ")
            append("取消${totalExecutionsCancelled}, ")
            append("触发${totalTriggersFired}次")
            if (isTriggerMonitorRunning()) append(" | 监控运行中")
        }
    }

    /**
     * 获取指定执行实例的可读进度描述。
     */
    fun getExecutionProgress(executionId: String): String? {
        val exec = executions[executionId] ?: return null
        val chain = chains[exec.chainId]
        val total = chain?.steps?.size ?: 0
        val completed = exec.completedStepIds.size
        val status = statusOf(exec)
        val time = timeFormatter.format(java.util.Date(exec.startTime))
        return buildString {
            append("[${exec.chainName}] ")
            append("状态:${status.description()}, ")
            append("进度:$completed/$total, ")
            append("触发:${exec.triggeredBy}, ")
            append("开始:$time")
            exec.errorMessage?.let { append(", 错误:$it") }
        }
    }

    /** 扩展：为 [ChainStatus] 提供中文描述。 */
    private fun ChainStatus.description(): String = when (this) {
        ChainStatus.IDLE -> "空闲"
        ChainStatus.RUNNING -> "运行中"
        ChainStatus.PAUSED -> "已暂停"
        ChainStatus.COMPLETED -> "已完成"
        ChainStatus.FAILED -> "已失败"
        ChainStatus.CANCELLED -> "已取消"
    }

    /**
     * 清空所有链、触发器、执行记录与统计计数（预置模板也会被清除）。
     */
    fun clear() {
        stopTriggerMonitor()
        // 取消所有运行中的执行
        for (job in executionJobs.values) {
            runCatching { job.cancel() }
        }
        chains.clear()
        triggers.clear()
        executions.clear()
        executionJobs.clear()
        totalExecutionsStarted = 0
        totalExecutionsCompleted = 0
        totalExecutionsFailed = 0
        totalExecutionsCancelled = 0
        totalTriggersFired = 0
        Log.d(TAG, "已清空所有自动化链数据与统计")
    }
}
