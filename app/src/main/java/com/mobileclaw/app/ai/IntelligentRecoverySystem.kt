package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.math.roundToLong

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 失败类型分类。
 *
 * 描述任务执行失败的具体原因类别。每种失败类型对应一组默认恢复策略链，
 * 详见 [IntelligentRecoverySystem] 的策略表。失败类型由
 * [IntelligentRecoverySystem.classifyFailure] 根据错误信息与动作上下文判定。
 */
enum class FailureType {
    /** 元素未找到：屏幕上不存在目标文本或可点击元素。 */
    ELEMENT_NOT_FOUND,

    /** 超时：动作执行未在规定时间内完成。 */
    TIMEOUT,

    /** 权限被拒绝：缺少必要权限（如无障碍服务、存储权限等）。 */
    PERMISSION_DENIED,

    /** 应用崩溃：目标应用崩溃、ANR 或被系统强制停止。 */
    APP_CRASH,

    /** 网络错误：连接失败、Socket 异常、DNS 解析失败等。 */
    NETWORK_ERROR,

    /** 意外状态：当前界面与预期不符（如弹出了非预期弹窗、跳转到错误页面）。 */
    UNEXPECTED_STATE,

    /** 元素不可点击：目标元素存在但无法点击（被遮挡、未启用、不可交互）。 */
    ELEMENT_NOT_CLICKABLE,

    /** 未知错误：无法归类的其他失败。 */
    UNKNOWN
}

/**
 * 恢复动作类型。
 *
 * 定义系统可采取的具体恢复手段。每种恢复动作在
 * [IntelligentRecoverySystem.executeRecovery] 中有对应的执行逻辑。
 */
enum class RecoveryAction {
    /** 延迟后重试同一动作：适用于暂时性故障（网络抖动、UI 未就绪）。 */
    RETRY_WITH_DELAY,

    /** 替代路径：换一种方式达成相同目标（如文本点击改为查找并点击）。 */
    ALTERNATIVE_PATH,

    /** 回滚后重试：回到上一状态再重试（如按返回键回到上一页面）。 */
    ROLLBACK_AND_RETRY,

    /** 重启应用后重试：适用于应用崩溃或进入异常状态。 */
    RESTART_APP,

    /** 调整参数：修改动作参数后重试（如微调坐标、增加超时时长）。 */
    ADJUST_PARAMETERS,

    /** 跳过并继续：跳过失败动作继续后续任务（非关键步骤失败时）。 */
    SKIP_AND_CONTINUE,

    /** 上报用户：升级为需人工介入，停止自动恢复。 */
    ESCALATE
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 失败上下文。
 *
 * 描述一次任务执行失败的完整上下文，是恢复决策的核心输入。由
 * [IntelligentRecoverySystem.classifyFailure] 构造，传递给
 * [IntelligentRecoverySystem.selectRecoveryStrategy] 与
 * [IntelligentRecoverySystem.generateRecoveryPlan]。
 *
 * @property failureType      失败类型分类结果。
 * @property action           执行失败的动作。
 * @property result           动作执行结果（含失败信息）。
 * @property attemptCount     已尝试次数（含首次尝试，从 1 开始）。
 * @property timestamp        失败发生时间戳（毫秒）。
 * @property previousActions  失败前已执行的动作序列（用于回滚分析，可为空）。
 * @property screenContext     失败时的屏幕上下文描述（如当前页面、可见文本摘要），可为 null。
 * @property appContext        失败时的应用上下文（包名），用于学习与统计。
 */
data class FailureContext(
    val failureType: FailureType,
    val action: ClawAction,
    val result: ClawActionResult,
    val attemptCount: Int,
    val timestamp: Long,
    val previousActions: List<ClawAction> = emptyList(),
    val screenContext: String? = null,
    val appContext: String? = null
)

/**
 * 恢复策略。
 *
 * 描述单个恢复步骤的完整参数，由 [IntelligentRecoverySystem.selectRecoveryStrategy]
 * 返回，也可作为 [RecoveryPlan] 中的一个步骤。
 *
 * @property action       恢复动作类型。
 * @property delayMs      执行恢复前的等待时间（毫秒），0 表示不等待。
 * @property params       策略参数（如调整后的坐标偏移、替代动作参数等），以 [JsonObject] 存储。
 * @property description  策略的自然语言说明（中文），便于日志追踪。
 * @property priority     优先级（越小越优先），用于多策略排序。
 */
data class RecoveryStrategy(
    val action: RecoveryAction,
    val delayMs: Long,
    val params: JsonObject,
    val description: String,
    val priority: Int
)

/**
 * 恢复计划。
 *
 * 针对复杂失败生成的多步恢复方案，包含一组按顺序执行的 [RecoveryStrategy]。
 * 由 [IntelligentRecoverySystem.generateRecoveryPlan] 生成。调用方应依次执行每个步骤，
 * 一旦某步骤成功即可停止后续步骤。
 *
 * @property context          触发该计划的失败上下文。
 * @property steps            恢复步骤列表（按优先级/顺序排列）。
 * @property description      计划的整体说明（中文）。
 * @property estimatedTimeMs  预计总耗时（毫秒，含各步骤延迟之和）。
 */
data class RecoveryPlan(
    val context: FailureContext,
    val steps: List<RecoveryStrategy>,
    val description: String,
    val estimatedTimeMs: Long
)

/**
 * 恢复尝试记录。
 *
 * 记录一次恢复尝试的执行情况，用于学习与统计。由
 * [IntelligentRecoverySystem.recordRecoveryResult] 内部记录并归入历史。
 *
 * @property failureType  触发恢复的失败类型。
 * @property action       采取的恢复动作。
 * @property success      恢复是否成功。
 * @property timestamp    恢复完成时间戳（毫秒）。
 * @property durationMs   恢复耗时（毫秒）。
 * @property details      恢复详情（中文，含失败信息摘要与策略说明）。
 */
data class RecoveryAttempt(
    val failureType: FailureType,
    val action: RecoveryAction,
    val success: Boolean,
    val timestamp: Long,
    val durationMs: Long,
    val details: String
)

/**
 * 恢复历史统计。
 *
 * 汇总某失败类型的恢复历史，反映各类恢复策略的效果，并给出历史最佳策略。
 * 由 [IntelligentRecoverySystem.getRecoveryHistory] 返回。
 *
 * @property failureType    失败类型。
 * @property totalAttempts  恢复尝试总次数。
 * @property successCount   恢复成功次数。
 * @property successRate    整体成功率（0.0-1.0），总次数为 0 时为 0.0。
 * @property actionStats     各恢复动作的统计：动作 -> (尝试次数, 成功次数)。
 * @property bestAction     历史最佳恢复动作（成功率最高且样本充足），无足够样本时为 null。
 */
data class RecoveryHistory(
    val failureType: FailureType,
    val totalAttempts: Int,
    val successCount: Int,
    val successRate: Double,
    val actionStats: Map<RecoveryAction, Pair<Int, Int>>,
    val bestAction: RecoveryAction?
)

// =============================================================================
//  IntelligentRecoverySystem —— 智能恢复系统
// =============================================================================

/**
 * IntelligentRecoverySystem —— 智能恢复系统
 *
 * 为 MobileClaw 的任务执行引擎提供基于失败分析与历史学习的智能恢复能力。当动作执行
 * 失败时，本系统分析失败上下文、分类失败原因，并选择最合适的恢复策略，从而在「尽快
 * 恢复任务」与「避免无效重试」之间取得平衡。
 *
 * 七大核心能力：
 * 1. **失败分类**：根据错误信息与动作上下文，将失败归入 8 种失败类型
 *    （元素未找到、超时、权限拒绝、应用崩溃、网络错误、意外状态、元素不可点击、未知）。
 * 2. **恢复策略选择**：基于失败类型、尝试次数与历史学习，从 7 种恢复动作中选取最佳策略。
 * 3. **恢复策略链**：为每种失败类型预置一条优先级递进的恢复策略链，尝试次数越多越激进。
 * 4. **恢复计划生成**：针对复杂失败，生成多步恢复计划，按序尝试直至成功或升级。
 * 5. **恢复执行**：将策略转化为可执行动作（如调整参数、构造替代动作、回滚动作、重启动作）。
 * 6. **历史记录与学习**：线程安全地记录每次恢复尝试的成功与否，统计各策略对不同失败
 *    类型的成功率，从而动态选出历史最佳策略。
 * 7. **升级机制**：尝试次数超过上限后自动升级为人工介入（ESCALATE），避免无限重试。
 *
 * ### 各失败类型的默认恢复策略链
 * | 失败类型              | 恢复策略链（按优先级）                                         |
 * |-----------------------|---------------------------------------------------------------|
 * | ELEMENT_NOT_FOUND     | 调整参数 → 替代路径 → 回滚重试 → 升级                         |
 * | TIMEOUT               | 延迟重试 → 调整参数 → 回滚重试 → 升级                         |
 * | PERMISSION_DENIED     | 升级（不自动恢复，需用户授权）                                |
 * | APP_CRASH             | 重启应用 → 回滚重试 → 升级                                    |
 * | NETWORK_ERROR         | 延迟重试 → 替代路径 → 升级                                    |
 * | UNEXPECTED_STATE      | 回滚重试 → 替代路径 → 升级                                    |
 * | ELEMENT_NOT_CLICKABLE | 调整参数 → 替代路径 → 回滚重试 → 升级                         |
 * | UNKNOWN              | 延迟重试 → 回滚重试 → 升级                                    |
 *
 * ### 线程安全
 * 所有存储均使用 [ConcurrentHashMap]，计数使用 [AtomicInteger] / [AtomicLong]，
 * 可被多线程并发调用（典型场景：执行线程触发恢复、分析线程读取统计）。
 *
 * ### 典型调用流程
 * ```
 * val recovery = IntelligentRecoverySystem()
 * // 1. 分类失败
 * val context = recovery.classifyFailure(action, failedResult, attemptCount = 1)
 * // 2. 选择恢复策略
 * val strategy = recovery.selectRecoveryStrategy(context)
 * // 3. 执行恢复（获取修改后的动作）
 * val modifiedAction = recovery.executeRecovery(strategy, action)
 * Thread.sleep(strategy.delayMs)
 * if (modifiedAction != null) execute(modifiedAction)
 * // 4. 记录恢复结果（用于学习）
 * recovery.recordRecoveryResult(context.failureType, strategy.action, recoverySucceeded)
 * // 复杂失败可生成多步计划
 * val plan = recovery.generateRecoveryPlan(context)
 * println(recovery.getRecoveryStats())
 * ```
 */
class IntelligentRecoverySystem {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 日志标签。 */
        private const val TAG = "IntelligentRecoverySystem"

        /** 学习生效所需的最小样本数，低于此数时沿用默认策略链。 */
        private const val MIN_SAMPLES_FOR_LEARNING = 5

        /** 单个失败类型的最大自动恢复尝试次数，超出后强制升级。 */
        private const val MAX_RECOVERY_ATTEMPTS = 4

        /** 延迟重试的初始延迟（毫秒）。 */
        private const val DEFAULT_RETRY_DELAY_MS = 1000L

        /** 单次重试延迟的上限（毫秒）。 */
        private const val MAX_RETRY_DELAY_MS = 8000L

        /** 指数退避的倍率（每次重试延迟翻倍）。 */
        private const val RETRY_MULTIPLIER = 2.0

        /** 重启应用后的等待时间（毫秒），等待应用完成启动。 */
        private const val RESTART_DELAY_MS = 2000L

        /** 回滚操作后的等待时间（毫秒），等待界面回退完成。 */
        private const val ROLLBACK_DELAY_MS = 800L

        /** 调整参数后的等待时间（毫秒）。 */
        private const val ADJUST_DELAY_MS = 300L

        /** 替代路径前的等待时间（毫秒）。 */
        private const val ALTERNATIVE_DELAY_MS = 500L

        /** 坐标微调偏移量（像素），用于 ADJUST_PARAMETERS 时微调点击坐标。 */
        private const val COORDINATE_ADJUST_OFFSET = 15

        /** 超时重试时，超时时长的增长因子（增加 50%）。 */
        private const val TIMEOUT_GROWTH_FACTOR = 1.5

        /** 高成功率阈值：历史成功率高于此值时优先采用该策略。 */
        private const val HIGH_SUCCESS_THRESHOLD = 0.6

        /** 低成功率阈值：历史成功率低于此值时降权该策略。 */
        private const val LOW_SUCCESS_THRESHOLD = 0.2

        /** 历史记录最大保留条数，超出时按 FIFO 淘汰。 */
        private const val MAX_HISTORY_RECORDS = 1000
    }

    // =========================================================================
    //  内部数据结构
    // =========================================================================

    /**
     * 单个 (失败类型, 恢复动作) 组合的恢复统计（线程安全）。
     *
     * @property totalAttempts 恢复尝试总次数。
     * @property successCount  恢复成功次数。
     */
    private class StrategyStats {
        val totalAttempts: AtomicInteger = AtomicInteger(0)
        val successCount: AtomicInteger = AtomicInteger(0)

        /** 成功率，总次数为 0 时返回 0.0。 */
        fun successRate(): Double {
            val total = totalAttempts.get()
            return if (total == 0) 0.0 else successCount.get().toDouble() / total
        }
    }

    // =========================================================================
    //  状态字段（全部线程安全）
    // =========================================================================

    /**
     * 恢复统计存储：键 = 失败类型，值 = (恢复动作 -> [StrategyStats]) 的内层映射。
     *
     * 采用双层 [ConcurrentHashMap] 结构，支持按失败类型聚合查看各策略效果。
     * 内层映射采用懒初始化：首次记录某组合时才创建对应 [StrategyStats]。
     */
    private val recoveryStats: ConcurrentHashMap<FailureType, ConcurrentHashMap<RecoveryAction, StrategyStats>> =
        ConcurrentHashMap()

    /**
     * 恢复尝试历史记录（按时间顺序），用于统计与回溯。
     *
     * 超出 [MAX_HISTORY_RECORDS] 时按 FIFO 淘汰最旧记录。
     */
    private val attemptHistory: ConcurrentHashMap<Long, RecoveryAttempt> = ConcurrentHashMap()

    /** 历史记录 ID 自增计数器，作为 [attemptHistory] 的键。 */
    private val historyIdCounter = AtomicLong(0)

    /** 累计恢复尝试总数（含已淘汰）。 */
    private val totalRecoveryAttempts = AtomicInteger(0)

    /** 累计恢复成功总数。 */
    private val totalRecoverySuccess = AtomicInteger(0)

    /** 累计升级（ESCALATE）次数。 */
    private val totalEscalations = AtomicInteger(0)

    // =========================================================================
    //  默认恢复策略链
    // =========================================================================

    /**
     * 各失败类型的默认恢复策略链（按优先级递进）。
     *
     * 策略链越靠前表示越优先尝试；当尝试次数增加时，逐步向后推进至更激进的策略。
     * 该映射在构造时初始化且此后不可变，因此无需同步即可安全读取。
     */
    private val defaultStrategyChain: Map<FailureType, List<RecoveryAction>> = mapOf(
        // 元素未找到：先调整参数（滚动查找），再走替代路径，再回滚，最终升级
        FailureType.ELEMENT_NOT_FOUND to listOf(
            RecoveryAction.ADJUST_PARAMETERS,
            RecoveryAction.ALTERNATIVE_PATH,
            RecoveryAction.ROLLBACK_AND_RETRY,
            RecoveryAction.ESCALATE
        ),
        // 超时：先延迟重试，再增加超时时长，再回滚，最终升级
        FailureType.TIMEOUT to listOf(
            RecoveryAction.RETRY_WITH_DELAY,
            RecoveryAction.ADJUST_PARAMETERS,
            RecoveryAction.ROLLBACK_AND_RETRY,
            RecoveryAction.ESCALATE
        ),
        // 权限拒绝：无法自动恢复，直接升级通知用户授权
        FailureType.PERMISSION_DENIED to listOf(
            RecoveryAction.ESCALATE
        ),
        // 应用崩溃：重启应用，再回滚，最终升级
        FailureType.APP_CRASH to listOf(
            RecoveryAction.RESTART_APP,
            RecoveryAction.ROLLBACK_AND_RETRY,
            RecoveryAction.ESCALATE
        ),
        // 网络错误：延迟重试（指数退避），再替代路径，最终升级
        FailureType.NETWORK_ERROR to listOf(
            RecoveryAction.RETRY_WITH_DELAY,
            RecoveryAction.ALTERNATIVE_PATH,
            RecoveryAction.ESCALATE
        ),
        // 意外状态：回滚到上一状态，再替代路径，最终升级
        FailureType.UNEXPECTED_STATE to listOf(
            RecoveryAction.ROLLBACK_AND_RETRY,
            RecoveryAction.ALTERNATIVE_PATH,
            RecoveryAction.ESCALATE
        ),
        // 元素不可点击：调整参数（改用查找点击），再替代路径，再回滚，最终升级
        FailureType.ELEMENT_NOT_CLICKABLE to listOf(
            RecoveryAction.ADJUST_PARAMETERS,
            RecoveryAction.ALTERNATIVE_PATH,
            RecoveryAction.ROLLBACK_AND_RETRY,
            RecoveryAction.ESCALATE
        ),
        // 未知错误：延迟重试，再回滚，最终升级
        FailureType.UNKNOWN to listOf(
            RecoveryAction.RETRY_WITH_DELAY,
            RecoveryAction.ROLLBACK_AND_RETRY,
            RecoveryAction.ESCALATE
        )
    )

    // =========================================================================
    //  核心方法 —— 失败分类
    // =========================================================================

    /**
     * 分析失败结果并构造 [FailureContext]。
     *
     * 结合错误信息关键字匹配与动作类型上下文，将失败归入 [FailureType]，
     * 并打包为完整的失败上下文，供后续恢复决策使用。
     *
     * 分类优先级（高 → 低）：
     * 1. 权限拒绝（避免被其他规则误判，最高优先级）
     * 2. 应用崩溃（崩溃/ANR/强制停止）
     * 3. 网络错误
     * 4. 超时
     * 5. 元素不可点击
     * 6. 意外状态
     * 7. 元素未找到
     * 8. 兜底：未知
     *
     * @param action        执行失败的动作。
     * @param result        动作执行结果（含失败信息）。
     * @param attemptCount  已尝试次数（含本次，从 1 开始）。
     * @param previousActions 失败前已执行的动作序列（用于回滚分析），默认空。
     * @param screenContext   失败时的屏幕上下文描述，默认 null。
     * @return 包含失败类型与上下文的 [FailureContext]。
     */
    fun classifyFailure(
        action: ClawAction,
        result: ClawActionResult,
        attemptCount: Int = 1,
        previousActions: List<ClawAction> = emptyList(),
        screenContext: String? = null
    ): FailureContext {
        val failureType = classifyFailureType(result.message, action)
        val appContext = action.packageName ?: action.name ?: screenContext ?: "unknown"

        val context = FailureContext(
            failureType = failureType,
            action = action,
            result = result,
            attemptCount = attemptCount.coerceAtLeast(1),
            timestamp = System.currentTimeMillis(),
            previousActions = previousActions,
            screenContext = screenContext,
            appContext = appContext
        )

        Log.d(
            TAG, "失败分类: type=${failureType.name}, " +
                    "action=${action.actionName}, " +
                    "attempt=${context.attemptCount}, " +
                    "app=$appContext"
        )
        return context
    }

    /**
     * 根据错误信息与动作类型，将失败原因分类为 [FailureType]。
     *
     * 分类逻辑结合关键字匹配与动作类型上下文：
     * - 权限类错误优先判定，避免被后续规则误归类。
     * - 应用类动作（APP_OPEN / APP_SEARCH 等）的崩溃类信息归为 [FailureType.APP_CRASH]。
     * - 支持中英文关键字混合匹配。
     *
     * @param errorMessage 原始错误信息文本。
     * @param action        失败动作（用于辅助消歧），可为 null。
     * @return 分类后的失败类型；无法匹配时返回 [FailureType.UNKNOWN]。
     */
    private fun classifyFailureType(errorMessage: String, action: ClawAction?): FailureType {
        val msg = errorMessage.lowercase().trim()
        if (msg.isEmpty()) return FailureType.UNKNOWN

        val actionType = action?.type
        val isAppAction = actionType != null && actionType in setOf(
            ActionType.APP_OPEN, ActionType.APP_SEARCH,
            ActionType.APP_INSTALL, ActionType.APP_UNINSTALL
        )

        // 1. 权限拒绝（最高优先级，避免被其他规则误判）
        if (containsAny(
                msg,
                "permission", "权限", "denied", "拒绝",
                "forbidden", "禁止", "unauthorized", "未授权",
                "access denied", "拒绝访问"
            )
        ) {
            return FailureType.PERMISSION_DENIED
        }

        // 2. 应用崩溃（崩溃 / ANR / 强制停止 / 已停止运行）
        if (containsAny(
                msg,
                "crash", "崩溃", "crashed", "stopped", "已停止",
                "unfortunately", "keeps stopping", "已停止运行",
                "anr", "application not responding", "无响应",
                "fatal", "force close", "强制关闭", "force closed"
            )
        ) {
            return FailureType.APP_CRASH
        }

        // 3. 网络错误
        if (containsAny(
                msg,
                "network", "网络", "connection", "连接",
                "socket", "unreachable", "无法访问", "断网",
                "no connectivity", "unknown host", "unknownhost",
                "host unresolved", "eof", "reset by peer",
                "connection reset", "连接重置", "ssl", "握手"
            )
        ) {
            return FailureType.NETWORK_ERROR
        }

        // 4. 超时
        if (containsAny(
                msg,
                "timeout", "超时", "timed out", "time out",
                "deadline", "截止", "expired", "已过期"
            )
        ) {
            return FailureType.TIMEOUT
        }

        // 5. 元素不可点击（元素存在但无法交互）
        if (containsAny(
                msg,
                "not clickable", "不可点击", "not interactable",
                "无法点击", "can't click", "cannot click",
                "not enabled", "未启用", "disabled", "被遮挡",
                "obscured", "covered", "被覆盖"
            )
        ) {
            return FailureType.ELEMENT_NOT_CLICKABLE
        }

        // 6. 意外状态（当前界面与预期不符）
        if (containsAny(
                msg,
                "unexpected", "意外", "unexpected state", "状态异常",
                "wrong state", "错误状态", "invalid state", "无效状态",
                "not expected", "非预期", "页面不对", "wrong page",
                "wrong screen", "页面错误", "弹窗", "dialog", "意外弹窗"
            )
        ) {
            return FailureType.UNEXPECTED_STATE
        }

        // 7. 元素未找到
        if (containsAny(
                msg,
                "not found", "未找到", "找不到", "no such element",
                "element", "元素", "不存在", "stale",
                "no element", "not present", "不在线",
                "not visible", "不可见", "not loaded", "未加载"
            )
        ) {
            return FailureType.ELEMENT_NOT_FOUND
        }

        // 8. 应用未安装类信息（应用动作的「未找到」在崩溃之后判定，此处兜底为元素未找到）
        if (isAppAction && containsAny(msg, "not installed", "未安装", "no such package")) {
            return FailureType.APP_CRASH
        }

        // 9. 兜底：未知错误
        return FailureType.UNKNOWN
    }

    // =========================================================================
    //  核心方法 —— 恢复策略选择
    // =========================================================================

    /**
     * 根据失败上下文选择最佳恢复策略。
     *
     * 选择逻辑综合三方面因素：
     * 1. **默认策略链**：按失败类型取预置策略链，根据尝试次数定位当前应采取的策略。
     * 2. **历史学习**：若该失败类型已有充足样本（≥ [MIN_SAMPLES_FOR_LEARNING]），
     *    首次尝试时优先采用历史最佳策略。
     * 3. **升级保护**：尝试次数超过 [MAX_RECOVERY_ATTEMPTS] 或策略链耗尽时，强制升级。
     *
     * @param context 失败上下文。
     * @return 选定的恢复策略。
     */
    fun selectRecoveryStrategy(context: FailureContext): RecoveryStrategy {
        val failureType = context.failureType
        val chain = defaultStrategyChain[failureType] ?: defaultStrategyChain.getValue(FailureType.UNKNOWN)

        // 升级保护：尝试次数超过上限，强制升级
        if (context.attemptCount > MAX_RECOVERY_ATTEMPTS) {
            return buildStrategy(
                RecoveryAction.ESCALATE,
                delayMs = 0L,
                params = JsonObject(emptyMap()),
                description = "尝试次数(${context.attemptCount})超过上限($MAX_RECOVERY_ATTEMPTS)，升级为人工介入",
                priority = Int.MAX_VALUE
            )
        }

        // 按尝试次数定位策略链索引（尝试 1 -> 索引 0，依此类推，超出则取末位）
        val chainIndex = (context.attemptCount - 1).coerceIn(0, chain.size - 1)
        var chosen = chain[chainIndex]

        // 历史学习：首次尝试时，若有充足样本的历史最佳策略，优先采用
        if (context.attemptCount == 1) {
            val learnedBest = getLearnedBestAction(failureType)
            if (learnedBest != null && learnedBest != RecoveryAction.ESCALATE &&
                learnedBest != RecoveryAction.SKIP_AND_CONTINUE
            ) {
                chosen = learnedBest
            }
        }

        val delayMs = computeDelay(chosen, context.attemptCount)
        val params = buildStrategyParams(chosen, context)
        val description = describeStrategy(chosen, failureType, context.attemptCount)

        Log.d(
            TAG, "选择恢复策略: type=${failureType.name}, action=${chosen.name}, " +
                    "attempt=${context.attemptCount}, delay=${delayMs}ms"
        )

        return buildStrategy(chosen, delayMs, params, description, chainIndex)
    }

    /**
     * 获取某失败类型的历史最佳恢复动作（基于学习）。
     *
     * 在该失败类型的所有恢复动作中，选取成功率最高且样本量 ≥ [MIN_SAMPLES_FOR_LEARNING]
     * 的动作。无足够样本时返回 null。
     *
     * @param failureType 失败类型。
     * @return 历史最佳恢复动作；样本不足时返回 null。
     */
    private fun getLearnedBestAction(failureType: FailureType): RecoveryAction? {
        val inner = recoveryStats[failureType] ?: return null
        var best: RecoveryAction? = null
        var bestRate = -1.0
        for ((action, stats) in inner) {
            if (stats.totalAttempts.get() < MIN_SAMPLES_FOR_LEARNING) continue
            val rate = stats.successRate()
            if (rate > bestRate) {
                bestRate = rate
                best = action
            }
        }
        return best
    }

    // =========================================================================
    //  核心方法 —— 恢复计划生成
    // =========================================================================

    /**
     * 为复杂失败生成多步恢复计划。
     *
     * 将该失败类型的完整默认策略链转化为有序的 [RecoveryStrategy] 列表，并按历史学习
     * 结果重排：成功率高的策略前置（降权成功率极低的策略）。调用方应依次执行各步骤，
     * 一旦某步骤成功即可停止后续步骤；若所有步骤均失败则需升级。
     *
     * @param context 失败上下文。
     * @return 多步恢复计划。
     */
    fun generateRecoveryPlan(context: FailureContext): RecoveryPlan {
        val failureType = context.failureType
        val chain = defaultStrategyChain[failureType] ?: defaultStrategyChain.getValue(FailureType.UNKNOWN)

        // 将策略链转化为策略对象列表
        val steps = chain.mapIndexed { index, action ->
            val delayMs = computeDelay(action, context.attemptCount + index)
            val params = buildStrategyParams(action, context)
            val description = describeStrategy(action, failureType, context.attemptCount + index)
            buildStrategy(action, delayMs, params, description, index)
        }

        // 按历史学习重排：成功率高的前置，样本不足的保持原序
        val reordered = reorderStepsByLearning(failureType, steps)

        val estimatedTimeMs = reordered.sumOf { it.delayMs }
        val description = "针对[${failureType.name}]失败生成${reordered.size}步恢复计划：" +
                reordered.joinToString(" → ") { it.action.name }

        Log.d(
            TAG, "生成恢复计划: type=${failureType.name}, steps=${reordered.size}, " +
                    "estimated=${estimatedTimeMs}ms"
        )

        return RecoveryPlan(
            context = context,
            steps = reordered,
            description = description,
            estimatedTimeMs = estimatedTimeMs
        )
    }

    /**
     * 根据历史学习结果对恢复步骤重排。
     *
     * - 样本充足且成功率高的策略前置。
     * - 成功率低于 [LOW_SUCCESS_THRESHOLD] 的策略后置。
     * - 样本不足的策略保持原相对顺序。
     *
     * @param failureType 失败类型。
     * @param steps       原始步骤列表。
     * @return 重排后的步骤列表。
     */
    private fun reorderStepsByLearning(
        failureType: FailureType,
        steps: List<RecoveryStrategy>
    ): List<RecoveryStrategy> {
        val inner = recoveryStats[failureType] ?: return steps
        // 为每个步骤计算学习得分：高成功率加分、低成功率减分，样本不足得中性分
        val scored = steps.map { step ->
            val stats = inner[step.action]
            val score = when {
                stats == null || stats.totalAttempts.get() < MIN_SAMPLES_FOR_LEARNING -> 0.0
                else -> {
                    val rate = stats.successRate()
                    when {
                        rate >= HIGH_SUCCESS_THRESHOLD -> 1.0
                        rate <= LOW_SUCCESS_THRESHOLD -> -1.0
                        else -> 0.0
                    }
                }
            }
            step to score
        }
        // 稳定排序：先按得分降序，得分相同则保持原 priority 顺序
        return scored.sortedWith(compareByDescending<Pair<RecoveryStrategy, Double>> { it.second }
            .thenBy { it.first.priority })
            .map { it.first }
    }

    // =========================================================================
    //  核心方法 —— 恢复执行
    // =========================================================================

    /**
     * 执行恢复策略，生成可执行的修改后动作。
     *
     * 将抽象的 [RecoveryStrategy] 转化为具体的 [ClawAction]：
     * - [RecoveryAction.RETRY_WITH_DELAY]：返回原动作（延迟由调用方按 [RecoveryStrategy.delayMs] 处理）。
     * - [RecoveryAction.ADJUST_PARAMETERS]：调整动作参数（如改用查找并点击、微调坐标、增加超时）。
     * - [RecoveryAction.ALTERNATIVE_PATH]：构造达成相同目标的替代动作。
     * - [RecoveryAction.ROLLBACK_AND_RETRY]：返回回滚动作（如按返回键回到上一页面）。
     * - [RecoveryAction.RESTART_APP]：返回重启应用动作（APP_OPEN）。
     * - [RecoveryAction.SKIP_AND_CONTINUE]：返回 null（无需执行，跳过该步骤）。
     * - [RecoveryAction.ESCALATE]：返回 null（无需执行，需通知用户），并累加升级计数。
     *
     * @param strategy 恢复策略。
     * @param action   原始失败动作。
     * @return 修改后待执行的动作；跳过或升级时返回 null。
     */
    fun executeRecovery(strategy: RecoveryStrategy, action: ClawAction): ClawAction? {
        Log.d(TAG, "执行恢复: action=${strategy.action.name}, delay=${strategy.delayMs}ms")

        return when (strategy.action) {
            RecoveryAction.RETRY_WITH_DELAY ->
                action.copy(
                    description = "恢复[延迟重试]: ${action.description}"
                )

            RecoveryAction.ADJUST_PARAMETERS ->
                applyParameterAdjustment(action, strategy)

            RecoveryAction.ALTERNATIVE_PATH ->
                buildAlternativeAction(action, strategy)

            RecoveryAction.ROLLBACK_AND_RETRY ->
                buildRollbackAction(action)

            RecoveryAction.RESTART_APP ->
                buildRestartAction(action)

            RecoveryAction.SKIP_AND_CONTINUE -> {
                Log.d(TAG, "跳过失败动作并继续后续任务")
                null
            }

            RecoveryAction.ESCALATE -> {
                totalEscalations.incrementAndGet()
                Log.w(TAG, "升级为人工介入: ${strategy.description}")
                null
            }
        }
    }

    /**
     * 调整动作参数以提升重试成功率。
     *
     * 各失败场景的调整策略：
     * - 元素未找到/不可点击 + 文本点击：升级为 [ActionType.SCREEN_FIND_AND_CLICK]（自动滚动查找）。
     * - 元素不可点击 + 坐标点击：微调坐标偏移（避开遮挡区域）。
     * - 超时 + 含 `ms` 参数：按 [TIMEOUT_GROWTH_FACTOR] 增加超时时长。
     * - 其他：返回原动作。
     *
     * @param action   原始动作。
     * @param strategy 恢复策略（可从中读取调整参数）。
     * @return 调整后的动作。
     */
    private fun applyParameterAdjustment(action: ClawAction, strategy: RecoveryStrategy): ClawAction {
        val actionType = action.type

        // 文本点击 -> 查找并点击（自动滚动查找）
        if (actionType == ActionType.SCREEN_CLICK_TEXT) {
            return action.copy(
                actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
                description = "恢复[调整参数]: 文本点击→查找并点击「${action.text ?: ""}」"
            )
        }

        // 坐标点击 -> 微调坐标偏移（从策略参数读取 dx/dy，默认偏移）
        // 注意：action.x / action.y 为带自定义 getter 的计算属性，需先取局部变量以触发智能转换。
        val origX = action.x
        val origY = action.y
        if (actionType == ActionType.SCREEN_CLICK && origX != null && origY != null) {
            val dx = strategy.params["dx"]?.let { it.jsonPrimitive.intOrNull } ?: COORDINATE_ADJUST_OFFSET
            val dy = strategy.params["dy"]?.let { it.jsonPrimitive.intOrNull } ?: COORDINATE_ADJUST_OFFSET
            val newX = origX + dx
            val newY = origY + dy
            return action.copy(
                params = action.params
                    .withParam("x", JsonPrimitive(newX))
                    .withParam("y", JsonPrimitive(newY)),
                description = "恢复[调整参数]: 坐标微调 ($origX,$origY)→($newX,$newY)"
            )
        }

        // 超时 -> 增加超时时长
        val currentMs = action.ms
        if (currentMs != null && currentMs > 0) {
            val newMs = (currentMs * TIMEOUT_GROWTH_FACTOR)
                .roundToLong()
                .coerceAtLeast(currentMs + 500L)
            return action.copy(
                params = action.params.withParam("ms", JsonPrimitive(newMs)),
                description = "恢复[调整参数]: 超时时长 ${currentMs}ms → ${newMs}ms"
            )
        }

        // 无可调整参数，返回原动作
        return action.copy(description = "恢复[调整参数]: ${action.description}")
    }

    /**
     * 构造达成相同目标的替代动作。
     *
     * 替代路径策略：
     * - 文本点击 / 坐标点击：改为 [ActionType.SCREEN_FIND_AND_CLICK]（滚动查找后点击）。
     * - 应用打开失败：改为 [ActionType.APP_SEARCH] 按名称搜索后打开。
     * - 输入失败：改为先清空再输入。
     * - 其他：返回原动作并附加说明。
     *
     * @param action   原始动作。
     * @param strategy 恢复策略。
     * @return 替代动作。
     */
    private fun buildAlternativeAction(action: ClawAction, strategy: RecoveryStrategy): ClawAction {
        val actionType = action.type

        return when (actionType) {
            // 点击类：改为查找并点击（更鲁棒）
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_CLICK,
            ActionType.SCREEN_LONG_CLICK,
            ActionType.SCREEN_DOUBLE_CLICK -> {
                val text = action.text ?: ""
                if (text.isNotBlank()) {
                    action.copy(
                        actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
                        description = "恢复[替代路径]: 改为查找并点击「$text」"
                    )
                } else {
                    // 无文本，尝试滚动后重试
                    action.copy(
                        actionName = ActionType.SCREEN_SCROLL_TO_TEXT.name,
                        description = "恢复[替代路径]: 滚动查找目标元素"
                    )
                }
            }

            // 应用打开失败：改为按名称搜索
            ActionType.APP_OPEN -> {
                val name = action.name ?: action.packageName ?: ""
                action.copy(
                    actionName = ActionType.APP_SEARCH.name,
                    params = action.params.withParam("name", JsonPrimitive(name)),
                    description = "恢复[替代路径]: 改为搜索应用「$name」"
                )
            }

            // 输入失败：清空后重新输入
            ActionType.SCREEN_INPUT -> {
                action.copy(
                    params = action.params.withParam("clear", JsonPrimitive(true)),
                    description = "恢复[替代路径]: 清空后重新输入「${action.text ?: ""}」"
                )
            }

            // 其他动作：附加替代说明，返回原动作
            else -> action.copy(description = "恢复[替代路径]: ${action.description}")
        }
    }

    /**
     * 构造回滚动作（回到上一状态）。
     *
     * 优先使用 [ActionType.SCREEN_KEY] 按返回键回到上一页面；若失败动作前有已记录的
     * 动作序列，则取最后一步作为回滚目标。
     *
     * @param action 原始失败动作。
     * @return 回滚动作（按返回键）。
     */
    private fun buildRollbackAction(action: ClawAction): ClawAction {
        return ClawAction(
            actionName = ActionType.SCREEN_KEY.name,
            params = JsonObject(mapOf("key" to JsonPrimitive("BACK"))),
            description = "恢复[回滚重试]: 按返回键回到上一状态后重试"
        )
    }

    /**
     * 构造重启应用动作。
     *
     * 优先使用原始动作的包名/名称重新打开应用；无包名信息时返回一个通用的应用打开动作。
     *
     * @param action 原始失败动作。
     * @return 重启应用动作。
     */
    private fun buildRestartAction(action: ClawAction): ClawAction {
        val packageName = action.packageName ?: action.name
        val params = if (packageName != null) {
            JsonObject(mapOf("packageName" to JsonPrimitive(packageName)))
        } else {
            JsonObject(emptyMap())
        }
        return ClawAction(
            actionName = ActionType.APP_OPEN.name,
            params = params,
            description = "恢复[重启应用]: 重新打开应用「${packageName ?: "未知"}」后重试"
        )
    }

    // =========================================================================
    //  核心方法 —— 记录与学习
    // =========================================================================

    /**
     * 记录一次恢复尝试的结果，用于学习与统计。
     *
     * 将尝试结果写入线程安全存储，更新该 (失败类型, 恢复动作) 组合的成功率，
     * 并累加全局统计计数。后续 [selectRecoveryStrategy] 与 [getBestStrategy]
     * 将基于此历史数据做出更优决策。
     *
     * @param failureType 触发恢复的失败类型。
     * @param action      采取的恢复动作。
     * @param success     恢复是否成功。
     * @param durationMs  恢复耗时（毫秒），默认 0。
     * @param details     恢复详情，默认空字符串。
     */
    fun recordRecoveryResult(
        failureType: FailureType,
        action: RecoveryAction,
        success: Boolean,
        durationMs: Long = 0L,
        details: String = ""
    ) {
        // 更新 (失败类型, 恢复动作) 统计
        val inner = recoveryStats.computeIfAbsent(failureType) { ConcurrentHashMap() }
        val stats = inner.computeIfAbsent(action) { StrategyStats() }
        stats.totalAttempts.incrementAndGet()
        if (success) {
            stats.successCount.incrementAndGet()
        }

        // 累加全局计数
        totalRecoveryAttempts.incrementAndGet()
        if (success) {
            totalRecoverySuccess.incrementAndGet()
        }

        // 记录历史
        val id = historyIdCounter.incrementAndGet()
        val attempt = RecoveryAttempt(
            failureType = failureType,
            action = action,
            success = success,
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs,
            details = details
        )
        attemptHistory[id] = attempt

        // 容量淘汰
        evictHistoryIfNeeded()

        Log.d(
            TAG, "记录恢复结果: type=${failureType.name}, action=${action.name}, " +
                    "success=$success"
        )
    }

    /**
     * 获取某失败类型的历史最佳恢复动作。
     *
     * 基于 [recordRecoveryResult] 积累的历史数据，返回成功率最高且样本量
     * ≥ [MIN_SAMPLES_FOR_LEARNING] 的恢复动作。无足够样本时返回默认策略链的首选动作。
     *
     * @param failureType 失败类型。
     * @return 历史最佳恢复动作；样本不足时返回默认首选动作。
     */
    fun getBestStrategy(failureType: FailureType): RecoveryAction {
        // 优先返回学习到的最佳动作
        val learnedBest = getLearnedBestAction(failureType)
        if (learnedBest != null) {
            Log.d(TAG, "历史最佳策略(已学习): type=${failureType.name}, best=${learnedBest.name}")
            return learnedBest
        }

        // 样本不足时返回默认策略链的首选动作
        val chain = defaultStrategyChain[failureType] ?: defaultStrategyChain.getValue(FailureType.UNKNOWN)
        val default = chain.first()
        Log.d(TAG, "历史最佳策略(默认): type=${failureType.name}, best=${default.name}")
        return default
    }

    /**
     * 获取某失败类型的恢复历史统计。
     *
     * @param failureType 失败类型。
     * @return 该失败类型的恢复历史汇总；无记录时返回零值统计。
     */
    fun getRecoveryHistory(failureType: FailureType): RecoveryHistory {
        val inner = recoveryStats[failureType]
        if (inner == null || inner.isEmpty()) {
            return RecoveryHistory(
                failureType = failureType,
                totalAttempts = 0,
                successCount = 0,
                successRate = 0.0,
                actionStats = emptyMap(),
                bestAction = null
            )
        }

        var total = 0
        var success = 0
        val actionStats = LinkedHashMap<RecoveryAction, Pair<Int, Int>>()
        for ((action, stats) in inner) {
            val aTotal = stats.totalAttempts.get()
            val aSuccess = stats.successCount.get()
            actionStats[action] = aTotal to aSuccess
            total += aTotal
            success += aSuccess
        }

        val rate = if (total == 0) 0.0 else success.toDouble() / total
        val best = getLearnedBestAction(failureType)

        return RecoveryHistory(
            failureType = failureType,
            totalAttempts = total,
            successCount = success,
            successRate = rate,
            actionStats = actionStats,
            bestAction = best
        )
    }

    // =========================================================================
    //  核心方法 —— 统计输出
    // =========================================================================

    /**
     * 获取恢复系统的全局统计信息（人类可读字符串）。
     *
     * 包含：累计恢复尝试/成功/升级数，以及各失败类型下各恢复动作的尝试次数、成功次数
     * 与成功率。适用于日志输出与调试。
     *
     * @return 统计信息字符串。
     */
    fun getRecoveryStats(): String {
        val sb = StringBuilder()
        sb.appendLine("===== IntelligentRecoverySystem 恢复统计 =====")
        sb.appendLine()
        sb.appendLine("累计恢复尝试: ${totalRecoveryAttempts.get()}")
        sb.appendLine("累计恢复成功: ${totalRecoverySuccess.get()}")
        sb.appendLine("累计升级次数: ${totalEscalations.get()}")
        val overallRate = if (totalRecoveryAttempts.get() == 0) 0.0
        else totalRecoverySuccess.get().toDouble() / totalRecoveryAttempts.get()
        sb.appendLine("整体成功率: ${"%.1f".format(overallRate * 100)}%")
        sb.appendLine("历史记录: ${attemptHistory.size}/$MAX_HISTORY_RECORDS")
        sb.appendLine()

        sb.appendLine("-- 按失败类型与恢复动作 --")
        for (failureType in FailureType.entries) {
            val history = getRecoveryHistory(failureType)
            if (history.totalAttempts == 0) continue

            sb.append("【${failureType.name}】")
            sb.append(" 尝试=${history.totalAttempts}, 成功=${history.successCount}, ")
            sb.appendLine("成功率=${"%.1f".format(history.successRate * 100)}%")
            if (history.bestAction != null) {
                sb.appendLine("    最佳策略: ${history.bestAction.name}")
            }
            for ((action, pair) in history.actionStats) {
                val (aTotal, aSuccess) = pair
                val aRate = if (aTotal == 0) 0.0 else aSuccess.toDouble() / aTotal
                sb.append("    - ${action.name}: 尝试=$aTotal, 成功=$aSuccess, ")
                sb.appendLine("成功率=${"%.1f".format(aRate * 100)}%")
            }
        }
        sb.appendLine("============================================")
        return sb.toString()
    }

    // =========================================================================
    //  内部辅助方法 —— 策略构造
    // =========================================================================

    /**
     * 构造 [RecoveryStrategy] 实例。
     */
    private fun buildStrategy(
        action: RecoveryAction,
        delayMs: Long,
        params: JsonObject,
        description: String,
        priority: Int
    ): RecoveryStrategy {
        return RecoveryStrategy(
            action = action,
            delayMs = delayMs,
            params = params,
            description = description,
            priority = priority
        )
    }

    /**
     * 根据恢复动作与尝试次数计算延迟时间。
     *
     * - [RecoveryAction.RETRY_WITH_DELAY]：指数退避 `base × multiplier^(n-1)`，受 [MAX_RETRY_DELAY_MS] 限制。
     * - [RecoveryAction.RESTART_APP]：[RESTART_DELAY_MS]，等待应用启动。
     * - [RecoveryAction.ROLLBACK_AND_RETRY]：[ROLLBACK_DELAY_MS]，等待界面回退。
     * - [RecoveryAction.ADJUST_PARAMETERS]：[ADJUST_DELAY_MS]。
     * - [RecoveryAction.ALTERNATIVE_PATH]：[ALTERNATIVE_DELAY_MS]。
     * - [RecoveryAction.SKIP_AND_CONTINUE] / [RecoveryAction.ESCALATE]：0。
     *
     * @param action        恢复动作。
     * @param attemptCount  尝试次数（用于退避计算）。
     * @return 延迟时间（毫秒）。
     */
    private fun computeDelay(action: RecoveryAction, attemptCount: Int): Long {
        val n = attemptCount.coerceAtLeast(1)
        return when (action) {
            RecoveryAction.RETRY_WITH_DELAY -> {
                val raw = (DEFAULT_RETRY_DELAY_MS * RETRY_MULTIPLIER.pow(n - 1)).roundToLong()
                minOf(raw, MAX_RETRY_DELAY_MS)
            }
            RecoveryAction.RESTART_APP -> RESTART_DELAY_MS
            RecoveryAction.ROLLBACK_AND_RETRY -> ROLLBACK_DELAY_MS
            RecoveryAction.ADJUST_PARAMETERS -> ADJUST_DELAY_MS
            RecoveryAction.ALTERNATIVE_PATH -> ALTERNATIVE_DELAY_MS
            RecoveryAction.SKIP_AND_CONTINUE -> 0L
            RecoveryAction.ESCALATE -> 0L
        }
    }

    /**
     * 构造策略参数 [JsonObject]。
     *
     * - [RecoveryAction.ADJUST_PARAMETERS]：附带坐标微调偏移 dx/dy。
     * - 其他动作：空参数对象。
     *
     * @param action  恢复动作。
     * @param context 失败上下文。
     * @return 策略参数。
     */
    private fun buildStrategyParams(action: RecoveryAction, context: FailureContext): JsonObject {
        return when (action) {
            RecoveryAction.ADJUST_PARAMETERS -> JsonObject(
                mapOf(
                    "dx" to JsonPrimitive(COORDINATE_ADJUST_OFFSET),
                    "dy" to JsonPrimitive(COORDINATE_ADJUST_OFFSET)
                )
            )
            else -> JsonObject(emptyMap())
        }
    }

    /**
     * 生成恢复策略的中文说明。
     */
    private fun describeStrategy(
        action: RecoveryAction,
        failureType: FailureType,
        attemptCount: Int
    ): String {
        return "失败[${failureType.name}] 第${attemptCount}次尝试 → 恢复动作: ${action.name}"
    }

    // =========================================================================
    //  内部辅助方法 —— 历史淘汰
    // =========================================================================

    /**
     * 在历史记录超过 [MAX_HISTORY_RECORDS] 时按 FIFO 淘汰最旧记录。
     */
    private fun evictHistoryIfNeeded() {
        while (attemptHistory.size > MAX_HISTORY_RECORDS) {
            val oldestKey = attemptHistory.keys.minOrNull()
            if (oldestKey == null) break
            attemptHistory.remove(oldestKey)
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

    /**
     * 扩展函数：为 [JsonObject] 添加或替换一个键值对，返回新的 [JsonObject]。
     *
     * 由于 [JsonObject] 是不可变的，此方法通过复制底层数据实现「修改」。
     */
    private fun JsonObject.withParam(key: String, value: JsonElement): JsonObject {
        val newMap = this.toMutableMap()
        newMap[key] = value
        return JsonObject(newMap)
    }

    // =========================================================================
    //  重置
    // =========================================================================

    /**
     * 清空所有恢复历史记录与统计计数。
     *
     * 适用于测试或需要清除历史学习的场景。重置后，策略选择将完全依赖默认策略链。
     */
    fun reset() {
        recoveryStats.clear()
        attemptHistory.clear()
        totalRecoveryAttempts.set(0)
        totalRecoverySuccess.set(0)
        totalEscalations.set(0)
        Log.d(TAG, "已清空所有恢复历史与统计")
    }
}
