package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ScreenChangePredictor —— 屏幕变化预测器。
 *
 * 核心理念：在执行一个动作之前，先预测动作执行后屏幕会发生什么变化。
 * 如果能准确预判「点击这个按钮会跳到哪个页面」「输入文本后输入框会出现什么内容」，
 * 就能在动作执行后快速验证结果是否符合预期，而不必每次都重新做完整的屏幕分析。
 * 更进一步，当实际变化与预测严重偏离时，可以立即发现异常（如点击没反应、
 * 页面跳错、弹出了意料之外的对话框），从而触发重试或上报错误。
 *
 * 预测器的四大职能：
 *
 * 1. **动作-变化映射**（Action-to-Change Mapping）
 *    维护一张「动作类型 → 期望变化类型」的默认映射表。例如：
 *    - APP_OPEN → PAGE_TRANSITION（切换到新应用页面）
 *    - SCREEN_SWIPE → SCROLL（内容滚动翻页）
 *    - SCREEN_INPUT → TEXT_INPUT（文本出现在输入框）
 *    - SCREEN_WAIT → NO_CHANGE（等待不会改变屏幕）
 *    这是「零经验」时的先验预测，仅依赖动作语义。
 *
 * 2. **变化预测**（Change Prediction）
 *    给定一个动作和当前屏幕状态，综合以下信号预测下一屏的样子：
 *    - 默认映射给出的变化类型（先验）
 *    - 转移图中「该状态 + 该动作」的历史转移记录（经验）
 *    - 状态熟悉度（该状态被观察过多少次）
 *    输出一个 [PredictedChange]，包含变化类型、预测目标状态、置信度和说明。
 *
 * 3. **预测验证与置信度追踪**（Prediction Verification & Confidence Tracking）
 *    动作执行后，将预测结果与实际屏幕状态对比，判定预测是否命中，
 *    并持续更新各动作类型的预测准确率（[PredictionModel]）。准确率越高，
 *    后续预测的置信度越高；反之则降低，使系统自适应地「学会」哪些动作
 *    的效果是稳定的、哪些是不确定的。
 *
 * 4. **异常检测**（Anomaly Detection）
 *    当实际变化与预测严重偏离时（如预测「页面跳转」但实际「无变化」，
 *    或预测高置信度目标状态却被跳到了一个完全陌生的状态），
 *    [detectAnomaly] 会返回一个 [AnomalyResult]，标记异常等级与原因，
 *    供上层决定是否重试、回滚或上报。
 *
 * 转移图（Transition Graph）
 * ----
 * 预测器内部维护一张有向图：节点 = 屏幕状态（[ScreenState]），
 * 边 = 转移记录（[TransitionEdge]），记录「状态 A + 动作 X → 状态 B」
 * 的出现频率、最后观察时间与平均置信度。随着使用积累，这张图会逐渐
 * 覆盖用户常用的操作路径，使预测从「语义猜测」进化为「经验复现」。
 *
 * 线程安全
 * ----
 * 所有存储结构均使用 [ConcurrentHashMap]，可被多线程并发调用。
 * 统计计数使用 @Volatile + private set，保证可见性的同时禁止外部修改。
 * 典型场景：UI 线程发起预测、后台执行线程在动作完成后回调验证与记录。
 *
 * 使用方式
 * ----
 * ```
 * val predictor = ScreenChangePredictor()
 *
 * // 执行动作前：预测
 * val prediction = predictor.predictChange(action, currentState)
 * executeAction(action)
 * val nextState = captureScreenState()
 *
 * // 执行动作后：验证 + 记录
 * val verification = predictor.verifyPrediction(prediction, nextState, currentState)
 * predictor.recordTransition(currentState, action, nextState)
 *
 * // 异常检测
 * val anomaly = predictor.detectAnomaly(prediction, nextState, currentState)
 * if (anomaly.isAnomaly) {
 *     Log.w(TAG, "屏幕变化异常: ${anomaly.reason}")
 * }
 * ```
 */
class ScreenChangePredictor {

    private val tag = "ScreenChangePredictor"

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 屏幕变化类型。
     *
     * 描述动作执行后屏幕发生的变化类别，用于预测与验证。
     *
     * @param displayName 人类可读的变化类型名称，用于日志与 UI 展示。
     */
    enum class ChangeType(val displayName: String) {
        /** 页面跳转：切换到了不同的页面/Activity（如打开新应用、点击进入详情页）。 */
        PAGE_TRANSITION("页面跳转"),

        /** 内容更新：同一页面内内容发生了变化（如列表刷新、数据加载完成）。 */
        CONTENT_UPDATE("内容更新"),

        /** 元素出现：屏幕上新出现了元素（如弹窗、加载动画、新增列表项）。 */
        ELEMENT_APPEAR("元素出现"),

        /** 元素消失：屏幕上原有元素消失了（如弹窗关闭、加载动画结束）。 */
        ELEMENT_DISAPPEAR("元素消失"),

        /** 文本输入：输入框中出现了用户输入的文本。 */
        TEXT_INPUT("文本输入"),

        /** 滚动翻页：页面内容发生了滚动（如上滑查看更多、左右翻页）。 */
        SCROLL("滚动翻页"),

        /** 无变化：屏幕状态与动作执行前完全一致（如等待、截图操作）。 */
        NO_CHANGE("无变化"),

        /** 未知变化：无法归类的变化类型，通常因为信息不足或状态不可比较。 */
        UNKNOWN("未知变化")
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 屏幕状态快照。
     *
     * 表示某个时刻屏幕的完整可观测状态，用作转移图的节点。
     * 两个 [ScreenState] 的 [stateId] 相同即视为同一状态（内容相同）。
     *
     * @param stateId 状态唯一标识（基于内容计算的 SHA-256 截断哈希）
     * @param packageName 当前前台应用包名，可为 null（桌面）
     * @param activity 当前 Activity 类名，可为 null
     * @param screenText 当前屏幕可见文本（用于内容比对与差异分析）
     * @param elementCount 屏幕上可交互元素的数量
     * @param timestamp 该状态被采集时的时间戳（毫秒）
     */
    data class ScreenState(
        val stateId: String,
        val packageName: String?,
        val activity: String?,
        val screenText: String,
        val elementCount: Int,
        val timestamp: Long
    ) {
        companion object {
            /**
             * 从原始屏幕信息构造一个 [ScreenState]，自动计算 [stateId]。
             *
             * @param packageName 前台应用包名
             * @param activity Activity 类名
             * @param screenText 屏幕可见文本
             * @param elementCount 可交互元素数量
             * @param timestamp 采集时间戳（毫秒），默认为当前时间
             * @return 自动计算了 stateId 的 [ScreenState]
             */
            fun from(
                packageName: String?,
                activity: String?,
                screenText: String,
                elementCount: Int,
                timestamp: Long = System.currentTimeMillis()
            ): ScreenState {
                val id = computeStateId(packageName, activity, screenText, elementCount)
                return ScreenState(id, packageName, activity, screenText, elementCount, timestamp)
            }
        }
    }

    /**
     * 预测的变化结果。
     *
     * 由 [predictChange] 产生，描述动作执行后屏幕预期的变化。
     *
     * @param changeType 预测的变化类型
     * @param predictedStateId 预测的目标状态 ID，若无足够历史则可能为 null
     * @param confidence 置信度（0.0-1.0），越高越可信
     * @param description 预测依据的人类可读说明
     * @param affectedElements 预期受影响的元素描述列表（如被点击的文本、输入的内容）
     * @param basedOnHistory 是否基于历史转移记录（false 表示仅基于默认映射先验）
     * @param actionType 触发该预测的动作类型，用于验证时更新对应预测模型
     */
    data class PredictedChange(
        val changeType: ChangeType,
        val predictedStateId: String?,
        val confidence: Float,
        val description: String,
        val affectedElements: List<String> = emptyList(),
        val basedOnHistory: Boolean = false,
        val actionType: ActionType? = null
    )

    /**
     * 转移图的一条边。
     *
     * 表示「从状态 [fromStateId] 执行动作后转移到状态 [toStateId]」的一次经验记录。
     * 同一组 (fromStateId, actionSignature) 可能对应多条边（不同的目标状态），
     * 频率最高的那条即为最可能的转移目标。
     *
     * @param fromStateId 起始状态 ID
     * @param toStateId 目标状态 ID
     * @param actionType 动作类型
     * @param actionSignature 动作签名（动作类型 + 关键参数的归一化表示）
     * @param frequency 该转移被观察到的次数
     * @param lastSeen 最后一次观察到该转移的时间戳（毫秒）
     * @param avgConfidence 该转移的平均预测置信度（历史验证均值）
     * @param lastChangeType 最后一次观察到该转移时的实际变化类型
     */
    data class TransitionEdge(
        val fromStateId: String,
        val toStateId: String,
        val actionType: ActionType,
        val actionSignature: String,
        var frequency: Int = 1,
        var lastSeen: Long = System.currentTimeMillis(),
        var avgConfidence: Float = 0f,
        var lastChangeType: ChangeType = ChangeType.UNKNOWN
    )

    /**
     * 单个动作类型的预测模型统计。
     *
     * 按动作类型维度追踪预测准确率与变化类型分布，用于评估该动作类型的
     * 可预测性并指导后续置信度计算。
     *
     * @param actionType 动作类型
     * @param totalPredictions 该动作类型的累计预测次数
     * @param correctPredictions 预测命中（变化类型匹配）的次数
     * @param exactStateMatches 预测目标状态精确匹配的次数
     * @param changeTypeDistribution 实际变化类型分布（变化类型 → 出现次数）
     */
    data class PredictionModel(
        val actionType: ActionType,
        var totalPredictions: Int = 0,
        var correctPredictions: Int = 0,
        var exactStateMatches: Int = 0,
        val changeTypeDistribution: ConcurrentHashMap<ChangeType, Int> = ConcurrentHashMap()
    ) {
        /** 该动作类型的预测准确率（0.0-1.0），基于变化类型匹配。 */
        val accuracy: Float
            get() = if (totalPredictions > 0) correctPredictions.toFloat() / totalPredictions else 0f

        /** 该动作类型的目标状态精确匹配率（0.0-1.0）。 */
        val stateMatchRate: Float
            get() = if (totalPredictions > 0) exactStateMatches.toFloat() / totalPredictions else 0f

        /** 该动作类型最常产生的变化类型（众数），无数据时返回 [ChangeType.UNKNOWN]。 */
        val dominantChangeType: ChangeType
            get() = changeTypeDistribution.maxByOrNull { it.value }?.key ?: ChangeType.UNKNOWN
    }

    /**
     * 异常检测结果。
     *
     * 由 [detectAnomaly] 产生，描述实际变化与预测之间的偏离程度。
     *
     * @param isAnomaly 是否判定为异常
     * @param severity 异常严重等级（0.0-1.0），越高越严重
     * @param predictedType 预测的变化类型
     * @param actualType 实际的变化类型
     * @param confidenceAtPrediction 预测时的置信度（高置信度下的偏离更可疑）
     * @param reason 异常原因的人类可读说明
     */
    data class AnomalyResult(
        val isAnomaly: Boolean,
        val severity: Float,
        val predictedType: ChangeType,
        val actualType: ChangeType,
        val confidenceAtPrediction: Float,
        val reason: String
    )

    /**
     * 预测验证结果。
     *
     * 由 [verifyPrediction] 产生，记录单次预测的命中情况。
     *
     * @param correct 变化类型是否匹配（部分命中）
     * @param exactStateMatch 目标状态是否精确匹配（完全命中）
     * @param predictedType 预测的变化类型
     * @param actualType 实际的变化类型
     * @param predictedStateId 预测的目标状态 ID
     * @param actualStateId 实际的目标状态 ID
     * @param deviation 偏离程度（0.0-1.0），0 表示完全吻合
     */
    data class VerificationResult(
        val correct: Boolean,
        val exactStateMatch: Boolean,
        val predictedType: ChangeType,
        val actualType: ChangeType,
        val predictedStateId: String?,
        val actualStateId: String,
        val deviation: Float
    )

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 转移图：键 = 起始状态 ID + 动作签名，值 = 该组合的所有转移边列表。 */
    private val transitionGraph = ConcurrentHashMap<String, MutableList<TransitionEdge>>()

    /** 状态注册表：键 = 状态 ID，值 = [ScreenState] 快照，用于回查状态详情。 */
    private val stateRegistry = ConcurrentHashMap<String, ScreenState>()

    /** 预测模型表：键 = 动作类型，值 = 该动作类型的预测统计模型。 */
    private val predictionModels = ConcurrentHashMap<ActionType, PredictionModel>()

    /** 默认动作-变化映射表：键 = 动作类型，值 = 该动作的先验变化类型。 */
    private val defaultChangeMap: ConcurrentHashMap<ActionType, ChangeType> =
        ConcurrentHashMap(buildDefaultChangeMap())

    // ============================================================
    // 配置常量
    // ============================================================

    /** 转移图每组边的最大保留数量（超出按频率淘汰最低的）。 */
    private val maxEdgesPerGroup = 20

    /** 状态注册表最大容量（超出按最久未访问淘汰）。 */
    private val maxStates = 500

    /** 转移图总组数上限（LRU 淘汰）。 */
    private val maxGroups = 1000

    /** 最低有效置信度阈值，低于此值视为无可靠预测。 */
    private val minConfidence = 0.1f

    /** 异常判定阈值：偏离程度超过此值且置信度足够高时判定为异常。 */
    private val anomalyThreshold = 0.6f

    /** 触发异常判定的最低预测置信度（低置信度的预测偏离不算异常）。 */
    private val anomalyMinConfidence = 0.4f

    // 预测置信度计算权重（之和为 1.0）
    private val weightBase = 0.4f       // 默认映射先验权重
    private val weightHistory = 0.45f   // 历史转移一致性权重
    private val weightFamiliarity = 0.15f // 状态熟悉度权重

    // ============================================================
    // 统计计数
    // ============================================================

    /** 累计预测次数。 */
    @Volatile
    var totalPredictions: Int = 0
        private set

    /** 累计预测命中次数（变化类型匹配）。 */
    @Volatile
    var totalCorrectPredictions: Int = 0
        private set

    /** 累计目标状态精确匹配次数。 */
    @Volatile
    var totalExactStateMatches: Int = 0
        private set

    /** 累计记录转移次数。 */
    @Volatile
    var totalTransitionsRecorded: Int = 0
        private set

    /** 累计检测到的异常次数。 */
    @Volatile
    var totalAnomaliesDetected: Int = 0
        private set

    // ============================================================
    // 默认动作-变化映射表构建
    // ============================================================

    /**
     * 构建「动作类型 → 期望变化类型」的默认映射表。
     *
     * 这是零经验时的先验知识，基于各动作类型的语义推断其最可能的屏幕变化：
     * - 打开/关闭应用、按键返回 → 页面跳转
     * - 滑动、滚动到文本 → 滚动翻页
     * - 输入文本 → 文本输入
     * - 点击类操作 → 内容更新（点击通常会触发页面内变化）
     * - 等待、截图、获取文本、检测文本 → 无变化（只读操作不改变屏幕）
     */
    private fun buildDefaultChangeMap(): Map<ActionType, ChangeType> = mapOf(
        // 应用级操作 → 页面跳转
        ActionType.APP_OPEN to ChangeType.PAGE_TRANSITION,
        ActionType.APP_CLOSE to ChangeType.PAGE_TRANSITION,
        ActionType.APP_SEARCH to ChangeType.PAGE_TRANSITION,
        ActionType.APP_INSTALL to ChangeType.CONTENT_UPDATE,
        ActionType.APP_UNINSTALL to ChangeType.PAGE_TRANSITION,
        ActionType.APP_LIST to ChangeType.CONTENT_UPDATE,

        // 滑动/滚动 → 滚动翻页
        ActionType.SCREEN_SWIPE to ChangeType.SCROLL,
        ActionType.SCREEN_SCROLL_TO_TEXT to ChangeType.SCROLL,

        // 输入 → 文本输入
        ActionType.SCREEN_INPUT to ChangeType.TEXT_INPUT,
        ActionType.CLIPBOARD_PASTE to ChangeType.TEXT_INPUT,

        // 点击类 → 内容更新（点击通常触发页面内变化或弹窗）
        ActionType.SCREEN_CLICK to ChangeType.CONTENT_UPDATE,
        ActionType.SCREEN_CLICK_TEXT to ChangeType.CONTENT_UPDATE,
        ActionType.SCREEN_LONG_CLICK to ChangeType.CONTENT_UPDATE,
        ActionType.SCREEN_DOUBLE_CLICK to ChangeType.CONTENT_UPDATE,
        ActionType.SCREEN_FIND_AND_CLICK to ChangeType.CONTENT_UPDATE,

        // 按键 → 页面跳转（返回键/桌面键通常切换页面）
        ActionType.SCREEN_KEY to ChangeType.PAGE_TRANSITION,

        // 只读/不改变屏幕的操作 → 无变化
        ActionType.SCREEN_WAIT to ChangeType.NO_CHANGE,
        ActionType.SCREEN_SCREENSHOT to ChangeType.NO_CHANGE,
        ActionType.SCREEN_GET_TEXT to ChangeType.NO_CHANGE,
        ActionType.SCREEN_TEXT_EXISTS to ChangeType.NO_CHANGE,

        // 系统操作 → 内容更新
        ActionType.SYSTEM_GET_INFO to ChangeType.NO_CHANGE,
        ActionType.SYSTEM_KILL_PROCESS to ChangeType.NO_CHANGE,
        ActionType.SYSTEM_CLEAR_CACHE to ChangeType.CONTENT_UPDATE,
        ActionType.SYSTEM_SET_VOLUME to ChangeType.NO_CHANGE,
        ActionType.SYSTEM_SET_BRIGHTNESS to ChangeType.NO_CHANGE,

        // 剪贴板复制 → 无变化（屏幕不变）
        ActionType.CLIPBOARD_COPY to ChangeType.NO_CHANGE,

        // 媒体控制 → 无变化（通常不改变可见界面）
        ActionType.MEDIA_CONTROL to ChangeType.NO_CHANGE,

        // 文件/通知/Shell → 无变化（不影响当前屏幕）
        ActionType.SHELL_EXEC to ChangeType.UNKNOWN,
        ActionType.FILE_READ to ChangeType.NO_CHANGE,
        ActionType.FILE_WRITE to ChangeType.NO_CHANGE,
        ActionType.NOTIFY_READ to ChangeType.NO_CHANGE,
        ActionType.NOTIFY_SEND to ChangeType.NO_CHANGE,
        ActionType.TIMER_SET to ChangeType.NO_CHANGE,

        // 直接回答 → 无变化（不操作手机）
        ActionType.ANSWER to ChangeType.NO_CHANGE
    )

    // ============================================================
    // 核心方法：变化预测
    // ============================================================

    /**
     * 预测动作执行后屏幕的变化。
     *
     * 预测流程：
     * 1. 从 [defaultChangeMap] 获取该动作类型的先验变化类型。
     * 2. 构建动作签名，在转移图中查找「当前状态 + 该动作」的历史转移记录。
     * 3. 若有历史记录，取频率最高的目标状态作为预测目标，并计算一致性。
     * 4. 综合先验置信度、历史一致性、状态熟悉度，加权得到最终置信度。
     * 5. 提取受影响元素（如被点击的文本、输入的内容）。
     *
     * @param action 即将执行的动作
     * @param currentState 当前屏幕状态
     * @return 预测的变化结果；无法识别动作类型时返回 [ChangeType.UNKNOWN] 的低置信预测
     */
    fun predictChange(action: ClawAction, currentState: ScreenState): PredictedChange {
        totalPredictions++

        val actionType = action.type
        if (actionType == null) {
            Log.w(tag, "无法识别动作类型: ${action.actionName}，返回未知预测")
            return PredictedChange(
                changeType = ChangeType.UNKNOWN,
                predictedStateId = null,
                confidence = 0f,
                description = "动作类型无法识别: ${action.actionName}",
                affectedElements = emptyList(),
                basedOnHistory = false,
                actionType = null
            )
        }

        // 1. 获取先验变化类型
        val priorChangeType = defaultChangeMap[actionType] ?: ChangeType.UNKNOWN
        val baseConfidence = baseConfidenceFor(priorChangeType)

        // 2. 构建动作签名并查找历史转移
        val signature = buildActionSignature(action)
        val graphKey = transitionKey(currentState.stateId, signature)
        val historyEdges = transitionGraph[graphKey]

        // 3. 计算预测结果
        val predictedStateId: String?
        val historicalConfidence: Float
        val basedOnHistory: Boolean
        val description: String

        if (historyEdges != null && historyEdges.isNotEmpty()) {
            // 有历史记录：取频率最高的目标状态
            val bestEdge = historyEdges.maxByOrNull { it.frequency }
            predictedStateId = bestEdge?.toStateId
            val totalFreq = historyEdges.sumOf { it.frequency }
            val consistency = if (totalFreq > 0 && bestEdge != null) {
                bestEdge.frequency.toFloat() / totalFreq
            } else {
                0f
            }
            historicalConfidence = consistency
            basedOnHistory = true
            val targetDesc = bestEdge?.let { stateRegistry[it.toStateId] }?.let { state ->
                "${state.packageName ?: "?"}/${state.activity ?: "?"}"
            } ?: "未知状态"
            description = "历史转移(${bestEdge?.frequency ?: 0}/$totalFreq) → $targetDesc" +
                    " | 先验: ${priorChangeType.displayName}"
        } else {
            // 无历史记录：仅基于先验
            predictedStateId = null
            historicalConfidence = 0f
            basedOnHistory = false
            description = "无历史记录，基于先验映射: ${actionType.description} → ${priorChangeType.displayName}"
        }

        // 4. 计算状态熟悉度
        val familiarity = computeFamiliarity(currentState.stateId, signature)

        // 5. 加权计算最终置信度
        val confidence = if (basedOnHistory) {
            (baseConfidence * weightBase +
                    historicalConfidence * weightHistory +
                    familiarity * weightFamiliarity).coerceIn(0f, 1f)
        } else {
            // 无历史时，先验置信度折半（仅凭语义猜测）
            (baseConfidence * 0.5f).coerceIn(0f, 1f)
        }

        // 6. 提取受影响元素
        val affectedElements = extractAffectedElements(action)

        // 7. 累计该动作类型的预测次数
        getOrCreateModel(actionType).totalPredictions++

        Log.d(tag, "预测: ${actionType.name} → ${priorChangeType.displayName}" +
                " (${(confidence * 100).toInt()}%)" +
                (if (basedOnHistory) " [历史]" else " [先验]"))

        return PredictedChange(
            changeType = priorChangeType,
            predictedStateId = predictedStateId,
            confidence = confidence,
            description = description,
            affectedElements = affectedElements,
            basedOnHistory = basedOnHistory,
            actionType = actionType
        )
    }

    // ============================================================
    // 核心方法：转移记录
    // ============================================================

    /**
     * 记录一次屏幕转移（状态 A + 动作 → 状态 B）。
     *
     * 将起始状态与目标状态注册到状态注册表，在转移图中新增或更新对应的转移边，
     * 并更新该动作类型的变化类型分布统计。该方法应在动作执行完成、
     * 采集到目标屏幕状态后调用。
     *
     * @param fromState 动作执行前的屏幕状态
     * @param action 执行的动作
     * @param toState 动作执行后的屏幕状态
     */
    fun recordTransition(fromState: ScreenState, action: ClawAction, toState: ScreenState) {
        val actionType = action.type ?: run {
            Log.w(tag, "记录转移时动作类型无法识别: ${action.actionName}，跳过")
            return
        }

        // 1. 注册起始与目标状态
        stateRegistry[fromState.stateId] = fromState
        stateRegistry[toState.stateId] = toState

        // 2. 计算实际变化类型
        val actualChangeType = computeChangeType(fromState, toState)

        // 3. 构建转移边并更新转移图
        val signature = buildActionSignature(action)
        val graphKey = transitionKey(fromState.stateId, signature)
        val now = System.currentTimeMillis()

        transitionGraph.compute(graphKey) { _, edges ->
            val list = edges ?: mutableListOf()
            val existing = list.find { it.toStateId == toState.stateId }
            if (existing != null) {
                existing.frequency++
                existing.lastSeen = now
                existing.lastChangeType = actualChangeType
            } else {
                list.add(
                    TransitionEdge(
                        fromStateId = fromState.stateId,
                        toStateId = toState.stateId,
                        actionType = actionType,
                        actionSignature = signature,
                        frequency = 1,
                        lastSeen = now,
                        avgConfidence = 0f,
                        lastChangeType = actualChangeType
                    )
                )
            }
            // 超容量时按频率淘汰最低的边
            if (list.size > maxEdgesPerGroup) {
                list.sortedByDescending { it.frequency }
                    .drop(maxEdgesPerGroup)
                    .forEach { edge -> list.removeIf { it.toStateId == edge.toStateId } }
            }
            list
        }

        // 4. 更新预测模型的变化类型分布
        val model = getOrCreateModel(actionType)
        model.changeTypeDistribution.merge(actualChangeType, 1) { old, new -> old + new }

        // 5. 容量淘汰
        evictStatesIfNeeded()
        evictGroupsIfNeeded()

        totalTransitionsRecorded++

        Log.d(tag, "记录转移: ${actionType.name}" +
                " | ${fromState.stateId.take(8)} → ${toState.stateId.take(8)}" +
                " | 变化: ${actualChangeType.displayName}")
    }

    // ============================================================
    // 核心方法：预测验证
    // ============================================================

    /**
     * 验证预测结果是否与实际变化吻合，并更新预测模型。
     *
     * 判定规则：
     * - **变化类型匹配**（correct）：预测的变化类型与实际变化类型一致 → 部分命中。
     * - **目标状态精确匹配**（exactStateMatch）：预测的目标状态 ID 与实际状态 ID 一致 → 完全命中。
     * - **偏离程度**（deviation）：0.0 表示完全吻合，1.0 表示完全偏离。
     *
     * 验证后会更新对应动作类型的 [PredictionModel] 统计，以及全局命中计数。
     * 该方法应在动作执行后、采集到实际屏幕状态时调用。
     *
     * @param prediction [predictChange] 返回的预测结果
     * @param actualState 动作执行后的实际屏幕状态
     * @param expectedFromState 动作执行前的屏幕状态（用于计算实际变化类型）
     * @return 验证结果
     */
    fun verifyPrediction(
        prediction: PredictedChange,
        actualState: ScreenState,
        expectedFromState: ScreenState
    ): VerificationResult {
        val actualChangeType = computeChangeType(expectedFromState, actualState)
        val typeMatch = prediction.changeType == actualChangeType
        val stateMatch = prediction.predictedStateId != null &&
                prediction.predictedStateId == actualState.stateId

        // 计算偏离程度
        val deviation = computeDeviation(
            predictedType = prediction.changeType,
            actualType = actualChangeType,
            typeMatch = typeMatch,
            stateMatch = stateMatch,
            basedOnHistory = prediction.basedOnHistory
        )

        // 更新全局计数
        totalCorrectPredictions += if (typeMatch) 1 else 0
        totalExactStateMatches += if (stateMatch) 1 else 0

        // 更新对应动作类型的预测模型
        prediction.actionType?.let { actType ->
            val model = getOrCreateModel(actType)
            if (typeMatch) model.correctPredictions++
            if (stateMatch) model.exactStateMatches++
            model.changeTypeDistribution.merge(actualChangeType, 1) { old, new -> old + new }
        }

        // 更新转移边的平均置信度
        if (prediction.basedOnHistory && prediction.predictedStateId != null) {
            updateEdgeConfidence(expectedFromState.stateId, prediction.predictedStateId, prediction.confidence)
        }

        Log.d(tag, "验证预测: 类型${if (typeMatch) "匹配" else "不匹配"}" +
                " 状态${if (stateMatch) "匹配" else "不匹配"}" +
                " 偏离=${(deviation * 100).toInt()}%" +
                " | 预测:${prediction.changeType.displayName} 实际:${actualChangeType.displayName}")

        return VerificationResult(
            correct = typeMatch,
            exactStateMatch = stateMatch,
            predictedType = prediction.changeType,
            actualType = actualChangeType,
            predictedStateId = prediction.predictedStateId,
            actualStateId = actualState.stateId,
            deviation = deviation
        )
    }

    // ============================================================
    // 核心方法：转移图查询
    // ============================================================

    /**
     * 获取完整的转移图（用于 UI 展示、调试或导出）。
     *
     * 返回一个不可变的映射：键 = 转移组键（起始状态 ID + 动作签名），
     * 值 = 该组下所有转移边的列表（按频率降序）。
     *
     * @return 转移图的快照副本
     */
    fun getTransitionGraph(): Map<String, List<TransitionEdge>> {
        val snapshot = LinkedHashMap<String, List<TransitionEdge>>()
        transitionGraph.forEach { (key, edges) ->
            synchronized(edges) {
                snapshot[key] = edges.sortedByDescending { it.frequency }.toList()
            }
        }
        return snapshot
    }

    /**
     * 获取从指定状态出发的所有转移边。
     *
     * @param stateId 起始状态 ID
     * @return 该状态的所有出边列表（按频率降序），无记录时返回空列表
     */
    fun getTransitionsFrom(stateId: String): List<TransitionEdge> {
        val results = mutableListOf<TransitionEdge>()
        transitionGraph.forEach { (key, edges) ->
            if (key.startsWith(stateId + "|")) {
                synchronized(edges) {
                    results.addAll(edges.sortedByDescending { it.frequency })
                }
            }
        }
        return results
    }

    /**
     * 获取已注册的所有屏幕状态。
     *
     * @return 状态注册表的快照列表
     */
    fun getRegisteredStates(): List<ScreenState> =
        stateRegistry.values.toList().sortedByDescending { it.timestamp }

    // ============================================================
    // 核心方法：异常检测
    // ============================================================

    /**
     * 检测实际屏幕变化是否偏离预测，判定是否为异常。
     *
     * 异常判定逻辑：
     * 1. 计算实际变化类型与预测变化类型的偏离程度。
     * 2. 若预测基于历史且目标状态不匹配，额外增加偏离分。
     * 3. 高置信度预测的偏离比低置信度预测的偏离更可疑（加权放大）。
     * 4. 当偏离程度超过 [anomalyThreshold] 且预测置信度不低于 [anomalyMinConfidence]时，
     *    判定为异常。
     *
     * 异常可能意味着：点击无反应、页面跳转错误、弹出了意料之外的弹窗、
     * 网络加载失败导致页面未变化等，应触发重试或错误上报。
     *
     * @param prediction [predictChange] 返回的预测结果
     * @param actualState 动作执行后的实际屏幕状态
     * @param expectedFromState 动作执行前的屏幕状态
     * @return 异常检测结果
     */
    fun detectAnomaly(
        prediction: PredictedChange,
        actualState: ScreenState,
        expectedFromState: ScreenState
    ): AnomalyResult {
        val actualChangeType = computeChangeType(expectedFromState, actualState)
        val typeMatch = prediction.changeType == actualChangeType
        val stateMatch = prediction.predictedStateId != null &&
                prediction.predictedStateId == actualState.stateId

        // 基础偏离度
        var severity = computeDeviation(
            predictedType = prediction.changeType,
            actualType = actualChangeType,
            typeMatch = typeMatch,
            stateMatch = stateMatch,
            basedOnHistory = prediction.basedOnHistory
        )

        // 高置信度下的偏离加权放大（越自信却错了，越可疑）
        if (!typeMatch && prediction.confidence >= anomalyMinConfidence) {
            severity = (severity * (0.5f + prediction.confidence * 0.5f)).coerceIn(0f, 1f)
        }

        // 基于历史预测但目标状态完全不匹配，额外加重
        if (prediction.basedOnHistory && !stateMatch) {
            severity = (severity + 0.2f).coerceIn(0f, 1f)
        }

        val isAnomaly = severity >= anomalyThreshold && prediction.confidence >= anomalyMinConfidence

        if (isAnomaly) {
            totalAnomaliesDetected++
        }

        val reason = buildString {
            append("预测:${prediction.changeType.displayName}")
            append(" 实际:${actualChangeType.displayName}")
            if (!typeMatch) append(" [变化类型不匹配]")
            if (prediction.basedOnHistory && !stateMatch) append(" [目标状态偏离历史]")
            append(" 偏离=${(severity * 100).toInt()}%")
            append(" 置信度=${(prediction.confidence * 100).toInt()}%")
        }

        if (isAnomaly) {
            Log.w(tag, "检测到异常: $reason")
        }

        return AnomalyResult(
            isAnomaly = isAnomaly,
            severity = severity,
            predictedType = prediction.changeType,
            actualType = actualChangeType,
            confidenceAtPrediction = prediction.confidence,
            reason = reason
        )
    }

    // ============================================================
    // 核心方法：预测准确率查询
    // ============================================================

    /**
     * 获取整体预测准确率（0.0-1.0）。
     *
     * 基于变化类型匹配统计：命中次数 / 总预测次数。
     * 无预测记录时返回 0。
     *
     * @return 预测准确率
     */
    fun getPredictionAccuracy(): Float {
        return if (totalPredictions > 0) {
            totalCorrectPredictions.toFloat() / totalPredictions
        } else {
            0f
        }
    }

    /**
     * 获取目标状态精确匹配率（0.0-1.0）。
     *
     * @return 状态精确匹配率
     */
    fun getStateMatchRate(): Float {
        return if (totalPredictions > 0) {
            totalExactStateMatches.toFloat() / totalPredictions
        } else {
            0f
        }
    }

    /**
     * 获取各动作类型的预测模型统计（用于 UI 展示与调试）。
     *
     * @return 动作类型到预测模型的映射快照
     */
    fun getPredictionModels(): Map<ActionType, PredictionModel> {
        return predictionModels.toMap()
    }

    /**
     * 获取预测器统计摘要（用于 UI 展示与调试）。
     *
     * 包含预测总数、命中率、状态匹配率、转移图规模、状态注册表规模、异常检测次数。
     */
    fun getSummary(): String {
        val accuracy = if (totalPredictions > 0) {
            "%.1f%%".format(getPredictionAccuracy() * 100)
        } else {
            "N/A"
        }
        val stateRate = if (totalPredictions > 0) {
            "%.1f%%".format(getStateMatchRate() * 100)
        } else {
            "N/A"
        }
        val groupCount = transitionGraph.size
        val edgeCount = transitionGraph.values.sumOf { it.size }
        return "屏幕变化预测器: 预测${totalPredictions}次" +
                " | 准确率:$accuracy 状态匹配:$stateRate" +
                " | 转移图:${groupCount}组/${edgeCount}边" +
                " | 状态表:${stateRegistry.size}/${maxStates}" +
                " | 异常:${totalAnomaliesDetected}次"
    }

    // ============================================================
    // 默认映射查询
    // ============================================================

    /**
     * 查询某动作类型的默认期望变化类型。
     *
     * @param actionType 动作类型
     * @return 默认变化类型，未配置时返回 [ChangeType.UNKNOWN]
     */
    fun getDefaultChangeType(actionType: ActionType): ChangeType {
        return defaultChangeMap[actionType] ?: ChangeType.UNKNOWN
    }

    /**
     * 更新某动作类型的默认期望变化类型（用于自适应调整先验映射）。
     *
     * @param actionType 动作类型
     * @param changeType 新的默认变化类型
     */
    fun updateDefaultChangeType(actionType: ActionType, changeType: ChangeType) {
        defaultChangeMap[actionType] = changeType
        Log.d(tag, "更新默认映射: ${actionType.name} → ${changeType.displayName}")
    }

    // ============================================================
    // 辅助方法：置信度与偏离度计算
    // ============================================================

    /**
     * 根据变化类型返回先验基础置信度。
     *
     * 高确定性变化（如 NO_CHANGE、PAGE_TRANSITION）置信度较高，
     * 低确定性变化（如 CONTENT_UPDATE）置信度较低。
     */
    private fun baseConfidenceFor(changeType: ChangeType): Float = when (changeType) {
        ChangeType.NO_CHANGE -> 0.85f       // 等待/截图等操作几乎不改变屏幕
        ChangeType.PAGE_TRANSITION -> 0.7f  // 打开应用/按键通常确定跳转
        ChangeType.SCROLL -> 0.65f          // 滑动通常确定滚动
        ChangeType.TEXT_INPUT -> 0.6f       // 输入通常确定文本出现
        ChangeType.ELEMENT_DISAPPEAR -> 0.4f
        ChangeType.ELEMENT_APPEAR -> 0.4f
        ChangeType.CONTENT_UPDATE -> 0.35f  // 点击后的变化不确定
        ChangeType.UNKNOWN -> 0.15f
    }

    /**
     * 计算状态熟悉度（0.0-1.0）。
     *
     * 基于该状态被观察到的转移组数量：观察越多越熟悉，上限为 1.0。
     */
    private fun computeFamiliarity(stateId: String, signature: String): Float {
        val graphKey = transitionKey(stateId, signature)
        val edges = transitionGraph[graphKey]
        val totalFreq = edges?.sumOf { it.frequency } ?: 0
        // 10 次观察即可达到满分熟悉度
        return (totalFreq.toFloat() / 10f).coerceIn(0f, 1f)
    }

    /**
     * 计算预测与实际之间的偏离程度（0.0-1.0）。
     *
     * - 变化类型匹配且状态匹配 → 0.0（完全吻合）
     * - 变化类型匹配但状态不匹配 → 0.3（部分偏离）
     * - 变化类型不匹配但同属「无变化/内容更新」类 → 0.5
     * - 变化类型完全不匹配 → 0.8-1.0
     *
     * 当预测基于历史经验（[basedOnHistory] = true）却出现类型不匹配时，
     * 偏离度额外上浮，因为经验预测本应更可靠，偏离意味着环境发生了变化。
     */
    private fun computeDeviation(
        predictedType: ChangeType,
        actualType: ChangeType,
        typeMatch: Boolean,
        stateMatch: Boolean,
        basedOnHistory: Boolean
    ): Float {
        if (typeMatch && stateMatch) return 0.0f
        if (typeMatch && !stateMatch) return 0.3f

        // 变化类型不匹配：判断是否属于相近类别
        val isPredictedPassive = predictedType == ChangeType.NO_CHANGE || predictedType == ChangeType.UNKNOWN
        val isActualPassive = actualType == ChangeType.NO_CHANGE || actualType == ChangeType.UNKNOWN

        val baseDeviation = when {
            // 预测有变化但实际无变化（操作可能未生效）→ 高偏离
            !isPredictedPassive && isActualPassive -> 0.9f
            // 预测无变化但实际有变化（意外变化）→ 高偏离
            isPredictedPassive && !isActualPassive -> 0.85f
            // 两者都是有变化但类型不同 → 中等偏离
            else -> 0.7f
        }

        // 基于历史经验的预测出现类型不匹配，偏离度额外上浮（经验失效更值得关注）
        return if (basedOnHistory) (baseDeviation + 0.05f).coerceAtMost(1f) else baseDeviation
    }

    // ============================================================
    // 辅助方法：变化类型推断
    // ============================================================

    /**
     * 通过比对前后两个屏幕状态，推断实际发生的变化类型。
     *
     * 推断优先级：
     * 1. 包名变化 → PAGE_TRANSITION
     * 2. 屏幕文本与元素数完全相同 → NO_CHANGE
     * 3. 元素数增加 → ELEMENT_APPEAR
     * 4. 元素数减少 → ELEMENT_DISAPPEAR
     * 5. 文本是前者的子串或前者是后者的子串（滚动特征）→ SCROLL
     * 6. 文本内容变化但包名相同 → CONTENT_UPDATE
     * 7. 无法判断 → UNKNOWN
     *
     * @param from 动作执行前的状态
     * @param to 动作执行后的状态
     * @return 推断的变化类型
     */
    private fun computeChangeType(from: ScreenState, to: ScreenState): ChangeType {
        // 1. 包名变化 → 页面跳转
        if (from.packageName != to.packageName) {
            return ChangeType.PAGE_TRANSITION
        }

        // 2. 完全相同 → 无变化
        if (from.screenText == to.screenText && from.elementCount == to.elementCount) {
            return ChangeType.NO_CHANGE
        }

        // 3. 元素数增加 → 元素出现
        if (to.elementCount > from.elementCount) {
            return ChangeType.ELEMENT_APPEAR
        }

        // 4. 元素数减少 → 元素消失
        if (to.elementCount < from.elementCount) {
            return ChangeType.ELEMENT_DISAPPEAR
        }

        // 5. 文本存在包含关系 → 滚动（滚动后部分文本重叠）
        if (from.screenText.isNotBlank() && to.screenText.isNotBlank()) {
            val overlap = from.screenText.length > 20 && to.screenText.length > 20 &&
                    (from.screenText.contains(to.screenText.take(40)) ||
                            to.screenText.contains(from.screenText.take(40)))
            if (overlap) {
                return ChangeType.SCROLL
            }
        }

        // 6. 文本内容变化但包名相同 → 内容更新
        if (from.screenText != to.screenText) {
            return ChangeType.CONTENT_UPDATE
        }

        // 7. 无法判断
        return ChangeType.UNKNOWN
    }

    // ============================================================
    // 辅助方法：动作签名与受影响元素
    // ============================================================

    /**
     * 构建动作签名（动作类型 + 关键参数的归一化表示）。
     *
     * 签名用于在转移图中分组相似动作。例如：
     * - SCREEN_CLICK_TEXT{text:"登录"} → "SCREEN_CLICK_TEXT:text=登录"
     * - SCREEN_SWIPE{direction:"UP"} → "SCREEN_SWIPE:dir=UP"
     * - APP_OPEN{packageName:"com.tencent.mm"} → "APP_OPEN:pkg=com.tencent.mm"
     * - SCREEN_CLICK{x:100,y:200} → "SCREEN_CLICK:coord"（坐标动作不区分具体坐标）
     *
     * @param action 动作
     * @return 归一化动作签名
     */
    private fun buildActionSignature(action: ClawAction): String {
        val type = action.type ?: return action.actionName
        val key = when (type) {
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK,
            ActionType.SCREEN_SCROLL_TO_TEXT,
            ActionType.SCREEN_TEXT_EXISTS ->
                "text=${action.text ?: ""}"

            ActionType.SCREEN_CLICK,
            ActionType.SCREEN_LONG_CLICK,
            ActionType.SCREEN_DOUBLE_CLICK ->
                "coord"

            ActionType.SCREEN_SWIPE ->
                "dir=${action.swipeDirectionName ?: "custom"}"

            ActionType.SCREEN_INPUT ->
                "input"

            ActionType.SCREEN_KEY ->
                "key=${action.keyName ?: ""}"

            ActionType.APP_OPEN,
            ActionType.APP_CLOSE,
            ActionType.APP_UNINSTALL ->
                "pkg=${action.packageName ?: action.name ?: ""}"

            ActionType.APP_SEARCH ->
                "name=${action.name ?: ""}"

            ActionType.SCREEN_WAIT ->
                "wait=${action.ms ?: 0}"

            ActionType.CLIPBOARD_COPY ->
                "copy"

            ActionType.CLIPBOARD_PASTE ->
                "paste"

            ActionType.MEDIA_CONTROL ->
                "media=${action.mediaAction ?: ""}"

            ActionType.SYSTEM_SET_VOLUME ->
                "vol=${action.volume ?: 0}"

            ActionType.SYSTEM_SET_BRIGHTNESS ->
                "bright=${action.brightness ?: 0}"

            else -> "default"
        }
        return "${type.name}:$key"
    }

    /**
     * 从动作中提取预期受影响的元素描述列表。
     *
     * 例如 SCREEN_CLICK_TEXT 的受影响元素是被点击的文本，
     * SCREEN_INPUT 的受影响元素是输入的文本内容。
     *
     * @param action 动作
     * @return 受影响元素描述列表
     */
    private fun extractAffectedElements(action: ClawAction): List<String> {
        val type = action.type ?: return emptyList()
        return when (type) {
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK,
            ActionType.SCREEN_SCROLL_TO_TEXT ->
                listOfNotNull(action.text?.let { "文本元素「$it」" })

            ActionType.SCREEN_INPUT ->
                listOfNotNull(action.text?.let { "输入框(内容:$it)" })

            ActionType.SCREEN_KEY ->
                listOfNotNull(action.keyName?.let { "按键($it)" })

            ActionType.APP_OPEN ->
                listOfNotNull((action.packageName ?: action.name)?.let { "应用($it)" })

            ActionType.SCREEN_SWIPE ->
                listOf("滚动区域(${action.swipeDirectionName ?: "自定义"})")

            else -> emptyList()
        }
    }

    // ============================================================
    // 辅助方法：键计算
    // ============================================================

    /** 构建转移图的组键：起始状态 ID + 动作签名。 */
    private fun transitionKey(stateId: String, signature: String): String = "$stateId|$signature"

    // ============================================================
    // 辅助方法：模型获取与边更新
    // ============================================================

    /** 获取或创建指定动作类型的预测模型。 */
    private fun getOrCreateModel(actionType: ActionType): PredictionModel =
        predictionModels.computeIfAbsent(actionType) { PredictionModel(it) }

    /**
     * 更新转移边的平均置信度（在验证预测后调用）。
     */
    private fun updateEdgeConfidence(fromStateId: String, toStateId: String, confidence: Float) {
        transitionGraph.forEach { (_, edges) ->
            synchronized(edges) {
                val edge = edges.find { it.fromStateId == fromStateId && it.toStateId == toStateId }
                if (edge != null) {
                    // 增量平均：newAvg = oldAvg + (value - oldAvg) / count
                    val count = edge.frequency
                    if (count > 0) {
                        edge.avgConfidence = edge.avgConfidence + (confidence - edge.avgConfidence) / count
                    }
                }
            }
        }
    }

    // ============================================================
    // 辅助方法：容量淘汰
    // ============================================================

    /** 状态注册表超容量时按最久未访问淘汰。 */
    private fun evictStatesIfNeeded() {
        if (stateRegistry.size <= maxStates) return
        val toRemove = stateRegistry.size - maxStates
        stateRegistry.entries
            .sortedBy { it.value.timestamp }
            .take(toRemove)
            .forEach { (key, _) -> stateRegistry.remove(key) }
        Log.d(tag, "状态表淘汰: 移除 $toRemove 条最旧状态")
    }

    /** 转移图组数超限时按最久未更新淘汰。 */
    private fun evictGroupsIfNeeded() {
        if (transitionGraph.size <= maxGroups) return
        val toRemove = transitionGraph.size - maxGroups
        transitionGraph.entries
            .sortedBy { (_, edges) -> edges.maxOfOrNull { it.lastSeen } ?: 0L }
            .take(toRemove)
            .forEach { (key, _) -> transitionGraph.remove(key) }
        Log.d(tag, "转移图淘汰: 移除 $toRemove 组最旧转移")
    }

    // ============================================================
    // 重置
    // ============================================================

    /** 清空所有学习数据与统计计数。 */
    fun clear() {
        transitionGraph.clear()
        stateRegistry.clear()
        predictionModels.clear()
        totalPredictions = 0
        totalCorrectPredictions = 0
        totalExactStateMatches = 0
        totalTransitionsRecorded = 0
        totalAnomaliesDetected = 0
        Log.d(tag, "已清空所有屏幕变化预测数据")
    }
}

// ============================================================
// 顶层私有工具：状态 ID 计算
// ============================================================

/** 状态 ID 哈希截断长度（十六进制字符数）。 */
private const val STATE_ID_LENGTH = 16

/**
 * 计算屏幕状态的唯一标识（SHA-256 截断哈希）。
 *
 * 基于包名、Activity、屏幕文本哈希、元素数拼接后取 SHA-256 前 [STATE_ID_LENGTH] 位十六进制。
 * 相同内容必然产生相同 ID，保证状态可比较。
 *
 * 作为顶层私有函数定义，以便 [ScreenChangePredictor.ScreenState] 的伴生对象
 * 在 [ScreenChangePredictor.ScreenState.from] 工厂方法中直接调用，
 * 而无需依赖类的实例状态。
 *
 * @param packageName 前台应用包名
 * @param activity Activity 类名
 * @param screenText 屏幕可见文本
 * @param elementCount 可交互元素数量
 * @return 16 位十六进制状态 ID
 */
private fun computeStateId(
    packageName: String?,
    activity: String?,
    screenText: String,
    elementCount: Int
): String {
    val raw = "${packageName ?: ""}|${activity ?: ""}|${screenText.hashCode()}|$elementCount"
    return try {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
        bytes.joinToString("") { "%02x".format(it) }.take(STATE_ID_LENGTH)
    } catch (e: Exception) {
        // 回退到简单哈希（理论上不会触发，保证健壮性）
        "h${(raw.hashCode().toUInt()).toString(16)}".take(STATE_ID_LENGTH)
    }
}
