package com.mobileclaw.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ActionTranslator - 动作翻译器
 *
 * 负责定义 MobileClaw 支持的所有动作类型，将 AI 返回的 JSON 指令解析为
 * 结构化的 [ClawAction]，并生成描述当前手机状态与可用操作的系统提示词。
 *
 * 该类是整个 AI 指令解析网关的「语义层」，所有动作的枚举定义、参数规约、
 * JSON 编解码以及人类可读描述都集中在此处，便于统一维护与扩展。
 */
object ActionTranslator {

    /** 用于编解码 AI 返回 JSON 的 Json 实例，忽略未知字段以保证前向兼容。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** FULL 提示词（复杂任务/无限制模式）下，屏幕文本的截断长度。 */
    private const val SCREEN_TEXT_LIMIT_FULL = 1200

    /** COMPACT 提示词（中等任务）下，屏幕文本的截断长度。 */
    private const val SCREEN_TEXT_LIMIT_COMPACT = 400
    // =========================================================================
    //  解析：JSON -> ClawAction 列表
    // =========================================================================

    /**
     * 将 AI 返回的原始文本解析为 [ClawAction] 列表。
     *
     * 支持两种返回格式：
     * 1. 单步操作：`{"action": "SCREEN_CLICK_TEXT", "params": {"text": "登录"}, "description": "..."}`
     * 2. 多步操作：`{"actions": [...], "description": "..."}`
     *
     * 该方法会自动剥离可能包裹在 ```json ... ``` 代码块中的内容，并尝试
     * 定位最外层的 JSON 对象，保证对大模型「啰嗦」输出的健壮性。
     *
     * @param raw AI 返回的原始字符串
     * @return 解析得到的指令集合 [ClawCommandResult]（包含动作列表与整体描述）
     */
    fun parse(raw: String): ClawCommandResult {
        val jsonText = extractJsonBlock(raw)
        if (jsonText.isBlank()) {
            // 无法提取到 JSON，视为直接回答（兜底处理）
            return ClawCommandResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(raw.trim()))),
                        description = "AI 未返回结构化指令，原样回传"
                    )
                ),
                description = raw.trim()
            )
        }

        val result = runCatching {
            val element = json.parseToJsonElement(jsonText)
            parseElement(element)
        }.getOrElse { e ->
            ClawCommandResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(raw.trim()))),
                        description = "JSON 解析失败：${e.message}"
                    )
                ),
                description = raw.trim()
            )
        }

        // 关键过滤：移除所有 actionName 为空或无法识别的动作
        val validActions = result.actions.filter { action ->
            action.actionName.isNotBlank() && action.type != null
        }

        // 如果过滤后没有有效动作，但有原始文本 → 作为 ANSWER 返回
        if (validActions.isEmpty() && result.actions.isNotEmpty()) {
            val rawText = raw.trim()
            return ClawCommandResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(rawText))),
                        description = "AI 返回的动作均无效，原样回传文本"
                    )
                ),
                description = rawText
            )
        }

        return result.copy(actions = validActions)
    }

    /**
     * 从已解析的 JSON 元素中提取动作列表。
     * 内部使用：根据是否包含 "actions" 字段决定走单步或多步分支。
     */
    private fun parseElement(element: kotlinx.serialization.json.JsonElement): ClawCommandResult {
        val obj = element.jsonObject

        // 多步操作：包含 "actions" 数组
        if (obj.contains("actions")) {
            val actions = obj["actions"]?.let { actionsElement ->
                when (actionsElement) {
                    is JsonArray -> actionsElement.mapNotNull { item ->
                        runCatching { json.decodeFromJsonElement(ClawAction.serializer(), item) }
                            .getOrNull()
                            ?.takeIf { it.actionName.isNotBlank() }
                    }
                    else -> emptyList()
                }
            } ?: emptyList()
            val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
            return ClawCommandResult(actions = actions, description = description)
        }

        // 单步操作：必须包含 "action" 字段才算有效动作
        if (!obj.contains("action")) {
            // 没有 "action" 字段 → 不是动作指令，将原始内容作为文本回答
            val textContent = obj["text"]?.jsonPrimitive?.contentOrNull
                ?: obj["content"]?.jsonPrimitive?.contentOrNull
                ?: element.toString()
            return ClawCommandResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(textContent))),
                        description = "非动作JSON，作为文本回答"
                    )
                ),
                description = textContent
            )
        }

        val action = json.decodeFromJsonElement(ClawAction.serializer(), obj)
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: action.description
        return ClawCommandResult(actions = listOf(action), description = description)
    }

    /**
     * 从可能包含 Markdown 代码块或多余文字的原始输出中提取最外层 JSON 文本。
     * 依次尝试：```json 代码块 -> ``` 代码块 -> 第一个 { 到最后一个 }。
     */
    private fun extractJsonBlock(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""

        // 1. 尝试匹配 ```json ... ``` 或 ``` ... ```
        val fencePattern = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fencePattern.find(text)?.let { match ->
            val inner = match.groupValues[1].trim()
            if (isValidJsonStart(inner)) return inner
        }

        // 2. 直接是 JSON
        if (isValidJsonStart(text)) return text

        // 3. 从文本中截取第一个 '{' 到最后一个 '}'
        //    但必须验证：提取的内容包含 "action" 或 "actions" 关键字，
        //    否则可能是从普通文本中误提取的花括号
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start in 0 until end) {
            val candidate = text.substring(start, end + 1)
            // 验证提取的 JSON 包含动作相关字段，避免从纯文本中误提取
            if (candidate.contains("\"action\"") || candidate.contains("\"actions\"")) {
                return candidate
            }
        }
        return ""
    }

    /**
     * 检查字符串是否以有效的 JSON 起始字符开头，且不是空对象/数组。
     */
    private fun isValidJsonStart(text: String): Boolean {
        if (!text.startsWith("{") && !text.startsWith("[")) return false
        // 排除空对象和空数组
        val stripped = text.replace(Regex("\\s"), "")
        return stripped != "{}" && stripped != "[]"
    }

    // =========================================================================
    //  反向：ClawAction -> 人类可读文本 / JSON 字符串
    // =========================================================================

    /**
     * 将单个 [ClawAction] 描述为人类可读文本，供日志展示或反馈给 AI 理解执行内容。
     */
    fun describeAction(action: ClawAction): String {
        val type = action.type
        val base = type?.description ?: action.actionName
        val detail = when (type) {
            ActionType.SCREEN_CLICK -> "坐标(${action.x}, ${action.y})"
            ActionType.SCREEN_CLICK_TEXT -> "文本「${action.text ?: ""}」"
            ActionType.SCREEN_LONG_CLICK -> {
                if (!action.text.isNullOrEmpty()) "长按文本「${action.text}\""
                else "长按坐标(${action.x ?: 0}, ${action.y ?: 0})"
            }
            ActionType.SCREEN_DOUBLE_CLICK -> {
                if (!action.text.isNullOrEmpty()) "双击文本「${action.text}\""
                else "双击坐标(${action.x ?: 0}, ${action.y ?: 0})"
            }
            ActionType.SCREEN_FIND_AND_CLICK -> "查找并点击「${action.text ?: ""}」"
            ActionType.SCREEN_SWIPE -> {
                val dir = action.swipeDirectionName
                if (dir != null) "方向$dir" else "(${action.x1},${action.y1}) -> (${action.x2},${action.y2})"
            }
            ActionType.SCREEN_SCROLL_TO_TEXT -> "滚动至文本「${action.text ?: ""}」"
            ActionType.SCREEN_INPUT -> "输入「${action.text ?: ""}」"
            ActionType.SCREEN_KEY -> "按键${action.keyName ?: ""}"
            ActionType.SCREEN_SCREENSHOT -> "截取当前屏幕"
            ActionType.SCREEN_WAIT -> "等待${action.ms ?: 0}毫秒"
            ActionType.SCREEN_GET_TEXT -> "获取屏幕文本"
            ActionType.SCREEN_TEXT_EXISTS -> "检测文本「${action.text ?: ""}」是否存在"
            ActionType.APP_OPEN -> if (!action.packageName.isNullOrEmpty()) "打开应用${action.packageName}" else "打开应用「${action.name ?: ""}」"
            ActionType.APP_CLOSE -> "关闭应用${action.packageName ?: ""}"
            ActionType.APP_LIST -> "列出已安装应用"
            ActionType.APP_SEARCH -> "搜索并打开应用「${action.name ?: ""}」"
            ActionType.APP_INSTALL -> "安装应用${action.apkPath ?: ""}"
            ActionType.APP_UNINSTALL -> "卸载应用${action.packageName ?: ""}"
            ActionType.SYSTEM_GET_INFO -> "获取${action.infoType ?: "系统"}信息"
            ActionType.SYSTEM_KILL_PROCESS -> "结束进程 pid=${action.pid ?: 0}"
            ActionType.SYSTEM_CLEAR_CACHE -> "清理缓存"
            ActionType.SYSTEM_SET_VOLUME -> "设置音量为${action.volume ?: 0}"
            ActionType.SYSTEM_SET_BRIGHTNESS -> "设置亮度为${action.brightness ?: 0}"
            ActionType.CLIPBOARD_COPY -> "复制「${action.text ?: ""}」到剪贴板"
            ActionType.CLIPBOARD_PASTE -> "粘贴剪贴板内容"
            ActionType.MEDIA_CONTROL -> "媒体控制: ${action.mediaAction ?: ""}"
            ActionType.SHELL_EXEC -> "执行命令「${action.command ?: ""}」"
            ActionType.FILE_READ -> "读取文件${action.path ?: ""}"
            ActionType.FILE_WRITE -> "写入文件${action.path ?: ""}"
            ActionType.NOTIFY_READ -> "读取通知"
            ActionType.NOTIFY_SEND -> "发送通知「${action.notifyTitle ?: ""}」"
            ActionType.TIMER_SET -> "设置定时器${action.duration ?: 0}秒"
            ActionType.ANSWER -> "回答：${action.text ?: ""}"
            ActionType.CUSTOM -> "自定义动作：${action.actionName}"
            null -> action.params.toString()
        }
        val desc = if (action.description.isNotBlank()) "（${action.description}）" else ""
        return "$base：$detail$desc"
    }

    /**
     * 将 [ClawCommandResult] 序列化为 JSON 字符串（用于日志或回传 AI）。
     */
    fun encode(result: ClawCommandResult): String = json.encodeToString(result)

    /** 将单个 [ClawAction] 序列化为 JSON 字符串。 */
    fun encodeAction(action: ClawAction): String = json.encodeToString(action)

    // =========================================================================
    //  系统提示词生成
    // =========================================================================

    /**
     * 生成描述所有可用动作及当前手机状态的系统提示词（完整版）。
     *
     * 完整版在基础动作说明之上，额外包含以下智能增强：
     * - 多步操作教学：教 AI 如何分解复杂任务（如「打开抖音搜索 XXX」）
     * - 输出格式约束：永不向用户展示原始 JSON，description 字段必须人类可读
     * - 屏幕文本感知：提示 AI 优先使用 SCREEN_CLICK_TEXT 点击屏幕可见文本
     * - 任务完成标志：要求 AI 完成任务时必须返回 ANSWER 动作并给出总结
     * - 常用应用包名映射表：AI 无需猜测包名
     * - 当前手机状态与屏幕可见文本（截断 [SCREEN_TEXT_LIMIT_FULL] 字符）
     *
     * @param phoneState 当前手机状态快照
     * @return 完整的系统提示词字符串
     */
    fun generateSystemPrompt(phoneState: PhoneState): String = buildString {
        appendLine("你是MobileClaw，能直接操控Android手机的智能助手。将用户指令转为JSON指令。")
        appendLine()
        appendLine("==输出格式==")
        appendLine("只返回JSON,绝不返回纯文本。")
        appendLine("单步:{\"action\":\"类型\",\"params\":{...},\"description\":\"人类可读说明\"}")
        appendLine("多步:{\"actions\":[...],\"description\":\"整体目标说明\"}")
        appendLine()
        appendLine("==核心规则(违反将导致任务失败)==")
        appendLine("1.操作类指令(打开应用/点击/输入/发送等)首轮必须返回操作动作,禁止返回ANSWER。")
        appendLine("2.ANSWER仅用于两种情况:a)纯聊天问答不需操作手机 b)看到执行结果确认操作成功后总结。")
        appendLine("3.禁止在同一批动作中包含ANSWER。先返回操作动作,系统执行后告诉你结果,你再决定下一步。")
        appendLine("4.禁止声称已完成未执行的操作。你无法预知操作是否成功,必须先执行再确认。")
        appendLine("5.复杂任务分解为多步,每步间加SCREEN_WAIT等待。")
        appendLine("6.打开应用后必须SCREEN_WAIT至少2000ms再操作。")
        appendLine("7.优先用SCREEN_CLICK_TEXT而非坐标。找不到文本用SCREEN_FIND_AND_CLICK自动滚动查找。")
        appendLine("8.仔细看「屏幕文本」中的内容,直接使用其中出现的文本作为SCREEN_CLICK_TEXT的参数。")
        appendLine()
        appendLine("==操作流程(必须遵守)==")
        appendLine("你返回操作动作 -> 系统执行并返回结果 -> 你根据结果返回下一步操作或ANSWER")
        appendLine("例「打开抖音搜索猫咪」:")
        appendLine("  第1轮:[APP_OPEN{packageName:\"com.ss.android.ugc.aweme\"},SCREEN_WAIT{ms:2000}]")
        appendLine("  第2轮(看到结果后):[SCREEN_CLICK_TEXT{text:\"搜索\"},SCREEN_WAIT{ms:1000}]")
        appendLine("  第3轮(看到结果后):[SCREEN_INPUT{text:\"猫咪\"},SCREEN_CLICK_TEXT{text:\"搜索\"}]")
        appendLine("  第4轮(看到搜索成功):ANSWER{text:\"已打开抖音并搜索了猫咪\"}")
        appendLine("例「用微信给张三发消息你好」:")
        appendLine("  第1轮:[APP_OPEN{packageName:\"com.tencent.mm\"},SCREEN_WAIT{ms:2000}]")
        appendLine("  第2轮:[SCREEN_CLICK_TEXT{text:\"张三\"},SCREEN_WAIT{ms:1000}]")
        appendLine("  第3轮:[SCREEN_INPUT{text:\"你好\"},SCREEN_CLICK_TEXT{text:\"发送\"}]")
        appendLine("  第4轮(看到发送成功):ANSWER{text:\"已通过微信给张三发送了消息\"}")
        appendLine("注意:前几轮只有操作动作,最后一轮看到成功后才ANSWER。不确定包名时用APP_OPEN{name:\"应用名\"}或APP_SEARCH{name:\"应用名\"}。")
        appendLine()
        appendLine("==屏幕文本感知(重要)==")
        appendLine("下方「屏幕文本」列出了当前屏幕上实际可见的文本。你的SCREEN_CLICK_TEXT参数应该直接来自这些文本。")
        appendLine("如果目标文本不在「屏幕文本」中,使用SCREEN_FIND_AND_CLICK自动滚动查找,或SCREEN_SWIPE翻页后再查找。")
        appendLine("输入文本前,确保屏幕文本中有输入框相关的元素(如\"搜索\"\"输入\"等)。")
        appendLine()
        appendLine("==经验引导(如有)==")
        appendLine("如果输入中包含「类似任务经验:」行,说明系统从历史成功任务中提取了参考模式。")
        appendLine("优先参考该模式选择动作类型和顺序,但仍需根据当前屏幕文本调整具体参数。")
        appendLine()
        appendLine("==常用应用包名==")
        appendLine(appPackageMap())
        appendLine()
        appendLine("==手机状态==")
        appendLine("${phoneState.screenWidth}x${phoneState.screenHeight} 前台:${phoneState.currentAppPackage ?: "未知"} 电量:${phoneState.batteryPercent}%${if (phoneState.isCharging) "(充)" else ""} 内存:${phoneState.availableMemoryMb}/${phoneState.totalMemoryMb}MB")
        if (phoneState.currentScreenText.isNotBlank()) {
            appendLine("屏幕文本(直接用于SCREEN_CLICK_TEXT的参数):")
            appendLine(phoneState.currentScreenText.take(SCREEN_TEXT_LIMIT_FULL))
        } else {
            appendLine("屏幕文本: (空)")
        }
        appendLine()
        appendLine("==可用动作==")
        appendLine("屏幕:SCREEN_CLICK{x,y} SCREEN_CLICK_TEXT{text} SCREEN_LONG_CLICK{x,y或text} SCREEN_DOUBLE_CLICK{x,y或text} SCREEN_FIND_AND_CLICK{text}")
        appendLine("      SCREEN_SWIPE{direction|UP/DOWN/LEFT/RIGHT 或 x1,y1,x2,y2} SCREEN_SCROLL_TO_TEXT{text}")
        appendLine("      SCREEN_INPUT{text} SCREEN_KEY{key|BACK/HOME/RECENTS/VOLUME_UP/VOLUME_DOWN/POWER/NOTIFICATION_PANEL/LOCK_SCREEN/QUICK_SETTINGS}")
        appendLine("      SCREEN_SCREENSHOT{} SCREEN_WAIT{ms} SCREEN_GET_TEXT{} SCREEN_TEXT_EXISTS{text}")
        appendLine("应用:APP_OPEN{packageName或name} APP_CLOSE{packageName} APP_LIST{} APP_SEARCH{name} APP_INSTALL{apkPath} APP_UNINSTALL{packageName}")
        appendLine("系统:SYSTEM_GET_INFO{info|MEMORY/CPU/BATTERY/STORAGE} SYSTEM_KILL_PROCESS{pid} SYSTEM_CLEAR_CACHE{} SYSTEM_SET_VOLUME{volume:0-100} SYSTEM_SET_BRIGHTNESS{brightness:0-255}")
        appendLine("其他:CLIPBOARD_COPY{text} CLIPBOARD_PASTE{} MEDIA_CONTROL{mediaAction|PLAY/PAUSE/NEXT/PREVIOUS/STOP} TIMER_SET{duration:秒}")
        appendLine("      SHELL_EXEC{command} FILE_READ{path} FILE_WRITE{path,content} NOTIFY_READ{} NOTIFY_SEND{title,content} ANSWER{text}")
    }

    /**
     * 微操作版系统提示词（MICRO 级别，极致省 Token）。
     *
     * 专为截图、按返回键等极简单步操作设计：
     * - 不发送屏幕文本（省最多 Token）
     * - 仅保留格式约束和最常用动作名
     * - 整个提示词压缩到 100 Token 以内
     *
     * @param phoneState 当前手机状态（MICRO级别不使用屏幕文本，保留参数为接口一致性）
     * @return 微操作系统提示词字符串
     */
    @Suppress("UNUSED_PARAMETER")
    fun generateSystemPromptMicro(phoneState: PhoneState): String = buildString {
        appendLine("你是手机操控助手。返回JSON动作。")
        appendLine("格式:{\"action\":\"类型\",\"params\":{},\"description\":\"说明\"}")
        append("动作:SCREEN_SCREENSHOT SCREEN_KEY{BACK/HOME/RECENTS/VOLUME_UP/VOLUME_DOWN/POWER/LOCK_SCREEN} APP_OPEN{packageName或name} SYSTEM_GET_INFO{MEMORY/CPU/BATTERY/STORAGE} ANSWER{text}")
    }

    /**
     * 超极简版系统提示词（简单任务专用）。
     *
     * 仅保留最核心的格式约束和动作列表，用于单步操作（截图、按键、查看信息等）。
     * 通过极小化提示词来最大程度节省 Token，但保持格式清晰确保 AI 能正确理解。
     *
     * @param phoneState 当前手机状态快照
     * @param screenTextLimit 屏幕文本截断长度
     * @return 超极简系统提示词字符串
     */
    fun generateSystemPromptUltra(phoneState: PhoneState, screenTextLimit: Int = 200): String = buildString {
        appendLine("你是MobileClaw手机操控助手。将用户指令转为JSON动作。")
        appendLine("必须返回JSON。操作类指令必须返回操作动作,禁止首轮返回ANSWER。")
        appendLine("ANSWER仅用于纯聊天或确认操作成功后。")
        appendLine("格式:{\"action\":\"类型\",\"params\":{},\"description\":\"说明\"}")
        appendLine("多步:{\"actions\":[...],\"description\":\"目标\"}")
        appendLine("动作:SCREEN_CLICK{x,y} SCREEN_CLICK_TEXT{text} SCREEN_LONG_CLICK{x,y或text} SCREEN_DOUBLE_CLICK{x,y或text} SCREEN_FIND_AND_CLICK{text} SCREEN_INPUT{text} SCREEN_KEY{BACK/HOME/RECENTS/VOLUME_UP/VOLUME_DOWN/POWER} SCREEN_WAIT{ms} SCREEN_SWIPE{direction} SCREEN_SCROLL_TO_TEXT{text} SCREEN_SCREENSHOT{} SCREEN_GET_TEXT{} SCREEN_TEXT_EXISTS{text} APP_OPEN{packageName或name} APP_CLOSE{packageName} APP_SEARCH{name} SYSTEM_GET_INFO{MEMORY/CPU/BATTERY/STORAGE} SYSTEM_SET_VOLUME{volume} SYSTEM_SET_BRIGHTNESS{brightness} CLIPBOARD_COPY{text} CLIPBOARD_PASTE{} MEDIA_CONTROL{PLAY/PAUSE/NEXT/PREVIOUS} NOTIFY_SEND{title,content} ANSWER{text}")
        append("状态:${phoneState.screenWidth}x${phoneState.screenHeight} 前台:${phoneState.currentAppPackage ?: "?"} 电${phoneState.batteryPercent}%")
        if (phoneState.currentScreenText.isNotBlank()) {
            appendLine()
            append("屏文:${phoneState.currentScreenText.take(screenTextLimit)}")
        }
    }

    /**
     * 极简版系统提示词（Token 节省模式专用）。
     *
     * 极致压缩：仅保留输出格式、包名映射、屏幕文本、动作列表。
     *
     * @param phoneState 当前手机状态快照
     * @param screenTextLimit 屏幕文本截断长度
     * @return 极简系统提示词字符串
     */
    fun generateSystemPromptCompact(phoneState: PhoneState, screenTextLimit: Int = SCREEN_TEXT_LIMIT_COMPACT): String = buildString {
        appendLine("你是MobileClaw手机操控助手。将用户指令转为JSON动作指令。")
        appendLine("必须返回JSON,禁止纯文本。")
        appendLine("单步:{\"action\":\"类型\",\"params\":{},\"description\":\"说明\"}")
        appendLine("多步:{\"actions\":[...],\"description\":\"目标\"}")
        appendLine()
        appendLine("核心规则:")
        appendLine("- 操作类指令首轮必须返回操作动作,禁止返回ANSWER。")
        appendLine("- ANSWER仅用于纯聊天或确认操作成功后。")
        appendLine("- 禁止同批动作中包含ANSWER,先操作再看结果。")
        appendLine("- 复杂任务分解多步,每步间加SCREEN_WAIT{ms:2000}。")
        appendLine("- 打开应用后必须等待再操作。")
        appendLine("- 优先用SCREEN_CLICK_TEXT,找不到用SCREEN_FIND_AND_CLICK。")
        appendLine("- 如有「类似任务经验:」行,优先参考其动作顺序。")
        appendLine()
        appendLine("包名:微信:com.tencent.mm 抖音:com.ss.android.ugc.aweme QQ:com.tencent.mobileqq 支付宝:com.eg.android.AlipayGphone 淘宝:com.taobao.taobao 知乎:com.zhihu.android 微博:com.sina.weibo 钉钉:com.alibaba.android.rimet B站:tv.danmaku.bili 小红书:com.xingin.xhs 京东:com.jingdong.app.mall 拼多多:com.xunmeng.pinduoduo 快手:com.smile.gifmaker 美团:com.sankuai.meituan 网易云音乐:com.netease.cloudmusic QQ音乐:com.tencent.qqmusic 高德地图:com.autonavi.minimap 百度:com.baidu.searchbox 今日头条:com.ss.android.article.news 腾讯视频:com.tencent.qqlive 爱奇艺:com.qiyi.video 百度网盘:com.baidu.netdisk 天猫:com.tmall.wireless 饿了么:me.ele 飞书:com.ss.android.lark 12306:com.MobileTicket 不确定包名用APP_OPEN{name:\"应用名\"}")
        append("状态:${phoneState.screenWidth}x${phoneState.screenHeight} 前台:${phoneState.currentAppPackage ?: "?"} 电${phoneState.batteryPercent}%")
        if (phoneState.currentScreenText.isNotBlank()) {
            appendLine()
            append("屏文:${phoneState.currentScreenText.take(screenTextLimit)}")
        }
        appendLine()
        appendLine("动作:SCREEN_CLICK{x,y} SCREEN_CLICK_TEXT{text} SCREEN_DOUBLE_CLICK{x,y或text} SCREEN_INPUT{text} SCREEN_KEY{BACK/HOME/RECENTS/VOLUME_UP/VOLUME_DOWN/POWER} SCREEN_WAIT{ms} SCREEN_SWIPE{direction} SCREEN_FIND_AND_CLICK{text} SCREEN_SCROLL_TO_TEXT{text} SCREEN_GET_TEXT{} SCREEN_TEXT_EXISTS{text} APP_OPEN{packageName或name} APP_CLOSE{packageName} APP_SEARCH{name} SYSTEM_GET_INFO{MEMORY/CPU/BATTERY/STORAGE} SYSTEM_SET_VOLUME{volume} SYSTEM_SET_BRIGHTNESS{brightness} CLIPBOARD_COPY{text} CLIPBOARD_PASTE{} MEDIA_CONTROL{PLAY/PAUSE/NEXT/PREVIOUS} NOTIFY_SEND{title,content} ANSWER{text}")
    }

    /** 常用应用包名映射表（微信、抖音、QQ 等主流应用），供系统提示词引用，避免 AI 猜测包名。 */
    private fun appPackageMap(): String =
        "微信:com.tencent.mm 抖音:com.ss.android.ugc.aweme QQ:com.tencent.mobileqq " +
        "支付宝:com.eg.android.AlipayGphone 淘宝:com.taobao.taobao 快手:com.smile.gifmaker " +
        "B站:tv.danmaku.bili 小红书:com.xingin.xhs 美团:com.sankuai.meituan 滴滴:com.sdu.didi.psnger " +
        "豆包:com.larus.nova 夸克:com.quark.browser 浏览器:com.android.chrome " +
        "设置:com.android.settings 京东:com.jingdong.app.mall 拼多多:com.xunmeng.pinduoduo " +
        "网易云音乐:com.netease.cloudmusic QQ音乐:com.tencent.qqmusic " +
        "知乎:com.zhihu.android 微博:com.sina.weibo 高德地图:com.autonavi.minimap " +
        "百度地图:com.baidu.BaiduMap 携程:ctrip.android.view 飞猪:com.taobao.trip " +
        "钉钉:com.alibaba.android.rimet 企业微信:com.tencent.wework 飞书:com.ss.android.lark " +
        "腾讯视频:com.tencent.qqlive 爱奇艺:com.qiyi.video 优酷:com.youku.phone " +
        "哔哩哔哩:tv.danmaku.bili WPS:cn.wps.moffice_eng 讯飞输入法:com.iflytek.inputmethod " +
        "应用商店:com.android.vending 华为应用市场:com.huawei.appmarket " +
        "小米应用商店:com.xiaomi.market OPPO应用商店:com.heytap.market " +
        "百度:com.baidu.searchbox 今日头条:com.ss.android.article.news " +
        "UC浏览器:com.UCMobile 哔哩哔哩漫画:com.bilibili.comic.manga " +
        "Keep:com.gotokeep.keep 喜马拉雅:com.ximalaya.ting.android " +
        "抖音火山版:com.ss.android.article.video 西瓜视频:com.ss.android.article.video " +
        "酷狗音乐:com.kugou.android 酷我音乐:cn.kuwo.player 虾米音乐:fm.xiami.main " +
        "芒果TV:com.hunantv.imgo.activity 苏宁易购:com.suning.mobile.ebuy " +
        "唯品会:com.achievo.vipshop 得物:com.shizhuang.duapp " +
        "顺丰速运:com.sf.activity 12306:com.MobileTicket 360浏览器:com.qihoo.browser " +
        "搜狗输入法:com.sogou.inputmethod 百度输入法:com.baidu.input " +
        "360安全卫士:com.qihoo360.mobilesafe 腾讯手机管家:com.tencent.qqpimsecure " +
        "万能钥匙:com.sohu.inputmethod.sogou WiFi万能钥匙:com.halo.wifikey.wifilocating " +
        "墨迹天气:com.moji.mojiweather 美图秀秀:com.mt.mtxx.mtxx " +
        "快手极速版:com.kuaishou.nebula 抖音极速版:com.ss.android.ugc.aweme.lite " +
        "番茄小说:com.dragon.read 七猫小说:com.kmxs.reader " +
        "WPS Office:cn.wps.moffice_eng 有道词典:com.youdao.dictionary " +
        "百度网盘:com.baidu.netdisk 腾讯微云:com.tencent.qcloud.wejifen " +
        "阿里云盘:com.alicloud.databox 天猫:com.tmall.wireless " +
        "饿了么:me.ele 大众点评:com.dianping.v1 58同城:com.wuba " +
        "招商银行:cmb.pb 银行:com.chinamworld.main 工商银行:com.icbc " +
        "未知应用用APP_SEARCH{name}按名称搜索打开,APP_OPEN也支持name参数"

    /**
     * 生成所有可用动作及其参数说明（用于系统提示词）。
     */
    fun availableActionsDescription(): String = buildString {
        appendLine("1. SCREEN_CLICK — 点击屏幕坐标")
        appendLine("   参数: {\"x\": int, \"y\": int}")
        appendLine("2. SCREEN_CLICK_TEXT — 点击包含指定文本的元素")
        appendLine("   参数: {\"text\": \"string\"}")
        appendLine("3. SCREEN_LONG_CLICK — 长按屏幕元素（坐标或文本二选一）")
        appendLine("   参数: {\"x\": int, \"y\": int} 或 {\"text\": \"string\"}")
        appendLine("4. SCREEN_DOUBLE_CLICK — 双击屏幕元素（坐标或文本二选一）")
        appendLine("   参数: {\"x\": int, \"y\": int} 或 {\"text\": \"string\"}")
        appendLine("5. SCREEN_FIND_AND_CLICK — 查找并点击（自动滚动查找）")
        appendLine("   参数: {\"text\": \"string\"}")
        appendLine("6. SCREEN_SWIPE — 滑动屏幕（方向或坐标二选一）")
        appendLine("   参数: {\"direction\": \"UP|DOWN|LEFT|RIGHT\"} 或 {\"x1\": int, \"y1\": int, \"x2\": int, \"y2\": int}")
        appendLine("7. SCREEN_SCROLL_TO_TEXT — 滚动到指定文本可见")
        appendLine("   参数: {\"text\": \"string\"}")
        appendLine("8. SCREEN_INPUT — 在当前焦点输入框输入文本")
        appendLine("   参数: {\"text\": \"string\"}")
        appendLine("9. SCREEN_KEY — 按键")
        appendLine("   参数: {\"key\": \"BACK|HOME|RECENTS|VOLUME_UP|VOLUME_DOWN|POWER|NOTIFICATION_PANEL|SPLIT_SCREEN|LOCK_SCREEN|QUICK_SETTINGS\"}")
        appendLine("10. SCREEN_SCREENSHOT — 截屏")
        appendLine("   参数: {}")
        appendLine("11. SCREEN_WAIT — 等待")
        appendLine("   参数: {\"ms\": int}")
        appendLine("12. SCREEN_GET_TEXT — 获取当前屏幕所有文本")
        appendLine("   参数: {}")
        appendLine("13. SCREEN_TEXT_EXISTS — 检测屏幕上是否存在指定文本")
        appendLine("   参数: {\"text\": \"string\"}")
        appendLine("14. APP_OPEN — 打开应用")
        appendLine("   参数: {\"packageName\": \"string\"} 或 {\"name\": \"string\"}")
        appendLine("15. APP_CLOSE — 关闭应用")
        appendLine("   参数: {\"packageName\": \"string\"}")
        appendLine("16. APP_LIST — 列出已安装应用")
        appendLine("   参数: {}")
        appendLine("17. APP_SEARCH — 按名称搜索应用并打开")
        appendLine("   参数: {\"name\": \"string\"}")
        appendLine("18. APP_INSTALL — 安装应用")
        appendLine("   参数: {\"apkPath\": \"string\"}")
        appendLine("19. APP_UNINSTALL — 卸载应用")
        appendLine("   参数: {\"packageName\": \"string\"}")
        appendLine("20. SYSTEM_GET_INFO — 获取系统信息")
        appendLine("   参数: {\"info\": \"MEMORY|CPU|BATTERY|STORAGE\"}")
        appendLine("21. SYSTEM_KILL_PROCESS — 结束进程")
        appendLine("   参数: {\"pid\": int}")
        appendLine("22. SYSTEM_CLEAR_CACHE — 清理缓存")
        appendLine("   参数: {}")
        appendLine("23. SYSTEM_SET_VOLUME — 设置音量")
        appendLine("   参数: {\"volume\": int(0-100)}")
        appendLine("24. SYSTEM_SET_BRIGHTNESS — 设置屏幕亮度")
        appendLine("   参数: {\"brightness\": int(0-255)}")
        appendLine("25. CLIPBOARD_COPY — 复制文本到剪贴板")
        appendLine("   参数: {\"text\": \"string\"}")
        appendLine("26. CLIPBOARD_PASTE — 粘贴剪贴板内容到当前输入框")
        appendLine("   参数: {}")
        appendLine("27. MEDIA_CONTROL — 媒体控制")
        appendLine("   参数: {\"mediaAction\": \"PLAY|PAUSE|NEXT|PREVIOUS|STOP\"}")
        appendLine("28. SHELL_EXEC — 执行 shell 命令")
        appendLine("   参数: {\"command\": \"string\"}")
        appendLine("29. FILE_READ — 读取文件")
        appendLine("   参数: {\"path\": \"string\"}")
        appendLine("30. FILE_WRITE — 写入文件")
        appendLine("   参数: {\"path\": \"string\", \"content\": \"string\"}")
        appendLine("31. NOTIFY_READ — 读取通知")
        appendLine("   参数: {}")
        appendLine("32. NOTIFY_SEND — 发送通知")
        appendLine("   参数: {\"title\": \"string\", \"content\": \"string\"}")
        appendLine("33. TIMER_SET — 设置定时器")
        appendLine("   参数: {\"duration\": int(秒)}")
        appendLine("34. ANSWER — 直接回答用户问题（不操作手机）")
        appendLine("   参数: {\"text\": \"string\"}")
    }
}


// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 所有支持的动作类型。
 *
 * @param description 人类可读的动作描述，用于日志与提示词。
 */
enum class ActionType(val description: String) {
    SCREEN_CLICK("点击屏幕坐标"),
    SCREEN_CLICK_TEXT("点击包含指定文本的元素"),
    SCREEN_LONG_CLICK("长按屏幕元素"),
    SCREEN_DOUBLE_CLICK("双击屏幕元素"),
    SCREEN_SWIPE("滑动屏幕"),
    SCREEN_SCROLL_TO_TEXT("滚动到指定文本"),
    SCREEN_FIND_AND_CLICK("查找并点击"),
    SCREEN_INPUT("输入文本"),
    SCREEN_KEY("按键操作"),
    SCREEN_SCREENSHOT("截屏"),
    SCREEN_WAIT("等待"),
    SCREEN_GET_TEXT("获取屏幕文本"),
    SCREEN_TEXT_EXISTS("检测文本是否存在"),
    APP_OPEN("打开应用"),
    APP_CLOSE("关闭应用"),
    APP_LIST("列出已安装应用"),
    APP_SEARCH("按名称搜索应用"),
    APP_INSTALL("安装应用"),
    APP_UNINSTALL("卸载应用"),
    SYSTEM_GET_INFO("获取系统信息"),
    SYSTEM_KILL_PROCESS("结束进程"),
    SYSTEM_CLEAR_CACHE("清理缓存"),
    SYSTEM_SET_VOLUME("设置音量"),
    SYSTEM_SET_BRIGHTNESS("设置亮度"),
    CLIPBOARD_COPY("复制到剪贴板"),
    CLIPBOARD_PASTE("粘贴剪贴板"),
    MEDIA_CONTROL("媒体控制"),
    SHELL_EXEC("执行Shell命令"),
    FILE_READ("读取文件"),
    FILE_WRITE("写入文件"),
    NOTIFY_READ("读取通知"),
    NOTIFY_SEND("发送通知"),
    TIMER_SET("设置定时器"),
    ANSWER("直接回答用户问题"),
    CUSTOM("自定义动作")
}

/** 系统信息类型，用于 [ActionType.SYSTEM_GET_INFO] 的参数。 */
enum class SystemInfoType(val description: String) {
    MEMORY("内存"),
    CPU("CPU"),
    BATTERY("电池"),
    STORAGE("存储")
}

/** 物理按键类型，用于 [ActionType.SCREEN_KEY] 的参数。 */
enum class KeyType(val description: String) {
    BACK("返回键"),
    HOME("Home键"),
    RECENTS("最近任务键"),
    VOLUME_UP("音量加"),
    VOLUME_DOWN("音量减"),
    POWER("电源键"),
    NOTIFICATION_PANEL("通知栏"),
    SPLIT_SCREEN("分屏"),
    LOCK_SCREEN("锁屏"),
    QUICK_SETTINGS("快速设置")
}

/** 滑动方向，用于 [ActionType.SCREEN_SWIPE] 的参数。 */
enum class SwipeDirection(val description: String) {
    UP("向上滑动"),
    DOWN("向下滑动"),
    LEFT("向左滑动"),
    RIGHT("向右滑动")
}


// =============================================================================
//  数据模型
// =============================================================================

/**
 * 单条结构化指令。
 *
 * 对应 AI 返回 JSON 中的单个动作对象，例如：
 * ```
 * {"action": "SCREEN_CLICK_TEXT", "params": {"text": "登录"}, "description": "点击登录按钮"}
 * ```
 *
 * @param actionName 动作类型名称（对应 [ActionType] 的 name）
 * @param params 动作参数，以 JsonObject 形式存储，便于灵活取值
 * @param description 该动作的自然语言说明
 */
@Serializable
data class ClawAction(
    @SerialName("action")
    val actionName: String = "",
    @SerialName("params")
    val params: JsonObject = JsonObject(emptyMap()),
    @SerialName("description")
    val description: String = ""
) {
    /** 将 actionName 解析为 [ActionType]，无法识别时返回 null。支持大小写归一化和常见变体。 */
    val type: ActionType?
        get() = normalizeActionName(actionName)?.let { name ->
            runCatching { ActionType.valueOf(name) }.getOrNull()
        }

    /**
     * 归一化动作名称：处理 AI 返回的各种大小写和格式变体。
     * - "app_open" / "App_Open" / "appOpen" -> "APP_OPEN"
     * - "screenclicktext" / "screen_click_text" -> "SCREEN_CLICK_TEXT"
     * - "answer" / "Answer" -> "ANSWER"
     */
    private fun normalizeActionName(name: String): String? {
        if (name.isBlank()) return null
        val upper = name.trim().uppercase()
        // 直接匹配（最常见情况）
        runCatching { ActionType.valueOf(upper) }.onSuccess { return upper }
        // 尝试将下划线/驼峰转为大写下划线格式
        val normalized = upper
            .replace("-", "_")
            .replace(" ", "_")
            .replace(Regex("([A-Z])([A-Z][a-z])"), "$1_$2") // 处理连续大写后跟小写
        if (normalized != upper) {
            runCatching { ActionType.valueOf(normalized) }.onSuccess { return normalized }
        }
        // 尝试模糊匹配：在所有枚举值中查找包含关系的
        return ActionType.entries.firstOrNull { enumVal ->
            enumVal.name.replace("_", "") == upper.replace("_", "")
        }?.name
    }

    // ---- 通用参数便捷取值 ----
    /** 坐标 x / 文本起始 x（SCREEN_CLICK、SCREEN_SWIPE）。 */
    val x: Int? get() = params["x"]?.jsonPrimitive?.intOrNull
    /** 坐标 y / 文本起始 y。 */
    val y: Int? get() = params["y"]?.jsonPrimitive?.intOrNull
    /** 滑动起点 x1。 */
    val x1: Int? get() = params["x1"]?.jsonPrimitive?.intOrNull
    /** 滑动起点 y1。 */
    val y1: Int? get() = params["y1"]?.jsonPrimitive?.intOrNull
    /** 滑动终点 x2。 */
    val x2: Int? get() = params["x2"]?.jsonPrimitive?.intOrNull
    /** 滑动终点 y2。 */
    val y2: Int? get() = params["y2"]?.jsonPrimitive?.intOrNull
    /** 文本内容（点击文本/输入/回答/文件内容等通用文本参数）。 */
    val text: String? get() = params["text"]?.jsonPrimitive?.contentOrNull
    /** 滑动方向名称（UP/DOWN/LEFT/RIGHT）。 */
    val swipeDirectionName: String? get() = params["direction"]?.jsonPrimitive?.contentOrNull
    /** 解析后的滑动方向枚举。 */
    val swipeDirection: SwipeDirection?
        get() = swipeDirectionName?.let { runCatching { SwipeDirection.valueOf(it) }.getOrNull() }
    /** 按键名称（BACK/HOME/RECENTS/VOLUME_UP/VOLUME_DOWN/POWER等）。 */
    val keyName: String? get() = params["key"]?.jsonPrimitive?.contentOrNull
    /** 解析后的按键枚举。 */
    val key: KeyType?
        get() = keyName?.let { runCatching { KeyType.valueOf(it) }.getOrNull() }
    /** 等待毫秒数。 */
    val ms: Long? get() = params["ms"]?.jsonPrimitive?.longOrNullSafe
    /** 应用包名。 */
    val packageName: String? get() = params["packageName"]?.jsonPrimitive?.contentOrNull
    /** 应用名称（APP_SEARCH 按名称搜索应用并打开）。 */
    val name: String? get() = params["name"]?.jsonPrimitive?.contentOrNull
    /** APK 安装包路径。 */
    val apkPath: String? get() = params["apkPath"]?.jsonPrimitive?.contentOrNull
    /** 系统信息类型名称。 */
    val infoType: String? get() = params["info"]?.jsonPrimitive?.contentOrNull
    /** 解析后的系统信息类型枚举。 */
    val systemInfoType: SystemInfoType?
        get() = infoType?.let { runCatching { SystemInfoType.valueOf(it) }.getOrNull() }
    /** 进程 pid。 */
    val pid: Int? get() = params["pid"]?.jsonPrimitive?.intOrNull
    /** Shell 命令。 */
    val command: String? get() = params["command"]?.jsonPrimitive?.contentOrNull
    /** 文件路径。 */
    val path: String? get() = params["path"]?.jsonPrimitive?.contentOrNull
    /** 文件写入内容。 */
    val content: String? get() = params["content"]?.jsonPrimitive?.contentOrNull
    /** 通知标题。 */
    val notifyTitle: String? get() = params["title"]?.jsonPrimitive?.contentOrNull
    /** 通知内容。 */
    val notifyContent: String? get() = params["content"]?.jsonPrimitive?.contentOrNull
    /** 音量级别（0-100，用于 SYSTEM_SET_VOLUME）。 */
    val volume: Int? get() = params["volume"]?.jsonPrimitive?.intOrNull
    /** 亮度级别（0-255，用于 SYSTEM_SET_BRIGHTNESS）。 */
    val brightness: Int? get() = params["brightness"]?.jsonPrimitive?.intOrNull
    /** 媒体控制命令（play/pause/next/previous，用于 MEDIA_CONTROL）。 */
    val mediaAction: String? get() = params["mediaAction"]?.jsonPrimitive?.contentOrNull
    /** 定时器时长（秒，用于 TIMER_SET）。 */
    val duration: Int? get() = params["duration"]?.jsonPrimitive?.intOrNull
    /** 定时器标签（用于 TIMER_SET）。 */
    val timerLabel: String? get() = params["label"]?.jsonPrimitive?.contentOrNull
}

/** 兼容 Int 溢出时取 Long 的辅助扩展。 */
private val kotlinx.serialization.json.JsonPrimitive.longOrNullSafe: Long?
    get() = content.toLongOrNull()

/**
 * AI 一次返回的指令集合（单步或多步）。
 *
 * @param actions 动作列表（单步时仅含一个元素）
 * @param description 本次返回的整体目标说明
 */
@Serializable
data class ClawCommandResult(
    @SerialName("actions")
    val actions: List<ClawAction> = emptyList(),
    @SerialName("description")
    val description: String = ""
) {
    /** 是否为多步操作。 */
    val isMultiStep: Boolean get() = actions.size > 1
    /** 是否仅包含 ANSWER 动作（即纯回答，无需执行手机操作）。 */
    val isAnswerOnly: Boolean
        get() = actions.size == 1 && actions.first().type == ActionType.ANSWER
}

/**
 * 单个动作执行后的结果。
 *
 * @param success 是否执行成功
 * @param message 结果描述信息
 * @param data 附加数据（如截屏路径、文件内容、系统信息文本等）
 */
@Serializable
data class ClawActionResult(
    val success: Boolean = false,
    val message: String = "",
    val data: String? = null
) {
    companion object {
        fun success(message: String, data: String? = null) = ClawActionResult(true, message, data)
        fun failure(message: String, data: String? = null) = ClawActionResult(false, message, data)
    }
}

/**
 * 当前手机状态快照，作为 AI 决策的上下文。
 *
 * 由 [com.mobileclaw.app.ai.ClawController.SystemInfoCollector] 采集后传入 AIGateway。
 *
 * @param screenWidth 屏幕宽度（像素）
 * @param screenHeight 屏幕高度（像素）
 * @param currentAppPackage 当前前台应用包名
 * @param currentActivity 当前 Activity 类名
 * @param batteryPercent 电量百分比（0-100）
 * @param isCharging 是否充电中
 * @param totalMemoryMb 总内存（MB）
 * @param availableMemoryMb 可用内存（MB）
 * @param totalStorageGb 总存储（GB）
 * @param availableStorageGb 可用存储（GB）
 * @param cpuUsagePercent CPU 使用率（0-100）
 * @param currentScreenText 当前屏幕可见文本（用于 AI 识别可点击元素）
 * @param recentNotifications 最近通知列表
 */
@Serializable
data class PhoneState(
    @SerialName("screen_width") val screenWidth: Int = 0,
    @SerialName("screen_height") val screenHeight: Int = 0,
    @SerialName("current_app_package") val currentAppPackage: String? = null,
    @SerialName("current_activity") val currentActivity: String? = null,
    @SerialName("battery_percent") val batteryPercent: Int = 0,
    @SerialName("is_charging") val isCharging: Boolean = false,
    @SerialName("total_memory_mb") val totalMemoryMb: Long = 0,
    @SerialName("available_memory_mb") val availableMemoryMb: Long = 0,
    @SerialName("total_storage_gb") val totalStorageGb: Long = 0,
    @SerialName("available_storage_gb") val availableStorageGb: Long = 0,
    @SerialName("cpu_usage_percent") val cpuUsagePercent: Int = 0,
    @SerialName("current_screen_text") val currentScreenText: String = "",
    @SerialName("recent_notifications") val recentNotifications: List<NotificationInfo> = emptyList()
)

/**
 * 通知信息（用于 [PhoneState.recentNotifications]）。
 */
@Serializable
data class NotificationInfo(
    @SerialName("package_name") val packageName: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("content") val content: String = "",
    @SerialName("timestamp") val timestamp: Long = 0
)
