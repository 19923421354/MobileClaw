package com.mobileclaw.app.ai

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================================================
//  ConversationMemory - 对话上下文记忆（增强版）
// =============================================================================

/**
 * 对话上下文记忆管理器（增强版）。
 *
 * **新增功能**：
 * 1. **消息计数器**：记录已处理的消息数量，支持按频率触发自动总结
 * 2. **记忆持久化**：支持将记忆序列化到 SharedPreferences，跨会话保留
 * 3. **频率触发回调**：当消息数量达到设定频率时，自动触发总结回调
 * 4. **可配置条目数**：用户可自定义保留的最大条目数
 *
 * 原有功能：
 * - 记录最近的用户指令、AI 返回的动作、执行结果以及手机状态快照
 * - 采用滑动窗口策略，只保留最近 [maxEntries] 条记录，控制内存与 Token 占用
 * - 提供 [buildContextSummary] 生成简洁的上下文摘要，供系统提示词引用
 * - 线程安全：所有读写操作通过 synchronized 保护
 */
class ConversationMemory(
    private var maxEntries: Int = 5
) {
    companion object {
        private const val TAG = "ConversationMemory"
        private const val PREFS_NAME = "mobileclaw"
        private const val KEY_MEMORY_JSON = "memory_persisted_json"
        private const val KEY_MESSAGE_COUNT = "memory_message_count"
    }

    /** 单条对话记忆条目。 */
    data class MemoryEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val userCommand: String,
        val actions: List<String>,
        val success: Boolean,
        val summary: String,
        val phoneStateSummary: String
    )

    private val _entries = mutableListOf<MemoryEntry>()
    val entries: List<MemoryEntry> get() = synchronized(_entries) { _entries.toList() }

    /** 消息计数器：记录自上次总结以来处理的消息数量。 */
    @Volatile
    private var messageCount: Int = 0

    /** 获取当前消息计数。 */
    fun getMessageCount(): Int = synchronized(this) { messageCount }

    /** 自动总结触发回调。当消息数达到频率阈值时调用。 */
    @Volatile
    var onSummaryTrigger: ((entries: List<MemoryEntry>, messageCount: Int) -> Unit)? = null

    /**
     * 更新最大条目数（运行时动态调整）。
     * 如果新的最大值小于当前条目数，自动裁剪旧条目。
     */
    fun updateMaxEntries(newMax: Int) {
        synchronized(_entries) {
            maxEntries = newMax
            while (_entries.size > maxEntries) {
                _entries.removeAt(0)
            }
        }
    }

    /** 添加一条对话记忆，并增加消息计数。 */
    fun add(entry: MemoryEntry) {
        synchronized(_entries) {
            _entries.add(entry)
            if (_entries.size > maxEntries) {
                _entries.removeAt(0)
            }
        }
        // 增加消息计数
        synchronized(this) {
            messageCount++
        }
        Log.d(TAG, "记忆已添加，当前条目: ${_entries.size}, 消息计数: $messageCount")
    }

    /** 清空记忆，同时重置消息计数器。 */
    fun clear() {
        synchronized(_entries) { _entries.clear() }
        synchronized(this) { messageCount = 0 }
        Log.d(TAG, "记忆已清空，消息计数器已重置")
    }

    /**
     * 检查是否达到总结触发频率。
     *
     * @param frequency 用户设定的总结频率（消息间隔数），0 表示仅手动
     * @return 达到频率返回 true，并重置计数器
     */
    fun checkAndResetSummaryTrigger(frequency: Int): Boolean {
        if (frequency <= 0) return false
        synchronized(this) {
            if (messageCount >= frequency) {
                messageCount = 0
                return true
            }
            return false
        }
    }

    /**
     * 手动触发总结回调。
     * 供 UI 手动总结时调用，不会重置计数器。
     */
    fun triggerManualSummary() {
        val currentEntries = entries
        if (currentEntries.isEmpty()) return
        onSummaryTrigger?.invoke(currentEntries, messageCount)
    }

    /**
     * 生成上下文摘要，供系统提示词引用。
     * 格式简洁，控制 Token 用量。
     */
    fun buildContextSummary(): String {
        val list = entries
        if (list.isEmpty()) return ""

        return buildString {
            appendLine("==最近对话历史==")
            list.forEachIndexed { index, entry ->
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
                val resultStr = if (entry.success) "成功" else "失败"
                appendLine("[$timeStr] 用户: ${entry.userCommand.take(50)}")
                appendLine("  动作: ${entry.actions.joinToString(",").take(80)} -> $resultStr")
            }
            appendLine("==历史结束==")
        }
    }

    /** 检查用户是否在重复同一指令（可能上次执行失败）。 */
    fun isRepeatedCommand(command: String, windowMs: Long = 60000): Boolean {
        val now = System.currentTimeMillis()
        return entries.any { entry ->
            now - entry.timestamp < windowMs &&
            entry.userCommand.contains(command, ignoreCase = true) &&
            !entry.success
        }
    }

    // =========================================================================
    //  持久化方法
    // =========================================================================

    /**
     * 将记忆序列化为 JSON 并保存到 SharedPreferences。
     *
     * @param context Android 上下文
     */
    fun persist(context: Context) {
        try {
            val json = serializeToJson()
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_MEMORY_JSON, json)
                .putInt(KEY_MESSAGE_COUNT, messageCount)
                .apply()
            Log.d(TAG, "记忆已持久化 (${_entries.size} 条, 消息计数: $messageCount)")
        } catch (e: Exception) {
            Log.e(TAG, "记忆持久化失败", e)
        }
    }

    /**
     * 从 SharedPreferences 恢复记忆。
     *
     * @param context Android 上下文
     * @return 成功恢复返回 true
     */
    fun restore(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_MEMORY_JSON, null) ?: return false
            deserializeFromJson(json)
            messageCount = prefs.getInt(KEY_MESSAGE_COUNT, 0)
            Log.d(TAG, "记忆已恢复 (${_entries.size} 条, 消息计数: $messageCount)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "记忆恢复失败", e)
            false
        }
    }

    /**
     * 清除持久化的记忆数据。
     */
    fun clearPersisted(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_MEMORY_JSON)
            .remove(KEY_MESSAGE_COUNT)
            .apply()
        Log.d(TAG, "持久化记忆已清除")
    }

    /**
     * 将记忆序列化为 JSON 字符串。
     * 格式：{"entries": [...], "version": 1}
     */
    private fun serializeToJson(): String {
        val sb = StringBuilder()
        sb.append("{\"version\":1,\"entries\":[")
        val list = entries
        list.forEachIndexed { index, entry ->
            if (index > 0) sb.append(",")
            sb.append("{")
            sb.append("\"ts\":${entry.timestamp},")
            sb.append("\"cmd\":${jsonEscape(entry.userCommand)},")
            sb.append("\"acts\":[")
            entry.actions.forEachIndexed { ai, a ->
                if (ai > 0) sb.append(",")
                sb.append(jsonEscape(a))
            }
            sb.append("],")
            sb.append("\"ok\":${entry.success},")
            sb.append("\"sum\":${jsonEscape(entry.summary)},")
            sb.append("\"phone\":${jsonEscape(entry.phoneStateSummary)}")
            sb.append("}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /**
     * 从 JSON 字符串恢复记忆。
     */
    private fun deserializeFromJson(json: String) {
        try {
            // 简单 JSON 解析，不依赖外部库
            val entries = mutableListOf<MemoryEntry>()
            // 提取 entries 数组部分
            val entriesStart = json.indexOf("\"entries\":[")
            if (entriesStart == -1) return
            val arrayStart = json.indexOf('[', entriesStart)
            val arrayEnd = json.lastIndexOf(']')
            if (arrayStart == -1 || arrayEnd == -1 || arrayEnd <= arrayStart) return

            val arrayContent = json.substring(arrayStart + 1, arrayEnd)
            if (arrayContent.isBlank()) return

            // 逐个解析对象
            var depth = 0
            var objStart = -1
            for (i in arrayContent.indices) {
                val c = arrayContent[i]
                when (c) {
                    '{' -> {
                        if (depth == 0) objStart = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && objStart >= 0) {
                            val objStr = arrayContent.substring(objStart, i + 1)
                            parseEntry(objStr)?.let { entries.add(it) }
                            objStart = -1
                        }
                    }
                }
            }

            synchronized(_entries) {
                _entries.clear()
                _entries.addAll(entries)
                // 裁剪到 maxEntries
                while (_entries.size > maxEntries) {
                    _entries.removeAt(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON 解析失败", e)
        }
    }

    private fun parseEntry(obj: String): MemoryEntry? {
        return try {
            val ts = extractLong(obj, "\"ts\":")
            val cmd = extractString(obj, "\"cmd\":")
            val ok = extractBoolean(obj, "\"ok\":")
            val sum = extractString(obj, "\"sum\":")
            val phone = extractString(obj, "\"phone\":")

            // 解析 actions 数组
            val actions = mutableListOf<String>()
            val actsStart = obj.indexOf("\"acts\":[")
            if (actsStart >= 0) {
                val arrStart = obj.indexOf('[', actsStart)
                val arrEnd = obj.indexOf(']', arrStart)
                if (arrStart >= 0 && arrEnd > arrStart) {
                    val content = obj.substring(arrStart + 1, arrEnd)
                    if (content.isNotBlank()) {
                        // 以引号分割提取字符串
                        val parts = content.split(",")
                        parts.forEach { p ->
                            val trimmed = p.trim().removeSurrounding("\"")
                            if (trimmed.isNotBlank()) actions.add(trimmed)
                        }
                    }
                }
            }

            if (cmd.isNullOrBlank()) return null

            MemoryEntry(
                timestamp = if (ts != null) ts else System.currentTimeMillis(),
                userCommand = cmd,
                actions = actions,
                success = ok ?: true,
                summary = sum ?: "",
                phoneStateSummary = phone ?: ""
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractLong(json: String, key: String): Long? {
        val idx = json.indexOf(key) ?: return null
        if (idx == -1) return null
        val start = idx + key.length
        val end = json.indexOfAny(charArrayOf(',', '}', ']'), start)
        if (end == -1) return null
        return json.substring(start, end).trim().toLongOrNull()
    }

    private fun extractString(json: String, key: String): String? {
        val idx = json.indexOf(key) ?: return null
        if (idx == -1) return null
        val quoteStart = json.indexOf('"', idx + key.length)
        if (quoteStart == -1) return null
        val quoteEnd = json.indexOf('"', quoteStart + 1)
        if (quoteEnd == -1) return null
        return json.substring(quoteStart + 1, quoteEnd)
    }

    private fun extractBoolean(json: String, key: String): Boolean? {
        val idx = json.indexOf(key) ?: return null
        if (idx == -1) return null
        val start = idx + key.length
        val end = json.indexOfAny(charArrayOf(',', '}', ']'), start)
        if (end == -1) return null
        val value = json.substring(start, end).trim()
        return when (value) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private fun jsonEscape(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

// =============================================================================
//  SmartRecovery - 智能错误恢复
// =============================================================================

/**
 * 智能错误恢复策略管理器。
 *
 * 当某个动作执行失败时，根据动作类型和失败原因，自动生成替代方案。
 * 这是一个「策略库」，不直接执行动作，只提供恢复建议。
 */
object SmartRecovery {

    /**
     * 根据失败的动作和结果，生成替代动作列表。
     *
     * @param failedAction 执行失败的动作
     * @param errorResult 失败结果
     * @param screenText 当前屏幕文本（用于判断可用替代方案）
     * @return 替代动作列表（可能为空表示无自动恢复方案）
     */
    fun suggestRecovery(
        failedAction: ClawAction,
        errorResult: ClawActionResult,
        screenText: String
    ): List<ClawAction> {
        val error = errorResult.message

        return when (failedAction.type) {
            // APP_OPEN 失败 -> 尝试 APP_SEARCH
            ActionType.APP_OPEN -> {
                val name = failedAction.name ?: failedAction.packageName
                if (!name.isNullOrEmpty() && error.contains("未安装")) {
                    listOf(ClawAction(
                        actionName = ActionType.APP_SEARCH.name,
                        params = JsonObject(mapOf("name" to JsonPrimitive(name))),
                        description = "应用可能未安装，尝试搜索打开"
                    ))
                } else emptyList()
            }

            // SCREEN_CLICK_TEXT 失败 -> 尝试 FIND_AND_CLICK（带滚动）
            ActionType.SCREEN_CLICK_TEXT -> {
                val text = failedAction.text
                if (!text.isNullOrEmpty()) {
                    listOf(
                        ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(1000))),
                            description = "等待页面加载"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
                            params = JsonObject(mapOf("text" to JsonPrimitive(text))),
                            description = "滚动查找并点击「$text」"
                        )
                    )
                } else emptyList()
            }

            // SCREEN_INPUT 失败 -> 先点击输入框再输入
            ActionType.SCREEN_INPUT -> {
                val text = failedAction.text
                if (text != null) {
                    listOf(
                        ClawAction(
                            actionName = ActionType.SCREEN_KEY.name,
                            params = JsonObject(mapOf("key" to JsonPrimitive("BACK"))),
                            description = "先返回取消当前焦点"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(500))),
                            description = "短暂等待"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_INPUT.name,
                            params = JsonObject(mapOf("text" to JsonPrimitive(text))),
                            description = "重新尝试输入"
                        )
                    )
                } else emptyList()
            }

            // SCREEN_FIND_AND_CLICK 失败 -> 尝试滑动后重试
            ActionType.SCREEN_FIND_AND_CLICK -> {
                val text = failedAction.text
                if (!text.isNullOrEmpty()) {
                    listOf(
                        ClawAction(
                            actionName = ActionType.SCREEN_SWIPE.name,
                            params = JsonObject(mapOf("direction" to JsonPrimitive("UP"))),
                            description = "向上滑动查看更多内容"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(800))),
                            description = "等待滑动完成"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
                            params = JsonObject(mapOf("text" to JsonPrimitive(text))),
                            description = "再次尝试查找并点击"
                        )
                    )
                } else emptyList()
            }

            else -> emptyList()
        }
    }

    /**
     * 判断错误是否可自动恢复。
     */
    fun isRecoverable(errorResult: ClawActionResult): Boolean {
        val msg = errorResult.message
        return msg.contains("未找到") || msg.contains("失败") || msg.contains("未安装") ||
               msg.contains("超时") || msg.contains("未获取焦点")
    }
}

// =============================================================================
//  QuickCommands - 快捷指令模板
// =============================================================================

/**
 * 快捷指令模板库。
 *
 * 预定义常见操作的快捷指令，用户输入匹配模板关键词时，
 * 可直接生成对应的动作序列，跳过 AI 解析步骤，节省 Token 和时间。
 */
object QuickCommands {

    /** 快捷指令定义。 */
    data class QuickCommand(
        val keywords: List<String>,
        val description: String,
        val generateActions: () -> List<ClawAction>
    )

    /** 所有预定义快捷指令。 */
    private val commands = listOf(
        QuickCommand(
            keywords = listOf("截图", "截屏", "screenshot"),
            description = "截取当前屏幕"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_SCREENSHOT.name,
                params = JsonObject(emptyMap()),
                description = "截取当前屏幕"
            ))
        },
        QuickCommand(
            keywords = listOf("返回", "回去", "back"),
            description = "按下返回键"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("BACK"))),
                description = "按下返回键"
            ))
        },
        QuickCommand(
            keywords = listOf("回桌面", "回到桌面", "home", "主屏"),
            description = "回到主屏幕"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("HOME"))),
                description = "回到主屏幕"
            ))
        },
        QuickCommand(
            keywords = listOf("最近任务", "多任务", "recents", "任务管理器"),
            description = "打开最近任务"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("RECENTS"))),
                description = "打开最近任务"
            ))
        },
        QuickCommand(
            keywords = listOf("锁屏", "锁屏", "lock"),
            description = "锁屏"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("LOCK_SCREEN"))),
                description = "锁屏"
            ))
        },
        QuickCommand(
            keywords = listOf("通知栏", "下拉通知", "notifications"),
            description = "展开通知栏"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("NOTIFICATION_PANEL"))),
                description = "展开通知栏"
            ))
        },
        QuickCommand(
            keywords = listOf("音量加", "大声点", "音量大", "volume up"),
            description = "音量增大"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("VOLUME_UP"))),
                description = "音量增大"
            ))
        },
        QuickCommand(
            keywords = listOf("音量减", "小声点", "音量小", "volume down"),
            description = "音量减小"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("VOLUME_DOWN"))),
                description = "音量减小"
            ))
        },
        QuickCommand(
            keywords = listOf("系统信息", "手机状态", "设备信息", "system info"),
            description = "获取系统综合信息"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SYSTEM_GET_INFO.name,
                params = JsonObject(emptyMap()),
                description = "获取系统综合信息"
            ))
        },
        QuickCommand(
            keywords = listOf("清理缓存", "清缓存", "clear cache"),
            description = "清理应用缓存"
        ) {
            listOf(ClawAction(
                actionName = ActionType.SYSTEM_CLEAR_CACHE.name,
                params = JsonObject(emptyMap()),
                description = "清理应用缓存"
            ))
        },
        QuickCommand(
            keywords = listOf("播放", "暂停", "play", "pause", "音乐控制"),
            description = "播放/暂停媒体"
        ) {
            listOf(ClawAction(
                actionName = ActionType.MEDIA_CONTROL.name,
                params = JsonObject(mapOf("mediaAction" to JsonPrimitive("PLAY_PAUSE"))),
                description = "播放/暂停媒体"
            ))
        },
        QuickCommand(
            keywords = listOf("下一首", "next track", "切歌"),
            description = "下一首"
        ) {
            listOf(ClawAction(
                actionName = ActionType.MEDIA_CONTROL.name,
                params = JsonObject(mapOf("mediaAction" to JsonPrimitive("NEXT"))),
                description = "下一首"
            ))
        },
        QuickCommand(
            keywords = listOf("上一首", "previous track", "上一曲"),
            description = "上一首"
        ) {
            listOf(ClawAction(
                actionName = ActionType.MEDIA_CONTROL.name,
                params = JsonObject(mapOf("mediaAction" to JsonPrimitive("PREVIOUS"))),
                description = "上一首"
            ))
        }
    )

    /**
     * 尝试匹配快捷指令。
     *
     * @param userInput 用户输入
     * @return 匹配到的快捷指令动作列表，未匹配返回 null
     */
    fun match(userInput: String): List<ClawAction>? {
        val input = userInput.trim().lowercase()
        for (cmd in commands) {
            if (cmd.keywords.any { keyword ->
                    input == keyword.lowercase() || input.contains(keyword.lowercase())
                }) {
                return cmd.generateActions()
            }
        }
        return null
    }

    /** 获取所有快捷指令的描述列表（用于 UI 展示）。 */
    fun getAllDescriptions(): List<String> =
        commands.map { "${it.keywords.first()}: ${it.description}" }
}

// =============================================================================
//  TaskPattern - 任务模式识别
// =============================================================================

/**
 * 任务模式识别器。
 *
 * 识别常见任务模式并返回预设的优化策略，减少 AI 解析次数。
 */
object TaskPattern {

    /** 识别结果。 */
    data class PatternMatch(
        val pattern: String,
        val confidence: Float,
        val suggestedFirstActions: List<ClawAction>
    )

    /** 应用打开模式："打开XXX" / "启动XXX" / "打开XXX应用" */
    private val appOpenPattern = Regex("(?:打开|启动|开启|运行|launch|open)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?$", RegexOption.IGNORE_CASE)

    /** 应用搜索模式："在XXX搜索YYY" / "用XXX搜索YYY" */
    private val searchInAppPattern = Regex("(?:在|用|使用)\\s*[「「【]?(.+?)[」」】]?\\s*(?:里|中|里面)?\\s*搜索\\s*(.+)", RegexOption.IGNORE_CASE)

    /** 发消息模式："用XXX给YYY发ZZZ" / "通过XXX发送消息给YYY" */
    val sendMessagePattern = Regex("(?:用|通过|使用)\\s*[「「【]?(.+?)[」」】]?\\s*给\\s*[「「【]?(.+?)[」」】]?\\s*(?:发|发送)\\s*(?:消息|信息)?\\s*[「「【]?(.+?)[」」】]?$", RegexOption.IGNORE_CASE)

    /**
     * 尝试识别任务模式。
     *
     * @param userInput 用户输入
     * @return 识别结果，未识别返回 null
     */
    fun match(userInput: String): PatternMatch? {
        // 发消息模式（最复杂，优先匹配）
        sendMessagePattern.find(userInput)?.let { match ->
            val appName = match.groupValues[1].trim()
            val contact = match.groupValues[2].trim()
            val message = match.groupValues[3].trim()
            return PatternMatch(
                pattern = "SEND_MESSAGE",
                confidence = 0.9f,
                suggestedFirstActions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf("name" to JsonPrimitive(appName))),
                        description = "打开$appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用启动"
                    )
                )
            )
        }

        // 应用内搜索模式
        searchInAppPattern.find(userInput)?.let { match ->
            val appName = match.groupValues[1].trim()
            return PatternMatch(
                pattern = "SEARCH_IN_APP",
                confidence = 0.85f,
                suggestedFirstActions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf("name" to JsonPrimitive(appName))),
                        description = "打开$appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用启动"
                    )
                )
            )
        }

        // 打开应用模式
        appOpenPattern.find(userInput)?.let { match ->
            val appName = match.groupValues[1].trim()
            // 检查是否是已知包名
            val knownPackage = APP_PACKAGE_MAP.entries.find { appName.contains(it.key, ignoreCase = true) }?.value
            return PatternMatch(
                pattern = "OPEN_APP",
                confidence = 0.95f,
                suggestedFirstActions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = if (knownPackage != null) {
                            JsonObject(mapOf("packageName" to JsonPrimitive(knownPackage)))
                        } else {
                            JsonObject(mapOf("name" to JsonPrimitive(appName)))
                        },
                        description = "打开$appName"
                    )
                )
            )
        }

        return null
    }

    /** 常用应用包名映射（与 ActionTranslator 中的映射保持一致）。 */
    private val APP_PACKAGE_MAP = mapOf(
        "微信" to "com.tencent.mm",
        "抖音" to "com.ss.android.ugc.aweme",
        "QQ" to "com.tencent.mobileqq",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "快手" to "com.smile.gifmaker",
        "B站" to "tv.danmaku.bili",
        "小红书" to "com.xingin.xhs",
        "美团" to "com.sankuai.meituan",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "知乎" to "com.zhihu.android",
        "微博" to "com.sina.weibo",
        "钉钉" to "com.alibaba.android.rimet",
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "今日头条" to "com.ss.android.article.news",
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",
        "设置" to "com.android.settings",
        "飞书" to "com.ss.android.lark",
        "企业微信" to "com.tencent.wework",
        "百度" to "com.baidu.searchbox",
        "夸克" to "com.quark.browser",
        "豆包" to "com.larus.nova",
        "滴滴" to "com.sdu.didi.psnger",
        "携程" to "ctrip.android.view",
        "12306" to "com.MobileTicket",
        "天猫" to "com.tmall.wireless",
        "饿了么" to "me.ele",
        "大众点评" to "com.dianping.v1",
        "百度网盘" to "com.baidu.netdisk",
        "Keep" to "com.gotokeep.keep",
        "喜马拉雅" to "com.ximalaya.ting.android",
        "WPS" to "cn.wps.moffice_eng"
    )
}

// =============================================================================
//  FeedbackOptimizer - 反馈优化器
// =============================================================================

/**
 * 反馈优化器。
 *
 * 优化发送给 AI 的执行反馈，减少 Token 用量同时保留关键信息。
 * 采用「成功动作简述 + 失败动作详述」策略。
 */
object FeedbackOptimizer {

    /**
     * 优化反馈文本。
     *
     * @param actions 本轮执行的动作列表
     * @param results 对应的执行结果列表
     * @return 优化后的反馈文本
     */
    fun optimize(
        actions: List<ClawAction>,
        results: List<Pair<ClawAction, ClawActionResult>>
    ): String {
        val successActions = results.filter { it.second.success }
        val failedActions = results.filter { !it.second.success }

        return buildString {
            if (successActions.isNotEmpty()) {
                appendLine("成功:")
                successActions.forEach { (action, result) ->
                    // 成功动作只保留简要描述
                    appendLine("- ${ActionTranslator.describeAction(action)}")
                    // 如果有返回数据且较短，保留
                    if (result.data != null && result.data.length in 1..100) {
                        appendLine("  数据: ${result.data}")
                    }
                }
            }

            if (failedActions.isNotEmpty()) {
                appendLine("失败:")
                failedActions.forEach { (action, result) ->
                    // 失败动作保留详细信息和恢复建议
                    appendLine("- ${ActionTranslator.describeAction(action)}")
                    appendLine("  错误: ${result.message}")
                    // 添加恢复建议
                    val recovery = SmartRecovery.suggestRecovery(action, result, "")
                    if (recovery.isNotEmpty()) {
                        appendLine("  建议下一步: ${recovery.joinToString(", ") { it.description }}")
                    }
                }
            }

            if (successActions.isEmpty() && failedActions.isEmpty()) {
                appendLine("无动作执行")
            }
        }
    }
}
