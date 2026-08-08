package com.mobileclaw.app.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 退避策略类型。
 *
 * 定义重试之间延迟时间的增长方式：
 * - [EXPONENTIAL] 指数退避：每次延迟乘以倍率（如 2.0 → 1s/2s/4s），适合网络抖动等暂时性故障。
 * - [LINEAR] 线性退避：延迟按固定步长递增，适合超时类问题。
 * - [FIXED] 固定延迟：每次重试间隔相同，适合 UI 未就绪等需要稳定等待的场景。
 * - [NONE] 不退避：无延迟，用于不重试的错误类型。
 */
enum class BackoffStrategy {
    EXPONENTIAL,
    LINEAR,
    FIXED,
    NONE
}

/**
 * 错误类型分类。
 *
 * 每种错误类型对应不同的重试策略与最大重试次数，详见 [SmartRetryStrategy] 的配置表。
 */
enum class ErrorType {
    /** 网络错误：连接失败、Socket 异常、DNS 解析失败等。 */
    NETWORK_ERROR,

    /** 元素未找到：屏幕上不存在目标文本或可点击元素。 */
    ELEMENT_NOT_FOUND,

    /** 应用未安装：目标应用包不存在于设备上。 */
    APP_NOT_INSTALLED,

    /** 权限被拒绝：缺少必要权限（如无障碍服务、存储权限等）。 */
    PERMISSION_DENIED,

    /** 超时：动作执行超时，未在规定时间内完成。 */
    TIMEOUT,

    /** UI 未就绪：界面正在加载、动画播放中或尚未渲染完成。 */
    UI_NOT_READY,

    /** 未知错误：无法归类的其他错误。 */
    UNKNOWN
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 重试配置。
 *
 * 描述某类错误的完整重试策略参数，在 [SmartRetryStrategy] 构造时为每种 [ErrorType] 初始化一份。
 *
 * @param maxRetries 最大重试次数（不含首次尝试）。0 表示不重试。
 * @param backoffStrategy 退避策略，决定延迟时间的增长方式。
 * @param initialDelayMs 首次重试的延迟时间（毫秒）。
 * @param maxDelayMs 单次延迟的上限（毫秒），0 表示不设上限。
 * @param multiplier 延迟倍率：
 *                   - 指数退避时为底数（如 2.0 → 1s/2s/4s）；
 *                   - 线性退避时控制步长（step = initialDelayMs × (multiplier - 1)）；
 *                   - 固定/无退避时不使用。
 */
data class RetryConfig(
    val maxRetries: Int,
    val backoffStrategy: BackoffStrategy,
    val initialDelayMs: Long,
    val maxDelayMs: Long,
    val multiplier: Double
)

/**
 * 重试决策。
 *
 * 由 [SmartRetryStrategy.shouldRetry] 返回，指导调用方是否重试以及如何重试。
 *
 * @param shouldRetry 是否应该重试。
 * @param delayMs 重试前的等待时间（毫秒），不应重试时为 0。
 * @param modifiedAction 经过重试策略修改后的动作。
 *                        注意：[SmartRetryStrategy.shouldRetry] 不持有原始动作，
 *                        因此此字段为 null；调用方应额外调用
 *                        [SmartRetryStrategy.applyRetryModification] 获取修改后的动作。
 * @param reason 决策原因的中文说明，便于日志追踪。
 */
data class RetryDecision(
    val shouldRetry: Boolean,
    val delayMs: Long,
    val modifiedAction: ClawAction?,
    val reason: String
)

/**
 * 错误分类结果。
 *
 * 将原始错误信息包装为结构化的分类结果，便于记录与传递。
 * 可通过 [SmartRetryStrategy.classifyDetailed] 获取。
 *
 * @param type 错误类型。
 * @param originalMessage 原始错误信息文本。
 */
data class ErrorClassification(
    val type: ErrorType,
    val originalMessage: String
)

// =============================================================================
//  SmartRetryStrategy —— 智能重试策略
// =============================================================================

/**
 * SmartRetryStrategy —— 智能重试策略
 *
 * 为 MobileClaw 的动作执行引擎提供基于错误分类的智能重试能力。核心功能包括：
 *
 * 1. **错误分类**：根据错误信息和动作类型，将失败原因归入 7 种错误类型
 *    （网络错误、元素未找到、应用未安装、权限拒绝、超时、UI 未就绪、未知）。
 * 2. **差异化重试策略**：为每种错误类型配置不同的退避策略与最大重试次数，
 *    例如网络错误使用指数退避（1s→2s→4s），超时使用线性退避并增加超时时长。
 * 3. **动作参数修改**：在重试时动态修改动作参数以提升成功率，
 *    例如将「点击文本」升级为「查找并点击」（自动滚动查找）。
 * 4. **成功率统计与动态调整**：使用 [ConcurrentHashMap] 线程安全地记录各类错误
 *    的重试成功率，当样本量充足时自动增减最大重试次数——成功率高则多试，
 *    成功率低则少试，从而在「不过度浪费」与「不轻易放弃」之间取得平衡。
 *
 * ### 各错误类型的默认策略
 * | 错误类型           | 退避策略   | 延迟序列        | 最大重试 | 动作修改         |
 * |--------------------|------------|-----------------|----------|------------------|
 * | NETWORK_ERROR      | 指数退避   | 1s → 2s → 4s    | 3        | 无               |
 * | ELEMENT_NOT_FOUND  | 固定延迟   | 800ms           | 2        | 点击文本→查找并点击 |
 * | APP_NOT_INSTALLED  | 不退避     | -               | 0        | 不重试，建议替代  |
 * | PERMISSION_DENIED  | 不退避     | -               | 0        | 不重试，通知用户  |
 * | TIMEOUT            | 线性退避   | 1s → 2s         | 2        | 增加超时时长      |
 * | UI_NOT_READY       | 指数退避   | 1s → 1.5s → 2.25s | 3      | 延长等待时间      |
 * | UNKNOWN            | 固定延迟   | 800ms           | 1        | 无               |
 *
 * ### 典型调用流程
 * ```
 * val strategy = SmartRetryStrategy()
 * val errorType = strategy.classifyError(result.message, action.type)
 * val decision = strategy.shouldRetry(errorType, attemptCount)
 * if (decision.shouldRetry) {
 *     val modifiedAction = strategy.applyRetryModification(action, errorType)
 *     Thread.sleep(decision.delayMs)
 *     execute(modifiedAction)
 * } else {
 *     // 处理最终失败（提示用户 / 建议替代方案等）
 * }
 * // 重试结束后记录结果
 * strategy.recordRetryResult(errorType, retrySucceeded)
 * ```
 */
class SmartRetryStrategy {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 动态调整所需的最小样本数，低于此数时沿用基础配置。 */
        private const val MIN_SAMPLES_FOR_ADJUSTMENT = 10

        /** 高成功率阈值：达到此值时允许额外增加 1 次重试。 */
        private const val HIGH_SUCCESS_RATE_THRESHOLD = 0.6

        /** 低成功率阈值：低于此值时减少 1 次重试。 */
        private const val LOW_SUCCESS_RATE_THRESHOLD = 0.2

        /** 最大重试次数上限（动态增加后不可超过此值）。 */
        private const val MAX_RETRY_CAP = 5

        /** 可重试错误类型的最大重试次数下限（动态减少后不可低于此值）。 */
        private const val MIN_RETRY_FLOOR = 1

        /** 超时重试时，超时参数的增长因子（增加 50%）。 */
        private const val TIMEOUT_GROWTH_FACTOR = 1.5

        /** UI 未就绪重试时，等待时间的增长因子。 */
        private const val UI_WAIT_GROWTH_FACTOR = 1.5
    }

    // =========================================================================
    //  内部数据结构
    // =========================================================================

    /**
     * 单个错误类型的重试统计（线程安全）。
     *
     * @property totalAttempts 重试总次数（含成功与失败）。
     * @property successCount 重试成功次数。
     */
    private class ErrorStats {
        val totalAttempts: AtomicInteger = AtomicInteger(0)
        val successCount: AtomicInteger = AtomicInteger(0)

        /** 成功率，总次数为 0 时返回 0.0。 */
        fun successRate(): Double {
            val total = totalAttempts.get()
            return if (total == 0) 0.0 else successCount.get().toDouble() / total
        }
    }

    // =========================================================================
    //  状态字段
    // =========================================================================

    /**
     * 各错误类型的默认重试配置。
     *
     * 该映射在构造时初始化且此后不可变，因此无需同步即可安全读取。
     */
    private val retryConfigs: Map<ErrorType, RetryConfig> = mapOf(
        // 网络错误：指数退避 1s → 2s → 4s，最多重试 3 次
        ErrorType.NETWORK_ERROR to RetryConfig(
            maxRetries = 3,
            backoffStrategy = BackoffStrategy.EXPONENTIAL,
            initialDelayMs = 1000L,
            maxDelayMs = 4000L,
            multiplier = 2.0
        ),
        // 元素未找到：等待 + 滚动查找 + 重试，最多 2 次
        ErrorType.ELEMENT_NOT_FOUND to RetryConfig(
            maxRetries = 2,
            backoffStrategy = BackoffStrategy.FIXED,
            initialDelayMs = 800L,
            maxDelayMs = 1500L,
            multiplier = 1.0
        ),
        // 应用未安装：不重试，建议替代方案
        ErrorType.APP_NOT_INSTALLED to RetryConfig(
            maxRetries = 0,
            backoffStrategy = BackoffStrategy.NONE,
            initialDelayMs = 0L,
            maxDelayMs = 0L,
            multiplier = 1.0
        ),
        // 权限被拒绝：不重试，通知用户
        ErrorType.PERMISSION_DENIED to RetryConfig(
            maxRetries = 0,
            backoffStrategy = BackoffStrategy.NONE,
            initialDelayMs = 0L,
            maxDelayMs = 0L,
            multiplier = 1.0
        ),
        // 超时：线性退避并增加超时时长，最多 2 次
        ErrorType.TIMEOUT to RetryConfig(
            maxRetries = 2,
            backoffStrategy = BackoffStrategy.LINEAR,
            initialDelayMs = 1000L,
            maxDelayMs = 5000L,
            multiplier = 2.0
        ),
        // UI 未就绪：延长等待后重试，最多 3 次
        ErrorType.UI_NOT_READY to RetryConfig(
            maxRetries = 3,
            backoffStrategy = BackoffStrategy.EXPONENTIAL,
            initialDelayMs = 1000L,
            maxDelayMs = 3000L,
            multiplier = 1.5
        ),
        // 未知错误：单次延迟重试
        ErrorType.UNKNOWN to RetryConfig(
            maxRetries = 1,
            backoffStrategy = BackoffStrategy.FIXED,
            initialDelayMs = 800L,
            maxDelayMs = 1500L,
            multiplier = 1.0
        )
    )

    /**
     * 各错误类型的重试统计，使用 [ConcurrentHashMap] 保证多线程安全。
     *
     * 采用懒初始化：首次记录某错误类型时才创建对应的 [ErrorStats]。
     */
    private val errorStats: ConcurrentHashMap<ErrorType, ErrorStats> = ConcurrentHashMap()

    // =========================================================================
    //  核心方法
    // =========================================================================

    /**
     * 根据错误信息和动作类型，将失败原因分类为 [ErrorType]。
     *
     * 分类逻辑结合关键字匹配与动作类型上下文：
     * - 权限类错误优先判定，避免被后续规则误归类。
     * - 应用类动作（APP_OPEN / APP_SEARCH 等）的「未找到」归为 [ErrorType.APP_NOT_INSTALLED]。
     * - 非应用类动作的「未找到」归为 [ErrorType.ELEMENT_NOT_FOUND]。
     * - 支持中英文关键字混合匹配。
     *
     * @param errorMessage 原始错误信息文本。
     * @param actionType 失败动作的类型，用于辅助消歧（可为 null）。
     * @return 分类后的错误类型；无法匹配时返回 [ErrorType.UNKNOWN]。
     */
    fun classifyError(errorMessage: String, actionType: ActionType?): ErrorType {
        val msg = errorMessage.lowercase().trim()
        if (msg.isEmpty()) return ErrorType.UNKNOWN

        // 1. 权限拒绝（最高优先级，避免被其他规则误判）
        if (containsAny(
                msg,
                "permission", "权限", "denied", "拒绝",
                "forbidden", "禁止", "unauthorized", "未授权",
                "access denied", "拒绝访问"
            )
        ) {
            return ErrorType.PERMISSION_DENIED
        }

        // 2. 应用未安装（结合动作类型判定）
        val isAppAction = actionType != null && actionType in setOf(
            ActionType.APP_OPEN, ActionType.APP_SEARCH,
            ActionType.APP_INSTALL, ActionType.APP_UNINSTALL
        )
        if (isAppAction && containsAny(
                msg,
                "not found", "not installed", "未找到", "未安装",
                "找不到", "no such package", "package not found",
                "不存在", "无法找到", "not exist", "no activity"
            )
        ) {
            return ErrorType.APP_NOT_INSTALLED
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
            return ErrorType.NETWORK_ERROR
        }

        // 4. 超时
        if (containsAny(
                msg,
                "timeout", "超时", "timed out", "time out",
                "deadline", "截止", "expired", "已过期"
            )
        ) {
            return ErrorType.TIMEOUT
        }

        // 5. UI 未就绪
        if (containsAny(
                msg,
                "not ready", "未就绪", "loading", "加载中",
                "still loading", "not visible", "不可见",
                "not loaded", "未加载", "animating", "动画",
                "busy", "繁忙", "not responding", "无响应",
                "not idle", "非空闲"
            )
        ) {
            return ErrorType.UI_NOT_READY
        }

        // 6. 元素未找到
        if (containsAny(
                msg,
                "not found", "未找到", "找不到", "no such element",
                "element", "元素", "不存在", "stale",
                "no element", "not present", "不在线"
            )
        ) {
            return ErrorType.ELEMENT_NOT_FOUND
        }

        // 7. 兜底：未知错误
        return ErrorType.UNKNOWN
    }

    /**
     * 根据错误类型和已尝试次数，判断是否应该重试并计算延迟。
     *
     * 延迟时间由对应错误类型的 [RetryConfig] 和退避策略计算得出。
     * 最大重试次数会根据历史成功率动态调整（参见 [getAdjustedMaxRetries]）。
     *
     * 注意：此方法不持有原始动作，返回的 [RetryDecision.modifiedAction] 为 null。
     * 调用方应在 `shouldRetry == true` 时，额外调用 [applyRetryModification]
     * 获取经过重试策略修改后的动作。
     *
     * @param errorType 错误类型。
     * @param attemptCount 已完成的尝试次数（含首次尝试，从 1 开始）。
     *                     例如：首次失败传 1，第一次重试失败传 2。
     * @return 重试决策，包含是否重试、延迟时间和原因说明。
     */
    fun shouldRetry(errorType: ErrorType, attemptCount: Int): RetryDecision {
        val config = retryConfigs[errorType] ?: retryConfigs.getValue(ErrorType.UNKNOWN)
        val safeAttempt = attemptCount.coerceAtLeast(1)

        // 不可重试的错误类型（基础配置为 0），直接返回不重试
        if (config.maxRetries <= 0) {
            return RetryDecision(
                shouldRetry = false,
                delayMs = 0L,
                modifiedAction = null,
                reason = describeNoRetryReason(errorType)
            )
        }

        val adjustedMax = getAdjustedMaxRetries(errorType)
        // 已完成的重试次数 = 尝试次数 - 1（首次尝试不计为重试）
        val retriesDone = safeAttempt - 1

        if (retriesDone >= adjustedMax) {
            return RetryDecision(
                shouldRetry = false,
                delayMs = 0L,
                modifiedAction = null,
                reason = "已达最大重试次数($adjustedMax)，停止重试"
            )
        }

        // 即将执行第 (retriesDone + 1) 次重试
        val retryNumber = retriesDone + 1
        val delayMs = computeDelay(config, retryNumber)

        return RetryDecision(
            shouldRetry = true,
            delayMs = delayMs,
            modifiedAction = null,
            reason = "允许第${retryNumber}/${adjustedMax}次重试，" +
                    "策略=${config.backoffStrategy}，延迟${delayMs}ms"
        )
    }

    /**
     * 根据错误类型修改动作参数，以提升重试成功率。
     *
     * 各错误类型的修改策略：
     * - [ErrorType.ELEMENT_NOT_FOUND]：将 [ActionType.SCREEN_CLICK_TEXT]
     *   升级为 [ActionType.SCREEN_FIND_AND_CLICK]（自动滚动查找）。
     * - [ErrorType.TIMEOUT]：若动作含 `ms` 参数，按 [TIMEOUT_GROWTH_FACTOR] 增加超时时长。
     * - [ErrorType.UI_NOT_READY]：若为 [ActionType.SCREEN_WAIT]，延长等待时间。
     * - 其他类型：返回原动作（等待延迟由 [RetryDecision.delayMs] 负责）。
     *
     * @param action 原始动作。
     * @param errorType 错误类型。
     * @return 修改后的动作（可能为原对象）。
     */
    fun applyRetryModification(action: ClawAction, errorType: ErrorType): ClawAction {
        return when (errorType) {
            ErrorType.ELEMENT_NOT_FOUND -> modifyForElementNotFound(action)
            ErrorType.TIMEOUT -> modifyForTimeout(action)
            ErrorType.UI_NOT_READY -> modifyForUiNotReady(action)
            // 网络错误、未知错误：通过退避延迟处理，动作本身无需修改
            // 应用未安装、权限拒绝：不重试，无需修改
            ErrorType.NETWORK_ERROR,
            ErrorType.UNKNOWN,
            ErrorType.APP_NOT_INSTALLED,
            ErrorType.PERMISSION_DENIED -> action
        }
    }

    /**
     * 记录单次重试的结果，用于统计成功率并动态调整重试次数。
     *
     * 应在每次重试执行完毕后调用（无论成功与否）。
     * 当累计样本量达到 [MIN_SAMPLES_FOR_ADJUSTMENT] 后，
     * [shouldRetry] 返回的最大重试次数将根据历史成功率自动增减。
     *
     * @param errorType 错误类型。
     * @param success 本次重试是否成功。
     */
    fun recordRetryResult(errorType: ErrorType, success: Boolean) {
        val stats = errorStats.computeIfAbsent(errorType) { ErrorStats() }
        stats.totalAttempts.incrementAndGet()
        if (success) {
            stats.successCount.incrementAndGet()
        }
    }

    /**
     * 获取所有错误类型的重试统计信息，格式化为人类可读的字符串。
     *
     * 包含每个错误类型的：退避策略、基础/调整后最大重试次数、尝试次数、成功次数、成功率。
     * 适用于日志输出与调试。
     *
     * @return 统计信息字符串。
     */
    fun getRetryStats(): String {
        val sb = StringBuilder()
        sb.appendLine("===== SmartRetryStrategy 重试统计 =====")

        sb.appendLine()
        sb.appendLine(
            "动态调整参数：最小样本=$MIN_SAMPLES_FOR_ADJUSTMENT, " +
                    "高成功阈值=$HIGH_SUCCESS_RATE_THRESHOLD, 低成功阈值=$LOW_SUCCESS_RATE_THRESHOLD"
        )
        sb.appendLine()

        // 按错误类型枚举顺序输出，确保结果稳定
        for (errorType in ErrorType.entries) {
            val config = retryConfigs[errorType]
            val stats = errorStats[errorType]
            val adjustedMax = getAdjustedMaxRetries(errorType)

            sb.append("【${errorType.name}】")
            if (config != null) {
                sb.append(" 策略=${config.backoffStrategy}")
                sb.append(" 基础重试=${config.maxRetries}")
                sb.append(" 调整后重试=$adjustedMax")
            }
            if (stats != null) {
                val total = stats.totalAttempts.get()
                val success = stats.successCount.get()
                val rate = stats.successRate()
                sb.append(" | 重试${total}次, 成功${success}次, 成功率=${"%.1f".format(rate * 100)}%")
                if (total < MIN_SAMPLES_FOR_ADJUSTMENT) {
                    sb.append(" (样本不足, 沿用基础配置)")
                }
            } else {
                sb.append(" | 暂无记录")
            }
            sb.appendLine()
        }

        sb.appendLine("======================================")
        return sb.toString()
    }

    // =========================================================================
    //  扩展方法
    // =========================================================================

    /**
     * 将错误信息分类并包装为 [ErrorClassification]，保留原始错误文本。
     *
     * 这是 [classifyError] 的增强版，返回结构化结果便于日志记录与传递。
     *
     * @param errorMessage 原始错误信息文本。
     * @param actionType 失败动作的类型（可为 null）。
     * @return 包含错误类型和原始信息的分类结果。
     */
    fun classifyDetailed(errorMessage: String, actionType: ActionType?): ErrorClassification {
        return ErrorClassification(
            type = classifyError(errorMessage, actionType),
            originalMessage = errorMessage
        )
    }

    /**
     * 重置所有错误类型的重试统计数据。
     *
     * 适用于测试或需要清除历史统计的场景。重置后，最大重试次数将恢复为基础配置值。
     */
    fun resetStats() {
        errorStats.clear()
    }

    // =========================================================================
    //  内部辅助方法
    // =========================================================================

    /**
     * 根据历史成功率动态计算某错误类型的最大重试次数。
     *
     * 调整规则（需样本量 >= [MIN_SAMPLES_FOR_ADJUSTMENT]）：
     * - 成功率 >= [HIGH_SUCCESS_RATE_THRESHOLD]：基础值 + 1（不超过 [MAX_RETRY_CAP]）。
     * - 成功率 <= [LOW_SUCCESS_RATE_THRESHOLD]：基础值 - 1（不低于 [MIN_RETRY_FLOOR]）。
     * - 其他：沿用基础值。
     *
     * 基础配置为 0（不可重试）的错误类型永远返回 0，不受动态调整影响。
     *
     * @param errorType 错误类型。
     * @return 调整后的最大重试次数。
     */
    private fun getAdjustedMaxRetries(errorType: ErrorType): Int {
        val baseConfig = retryConfigs[errorType] ?: return 0
        // 不可重试的错误类型永远不调整
        if (baseConfig.maxRetries <= 0) return 0

        val stats = errorStats[errorType] ?: return baseConfig.maxRetries
        val total = stats.totalAttempts.get()
        if (total < MIN_SAMPLES_FOR_ADJUSTMENT) return baseConfig.maxRetries

        val successRate = stats.successRate()
        return when {
            successRate >= HIGH_SUCCESS_RATE_THRESHOLD ->
                minOf(baseConfig.maxRetries + 1, MAX_RETRY_CAP)
            successRate <= LOW_SUCCESS_RATE_THRESHOLD ->
                maxOf(baseConfig.maxRetries - 1, MIN_RETRY_FLOOR)
            else -> baseConfig.maxRetries
        }
    }

    /**
     * 根据退避策略和重试序号计算延迟时间。
     *
     * - 指数退避：`delay = initial × multiplier^(n-1)`
     * - 线性退避：`delay = initial + (n-1) × step`，其中 `step = initial × (multiplier - 1)`
     * - 固定延迟：`delay = initial`
     * - 不退避：`delay = 0`
     *
     * 计算结果会根据 [RetryConfig.maxDelayMs] 取上限。
     *
     * @param config 重试配置。
     * @param retryNumber 重试序号（从 1 开始，1 = 第一次重试）。
     * @return 延迟时间（毫秒）。
     */
    private fun computeDelay(config: RetryConfig, retryNumber: Int): Long {
        if (config.backoffStrategy == BackoffStrategy.NONE) return 0L

        val n = retryNumber.coerceAtLeast(1)
        val raw: Long = when (config.backoffStrategy) {
            BackoffStrategy.EXPONENTIAL -> {
                val factor = config.multiplier.pow(n - 1)
                (config.initialDelayMs * factor).roundToLong()
            }
            BackoffStrategy.LINEAR -> {
                val step = (config.initialDelayMs * (config.multiplier - 1.0))
                    .roundToLong()
                    .coerceAtLeast(0L)
                config.initialDelayMs + (n - 1) * step
            }
            BackoffStrategy.FIXED -> config.initialDelayMs
            BackoffStrategy.NONE -> 0L
        }

        return if (config.maxDelayMs > 0) min(raw, config.maxDelayMs) else raw
    }

    /**
     * 为「元素未找到」错误修改动作：将文本点击升级为自动滚动查找。
     *
     * [ActionType.SCREEN_CLICK_TEXT] → [ActionType.SCREEN_FIND_AND_CLICK]，
     * 后者会自动滚动屏幕直到目标文本可见再点击，适合元素可能在屏幕外的场景。
     * 坐标点击等无文本动作无法滚动查找，返回原动作。
     */
    private fun modifyForElementNotFound(action: ClawAction): ClawAction {
        return when (action.type) {
            ActionType.SCREEN_CLICK_TEXT -> action.copy(
                actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
                description = "重试[元素未找到]：自动滚动查找并点击「${action.text ?: ""}」"
            )
            // 坐标点击或无文本动作无法滚动查找，返回原动作，依赖退避延迟
            else -> action
        }
    }

    /**
     * 为「超时」错误修改动作：增加超时/等待时长。
     *
     * 若动作含有 `ms` 参数（如 SCREEN_WAIT），按 [TIMEOUT_GROWTH_FACTOR] 增大该值，
     * 且至少增加 500ms，确保下次执行有更充裕的时间完成。
     */
    private fun modifyForTimeout(action: ClawAction): ClawAction {
        val currentMs = action.ms
        return if (currentMs != null && currentMs > 0) {
            val newMs = (currentMs * TIMEOUT_GROWTH_FACTOR)
                .roundToLong()
                .coerceAtLeast(currentMs + 500L) // 至少增加 500ms
            action.copy(
                params = action.params.withParam("ms", JsonPrimitive(newMs)),
                description = "重试[超时]：超时时长 ${currentMs}ms → ${newMs}ms"
            )
        } else {
            // 无超时参数的动作，返回原动作，依赖退避延迟
            action
        }
    }

    /**
     * 为「UI 未就绪」错误修改动作：延长等待时间。
     *
     * 若动作为 [ActionType.SCREEN_WAIT]，按 [UI_WAIT_GROWTH_FACTOR] 延长等待时长。
     * 非等待动作则附加重试标记，实际等待由 [RetryDecision.delayMs] 负责。
     */
    private fun modifyForUiNotReady(action: ClawAction): ClawAction {
        return when (action.type) {
            ActionType.SCREEN_WAIT -> {
                val currentMs = action.ms ?: 1000L
                val newMs = (currentMs * UI_WAIT_GROWTH_FACTOR).roundToLong()
                action.copy(
                    params = action.params.withParam("ms", JsonPrimitive(newMs)),
                    description = "重试[UI未就绪]：等待时间 ${currentMs}ms → ${newMs}ms"
                )
            }
            // 非等待动作：附加重试标记，实际等待由 RetryDecision.delayMs 负责
            else -> action.copy(
                description = "重试[UI未就绪]：${action.description}"
            )
        }
    }

    /**
     * 为不可重试的错误类型生成原因说明。
     */
    private fun describeNoRetryReason(errorType: ErrorType): String {
        return when (errorType) {
            ErrorType.APP_NOT_INSTALLED -> "应用未安装，不重试，建议提供替代方案"
            ErrorType.PERMISSION_DENIED -> "权限被拒绝，不重试，需通知用户授权"
            else -> "该错误类型不可重试"
        }
    }

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
}