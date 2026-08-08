package com.mobileclaw.app.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 反馈级别。
 *
 * 用于标识单条反馈消息的重要程度，数值越大优先级越高。
 * UI 层可据此决定提示样式（颜色、是否置顶、是否震动等）。
 *
 * @property displayName 中文显示名
 * @property priority    优先级（1-5，越大越重要）
 */
enum class FeedbackLevel(val displayName: String, val priority: Int) {
    /** 信息：常规进度或状态提示，无需用户特别关注。 */
    INFO("信息", 1),

    /** 成功：动作执行成功，给予正向反馈。 */
    SUCCESS("成功", 2),

    /** 警告：潜在问题，建议用户留意但不阻塞操作。 */
    WARNING("警告", 3),

    /** 错误：动作执行失败，需要用户知晓并可能介入。 */
    ERROR("错误", 4),

    /** 严重：致命错误，操作无法继续，需立即处理。 */
    CRITICAL("严重", 5)
}

/**
 * 图标类型。
 *
 * 为不同动作与状态推荐对应的视觉图标，UI 层据此选择图标资源。
 *
 * @property description 中文描述
 */
enum class IconType(val description: String) {
    /** 完成对勾。 */
    CHECK("完成"),

    /** 加载中（转圈）。 */
    LOADING("加载中"),

    /** 警告标识。 */
    WARNING("警告"),

    /** 错误叉号。 */
    ERROR("错误"),

    /** 搜索放大镜。 */
    SEARCH("搜索"),

    /** 发送 / 提交。 */
    SEND("发送"),

    /** 打开应用。 */
    OPEN("打开"),

    /** 关闭 / 退出。 */
    CLOSE("关闭"),

    /** 等待 / 沙漏。 */
    WAIT("等待"),

    /** 设置齿轮。 */
    SETTINGS("设置")
}

/**
 * 颜色主题。
 *
 * 为视觉提示推荐主色调，附带十六进制色值便于 UI 层直接使用。
 *
 * @property hexColor    十六进制颜色值
 * @property displayName 中文显示名
 */
enum class ColorTheme(val hexColor: String, val displayName: String) {
    /** 蓝色：信息、进行中。 */
    BLUE("#2196F3", "蓝色"),

    /** 绿色：成功、完成。 */
    GREEN("#4CAF50", "绿色"),

    /** 橙色：警告、注意。 */
    ORANGE("#FF9800", "橙色"),

    /** 红色：错误、失败。 */
    RED("#F44336", "红色"),

    /** 灰色：中性、禁用。 */
    GRAY("#9E9E9E", "灰色")
}

/**
 * 动画类型。
 *
 * 为视觉提示推荐动画效果与默认时长。
 *
 * @property defaultDurationMs 默认动画时长（毫秒），0 表示无动画
 */
enum class AnimationType(val defaultDurationMs: Long) {
    /** 脉冲：缩放呼吸效果，用于轻量正向提示。 */
    PULSE(300L),

    /** 旋转：持续转圈，用于加载中。 */
    SPIN(1000L),

    /** 抖动：左右晃动，用于错误反馈。 */
    SHAKE(500L),

    /** 淡入淡出。 */
    FADE(300L),

    /** 滑入滑出。 */
    SLIDE(400L),

    /** 无动画。 */
    NONE(0L)
}

/**
 * 用户情绪状态。
 *
 * 由 [MultiModalFeedback.detectMood] 根据用户近期行为推断，
 * 用于调整反馈语气与详略程度。
 *
 * @property displayName 中文显示名
 */
enum class MoodState(val displayName: String) {
    /** 平静：无明显情绪倾向。 */
    NEUTRAL("平静"),

    /** 满意：操作顺利，情绪积极。 */
    SATISFIED("满意"),

    /** 沮丧：反复失败，情绪消极，需安抚。 */
    FRUSTRATED("沮丧"),

    /** 困惑：操作不顺利但未达沮丧，需更多解释。 */
    CONFUSED("困惑"),

    /** 兴奋：快速重试、活跃度高，情绪积极。 */
    EXCITED("兴奋")
}

/**
 * 用户专业水平（决定反馈详略）。
 *
 * - [BEGINNER] 初学者：提供详细说明与操作指引；
 * - [INTERMEDIATE] 进阶用户：提供标准反馈；
 * - [EXPERT] 专家：提供简洁反馈，省略冗余解释。
 *
 * @property displayName 中文显示名
 * @property detailLevel 对应的详略风格描述
 */
enum class ExpertiseLevel(val displayName: String, val detailLevel: String) {
    /** 初学者：详细反馈，含完整说明与建议。 */
    BEGINNER("初学者", "详细"),

    /** 进阶用户：标准反馈。 */
    INTERMEDIATE("进阶用户", "标准"),

    /** 专家：简洁反馈，仅保留关键信息。 */
    EXPERT("专家", "简洁")
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 进度更新。
 *
 * 由 [MultiModalFeedback.createProgressUpdate] 生成，描述任务执行的实时进度。
 *
 * @param step          当前步骤序号（从 1 开始）
 * @param totalSteps    总步骤数
 * @param description   人类可读的步骤描述
 * @param percentage    完成百分比（0-100）
 * @param etaMs         预计剩余时间（毫秒），0 表示无法估算或已完成
 * @param currentAction 当前正在执行的动作名称
 */
data class ProgressUpdate(
    val step: Int,
    val totalSteps: Int,
    val description: String,
    val percentage: Int,
    val etaMs: Long,
    val currentAction: String
)

/**
 * 视觉提示。
 *
 * 由 [MultiModalFeedback.getVisualHint] 生成，为动作结果推荐图标、颜色与动画。
 *
 * @param iconType      图标类型
 * @param color         主色调
 * @param animationType 动画类型
 * @param duration      动画时长（毫秒）
 */
data class VisualHint(
    val iconType: IconType,
    val color: ColorTheme,
    val animationType: AnimationType,
    val duration: Long
)

/**
 * 反馈消息。
 *
 * 由 [MultiModalFeedback.adaptFeedback] 调整后输出，承载面向用户的文本反馈。
 *
 * @param level        反馈级别
 * @param title        标题（简短概括）
 * @param message      正文内容
 * @param suggestion   可选的操作建议，null 表示无建议
 * @param isActionable 是否可操作（即用户可据此采取行动）
 */
data class FeedbackMessage(
    val level: FeedbackLevel,
    val title: String,
    val message: String,
    val suggestion: String?,
    val isActionable: Boolean
)

/**
 * 任务完成摘要。
 *
 * 由 [MultiModalFeedback.generateCompletionSummary] 生成，总结任务执行情况并给出后续建议。
 *
 * @param title           摘要标题
 * @param duration        任务总耗时（毫秒）
 * @param stepsCompleted  完成的步骤数
 * @param successRate     成功率（0-100，基于历史任务统计）
 * @param insights        洞察列表（对本次执行的分析）
 * @param nextSuggestions 后续建议列表
 */
data class CompletionSummary(
    val title: String,
    val duration: Long,
    val stepsCompleted: Int,
    val successRate: Double,
    val insights: List<String>,
    val nextSuggestions: List<String>
)

/**
 * 错误解释。
 *
 * 由 [MultiModalFeedback.explainError] 生成，将技术错误翻译为用户友好的说明。
 *
 * @param technicalError   原始技术错误信息
 * @param userMessage      面向用户的友好说明
 * @param possibleCauses   可能的原因列表
 * @param suggestedActions 建议的操作列表
 * @param severity         严重程度（反馈级别）
 */
data class ErrorExplanation(
    val technicalError: String,
    val userMessage: String,
    val possibleCauses: List<String>,
    val suggestedActions: List<String>,
    val severity: FeedbackLevel
)

/**
 * 用户情绪。
 *
 * 由 [MultiModalFeedback.detectMood] 生成，描述推断出的情绪状态及依据。
 *
 * @param state      情绪状态
 * @param confidence 置信度（0-1，越大越确信）
 * @param evidence   推断依据列表（人类可读）
 */
data class UserMood(
    val state: MoodState,
    val confidence: Double,
    val evidence: List<String>
)

// =============================================================================
//  MultiModalFeedback —— 多模态反馈系统
// =============================================================================

/**
 * MultiModalFeedback —— 多模态反馈系统
 *
 * 为 MobileClaw 提供富媒体、多格式的用户反馈能力。在任务执行的全生命周期
 * （进行中、完成、失败）中，向用户传递清晰、恰当、有温度的反馈信息。
 *
 * 六大反馈能力：
 * 1. **进度反馈**：[createProgressUpdate] 实时计算完成百分比、预计剩余时间（ETA）
 *    与步骤描述，让用户随时掌握任务进展。
 * 2. **视觉反馈**：[getVisualHint] 根据动作类型与成败，推荐图标、颜色与动画，
 *    帮助 UI 层呈现直观的视觉指示。
 * 3. **上下文反馈**：[adaptFeedback] 结合用户专业水平（初学者 / 进阶 / 专家）与
 *    当前情绪，动态调整反馈的详略与语气——初学者得详细，专家得简洁。
 * 4. **情绪反馈**：[detectMood] 根据近期失败次数、重复指令与快速重试行为，
 *    推断用户情绪（沮丧 / 困惑 / 兴奋等），并据此调整反馈语气。
 * 5. **完成反馈**：[generateCompletionSummary] 生成任务完成摘要，包含耗时、
 *    步骤数、成功率、洞察与后续建议。
 * 6. **错误解释**：[explainError] 将技术错误翻译为用户友好的中文说明，
 *    给出可能原因与建议操作。
 *
 * ### 沮丧检测规则
 * - 近 2 分钟内失败 **3 次及以上** → 判定为沮丧；
 * - 同一指令重复执行 **2 次及以上** → 判定为沮丧；
 * - 满足任一条件即触发沮丧情绪，并提高置信度。
 *
 * ### 详略适配规则
 * | 专业水平     | 详略风格 | 处理方式                       |
 * |--------------|----------|--------------------------------|
 * | BEGINNER     | 详细     | 补充说明与操作建议，确保可操作 |
 * | INTERMEDIATE | 标准     | 原样保留                       |
 * | EXPERT       | 简洁     | 截断冗长文本，仅保留关键信息   |
 *
 * ### 典型调用流程
 * ```
 * val feedback = MultiModalFeedback()
 *
 * // 1. 进度反馈
 * val progress = feedback.createProgressUpdate(2, 5, "点击登录按钮", startTime)
 *
 * // 2. 视觉反馈
 * val hint = feedback.getVisualHint(ActionType.APP_OPEN, success = true)
 *
 * // 3. 情绪检测 + 上下文适配
 * val mood = feedback.detectMood(recentFailures = 4, repeatedCommands = 2, rapidRetries = 1)
 * val adapted = feedback.adaptFeedback(rawMessage, ExpertiseLevel.BEGINNER, mood)
 *
 * // 4. 错误解释
 * val explanation = feedback.explainError("connection timeout", ActionType.APP_OPEN, "com.tencent.mm")
 *
 * // 5. 完成摘要
 * val summary = feedback.generateCompletionSummary("打开微信并发送消息", 12000, 3, true, listOf("网络稳定"))
 *
 * // 6. 统计
 * println(feedback.getFeedbackStats())
 * ```
 *
 * 线程安全：统计计数使用 [AtomicInteger]，情绪分布使用 [ConcurrentHashMap]。
 */
class MultiModalFeedback {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 沮丧检测：近 2 分钟内失败次数阈值。 */
        private const val FRUSTRATION_FAILURE_THRESHOLD = 3

        /** 沮丧检测：同一指令重复执行次数阈值。 */
        private const val FRUSTRATION_REPEAT_THRESHOLD = 2

        /** 沮丧检测的时间窗口（毫秒，2 分钟）。 */
        private const val FRUSTRATION_WINDOW_MS = 2 * 60 * 1000L

        /** 兴奋检测：快速重试次数阈值。 */
        private const val EXCITEMENT_RETRY_THRESHOLD = 3

        /** 进度百分比上限。 */
        private const val MAX_PERCENTAGE = 100

        /** 专家模式反馈正文最大长度（超出截断）。 */
        private const val EXPERT_MESSAGE_MAX_LENGTH = 80

        /** 成功视觉提示默认动画时长（毫秒）。 */
        private const val SUCCESS_ANIMATION_DURATION = 300L

        /** 失败视觉提示默认动画时长（毫秒）。 */
        private const val FAILURE_ANIMATION_DURATION = 500L

        /** 视为「耗时较长」的任务时长阈值（毫秒）。 */
        private const val LONG_TASK_THRESHOLD_MS = 30_000L

        /** 视为「执行迅速」的任务时长上限（毫秒）。 */
        private const val FAST_TASK_THRESHOLD_MS = 5_000L

        /** 视为「步骤较多」的步骤数阈值。 */
        private const val MANY_STEPS_THRESHOLD = 5
    }

    // =========================================================================
    //  统计字段（线程安全）
    // =========================================================================

    /** 已生成的进度更新数。 */
    private val progressUpdatesCreated = AtomicInteger(0)

    /** 已生成的视觉提示数。 */
    private val visualHintsGenerated = AtomicInteger(0)

    /** 已适配的反馈消息数。 */
    private val feedbackMessagesAdapted = AtomicInteger(0)

    /** 已检测的情绪数。 */
    private val moodsDetected = AtomicInteger(0)

    /** 情绪分布统计（情绪状态 -> 出现次数）。 */
    private val moodDistribution = ConcurrentHashMap<MoodState, AtomicInteger>()

    /** 已生成的完成摘要数。 */
    private val completionSummariesGenerated = AtomicInteger(0)

    /** 已完成的任务数（含成功与失败）。 */
    private val tasksCompleted = AtomicInteger(0)

    /** 成功完成的任务数。 */
    private val tasksSucceeded = AtomicInteger(0)

    /** 已解释的错误数。 */
    private val errorsExplained = AtomicInteger(0)

    // =========================================================================
    //  内部类型
    // =========================================================================

    /** 错误分类（内部使用）。 */
    private enum class ErrorCategory {
        /** 网络错误。 */
        NETWORK,

        /** 元素未找到。 */
        ELEMENT_NOT_FOUND,

        /** 应用未安装。 */
        APP_NOT_INSTALLED,

        /** 权限被拒绝。 */
        PERMISSION,

        /** 超时。 */
        TIMEOUT,

        /** 界面未就绪。 */
        UI_NOT_READY,

        /** 未知错误。 */
        UNKNOWN
    }

    /** 错误解释模板（内部使用）。 */
    private data class ErrorTemplate(
        val userMessage: String,
        val possibleCauses: List<String>,
        val suggestedActions: List<String>,
        val severity: FeedbackLevel
    )

    // =========================================================================
    //  公共方法：进度反馈
    // =========================================================================

    /**
     * 创建进度更新。
     *
     * 根据当前步骤、总步骤数与起始时间，计算完成百分比与预计剩余时间（ETA）。
     * ETA 采用线性外推：`剩余时间 = 已用时间 × 剩余步骤数 / 已完成步骤数`。
     *
     * 百分比采用「当前步骤 / 总步骤数」的口径，因此执行到最后一步时为 100%。
     * 当已完成步骤数为 0 或已达到总步骤数时，ETA 返回 0（无法估算或已近完成）。
     *
     * @param currentStep 当前步骤序号（从 1 开始）
     * @param totalSteps  总步骤数（至少为 1）
     * @param action      当前执行的动作描述
     * @param startTime   任务开始时间戳（毫秒）
     * @return 进度更新对象
     */
    fun createProgressUpdate(
        currentStep: Int,
        totalSteps: Int,
        action: String,
        startTime: Long
    ): ProgressUpdate {
        progressUpdatesCreated.incrementAndGet()

        val safeTotal = totalSteps.coerceAtLeast(1)
        val safeStep = currentStep.coerceIn(1, safeTotal)

        val percentage = (safeStep.toFloat() / safeTotal.toFloat() * MAX_PERCENTAGE)
            .roundToInt()
            .coerceIn(0, MAX_PERCENTAGE)

        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(0L)
        val etaMs = if (safeStep in 1 until safeTotal && elapsed > 0L) {
            (elapsed * (safeTotal - safeStep) / safeStep).coerceAtLeast(0L)
        } else {
            0L
        }

        val description = "正在执行第 ${safeStep}/${safeTotal} 步（${percentage}%）：${action}"

        return ProgressUpdate(
            step = safeStep,
            totalSteps = safeTotal,
            description = description,
            percentage = percentage,
            etaMs = etaMs,
            currentAction = action
        )
    }

    // =========================================================================
    //  公共方法：视觉反馈
    // =========================================================================

    /**
     * 获取视觉提示。
     *
     * 根据动作类型与执行结果，推荐合适的图标、颜色与动画：
     * - 失败：统一使用错误图标、红色与抖动动画，强调需关注；
     * - 成功：颜色为绿色，动画为脉冲或淡出等轻量效果，图标随动作类型变化。
     *
     * @param actionType 动作类型
     * @param success    动作是否执行成功
     * @return 视觉提示对象
     */
    fun getVisualHint(actionType: ActionType, success: Boolean): VisualHint {
        visualHintsGenerated.incrementAndGet()

        // 失败：统一错误提示
        if (!success) {
            return VisualHint(
                iconType = IconType.ERROR,
                color = ColorTheme.RED,
                animationType = AnimationType.SHAKE,
                duration = FAILURE_ANIMATION_DURATION
            )
        }

        // 成功：根据动作类型选择图标与动画
        val (icon, animation) = successHintForAction(actionType)
        return VisualHint(
            iconType = icon,
            color = ColorTheme.GREEN,
            animationType = animation,
            duration = if (animation == AnimationType.NONE) 0L else SUCCESS_ANIMATION_DURATION
        )
    }

    // =========================================================================
    //  公共方法：上下文反馈
    // =========================================================================

    /**
     * 适配反馈消息。
     *
     * 根据用户专业水平调整反馈详略，并根据当前情绪调整语气：
     * - 初学者：补充说明，确保存在操作建议并标记为可操作；
     * - 专家：截断冗长正文，仅保留关键信息；
     * - 沮丧：安抚语气、补充致歉与安抚建议，强调可操作性；
     * - 困惑：补充解释性提示。
     *
     * @param message   原始反馈消息
     * @param userLevel 用户专业水平
     * @param mood      当前用户情绪
     * @return 适配后的反馈消息
     */
    fun adaptFeedback(
        message: FeedbackMessage,
        userLevel: ExpertiseLevel,
        mood: UserMood
    ): FeedbackMessage {
        feedbackMessagesAdapted.incrementAndGet()

        // 1. 按专业水平调整详略
        var adjustedTitle = message.title
        var adjustedMessage = message.message
        var adjustedSuggestion = message.suggestion
        var adjustedActionable = message.isActionable

        when (userLevel) {
            ExpertiseLevel.BEGINNER -> {
                // 初学者：补充说明，确保存在建议
                if (adjustedSuggestion == null) {
                    adjustedSuggestion = "建议逐步操作，遇到问题随时反馈"
                }
                adjustedMessage = buildString {
                    append(adjustedMessage)
                    append("\n建议：")
                    append(adjustedSuggestion)
                }
                adjustedActionable = true
            }
            ExpertiseLevel.EXPERT -> {
                // 专家：截断冗长正文
                if (adjustedMessage.length > EXPERT_MESSAGE_MAX_LENGTH) {
                    adjustedMessage = adjustedMessage.take(EXPERT_MESSAGE_MAX_LENGTH - 1) + "…"
                }
            }
            ExpertiseLevel.INTERMEDIATE -> {
                // 进阶用户：原样保留
            }
        }

        // 2. 按情绪调整语气
        when (mood.state) {
            MoodState.FRUSTRATED -> {
                adjustedTitle = "别着急，$adjustedTitle"
                if (message.level == FeedbackLevel.ERROR || message.level == FeedbackLevel.WARNING) {
                    adjustedMessage = "抱歉遇到了一些问题。$adjustedMessage"
                }
                adjustedActionable = true
                if (adjustedSuggestion == null) {
                    adjustedSuggestion = "可以先稍作休息，再尝试其他方式"
                }
            }
            MoodState.CONFUSED -> {
                adjustedMessage = "$adjustedMessage\n（若不清楚如何操作，可参考上方建议）"
            }
            MoodState.SATISFIED,
            MoodState.EXCITED -> {
                // 积极情绪：保持正向，不额外干预
            }
            MoodState.NEUTRAL -> {
                // 平静：不调整
            }
        }

        return FeedbackMessage(
            level = message.level,
            title = adjustedTitle,
            message = adjustedMessage,
            suggestion = adjustedSuggestion,
            isActionable = adjustedActionable
        )
    }

    // =========================================================================
    //  公共方法：情绪反馈
    // =========================================================================

    /**
     * 检测用户情绪。
     *
     * 基于近期行为信号推断情绪状态：
     * - 近 2 分钟内失败 [FRUSTRATION_FAILURE_THRESHOLD] 次及以上，或同一指令重复
     *   [FRUSTRATION_REPEAT_THRESHOLD] 次及以上 → 沮丧；
     * - 存在少量失败但未达沮丧阈值 → 困惑；
     * - 快速重试 [EXCITEMENT_RETRY_THRESHOLD] 次及以上且无失败 → 兴奋；
     * - 无失败且无重复 → 满意 / 平静。
     *
     * 置信度随触发信号强度递增，沮丧情绪的置信度最低为 0.55。
     *
     * @param recentFailures   近 2 分钟内的失败次数
     * @param repeatedCommands 同一指令的重复执行次数
     * @param rapidRetries     快速连续重试次数
     * @return 推断出的用户情绪（含置信度与依据）
     */
    fun detectMood(
        recentFailures: Int,
        repeatedCommands: Int,
        rapidRetries: Int
    ): UserMood {
        moodsDetected.incrementAndGet()

        val evidence = mutableListOf<String>()
        var frustrated = false

        if (recentFailures >= FRUSTRATION_FAILURE_THRESHOLD) {
            frustrated = true
            evidence.add("近${FRUSTRATION_WINDOW_MS / 60000}分钟内失败${recentFailures}次（≥${FRUSTRATION_FAILURE_THRESHOLD}）")
        }
        if (repeatedCommands >= FRUSTRATION_REPEAT_THRESHOLD) {
            frustrated = true
            evidence.add("同一指令重复${repeatedCommands}次（≥${FRUSTRATION_REPEAT_THRESHOLD}）")
        }

        val state: MoodState
        val confidence: Double

        if (frustrated) {
            state = MoodState.FRUSTRATED
            confidence = min(1.0, 0.55 + 0.12 * (recentFailures + repeatedCommands))
        } else if (recentFailures >= 1) {
            evidence.add("近期失败${recentFailures}次，可能存在困惑")
            state = MoodState.CONFUSED
            confidence = 0.6
        } else if (rapidRetries >= EXCITEMENT_RETRY_THRESHOLD) {
            evidence.add("快速重试${rapidRetries}次，表现积极")
            state = MoodState.EXCITED
            confidence = 0.65
        } else if (rapidRetries >= 1) {
            evidence.add("少量快速重试，状态平稳")
            state = MoodState.NEUTRAL
            confidence = 0.5
        } else {
            evidence.add("无失败与重复，操作顺畅")
            state = MoodState.SATISFIED
            confidence = 0.6
        }

        moodDistribution.computeIfAbsent(state) { AtomicInteger(0) }.incrementAndGet()

        return UserMood(
            state = state,
            confidence = confidence,
            evidence = evidence
        )
    }

    // =========================================================================
    //  公共方法：完成反馈
    // =========================================================================

    /**
     * 生成任务完成摘要。
     *
     * 汇总任务执行情况，更新历史成功率统计，并生成洞察与后续建议。
     * 成功率基于历史完成任务统计（成功数 / 总数 × 100），随任务积累反映整体可靠性。
     *
     * 洞察会结合耗时、步骤数与成败自动补充，例如：
     * - 耗时过长 → 提示优化执行流程；
     * - 步骤较多 → 提示拆分子任务；
     * - 未成功 → 提示检查失败环节。
     *
     * @param taskDescription 任务描述
     * @param duration        任务总耗时（毫秒）
     * @param steps           完成的步骤数
     * @param success         任务是否成功完成
     * @param insights        额外洞察列表
     * @return 任务完成摘要
     */
    fun generateCompletionSummary(
        taskDescription: String,
        duration: Long,
        steps: Int,
        success: Boolean,
        insights: List<String>
    ): CompletionSummary {
        completionSummariesGenerated.incrementAndGet()

        // 更新历史任务统计
        val totalTasks = tasksCompleted.incrementAndGet()
        if (success) tasksSucceeded.incrementAndGet()
        val successRate = if (totalTasks > 0) {
            tasksSucceeded.get().toDouble() / totalTasks * MAX_PERCENTAGE
        } else {
            0.0
        }

        val title = if (success) {
            "任务完成：${taskDescription}"
        } else {
            "任务未完成：${taskDescription}"
        }

        // 生成洞察（在传入洞察基础上自动补充）
        val allInsights = mutableListOf<String>()
        allInsights.addAll(insights)
        if (duration > LONG_TASK_THRESHOLD_MS) {
            allInsights.add("任务耗时较长（${duration / 1000}秒），可考虑优化执行流程")
        } else if (success && duration in 1L..FAST_TASK_THRESHOLD_MS) {
            allInsights.add("任务执行迅速（${duration / 1000}秒），效率良好")
        }
        if (!success) {
            allInsights.add("部分步骤未成功完成，建议检查失败环节")
        }
        if (steps > MANY_STEPS_THRESHOLD) {
            allInsights.add("本次任务步骤较多（${steps}步），可考虑拆分为更小的子任务")
        }

        // 生成后续建议
        val nextSuggestions = generateNextSuggestions(success, duration)

        return CompletionSummary(
            title = title,
            duration = duration,
            stepsCompleted = steps,
            successRate = successRate,
            insights = allInsights,
            nextSuggestions = nextSuggestions
        )
    }

    // =========================================================================
    //  公共方法：错误解释
    // =========================================================================

    /**
     * 解释技术错误。
     *
     * 将原始技术错误信息翻译为用户友好的中文说明，结合动作类型与应用上下文，
     * 给出可能原因与建议操作，并标注严重程度。
     *
     * 分类逻辑结合关键字匹配与动作类型上下文：
     * - 权限类错误优先判定，避免被后续规则误归类；
     * - 应用类动作（APP_OPEN / APP_SEARCH 等）的「未找到」归为应用未安装；
     * - 非应用类动作的「未找到」归为元素未找到；
     * - 支持中英文关键字混合匹配。
     *
     * @param technicalError 原始技术错误信息
     * @param actionType     出错的动作类型（可为 null）
     * @param appContext     应用上下文（包名或应用名，可为 null）
     * @return 错误解释对象
     */
    fun explainError(
        technicalError: String,
        actionType: ActionType?,
        appContext: String?
    ): ErrorExplanation {
        errorsExplained.incrementAndGet()

        val category = classifyError(technicalError, actionType)
        val appRef = shortAppName(appContext)

        val template = buildExplanation(category, appRef)

        return ErrorExplanation(
            technicalError = technicalError,
            userMessage = template.userMessage,
            possibleCauses = template.possibleCauses,
            suggestedActions = template.suggestedActions,
            severity = template.severity
        )
    }

    // =========================================================================
    //  公共方法：统计
    // =========================================================================

    /**
     * 获取反馈系统统计信息。
     *
     * 汇总各类反馈的生成次数、任务成功率与情绪分布，用于日志输出与调试。
     *
     * @return 格式化的统计信息字符串
     */
    fun getFeedbackStats(): String {
        val totalTasks = tasksCompleted.get()
        val succeeded = tasksSucceeded.get()
        val rate = if (totalTasks > 0) {
            succeeded.toDouble() / totalTasks * MAX_PERCENTAGE
        } else {
            0.0
        }

        return buildString {
            appendLine("===== MultiModalFeedback 反馈统计 =====")
            appendLine("进度更新生成数: ${progressUpdatesCreated.get()}")
            appendLine("视觉提示生成数: ${visualHintsGenerated.get()}")
            appendLine("反馈消息适配数: ${feedbackMessagesAdapted.get()}")
            appendLine("情绪检测次数:   ${moodsDetected.get()}")
            appendLine("完成摘要生成数: ${completionSummariesGenerated.get()}")
            appendLine("错误解释生成数: ${errorsExplained.get()}")
            appendLine()
            appendLine("任务统计: 完成${totalTasks}次, 成功${succeeded}次, 成功率=${"%.1f".format(rate)}%")
            appendLine()

            appendLine("情绪分布:")
            if (moodDistribution.isEmpty()) {
                appendLine("  暂无数据")
            } else {
                MoodState.entries.forEach { state ->
                    val count = moodDistribution[state]?.get() ?: 0
                    appendLine("  ${state.displayName}($state): $count 次")
                }
            }
            appendLine("========================================")
        }
    }

    /**
     * 重置所有统计数据。
     *
     * 适用于测试或需要清除历史统计的场景。重置后任务成功率将重新从零累计。
     */
    fun resetStats() {
        progressUpdatesCreated.set(0)
        visualHintsGenerated.set(0)
        feedbackMessagesAdapted.set(0)
        moodsDetected.set(0)
        moodDistribution.clear()
        completionSummariesGenerated.set(0)
        tasksCompleted.set(0)
        tasksSucceeded.set(0)
        errorsExplained.set(0)
    }

    // =========================================================================
    //  内部辅助方法：视觉提示
    // =========================================================================

    /**
     * 为成功的动作推荐图标与动画。
     *
     * 不同动作类型对应不同的视觉语义：
     * - 打开应用 → OPEN 图标 + 滑入动画；
     * - 关闭应用 → CLOSE 图标 + 淡出动画；
     * - 搜索 / 查找 → SEARCH 图标，无动画；
     * - 等待 → WAIT 图标，无动画；
     * - 发送 / 回答 / 复制 → SEND 图标 + 淡出动画；
     * - 系统设置类 → SETTINGS 图标，无动画；
     * - 其他 → CHECK 图标 + 脉冲动画。
     *
     * @param actionType 动作类型
     * @return 图标类型与动画类型的配对
     */
    private fun successHintForAction(actionType: ActionType): Pair<IconType, AnimationType> {
        return when (actionType) {
            ActionType.APP_OPEN -> IconType.OPEN to AnimationType.SLIDE
            ActionType.APP_CLOSE -> IconType.CLOSE to AnimationType.FADE
            ActionType.APP_SEARCH,
            ActionType.SCREEN_FIND_AND_CLICK,
            ActionType.SCREEN_SCROLL_TO_TEXT -> IconType.SEARCH to AnimationType.NONE
            ActionType.SCREEN_WAIT -> IconType.WAIT to AnimationType.NONE
            ActionType.NOTIFY_SEND,
            ActionType.CLIPBOARD_COPY,
            ActionType.ANSWER -> IconType.SEND to AnimationType.FADE
            ActionType.SCREEN_INPUT -> IconType.CHECK to AnimationType.PULSE
            ActionType.SYSTEM_SET_VOLUME,
            ActionType.SYSTEM_SET_BRIGHTNESS,
            ActionType.SYSTEM_CLEAR_CACHE,
            ActionType.SYSTEM_KILL_PROCESS,
            ActionType.SYSTEM_GET_INFO -> IconType.SETTINGS to AnimationType.NONE
            else -> IconType.CHECK to AnimationType.PULSE
        }
    }

    // =========================================================================
    //  内部辅助方法：完成摘要
    // =========================================================================

    /**
     * 根据任务成败与耗时生成后续建议。
     *
     * - 成功：建议执行后续操作、优化耗时、设置快捷指令；
     * - 失败：建议重试、检查网络与权限、拆分任务。
     *
     * @param success  任务是否成功
     * @param duration 任务耗时（毫秒）
     * @return 后续建议列表
     */
    private fun generateNextSuggestions(success: Boolean, duration: Long): List<String> {
        return if (success) {
            buildList {
                add("是否需要执行相关后续操作？")
                if (duration > 20_000L) {
                    add("耗时较长，下次可尝试更直接的操作方式")
                }
                add("可对常用操作设置快捷指令以提升效率")
            }
        } else {
            buildList {
                add("建议稍后重试该任务")
                add("检查网络连接与应用权限设置")
                add("如多次失败，可尝试拆分任务分步执行")
            }
        }
    }

    // =========================================================================
    //  内部辅助方法：错误分类与解释
    // =========================================================================

    /**
     * 根据错误信息和动作类型，将失败原因分类为 [ErrorCategory]。
     *
     * 分类优先级：权限 > 应用未安装 > 网络 > 超时 > 界面未就绪 > 元素未找到 > 未知。
     * 支持中英文关键字混合匹配。
     *
     * @param errorMessage 原始错误信息文本（调用前无需转小写，内部统一处理）
     * @param actionType    失败动作的类型，用于辅助消歧（可为 null）
     * @return 分类后的错误类型；无法匹配时返回 [ErrorCategory.UNKNOWN]
     */
    private fun classifyError(errorMessage: String, actionType: ActionType?): ErrorCategory {
        val msg = errorMessage.lowercase().trim()
        if (msg.isEmpty()) return ErrorCategory.UNKNOWN

        // 1. 权限拒绝（最高优先级，避免被其他规则误判）
        if (containsAny(
                msg,
                "permission", "权限", "denied", "拒绝",
                "forbidden", "禁止", "未授权", "unauthorized"
            )
        ) {
            return ErrorCategory.PERMISSION
        }

        // 2. 应用未安装（结合动作类型判定）
        val isAppAction = actionType != null && actionType in setOf(
            ActionType.APP_OPEN, ActionType.APP_SEARCH,
            ActionType.APP_INSTALL, ActionType.APP_UNINSTALL
        )
        if (isAppAction && containsAny(
                msg,
                "not found", "not installed", "未找到", "未安装",
                "找不到", "不存在", "no such package"
            )
        ) {
            return ErrorCategory.APP_NOT_INSTALLED
        }

        // 3. 网络错误
        if (containsAny(
                msg,
                "network", "网络", "connection", "连接",
                "socket", "unreachable", "无法访问", "断网",
                "unknown host", "ssl", "reset"
            )
        ) {
            return ErrorCategory.NETWORK
        }

        // 4. 超时
        if (containsAny(
                msg,
                "timeout", "超时", "timed out", "time out",
                "deadline", "expired", "已过期"
            )
        ) {
            return ErrorCategory.TIMEOUT
        }

        // 5. 界面未就绪
        if (containsAny(
                msg,
                "not ready", "未就绪", "loading", "加载中",
                "not visible", "不可见", "animating", "动画",
                "busy", "繁忙", "无响应"
            )
        ) {
            return ErrorCategory.UI_NOT_READY
        }

        // 6. 元素未找到
        if (containsAny(
                msg,
                "not found", "未找到", "找不到", "no such element",
                "元素", "不存在", "stale", "not present"
            )
        ) {
            return ErrorCategory.ELEMENT_NOT_FOUND
        }

        // 7. 兜底：未知错误
        return ErrorCategory.UNKNOWN
    }

    /**
     * 根据错误分类与应用上下文，构建用户友好的错误解释模板。
     *
     * @param category 错误分类
     * @param appRef   应用简称（可为 null，用于在说明中指明出问题的应用）
     * @return 错误解释模板
     */
    private fun buildExplanation(category: ErrorCategory, appRef: String?): ErrorTemplate {
        val contextPrefix = if (!appRef.isNullOrEmpty()) "在「$appRef」中，" else ""

        return when (category) {
            ErrorCategory.NETWORK -> ErrorTemplate(
                userMessage = "${contextPrefix}网络连接出现问题，请检查网络后重试。",
                possibleCauses = listOf("网络信号较弱", "WiFi 或移动数据未开启", "服务器暂时不可用"),
                suggestedActions = listOf("检查网络连接后重试", "切换 WiFi 与移动数据", "稍候片刻再试"),
                severity = FeedbackLevel.ERROR
            )

            ErrorCategory.ELEMENT_NOT_FOUND -> ErrorTemplate(
                userMessage = "${contextPrefix}未找到目标元素，可能需要滑动查找或等待加载。",
                possibleCauses = listOf("目标元素不在当前可视区域", "页面尚未加载完成", "元素文本与预期不一致"),
                suggestedActions = listOf("等待几秒后重试", "尝试滑动屏幕查找目标", "确认目标文本是否正确"),
                severity = FeedbackLevel.WARNING
            )

            ErrorCategory.APP_NOT_INSTALLED -> ErrorTemplate(
                userMessage = if (!appRef.isNullOrEmpty()) {
                    "未在设备上找到「$appRef」，可能未安装。"
                } else {
                    "目标应用未安装。"
                },
                possibleCauses = listOf("应用未安装", "应用名称输入有误", "应用被冻结或停用"),
                suggestedActions = listOf("确认应用名称是否正确", "前往应用商店搜索并安装", "在桌面手动查找该应用"),
                severity = FeedbackLevel.ERROR
            )

            ErrorCategory.PERMISSION -> ErrorTemplate(
                userMessage = "${contextPrefix}缺少必要权限，操作无法继续。",
                possibleCauses = listOf("未授予相关权限", "权限被系统或用户撤销", "应用被限制后台活动"),
                suggestedActions = listOf("前往系统设置开启对应权限", "检查无障碍服务是否已启用", "重启应用后重试"),
                severity = FeedbackLevel.ERROR
            )

            ErrorCategory.TIMEOUT -> ErrorTemplate(
                userMessage = "${contextPrefix}操作超时，响应未在规定时间内完成。",
                possibleCauses = listOf("网络速度较慢", "应用响应迟缓", "设备性能不足或后台过多"),
                suggestedActions = listOf("稍后重试", "清理后台应用释放资源", "检查网络连接稳定性"),
                severity = FeedbackLevel.WARNING
            )

            ErrorCategory.UI_NOT_READY -> ErrorTemplate(
                userMessage = "${contextPrefix}界面尚未就绪，请稍候再操作。",
                possibleCauses = listOf("页面正在加载", "动画播放中", "弹窗遮挡了目标元素"),
                suggestedActions = listOf("等待几秒后重试", "如有弹窗先将其关闭"),
                severity = FeedbackLevel.INFO
            )

            ErrorCategory.UNKNOWN -> ErrorTemplate(
                userMessage = "${contextPrefix}发生未知错误，请稍后重试。",
                possibleCauses = listOf("未知异常", "操作环境异常"),
                suggestedActions = listOf("重试该操作", "若持续失败请联系支持"),
                severity = FeedbackLevel.ERROR
            )
        }
    }

    // =========================================================================
    //  内部辅助方法：工具函数
    // =========================================================================

    /**
     * 从应用上下文中提取简称。
     *
     * 若形如包名（含点），取最后一段（如 `com.tencent.mm` → `mm`）；
     * 否则原样返回。空白或 null 返回 null。
     *
     * @param appContext 应用上下文（包名或应用名）
     * @return 应用简称，可为 null
     */
    private fun shortAppName(appContext: String?): String? {
        if (appContext.isNullOrEmpty()) return null
        return if (appContext.contains('.')) {
            appContext.substringAfterLast('.').takeIf { it.isNotBlank() } ?: appContext
        } else {
            appContext
        }
    }

    /**
     * 判断文本是否包含任意一个关键字（调用前应已将文本转为小写）。
     *
     * @param text     待检测文本
     * @param keywords 关键字变长参数
     * @return 包含任意关键字时返回 true
     */
    private fun containsAny(text: String, vararg keywords: String): Boolean {
        return keywords.any { it in text }
    }
}
