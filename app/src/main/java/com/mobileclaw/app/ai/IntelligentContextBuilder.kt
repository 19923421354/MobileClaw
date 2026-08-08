package com.mobileclaw.app.ai

/**
 * 智能上下文构建器 —— 为 AI 提供精准、精简的上下文信息。
 *
 * 核心问题：当前系统将所有屏幕文本、系统信息无差别地发送给 AI，
 * 导致 Token 浪费且 AI 被无关信息干扰。
 *
 * 优化策略：
 * - 任务类型识别：根据用户指令推断任务类型，只发送相关上下文
 * - 屏幕信息过滤：只发送当前可见的交互元素，过滤装饰性文本
 * - 历史上下文压缩：将多轮对话历史压缩为关键信息
 * - 动态 Token 分配：根据任务复杂度分配上下文长度
 *
 * 使用方式：
 * ```
 * val builder = IntelligentContextBuilder(cache)
 * val context = builder.buildContext(userInput, phoneState, memory, complexity)
 * // 将 context 附加到 AI 请求中
 * ```
 */
class IntelligentContextBuilder(
    private val cache: ScreenStateCache
) {

    /** 任务类型。 */
    enum class TaskType {
        OPEN_APP,           // 打开应用
        SEND_MESSAGE,       // 发送消息
        SEARCH,             // 搜索内容
        NAVIGATE,           // 页面导航（返回、滑动等）
        SYSTEM_CONTROL,     // 系统控制（音量、亮度等）
        READ_INFO,          // 读取信息（通知、系统信息等）
        MULTI_STEP,         // 多步骤复杂任务
        UNKNOWN             // 未知类型
    }

    /** 上下文构建结果。 */
    data class ContextResult(
        val taskType: TaskType,
        val contextText: String,
        val estimatedTokens: Int
    )

    /**
     * 识别任务类型。
     */
    fun identifyTaskType(userInput: String): TaskType {
        val input = userInput.trim().lowercase()

        // 打开应用
        if (input.matches(Regex(".*(打开|启动|开启|运行|launch|open)\\s*.+.*", RegexOption.IGNORE_CASE))) {
            // 如果还包含搜索/发送等关键词，则是多步骤任务
            if (input.contains("搜索") || input.contains("发送") || input.contains("发消息")) {
                return TaskType.MULTI_STEP
            }
            return TaskType.OPEN_APP
        }

        // 发送消息
        if (input.contains("发送") || input.contains("发消息") || input.contains("发信息") ||
            input.contains("发给") || input.matches(Regex(".*send\\s+.*", RegexOption.IGNORE_CASE))) {
            return TaskType.SEND_MESSAGE
        }

        // 搜索
        if (input.contains("搜索") || input.contains("查找") || input.contains("search") ||
            input.matches(Regex(".*查\\s*.+.*", RegexOption.IGNORE_CASE))) {
            return TaskType.SEARCH
        }

        // 系统控制
        if (input.contains("音量") || input.contains("亮度") || input.contains("蓝牙") ||
            input.contains("wifi") || input.contains("热点") || input.contains("飞行模式") ||
            input.contains("省电") || input.contains("旋转") || input.contains("勿扰")) {
            return TaskType.SYSTEM_CONTROL
        }

        // 读取信息
        if (input.contains("通知") || input.contains("电量") || input.contains("存储") ||
            input.contains("内存") || input.contains("系统信息") || input.contains("设备信息")) {
            return TaskType.READ_INFO
        }

        // 导航
        if (input == "返回" || input == "back" || input == "回桌面" || input == "home" ||
            input.contains("滑动") || input.contains("向上") || input.contains("向下") ||
            input.contains("向左") || input.contains("向右") || input.contains("swipe")) {
            return TaskType.NAVIGATE
        }

        // 多步骤任务（包含多个动词或"然后"、"接着"等连接词）
        if (input.contains("然后") || input.contains("接着") || input.contains("之后") ||
            input.split("，", "。", "；", ",").size >= 3) {
            return TaskType.MULTI_STEP
        }

        return TaskType.UNKNOWN
    }

    /**
     * 构建智能上下文。
     *
     * 根据任务类型和复杂度，生成精简但信息丰富的上下文文本。
     *
     * @param userInput 用户指令
     * @param phoneState 当前手机状态
     * @param memory 对话记忆
     * @param complexity 任务复杂度
     * @return 上下文构建结果
     */
    suspend fun buildContext(
        userInput: String,
        phoneState: PhoneState,
        memory: ConversationMemory,
        complexity: TaskComplexityAnalyzer.Complexity
    ): ContextResult {
        val taskType = identifyTaskType(userInput)
        val maxScreenTextLength = when (complexity) {
            TaskComplexityAnalyzer.Complexity.MICRO -> 0
            TaskComplexityAnalyzer.Complexity.SIMPLE -> 100
            TaskComplexityAnalyzer.Complexity.MEDIUM -> 200
            TaskComplexityAnalyzer.Complexity.COMPLEX -> 400
            TaskComplexityAnalyzer.Complexity.UNLIMITED -> 600
        }

        val contextBuilder = StringBuilder()

        // 1. 基础设备信息（所有任务都需要）
        contextBuilder.appendLine("设备: ${phoneState.currentAppPackage ?: "桌面"} 电量:${phoneState.batteryPercent}%${if (phoneState.isCharging) "(充电中)" else ""}")

        // 2. 根据任务类型添加特定上下文
        when (taskType) {
            TaskType.OPEN_APP -> {
                // 打开应用：只需当前应用信息 + 已知应用映射
                contextBuilder.appendLine("当前前台: ${phoneState.currentAppPackage ?: "桌面/启动器"}")
                // 提示已知包名
                contextBuilder.appendLine("常用应用包名: 微信=com.tencent.mm 抖音=com.ss.android.ugc.aweme QQ=com.tencent.mobileqq 支付宝=com.eg.android.AlipayGphone 淘宝=com.taobao.taobao 设置=com.android.settings")
            }

            TaskType.SEND_MESSAGE -> {
                // 发送消息：需要屏幕元素（找输入框、发送按钮）+ 当前应用
                val summary = cache.getStateSummary(maxScreenTextLength)
                contextBuilder.appendLine(summary)
                contextBuilder.appendLine("提示: 发送消息需要先找到输入框(EditText)输入内容，再点击发送按钮")
            }

            TaskType.SEARCH -> {
                // 搜索：需要屏幕元素（找搜索框）+ 当前应用
                val summary = cache.getStateSummary(maxScreenTextLength)
                contextBuilder.appendLine(summary)
                contextBuilder.appendLine("提示: 搜索需要先点击搜索框(通常在顶部)，输入关键词，再点击搜索按钮或回车")
            }

            TaskType.NAVIGATE -> {
                // 导航：只需最简信息
                contextBuilder.appendLine("当前应用: ${phoneState.currentAppPackage ?: "桌面"}")
            }

            TaskType.SYSTEM_CONTROL -> {
                // 系统控制：不需要屏幕信息
                contextBuilder.appendLine("可用操作: SYSTEM_SET_VOLUME{volume:0-15} SYSTEM_SET_BRIGHTNESS{brightness:0-255} SCREEN_KEY{key:VOLUME_UP/VOLUME_DOWN}")
            }

            TaskType.READ_INFO -> {
                // 读取信息：提供系统状态摘要
                contextBuilder.appendLine("内存: ${phoneState.availableMemoryMb}/${phoneState.totalMemoryMb}MB")
                contextBuilder.appendLine("存储: ${phoneState.availableStorageGb}/${phoneState.totalStorageGb}GB")
                if (phoneState.recentNotifications.isNotEmpty()) {
                    contextBuilder.appendLine("通知(${phoneState.recentNotifications.size}条):")
                    phoneState.recentNotifications.take(5).forEach { notif ->
                        contextBuilder.appendLine("  - ${notif.title}: ${notif.content?.take(50)}")
                    }
                }
            }

            TaskType.MULTI_STEP -> {
                // 多步骤任务：提供完整上下文
                val summary = cache.getStateSummary(maxScreenTextLength)
                contextBuilder.appendLine(summary)
                // 添加历史记忆
                val memSummary = memory.buildContextSummary()
                if (memSummary.isNotBlank()) {
                    contextBuilder.appendLine(memSummary)
                }
            }

            TaskType.UNKNOWN -> {
                // 未知任务：提供中等量上下文
                val summary = cache.getStateSummary(maxScreenTextLength / 2)
                contextBuilder.appendLine(summary)
            }
        }

        // 3. 对话历史（非首轮任务需要）
        if (taskType != TaskType.OPEN_APP && taskType != TaskType.NAVIGATE && taskType != TaskType.SYSTEM_CONTROL) {
            val memSummary = memory.buildContextSummary()
            if (memSummary.isNotBlank() && taskType != TaskType.MULTI_STEP) {
                contextBuilder.appendLine(memSummary)
            }
        }

        val contextText = contextBuilder.toString().trim()
        val estimatedTokens = estimateTokens(contextText)

        return ContextResult(taskType, contextText, estimatedTokens)
    }

    /**
     * 构建错误恢复上下文。
     *
     * 当动作执行失败后，为 AI 提供失败信息和当前屏幕状态，
     * 帮助 AI 做出更好的下一步决策。
     *
     * @param failedAction 失败的动作
     * @param errorResult 错误结果
     * @param retryCount 当前重试次数
     * @return 恢复上下文文本
     */
    suspend fun buildRecoveryContext(
        failedAction: ClawAction,
        errorResult: ClawActionResult,
        retryCount: Int
    ): String {
        val summary = cache.getStateSummary(150)

        return buildString {
            appendLine("==动作执行失败==")
            appendLine("动作: ${ActionTranslator.describeAction(failedAction)}")
            appendLine("错误: ${errorResult.message}")
            appendLine("重试次数: $retryCount")
            appendLine("当前屏幕状态:")
            appendLine(summary)
            appendLine("==请根据当前屏幕状态调整下一步操作==")
        }
    }

    /**
     * 构建任务完成上下文。
     *
     * 任务完成后，生成简洁的完成报告上下文，供记忆存储。
     *
     * @param userInput 用户原始指令
     * @param executedActions 已执行的动作列表
     * @param success 是否成功
     * @return 完成上下文
     */
    fun buildCompletionContext(
        userInput: String,
        executedActions: List<ClawAction>,
        success: Boolean
    ): String {
        return buildString {
            appendLine("任务: ${userInput.take(60)}")
            appendLine("结果: ${if (success) "成功" else "部分完成"}")
            appendLine("步骤(${executedActions.size}步):")
            executedActions.take(10).forEachIndexed { index, action ->
                appendLine("  ${index + 1}. ${ActionTranslator.describeAction(action)}")
            }
            if (executedActions.size > 10) {
                appendLine("  ...(共${executedActions.size}步)")
            }
        }
    }

    /**
     * 获取任务类型对应的建议首轮动作。
     *
     * 对于已知任务类型，可以直接建议首轮动作，减少 AI 思考时间。
     */
    fun getSuggestedFirstActions(taskType: TaskType, userInput: String): List<ClawAction>? {
        return when (taskType) {
            TaskType.OPEN_APP -> {
                // 尝试从用户指令中提取应用名
                val appPattern = Regex("(?:打开|启动|开启|运行|launch|open)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?$", RegexOption.IGNORE_CASE)
                val match = appPattern.find(userInput.trim())
                val appName = match?.groupValues?.getOrNull(1)?.trim()

                if (!appName.isNullOrEmpty()) {
                    // 查找已知包名
                    val knownPkg = APP_PACKAGE_MAP.entries.find {
                        appName.contains(it.key, ignoreCase = true)
                    }?.value

                    listOf(ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = if (knownPkg != null) {
                            kotlinx.serialization.json.JsonObject(mapOf(
                                "packageName" to kotlinx.serialization.json.JsonPrimitive(knownPkg)
                            ))
                        } else {
                            kotlinx.serialization.json.JsonObject(mapOf(
                                "name" to kotlinx.serialization.json.JsonPrimitive(appName)
                            ))
                        },
                        description = "打开$appName"
                    ))
                } else null
            }

            TaskType.NAVIGATE -> {
                val input = userInput.trim().lowercase()
                when {
                    input == "返回" || input == "back" -> listOf(ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = kotlinx.serialization.json.JsonObject(mapOf(
                            "key" to kotlinx.serialization.json.JsonPrimitive("BACK")
                        )),
                        description = "按下返回键"
                    ))
                    input == "回桌面" || input == "home" || input == "主屏" -> listOf(ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = kotlinx.serialization.json.JsonObject(mapOf(
                            "key" to kotlinx.serialization.json.JsonPrimitive("HOME")
                        )),
                        description = "回到主屏幕"
                    ))
                    else -> null
                }
            }

            else -> null
        }
    }

    /**
     * 估算文本的 Token 数量。
     *
     * 粗略估算：中文约1字=1.5token，英文约4字符=1token。
     */
    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val chineseCount = text.count { it.code in 0x4E00..0x9FFF }
        val otherCount = text.length - chineseCount
        return (chineseCount * 1.5 + otherCount / 4.0).toInt()
    }

    /** 常用应用包名映射。 */
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
