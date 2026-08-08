package com.mobileclaw.app.ai

import android.util.Log
import com.mobileclaw.app.ai.ActionType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * ContextualHelpSystem —— 上下文感知帮助系统。
 *
 * 根据当前屏幕状态、用户活动与任务上下文，提供情境相关的帮助消息、操作建议、
 * 新手引导、故障排除步骤与 FAQ 匹配，并根据用户专业水平自适应调整帮助详略。
 *
 * 核心理念：移动端自动化助手的价值不仅在于「能做什么」，更在于「在恰当的时机
 * 告诉用户可以做什么、应该怎么做」。用户在不同场景下的困惑点不同——在搜索界面
 * 不知道怎么输入、在设置页面找不到选项、操作失败时不知道如何恢复。本系统通过
 * 实时检测当前上下文，主动推送与场景匹配的帮助内容，将「被动响应」升级为
 * 「主动引导」，降低使用门槛、提升操作成功率。
 *
 * 六大核心能力：
 * 1. **上下文检测**：根据前台应用包名、屏幕文本与用户活动，推断当前所处场景
 *    （应用首页、搜索页、应用设置页、系统设置页、锁屏页或未知场景）。
 * 2. **帮助生成**：基于检测到的上下文，生成情境相关的帮助消息与提示，包括
 *    操作技巧、注意事项与功能说明。
 * 3. **建议引擎**：结合当前上下文与用户历史交互记录，推荐下一步最可能需要的
 *    操作，以 [ActionType] 形式给出可执行建议。
 * 4. **新手引导**：为新用户（交互次数低于阈值）提供循序渐进的引导提示，
 *    基于交互模式判断用户是否已掌握某项功能，避免重复打扰。
 * 5. **故障排除**：当操作出错时，根据错误类型与上下文提供分步排查方案，
 *    每一步包含操作描述、预期结果与可选的重试动作。
 * 6. **FAQ 匹配**：将用户自然语言问题与内置知识库进行关键词匹配，返回最相关
 *    的问答条目；支持按上下文过滤，优先返回与当前场景相关的解答。
 *
 * ### 自适应详略
 * 帮助内容的详细程度根据 [ExpertiseLevel] 三级调整：
 * - **BEGINNER（新手）**：提供详细步骤说明、附加解释与引导提示，语气友好。
 * - **INTERMEDIATE（进阶）**：提供关键操作要点，省略基础解释。
 * - **EXPERT（专家）**：仅提供核心信息与警告，保持简洁。
 * 专业水平可由 [detectExpertiseLevel] 基于交互历史自动推断，也可由
 * [setExpertiseLevel] 手动设定。
 *
 * ### 线程安全
 * 所有存储均使用 [ConcurrentHashMap] / [ConcurrentLinkedDeque]，可被多线程
 * 并发调用。统计计数使用 @Volatile + private set，保证可见性的同时禁止外部修改。
 * 典型场景：UI 线程检测上下文并生成帮助、后台线程记录交互与更新专业水平。
 *
 * ### 容量与淘汰
 * - 交互历史最多保留 [MAX_INTERACTION_HISTORY]（500）条，超出时从队尾丢弃最旧记录。
 * - FAQ 知识库最多 [MAX_FAQ_ENTRIES]（200）条，超出时拒绝添加并输出告警。
 * - 故障排除模板最多 [MAX_TROUBLESHOOTING_TEMPLATES]（50）个。
 *
 * ### 典型调用流程
 * ```
 * val helpSystem = ContextualHelpSystem()
 * // 检测当前上下文
 * val context = helpSystem.detectContext(
 *     packageName = "com.tencent.mm",
 *     screenText = "搜索",
 *     userActivity = "正在查看聊天列表"
 * )
 * // 生成上下文相关帮助
 * val helpMessages = helpSystem.generateHelp(context)
 * helpMessages.forEach { showHelp(it) }
 * // 获取操作建议
 * val suggestions = helpSystem.getSuggestions(context)
 * suggestions.forEach { showSuggestion(it) }
 * // 用户提问时匹配 FAQ
 * val faqs = helpSystem.getFAQ("怎么搜索联系人")
 * // 操作失败时获取排查步骤
 * val steps = helpSystem.getTroubleshooting("timeout")
 * // 记录交互（用于专业水平推断与新手引导）
 * helpSystem.recordInteraction(ActionType.SCREEN_CLICK, true, context)
 * ```
 */
class ContextualHelpSystem {

    /** 日志标签。 */
    private val tag = "ContextualHelpSystem"

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 帮助类型。
     *
     * 标识一条帮助消息的类别，用于 UI 分组展示与过滤。
     *
     * @property displayName 中文显示名称
     */
    enum class HelpType(val displayName: String) {
        /** 操作技巧：简短的使用建议或快捷方式。 */
        TIP("操作技巧"),

        /** 操作建议：推荐用户执行的下一步动作。 */
        SUGGESTION("操作建议"),

        /** 警告提示：提醒用户注意潜在风险或限制。 */
        WARNING("注意事项"),

        /** 故障排除：针对错误场景的分步排查指引。 */
        TROUBLESHOOTING("故障排除"),

        /** 新手引导：面向新用户的功能介绍与引导。 */
        ONBOARDING("新手引导"),

        /** 常见问答：匹配知识库中的高频问题解答。 */
        FAQ("常见问答")
    }

    /**
     * 用户专业水平。
     *
     * 决定帮助内容的详略程度。由 [detectExpertiseLevel] 自动推断或由
     * [setExpertiseLevel] 手动设定。
     *
     * @property displayName 中文显示名称
     * @property detailLabel 详略描述，用于日志与调试
     */
    enum class ExpertiseLevel(val displayName: String, val detailLabel: String) {
        /** 新手：交互次数少、动作类型单一，需要详细引导。 */
        BEGINNER("新手", "详细引导"),

        /** 进阶：有一定使用经验，掌握基本操作，需要关键提示。 */
        INTERMEDIATE("进阶", "关键提示"),

        /** 专家：经验丰富，仅需核心信息与警告。 */
        EXPERT("专家", "简洁概要")
    }

    /**
     * 上下文场景类型。
     *
     * 由 [detectContext] 根据前台应用与屏幕内容推断，决定生成何种帮助与建议。
     *
     * @property displayName 中文显示名称
     */
    enum class ContextType(val displayName: String) {
        /** 应用首页/主界面：应用启动后的主屏幕。 */
        APP_HOME("应用首页"),

        /** 搜索界面：包含搜索框或搜索结果的页面。 */
        APP_SEARCH("搜索界面"),

        /** 应用设置页：应用内部的设置/配置页面。 */
        APP_SETTINGS("应用设置"),

        /** 系统设置页：Android 系统设置应用。 */
        SYSTEM_SETTINGS("系统设置"),

        /** 锁屏页面：设备锁屏或解锁界面。 */
        LOCKED_SCREEN("锁屏页面"),

        /** 未知场景：无法识别的上下文，回退为通用帮助。 */
        UNKNOWN("未知场景")
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 帮助上下文。
     *
     * 描述 [detectContext] 推断出的当前场景快照，是帮助生成与建议引擎的核心输入。
     *
     * @property contextType       场景类型，对应 [ContextType]
     * @property packageName       前台应用包名（锁屏时为 null）
     * @property appName           前台应用可读名称（如「微信」），无法确定时为空字符串
     * @property screenDescription 当前屏幕的简要描述（如「聊天列表页」「搜索结果页」）
     * @property userActivity      用户当前活动描述（如「正在浏览消息」），无则为空字符串
     * @property taskContext       任务上下文（用户正在尝试完成的任务），无则为 null
     * @property timestamp         上下文检测时间戳（毫秒）
     */
    data class HelpContext(
        val contextType: ContextType,
        val packageName: String?,
        val appName: String,
        val screenDescription: String,
        val userActivity: String,
        val taskContext: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 帮助消息。
     *
     * 一条面向用户展示的帮助内容，包含类型、标题、正文与优先级。
     *
     * @property type        帮助类型，对应 [HelpType]
     * @property title       消息标题（简短，用于列表项展示）
     * @property content     消息正文（根据专业水平调整详略）
     * @property detailLevel 该消息适配的专业水平（用于过滤）
     * @property priority    优先级（数值越大越重要，排序时靠前）
     * @property actionable  是否可操作（true 表示附带可执行建议，UI 可显示「立即执行」按钮）
     * @property hintId      引导提示的唯一标识（仅 [HelpType.ONBOARDING] 类型使用，
     *                       用于去重，避免重复展示同一条引导），默认空字符串
     * @property timestamp   消息生成时间戳（毫秒）
     */
    data class HelpMessage(
        val type: HelpType,
        val title: String,
        val content: String,
        val detailLevel: ExpertiseLevel = ExpertiseLevel.BEGINNER,
        val priority: Int = 0,
        val actionable: Boolean = false,
        val hintId: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * 操作建议。
     *
     * 推荐用户执行的下一步动作，以 [ActionType] 形式给出，附带置信度与理由。
     *
     * @property actionType   建议的动作类型，对应 [ActionType]
     * @property description  建议描述（如「打开微信」「输入搜索关键词」）
     * @property confidence   置信度（0.0-1.0），越高越推荐
     * @property reason       推荐理由（如「基于当前搜索界面」「常用操作」）
     * @property priority     优先级（数值越大越靠前）
     */
    data class Suggestion(
        val actionType: ActionType,
        val description: String,
        val confidence: Float,
        val reason: String,
        val priority: Int = 0
    )

    /**
     * FAQ 条目。
     *
     * 知识库中的一条常见问答，包含问题、答案、匹配关键词与适用场景。
     *
     * @property id                条目唯一标识
     * @property question          问题文本
     * @property answer            答案文本
     * @property keywords          匹配关键词列表（用于问题匹配，支持中英文）
     * @property category          分类（如「基础操作」「故障排查」「系统功能」）
     * @property applicableContexts 适用场景列表（为空表示所有场景通用），对应 [ContextType]
     */
    data class FAQEntry(
        val id: String,
        val question: String,
        val answer: String,
        val keywords: List<String>,
        val category: String,
        val applicableContexts: List<ContextType> = emptyList()
    )

    /**
     * 故障排除步骤。
     *
     * 分步排查方案中的单一步骤，包含操作描述、预期结果与可选的重试动作。
     *
     * @property stepNumber     步骤序号（从 1 开始）
     * @property description    步骤描述（用户需要执行的操作）
     * @property expectedResult 预期结果（执行后应观察到的现象）
     * @property actionType     可选的重试动作类型，对应 [ActionType]；为 null 表示该步骤
     *                          仅供用户手动操作，不提供自动重试
     * @property isCritical     是否为关键步骤（关键步骤失败时建议跳过后续步骤并联系支持）
     */
    data class TroubleshootingStep(
        val stepNumber: Int,
        val description: String,
        val expectedResult: String,
        val actionType: ActionType? = null,
        val isCritical: Boolean = false
    )

    /**
     * 交互记录（内部使用）。
     *
     * 记录用户的一次操作交互，用于专业水平推断与新手引导进度跟踪。
     */
    private data class InteractionRecord(
        val actionType: ActionType,
        val success: Boolean,
        val contextType: ContextType,
        val timestamp: Long
    )

    // ============================================================
    // 伴生对象 —— 配置常量
    // ============================================================

    companion object {
        /** 交互历史最大保留条数，超出时从队尾丢弃最旧记录。 */
        private const val MAX_INTERACTION_HISTORY = 500

        /** FAQ 知识库最大条目数，超出时拒绝添加。 */
        private const val MAX_FAQ_ENTRIES = 200

        /** 故障排除模板最大数量，超出时拒绝添加。 */
        private const val MAX_TROUBLESHOOTING_TEMPLATES = 50

        /** FAQ 匹配最低相似度阈值，低于此值的条目不返回。 */
        private const val MIN_FAQ_SIMILARITY = 0.15f

        /** 进阶用户的最小交互次数阈值。 */
        private const val INTERMEDIATE_ACTION_THRESHOLD = 50

        /** 专家用户的最小交互次数阈值。 */
        private const val EXPERT_ACTION_THRESHOLD = 200

        /** 进阶用户的最小动作类型多样性阈值（使用过的不同 ActionType 数量）。 */
        private const val INTERMEDIATE_DIVERSITY_THRESHOLD = 5

        /** 专家用户的最小动作类型多样性阈值。 */
        private const val EXPERT_DIVERSITY_THRESHOLD = 10

        /** 专家用户的最小操作成功率阈值。 */
        private const val EXPERT_SUCCESS_RATE_THRESHOLD = 0.8f

        /** 新手引导展示的交互次数上限（交互次数低于此值时仍展示引导）。 */
        private const val ONBOARDING_INTERACTION_THRESHOLD = 20

        /** 默认建议返回条数。 */
        private const val DEFAULT_SUGGESTION_LIMIT = 5

        /** 默认 FAQ 返回条数。 */
        private const val DEFAULT_FAQ_LIMIT = 3

        /** 新手帮助附加提示语。 */
        private const val BEGINNER_EXTRA_HINT = "\n\n提示：如遇问题，随时说「帮助」获取更多指导。"

        /** 搜索界面关键词（用于上下文检测）。 */
        private val SEARCH_KEYWORDS = arrayOf("搜索", "查找", "search", "find", "查询")

        /** 设置界面关键词（用于上下文检测）。 */
        private val SETTINGS_KEYWORDS = arrayOf("设置", "setting", "配置", "config", "偏好", "preference")

        /** 启动器/桌面包名关键词（用于上下文检测）。 */
        private val LAUNCHER_KEYWORDS = arrayOf("launcher", "desktop", "home")

        /** 系统设置包名关键词。 */
        private val SYSTEM_SETTINGS_KEYWORDS = arrayOf("com.android.settings", "settings")

        /** 网络错误关键词（用于故障排除模板匹配）。 */
        private val NETWORK_ERROR_KEYWORDS = arrayOf("network", "网络", "connection", "连接", "net_")

        /** 超时错误关键词。 */
        private val TIMEOUT_ERROR_KEYWORDS = arrayOf("timeout", "超时", "timed out")

        /** 元素未找到错误关键词。 */
        private val NOT_FOUND_ERROR_KEYWORDS = arrayOf("not found", "未找到", "找不到", "no such element", "元素不存在")

        /** 权限错误关键词。 */
        private val PERMISSION_ERROR_KEYWORDS = arrayOf("permission", "权限", "denied", "拒绝", "forbidden")

        /** 应用无响应错误关键词。 */
        private val ANR_ERROR_KEYWORDS = arrayOf("not responding", "无响应", "anr", "卡死", "frozen", "卡住")

        /** 输入失败错误关键词。 */
        private val INPUT_ERROR_KEYWORDS = arrayOf("input", "输入", "输入失败", "type failed")
    }

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** FAQ 知识库，键 = 条目 ID，值 = [FAQEntry]。 */
    private val faqEntries = ConcurrentHashMap<String, FAQEntry>()

    /** 故障排除模板，键 = 错误类型标识，值 = 排查步骤列表。 */
    private val troubleshootingTemplates = ConcurrentHashMap<String, MutableList<TroubleshootingStep>>()

    /** 交互历史（队首为最新，最多保留 [MAX_INTERACTION_HISTORY] 条）。 */
    private val interactionHistory = ConcurrentLinkedDeque<InteractionRecord>()

    /** 已展示过的新手引导提示 ID 集合（避免重复展示）。 */
    private val shownOnboardingHints = ConcurrentHashMap.newKeySet<String>()

    /** 各动作类型的使用次数（用于专业水平推断中的多样性计算）。 */
    private val actionTypeUsage = ConcurrentHashMap<ActionType, Int>()

    // ============================================================
    // 状态与统计
    // ============================================================

    /** 当前专业水平（可自动推断或手动设定）。 */
    @Volatile
    var currentExpertiseLevel: ExpertiseLevel = ExpertiseLevel.BEGINNER
        private set

    /** 是否启用专业水平自动推断（默认启用）。 */
    @Volatile
    var autoDetectExpertise: Boolean = true

    /** 累计记录的交互总数（含已淘汰）。 */
    @Volatile
    var totalInteractions: Int = 0
        private set

    /** 累计生成的帮助消息总数。 */
    @Volatile
    var totalHelpGenerated: Int = 0
        private set

    /** 累计 FAQ 查询次数。 */
    @Volatile
    var totalFAQQueries: Int = 0
        private set

    /** 累计故障排除查询次数。 */
    @Volatile
    var totalTroubleshootingQueries: Int = 0
        private set

    // ============================================================
    // 初始化
    // ============================================================

    init {
        initializeDefaultFAQs()
        initializeDefaultTroubleshootingTemplates()
        Log.d(tag, "上下文帮助系统已初始化 (FAQ=${faqEntries.size}, 模板=${troubleshootingTemplates.size})")
    }

    /**
     * 初始化默认 FAQ 知识库。
     *
     * 预置覆盖基础操作、系统功能与故障排查的常见问答，供 [getFAQ] 匹配。
     */
    private fun initializeDefaultFAQs() {
        val defaults = listOf(
            FAQEntry(
                id = "faq_open_app",
                question = "如何打开应用？",
                answer = "您可以直接说出应用名称，例如「打开微信」「启动抖音」。系统会自动查找并打开对应应用。如果应用未安装，会提示您前往应用商店下载。",
                keywords = listOf("打开", "启动", "应用", "open", "app", "start", "开启"),
                category = "基础操作",
                applicableContexts = listOf(ContextType.APP_HOME, ContextType.UNKNOWN)
            ),
            FAQEntry(
                id = "faq_search",
                question = "如何搜索内容？",
                answer = "在搜索界面中，您可以直接告诉我要搜索的关键词，例如「搜索猫咪图片」「查找联系人张三」。系统会自动定位搜索框并输入内容。",
                keywords = listOf("搜索", "查找", "搜", "search", "find", "查询", "找"),
                category = "基础操作",
                applicableContexts = listOf(ContextType.APP_SEARCH)
            ),
            FAQEntry(
                id = "faq_clear_cache",
                question = "如何清理缓存？",
                answer = "您可以说「清理缓存」或「清除缓存」，系统会自动执行缓存清理操作。也可以前往设置 > 应用管理，选择目标应用后清除缓存。",
                keywords = listOf("清理", "清除", "缓存", "clear", "cache", "垃圾", "清理"),
                category = "系统功能",
                applicableContexts = listOf(ContextType.SYSTEM_SETTINGS, ContextType.APP_SETTINGS)
            ),
            FAQEntry(
                id = "faq_operation_failed",
                question = "操作失败怎么办？",
                answer = "操作失败时，系统会自动提供故障排除建议。常见原因包括：网络不稳定、页面加载未完成、元素位置变化。您可以尝试：1) 等待页面加载完成后重试；2) 重新描述操作；3) 说「帮助」获取当前页面的操作指引。",
                keywords = listOf("失败", "错误", "不行", "无法", "fail", "error", "出错", "不能用"),
                category = "故障排查",
                applicableContexts = emptyList()
            ),
            FAQEntry(
                id = "faq_input_text",
                question = "如何输入文字？",
                answer = "在需要输入的界面中，直接说出要输入的内容即可，例如「输入你好世界」。系统会自动定位输入框并填入文本。如果输入框未激活，会先点击激活再输入。",
                keywords = listOf("输入", "打字", "文字", "input", "type", "text", "填写", "录入"),
                category = "基础操作",
                applicableContexts = listOf(ContextType.APP_SEARCH, ContextType.UNKNOWN)
            ),
            FAQEntry(
                id = "faq_swipe",
                question = "如何滑动屏幕？",
                answer = "您可以说「向上滑动」「向下滑动」「向左滑动」「向右滑动」来控制屏幕滚动方向。也可以说「滚动到指定位置」让系统自动查找目标内容。",
                keywords = listOf("滑动", "滑", "上滑", "下滑", "swipe", "scroll", "滚动", "翻页"),
                category = "基础操作",
                applicableContexts = emptyList()
            ),
            FAQEntry(
                id = "faq_click_button",
                question = "如何点击按钮？",
                answer = "您可以直接描述要点击的元素，例如「点击确定按钮」「点击发送」。系统支持按文本内容和按坐标两种点击方式，优先使用文本匹配以提高准确性。",
                keywords = listOf("点击", "点", "按钮", "click", "button", "tap", "按下", "触"),
                category = "基础操作",
                applicableContexts = emptyList()
            ),
            FAQEntry(
                id = "faq_system_info",
                question = "如何查看系统信息？",
                answer = "您可以说「查看内存」「查看电量」「查看CPU使用率」等，系统会获取并报告对应的系统信息。也可以说「查看所有系统信息」获取综合报告。",
                keywords = listOf("系统", "信息", "内存", "电量", "system", "info", "memory", "battery", "cpu", "存储"),
                category = "系统功能",
                applicableContexts = listOf(ContextType.SYSTEM_SETTINGS, ContextType.APP_SETTINGS)
            ),
            FAQEntry(
                id = "faq_screenshot",
                question = "如何截图？",
                answer = "您可以说「截图」或「截屏」，系统会自动截取当前屏幕。截取的图片会保存在默认图片目录中。",
                keywords = listOf("截图", "截屏", "screenshot", "屏幕截图", "拍照"),
                category = "系统功能",
                applicableContexts = emptyList()
            ),
            FAQEntry(
                id = "faq_timer",
                question = "如何设置定时器或提醒？",
                answer = "您可以说「设置5分钟后的定时器」「10分钟后提醒我喝水」。系统会在指定时间后发送通知提醒。",
                keywords = listOf("定时", "倒计时", "提醒", "timer", "countdown", "reminder", "闹钟"),
                category = "系统功能",
                applicableContexts = emptyList()
            )
        )
        defaults.forEach { entry ->
            faqEntries[entry.id] = entry
        }
    }

    /**
     * 初始化默认故障排除模板。
     *
     * 预置常见错误类型的分步排查方案，供 [getTroubleshooting] 查询。
     */
    private fun initializeDefaultTroubleshootingTemplates() {
        // 网络错误排查
        troubleshootingTemplates["network"] = mutableListOf(
            TroubleshootingStep(1, "检查网络连接状态，确认 Wi-Fi 或移动数据已开启", "状态栏显示网络已连接", null, true),
            TroubleshootingStep(2, "尝试重新执行操作，网络波动可能已恢复", "操作成功执行", null, false),
            TroubleshootingStep(3, "若仍失败，尝试切换网络（Wi-Fi ↔ 移动数据）后重试", "操作成功执行", ActionType.SCREEN_CLICK, false),
            TroubleshootingStep(4, "检查目标应用是否需要登录或存在网络访问限制", "应用网络访问正常", null, false)
        )

        // 超时错误排查
        troubleshootingTemplates["timeout"] = mutableListOf(
            TroubleshootingStep(1, "等待页面完全加载后重试操作", "页面内容完整显示", ActionType.SCREEN_WAIT, true),
            TroubleshootingStep(2, "检查目标元素是否在当前可视区域，尝试滑动查找", "目标元素出现在屏幕上", ActionType.SCREEN_SWIPE, false),
            TroubleshootingStep(3, "确认应用未处于后台或休眠状态，必要时重新打开应用", "应用恢复至前台活跃状态", ActionType.APP_OPEN, false),
            TroubleshootingStep(4, "若持续超时，考虑增加操作超时时长或联系支持", "操作在合理时间内完成", null, false)
        )

        // 元素未找到排查
        troubleshootingTemplates["element_not_found"] = mutableListOf(
            TroubleshootingStep(1, "等待页面加载完成，元素可能尚未渲染", "页面加载完毕", ActionType.SCREEN_WAIT, true),
            TroubleshootingStep(2, "尝试滑动屏幕，元素可能在可视区域之外", "目标元素出现", ActionType.SCREEN_SWIPE, false),
            TroubleshootingStep(3, "确认当前页面是否正确，可能需要先导航到目标页面", "到达包含目标元素的页面", null, false),
            TroubleshootingStep(4, "检查元素文本是否准确，尝试使用部分文本匹配", "元素被正确定位", ActionType.SCREEN_CLICK_TEXT, false)
        )

        // 权限错误排查
        troubleshootingTemplates["permission"] = mutableListOf(
            TroubleshootingStep(1, "前往系统设置 > 应用管理，检查目标应用的权限设置", "权限管理页面打开", null, true),
            TroubleshootingStep(2, "授予所需权限（如存储、位置、通知等）", "权限状态显示为已允许", null, false),
            TroubleshootingStep(3, "返回应用重新执行操作", "操作成功执行", null, false),
            TroubleshootingStep(4, "若权限已授予仍报错，尝试清除应用数据后重试", "操作成功执行", ActionType.SYSTEM_CLEAR_CACHE, false)
        )

        // 应用无响应排查
        troubleshootingTemplates["app_not_responding"] = mutableListOf(
            TroubleshootingStep(1, "等待数秒，应用可能正在处理耗时操作", "应用恢复响应", ActionType.SCREEN_WAIT, true),
            TroubleshootingStep(2, "若持续无响应，强制结束应用进程", "应用进程被终止", ActionType.SYSTEM_KILL_PROCESS, false),
            TroubleshootingStep(3, "重新打开应用", "应用正常启动", ActionType.APP_OPEN, false),
            TroubleshootingStep(4, "若反复卡死，考虑清理应用缓存或更新应用版本", "应用运行流畅", ActionType.SYSTEM_CLEAR_CACHE, false)
        )

        // 输入失败排查
        troubleshootingTemplates["input_failed"] = mutableListOf(
            TroubleshootingStep(1, "确认输入框已激活（光标在输入框内闪烁）", "输入框显示光标", ActionType.SCREEN_CLICK, true),
            TroubleshootingStep(2, "清除输入框中的已有内容后重新输入", "输入框为空", null, false),
            TroubleshootingStep(3, "检查输入内容是否包含特殊字符，尝试简化后重试", "文本成功输入", ActionType.SCREEN_INPUT, false),
            TroubleshootingStep(4, "若输入法异常，尝试切换输入法或使用系统默认输入法", "文本成功输入", null, false)
        )
    }

    // ============================================================
    // 上下文检测
    // ============================================================

    /**
     * 检测当前上下文。
     *
     * 根据前台应用包名、屏幕文本与用户活动描述，推断当前所处场景类型
     * （[ContextType]），并构建 [HelpContext] 供后续帮助生成与建议使用。
     *
     * 检测优先级（从高到低）：
     * 1. 包名为 null → 锁屏页面
     * 2. 包名匹配系统设置 → 系统设置页
     * 3. 屏幕文本包含设置关键词 → 应用设置页
     * 4. 屏幕文本包含搜索关键词 → 搜索界面
     * 5. 包名匹配启动器 → 应用首页
     * 6. 以上均不匹配 → 未知场景
     *
     * @param packageName    前台应用包名，锁屏时传 null
     * @param screenText     当前屏幕可见文本（可截取关键片段），无则为 null
     * @param userActivity   用户当前活动描述，无则为 null
     * @param taskContext    任务上下文（用户正在尝试完成的任务），无则为 null
     * @return 检测到的 [HelpContext]
     */
    fun detectContext(
        packageName: String?,
        screenText: String?,
        userActivity: String? = null,
        taskContext: String? = null
    ): HelpContext {
        val normalizedPackage = packageName?.trim()?.lowercase()
        val normalizedScreen = screenText?.trim()?.lowercase()

        val contextType = when {
            // 1. 包名为 null → 锁屏
            normalizedPackage == null || normalizedPackage.isEmpty() ->
                ContextType.LOCKED_SCREEN

            // 2. 系统设置
            SYSTEM_SETTINGS_KEYWORDS.any { normalizedPackage.contains(it) } ->
                ContextType.SYSTEM_SETTINGS

            // 3. 应用设置页（屏幕包含设置关键词）
            normalizedScreen != null && SETTINGS_KEYWORDS.any { normalizedScreen.contains(it) } ->
                ContextType.APP_SETTINGS

            // 4. 搜索界面（屏幕包含搜索关键词）
            normalizedScreen != null && SEARCH_KEYWORDS.any { normalizedScreen.contains(it) } ->
                ContextType.APP_SEARCH

            // 5. 应用首页 / 启动器
            LAUNCHER_KEYWORDS.any { normalizedPackage.contains(it) } ->
                ContextType.APP_HOME

            // 6. 未知场景
            else -> ContextType.UNKNOWN
        }

        val appName = resolveAppName(normalizedPackage)
        val screenDescription = resolveScreenDescription(contextType, normalizedScreen)

        val context = HelpContext(
            contextType = contextType,
            packageName = packageName,
            appName = appName,
            screenDescription = screenDescription,
            userActivity = userActivity?.trim() ?: "",
            taskContext = taskContext?.trim(),
            timestamp = System.currentTimeMillis()
        )

        Log.d(tag, "上下文检测: ${contextType.displayName} [$packageName] $screenDescription")
        return context
    }

    // ============================================================
    // 帮助生成
    // ============================================================

    /**
     * 生成上下文相关的帮助消息。
     *
     * 根据检测到的上下文场景与用户专业水平，生成一组情境相关的帮助消息，
     * 包括操作技巧、注意事项与（新手场景下的）引导提示。消息详略根据
     * [expertiseLevel]（默认使用 [currentExpertiseLevel]）自适应调整。
     *
     * @param context         当前帮助上下文
     * @param expertiseLevel  指定专业水平（为 null 时使用 [currentExpertiseLevel]）
     * @return 帮助消息列表，按优先级降序排列
     */
    fun generateHelp(
        context: HelpContext,
        expertiseLevel: ExpertiseLevel? = null
    ): List<HelpMessage> {
        val level = expertiseLevel ?: currentExpertiseLevel
        val messages = ArrayList<HelpMessage>()

        when (context.contextType) {
            ContextType.APP_HOME -> {
                messages.add(HelpMessage(
                    type = HelpType.TIP,
                    title = "快速打开应用",
                    content = adjustDetail(
                        "直接说出应用名称即可打开，例如「打开微信」「启动抖音」。支持中英文应用名。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 10,
                    actionable = true
                ))
                messages.add(HelpMessage(
                    type = HelpType.SUGGESTION,
                    title = "搜索应用",
                    content = adjustDetail(
                        "如果不确定应用名称，可以说「搜索应用」按关键词查找已安装的应用。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 5,
                    actionable = true
                ))
            }

            ContextType.APP_SEARCH -> {
                messages.add(HelpMessage(
                    type = HelpType.TIP,
                    title = "输入搜索关键词",
                    content = adjustDetail(
                        "直接说出要搜索的内容，例如「搜索猫咪」「查找联系人张三」。系统会自动定位搜索框并输入。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 10,
                    actionable = true
                ))
                messages.add(HelpMessage(
                    type = HelpType.WARNING,
                    title = "确认搜索框已激活",
                    content = adjustDetail(
                        "如果搜索框未获得焦点，系统会先点击激活再输入。请确保搜索框可见且未被遮挡。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.INTERMEDIATE,
                    priority = 6,
                    actionable = false
                ))
            }

            ContextType.APP_SETTINGS -> {
                messages.add(HelpMessage(
                    type = HelpType.TIP,
                    title = "浏览设置选项",
                    content = adjustDetail(
                        "上下滑动可以查看更多设置项。可以说「向下滑动」或直接描述要找的设置名称。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 8,
                    actionable = true
                ))
                messages.add(HelpMessage(
                    type = HelpType.WARNING,
                    title = "谨慎修改系统级选项",
                    content = adjustDetail(
                        "部分设置项会影响应用核心行为，修改前请确认了解其影响。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.INTERMEDIATE,
                    priority = 7,
                    actionable = false
                ))
            }

            ContextType.SYSTEM_SETTINGS -> {
                messages.add(HelpMessage(
                    type = HelpType.TIP,
                    title = "快速定位设置项",
                    content = adjustDetail(
                        "直接描述要找的设置，例如「打开 Wi-Fi 设置」「调整亮度」。系统会在设置中自动定位对应项。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 10,
                    actionable = true
                ))
                messages.add(HelpMessage(
                    type = HelpType.TIP,
                    title = "查看系统信息",
                    content = adjustDetail(
                        "可以说「查看内存」「查看电量」「查看存储」获取系统状态信息。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 6,
                    actionable = true
                ))
                messages.add(HelpMessage(
                    type = HelpType.WARNING,
                    title = "系统设置变更需谨慎",
                    content = adjustDetail(
                        "修改系统级设置可能影响设备正常运行，建议仅修改您了解的选项。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.INTERMEDIATE,
                    priority = 9,
                    actionable = false
                ))
            }

            ContextType.LOCKED_SCREEN -> {
                messages.add(HelpMessage(
                    type = HelpType.WARNING,
                    title = "设备已锁屏",
                    content = adjustDetail(
                        "当前设备处于锁屏状态，请先解锁设备后再执行操作。部分操作（如查看时间、播放音乐）可在锁屏状态下进行。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 10,
                    actionable = false
                ))
            }

            ContextType.UNKNOWN -> {
                messages.add(HelpMessage(
                    type = HelpType.TIP,
                    title = "通用操作指引",
                    content = adjustDetail(
                        "您可以用自然语言描述想要执行的操作，例如「点击发送按钮」「向下滑动」「输入你好」。系统会自动解析并执行。",
                        level
                    ),
                    detailLevel = ExpertiseLevel.BEGINNER,
                    priority = 5,
                    actionable = true
                ))
            }
        }

        // 新手场景下追加引导提示
        if (level == ExpertiseLevel.BEGINNER) {
            val onboardingHints = getOnboardingHints(context)
            messages.addAll(onboardingHints)
        }

        // 按优先级降序排列
        val sorted = messages.sortedByDescending { it.priority }
        totalHelpGenerated += sorted.size

        Log.d(tag, "生成帮助: ${sorted.size} 条 [${level.displayName}] 场景=${context.contextType.displayName}")
        return sorted
    }

    /**
     * 获取新手引导提示。
     *
     * 为新用户（交互次数低于 [ONBOARDING_INTERACTION_THRESHOLD]）提供循序渐进的
     * 功能引导。已展示过的引导（通过 [hintId] 标识）不会重复返回。
     *
     * @param context 当前帮助上下文
     * @return 未展示过的新手引导消息列表
     */
    fun getOnboardingHints(context: HelpContext): List<HelpMessage> {
        // 交互次数超过阈值时不再展示引导（除非手动设定为新手水平）
        val interactionCount = interactionHistory.size
        if (interactionCount >= ONBOARDING_INTERACTION_THRESHOLD &&
            autoDetectExpertise &&
            currentExpertiseLevel != ExpertiseLevel.BEGINNER
        ) {
            return emptyList()
        }

        val hints = ArrayList<HelpMessage>()

        when (context.contextType) {
            ContextType.APP_HOME -> {
                if ("onboarding_home_welcome" !in shownOnboardingHints) {
                    hints.add(HelpMessage(
                        type = HelpType.ONBOARDING,
                        title = "欢迎使用 MobileClaw",
                        content = "我是您的手机操作助手，可以帮您打开应用、搜索内容、操作系统设置。" +
                                "试着说「打开微信」开始体验吧！",
                        detailLevel = ExpertiseLevel.BEGINNER,
                        priority = 15,
                        hintId = "onboarding_home_welcome"
                    ))
                }
                if ("onboarding_home_voice" !in shownOnboardingHints) {
                    hints.add(HelpMessage(
                        type = HelpType.ONBOARDING,
                        title = "语音输入更便捷",
                        content = "除了文字输入，您还可以使用语音说出指令，点击麦克风图标即可开始语音输入。",
                        detailLevel = ExpertiseLevel.BEGINNER,
                        priority = 12,
                        hintId = "onboarding_home_voice"
                    ))
                }
            }

            ContextType.APP_SEARCH -> {
                if ("onboarding_search_basic" !in shownOnboardingHints) {
                    hints.add(HelpMessage(
                        type = HelpType.ONBOARDING,
                        title = "搜索操作入门",
                        content = "在搜索界面，直接说出搜索内容即可。例如「搜索美食」「查找附近的咖啡店」。" +
                                "系统会自动定位搜索框并输入关键词。",
                        detailLevel = ExpertiseLevel.BEGINNER,
                        priority = 15,
                        hintId = "onboarding_search_basic"
                    ))
                }
            }

            ContextType.SYSTEM_SETTINGS -> {
                if ("onboarding_settings_navigation" !in shownOnboardingHints) {
                    hints.add(HelpMessage(
                        type = HelpType.ONBOARDING,
                        title = "设置导航",
                        content = "系统设置包含许多选项，您可以直接描述要找的设置项。" +
                                "例如「打开蓝牙设置」「调整屏幕亮度」，系统会自动定位。",
                        detailLevel = ExpertiseLevel.BEGINNER,
                        priority = 15,
                        hintId = "onboarding_settings_navigation"
                    ))
                }
            }

            ContextType.UNKNOWN -> {
                if ("onboarding_general_commands" !in shownOnboardingHints) {
                    hints.add(HelpMessage(
                        type = HelpType.ONBOARDING,
                        title = "自然语言指令",
                        content = "您可以用日常语言描述操作，系统会自动理解并执行。" +
                                "例如「点击右上角的分享按钮」「向下滑动一点」「返回上一页」。",
                        detailLevel = ExpertiseLevel.BEGINNER,
                        priority = 15,
                        hintId = "onboarding_general_commands"
                    ))
                }
            }

            else -> { /* APP_SETTINGS 和 LOCKED_SCREEN 无特定引导 */ }
        }

        return hints
    }

    /**
     * 标记新手引导提示已展示。
     *
     * 当 UI 展示了某条引导提示后调用此方法，避免后续重复展示。
     *
     * @param hintId 引导提示的唯一标识（对应 [HelpMessage.hintId]）
     */
    fun markOnboardingShown(hintId: String) {
        if (hintId.isNotBlank()) {
            shownOnboardingHints.add(hintId)
            Log.d(tag, "标记引导已展示: $hintId")
        }
    }

    // ============================================================
    // 建议引擎
    // ============================================================

    /**
     * 获取操作建议。
     *
     * 结合当前上下文场景与用户交互历史，推荐下一步最可能需要的操作。
     * 建议以 [Suggestion] 形式返回，包含动作类型、描述、置信度与理由。
     *
     * 建议来源：
     * 1. 场景匹配建议：根据当前 [ContextType] 推荐该场景下的常见操作。
     * 2. 历史频率建议：基于用户在该场景下最常执行的动作（来自 [interactionHistory]）。
     * 3. 任务上下文建议：若 [HelpContext.taskContext] 非空，推荐与任务相关的下一步。
     *
     * @param context 当前帮助上下文
     * @param limit   返回的最大建议条数，默认 [DEFAULT_SUGGESTION_LIMIT]
     * @return 建议列表，按优先级与置信度降序排列
     */
    fun getSuggestions(
        context: HelpContext,
        limit: Int = DEFAULT_SUGGESTION_LIMIT
    ): List<Suggestion> {
        val suggestions = ArrayList<Suggestion>()

        // 1. 场景匹配建议
        suggestions.addAll(getContextBasedSuggestions(context))

        // 2. 历史频率建议
        suggestions.addAll(getHistoryBasedSuggestions(context))

        // 3. 任务上下文建议
        context.taskContext?.let { task ->
            suggestions.addAll(getTaskBasedSuggestions(task, context))
        }

        // 去重（按 actionType 保留优先级最高者）并排序
        val deduped = suggestions
            .groupBy { it.actionType }
            .mapValues { (_, list) -> list.maxByOrNull { it.priority * 100 + it.confidence }!! }
            .values
            .sortedWith(
                compareByDescending<Suggestion> { it.priority }
                    .thenByDescending { it.confidence }
            )
            .take(limit)

        Log.d(tag, "生成建议: ${deduped.size}/$limit 条 场景=${context.contextType.displayName}")
        return deduped
    }

    /**
     * 根据场景类型生成基础建议。
     */
    private fun getContextBasedSuggestions(context: HelpContext): List<Suggestion> {
        return when (context.contextType) {
            ContextType.APP_HOME -> listOf(
                Suggestion(
                    actionType = ActionType.APP_OPEN,
                    description = "打开常用应用",
                    confidence = 0.8f,
                    reason = "应用首页场景，打开应用是常见操作",
                    priority = 10
                ),
                Suggestion(
                    actionType = ActionType.APP_SEARCH,
                    description = "搜索应用",
                    confidence = 0.5f,
                    reason = "快速查找目标应用",
                    priority = 5
                ),
                Suggestion(
                    actionType = ActionType.SYSTEM_GET_INFO,
                    description = "查看系统信息",
                    confidence = 0.3f,
                    reason = "了解设备当前状态",
                    priority = 2
                )
            )

            ContextType.APP_SEARCH -> listOf(
                Suggestion(
                    actionType = ActionType.SCREEN_INPUT,
                    description = "输入搜索关键词",
                    confidence = 0.9f,
                    reason = "搜索界面，输入是首要操作",
                    priority = 10
                ),
                Suggestion(
                    actionType = ActionType.SCREEN_CLICK_TEXT,
                    description = "点击搜索建议",
                    confidence = 0.5f,
                    reason = "搜索界面可能有联想建议",
                    priority = 4
                )
            )

            ContextType.APP_SETTINGS -> listOf(
                Suggestion(
                    actionType = ActionType.SCREEN_SWIPE,
                    description = "滑动浏览设置项",
                    confidence = 0.7f,
                    reason = "设置页通常需要滚动查看更多选项",
                    priority = 8
                ),
                Suggestion(
                    actionType = ActionType.SCREEN_CLICK_TEXT,
                    description = "点击目标设置项",
                    confidence = 0.6f,
                    reason = "直接定位并点击需要的设置",
                    priority = 7
                )
            )

            ContextType.SYSTEM_SETTINGS -> listOf(
                Suggestion(
                    actionType = ActionType.SCREEN_CLICK_TEXT,
                    description = "定位设置项",
                    confidence = 0.8f,
                    reason = "系统设置中按名称定位更高效",
                    priority = 10
                ),
                Suggestion(
                    actionType = ActionType.SYSTEM_GET_INFO,
                    description = "查看系统状态",
                    confidence = 0.6f,
                    reason = "系统设置页适合查看设备信息",
                    priority = 6
                ),
                Suggestion(
                    actionType = ActionType.SYSTEM_CLEAR_CACHE,
                    description = "清理缓存",
                    confidence = 0.4f,
                    reason = "系统设置中可执行缓存清理",
                    priority = 3
                )
            )

            ContextType.LOCKED_SCREEN -> listOf(
                Suggestion(
                    actionType = ActionType.SCREEN_SWIPE,
                    description = "滑动解锁",
                    confidence = 0.5f,
                    reason = "锁屏状态下可尝试滑动解锁",
                    priority = 5
                )
            )

            ContextType.UNKNOWN -> listOf(
                Suggestion(
                    actionType = ActionType.SCREEN_CLICK_TEXT,
                    description = "点击界面元素",
                    confidence = 0.4f,
                    reason = "通用场景，按文本点击是最常用操作",
                    priority = 5
                ),
                Suggestion(
                    actionType = ActionType.SCREEN_SWIPE,
                    description = "滑动浏览内容",
                    confidence = 0.3f,
                    reason = "通用场景，滑动查看更多内容",
                    priority = 3
                ),
                Suggestion(
                    actionType = ActionType.SCREEN_GET_TEXT,
                    description = "获取屏幕文本",
                    confidence = 0.3f,
                    reason = "通用场景，读取当前屏幕信息",
                    priority = 2
                )
            )
        }
    }

    /**
     * 基于交互历史生成建议。
     *
     * 统计用户在相同 [ContextType] 下最常执行的动作类型，作为高频建议。
     */
    private fun getHistoryBasedSuggestions(context: HelpContext): List<Suggestion> {
        if (interactionHistory.isEmpty()) return emptyList()

        // 统计相同场景下的动作频率
        val actionCounts = HashMap<ActionType, Int>()
        interactionHistory.forEach { record ->
            if (record.contextType == context.contextType) {
                actionCounts.merge(record.actionType, 1) { a, b -> a + b }
            }
        }

        if (actionCounts.isEmpty()) return emptyList()

        val maxCount = actionCounts.values.maxOrNull() ?: return emptyList()
        val totalInContext = actionCounts.values.sum()

        return actionCounts.entries
            .filter { it.value >= 2 } // 至少出现 2 次才作为建议
            .map { (actionType, count) ->
                Suggestion(
                    actionType = actionType,
                    description = "${actionType.description}（历史常用）",
                    confidence = count.toFloat() / maxCount * 0.7f,
                    reason = "在此场景下已使用 ${count} 次（共 ${totalInContext} 次操作）",
                    priority = 6
                )
            }
    }

    /**
     * 基于任务上下文生成建议。
     *
     * 当用户有明确的任务上下文时，推荐与任务相关的动作。
     */
    private fun getTaskBasedSuggestions(task: String, context: HelpContext): List<Suggestion> {
        val suggestions = ArrayList<Suggestion>()
        val normalizedTask = task.lowercase()

        when {
            normalizedTask.contains("搜索") || normalizedTask.contains("查找") || normalizedTask.contains("search") ->
                suggestions.add(Suggestion(
                    actionType = ActionType.SCREEN_INPUT,
                    description = "输入搜索内容",
                    confidence = 0.85f,
                    reason = "任务上下文包含搜索意图",
                    priority = 9
                ))

            normalizedTask.contains("打开") || normalizedTask.contains("启动") || normalizedTask.contains("open") ->
                suggestions.add(Suggestion(
                    actionType = ActionType.APP_OPEN,
                    description = "打开目标应用",
                    confidence = 0.85f,
                    reason = "任务上下文包含打开应用意图",
                    priority = 9
                ))

            normalizedTask.contains("清理") || normalizedTask.contains("清除") || normalizedTask.contains("clear") ->
                suggestions.add(Suggestion(
                    actionType = ActionType.SYSTEM_CLEAR_CACHE,
                    description = "执行清理操作",
                    confidence = 0.8f,
                    reason = "任务上下文包含清理意图",
                    priority = 9
                ))

            normalizedTask.contains("设置") || normalizedTask.contains("调整") || normalizedTask.contains("config") ->
                suggestions.add(Suggestion(
                    actionType = ActionType.SCREEN_CLICK_TEXT,
                    description = "定位设置项",
                    confidence = 0.75f,
                    reason = "任务上下文包含设置调整意图",
                    priority = 8
                ))
        }

        return suggestions
    }

    // ============================================================
    // FAQ 匹配
    // ============================================================

    /**
     * 匹配用户问题与 FAQ 知识库。
     *
     * 将用户自然语言问题分词后，与每条 FAQ 条目的关键词进行匹配，计算相似度得分。
     * 返回得分超过 [MIN_FAQ_SIMILARITY] 的条目，按得分降序排列。
     * 若提供了 [context]，优先返回与当前场景匹配的条目。
     *
     * 匹配算法：
     * 1. 将问题文本分词（按空格与标点拆分，保留中英文 token）。
     * 2. 对每条 FAQ，检查其关键词是否出现在问题 token 中（支持子串包含）。
     * 3. 得分 = 匹配关键词数 / 该条目关键词总数。
     * 4. 若条目有 [applicableContexts] 且包含当前场景，得分加权 ×1.2。
     *
     * @param question 用户问题文本
     * @param context  当前帮助上下文（为 null 时不做场景过滤加权）
     * @param limit    返回的最大条目数，默认 [DEFAULT_FAQ_LIMIT]
     * @return 匹配的 FAQ 条目列表，按相似度降序排列
     */
    fun getFAQ(
        question: String,
        context: HelpContext? = null,
        limit: Int = DEFAULT_FAQ_LIMIT
    ): List<FAQEntry> {
        totalFAQQueries++

        val queryTokens = tokenize(question)
        if (queryTokens.isEmpty()) {
            Log.d(tag, "FAQ 查询: 问题为空，返回空列表")
            return emptyList()
        }

        val results = faqEntries.values
            .map { entry ->
                val baseScore = computeFAQScore(queryTokens, entry)
                // 场景匹配加权
                val contextBonus = if (context != null &&
                    entry.applicableContexts.isNotEmpty() &&
                    entry.applicableContexts.contains(context.contextType)
                ) {
                    1.2f
                } else {
                    1.0f
                }
                val finalScore = baseScore * contextBonus
                Triple(entry, finalScore, baseScore)
            }
            .filter { it.third >= MIN_FAQ_SIMILARITY }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }

        Log.d(tag, "FAQ 查询: \"$question\" → 匹配 ${results.size} 条")
        return results
    }

    /**
     * 添加自定义 FAQ 条目。
     *
     * @param entry 要添加的 FAQ 条目，ID 不能为空且不能与已有条目重复
     * @return true 表示添加成功；false 表示 ID 为空、已存在或知识库已满
     */
    fun addFAQEntry(entry: FAQEntry): Boolean {
        if (entry.id.isBlank()) {
            Log.w(tag, "FAQ 条目 ID 为空，拒绝添加")
            return false
        }
        if (faqEntries.containsKey(entry.id)) {
            Log.w(tag, "FAQ 条目已存在: ${entry.id}")
            return false
        }
        if (faqEntries.size >= MAX_FAQ_ENTRIES) {
            Log.w(tag, "FAQ 知识库已满 ($MAX_FAQ_ENTRIES)，拒绝添加: ${entry.id}")
            return false
        }
        faqEntries[entry.id] = entry
        Log.d(tag, "添加 FAQ 条目: ${entry.id} (${entry.question})")
        return true
    }

    /**
     * 计算问题 token 与 FAQ 条目的匹配得分。
     *
     * @param queryTokens 问题分词后的 token 集合
     * @param entry       FAQ 条目
     * @return 匹配得分（0.0-1.0），= 匹配关键词数 / 关键词总数
     */
    private fun computeFAQScore(queryTokens: Set<String>, entry: FAQEntry): Float {
        if (entry.keywords.isEmpty()) return 0f

        var matched = 0
        for (keyword in entry.keywords) {
            val kw = keyword.lowercase().trim()
            if (kw.isEmpty()) continue
            // 检查关键词是否出现在问题 token 中（支持子串包含与反向包含）
            val isMatched = queryTokens.any { token ->
                token.contains(kw) || kw.contains(token)
            }
            // 若 token 级别未匹配，检查关键词是否直接出现在原始问题中
            if (isMatched) {
                matched++
            }
        }

        return matched.toFloat() / entry.keywords.size
    }

    // ============================================================
    // 故障排除
    // ============================================================

    /**
     * 获取故障排除步骤。
     *
     * 根据错误类型标识或错误信息文本，从模板库中查找匹配的分步排查方案。
     * 支持精确匹配（如 "network"、"timeout"）与关键词模糊匹配。
     *
     * 匹配优先级：
     * 1. 精确匹配模板键（错误类型标识）。
     * 2. 关键词模糊匹配（如错误信息包含「网络」「超时」等关键词）。
     * 3. 回退为通用排查步骤。
     *
     * @param errorType 错误类型标识或错误信息文本
     * @param context   当前帮助上下文（用于日志记录，可为 null）
     * @return 故障排除步骤列表，按步骤序号升序排列
     */
    fun getTroubleshooting(
        errorType: String,
        context: HelpContext? = null
    ): List<TroubleshootingStep> {
        totalTroubleshootingQueries++

        val normalized = errorType.trim().lowercase()
        if (normalized.isEmpty()) {
            return getGenericTroubleshootingSteps()
        }

        // 1. 精确匹配
        troubleshootingTemplates[normalized]?.let { steps ->
            Log.d(tag, "故障排除: 精确匹配 \"$normalized\" → ${steps.size} 步")
            return steps.sortedBy { it.stepNumber }
        }

        // 2. 关键词模糊匹配
        val matchedKey = matchErrorKeywords(normalized)
        if (matchedKey != null) {
            val steps = troubleshootingTemplates[matchedKey]!!
            Log.d(tag, "故障排除: 关键词匹配 \"$normalized\" → $matchedKey (${steps.size} 步)")
            return steps.sortedBy { it.stepNumber }
        }

        // 3. 通用回退
        Log.d(tag, "故障排除: 无匹配模板 \"$normalized\"，返回通用步骤")
        return getGenericTroubleshootingSteps()
    }

    /**
     * 添加自定义故障排除模板。
     *
     * @param errorKey 错误类型标识（如 "camera_error"）
     * @param steps    排查步骤列表
     * @return true 表示添加成功；false 表示标识为空、已存在或模板库已满
     */
    fun addTroubleshootingTemplate(
        errorKey: String,
        steps: List<TroubleshootingStep>
    ): Boolean {
        val key = errorKey.trim().lowercase()
        if (key.isEmpty()) {
            Log.w(tag, "故障排除模板标识为空，拒绝添加")
            return false
        }
        if (troubleshootingTemplates.containsKey(key)) {
            Log.w(tag, "故障排除模板已存在: $key")
            return false
        }
        if (troubleshootingTemplates.size >= MAX_TROUBLESHOOTING_TEMPLATES) {
            Log.w(tag, "故障排除模板库已满 ($MAX_TROUBLESHOOTING_TEMPLATES)，拒绝添加: $key")
            return false
        }
        troubleshootingTemplates[key] = steps.toMutableList()
        Log.d(tag, "添加故障排除模板: $key (${steps.size} 步)")
        return true
    }

    /**
     * 根据错误信息文本匹配故障排除模板键。
     *
     * @param errorText 已小写化的错误信息文本
     * @return 匹配的模板键，无匹配时返回 null
     */
    private fun matchErrorKeywords(errorText: String): String? {
        return when {
            NETWORK_ERROR_KEYWORDS.any { errorText.contains(it) } -> "network"
            TIMEOUT_ERROR_KEYWORDS.any { errorText.contains(it) } -> "timeout"
            NOT_FOUND_ERROR_KEYWORDS.any { errorText.contains(it) } -> "element_not_found"
            PERMISSION_ERROR_KEYWORDS.any { errorText.contains(it) } -> "permission"
            ANR_ERROR_KEYWORDS.any { errorText.contains(it) } -> "app_not_responding"
            INPUT_ERROR_KEYWORDS.any { errorText.contains(it) } -> "input_failed"
            else -> null
        }
    }

    /**
     * 获取通用故障排除步骤（无匹配模板时的回退方案）。
     */
    private fun getGenericTroubleshootingSteps(): List<TroubleshootingStep> {
        return listOf(
            TroubleshootingStep(
                stepNumber = 1,
                description = "等待页面完全加载后重试操作",
                expectedResult = "页面内容完整显示，操作成功执行",
                actionType = ActionType.SCREEN_WAIT,
                isCritical = true
            ),
            TroubleshootingStep(
                stepNumber = 2,
                description = "检查操作描述是否准确，尝试用更清晰的语言重新描述",
                expectedResult = "系统能正确理解并执行操作",
                actionType = null,
                isCritical = false
            ),
            TroubleshootingStep(
                stepNumber = 3,
                description = "尝试滑动屏幕确认目标元素可见，然后重试",
                expectedResult = "目标元素出现在可视区域，操作成功",
                actionType = ActionType.SCREEN_SWIPE,
                isCritical = false
            ),
            TroubleshootingStep(
                stepNumber = 4,
                description = "若问题持续，尝试关闭并重新打开当前应用",
                expectedResult = "应用重新启动后操作正常",
                actionType = ActionType.APP_OPEN,
                isCritical = false
            )
        )
    }

    // ============================================================
    // 专业水平检测与设置
    // ============================================================

    /**
     * 检测用户专业水平。
     *
     * 基于交互历史的多维度分析，自动推断用户的专业水平：
     *
     * - **EXPERT（专家）**：交互次数 ≥ [EXPERT_ACTION_THRESHOLD]（200）、
     *   使用过的不同动作类型 ≥ [EXPERT_DIVERSITY_THRESHOLD]（10）、
     *   操作成功率 ≥ [EXPERT_SUCCESS_RATE_THRESHOLD]（80%）。
     * - **INTERMEDIATE（进阶）**：交互次数 ≥ [INTERMEDIATE_ACTION_THRESHOLD]（50）、
     *   使用过的不同动作类型 ≥ [INTERMEDIATE_DIVERSITY_THRESHOLD]（5）。
     * - **BEGINNER（新手）**：不满足以上条件。
     *
     * 注意：此方法仅计算不修改 [currentExpertiseLevel]。若需自动更新，
     * 确保 [autoDetectExpertise] 为 true，[recordInteraction] 会在每次记录后自动调用。
     *
     * @return 检测到的专业水平
     */
    fun detectExpertiseLevel(): ExpertiseLevel {
        if (interactionHistory.isEmpty()) {
            return ExpertiseLevel.BEGINNER
        }

        val totalActions = interactionHistory.size
        val uniqueActionTypes = actionTypeUsage.size
        val successCount = interactionHistory.count { it.success }
        val successRate = successCount.toFloat() / totalActions

        val detected = when {
            totalActions >= EXPERT_ACTION_THRESHOLD &&
                    uniqueActionTypes >= EXPERT_DIVERSITY_THRESHOLD &&
                    successRate >= EXPERT_SUCCESS_RATE_THRESHOLD -> ExpertiseLevel.EXPERT

            totalActions >= INTERMEDIATE_ACTION_THRESHOLD &&
                    uniqueActionTypes >= INTERMEDIATE_DIVERSITY_THRESHOLD -> ExpertiseLevel.INTERMEDIATE

            else -> ExpertiseLevel.BEGINNER
        }

        Log.d(tag, "专业水平检测: ${detected.displayName} " +
                "(交互=$totalActions, 多样性=$uniqueActionTypes, 成功率=${"%.1f%%".format(successRate * 100)})")
        return detected
    }

    /**
     * 手动设置用户专业水平。
     *
     * 设置后 [autoDetectExpertise] 自动关闭，后续 [recordInteraction] 不再自动覆盖。
     * 如需恢复自动推断，将 [autoDetectExpertise] 设为 true。
     *
     * @param level 要设定的专业水平
     */
    fun setExpertiseLevel(level: ExpertiseLevel) {
        currentExpertiseLevel = level
        autoDetectExpertise = false
        Log.d(tag, "手动设置专业水平: ${level.displayName} (自动推断已关闭)")
    }

    /**
     * 启用专业水平自动推断。
     *
     * 调用后 [autoDetectExpertise] 设为 true，并立即执行一次推断更新 [currentExpertiseLevel]。
     */
    fun enableAutoDetectExpertise() {
        autoDetectExpertise = true
        currentExpertiseLevel = detectExpertiseLevel()
        Log.d(tag, "已启用专业水平自动推断: ${currentExpertiseLevel.displayName}")
    }

    // ============================================================
    // 交互记录
    // ============================================================

    /**
     * 记录一次用户交互。
     *
     * 每次用户执行操作（无论成功与否）后调用，用于：
     * 1. 专业水平自动推断（当 [autoDetectExpertise] 为 true 时，每次记录后自动更新）。
     * 2. 新手引导进度跟踪（交互次数达到阈值后停止展示引导）。
     * 3. 建议引擎的历史频率分析。
     *
     * @param actionType 执行的动作类型
     * @param success    操作是否成功
     * @param context    操作发生时的帮助上下文，为 null 时场景记为 [ContextType.UNKNOWN]
     */
    fun recordInteraction(
        actionType: ActionType,
        success: Boolean,
        context: HelpContext? = null
    ) {
        totalInteractions++

        val contextType = context?.contextType ?: ContextType.UNKNOWN
        val record = InteractionRecord(
            actionType = actionType,
            success = success,
            contextType = contextType,
            timestamp = System.currentTimeMillis()
        )

        // 记录到历史队列（队首为最新），超限时从队尾丢弃
        interactionHistory.addFirst(record)
        while (interactionHistory.size > MAX_INTERACTION_HISTORY) {
            interactionHistory.pollLast()
        }

        // 更新动作类型使用计数
        actionTypeUsage.compute(actionType) { _, count -> (count ?: 0) + 1 }

        // 自动推断专业水平
        if (autoDetectExpertise) {
            val detected = detectExpertiseLevel()
            if (detected != currentExpertiseLevel) {
                currentExpertiseLevel = detected
                Log.d(tag, "专业水平自动更新: ${detected.displayName}")
            }
        }

        Log.d(tag, "记录交互: ${actionType.name} 成功=$success 场景=${contextType.displayName}")
    }

    // ============================================================
    // 统计与查询
    // ============================================================

    /**
     * 获取帮助系统统计摘要（用于 UI 展示与调试）。
     *
     * 包含专业水平、交互总数、动作多样性、FAQ 条目数、模板数、引导展示数等。
     */
    fun getStats(): String {
        val totalActions = interactionHistory.size
        val uniqueTypes = actionTypeUsage.size
        val successCount = interactionHistory.count { it.success }
        val successRate = if (totalActions > 0) {
            "%.1f%%".format(successCount.toFloat() / totalActions * 100)
        } else {
            "N/A"
        }
        return "帮助系统: 专业水平=${currentExpertiseLevel.displayName} " +
                "(自动=${autoDetectExpertise}) | 交互=$totalActions/$MAX_INTERACTION_HISTORY " +
                "多样性=$uniqueTypes 成功率=$successRate | FAQ=${faqEntries.size}/$MAX_FAQ_ENTRIES " +
                "模板=${troubleshootingTemplates.size}/$MAX_TROUBLESHOOTING_TEMPLATES " +
                "引导已展示=${shownOnboardingHints.size} " +
                "| 帮助生成=$totalHelpGenerated FAQ查询=$totalFAQQueries 排查查询=$totalTroubleshootingQueries"
    }

    /** 获取当前专业水平。 */
    fun getExpertiseLevel(): ExpertiseLevel = currentExpertiseLevel

    /** 获取交互历史条数。 */
    fun getInteractionCount(): Int = interactionHistory.size

    /** 获取已使用的不同动作类型数量。 */
    fun getActionTypeDiversity(): Int = actionTypeUsage.size

    /** 获取 FAQ 知识库中的所有条目（用于 UI 展示）。 */
    fun getAllFAQEntries(): List<FAQEntry> =
        faqEntries.values.sortedBy { it.category }

    /** 获取所有故障排除模板键（用于 UI 展示）。 */
    fun getTroubleshootingCategories(): List<String> =
        troubleshootingTemplates.keys.sorted()

    /** 获取已展示的新手引导提示 ID 列表。 */
    fun getShownOnboardingHintIds(): List<String> =
        shownOnboardingHints.sorted()

    // ============================================================
    // 辅助方法
    // ============================================================

    /**
     * 根据专业水平调整帮助内容详略。
     *
     * - BEGINNER：附加新手提示语，语气更友好。
     * - INTERMEDIATE：保持原文。
     * - EXPERT：保持原文（专家场景由调用方生成更简洁的内容）。
     *
     * @param content 原始帮助内容
     * @param level   目标专业水平
     * @return 调整后的帮助内容
     */
    private fun adjustDetail(content: String, level: ExpertiseLevel): String {
        return when (level) {
            ExpertiseLevel.BEGINNER -> content + BEGINNER_EXTRA_HINT
            ExpertiseLevel.INTERMEDIATE -> content
            ExpertiseLevel.EXPERT -> content
        }
    }

    /**
     * 从包名推断应用可读名称。
     *
     * 提取包名最后一段作为简化名称（如 "com.tencent.mm" → "mm"），
     * 无法提取时返回空字符串。
     */
    private fun resolveAppName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        val parts = packageName.split(".")
        return parts.lastOrNull()?.takeIf { it.isNotBlank() } ?: ""
    }

    /**
     * 根据场景类型与屏幕文本推断屏幕描述。
     */
    private fun resolveScreenDescription(contextType: ContextType, screenText: String?): String {
        return when (contextType) {
            ContextType.APP_HOME -> "应用首页"
            ContextType.APP_SEARCH -> "搜索界面"
            ContextType.APP_SETTINGS -> "应用设置页"
            ContextType.SYSTEM_SETTINGS -> "系统设置页"
            ContextType.LOCKED_SCREEN -> "锁屏页面"
            ContextType.UNKNOWN -> {
                if (screenText.isNullOrBlank()) "未知页面" else "未知页面（含文本: ${screenText.take(30)}）"
            }
        }
    }

    /**
     * 将文本分词为 token 集合。
     *
     * 按空格与常见标点（中英文）拆分，保留非空 token 并转为小写。
     * 适用于中英文混合的问题文本。
     *
     * @param text 原始文本
     * @return token 集合（已小写化）
     */
    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[\\s,，。.!！?？;；:：、/\\\\()（）\"'\\[\\]【】]+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    // ============================================================
    // 重置
    // ============================================================

    /**
     * 清空所有交互历史、引导记录与统计计数。
     *
     * 注意：此方法不会清除默认 FAQ 条目与故障排除模板。
     * 专业水平重置为 BEGINNER，自动推断重新启用。
     */
    fun clear() {
        interactionHistory.clear()
        shownOnboardingHints.clear()
        actionTypeUsage.clear()
        currentExpertiseLevel = ExpertiseLevel.BEGINNER
        autoDetectExpertise = true
        totalInteractions = 0
        totalHelpGenerated = 0
        totalFAQQueries = 0
        totalTroubleshootingQueries = 0
        Log.d(tag, "已清空所有交互历史与统计 (保留 FAQ 与模板)")
    }

    /**
     * 清空所有自定义 FAQ 条目与故障排除模板，恢复为默认状态。
     *
     * 注意：此方法会移除通过 [addFAQEntry] 和 [addTroubleshootingTemplate] 添加的自定义内容，
     * 并重新初始化默认条目。通常仅在调试或重置场景使用。
     */
    fun resetToDefaults() {
        faqEntries.clear()
        troubleshootingTemplates.clear()
        initializeDefaultFAQs()
        initializeDefaultTroubleshootingTemplates()
        Log.d(tag, "已重置 FAQ 与模板为默认状态")
    }
}
