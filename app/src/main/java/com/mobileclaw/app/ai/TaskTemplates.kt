package com.mobileclaw.app.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// =============================================================================
//  TaskTemplates - 任务模板系统
// =============================================================================

/**
 * 任务模板系统。
 *
 * 预定义常见复杂任务的操作流程模板，用户输入匹配模板时可直接生成
 * 第一轮动作序列，减少 AI 解析步骤，提高响应速度和准确性。
 *
 * 模板匹配优先级：
 * 1. 精确匹配模板关键词
 * 2. 模糊匹配（包含关键词）
 * 3. 降级到 AI 解析
 *
 * 每个模板包含：
 * - 匹配关键词和正则
 * - 首轮动作序列（已优化为最佳实践）
 * - 后续步骤的引导提示（告诉 AI 接下来该做什么）
 */
object TaskTemplates {

    /** 模板匹配结果。 */
    data class TemplateMatch(
        val templateName: String,
        val confidence: Float,
        val firstActions: List<ClawAction>,
        val guidance: String,
        val estimatedSteps: Int
    )

    /** 单个任务模板定义。 */
    private data class TaskTemplate(
        val name: String,
        val keywords: List<String>,
        val regex: Regex?,
        val description: String,
        val generateActions: (MatchResult?) -> List<ClawAction>,
        val guidance: String,
        val estimatedSteps: Int
    )

    /** 所有预定义模板。 */
    private val templates = listOf(

        // —— 打开应用模板 ——
        TaskTemplate(
            name = "OPEN_APP",
            keywords = listOf("打开", "启动", "开启", "运行", "launch", "open"),
            regex = Regex("(?:打开|启动|开启|运行|launch|open)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?$", RegexOption.IGNORE_CASE),
            description = "打开指定应用",
            generateActions = { match ->
                val appName = match?.groupValues?.getOrNull(1)?.trim() ?: ""
                val pkg = APP_PACKAGES[appName.lowercase()]
                listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = if (pkg != null) {
                            JsonObject(mapOf("packageName" to JsonPrimitive(pkg)))
                        } else {
                            JsonObject(mapOf("name" to JsonPrimitive(appName)))
                        },
                        description = "打开$appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用启动"
                    )
                )
            },
            guidance = "应用已打开，查看屏幕内容决定下一步",
            estimatedSteps = 1
        ),

        // —— 应用内搜索模板 ——
        TaskTemplate(
            name = "SEARCH_IN_APP",
            keywords = listOf("搜索", "查找", "search"),
            regex = Regex("(?:在|用|使用)?\\s*[「「【]?(.+?)[」」】]?\\s*(?:里|中|里面|上)?\\s*搜索\\s*(.+)", RegexOption.IGNORE_CASE),
            description = "在指定应用中搜索内容",
            generateActions = { match ->
                val appName = match?.groupValues?.getOrNull(1)?.trim() ?: ""
                val searchContent = match?.groupValues?.getOrNull(2)?.trim() ?: ""
                val pkg = APP_PACKAGES[appName.lowercase()]
                listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = if (pkg != null) {
                            JsonObject(mapOf("packageName" to JsonPrimitive(pkg)))
                        } else {
                            JsonObject(mapOf("name" to JsonPrimitive(appName)))
                        },
                        description = "打开$appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用启动"
                    )
                )
            },
            guidance = "应用已打开，点击搜索按钮，输入「${'$'}{searchContent}」，然后点击搜索",
            estimatedSteps = 4
        ),

        // —— 发送消息模板 ——
        TaskTemplate(
            name = "SEND_MESSAGE",
            keywords = listOf("发消息", "发送消息", "发信息", "send message"),
            regex = Regex("(?:用|通过|使用)?\\s*[「「【]?(.+?)[」」】]?\\s*给\\s*[「「【]?(.+?)[」」】]?\\s*(?:发|发送)\\s*(?:消息|信息)?\\s*[「「【]?(.+?)[」」】]?$", RegexOption.IGNORE_CASE),
            description = "通过指定应用给联系人发送消息",
            generateActions = { match ->
                val appName = match?.groupValues?.getOrNull(1)?.trim() ?: "微信"
                val pkg = APP_PACKAGES[appName.lowercase()]
                listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = if (pkg != null) {
                            JsonObject(mapOf("packageName" to JsonPrimitive(pkg)))
                        } else {
                            JsonObject(mapOf("name" to JsonPrimitive(appName)))
                        },
                        description = "打开$appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用启动"
                    )
                )
            },
            guidance = "应用已打开，点击联系人，输入消息，然后点击发送",
            estimatedSteps = 4
        ),

        // —— 关闭应用模板 ——
        TaskTemplate(
            name = "CLOSE_APP",
            keywords = listOf("关闭", "退出", "kill", "close"),
            regex = Regex("(?:关闭|退出|kill|close)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?$", RegexOption.IGNORE_CASE),
            description = "关闭指定应用",
            generateActions = { match ->
                val appName = match?.groupValues?.getOrNull(1)?.trim() ?: ""
                val pkg = APP_PACKAGES[appName.lowercase()]
                listOf(
                    ClawAction(
                        actionName = ActionType.APP_CLOSE.name,
                        params = if (pkg != null) {
                            JsonObject(mapOf("packageName" to JsonPrimitive(pkg)))
                        } else {
                            JsonObject(mapOf("packageName" to JsonPrimitive(appName)))
                        },
                        description = "关闭$appName"
                    )
                )
            },
            guidance = "应用已关闭",
            estimatedSteps = 1
        ),

        // —— 调节音量模板 ——
        TaskTemplate(
            name = "SET_VOLUME",
            keywords = listOf("音量调", "声音调", "调音量", "set volume"),
            regex = Regex("(?:音量|声音|volume)\\s*(?:调|设|设置为?)\\s*(\\d+)", RegexOption.IGNORE_CASE),
            description = "设置音量到指定值",
            generateActions = { match ->
                val volume = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 50
                listOf(
                    ClawAction(
                        actionName = ActionType.SYSTEM_SET_VOLUME.name,
                        params = JsonObject(mapOf("volume" to JsonPrimitive(volume))),
                        description = "设置音量为$volume"
                    )
                )
            },
            guidance = "音量已设置",
            estimatedSteps = 1
        ),

        // —— 调节亮度模板 ——
        TaskTemplate(
            name = "SET_BRIGHTNESS",
            keywords = listOf("亮度调", "屏幕亮度", "调亮度", "set brightness"),
            regex = Regex("(?:亮度|brightness)\\s*(?:调|设|设置为?)\\s*(\\d+)", RegexOption.IGNORE_CASE),
            description = "设置屏幕亮度到指定值",
            generateActions = { match ->
                val brightness = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 128
                listOf(
                    ClawAction(
                        actionName = ActionType.SYSTEM_SET_BRIGHTNESS.name,
                        params = JsonObject(mapOf("brightness" to JsonPrimitive(brightness))),
                        description = "设置亮度为$brightness"
                    )
                )
            },
            guidance = "亮度已设置",
            estimatedSteps = 1
        ),

        // —— 定时任务模板 ——
        TaskTemplate(
            name = "DELAYED_TASK",
            keywords = listOf("分钟后", "秒钟后", "小时后", "after", "later"),
            regex = Regex("(\\d+)\\s*(分钟|秒钟|小时|min|sec|hour)\\s*后\\s*(.+)", RegexOption.IGNORE_CASE),
            description = "延迟执行任务",
            generateActions = { match ->
                // 返回空列表，由调度器处理延迟
                emptyList()
            },
            guidance = "已设置定时任务",
            estimatedSteps = 0
        ),

        // —— 清理模板 ——
        TaskTemplate(
            name = "CLEANUP",
            keywords = listOf("清理", "清理手机", "加速", "optimize", "cleanup"),
            regex = Regex("(?:清理|加速|cleanup|optimize)\\s*(?:手机|缓存|垃圾)?", RegexOption.IGNORE_CASE),
            description = "清理缓存并优化系统",
            generateActions = { _ ->
                listOf(
                    ClawAction(
                        actionName = ActionType.SYSTEM_CLEAR_CACHE.name,
                        params = JsonObject(emptyMap()),
                        description = "清理缓存"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = JsonObject(mapOf("key" to JsonPrimitive("RECENTS"))),
                        description = "打开最近任务"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(500))),
                        description = "等待"
                    )
                )
            },
            guidance = "缓存已清理，可在最近任务中手动清理后台应用",
            estimatedSteps = 1
        )
    )

    /**
     * 尝试匹配任务模板。
     *
     * @param userInput 用户输入
     * @return 匹配结果，未匹配返回 null
     */
    fun match(userInput: String): TemplateMatch? {
        val input = userInput.trim()

        for (template in templates) {
            // 先尝试正则匹配
            if (template.regex != null) {
                val match = template.regex.find(input)
                if (match != null) {
                    val actions = template.generateActions(match)
                    if (actions.isNotEmpty()) {
                        return TemplateMatch(
                            templateName = template.name,
                            confidence = 0.95f,
                            firstActions = actions,
                            guidance = template.guidance,
                            estimatedSteps = template.estimatedSteps
                        )
                    }
                }
            }

            // 关键词匹配
            if (template.keywords.any { keyword ->
                    input.contains(keyword, ignoreCase = true)
                }) {
                val actions = template.generateActions(null)
                if (actions.isNotEmpty()) {
                    return TemplateMatch(
                        templateName = template.name,
                        confidence = 0.7f,
                        firstActions = actions,
                        guidance = template.guidance,
                        estimatedSteps = template.estimatedSteps
                    )
                }
            }
        }

        return null
    }

    /** 获取所有模板描述（用于 UI 展示）。 */
    fun getAllTemplateDescriptions(): List<String> =
        templates.map { "${it.name}: ${it.description} (${it.estimatedSteps}步)" }

    /** 常用应用包名映射。 */
    private val APP_PACKAGES = mapOf(
        "微信" to "com.tencent.mm",
        "抖音" to "com.ss.android.ugc.aweme",
        "qq" to "com.tencent.mobileqq",
        "支付宝" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "快手" to "com.smile.gifmaker",
        "b站" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
        "小红书" to "com.xingin.xhs",
        "美团" to "com.sankuai.meituan",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "知乎" to "com.zhihu.android",
        "微博" to "com.sina.weibo",
        "钉钉" to "com.alibaba.android.rimet",
        "网易云音乐" to "com.netease.cloudmusic",
        "qq音乐" to "com.tencent.qqmusic",
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
        "keep" to "com.gotokeep.keep",
        "喜马拉雅" to "com.ximalaya.ting.android",
        "wps" to "cn.wps.moffice_eng"
    )
}
