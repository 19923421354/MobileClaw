package com.mobileclaw.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// =============================================================================
//  ActionRecorder - 动作录制器
// =============================================================================

/**
 * 动作录制器 —— 录制成功动作序列并支持智能回放。
 *
 * 核心理念：用户经常重复执行相同的操作流程（如每天早上打开微信看消息、
 * 打开抖音搜索特定内容、用支付宝扫码付款等）。每次都让 AI 从零解析既
 * 浪费 Token 又增加延迟。本系统将成功的动作序列录制下来，后续可通过
 * 名称直接回放，实现「一次录制，多次复用」。
 *
 * 核心能力：
 * 1. 录制：捕获完整动作序列及其执行结果，附带时间戳、用户指令、应用
 *    上下文、总耗时等元数据，形成可复用的 [Recording]。
 * 2. 命名回放：用户可为录制命名，后续通过名称快速查找并回放。
 * 3. 智能适配：回放时若动作失败，自动尝试等价替代（如文本点击失败时
 *    自动降级为查找并点击），提升对应用版本变化的鲁棒性。
 * 4. 自动分类：根据动作类型自动推断录制类别（日常惯例、工作流、娱乐、
 *    通信、自定义），便于分类管理。
 * 5. 导入导出：支持将录制序列化为 JSON 字符串，方便备份、分享和跨
 *    设备迁移。
 * 6. 回放统计：跟踪每条录制的回放次数和成功率，辅助判断录制的可靠性。
 *
 * 线程安全：所有存储使用 [ConcurrentHashMap]，录制会话使用同步锁
 * 保证原子性，回放统计使用 [ConcurrentHashMap.computeIfPresent] 保证
 * 原子更新。
 *
 * 容量限制：最多保存 50 条录制，超出时按最久未回放时间 LRU 淘汰。
 */
class ActionRecorder {

    // =========================================================================
    //  常量
    // =========================================================================

    /** 最大录制条目数。 */
    private val maxRecordings = 50

    // =========================================================================
    //  录制存储与会话状态
    // =========================================================================

    /** 录制存储（id → Recording），线程安全。 */
    private val recordings = ConcurrentHashMap<String, Recording>()

    /** 当前录制会话（同一时刻仅允许一个活跃会话）。 */
    @Volatile
    private var currentSession: RecordingSession? = null

    /** 录制会话同步锁，保证 startRecording/recordAction/stopRecording 的原子性。 */
    private val sessionLock = Any()

    /** 用于导入导出的 Json 实例，忽略未知字段以保证前向兼容。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * 当前录制会话的内部状态。
     *
     * @property name        录制名称
     * @property userCommand 用户原始指令
     * @property appContext   录制时的前台应用包名
     * @property startTime    录制开始时间戳（毫秒）
     * @property actions      已录制的动作列表
     * @property results      已录制的动作结果列表（与 actions 一一对应）
     */
    private data class RecordingSession(
        val name: String,
        val userCommand: String,
        val appContext: String?,
        val startTime: Long,
        val actions: MutableList<ClawAction>,
        val results: MutableList<ClawActionResult>
    )

    // =========================================================================
    //  录制控制
    // =========================================================================

    /**
     * 开始一次新的录制会话。
     *
     * 同一时刻仅允许一个活跃会话；若已有会话进行中，则返回 false。
     * 调用 [recordAction] 逐条记录动作及其结果，最后调用 [stopRecording]
     * 完成录制。
     *
     * @param name        录制名称（用于后续按名称查找回放）
     * @param userCommand 用户原始指令
     * @param appContext   录制时的前台应用包名（可选，用于上下文匹配）
     * @return true 表示成功开始录制，false 表示已有录制进行中
     */
    fun startRecording(name: String, userCommand: String, appContext: String? = null): Boolean {
        synchronized(sessionLock) {
            if (currentSession != null) return false
            currentSession = RecordingSession(
                name = name,
                userCommand = userCommand,
                appContext = appContext,
                startTime = System.currentTimeMillis(),
                actions = mutableListOf(),
                results = mutableListOf()
            )
            return true
        }
    }

    /**
     * 录制一条动作及其执行结果。
     *
     * 在 [startRecording] 之后、[stopRecording] 之前调用。动作与结果按
     * 调用顺序追加，保持一一对应关系。若当前无活跃会话，则忽略。
     *
     * @param action 已执行的动作
     * @param result 动作执行结果
     */
    fun recordAction(action: ClawAction, result: ClawActionResult) {
        synchronized(sessionLock) {
            val session = currentSession ?: return
            session.actions.add(action)
            session.results.add(result)
        }
    }

    /**
     * 结束当前录制会话并保存录制。
     *
     * 仅当 success 为 true 且会话中包含至少一条动作时，才创建 [Recording]
     * 并存入存储；否则丢弃会话内容并返回 null。录制类别由 [autoCategorize]
     * 根据动作类型自动推断。
     *
     * @param success 整体任务是否成功执行
     * @return 已保存的录制，若未保存（无会话/失败/空动作）则返回 null
     */
    fun stopRecording(success: Boolean): Recording? {
        val session: RecordingSession?
        synchronized(sessionLock) {
            session = currentSession
            currentSession = null
        }

        if (session == null) return null
        // 仅录制成功的动作序列
        if (!success || session.actions.isEmpty()) return null

        val now = System.currentTimeMillis()
        val recording = Recording(
            id = UUID.randomUUID().toString(),
            name = session.name,
            category = autoCategorize(session.actions),
            userCommand = session.userCommand,
            actions = session.actions.toList(),
            results = session.results.toList(),
            createdAt = session.startTime,
            durationMs = now - session.startTime,
            appContext = session.appContext,
            replayCount = 0,
            lastReplayed = null,
            replaySuccessRate = 0.0f
        )

        recordings[recording.id] = recording
        enforceMaxRecordings()

        return recording
    }

    // =========================================================================
    //  回放
    // =========================================================================

    /**
     * 回放一条录制，重新执行其动作序列。
     *
     * 逐条将动作交给 [adapter] 执行，并收集结果。具备智能适配能力：
     * - 跳过 ANSWER 类型动作（纯文本回答，无需执行）
     * - SCREEN_CLICK_TEXT 失败时，自动降级为 SCREEN_FIND_AND_CLICK 重试
     *   （应对应用版本更新导致文本位置变化的情况）
     * - 执行器抛出异常时，捕获并转为失败结果，不中断回放流程
     *
     * 回放结束后，自动更新录制的回放统计（回放次数、最后回放时间、成功率）。
     * 成功率采用累计加权计算：newRate = (oldRate * oldCount + score) / newCount。
     *
     * @param recording 要回放的录制
     * @param adapter   动作执行器，接收 [ClawAction] 返回 [ClawActionResult]
     * @return 回放结果 [ReplayResult]
     */
    fun replay(recording: Recording, adapter: (ClawAction) -> ClawActionResult): ReplayResult {
        val actions = recording.actions
        if (actions.isEmpty()) {
            return ReplayResult(
                success = true,
                completedActions = 0,
                failedActionIndex = -1,
                errorMessage = null
            )
        }

        var completed = 0
        var failedIndex = -1
        var errorMessage: String? = null

        for ((index, action) in actions.withIndex()) {
            // 跳过 ANSWER 类型（仅文本回答，无需执行）
            if (action.type == ActionType.ANSWER) {
                completed++
                continue
            }

            // 执行动作
            var result = executeSafely(action, adapter)

            // 智能适配：文本点击失败时，降级为查找并点击
            if (!result.success && action.type == ActionType.SCREEN_CLICK_TEXT) {
                val adapted = adaptClickTextToFindAndClick(action)
                if (adapted != null) {
                    result = executeSafely(adapted, adapter)
                }
            }

            if (!result.success) {
                failedIndex = index
                errorMessage = result.message
                break
            }
            completed++
        }

        val success = failedIndex == -1

        // 更新回放统计
        updateReplayStats(recording.id, success)

        return ReplayResult(
            success = success,
            completedActions = completed,
            failedActionIndex = failedIndex,
            errorMessage = errorMessage
        )
    }

    /**
     * 安全执行单条动作，捕获执行器抛出的异常。
     *
     * @param action  待执行的动作
     * @param adapter 动作执行器
     * @return 执行结果，异常时返回失败结果
     */
    private fun executeSafely(
        action: ClawAction,
        adapter: (ClawAction) -> ClawActionResult
    ): ClawActionResult {
        return try {
            adapter(action)
        } catch (e: Exception) {
            ClawActionResult.failure("回放执行异常: ${e.message}")
        }
    }

    /**
     * 智能适配：将 SCREEN_CLICK_TEXT 转换为 SCREEN_FIND_AND_CLICK。
     *
     * 应对应用版本更新后，目标文本不在可视区域内、需要滚动查找的场景。
     * 两者均使用 text 参数，参数可直接复用。
     *
     * @param action 原始文本点击动作
     * @return 适配后的查找并点击动作，无文本参数时返回 null
     */
    private fun adaptClickTextToFindAndClick(action: ClawAction): ClawAction? {
        val text = action.text ?: return null
        return action.copy(
            actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
            description = "智能适配: 文本点击→查找并点击「$text」"
        )
    }

    /**
     * 更新录制的回放统计（回放次数、最后回放时间、成功率）。
     *
     * 成功率计算：newRate = (oldRate * oldCount + score) / newCount，
     * 其中 score 为 1.0（成功）或 0.0（失败）。
     *
     * @param recordingId 录制 ID
     * @param success     本次回放是否成功
     */
    private fun updateReplayStats(recordingId: String, success: Boolean) {
        recordings.computeIfPresent(recordingId) { _, recording ->
            val newCount = recording.replayCount + 1
            val score = if (success) 1.0f else 0.0f
            val newRate = if (recording.replayCount == 0) {
                score
            } else {
                (recording.replaySuccessRate * recording.replayCount + score) / newCount
            }
            recording.copy(
                replayCount = newCount,
                lastReplayed = System.currentTimeMillis(),
                replaySuccessRate = newRate
            )
        }
    }

    // =========================================================================
    //  录制管理
    // =========================================================================

    /**
     * 根据 ID 获取录制。
     *
     * @param id 录制 ID
     * @return 对应的录制，不存在返回 null
     */
    fun getRecording(id: String): Recording? = recordings[id]

    /**
     * 列出录制，可按类别筛选。
     *
     * 结果按创建时间降序排列（最新在前）。若指定 category，仅返回该类别
     * 的录制；否则返回全部录制。
     *
     * @param category 录制类别（可选，null 表示不筛选）
     * @return 录制列表（按创建时间降序）
     */
    fun listRecordings(category: RecordingCategory? = null): List<Recording> {
        return recordings.values
            .filter { category == null || it.category == category }
            .sortedByDescending { it.createdAt }
    }

    /**
     * 删除指定录制。
     *
     * @param id 录制 ID
     * @return true 表示删除成功（录制存在），false 表示录制不存在
     */
    fun deleteRecording(id: String): Boolean {
        return recordings.remove(id) != null
    }

    // =========================================================================
    //  导入导出
    // =========================================================================

    /**
     * 将录制导出为 JSON 字符串。
     *
     * 用于备份、分享或跨设备迁移。导出内容包含录制的全部字段（含动作
     * 序列、结果、元数据和回放统计）。
     *
     * @param id 录制 ID
     * @return JSON 字符串，录制不存在时返回空字符串
     */
    fun exportRecording(id: String): String {
        val recording = recordings[id] ?: return ""
        return runCatching { json.encodeToString(recording) }.getOrElse { "" }
    }

    /**
     * 从 JSON 字符串导入录制。
     *
     * 解析 JSON 并将录制存入存储。若 ID 与已有录制冲突，则覆盖。导入
     * 后受最大条目数限制，超出时按最久未回放时间 LRU 淘汰。
     *
     * @param jsonStr JSON 字符串（由 [exportRecording] 生成）
     * @return 导入的录制，解析失败时返回 null
     */
    fun importRecording(jsonStr: String): Recording? {
        val recording = runCatching {
            json.decodeFromString(Recording.serializer(), jsonStr)
        }.getOrNull() ?: return null

        recordings[recording.id] = recording
        enforceMaxRecordings()

        return recording
    }

    // =========================================================================
    //  自动分类
    // =========================================================================

    /**
     * 根据动作类型自动推断录制类别。
     *
     * 对每条动作按其类型投票，取得票数最多的类别作为录制类别。投票规则：
     * - COMMUNICATION：发送通知、复制剪贴板，或打开通信类应用
     *   （微信、QQ、钉钉、飞书、企业微信、微博、知乎等）
     * - ENTERTAINMENT：媒体控制，或打开娱乐类应用
     *   （抖音、快手、B站、网易云音乐、QQ音乐、视频类应用等）
     * - WORKFLOW：文件读写、Shell 执行，或打开办公类应用
     *   （钉钉、企业微信、飞书、WPS 等）
     * - DAILY_ROUTINE：音量/亮度设置、清理缓存、截屏、按键、关闭应用
     * - 无匹配票数时返回 CUSTOM
     *
     * @param actions 动作列表
     * @return 推断的录制类别
     */
    fun autoCategorize(actions: List<ClawAction>): RecordingCategory {
        if (actions.isEmpty()) return RecordingCategory.CUSTOM

        val scores = mutableMapOf<RecordingCategory, Int>()

        for (action in actions) {
            val type = action.type ?: continue
            val category = when {
                // 通信类
                type == ActionType.NOTIFY_SEND || type == ActionType.CLIPBOARD_COPY ->
                    RecordingCategory.COMMUNICATION
                type == ActionType.APP_OPEN && isCommunicationApp(action) ->
                    RecordingCategory.COMMUNICATION

                // 娱乐类
                type == ActionType.MEDIA_CONTROL ->
                    RecordingCategory.ENTERTAINMENT
                type == ActionType.APP_OPEN && isEntertainmentApp(action) ->
                    RecordingCategory.ENTERTAINMENT

                // 工作流类
                type == ActionType.FILE_READ || type == ActionType.FILE_WRITE ||
                    type == ActionType.SHELL_EXEC ->
                    RecordingCategory.WORKFLOW
                type == ActionType.APP_OPEN && isWorkApp(action) ->
                    RecordingCategory.WORKFLOW

                // 日常惯例类
                type == ActionType.SYSTEM_SET_VOLUME ||
                    type == ActionType.SYSTEM_SET_BRIGHTNESS ||
                    type == ActionType.SYSTEM_CLEAR_CACHE ||
                    type == ActionType.SCREEN_SCREENSHOT ||
                    type == ActionType.SCREEN_KEY ||
                    type == ActionType.APP_CLOSE ->
                    RecordingCategory.DAILY_ROUTINE

                else -> null
            }
            if (category != null) {
                scores[category] = (scores[category] ?: 0) + 1
            }
        }

        return scores.maxByOrNull { it.value }?.key ?: RecordingCategory.CUSTOM
    }

    /** 判断动作是否打开通信类应用。 */
    private fun isCommunicationApp(action: ClawAction): Boolean {
        val pkg = action.packageName?.lowercase() ?: return false
        return COMMUNICATION_PACKAGES.any { pkg.contains(it) }
    }

    /** 判断动作是否打开娱乐类应用。 */
    private fun isEntertainmentApp(action: ClawAction): Boolean {
        val pkg = action.packageName?.lowercase() ?: return false
        return ENTERTAINMENT_PACKAGES.any { pkg.contains(it) }
    }

    /** 判断动作是否打开办公类应用。 */
    private fun isWorkApp(action: ClawAction): Boolean {
        val pkg = action.packageName?.lowercase() ?: return false
        return WORK_PACKAGES.any { pkg.contains(it) }
    }

    /** 通信类应用包名关键词。 */
    private val COMMUNICATION_PACKAGES = setOf(
        "tencent.mm", "tencent.mobileqq", "alibaba.android.rimet",
        "ss.android.lark", "tencent.wework", "android.mms",
        "sinaweibo", "zhihu"
    )

    /** 娱乐类应用包名关键词。 */
    private val ENTERTAINMENT_PACKAGES = setOf(
        "ss.android.ugc.aweme", "smile.gifmaker", "danmaku.bili",
        "netease.cloudmusic", "tencent.qqmusic", "tencent.qqlive",
        "qiyi.video", "youku.phone", "ximalaya", "kugou", "kuwo",
        "hunantv", "ss.android.article", "dragon.read", "kmxs.reader"
    )

    /** 办公类应用包名关键词。 */
    private val WORK_PACKAGES = setOf(
        "alibaba.android.rimet", "ss.android.lark", "tencent.wework",
        "wps.moffice", "dingtalk"
    )

    // =========================================================================
    //  维护方法
    // =========================================================================

    /** 清空所有录制。 */
    fun clear() {
        recordings.clear()
    }

    /**
     * 获取录制统计摘要。
     *
     * 汇总录制总数、各类别数量、回放总次数和平均成功率，用于 UI 展示
     * 和调试。
     *
     * @return 多行摘要文本
     */
    fun getSummary(): String {
        val total = recordings.size
        val byCategory = RecordingCategory.entries.associateWith { cat ->
            recordings.values.count { it.category == cat }
        }
        val totalReplays = recordings.values.sumOf { it.replayCount }
        val avgSuccessRate = if (recordings.isNotEmpty()) {
            recordings.values.map { it.replaySuccessRate }.average()
        } else {
            0.0
        }

        return buildString {
            appendLine("===== 动作录制器 =====")
            appendLine("录制总数: $total / $maxRecordings")
            appendLine("回放总次数: $totalReplays")
            appendLine("平均成功率: ${"%.1f".format(avgSuccessRate * 100)}%")
            appendLine("各类别数量:")
            byCategory.forEach { (cat, count) ->
                appendLine("  ${cat.displayName}: $count")
            }
        }
    }

    // =========================================================================
    //  私有方法
    // =========================================================================

    /**
     * 强制录制条目数不超过上限。
     *
     * 超出时按最久未使用时间 LRU 淘汰：以 lastReplayed（从未回放则回退
     * 到 createdAt）作为主排序键，createdAt 作为次排序键，移除值最小的
     * 录制。从未回放的录制优先被淘汰，已回放的录制按最后回放时间淘汰。
     */
    private fun enforceMaxRecordings() {
        while (recordings.size > maxRecordings) {
            val oldest = recordings.values.minWithOrNull(
                compareBy(
                    { it.lastReplayed ?: it.createdAt },
                    { it.createdAt }
                )
            )
            oldest?.let { recordings.remove(it.id) } ?: break
        }
    }
}


// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 录制类别。
 *
 * 用于对录制进行分类管理，支持按类别筛选和展示。由 [ActionRecorder.autoCategorize]
 * 根据动作类型自动推断，也可由用户手动指定。
 *
 * @property displayName 中文显示名
 */
enum class RecordingCategory(val displayName: String) {
    /** 日常惯例：音量、亮度、截屏、清理缓存、按键、关闭应用等日常操作。 */
    DAILY_ROUTINE("日常惯例"),
    /** 工作流：文件读写、Shell 命令、办公应用相关操作。 */
    WORKFLOW("工作流"),
    /** 娱乐：媒体控制、视频/音乐类应用操作。 */
    ENTERTAINMENT("娱乐"),
    /** 通信：发送通知、剪贴板操作、社交/通讯应用操作。 */
    COMMUNICATION("通信"),
    /** 自定义：无法自动归类的录制。 */
    CUSTOM("自定义")
}


// =============================================================================
//  数据模型
// =============================================================================

/**
 * 一条完整的动作录制。
 *
 * 记录一次成功任务执行的完整动作序列及其元数据，可用于后续回放。
 * 通过 [ActionRecorder.exportRecording] 和 [ActionRecorder.importRecording]
 * 支持 JSON 序列化，方便备份和跨设备迁移。
 *
 * @param id                录制唯一标识（UUID）
 * @param name              录制名称（用户自定义，用于按名称查找）
 * @param category          录制类别
 * @param userCommand       用户原始指令
 * @param actions           录制的动作序列（按执行顺序）
 * @param results           各动作的执行结果（与 actions 一一对应）
 * @param createdAt         录制创建时间戳（毫秒）
 * @param durationMs        录制总耗时（毫秒）
 * @param appContext        录制时的前台应用包名（可选，用于上下文匹配）
 * @param replayCount       回放次数
 * @param lastReplayed      最后回放时间戳（毫秒），从未回放为 null
 * @param replaySuccessRate 回放成功率（0.0-1.0）
 */
@Serializable
data class Recording(
    @SerialName("id")
    val id: String = "",
    @SerialName("name")
    val name: String = "",
    @SerialName("category")
    val category: RecordingCategory = RecordingCategory.CUSTOM,
    @SerialName("user_command")
    val userCommand: String = "",
    @SerialName("actions")
    val actions: List<ClawAction> = emptyList(),
    @SerialName("results")
    val results: List<ClawActionResult> = emptyList(),
    @SerialName("created_at")
    val createdAt: Long = 0L,
    @SerialName("duration_ms")
    val durationMs: Long = 0L,
    @SerialName("app_context")
    val appContext: String? = null,
    @SerialName("replay_count")
    val replayCount: Int = 0,
    @SerialName("last_replayed")
    val lastReplayed: Long? = null,
    @SerialName("replay_success_rate")
    val replaySuccessRate: Float = 0.0f
)

/**
 * 回放结果。
 *
 * 由 [ActionRecorder.replay] 返回，描述回放的执行情况。
 *
 * @param success            是否全部动作成功执行
 * @param completedActions   成功完成的动作数（含跳过的 ANSWER 动作）
 * @param failedActionIndex  失败动作的索引，无失败时为 -1
 * @param errorMessage       失败时的错误信息，成功时为 null
 */
@Serializable
data class ReplayResult(
    @SerialName("success")
    val success: Boolean = false,
    @SerialName("completed_actions")
    val completedActions: Int = 0,
    @SerialName("failed_action_index")
    val failedActionIndex: Int = -1,
    @SerialName("error_message")
    val errorMessage: String? = null
)
