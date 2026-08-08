package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 修正规则。
 *
 * 当用户纠正 AI 执行的动作时（例如「不对，点另一个按钮」「应该向上滑而不是向下」），
 * 系统会将「原始动作 → 修正后动作」的映射记录为一条修正规则。后续在相似上下文中
 * 再次出现同类型动作时，可优先应用该修正，避免重复犯错。
 *
 * @property originalActionName     被纠正的原始动作类型名称（对应 [ActionType] 的 name）
 * @property correctedActionName    修正后的动作类型名称（用户期望的动作）
 * @property context                修正发生时的上下文（通常为前台应用包名）
 * @property originalParams         原始动作参数（用于匹配相似动作）
 * @property correctedParams        修正后的动作参数（用户期望的参数）
 * @property description            修正说明（自然语言，用于日志与调试）
 * @property confidence             置信度（0.0-1.0），随成功应用递增、失败递减
 * @property successCount           该规则被成功应用的次数
 * @property failureCount           该规则应用失败的次数
 * @property lastUsed               最后一次应用的时间戳（毫秒），用于衰减淘汰
 * @property createdAt              规则创建时间戳（毫秒）
 */
data class CorrectionRule(
    val originalActionName: String,
    var correctedActionName: String,
    val context: String,
    val originalParams: JsonObject,
    var correctedParams: JsonObject,
    var description: String,
    var confidence: Double,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var lastUsed: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 动作偏好。
 *
 * 记录用户在特定上下文中对「可互换动作类型」的偏好。例如对于翻页操作，
 * 用户可能偏好 [ActionType.SCREEN_SWIPE]（滑动）而非
 * [ActionType.SCREEN_SCROLL_TO_TEXT]（滚动查找）；对于点击操作，可能偏好
 * [ActionType.SCREEN_CLICK_TEXT]（按文本点击）而非 [ActionType.SCREEN_CLICK]
 * （按坐标点击）。引擎通过统计用户的历史选择，学习并固化这些偏好。
 *
 * @property context               偏好生效的上下文（通常为前台应用包名）
 * @property preferredActionType   用户偏好的动作类型
 * @property groupId               所属「可互换动作组」标识（见 [AdaptiveLearningEngine.interchangeableGroups]）
 * @property alternativeTypes      同组内备选动作类型（用户较少选择的其他类型）
 * @property confidence            置信度（0.0-1.0），随偏好被验证递增
 * @property preferenceCount       该偏好被观察到的次数（用户选择该类型的次数）
 * @property successCount          应用该偏好后动作成功的次数
 * @property failureCount          应用该偏好后动作失败的次数
 * @property lastUsed              最后一次应用的时间戳（毫秒）
 */
data class ActionPreference(
    val context: String,
    var preferredActionType: ActionType,
    val groupId: String,
    var alternativeTypes: List<ActionType>,
    var confidence: Double,
    var preferenceCount: Int = 0,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var lastUsed: Long = System.currentTimeMillis()
)

/**
 * 已学习的错误模式。
 *
 * 统计特定动作类型在特定上下文中的成功/失败次数，计算失败概率。当某动作类型
 * 在某应用中失败率较高时，引擎会主动预警并建议替代动作类型，实现「未雨绸缪」
 * 的错误规避。
 *
 * @property actionType            被统计的动作类型
 * @property context               失败发生的上下文（通常为前台应用包名）
 * @property failureCount          失败次数
 * @property successCount          成功次数
 * @property suggestedAlternative  建议的替代动作类型（可能为 null，表示无推荐替代；失败时若
 *                                  传入新建议则更新覆盖）
 * @property confidence            模式置信度（0.0-1.0），反映该失败模式是否稳定
 * @property lastUsed              最后一次记录的时间戳（毫秒）
 * @property failureProbability    失败概率（0.0-1.0），= failureCount / (failureCount + successCount)
 */
data class LearnedPattern(
    val actionType: ActionType,
    val context: String,
    var failureCount: Int = 0,
    var successCount: Int = 0,
    var suggestedAlternative: ActionType?,
    var confidence: Double,
    var lastUsed: Long = System.currentTimeMillis()
) {
    /** 失败概率：失败次数 / (失败次数 + 成功次数)，无样本时为 0.0。 */
    val failureProbability: Double
        get() {
            val total = failureCount + successCount
            return if (total > 0) failureCount.toDouble() / total else 0.0
        }

    /** 模式摘要（用于日志与统计展示）。 */
    fun summary(): String =
        "[${actionType.name}@$context] 失败$failureCount/成功$successCount " +
            "失败率${"%.0f".format(failureProbability * 100)}% 置信度${"%.2f".format(confidence)}"
}

/**
 * 学习应用结果。
 *
 * [AdaptiveLearningEngine.applyLearning] 的返回值，描述对原始动作施加学习后的结果，
 * 包括是否被修正、应用的修正规则、偏好动作类型、错误预警以及建议列表。
 *
 * @property originalAction        原始动作（未经任何修正）
 * @property modifiedAction        修正后的动作（若 [wasModified] 为 false 则等于原始动作）
 * @property wasModified           动作是否被修正
 * @property correctionApplied     应用的修正规则（未应用修正时为 null）
 * @property preferredType         应用的偏好动作类型（未应用偏好时为 null）
 * @property errorWarning          错误模式预警信息（无预警时为 null）
 * @property suggestions           建议列表（人类可读的中文建议）
 */
data class LearningApplication(
    val originalAction: ClawAction,
    val modifiedAction: ClawAction,
    val wasModified: Boolean,
    val correctionApplied: CorrectionRule?,
    val preferredType: ActionType?,
    val errorWarning: String?,
    val suggestions: List<String>
)

// =============================================================================
//  AdaptiveLearningEngine —— 自适应学习引擎
// =============================================================================

/**
 * AdaptiveLearningEngine —— 自适应学习引擎
 *
 * 从用户反馈与执行结果中持续学习，动态调整 MobileClaw 的行为，使其越来越贴合
 * 用户的使用习惯，并主动规避已知的失败模式。
 *
 * 核心理念：AI 解析动作难免出错，但「同样的错误不应犯第二次」。本引擎将用户的
 * 每一次纠正、每一次偏好选择、每一次执行成败都转化为可复用的规则，让系统在
 * 相似场景下自动修正动作、应用偏好、规避错误，实现「越用越聪明」。
 *
 * 五大核心能力：
 * 1. **纠正学习**：记录用户对动作的纠正（如「点另一个按钮」），在相似上下文中
 *    自动应用历史纠正，避免重复犯错。
 * 2. **偏好学习**：统计用户对可互换动作类型（如滑动 vs 滚动、按文本点击 vs 按
 *    坐标点击）的选择偏好，固化用户习惯。
 * 3. **错误模式学习**：统计各动作类型在各应用中的成功/失败次数与失败概率，在
 *    高失败率场景下主动预警并建议替代动作。
 * 4. **置信度评分**：每条规则均带置信度（0.0-1.0），成功应用递增、失败递减，
 *    低于阈值 [MIN_CONFIDENCE] 的规则会被淘汰，保证学习质量。
 * 5. **规则衰减**：长时间未使用的规则会按时间衰减置信度，使引擎聚焦于用户近期
 *    习惯，遗忘过时规则。
 *
 * ### 线程安全
 * 所有存储均使用 [ConcurrentHashMap]，计数使用 [AtomicInteger]，
 * 可被多线程并发调用（典型场景：执行线程记录结果、决策线程查询学习建议）。
 *
 * ### 容量与淘汰
 * - 修正规则最多保留 [MAX_CORRECTION_RULES]（200）条
 * - 动作偏好最多保留 [MAX_ACTION_PREFERENCES]（100）条
 * - 错误模式最多保留 [MAX_LEARNED_PATTERNS]（300）条
 * - 超出时按 LRU（最久未使用）策略淘汰
 * - 置信度低于 [MIN_CONFIDENCE]（0.1）的规则在清理时自动移除
 *
 * ### 置信度机制
 * - 新规则初始置信度为 [INITIAL_CONFIDENCE]（0.5）
 * - 成功应用：置信度 += [CONFIDENCE_INCREMENT]（0.1），上限 [MAX_CONFIDENCE]（1.0）
 * - 失败应用：置信度 -= [CONFIDENCE_DECREMENT]（0.15），下限 0.0
 *   （失败惩罚略大于成功奖励，避免错误规则长期残留）
 *
 * ### 衰减机制
 * - 规则超过 [DECAY_THRESHOLD_MS]（7 天）未使用即开始衰减
 * - 每经过 [DECAY_INTERVAL_MS]（1 天）衰减 [DECAY_AMOUNT]（0.05）
 * - 衰减在规则被访问时惰性计算，也可通过 [applyDecay] 主动批量执行
 *
 * ### 典型调用流程
 * ```
 * val engine = AdaptiveLearningEngine()
 * // 1. 用户纠正了一个动作
 * engine.recordCorrection(
 *     originalAction = wrongAction,
 *     correctedAction = rightAction,
 *     context = "com.tencent.mm",
 *     description = "用户要求点击「发送」而非「取消」"
 * )
 * // 2. 记录用户偏好（用户选择了滑动而非滚动）
 * engine.recordActionPreference(
 *     context = "com.ss.android.ugc.aweme",
 *     chosenType = ActionType.SCREEN_SWIPE,
 *     candidateTypes = listOf(ActionType.SCREEN_SWIPE, ActionType.SCREEN_SCROLL_TO_TEXT)
 * )
 * // 3. 记录动作执行结果（成功/失败）
 * engine.recordErrorPattern(
 *     actionType = ActionType.SCREEN_CLICK_TEXT,
 *     context = "com.tencent.mm",
 *     success = false,
 *     suggestedAlternative = ActionType.SCREEN_FIND_AND_CLICK
 * )
 * // 4. 对新动作施加学习（综合应用修正、偏好、错误预警）
 * val application = engine.applyLearning(newAction, "com.tencent.mm")
 * if (application.wasModified) {
 *     // 使用修正后的动作执行
 *     execute(application.modifiedAction)
 * }
 * // 5. 反馈应用结果，更新置信度
 * engine.feedbackCorrection(action = newAction, context = "com.tencent.mm", success = true)
 * // 6. 输出统计
 * println(engine.getStats())
 * ```
 */
class AdaptiveLearningEngine {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 修正规则最大保留数量，超出时按 LRU 淘汰。 */
        private const val MAX_CORRECTION_RULES = 200

        /** 动作偏好最大保留数量，超出时按 LRU 淘汰。 */
        private const val MAX_ACTION_PREFERENCES = 100

        /** 错误模式最大保留数量，超出时按 LRU 淘汰。 */
        private const val MAX_LEARNED_PATTERNS = 300

        /** 新规则的初始置信度。 */
        private const val INITIAL_CONFIDENCE = 0.5

        /** 成功应用时的置信度增量。 */
        private const val CONFIDENCE_INCREMENT = 0.1

        /** 失败应用时的置信度减量（略大于增量，加速淘汰错误规则）。 */
        private const val CONFIDENCE_DECREMENT = 0.15

        /** 置信度上限。 */
        private const val MAX_CONFIDENCE = 1.0

        /** 置信度下限（低于此值的规则在清理时被移除）。 */
        private const val MIN_CONFIDENCE = 0.1

        /** 应用修正/偏好所需的最低置信度阈值。 */
        private const val APPLY_CONFIDENCE_THRESHOLD = 0.5

        /** 触发错误预警的最低失败概率阈值。 */
        private const val ERROR_WARNING_THRESHOLD = 0.5

        /** 触发错误预警所需的最小样本数（避免小样本误报）。 */
        private const val ERROR_WARNING_MIN_SAMPLES = 3

        /** 规则衰减启动阈值：超过此时长未使用即开始衰减（7 天）。 */
        private const val DECAY_THRESHOLD_MS = 7 * 24 * 60 * 60 * 1000L

        /** 衰减计算间隔：每经过此时长衰减一次（1 天）。 */
        private const val DECAY_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** 每次衰减的置信度减量。 */
        private const val DECAY_AMOUNT = 0.05
    }

    /** 日志标签。 */
    private val tag = "AdaptiveLearningEngine"

    // =========================================================================
    //  可互换动作组定义
    // =========================================================================

    /**
     * 可互换动作组：语义相近、可相互替代的动作类型集合。
     *
     * 当用户在某上下文中反复选择组内某一类型时，引擎将其记录为偏好。
     * 后续在相同上下文遇到同组动作时，优先使用用户偏好的类型。
     */
    private val interchangeableGroups: List<Set<ActionType>> = listOf(
        // 翻页/滚动类：滑动与滚动查找可互换
        setOf(ActionType.SCREEN_SWIPE, ActionType.SCREEN_SCROLL_TO_TEXT),
        // 点击类：按坐标、按文本、查找并点击可互换
        setOf(
            ActionType.SCREEN_CLICK,
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK
        ),
        // 长按/双击类
        setOf(ActionType.SCREEN_LONG_CLICK, ActionType.SCREEN_DOUBLE_CLICK),
        // 打开应用类：按包名打开与按名称搜索打开可互换
        setOf(ActionType.APP_OPEN, ActionType.APP_SEARCH)
    )

    // =========================================================================
    //  存储结构（全部线程安全）
    // =========================================================================

    /**
     * 修正规则存储。键 = "原始动作名@上下文"，值 = [CorrectionRule]。
     *
     * 相同（原始动作类型 + 上下文）的纠正会合并到同一条规则，累加成功/失败计数。
     */
    private val correctionRules: ConcurrentHashMap<String, CorrectionRule> = ConcurrentHashMap()

    /**
     * 动作偏好存储。键 = "可互换组ID@上下文"，值 = [ActionPreference]。
     *
     * 同一可互换组在同一上下文下仅保留一条偏好（用户最常选择的类型）。
     */
    private val actionPreferences: ConcurrentHashMap<String, ActionPreference> = ConcurrentHashMap()

    /**
     * 错误模式存储。键 = "动作类型名@上下文"，值 = [LearnedPattern]。
     *
     * 记录每种动作类型在每个应用上下文中的成功/失败统计。
     */
    private val learnedPatterns: ConcurrentHashMap<String, LearnedPattern> = ConcurrentHashMap()

    // =========================================================================
    //  统计计数
    // =========================================================================

    /** 累计记录的修正总数（含已淘汰）。 */
    private val totalCorrections = AtomicInteger(0)

    /** 累计记录的偏好总数（含已淘汰）。 */
    private val totalPreferences = AtomicInteger(0)

    /** 累计记录的错误样本总数（含已淘汰）。 */
    private val totalErrorSamples = AtomicInteger(0)

    /** 累计成功应用学习的次数（修正或偏好生效且执行成功）。 */
    private val totalAppliedSuccess = AtomicInteger(0)

    /** 累计失败应用学习的次数（修正或偏好生效但执行失败）。 */
    private val totalAppliedFailure = AtomicInteger(0)

    // =========================================================================
    //  纠正学习
    // =========================================================================

    /**
     * 记录一次用户纠正。
     *
     * 当用户纠正 AI 执行的动作时调用。若相同（原始动作类型 + 上下文）的规则已存在，
     * 则更新其修正后动作与置信度（重置为较高值，因为用户再次纠正说明该规则仍有效）；
     * 否则新建一条规则。
     *
     * 超过 [MAX_CORRECTION_RULES] 时按 LRU 淘汰最久未使用的规则。
     *
     * @param originalAction  被纠正的原始动作
     * @param correctedAction 用户期望的修正后动作
     * @param context         纠正发生时的上下文（通常为前台应用包名）
     * @param description     修正说明（自然语言）
     */
    fun recordCorrection(
        originalAction: ClawAction,
        correctedAction: ClawAction,
        context: String,
        description: String = ""
    ) {
        val originalName = originalAction.actionName
        if (originalName.isBlank()) return

        val key = correctionKey(originalName, context)
        val now = System.currentTimeMillis()

        correctionRules.compute(key) { _, existing ->
            if (existing == null) {
                // 新建修正规则
                CorrectionRule(
                    originalActionName = originalName,
                    correctedActionName = correctedAction.actionName,
                    context = context,
                    originalParams = originalAction.params,
                    correctedParams = correctedAction.params,
                    description = if (description.isNotBlank()) description else correctedAction.description,
                    confidence = INITIAL_CONFIDENCE,
                    lastUsed = now,
                    createdAt = now
                )
            } else {
                // 已存在：更新修正后动作（用户最新的纠正覆盖旧的），提升置信度
                existing.apply {
                    correctedActionName = correctedAction.actionName
                    correctedParams = correctedAction.params
                    if (description.isNotBlank()) this.description = description
                    // 用户再次纠正，说明规则有效，提升置信度
                    confidence = (confidence + CONFIDENCE_INCREMENT).coerceAtMost(MAX_CONFIDENCE)
                    lastUsed = now
                }
            }
        }

        totalCorrections.incrementAndGet()
        evictCorrections()

        Log.d(
            tag,
            "记录纠正: $originalName -> ${correctedAction.actionName} @$context " +
                "(当前 ${correctionRules.size}/$MAX_CORRECTION_RULES)"
        )
    }

    /**
     * 查询某动作在某上下文下的修正规则。
     *
     * 仅返回置信度 ≥ [APPLY_CONFIDENCE_THRESHOLD] 的规则，并对该规则执行惰性衰减计算。
     *
     * @param action  待查询的动作
     * @param context 当前上下文（通常为前台应用包名）
     * @return 匹配的修正规则；无匹配或置信度过低时返回 null
     */
    fun getCorrection(action: ClawAction, context: String): CorrectionRule? {
        if (action.actionName.isBlank()) return null
        val key = correctionKey(action.actionName, context)
        val rule = correctionRules[key] ?: return null

        // 惰性衰减
        applyDecayToRule(rule)

        return if (rule.confidence >= APPLY_CONFIDENCE_THRESHOLD) rule else null
    }

    /**
     * 反馈修正规则的应用结果，更新其置信度与计数。
     *
     * 应在执行修正后动作并获知结果时调用：
     * - 成功：置信度递增、成功计数 +1
     * - 失败：置信度递减、失败计数 +1
     *
     * @param action  应用了修正的动作（用于定位规则）
     * @param context 应用上下文
     * @param success 应用是否成功
     */
    fun feedbackCorrection(action: ClawAction, context: String, success: Boolean) {
        if (action.actionName.isBlank()) return
        val key = correctionKey(action.actionName, context)
        correctionRules.computeIfPresent(key) { _, rule ->
            rule.apply {
                if (success) {
                    successCount++
                    confidence = (confidence + CONFIDENCE_INCREMENT).coerceAtMost(MAX_CONFIDENCE)
                    totalAppliedSuccess.incrementAndGet()
                } else {
                    failureCount++
                    confidence = (confidence - CONFIDENCE_DECREMENT).coerceAtLeast(0.0)
                    totalAppliedFailure.incrementAndGet()
                }
                lastUsed = System.currentTimeMillis()
            }
        }
    }

    // =========================================================================
    //  偏好学习
    // =========================================================================

    /**
     * 记录一次用户的动作类型选择偏好。
     *
     * 当系统向用户提供多个可互换动作类型、或用户主动选择某种类型时调用。引擎会
     * 统计用户在指定上下文中对各类型的偏好，记录最常选择的类型及其置信度。
     *
     * 若该上下文下同组已有偏好记录，则比较新选择类型与已记录偏好类型：
     * - 一致：偏好计数 +1，置信度递增
     * - 不一致：降低原偏好置信度；若新类型累计选择次数超过原偏好，则替换偏好类型
     *
     * 超过 [MAX_ACTION_PREFERENCES] 时按 LRU 淘汰。
     *
     * @param context        偏好上下文（通常为前台应用包名）
     * @param chosenType     用户实际选择的动作类型
     * @param candidateTypes 候选动作类型列表（应包含 [chosenType] 及其可互换类型）
     */
    fun recordActionPreference(
        context: String,
        chosenType: ActionType,
        candidateTypes: List<ActionType>
    ) {
        val group = findInterchangeableGroup(chosenType) ?: return
        val groupId = groupIdOf(group)
        val alternatives = candidateTypes.filter { it != chosenType && it in group }
        val key = preferenceKey(groupId, context)
        val now = System.currentTimeMillis()

        actionPreferences.compute(key) { _, existing ->
            if (existing == null) {
                // 新建偏好
                ActionPreference(
                    context = context,
                    preferredActionType = chosenType,
                    groupId = groupId,
                    alternativeTypes = alternatives,
                    confidence = INITIAL_CONFIDENCE,
                    preferenceCount = 1,
                    lastUsed = now
                )
            } else if (existing.preferredActionType == chosenType) {
                // 与已记录偏好一致：强化
                existing.apply {
                    preferenceCount++
                    confidence = (confidence + CONFIDENCE_INCREMENT).coerceAtMost(MAX_CONFIDENCE)
                    lastUsed = now
                }
            } else {
                // 与已记录偏好不一致：弱化原偏好
                existing.apply {
                    confidence = (confidence - CONFIDENCE_DECREMENT).coerceAtLeast(0.0)
                    lastUsed = now
                    // 当置信度跌至阈值以下，且新选择类型在候选中，切换偏好
                    if (confidence < APPLY_CONFIDENCE_THRESHOLD && chosenType in group) {
                        preferredActionType = chosenType
                        alternativeTypes = alternatives
                        preferenceCount = 1
                        confidence = INITIAL_CONFIDENCE
                    }
                }
            }
        }

        totalPreferences.incrementAndGet()
        evictPreferences()

        Log.d(
            tag,
            "记录偏好: $chosenType @$context (组=$groupId, " +
                "当前 ${actionPreferences.size}/$MAX_ACTION_PREFERENCES)"
        )
    }

    /**
     * 查询某上下文下用户偏好的动作类型。
     *
     * 给定候选动作类型列表，返回用户在该上下文中偏好的类型（若其置信度达标）。
     * 用于在 AI 返回多个可互换动作时，优先选用用户习惯的类型。
     *
     * @param context        当前上下文
     * @param candidateTypes 候选动作类型列表
     * @return 用户偏好的动作类型；无偏好或置信度过低时返回 null
     */
    fun getPreferredAction(context: String, candidateTypes: List<ActionType>): ActionType? {
        if (candidateTypes.isEmpty()) return null

        // 遍历候选类型所属的可互换组，查找匹配的偏好
        for (type in candidateTypes) {
            val group = findInterchangeableGroup(type) ?: continue
            val groupId = groupIdOf(group)
            val key = preferenceKey(groupId, context)
            val pref = actionPreferences[key] ?: continue

            applyDecayToPreference(pref)

            if (pref.confidence >= APPLY_CONFIDENCE_THRESHOLD &&
                pref.preferredActionType in candidateTypes
            ) {
                return pref.preferredActionType
            }
        }
        return null
    }

    /**
     * 反馈偏好应用结果，更新置信度与计数。
     *
     * @param context    应用上下文
     * @param actionType 应用了偏好的动作类型
     * @param success    应用是否成功
     */
    fun feedbackActionPreference(context: String, actionType: ActionType, success: Boolean) {
        val group = findInterchangeableGroup(actionType) ?: return
        val groupId = groupIdOf(group)
        val key = preferenceKey(groupId, context)

        actionPreferences.computeIfPresent(key) { _, pref ->
            pref.apply {
                if (success) {
                    successCount++
                    confidence = (confidence + CONFIDENCE_INCREMENT).coerceAtMost(MAX_CONFIDENCE)
                    totalAppliedSuccess.incrementAndGet()
                } else {
                    failureCount++
                    confidence = (confidence - CONFIDENCE_DECREMENT).coerceAtLeast(0.0)
                    totalAppliedFailure.incrementAndGet()
                }
                lastUsed = System.currentTimeMillis()
            }
        }
    }

    // =========================================================================
    //  错误模式学习
    // =========================================================================

    /**
     * 记录一次动作执行的成败，用于错误模式学习。
     *
     * 累计每种动作类型在每个上下文中的成功/失败次数，计算失败概率，并维护置信度。
     * 当失败率较高时，可通过 [getErrorPattern] 查询并触发预警与替代建议。
     *
     * @param actionType          执行的动作类型
     * @param context             执行上下文（通常为前台应用包名）
     * @param success             执行是否成功
     * @param suggestedAlternative 建议的替代动作类型（失败时可传入，供后续预警引用）
     */
    fun recordErrorPattern(
        actionType: ActionType,
        context: String,
        success: Boolean,
        suggestedAlternative: ActionType? = null
    ) {
        val key = patternKey(actionType, context)
        val now = System.currentTimeMillis()

        learnedPatterns.compute(key) { _, existing ->
            if (existing == null) {
                LearnedPattern(
                    actionType = actionType,
                    context = context,
                    failureCount = if (success) 0 else 1,
                    successCount = if (success) 1 else 0,
                    suggestedAlternative = suggestedAlternative,
                    confidence = INITIAL_CONFIDENCE,
                    lastUsed = now
                )
            } else {
                existing.apply {
                    if (success) {
                        successCount++
                        // 成功样本提升模式稳定性置信度（幅度减半，避免成功样本过快拉高置信度）
                        confidence = (confidence + CONFIDENCE_INCREMENT * 0.5)
                            .coerceAtMost(MAX_CONFIDENCE)
                    } else {
                        failureCount++
                        // 失败样本若携带新替代建议，则更新（保留最新的有效建议）
                        if (suggestedAlternative != null) {
                            this.suggestedAlternative = suggestedAlternative
                        }
                        // 失败样本强化「该模式确实存在」的置信度
                        confidence = (confidence + CONFIDENCE_INCREMENT)
                            .coerceAtMost(MAX_CONFIDENCE)
                    }
                    lastUsed = now
                }
            }
        }

        totalErrorSamples.incrementAndGet()
        evictPatterns()

        if (!success) {
            Log.d(
                tag,
                "记录失败样本: ${actionType.name}@$context " +
                    "(当前 ${learnedPatterns.size}/$MAX_LEARNED_PATTERNS)"
            )
        }
    }

    /**
     * 查询某动作类型在某上下文下的错误模式。
     *
     * 对返回的模式执行惰性衰减计算。调用方可根据 [LearnedPattern.failureProbability]
     * 与 [LearnedPattern.confidence] 判断是否需要预警。
     *
     * @param actionType 动作类型
     * @param context    上下文
     * @return 错误模式；无记录时返回 null
     */
    fun getErrorPattern(actionType: ActionType, context: String): LearnedPattern? {
        val key = patternKey(actionType, context)
        val pattern = learnedPatterns[key] ?: return null
        applyDecayToPattern(pattern)
        return pattern
    }

    /**
     * 判断某动作在某上下文下是否应触发错误预警。
     *
     * 当失败概率 ≥ [ERROR_WARNING_THRESHOLD] 且样本数 ≥ [ERROR_WARNING_MIN_SAMPLES]
     * 时返回预警信息，否则返回 null。
     *
     * @param actionType 动作类型
     * @param context    上下文
     * @return 预警信息字符串；无需预警时返回 null
     */
    fun getErrorWarning(actionType: ActionType, context: String): String? {
        val pattern = getErrorPattern(actionType, context) ?: return null
        val total = pattern.failureCount + pattern.successCount
        if (total < ERROR_WARNING_MIN_SAMPLES) return null
        if (pattern.failureProbability < ERROR_WARNING_THRESHOLD) return null

        return buildString {
            append("警告：${actionType.description}在 $context 中失败率较高")
            append("（${"%.0f".format(pattern.failureProbability * 100)}%，")
            append("${pattern.failureCount}/${total}）")
            val alt = pattern.suggestedAlternative
            if (alt != null) {
                append("，建议改用${alt.description}（${alt.name}）")
            }
        }
    }

    // =========================================================================
    //  综合应用学习
    // =========================================================================

    /**
     * 对单个动作综合应用全部学习成果。
     *
     * 依次执行三步学习应用，优先级从高到低：
     * 1. **纠正应用**：若存在匹配的高置信度修正规则，将动作替换为修正后动作。
     * 2. **偏好应用**：若动作类型属于可互换组且用户有偏好，将动作类型替换为偏好类型
     *    （仅当偏好类型与原类型不同且置信度达标时）。
     * 3. **错误预警**：若动作类型在该上下文失败率较高，附加预警信息与替代建议。
     *
     * 注意：纠正应用会直接替换动作参数；偏好应用仅替换动作类型名称，保留原参数
     * （因偏好类型与原类型语义可互换，参数通常兼容）。
     *
     * @param action  待应用学习的原始动作
     * @param context 当前上下文（通常为前台应用包名）
     * @return 学习应用结果 [LearningApplication]
     */
    fun applyLearning(action: ClawAction, context: String): LearningApplication {
        val suggestions = ArrayList<String>()
        var currentAction = action
        var correctionApplied: CorrectionRule? = null
        var preferredType: ActionType? = null
        var errorWarning: String? = null

        // 1. 纠正应用
        val correction = getCorrection(action, context)
        if (correction != null) {
            val corrected = ClawAction(
                actionName = correction.correctedActionName,
                params = correction.correctedParams,
                description = if (correction.description.isNotBlank()) {
                    correction.description
                } else {
                    "已应用历史纠正：${action.actionName} -> ${correction.correctedActionName}"
                }
            )
            currentAction = corrected
            correctionApplied = correction
            suggestions.add(
                "已应用历史纠正(${correction.successCount}次成功)：${action.actionName} -> " +
                    "${correction.correctedActionName}（置信度${"%.2f".format(correction.confidence)}）"
            )
            Log.d(tag, "应用纠正: ${action.actionName} -> ${correction.correctedActionName} @$context")
        }

        // 2. 偏好应用（仅在未应用纠正时考虑，避免与纠正冲突）
        if (correctionApplied == null) {
            val actionType = currentAction.type
            if (actionType != null) {
                val group = findInterchangeableGroup(actionType)
                if (group != null) {
                    val preferred = getPreferredAction(context, group.toList())
                    if (preferred != null && preferred != actionType) {
                        currentAction = ClawAction(
                            actionName = preferred.name,
                            params = currentAction.params,
                            description = currentAction.description
                        )
                        preferredType = preferred
                        suggestions.add(
                            "已应用动作偏好：${actionType.description} -> " +
                                "${preferred.description}（用户在该应用习惯使用此操作）"
                        )
                        Log.d(tag, "应用偏好: ${actionType.name} -> ${preferred.name} @$context")
                    }
                }
            }
        }

        // 3. 错误预警（基于最终动作类型）
        val finalType = currentAction.type
        if (finalType != null) {
            errorWarning = getErrorWarning(finalType, context)
            if (errorWarning != null) {
                suggestions.add(errorWarning)
                Log.d(tag, "错误预警: ${finalType.name} @$context - $errorWarning")
            }
        }

        val wasModified = correctionApplied != null || preferredType != null

        return LearningApplication(
            originalAction = action,
            modifiedAction = currentAction,
            wasModified = wasModified,
            correctionApplied = correctionApplied,
            preferredType = preferredType,
            errorWarning = errorWarning,
            suggestions = suggestions
        )
    }

    // =========================================================================
    //  统计与查询
    // =========================================================================

    /**
     * 获取学习引擎的统计摘要（人类可读字符串）。
     *
     * 包含：各类规则当前数量与累计数量、应用成功/失败次数、高置信度规则数量、
     * Top 修正规则、Top 错误模式等。
     *
     * @return 统计摘要字符串
     */
    fun getStats(): String {
        val sb = StringBuilder()

        sb.appendLine("===== AdaptiveLearningEngine 学习统计 =====")
        sb.appendLine("修正规则: ${correctionRules.size}/$MAX_CORRECTION_RULES (累计 ${totalCorrections.get()})")
        sb.appendLine("动作偏好: ${actionPreferences.size}/$MAX_ACTION_PREFERENCES (累计 ${totalPreferences.get()})")
        sb.appendLine("错误模式: ${learnedPatterns.size}/$MAX_LEARNED_PATTERNS (累计样本 ${totalErrorSamples.get()})")
        sb.appendLine(
            "学习应用: 成功 ${totalAppliedSuccess.get()} 次 | 失败 ${totalAppliedFailure.get()} 次"
        )
        sb.appendLine()

        // 高置信度规则统计
        val highConfCorrections = correctionRules.values.count {
            applyDecayToRuleSilent(it); it.confidence >= APPLY_CONFIDENCE_THRESHOLD
        }
        val highConfPreferences = actionPreferences.values.count {
            applyDecayToPreferenceSilent(it); it.confidence >= APPLY_CONFIDENCE_THRESHOLD
        }
        sb.appendLine("高置信度修正规则(>=${APPLY_CONFIDENCE_THRESHOLD}): $highConfCorrections")
        sb.appendLine("高置信度动作偏好(>=${APPLY_CONFIDENCE_THRESHOLD}): $highConfPreferences")
        sb.appendLine()

        // Top 修正规则
        sb.appendLine("-- Top 修正规则（按成功次数） --")
        val topCorrections = correctionRules.values
            .sortedByDescending { it.successCount }
            .take(5)
        if (topCorrections.isEmpty()) {
            sb.appendLine("  (无)")
        } else {
            for (rule in topCorrections) {
                sb.appendLine(
                    "  ${rule.originalActionName} -> ${rule.correctedActionName} " +
                        "@${rule.context} (成功${rule.successCount}/失败${rule.failureCount}, " +
                        "置信度${"%.2f".format(rule.confidence)})"
                )
            }
        }
        sb.appendLine()

        // Top 错误模式
        sb.appendLine("-- Top 错误模式（按失败次数） --")
        val topPatterns = learnedPatterns.values
            .filter { it.failureCount > 0 }
            .sortedByDescending { it.failureCount }
            .take(5)
        if (topPatterns.isEmpty()) {
            sb.appendLine("  (无)")
        } else {
            for (pattern in topPatterns) {
                sb.appendLine("  ${pattern.summary()}")
            }
        }
        sb.appendLine()

        // Top 动作偏好
        sb.appendLine("-- Top 动作偏好（按偏好次数） --")
        val topPrefs = actionPreferences.values
            .sortedByDescending { it.preferenceCount }
            .take(5)
        if (topPrefs.isEmpty()) {
            sb.appendLine("  (无)")
        } else {
            for (pref in topPrefs) {
                sb.appendLine(
                    "  ${pref.preferredActionType.name} @${pref.context} " +
                        "(选择${pref.preferenceCount}次, 置信度${"%.2f".format(pref.confidence)})"
                )
            }
        }
        sb.appendLine("==========================================")

        return sb.toString()
    }

    /**
     * 获取所有修正规则（用于 UI 展示或持久化）。
     *
     * @return 修正规则列表（按最后使用时间降序）
     */
    fun getAllCorrections(): List<CorrectionRule> =
        correctionRules.values.sortedByDescending { it.lastUsed }

    /**
     * 获取所有动作偏好（用于 UI 展示或持久化）。
     *
     * @return 动作偏好列表（按最后使用时间降序）
     */
    fun getAllPreferences(): List<ActionPreference> =
        actionPreferences.values.sortedByDescending { it.lastUsed }

    /**
     * 获取所有错误模式（用于 UI 展示或持久化）。
     *
     * @return 错误模式列表（按最后使用时间降序）
     */
    fun getAllPatterns(): List<LearnedPattern> =
        learnedPatterns.values.sortedByDescending { it.lastUsed }

    // =========================================================================
    //  衰减与维护
    // =========================================================================

    /**
     * 对所有规则批量执行衰减计算。
     *
     * 遍历全部修正规则、动作偏好与错误模式，对超过 [DECAY_THRESHOLD_MS] 未使用的
     * 规则按 [DECAY_INTERVAL_MS] 衰减置信度。建议由定时任务周期性调用（如每日一次）。
     *
     * 衰减后置信度低于 [MIN_CONFIDENCE] 的规则会被移除。
     *
     * @return 本次衰减实际移除的规则总数
     */
    fun applyDecay(): Int {
        var removed = 0

        // 衰减并清理修正规则
        val correctionToRemove = ArrayList<String>()
        for ((key, rule) in correctionRules) {
            rule.confidence = computeDecayedConfidence(rule.confidence, rule.lastUsed)
            if (rule.confidence < MIN_CONFIDENCE) {
                correctionToRemove.add(key)
            }
        }
        for (key in correctionToRemove) {
            correctionRules.remove(key)
            removed++
        }

        // 衰减并清理动作偏好
        val preferenceToRemove = ArrayList<String>()
        for ((key, pref) in actionPreferences) {
            pref.confidence = computeDecayedConfidence(pref.confidence, pref.lastUsed)
            if (pref.confidence < MIN_CONFIDENCE) {
                preferenceToRemove.add(key)
            }
        }
        for (key in preferenceToRemove) {
            actionPreferences.remove(key)
            removed++
        }

        // 衰减并清理错误模式
        val patternToRemove = ArrayList<String>()
        for ((key, pattern) in learnedPatterns) {
            pattern.confidence = computeDecayedConfidence(pattern.confidence, pattern.lastUsed)
            if (pattern.confidence < MIN_CONFIDENCE) {
                patternToRemove.add(key)
            }
        }
        for (key in patternToRemove) {
            learnedPatterns.remove(key)
            removed++
        }

        if (removed > 0) {
            Log.d(tag, "衰减完成：移除 $removed 条低置信度规则")
        }
        return removed
    }

    /**
     * 清理过期与低置信度规则。
     *
     * 移除置信度低于 [MIN_CONFIDENCE] 的规则，以及超过 [DECAY_THRESHOLD_MS] 未使用
     * 且置信度低于 [APPLY_CONFIDENCE_THRESHOLD] 的规则。
     */
    fun cleanup() {
        val now = System.currentTimeMillis()

        // 先执行一次衰减
        applyDecay()

        // 移除低置信度规则
        correctionRules.entries.removeIf { (_, rule) ->
            rule.confidence < MIN_CONFIDENCE
        }
        actionPreferences.entries.removeIf { (_, pref) ->
            pref.confidence < MIN_CONFIDENCE
        }
        learnedPatterns.entries.removeIf { (_, pattern) ->
            pattern.confidence < MIN_CONFIDENCE
        }

        Log.d(
            tag,
            "清理完成: 修正=${correctionRules.size} 偏好=${actionPreferences.size} " +
                "模式=${learnedPatterns.size} (now=$now)"
        )
    }

    /**
     * 清空所有学习数据与统计计数。
     *
     * 适用于测试或需要重置学习状态的场景。
     */
    fun clear() {
        correctionRules.clear()
        actionPreferences.clear()
        learnedPatterns.clear()
        totalCorrections.set(0)
        totalPreferences.set(0)
        totalErrorSamples.set(0)
        totalAppliedSuccess.set(0)
        totalAppliedFailure.set(0)
        Log.d(tag, "已清空所有学习数据与统计")
    }

    // =========================================================================
    //  内部辅助方法 —— 键生成与可互换组
    // =========================================================================

    /** 生成修正规则的存储键：`原始动作名@上下文`。 */
    private fun correctionKey(actionName: String, context: String): String =
        "${actionName}@$context"

    /** 生成动作偏好的存储键：`可互换组ID@上下文`。 */
    private fun preferenceKey(groupId: String, context: String): String =
        "${groupId}@$context"

    /** 生成错误模式的存储键：`动作类型名@上下文`。 */
    private fun patternKey(actionType: ActionType, context: String): String =
        "${actionType.name}@$context"

    /**
     * 查找某动作类型所属的可互换组。
     *
     * @param actionType 动作类型
     * @return 包含该类型的可互换组；不属于任何组时返回 null
     */
    private fun findInterchangeableGroup(actionType: ActionType): Set<ActionType>? =
        interchangeableGroups.firstOrNull { actionType in it }

    /**
     * 为可互换组生成稳定的组标识。
     *
     * 使用组内动作类型名称排序后拼接，保证同一组始终生成相同标识。
     *
     * @param group 可互换组
     * @return 组标识字符串
     */
    private fun groupIdOf(group: Set<ActionType>): String =
        group.joinToString("_") { it.name }.let { "grp_${it.hashCode()}" }

    // =========================================================================
    //  内部辅助方法 —— 置信度衰减计算
    // =========================================================================

    /**
     * 对单条修正规则执行惰性衰减计算（不落库，仅修改内存值）。
     *
     * 根据 [rule] 的 [CorrectionRule.lastUsed] 与当前时间的差值，计算应衰减的
     * 置信度并更新到 [CorrectionRule.confidence]。
     */
    private fun applyDecayToRule(rule: CorrectionRule) {
        val decayed = computeDecayedConfidence(rule.confidence, rule.lastUsed)
        if (decayed != rule.confidence) {
            rule.confidence = decayed
        }
    }

    /** 与 [applyDecayToRule] 相同，但静默执行（不输出日志），用于统计遍历。 */
    private fun applyDecayToRuleSilent(rule: CorrectionRule) {
        rule.confidence = computeDecayedConfidence(rule.confidence, rule.lastUsed)
    }

    /** 对单条动作偏好执行惰性衰减计算。 */
    private fun applyDecayToPreference(pref: ActionPreference) {
        val decayed = computeDecayedConfidence(pref.confidence, pref.lastUsed)
        if (decayed != pref.confidence) {
            pref.confidence = decayed
        }
    }

    /** 与 [applyDecayToPreference] 相同，但静默执行，用于统计遍历。 */
    private fun applyDecayToPreferenceSilent(pref: ActionPreference) {
        pref.confidence = computeDecayedConfidence(pref.confidence, pref.lastUsed)
    }

    /** 对单条错误模式执行惰性衰减计算。 */
    private fun applyDecayToPattern(pattern: LearnedPattern) {
        val decayed = computeDecayedConfidence(pattern.confidence, pattern.lastUsed)
        if (decayed != pattern.confidence) {
            pattern.confidence = decayed
        }
    }

    /**
     * 计算衰减后的置信度。
     *
     * 衰减规则：
     * - 若距上次使用未超过 [DECAY_THRESHOLD_MS]，不衰减。
     * - 超过阈值后，每经过一个 [DECAY_INTERVAL_MS] 衰减 [DECAY_AMOUNT]。
     * - 衰减结果不低于 0.0。
     *
     * @param confidence 原始置信度
     * @param lastUsed   最后使用时间戳
     * @return 衰减后的置信度
     */
    private fun computeDecayedConfidence(confidence: Double, lastUsed: Long): Double {
        val now = System.currentTimeMillis()
        val idleMs = now - lastUsed
        if (idleMs <= DECAY_THRESHOLD_MS) return confidence

        // 超过阈值的部分，按间隔衰减
        val decayPeriods = ((idleMs - DECAY_THRESHOLD_MS) / DECAY_INTERVAL_MS).toInt()
        if (decayPeriods <= 0) return confidence

        val decayed = confidence - decayPeriods * DECAY_AMOUNT
        return decayed.coerceAtLeast(0.0)
    }

    // =========================================================================
    //  内部辅助方法 —— LRU 淘汰
    // =========================================================================

    /** 修正规则超出上限时按 LRU 淘汰最久未使用的条目。 */
    private fun evictCorrections() {
        while (correctionRules.size > MAX_CORRECTION_RULES) {
            val oldest = correctionRules.entries.minByOrNull { it.value.lastUsed }
            if (oldest == null) break
            correctionRules.remove(oldest.key)
        }
    }

    /** 动作偏好超出上限时按 LRU 淘汰最久未使用的条目。 */
    private fun evictPreferences() {
        while (actionPreferences.size > MAX_ACTION_PREFERENCES) {
            val oldest = actionPreferences.entries.minByOrNull { it.value.lastUsed }
            if (oldest == null) break
            actionPreferences.remove(oldest.key)
        }
    }

    /** 错误模式超出上限时按 LRU 淘汰最久未使用的条目。 */
    private fun evictPatterns() {
        while (learnedPatterns.size > MAX_LEARNED_PATTERNS) {
            val oldest = learnedPatterns.entries.minByOrNull { it.value.lastUsed }
            if (oldest == null) break
            learnedPatterns.remove(oldest.key)
        }
    }
}
