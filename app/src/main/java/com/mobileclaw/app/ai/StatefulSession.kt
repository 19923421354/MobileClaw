package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 有状态会话管理器 —— 维护跨多次任务执行的对话状态，实现连续、连贯的手机操控体验。
 *
 * 核心理念：用户的手机操作往往不是孤立的单次指令，而是一段连续的「会话」——
 * 打开微信看消息、回复消息、关闭微信，这一系列动作属于同一个「社交沟通」会话。
 * 如果系统不维护会话状态，每次指令都从零开始理解上下文，既浪费 AI Token
 * 又无法提供「接着上次继续」的连贯体验。本系统通过显式的会话生命周期管理，
 * 让 AI 始终拥有「用户在做什么、做到哪了、接下来可能要做什么」的完整上下文。
 *
 * 会话状态机：
 * ```
 * IDLE → ACTIVE → WAITING_FOR_INPUT → ACTIVE → ... → ENDED
 *                 ↑                   ↓
 *                 ←─── PAUSED ←──────┘
 * ```
 * - IDLE：无会话状态，等待 [startSession] 创建新会话。
 * - ACTIVE：会话活跃中，正在执行用户指令。
 * - WAITING_FOR_INPUT：系统需要用户提供额外信息（如澄清指令、确认操作）。
 * - PAUSED：会话暂停（如用户切换到其他应用），可随时 [resumeSession] 恢复。
 * - ENDED：会话已结束（用户主动结束或超时自动结束），上下文归档至跨会话记忆。
 *
 * 七大核心能力：
 * 1. 会话生命周期管理：创建、暂停、恢复、结束会话，状态转换均有约束校验。
 * 2. 会话上下文追踪：记录当前应用、已打开应用列表、待处理任务、用户目标。
 * 3. 状态机驱动：严格的状态转换约束，非法转换会被拒绝并记录日志。
 * 4. 跨会话记忆：会话结束时自动归档上下文，新会话可引用历史（如「上次在
 *    微信中正在发消息」），实现连续体验。
 * 5. 超时自动结束：会话超过 [sessionTimeoutMs] 无活动后自动结束，释放资源。
 * 6. 目标检测：基于动作序列推断用户当前目标（如「发送消息」「浏览社交媒体」），
 *    置信度随动作积累动态更新。
 * 7. 会话恢复提示：用户返回时生成「是否继续上次操作」的恢复建议。
 *
 * 线程安全：
 * - 当前会话指针使用 @Volatile，状态变更通过 [sessionLock] 同步保护。
 * - 历史会话与跨会话记忆使用 [ConcurrentLinkedDeque]，可被多线程并发调用。
 * - 统计计数使用 @Volatile + private set，保证可见性的同时禁止外部修改。
 * - 典型场景：UI 线程发起会话操作，后台协程执行定时清理。
 *
 * 容量限制：
 * - 同一时刻最多 1 个活跃会话（含暂停/等待输入态）。
 * - 历史会话最多保留 [maxHistorySessions] 条（默认 10），超出按最旧优先淘汰。
 * - 跨会话记忆最多保留 [maxMemoryEntries] 条（默认 5）。
 * - 单会话动作历史最多 [maxActionHistory] 条（默认 200），超出按时间淘汰。
 *
 * 使用方式：
 * ```
 * val sessionMgr = StatefulSession()
 * // 创建会话
 * val session = sessionMgr.startSession()
 * // 每次执行动作后更新
 * sessionMgr.updateActivity(action, result)
 * // 需要用户输入时
 * sessionMgr.waitForInput()
 * // 暂停 / 恢复
 * sessionMgr.pauseSession()
 * sessionMgr.resumeSession()
 * // 结束会话
 * sessionMgr.endSession()
 * // 用户返回时检查恢复提示
 * val hint = sessionMgr.getResumptionHint() // "上次你在微信中发送消息，是否继续？"
 * ```
 */
class StatefulSession(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val tag = "StatefulSession"

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 会话状态。
     *
     * 驱动整个会话生命周期的状态机，转换约束如下：
     * - IDLE → ACTIVE：调用 [startSession] 创建会话。
     * - ACTIVE → WAITING_FOR_INPUT：调用 [waitForInput]，系统需要用户提供额外信息。
     * - WAITING_FOR_INPUT → ACTIVE：调用 [updateActivity]，用户提供了新的指令。
     * - ACTIVE / WAITING_FOR_INPUT → PAUSED：调用 [pauseSession]。
     * - PAUSED → ACTIVE：调用 [resumeSession]。
     * - 任意非 ENDED 状态 → ENDED：调用 [endSession] 或超时自动结束。
     */
    enum class SessionState {
        /** 空闲：当前无会话。 */
        IDLE,

        /** 活跃：会话进行中，正在执行用户指令。 */
        ACTIVE,

        /** 等待输入：系统需要用户提供额外信息（澄清指令、确认操作等）。 */
        WAITING_FOR_INPUT,

        /** 已暂停：会话临时挂起，可随时恢复。 */
        PAUSED,

        /** 已结束：会话已终止，上下文归档至跨会话记忆。 */
        ENDED
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 会话目标 —— 从动作序列推断出的用户意图。
     *
     * @property description     目标的人类可读描述（如「发送消息」「浏览社交媒体」）
     * @property confidence      置信度（0.0-1.0），随动作积累动态更新
     * @property relatedActions  与该目标相关的动作类型列表
     */
    data class SessionGoal(
        val description: String,
        val confidence: Float,
        val relatedActions: List<String>
    )

    /**
     * 会话上下文 —— 记录会话期间的环境信息与用户意图。
     *
     * @property currentApp    当前前台应用（包名或名称）
     * @property openApps      本次会话中打开过的应用列表
     * @property pendingTasks  待处理任务（如「正在输入文本」「未发送的消息」）
     * @property userGoal      推断的用户目标描述
     */
    data class SessionContext(
        var currentApp: String? = null,
        val openApps: MutableList<String> = mutableListOf(),
        val pendingTasks: MutableList<String> = mutableListOf(),
        var userGoal: String? = null
    )

    /**
     * 动作历史条目 —— 单次动作执行的完整记录。
     *
     * @property timestamp 记录时间戳（毫秒）
     * @property action    执行的动作
     * @property result    动作执行结果
     */
    data class ActionHistoryEntry(
        val timestamp: Long,
        val action: ClawAction,
        val result: ClawActionResult
    )

    /**
     * 会话 —— 一次完整的用户交互会话。
     *
     * @property id            会话唯一标识
     * @property startTime     会话开始时间戳（毫秒）
     * @property lastActivity  最后活动时间戳（毫秒），用于超时判断
     * @property state         当前会话状态
     * @property goal          推断的会话目标，未确定时为 null
     * @property context       会话上下文
     * @property actionHistory 动作历史记录（按时间顺序）
     */
    data class Session(
        val id: String,
        val startTime: Long,
        var lastActivity: Long,
        var state: SessionState,
        var goal: SessionGoal?,
        var context: SessionContext,
        val actionHistory: MutableList<ActionHistoryEntry>
    ) {
        /** 会话持续时长（毫秒，从开始到最后活动）。 */
        val durationMs: Long
            get() = lastActivity - startTime

        /** 动作总数。 */
        val actionCount: Int
            get() = actionHistory.size

        /** 成功动作数。 */
        val successCount: Int
            get() = actionHistory.count { it.result.success }
    }

    /**
     * 跨会话记忆条目 —— 会话结束时归档的上下文快照。
     *
     * 用于在新会话中引用历史上下文，实现「接着上次继续」的连续体验。
     *
     * @property sessionId    原会话 ID
     * @property endTime      会话结束时间戳（毫秒）
     * @property lastApp      会话最后使用的应用
     * @property goal         会话目标
     * @property pendingTasks 未完成的待办任务
     * @property actionCount  会话期间执行的动作总数
     */
    data class SessionMemory(
        val sessionId: String,
        val endTime: Long,
        val lastApp: String?,
        val goal: SessionGoal?,
        val pendingTasks: List<String>,
        val actionCount: Int
    )

    /** 目标推断规则（内部使用）。 */
    private data class GoalRule(
        /** 目标描述。 */
        val description: String,
        /** 该目标期望出现的动作类型集合。 */
        val actionTypes: Set<String>,
        /** 基础置信度（在完全匹配时的置信度上限参考）。 */
        val baseConfidence: Float
    )

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 当前会话（同一时刻最多 1 个活跃/暂停/等待中的会话）。 */
    @Volatile
    private var currentSession: Session? = null

    /** 会话状态变更同步锁，保证生命周期方法的原子性。 */
    private val sessionLock = Any()

    /** 历史会话记录（队首为最新，最多保留 [maxHistorySessions] 条）。 */
    private val sessionHistory = ConcurrentLinkedDeque<Session>()

    /** 历史会话索引（id → Session），用于按 ID 快速查找，线程安全。 */
    private val sessionIndex = ConcurrentHashMap<String, Session>()

    /** 跨会话记忆（队首为最新，最多保留 [maxMemoryEntries] 条）。 */
    private val crossSessionMemory = ConcurrentLinkedDeque<SessionMemory>()

    // ============================================================
    // 配置常量
    // ============================================================

    /** 会话超时时间（毫秒），默认 30 分钟无活动后自动结束。 */
    private val sessionTimeoutMs = 30L * 60 * 1000

    /** 定时清理间隔（毫秒），默认 5 分钟检查一次超时会话。 */
    private val cleanupIntervalMs = 5L * 60 * 1000

    /** 历史会话最大保留条数。 */
    private val maxHistorySessions = 10

    /** 跨会话记忆最大保留条数。 */
    private val maxMemoryEntries = 5

    /** 单会话动作历史最大条数。 */
    private val maxActionHistory = 200

    /** 最低目标置信度阈值，低于此值视为目标未确定。 */
    private val minGoalConfidence = 0.3f

    /** 达到此动作数后开始目标检测。 */
    private val goalDetectionThreshold = 3

    /** 恢复提示有效时间窗口（毫秒），超过则不再提示继续上次操作。 */
    private val resumptionWindowMs = 2L * 60 * 60 * 1000

    /** 目标检测时回溯的最近动作数上限。 */
    private val goalDetectionWindowSize = 20

    // ============================================================
    // 目标推断规则
    // ============================================================

    /** 目标推断规则表，按场景覆盖面排列。 */
    private val goalRules = listOf(
        GoalRule("发送消息", setOf("APP_OPEN", "SCREEN_INPUT", "SCREEN_CLICK_TEXT"), 0.75f),
        GoalRule("浏览社交媒体", setOf("APP_OPEN", "SCREEN_SWIPE", "SCREEN_SCROLL_TO_TEXT"), 0.65f),
        GoalRule("搜索内容", setOf("SCREEN_INPUT", "SCREEN_CLICK_TEXT", "SCREEN_FIND_AND_CLICK"), 0.65f),
        GoalRule("管理系统设置", setOf("SYSTEM_GET_INFO", "SYSTEM_SET_VOLUME", "SYSTEM_SET_BRIGHTNESS", "SYSTEM_CLEAR_CACHE"), 0.65f),
        GoalRule("控制媒体播放", setOf("MEDIA_CONTROL"), 0.75f),
        GoalRule("文件操作", setOf("FILE_READ", "FILE_WRITE"), 0.75f),
        GoalRule("应用管理", setOf("APP_OPEN", "APP_CLOSE", "APP_INSTALL", "APP_UNINSTALL"), 0.6f),
        GoalRule("通知管理", setOf("NOTIFY_READ", "NOTIFY_SEND"), 0.7f),
        GoalRule("剪贴板操作", setOf("CLIPBOARD_COPY", "CLIPBOARD_PASTE"), 0.7f),
        GoalRule("查看屏幕内容", setOf("SCREEN_SCREENSHOT", "SCREEN_GET_TEXT", "SCREEN_TEXT_EXISTS"), 0.6f)
    )

    // ============================================================
    // 统计计数
    // ============================================================

    /** 累计创建会话总数。 */
    @Volatile
    var totalSessions: Int = 0
        private set

    /** 累计记录动作总数。 */
    @Volatile
    var totalActions: Int = 0
        private set

    /** 累计超时自动结束次数。 */
    @Volatile
    var totalTimeouts: Int = 0
        private set

    /** 定时清理协程 Job。 */
    private var cleanupJob: Job? = null

    /** 时间格式化器（用于摘要展示）。 */
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    // ============================================================
    // 初始化与生命周期
    // ============================================================

    init {
        startPeriodicCleanup()
    }

    /** 启动定时清理协程，周期性检查并结束超时会话。 */
    private fun startPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                try {
                    cleanupExpiredSessions()
                } catch (e: Exception) {
                    Log.w(tag, "定时清理异常: ${e.message}")
                }
            }
        }
    }

    /**
     * 取消定时清理协程（通常在组件销毁时调用）。
     * 注意：此方法不会清空已存储的会话数据。
     */
    fun dispose() {
        cleanupJob?.cancel()
        cleanupJob = null
    }

    // ============================================================
    // 会话生命周期管理
    // ============================================================

    /**
     * 创建并启动一个新的会话。
     *
     * 如果当前已有活跃会话（含暂停/等待输入态），会先自动结束旧会话再创建新会话，
     * 以保证同一时刻最多 1 个活跃会话。
     *
     * @return 新创建的会话（状态为 ACTIVE）
     */
    fun startSession(): Session {
        synchronized(sessionLock) {
            // 若已有会话，先自动结束
            currentSession?.let { existing ->
                if (existing.state != SessionState.ENDED) {
                    endSessionInternal(existing)
                }
            }

            val now = System.currentTimeMillis()
            val session = Session(
                id = generateSessionId(),
                startTime = now,
                lastActivity = now,
                state = SessionState.ACTIVE,
                goal = null,
                context = SessionContext(),
                actionHistory = mutableListOf()
            )
            currentSession = session
            totalSessions++
            Log.d(tag, "创建会话: ${session.id.take(8)} (第 $totalSessions 个)")
            return session
        }
    }

    /**
     * 获取当前会话。
     *
     * 若当前会话已超时（超过 [sessionTimeoutMs] 无活动），会自动结束并返回 null。
     *
     * @return 当前活跃/暂停/等待输入的会话；无会话或已结束返回 null
     */
    fun getCurrentSession(): Session? {
        val session = currentSession ?: return null

        // 检查是否超时（双重检查锁定模式）
        if (isSessionExpired(session)) {
            synchronized(sessionLock) {
                if (currentSession === session) {
                    endSessionInternal(session, timeout = true)
                    currentSession = null
                }
            }
            return null
        }

        return session
    }

    /**
     * 更新会话活动 —— 记录一次动作执行及其结果。
     *
     * 这是会话管理的核心方法，每次执行用户指令后都应调用。内部会：
     * 1. 若无当前会话，自动创建一个新会话。
     * 2. 更新最后活动时间戳（重置超时计时器）。
     * 3. 将动作与结果追加到动作历史。
     * 4. 更新会话上下文（当前应用、已打开应用、待办任务）。
     * 5. 若状态为 WAITING_FOR_INPUT 或 PAUSED，自动恢复为 ACTIVE。
     * 6. 动作累积达到阈值后重新推断会话目标。
     *
     * @param action 执行的动作
     * @param result 动作执行结果
     */
    fun updateActivity(action: ClawAction, result: ClawActionResult) {
        val now = System.currentTimeMillis()

        synchronized(sessionLock) {
            // 若无会话则自动创建（synchronized 可重入，安全调用）
            val session = currentSession ?: startSession()

            // 更新最后活动时间
            session.lastActivity = now

            // 状态恢复：WAITING_FOR_INPUT / PAUSED → ACTIVE
            if (session.state == SessionState.WAITING_FOR_INPUT ||
                session.state == SessionState.PAUSED
            ) {
                session.state = SessionState.ACTIVE
                Log.d(tag, "会话 ${session.id.take(8)} 恢复为 ACTIVE")
            }

            // 追加动作历史
            session.actionHistory.add(ActionHistoryEntry(now, action, result))
            // 限制历史长度，超出时从头部（最旧）淘汰
            while (session.actionHistory.size > maxActionHistory) {
                session.actionHistory.removeAt(0)
            }

            // 更新上下文（当前应用、已打开应用、待办任务）
            updateContext(session, action, result)

            // 动作累积达到阈值后重新检测目标
            if (session.actionHistory.size >= goalDetectionThreshold) {
                val recentActions = session.actionHistory
                    .takeLast(minOf(session.actionHistory.size, goalDetectionWindowSize))
                    .map { it.action }
                detectGoal(recentActions)?.let { newGoal ->
                    session.goal = newGoal
                    session.context.userGoal = newGoal.description
                }
            }

            totalActions++
        }
    }

    /**
     * 暂停当前会话。
     *
     * 将 ACTIVE 或 WAITING_FOR_INPUT 状态的会话转为 PAUSED，
     * 保留所有上下文，可通过 [resumeSession] 随时恢复。
     * 若无活跃会话或会话已暂停/结束，则不做任何操作。
     */
    fun pauseSession() {
        synchronized(sessionLock) {
            val session = currentSession ?: return
            if (session.state == SessionState.ACTIVE ||
                session.state == SessionState.WAITING_FOR_INPUT
            ) {
                session.state = SessionState.PAUSED
                Log.d(tag, "会话 ${session.id.take(8)} 已暂停")
            }
        }
    }

    /**
     * 恢复已暂停的会话。
     *
     * 将 PAUSED 状态的会话恢复为 ACTIVE，并重置最后活动时间
     * （给予新的超时窗口）。
     *
     * @return 恢复后的会话；若无可恢复的暂停会话返回 null
     */
    fun resumeSession(): Session? {
        synchronized(sessionLock) {
            val session = currentSession ?: return null
            if (session.state != SessionState.PAUSED) {
                Log.d(tag, "会话 ${session.id.take(8)} 非暂停态，无法恢复 (当前: ${session.state})")
                return null
            }
            session.state = SessionState.ACTIVE
            session.lastActivity = System.currentTimeMillis()
            Log.d(tag, "会话 ${session.id.take(8)} 已恢复为 ACTIVE")
            return session
        }
    }

    /**
     * 将当前会话标记为等待用户输入。
     *
     * 当 AI 系统需要用户提供额外信息（如澄清指令、确认操作）时调用，
     * 将 ACTIVE 状态转为 WAITING_FOR_INPUT。后续调用 [updateActivity] 即可恢复。
     * 若无活跃会话或会话非 ACTIVE 状态，则不做任何操作。
     */
    fun waitForInput() {
        synchronized(sessionLock) {
            val session = currentSession ?: return
            if (session.state == SessionState.ACTIVE) {
                session.state = SessionState.WAITING_FOR_INPUT
                Log.d(tag, "会话 ${session.id.take(8)} 等待用户输入")
            }
        }
    }

    /**
     * 结束当前会话。
     *
     * 将会话状态置为 ENDED，归档至历史记录与跨会话记忆，然后清除当前会话指针。
     * 若无活跃会话，则不做任何操作。
     */
    fun endSession() {
        synchronized(sessionLock) {
            val session = currentSession ?: return
            endSessionInternal(session)
            currentSession = null
        }
    }

    /**
     * 结束会话的内部实现（调用方需持有 [sessionLock]）。
     *
     * @param session  要结束的会话
     * @param timeout  是否因超时自动结束
     */
    private fun endSessionInternal(session: Session, timeout: Boolean = false) {
        if (session.state == SessionState.ENDED) return

        session.state = SessionState.ENDED

        // 归档至历史记录
        sessionHistory.addFirst(session)
        sessionIndex[session.id] = session
        evictOldHistory()

        // 归档跨会话记忆（供后续恢复提示使用）
        saveSessionMemory(session)

        if (timeout) {
            totalTimeouts++
            Log.d(tag, "会话 ${session.id.take(8)} 因超时自动结束 (动作 ${session.actionCount} 次)")
        } else {
            Log.d(
                tag,
                "会话 ${session.id.take(8)} 已结束 " +
                    "(动作 ${session.actionCount} 次, 持续 ${formatDuration(session.durationMs)})"
            )
        }
    }

    // ============================================================
    // 目标检测
    // ============================================================

    /**
     * 根据动作序列推断用户当前目标。
     *
     * 推断算法：
     * 1. 提取动作列表中所有动作类型名称（归一化为大写）。
     * 2. 逐条匹配 [goalRules] 中的规则，计算覆盖率与频率加权得分。
     * 3. 得分 = 基础置信度 × 覆盖率 + 频率加权（每多一个匹配动作 +0.03，上限 +0.3）。
     * 4. 返回得分最高且达到 [minGoalConfidence] 阈值的目标。
     *
     * 示例：动作序列 [APP_OPEN, SCREEN_INPUT, SCREEN_CLICK_TEXT]
     * → 匹配「发送消息」规则（覆盖率 3/3=100%，频率加权 +0.09）
     * → 得分 = 0.75 × 1.0 + 0.09 = 0.84 → 返回「发送消息」(84%)
     *
     * @param actions 待分析的动作列表
     * @return 推断的会话目标，数据不足或置信度过低时返回 null
     */
    fun detectGoal(actions: List<ClawAction>): SessionGoal? {
        if (actions.isEmpty()) return null

        val actionNames = actions.map { it.actionName.trim().uppercase() }
        val actionTypeSet = actionNames.toSet()

        var bestGoal: SessionGoal? = null
        var bestScore = 0f

        for (rule in goalRules) {
            // 计算匹配的类型数（去重后）
            val matchedTypes = actionTypeSet.intersect(rule.actionTypes)
            if (matchedTypes.isEmpty()) continue

            // 覆盖率：匹配的类型数 / 规则期望的类型数
            val coverage = matchedTypes.size.toFloat() / rule.actionTypes.size
            // 频率加权：匹配动作越多，置信度越高（上限 +0.3）
            val matchCount = actionNames.count { it in rule.actionTypes }
            val frequencyBoost = minOf(matchCount, 10) * 0.03f
            // 综合得分
            val score = (rule.baseConfidence * coverage + frequencyBoost).coerceIn(0f, 1f)

            if (score > bestScore && score >= minGoalConfidence) {
                bestScore = score
                bestGoal = SessionGoal(
                    description = rule.description,
                    confidence = score,
                    relatedActions = matchedTypes.sorted()
                )
            }
        }

        return bestGoal
    }

    // ============================================================
    // 摘要与提示
    // ============================================================

    /**
     * 生成当前会话的摘要（用于 UI 展示与调试）。
     *
     * 包含会话 ID、开始时间、持续时长、状态、目标、当前应用、
     * 已打开应用、待办任务、动作数与成功率。
     *
     * @return 会话摘要字符串；无活跃会话时返回提示信息
     */
    fun getSessionSummary(): String {
        val session = currentSession ?: return "无活跃会话"

        val duration = formatDuration(System.currentTimeMillis() - session.startTime)
        val startTimeStr = timeFormatter.format(Date(session.startTime))
        val goalStr = session.goal?.let {
            "${it.description} (${"%.0f%%".format(it.confidence * 100)})"
        } ?: "未确定"
        val successRate = if (session.actionCount > 0) {
            "%.0f%%".format(session.successCount.toFloat() / session.actionCount * 100)
        } else {
            "N/A"
        }

        return buildString {
            appendLine("==当前会话==")
            appendLine("ID: ${session.id.take(8)}")
            appendLine("开始: $startTimeStr (持续 $duration)")
            appendLine("状态: ${session.state.name}")
            appendLine("目标: $goalStr")
            appendLine("当前应用: ${session.context.currentApp ?: "无"}")
            appendLine("已打开应用: ${session.context.openApps.joinToString(", ").ifEmpty { "无" }}")
            appendLine("待办: ${session.context.pendingTasks.joinToString(", ").ifEmpty { "无" }}")
            append("动作: ${session.actionCount} 次 (成功率 $successRate)")
        }
    }

    /**
     * 生成会话恢复提示。
     *
     * 检查跨会话记忆中最近的已结束会话，若在 [resumptionWindowMs] 时间窗口内，
     * 则生成「是否继续上次操作」的提示文本。
     *
     * 典型输出：
     * ```
     * 上次会话（35分钟前）你在微信中正在发送消息，还有1项待办（共8个动作），是否继续？
     * ```
     *
     * @return 恢复提示字符串；无可用记忆或超出时间窗口时返回 null
     */
    fun getResumptionHint(): String? {
        val memory = crossSessionMemory.peekFirst() ?: return null
        val now = System.currentTimeMillis()
        val ageMs = now - memory.endTime

        // 超出恢复窗口则不提示
        if (ageMs > resumptionWindowMs) return null

        // 无任何可恢复的上下文时不提示
        if (memory.lastApp == null && memory.goal == null &&
            memory.pendingTasks.isEmpty() && memory.actionCount == 0
        ) {
            return null
        }

        val timeAgo = formatDuration(ageMs)
        return buildString {
            append("上次会话（${timeAgo}前）")
            if (memory.lastApp != null) {
                append("在${memory.lastApp}中")
            }
            if (memory.goal != null) {
                append("正在${memory.goal.description}")
            }
            if (memory.pendingTasks.isNotEmpty()) {
                append("，还有${memory.pendingTasks.size}项待办")
            }
            if (memory.actionCount > 0) {
                append("（共${memory.actionCount}个动作）")
            }
            append("，是否继续？")
        }
    }

    // ============================================================
    // 清理与维护
    // ============================================================

    /**
     * 清理过期会话。
     *
     * 检查当前会话是否超过 [sessionTimeoutMs] 无活动：
     * - 若超时，自动结束会话并归档至跨会话记忆。
     *
     * 由定时协程周期性调用（默认每 [cleanupIntervalMs] 检查一次），也可手动触发。
     */
    fun cleanupExpiredSessions() {
        val session = currentSession ?: return

        if (isSessionExpired(session)) {
            synchronized(sessionLock) {
                if (currentSession === session) {
                    endSessionInternal(session, timeout = true)
                    currentSession = null
                }
            }
        }
    }

    // ============================================================
    // 查询方法
    // ============================================================

    /** 获取历史会话列表（按结束时间倒序，最新的在前）。 */
    fun getSessionHistory(): List<Session> = sessionHistory.toList()

    /** 获取跨会话记忆列表（按结束时间倒序，最新的在前）。 */
    fun getCrossSessionMemory(): List<SessionMemory> = crossSessionMemory.toList()

    /**
     * 按 ID 查找历史会话。
     *
     * @param sessionId 会话 ID
     * @return 匹配的历史会话；未找到返回 null
     */
    fun getSessionById(sessionId: String): Session? = sessionIndex[sessionId]

    /**
     * 获取管理器统计摘要（用于 UI 展示与调试）。
     *
     * 包含会话总数、动作总数、超时次数、历史会话数与跨会话记忆数。
     */
    fun getSummary(): String {
        val currentGoal = currentSession?.goal?.description ?: "无"
        return "会话管理: 总会话 $totalSessions | 总动作 $totalActions | " +
            "超时 $totalTimeouts | 历史 ${sessionHistory.size}/$maxHistorySessions | " +
            "记忆 ${crossSessionMemory.size}/$maxMemoryEntries | 当前目标: $currentGoal"
    }

    // ============================================================
    // 内部工具方法
    // ============================================================

    /**
     * 更新会话上下文 —— 根据动作类型维护当前应用、已打开应用与待办任务。
     *
     * 仅在动作执行成功时更新上下文，避免因失败操作导致上下文状态不一致。
     *
     * @param session 当前会话
     * @param action  执行的动作
     * @param result  动作执行结果
     */
    private fun updateContext(session: Session, action: ClawAction, result: ClawActionResult) {
        val context = session.context
        val actionType = action.actionName.trim().uppercase()

        // 动作执行失败时不更新上下文，避免状态不一致
        if (!result.success) {
            session.goal?.let { context.userGoal = it.description }
            return
        }

        when (actionType) {
            "APP_OPEN" -> {
                // 打开应用：更新当前应用并记录到已打开列表
                val app = action.packageName
                    ?: action.name
                    ?: action.description.takeIf { it.isNotBlank() }
                if (app != null) {
                    context.currentApp = app
                    if (app !in context.openApps) {
                        context.openApps.add(app)
                    }
                }
            }
            "APP_CLOSE" -> {
                // 关闭应用：从已打开列表移除
                val app = action.packageName ?: action.name
                if (app != null) {
                    context.openApps.remove(app)
                    if (context.currentApp == app) {
                        context.currentApp = context.openApps.lastOrNull()
                    }
                }
            }
            "SCREEN_INPUT" -> {
                // 输入文本：可能正在撰写消息，记录待办
                val task = "正在输入文本"
                if (task !in context.pendingTasks) {
                    context.pendingTasks.add(task)
                }
            }
            "SCREEN_CLICK_TEXT", "SCREEN_CLICK", "SCREEN_FIND_AND_CLICK" -> {
                // 点击「发送」「提交」等关键词时，清除输入待办
                val clickText = action.text ?: action.description
                if (clickText.contains("发送") ||
                    clickText.contains("send", ignoreCase = true) ||
                    clickText.contains("提交") ||
                    clickText.contains("确认") ||
                    clickText.contains("确定")
                ) {
                    context.pendingTasks.removeIf { it.contains("输入") }
                }
            }
        }

        // 同步用户目标描述
        session.goal?.let { context.userGoal = it.description }
    }

    /**
     * 将会话上下文归档至跨会话记忆。
     *
     * @param session 已结束的会话
     */
    private fun saveSessionMemory(session: Session) {
        val memory = SessionMemory(
            sessionId = session.id,
            endTime = System.currentTimeMillis(),
            lastApp = session.context.currentApp,
            goal = session.goal,
            pendingTasks = session.context.pendingTasks.toList(),
            actionCount = session.actionCount
        )
        crossSessionMemory.addFirst(memory)
        while (crossSessionMemory.size > maxMemoryEntries) {
            crossSessionMemory.pollLast()
        }
    }

    /** 判断会话是否已超时（无活动时间超过 [sessionTimeoutMs]）。 */
    private fun isSessionExpired(session: Session): Boolean {
        if (session.state == SessionState.ENDED) return false
        return System.currentTimeMillis() - session.lastActivity > sessionTimeoutMs
    }

    /** 淘汰超出的历史会话（保留最近的 [maxHistorySessions] 条）。 */
    private fun evictOldHistory() {
        while (sessionHistory.size > maxHistorySessions) {
            val evicted = sessionHistory.pollLast()
            if (evicted != null) {
                sessionIndex.remove(evicted.id)
            }
        }
    }

    /** 生成会话唯一标识（32 位无连字符 UUID）。 */
    private fun generateSessionId(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    /**
     * 将毫秒时长格式化为人类可读字符串。
     * 如：5秒、3分钟、2小时、1天。
     */
    private fun formatDuration(ms: Long): String {
        if (ms < 0) return "0秒"
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds < 3600 -> "${seconds / 60}分钟"
            seconds < 86400 -> "${seconds / 3600}小时"
            else -> "${seconds / 86400}天"
        }
    }

    // ============================================================
    // 重置
    // ============================================================

    /** 清空所有会话数据与统计计数（定时清理协程不会被取消）。 */
    fun clear() {
        synchronized(sessionLock) {
            currentSession = null
        }
        sessionHistory.clear()
        sessionIndex.clear()
        crossSessionMemory.clear()
        totalSessions = 0
        totalActions = 0
        totalTimeouts = 0
        Log.d(tag, "已清空所有会话数据")
    }
}
