package com.mobileclaw.app.ai

/**
 * 增强反馈压缩器 —— 极致压缩发送给 AI 的执行反馈，同时保留关键信息。
 *
 * 核心问题：当前反馈包含过多冗余文本（完整描述、成功/失败消息、数据），
 * 导致每轮反馈消耗 200-500 Token。本压缩器将反馈压缩到 50-100 Token。
 *
 * 压缩策略：
 * - 成功动作：仅保留动作类型+关键参数（如"APP_OPEN:微信->OK"）
 * - 失败动作：保留动作+错误摘要+建议（如"CLICK_TEXT:登录->FAIL(未找到),建议WAIT+SWIPE"）
 * - 屏幕变化：增量描述（如"页面已跳转/仍在原页面/出现弹窗"）
 * - 状态摘要：仅1行（如"前台:微信 电量:80%"）
 *
 * 对比示例：
 * 原始: "- 打开微信(com.tencent.mm) -> 成功: 已打开应用 com.tencent.mm\n- 点击「搜索」 -> 成功\n- 输入「猫咪」 -> 失败: 文本输入失败，可能没有焦点输入框"
 * 压缩: "OK:APP_OPEN(微信) OK:CLICK(搜索) FAIL:INPUT(猫咪,无焦点)->先CLICK输入框"
 */
object EnhancedFeedbackCompressor {

    /** 单条反馈最大长度。 */
    private const val MAX_FEEDBACK_LENGTH = 500

    /**
     * 压缩执行反馈。
     *
     * @param actions 本轮 AI 返回的动作列表
     * @param results 执行结果列表（动作+结果配对）
     * @param screenChanged 屏幕是否发生变化
     * @param currentApp 当前前台应用
     * @return 压缩后的反馈文本
     */
    fun compress(
        actions: List<ClawAction>,
        results: List<Pair<ClawAction, ClawActionResult>>,
        screenChanged: Boolean? = null,
        currentApp: String? = null
    ): String {
        if (results.isEmpty()) return "无动作执行"

        val sb = StringBuilder()
        var successCount = 0
        var failCount = 0

        // 状态行（极简）
        if (currentApp != null) {
            sb.append("[$currentApp] ")
        }

        // 逐条压缩
        results.forEach { (action, result) ->
            if (result.success) {
                successCount++
                sb.append(compressSuccess(action))
            } else {
                failCount++
                sb.append(compressFailure(action, result))
            }
            sb.append(" ")
        }

        // 屏幕变化提示
        if (screenChanged != null) {
            sb.append(if (screenChanged) "[页面已变]" else "[页面未变]")
        }

        // 统计行
        sb.append(" (${successCount}OK/${failCount}FAIL)")

        val compressed = sb.toString().trim()

        // 如果压缩后仍然过长，进一步截断
        return if (compressed.length > MAX_FEEDBACK_LENGTH) {
            compressed.take(MAX_FEEDBACK_LENGTH - 3) + "..."
        } else {
            compressed
        }
    }

    /**
     * 压缩成功动作。
     * 格式：OK:动作类型(关键参数)
     */
    private fun compressSuccess(action: ClawAction): String {
        val keyParam = getKeyParam(action)
        return "OK:${action.type?.shortName ?: "ACTION"}($keyParam)"
    }

    /**
     * 压缩失败动作。
     * 格式：FAIL:动作类型(关键参数,错误摘要)->建议
     */
    private fun compressFailure(action: ClawAction, result: ClawActionResult): String {
        val keyParam = getKeyParam(action)
        val errorSummary = compressError(result.message)
        val suggestion = getSuggestion(action, result)
        return if (suggestion != null) {
            "FAIL:${action.type?.shortName ?: "ACTION"}($keyParam,$errorSummary)->$suggestion"
        } else {
            "FAIL:${action.type?.shortName ?: "ACTION"}($keyParam,$errorSummary)"
        }
    }

    /**
     * 提取动作的关键参数（用于压缩表示）。
     */
    private fun getKeyParam(action: ClawAction): String {
        return when (action.type) {
            ActionType.APP_OPEN -> action.name ?: action.packageName?.substringAfterLast(".") ?: "?"
            ActionType.APP_CLOSE -> action.packageName?.substringAfterLast(".") ?: "?"
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK -> action.text?.take(10) ?: "?"
            ActionType.SCREEN_INPUT -> action.text?.take(10) ?: "?"
            ActionType.SCREEN_KEY -> action.keyName ?: "?"
            ActionType.SCREEN_SWIPE -> action.swipeDirection?.name ?: "SWIPE"
            ActionType.SCREEN_WAIT -> "${action.ms}ms"
            ActionType.SCREEN_SCREENSHOT -> "SHOT"
            ActionType.SCREEN_CLICK -> "${action.x},${action.y}"
            ActionType.SYSTEM_GET_INFO -> action.systemInfoType?.name ?: "INFO"
            ActionType.SYSTEM_SET_VOLUME -> "vol=${action.volume}"
            ActionType.SYSTEM_SET_BRIGHTNESS -> "bright=${action.brightness}"
            ActionType.CLIPBOARD_COPY -> action.text?.take(10) ?: "COPY"
            ActionType.MEDIA_CONTROL -> action.mediaAction ?: "MEDIA"
            ActionType.ANSWER -> "ANSWER"
            else -> action.actionName.take(8)
        }
    }

    /**
     * 压缩错误消息。
     */
    private fun compressError(message: String): String {
        // 提取错误的核心信息
        return when {
            message.contains("未找到") -> "未找到"
            message.contains("未安装") -> "未安装"
            message.contains("权限") || message.contains("悬浮窗") -> "无权限"
            message.contains("焦点") || message.contains("输入框") -> "无焦点"
            message.contains("超时") -> "超时"
            message.contains("网络") -> "网络错误"
            message.contains("未生效") -> "未生效"
            message.length > 15 -> message.take(15)
            else -> message
        }
    }

    /**
     * 获取失败动作的恢复建议（极简格式）。
     */
    private fun getSuggestion(action: ClawAction, result: ClawActionResult): String? {
        return when (action.type) {
            ActionType.APP_OPEN -> {
                when {
                    result.message.contains("未安装") -> "用APP_SEARCH"
                    result.message.contains("权限") -> "需悬浮窗权限"
                    else -> "换name参数"
                }
            }
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK -> {
                when {
                    result.message.contains("未找到") -> "先WAIT+SWIPE查找"
                    else -> null
                }
            }
            ActionType.SCREEN_INPUT -> {
                "先CLICK输入框"
            }
            ActionType.SCREEN_SWIPE -> null
            else -> null
        }
    }

    /**
     * 构建极简状态摘要。
     *
     * @param phoneState 手机状态
     * @return 1行状态摘要
     */
    fun compressState(phoneState: PhoneState): String {
        val app = phoneState.currentAppPackage?.substringAfterLast(".") ?: "桌面"
        val battery = "${phoneState.batteryPercent}%${if (phoneState.isCharging) "+" else ""}"
        val mem = if (phoneState.availableMemoryMb > 0) {
            "${phoneState.availableMemoryMb / 1024}G可用"
        } else ""
        return "$app|电量$battery${if (mem.isNotEmpty()) "|$mem" else ""}"
    }
}

/**
 * ActionType 的短名称扩展（用于反馈压缩）。
 */
val ActionType.shortName: String
    get() = when (this) {
        ActionType.APP_OPEN -> "OPEN"
        ActionType.APP_CLOSE -> "CLOSE"
        ActionType.APP_SEARCH -> "SEARCH"
        ActionType.APP_LIST -> "LIST"
        ActionType.SCREEN_CLICK -> "CLICK"
        ActionType.SCREEN_CLICK_TEXT -> "CLICK_T"
        ActionType.SCREEN_LONG_CLICK -> "LCLICK"
        ActionType.SCREEN_DOUBLE_CLICK -> "DCLICK"
        ActionType.SCREEN_FIND_AND_CLICK -> "FCLICK"
        ActionType.SCREEN_SCROLL_TO_TEXT -> "SCROLL_T"
        ActionType.SCREEN_SWIPE -> "SWIPE"
        ActionType.SCREEN_INPUT -> "INPUT"
        ActionType.SCREEN_KEY -> "KEY"
        ActionType.SCREEN_SCREENSHOT -> "SHOT"
        ActionType.SCREEN_WAIT -> "WAIT"
        ActionType.SCREEN_GET_TEXT -> "GET_TEXT"
        ActionType.SCREEN_TEXT_EXISTS -> "TEXT_EX"
        ActionType.SYSTEM_GET_INFO -> "INFO"
        ActionType.SYSTEM_KILL_PROCESS -> "KILL"
        ActionType.SYSTEM_CLEAR_CACHE -> "CACHE"
        ActionType.SYSTEM_SET_VOLUME -> "VOL"
        ActionType.SYSTEM_SET_BRIGHTNESS -> "BRIGHT"
        ActionType.SHELL_EXEC -> "SHELL"
        ActionType.FILE_READ -> "FREAD"
        ActionType.FILE_WRITE -> "FWRITE"
        ActionType.NOTIFY_READ -> "NOTIF_R"
        ActionType.NOTIFY_SEND -> "NOTIF_S"
        ActionType.APP_INSTALL -> "INSTALL"
        ActionType.APP_UNINSTALL -> "UNINST"
        ActionType.CLIPBOARD_COPY -> "COPY"
        ActionType.CLIPBOARD_PASTE -> "PASTE"
        ActionType.MEDIA_CONTROL -> "MEDIA"
        ActionType.TIMER_SET -> "TIMER"
        ActionType.ANSWER -> "ANS"
        ActionType.CUSTOM -> "CUSTOM"
    }
