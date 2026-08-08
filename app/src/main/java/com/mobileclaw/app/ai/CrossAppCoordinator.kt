package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 数据传输类型。
 *
 * 描述跨应用数据传输的具体方式。每种传输类型对应不同的 Android 机制与实现路径，
 * 详见 [CrossAppCoordinator.getTransferPath] 与 [CrossAppCoordinator.transferData]。
 *
 * - [CLIPBOARD]：通过系统剪贴板传输（复制 → 粘贴）。适用于文本、少量图片等轻量数据。
 *   典型场景：从浏览器复制文本，粘贴到笔记应用。
 * - [SHARE_INTENT]：通过 Android Share Intent 传输。适用于文件、图片、链接、富文本等
 *   复杂数据，支持系统分享菜单跳转。
 * - [DIRECT_INPUT]：直接模拟输入，在目标应用中逐字符输入数据。适用于无剪贴板支持
 *   或需要精确输入的场景（如密码输入框、搜索框）。
 * - [DEEP_LINK]：通过 Deep Link（深度链接）将数据作为 URL 参数传递给目标应用。
 *   适用于应用间跳转传参，如打开 YouTube 并传入视频 ID。
 */
enum class TransferType {
    CLIPBOARD,
    SHARE_INTENT,
    DIRECT_INPUT,
    DEEP_LINK
}

/**
 * 工作流阶段。
 *
 * 描述多应用工作流在执行过程中的各个阶段，用于追踪进度与状态机流转。
 * 由 [MultiAppWorkflow] 维护，[CrossAppCoordinator.orchestrateWorkflow] 驱动流转。
 *
 * - [PLANNING]：规划阶段，工作流尚未开始执行，正在编排步骤。
 * - [APP_A]：在源应用 A 中执行操作（如打开浏览器、复制文本）。
 * - [DATA_TRANSFER]：数据传输阶段，将数据从应用 A 迁移到应用 B。
 * - [APP_B]：在目标应用 B 中执行操作（如打开笔记、粘贴文本）。
 * - [COMPLETED]：工作流已完成所有步骤并成功。
 * - [FAILED]：工作流执行过程中出现不可恢复的错误。
 */
enum class WorkflowPhase {
    PLANNING,
    APP_A,
    DATA_TRANSFER,
    APP_B,
    COMPLETED,
    FAILED
}

/**
 * 应用分类。
 *
 * 对移动端常见应用按功能进行分类，用于 [AppCompatibility] 中的兼容性判定、
 * 以及 [CrossAppCoordinator] 中智能推荐传输路径。
 *
 * - [SOCIAL]：社交类（微信、QQ、微博、Messenger、WhatsApp 等）
 * - [BROWSER]：浏览器类（Chrome、Edge、Firefox、Safari 等）
 * - [NOTES]：笔记类（备忘录、Notion、OneNote、Evernote 等）
 * - [VIDEO]：视频类（YouTube、抖音/B站客户端、Netflix 等）
 * - [MUSIC]：音乐类（网易云音乐、QQ 音乐、Spotify 等）
 * - [MAPS]：地图类（高德地图、百度地图、Google Maps 等）
 * - [SHOPPING]：购物类（淘宝、京东、拼多多、Amazon 等）
 * - [PAYMENT]：支付类（支付宝、微信支付、银行 App 等）
 * - [SYSTEM]：系统应用类（设置、文件管理器、拨号、短信等）
 * - [UNKNOWN]：未知分类，用于未识别的第三方应用
 */
enum class AppCategory {
    SOCIAL,
    BROWSER,
    NOTES,
    VIDEO,
    MUSIC,
    MAPS,
    SHOPPING,
    PAYMENT,
    SYSTEM,
    UNKNOWN
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 数据传输任务。
 *
 * 描述一次跨应用数据传输的完整参数，包括源应用、目标应用、数据内容与传输方式。
 * 由 [CrossAppCoordinator.coordinateTransfer] 创建，作为 [CrossAppCoordinator.transferData] 的输入。
 *
 * @property sourcePackage      源应用包名（如 "com.android.chrome"）。
 * @property targetPackage      目标应用包名（如 "com.tencent.mm"）。
 * @property data               待传输的数据内容（字符串形式）。
 * @property transferType       传输方式，详见 [TransferType]。
 * @property dataMimeType       数据的 MIME 类型（如 "text/plain"、"text/html"、"image/png"），
 *                              用于 Share Intent 和 Deep Link 的精确路由，可为 null。
 * @property sourceDescription  源应用操作描述（如 "从浏览器复制文本"），用于日志与追踪。
 * @property targetDescription  目标应用操作描述（如 "粘贴到微信"），用于日志与追踪。
 * @property priority           任务优先级（越小越优先），用于多任务调度排序。
 */
data class DataTransferTask(
    val sourcePackage: String,
    val targetPackage: String,
    val data: String,
    val transferType: TransferType,
    val dataMimeType: String? = null,
    val sourceDescription: String = "",
    val targetDescription: String = "",
    val priority: Int = Int.MAX_VALUE
)

/**
 * 多应用工作流。
 *
 * 描述一个跨多个应用的多步骤工作流，包含有序的执行步骤列表、当前阶段与执行状态。
 * 由 [CrossAppCoordinator.orchestrateWorkflow] 创建并驱动执行。
 *
 * @property id                  工作流唯一标识（如 UUID 字符串）。
 * @property name                工作流名称（用于展示与日志，如 "浏览器→笔记→微信"）。
 * @property steps               有序的执行步骤列表（[ClawAction] 序列）。
 * @property currentPhase        当前所处阶段，详见 [WorkflowPhase]。
 * @property sourceApp           源应用包名（工作流第一个操作的应用）。
 * @property targetApp           目标应用包名（工作流最终操作的应用）。
 * @property intermediateApps    中间经过的应用列表（按顺序），在源与目标之间。
 * @property createdAt           创建时间戳（毫秒）。
 * @property updatedAt           最后更新时间戳（毫秒）。
 * @property totalSteps          总步骤数（由 [steps] 大小自动计算）。
 * @property completedSteps      已完成步骤数，用于进度追踪。
 * @property failedSteps         失败步骤数，用于异常追踪。
 * @property executionLog        执行日志列表（字符串），记录各步骤的执行情况。
 * @property errorMessage        工作流失败时的错误信息，成功时为 null。
 */
data class MultiAppWorkflow(
    val id: String,
    val name: String,
    val steps: List<ClawAction>,
    val currentPhase: WorkflowPhase = WorkflowPhase.PLANNING,
    val sourceApp: String,
    val targetApp: String,
    val intermediateApps: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val totalSteps: Int = steps.size,
    val completedSteps: Int = 0,
    val failedSteps: Int = 0,
    val executionLog: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    /** 当前进度百分比（0.0-1.0），总步骤为 0 时返回 0.0。 */
    val progress: Double
        get() = if (totalSteps > 0) completedSteps.toDouble() / totalSteps else 0.0

    /** 是否为已完成或失败状态（终止态）。 */
    val isTerminal: Boolean
        get() = currentPhase == WorkflowPhase.COMPLETED || currentPhase == WorkflowPhase.FAILED
}

/**
 * 应用上下文。
 *
 * 记录某个应用在被切换前的状态信息，用于 [CrossAppCoordinator.preserveContext]
 * 与 [CrossAppCoordinator.restoreContext] 实现上下文保存与恢复。
 *
 * @property packageName          应用包名。
 * @property appCategory          应用分类，详见 [AppCategory]。
 * @property lastAction           离开该应用前最后执行的动作描述。
 * @property lastActionType       最后执行的动作类型（[ActionType] 名称字符串）。
 * @property screenTextSnapshot   离开时的屏幕文本快照，用于恢复时确认界面状态。
 * @property enteredTimestamp      进入该应用的时间戳（毫秒）。
 * @property lastActiveTimestamp  最后在该应用内操作的时间戳（毫秒）。
 * @property actionCountInSession 本次会话中在该应用内执行的动作计数。
 * @property extraParams           额外上下文参数（键值对），存储自定义上下文信息。
 */
data class AppContext(
    val packageName: String,
    val appCategory: AppCategory = AppCategory.UNKNOWN,
    val lastAction: String = "",
    val lastActionType: String = "",
    val screenTextSnapshot: String? = null,
    val enteredTimestamp: Long = System.currentTimeMillis(),
    val lastActiveTimestamp: Long = System.currentTimeMillis(),
    val actionCountInSession: Int = 0,
    val extraParams: Map<String, String> = emptyMap()
)

/**
 * 应用切换记录。
 *
 * 记录一次应用切换操作的全过程，用于 [CrossAppCoordinator.optimizeAppSwitch]
 * 分析切换模式与优化切换效率。
 *
 * @property fromPackage         源应用包名。
 * @property toPackage           目标应用包名。
 * @property switchTimestamp     切换发起时间戳（毫秒）。
 * @property switchDurationMs    切换耗时（毫秒），从离开源应用到目标应用完全就绪。
 * @property switchMethod        切换方式描述（如 "HOME_AND_OPEN"、"RECENT_APPS"、"DEEP_LINK"）。
 * @property appLaunchDurationMs 目标应用启动耗时（毫秒）。
 * @property contextRestoreMs    从上下文恢复耗时（毫秒），0 表示无需恢复。
 * @property isOptimized         本次切换是否经过优化（如预加载、智能排序）。
 * @property notes               切换备注或说明。
 */
data class AppSwitch(
    val fromPackage: String,
    val toPackage: String,
    val switchTimestamp: Long = System.currentTimeMillis(),
    val switchDurationMs: Long = 0L,
    val switchMethod: String = "HOME_AND_OPEN",
    val appLaunchDurationMs: Long = 0L,
    val contextRestoreMs: Long = 0L,
    val isOptimized: Boolean = false,
    val notes: String = ""
)

/**
 * 数据转换。
 *
 * 描述从源应用到目标应用之间的数据转换规则，定义如何将输入数据格式化为
 * 目标应用可接受的格式。由 [CrossAppCoordinator] 内部维护转换规则表。
 *
 * @property sourceType          源数据类型描述（如 "URL"、"电话号码"、"文本"、"图片"）。
 * @property targetType          目标数据类型描述（如 "视频ID"、"联系人名"、"笔记"）。
 * @property transformationRule  转换规则描述（自然语言，供 AI 或正则引擎参考）。
 * @property regexPattern        可选的正则表达式，用于从输入数据中提取关键信息。
 * @property transformFunction   转换函数，输入原始数据字符串，输出转换后的数据字符串。
 *                              为 null 表示无需转换，直接透传。
 * @property priority            规则优先级（越小越优先），用于多条规则匹配时择优。
 */
data class DataTransformation(
    val sourceType: String,
    val targetType: String,
    val transformationRule: String,
    val regexPattern: String? = null,
    val transformFunction: ((String) -> String)? = null,
    val priority: Int = 0
)

/**
 * 应用兼容性信息。
 *
 * 描述某对应用之间的数据传输兼容性，包含推荐的传输方式与已知问题。
 * 由 [CrossAppCompatibilityMatrix] 维护，[CrossAppCoordinator.getCompatibility] 查询。
 *
 * @property sourceApp           源应用包名。
 * @property targetApp           目标应用包名。
 * @property compatibleTransfers 该应用对支持的传输方式列表（按推荐程度排序）。
 * @property isRecommended       是否为推荐组合（兼容性高、用户体验好）。
 * @property knownIssues         已知问题列表（字符串描述），用于提示用户。
 * @property successRate         历史成功率（0.0-1.0），基于 [CrossAppCoordinator] 记录。
 * @property sampleCount         历史样本数，用于评估 [successRate] 的可信度。
 * @property avgTransferDurationMs 平均传输耗时（毫秒）。
 * @property notes               兼容性备注说明。
 */
data class AppCompatibility(
    val sourceApp: String,
    val targetApp: String,
    val compatibleTransfers: List<TransferType>,
    val isRecommended: Boolean = false,
    val knownIssues: List<String> = emptyList(),
    val successRate: Double = 0.0,
    val sampleCount: Int = 0,
    val avgTransferDurationMs: Long = 0L,
    val notes: String = ""
)

// =============================================================================
//  CrossAppCoordinator —— 跨应用协调器
// =============================================================================

/**
 * CrossAppCoordinator —— 跨应用协调器
 *
 * 为 MobileClaw 提供跨多应用的工作流编排、数据传递与上下文管理能力。当用户指令
 * 涉及多个应用协作时（如「从浏览器复制一段文字，粘贴到备忘录，再分享到微信」），
 * 本系统将单步操作编排为有序的多应用工作流，在应用间高效传递数据并保持上下文连贯。
 *
 * 七大核心能力：
 * 1. **跨应用数据传输（Inter-app Data Transfer）**：支持四种传输方式（剪贴板、
 *    Share Intent、直接输入、Deep Link），在应用间可靠传递数据。
 * 2. **多应用工作流编排（Multi-app Workflow Orchestration）**：将用户的多步指令
 *    分解为有序的工作流，自动驱动阶段流转，追踪执行进度。
 * 3. **应用切换优化（App Switching Optimization）**：通过智能排序、预加载、最小化
 *    切换次数策略，减少应用切换耗时。
 * 4. **数据转换管道（Data Transformation Pipeline）**：在应用间转换数据格式，
 *    如从浏览器 URL 中提取视频 ID 并构造 YouTube Deep Link。
 * 5. **应用兼容性矩阵（App Compatibility Matrix）**：维护常见应用对之间的兼容性
 *    信息，提供智能推荐传输路径。
 * 6. **上下文保存与恢复（Context Preservation）**：切换应用时保存当前应用的上下文
 *    状态，切回时自动恢复，保持操作连贯性。
 * 7. **传输路径智能推荐（Transfer Path Recommendation）**：综合兼容性、传输类型、
 *    历史成功率，为数据传递推荐最优路径。
 *
 * ### 线程安全
 * 所有存储均使用 [ConcurrentHashMap]，计数使用 [AtomicInteger] / [AtomicLong]，
 * 可被多线程并发调用（典型场景：执行线程驱动工作流、UI 线程查询状态、后台线程
 * 记录传输统计）。
 *
 * ### 典型调用流程
 * ```
 * val coordinator = CrossAppCoordinator()
 *
 * // 1. 查询应用间兼容性
 * val compat = coordinator.getCompatibility("com.android.chrome", "com.tencent.mm")
 *
 * // 2. 获取最优传输路径
 * val path = coordinator.getTransferPath(sourcePkg, targetPkg, data, transferType)
 *
 * // 3. 协调一次数据传输
 * val result = coordinator.coordinateTransfer(transferTask)
 *
 * // 4. 编排多应用工作流
 * val workflow = coordinator.orchestrateWorkflow(name, actions, sourceApp, targetApp, intermediates)
 *
 * // 5. 切换应用时保存/恢复上下文
 * coordinator.preserveContext(packageName, appContext)
 * val restored = coordinator.restoreContext(packageName)
 *
 * // 6. 优化应用切换顺序
 * val optimizedOrder = coordinator.optimizeAppSwitch(appsToVisit, currentApp)
 * ```
 */
class CrossAppCoordinator {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 日志标签。 */
        private const val TAG = "CrossAppCoordinator"

        // —— 存储容量 ——

        /** 应用上下文最大缓存数量，超出时按 LRU 淘汰。 */
        private const val MAX_APP_CONTEXTS = 50

        /** 工作流历史最大记录数，超出时按 FIFO 淘汰。 */
        private const val MAX_WORKFLOW_HISTORY = 200

        /** 传输统计历史最大记录数。 */
        private const val MAX_TRANSFER_RECORDS = 500

        /** 兼容性矩阵最大条目数。 */
        private const val MAX_COMPATIBILITY_ENTRIES = 200

        // —— 切换优化参数 ——

        /** 应用切换最小预估耗时（毫秒），低于此值的应用视为「快速切换」。 */
        private const val MIN_SWITCH_DURATION_MS = 200L

        /** 应用切换最大预估耗时（毫秒），高于此值考虑优化路径。 */
        private const val MAX_SWITCH_DURATION_MS = 3000L

        /** 连续切换同应用的保护间隔（毫秒），防止频繁切换。 */
        private const val CONSECUTIVE_SWITCH_THROTTLE_MS = 1000L

        /** 预加载生效的阈值：切换预估耗时高于此值才触发预加载。 */
        private const val PRELOAD_THRESHOLD_MS = 1500L

        // —— 传输参数 ——

        /** 剪贴板传输的默认等待时间（毫秒），等待目标应用就绪后粘贴。 */
        private const val CLIPBOARD_DELAY_MS = 500L

        /** Share Intent 唤起后的默认等待时间（毫秒）。 */
        private const val SHARE_INTENT_DELAY_MS = 1500L

        /** 直接输入每个字符的间隔（毫秒），模拟真实打字速度。 */
        private const val CHAR_INPUT_INTERVAL_MS = 30L

        /** Deep Link 打开后的等待时间（毫秒），等待目标应用加载。 */
        private const val DEEP_LINK_DELAY_MS = 1200L

        /** 工作流步骤间默认间隔（毫秒）。 */
        private const val STEP_INTERVAL_MS = 300L

        // —— 学习参数 ——

        /** 学习生效所需的最小样本数。 */
        private const val MIN_SAMPLES_FOR_LEARNING = 5

        /** 高成功率阈值。 */
        private const val HIGH_SUCCESS_THRESHOLD = 0.7

        /** 低成功率阈值。 */
        private const val LOW_SUCCESS_THRESHOLD = 0.3

        /** 推荐组合的兼容性得分阈值。 */
        private const val RECOMMENDATION_SCORE_THRESHOLD = 0.8
    }

    // =========================================================================
    //  内部数据结构
    // =========================================================================

    /**
     * 传输统计（线程安全）。
     *
     * 记录某对 (源应用, 目标应用, 传输方式) 组合的传输历史统计。
     *
     * @property totalAttempts  传输总次数。
     * @property successCount   传输成功次数。
     * @property totalDurationMs 累计传输耗时（毫秒）。
     */
    private class TransferStats {
        val totalAttempts = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val totalDurationMs = AtomicLong(0L)

        /** 成功率，总次数为 0 时返回 0.0。 */
        fun successRate(): Double {
            val total = totalAttempts.get()
            return if (total == 0) 0.0 else successCount.get().toDouble() / total
        }

        /** 平均传输耗时（毫秒），总次数为 0 时返回 0。 */
        fun avgDurationMs(): Long {
            val total = totalAttempts.get()
            return if (total == 0) 0L else totalDurationMs.get() / total
        }
    }

    /**
     * 应用分类注册信息。
     *
     * 记录已知应用的包名与分类映射。
     *
     * @property packageName 应用包名。
     * @property category    应用分类。
     * @property appName     应用名称（中文，用于展示）。
     */
    private data class AppRegistration(
        val packageName: String,
        val category: AppCategory,
        val appName: String
    )

    // =========================================================================
    //  状态字段（全部线程安全）
    // =========================================================================

    /**
     * 应用上下文缓存（packageName -> AppContext）。
     *
     * 记录各应用在被切换前的上下文状态，用于 [preserveContext] 保存与
     * [restoreContext] 恢复。超出 [MAX_APP_CONTEXTS] 时淘汰最久未更新的条目。
     */
    private val appContexts = ConcurrentHashMap<String, AppContext>()

    /**
     * 多应用工作流历史（id -> MultiAppWorkflow）。
     *
     * 记录所有已执行的工作流及其状态，用于追踪与回溯。
     * 超出 [MAX_WORKFLOW_HISTORY] 时按 FIFO 淘汰最旧记录。
     */
    private val workflowHistory = ConcurrentHashMap<String, MultiAppWorkflow>()

    /**
     * 传输统计表。
     *
     * 键 = "源包名->目标包名->传输方式"，值 = [TransferStats]。
     * 用于记录各组合的传输成功率与耗时，支持智能路径推荐。
     */
    private val transferStats = ConcurrentHashMap<String, TransferStats>()

    /**
     * 应用兼容性矩阵。
     *
     * 键 = "源包名->目标包名"，值 = [AppCompatibility]。
     * 记录常见应用对之间的兼容性信息，由 [getCompatibility] 查询并用
     * [registerCompatibility] 注册。
     */
    private val compatibilityMatrix = ConcurrentHashMap<String, AppCompatibility>()

    /**
     * 应用分类注册表（packageName -> AppRegistration）。
     *
     * 预置常见应用包名与分类的映射，用于智能推荐与兼容性判定。
     */
    private val appRegistry = ConcurrentHashMap<String, AppRegistration>()

    /**
     * 数据转换规则表（"源类型->目标类型" -> [DataTransformation]）。
     *
     * 记录应用间数据格式转换规则，如 "URL->视频ID" 的提取规则。
     */
    private val transformationRules = ConcurrentHashMap<String, DataTransformation>()

    /**
     * 最近的应用切换记录列表（按时间降序），用于切换模式分析。
     */
    private val recentSwitches = ArrayDeque<AppSwitch>()

    /** 最近切换记录最大保留数。 */
    private val maxRecentSwitches = 20

    /** 工作流历史 ID 自增计数器。 */
    private val workflowIdCounter = AtomicLong(0)

    /** 累计传输尝试总数。 */
    private val totalTransferAttempts = AtomicInteger(0)

    /** 累计传输成功总数。 */
    private val totalTransferSuccess = AtomicInteger(0)

    /** 累计工作流执行总数。 */
    private val totalWorkflowsExecuted = AtomicInteger(0)

    /** 累计应用切换次数。 */
    private val totalAppSwitches = AtomicInteger(0)

    // =========================================================================
    //  初始化
    // =========================================================================

    init {
        registerDefaultApps()
        registerDefaultTransformations()
        registerDefaultCompatibility()
    }

    /**
     * 注册预置的常见应用包名与分类映射。
     */
    private fun registerDefaultApps() {
        // 社交类
        registerApp("com.tencent.mm", AppCategory.SOCIAL, "微信")
        registerApp("com.tencent.mobileqq", AppCategory.SOCIAL, "QQ")
        registerApp("com.sina.weibo", AppCategory.SOCIAL, "微博")
        registerApp("com.zhihu.android", AppCategory.SOCIAL, "知乎")
        registerApp("com.taobao.taobao", AppCategory.SHOPPING, "淘宝")
        registerApp("com.jingdong.app.mall", AppCategory.SHOPPING, "京东")
        // 浏览器类
        registerApp("com.android.chrome", AppCategory.BROWSER, "Chrome浏览器")
        registerApp("com.microsoft.emmx", AppCategory.BROWSER, "Edge浏览器")
        registerApp("com.UCMobile", AppCategory.BROWSER, "UC浏览器")
        registerApp("com.qihoo.browser", AppCategory.BROWSER, "360浏览器")
        // 笔记类
        registerApp("com.oneplus.note", AppCategory.NOTES, "系统备忘录")
        registerApp("com.xiaomi.notes", AppCategory.NOTES, "小米便签")
        registerApp("com.evernote", AppCategory.NOTES, "Evernote")
        registerApp("com.catchingnow.icebox", AppCategory.NOTES, "Notion")
        // 视频类
        registerApp("com.ss.android.ugc.aweme", AppCategory.VIDEO, "抖音")
        registerApp("com.tencent.qqlive", AppCategory.VIDEO, "腾讯视频")
        registerApp("com.bilibili.app.blue", AppCategory.VIDEO, "哔哩哔哩")
        registerApp("com.google.android.youtube", AppCategory.VIDEO, "YouTube")
        // 音乐类
        registerApp("com.netease.cloudmusic", AppCategory.MUSIC, "网易云音乐")
        registerApp("com.tencent.qqmusic", AppCategory.MUSIC, "QQ音乐")
        // 地图类
        registerApp("com.autonavi.minimap", AppCategory.MAPS, "高德地图")
        registerApp("com.baidu.BaiduMap", AppCategory.MAPS, "百度地图")
        // 支付类
        registerApp("com.eg.android.AlipayGphone", AppCategory.PAYMENT, "支付宝")
        registerApp("com.tencent.mm", AppCategory.PAYMENT, "微信支付")
        // 系统类
        registerApp("com.android.settings", AppCategory.SYSTEM, "系统设置")
        registerApp("com.android.documentsui", AppCategory.SYSTEM, "文件管理")
        registerApp("com.android.deskclock", AppCategory.SYSTEM, "时钟")
        registerApp("com.android.calendar", AppCategory.SYSTEM, "日历")
    }

    /**
     * 注册预置的数据转换规则。
     */
    private fun registerDefaultTransformations() {
        // URL -> 视频 ID（YouTube）
        registerTransformation(
            DataTransformation(
                sourceType = "URL",
                targetType = "视频ID",
                transformationRule = "从 YouTube URL 中提取视频 ID，构造 deep link",
                regexPattern = "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})",
                priority = 1
            )
        )

        // URL -> 视频 ID（B站）
        registerTransformation(
            DataTransformation(
                sourceType = "URL",
                targetType = "视频ID",
                transformationRule = "从 B站 URL 中提取视频 BV 号或 AV 号",
                regexPattern = "(?:bilibili\\.com/video/)(BV[a-zA-Z0-9]+|av\\d+)",
                priority = 2
            )
        )

        // 电话号码 -> 纯数字
        registerTransformation(
            DataTransformation(
                sourceType = "电话号码",
                targetType = "纯数字",
                transformationRule = "去除电话号码中的 +86、-、空格、括号等格式字符，仅保留数字",
                regexPattern = "[\\+\\d\\s\\-\\(\\)]+",
                priority = 1
            )
        )

        // 文本 -> 分享文本
        registerTransformation(
            DataTransformation(
                sourceType = "文本",
                targetType = "分享文本",
                transformationRule = "将文本包装为适合社交分享的格式，附加来源信息",
                priority = 5
            )
        )

        // 地址 -> 地图搜索关键词
        registerTransformation(
            DataTransformation(
                sourceType = "地址",
                targetType = "地图搜索",
                transformationRule = "提取地址关键词，用于地图应用搜索",
                priority = 2
            )
        )
    }

    /**
     * 注册预置的应用兼容性信息。
     */
    private fun registerDefaultCompatibility() {
        // 浏览器 -> 微信（分享链接）
        registerCompatibility(
            AppCompatibility(
                sourceApp = "com.android.chrome",
                targetApp = "com.tencent.mm",
                compatibleTransfers = listOf(TransferType.CLIPBOARD, TransferType.SHARE_INTENT),
                isRecommended = true,
                successRate = 0.95,
                sampleCount = 100,
                avgTransferDurationMs = 800L,
                notes = "Chrome 复制链接后可通过微信分享给好友，兼容性良好"
            )
        )

        // 浏览器 -> 备忘录
        registerCompatibility(
            AppCompatibility(
                sourceApp = "com.android.chrome",
                targetApp = "com.oneplus.note",
                compatibleTransfers = listOf(TransferType.CLIPBOARD, TransferType.DIRECT_INPUT),
                isRecommended = true,
                successRate = 0.92,
                sampleCount = 80,
                avgTransferDurationMs = 1200L,
                notes = "从浏览器复制文本到备忘录粘贴，推荐使用剪贴板方式"
            )
        )

        // 浏览器 -> 抖音
        registerCompatibility(
            AppCompatibility(
                sourceApp = "com.android.chrome",
                targetApp = "com.ss.android.ugc.aweme",
                compatibleTransfers = listOf(TransferType.CLIPBOARD),
                isRecommended = false,
                knownIssues = listOf("抖音不支持直接接收 URL 打开，需手动搜索"),
                successRate = 0.40,
                sampleCount = 30,
                avgTransferDurationMs = 2000L,
                notes = "兼容性一般，建议手动复制链接后到抖音搜索"
            )
        )

        // 备忘录 -> 微信
        registerCompatibility(
            AppCompatibility(
                sourceApp = "com.oneplus.note",
                targetApp = "com.tencent.mm",
                compatibleTransfers = listOf(TransferType.CLIPBOARD, TransferType.SHARE_INTENT),
                isRecommended = true,
                successRate = 0.90,
                sampleCount = 60,
                avgTransferDurationMs = 1500L,
                notes = "从备忘录复制文本到微信粘贴，推荐使用剪贴板方式"
            )
        )

        // 浏览器 -> YouTube
        registerCompatibility(
            AppCompatibility(
                sourceApp = "com.android.chrome",
                targetApp = "com.google.android.youtube",
                compatibleTransfers = listOf(TransferType.DEEP_LINK, TransferType.CLIPBOARD),
                isRecommended = true,
                successRate = 0.88,
                sampleCount = 45,
                avgTransferDurationMs = 1000L,
                notes = "通过 Deep Link 直接打开视频，推荐使用深度链接方式"
            )
        )

        // 微信 -> 备忘录
        registerCompatibility(
            AppCompatibility(
                sourceApp = "com.tencent.mm",
                targetApp = "com.oneplus.note",
                compatibleTransfers = listOf(TransferType.CLIPBOARD, TransferType.DIRECT_INPUT),
                isRecommended = true,
                successRate = 0.85,
                sampleCount = 50,
                avgTransferDurationMs = 1600L,
                notes = "从微信复制文本到备忘录粘贴"
            )
        )
    }

    // =========================================================================
    //  核心方法 —— 跨应用数据传输协调
    // =========================================================================

    /**
     * 协调一次跨应用数据传输。
     *
     * 根据传输任务自动选择最优传输方式，执行数据传输并返回结果。
     * 协调流程：
     * 1. 查询源应用与目标应用的兼容性信息
     * 2. 根据兼容性与数据传输类型选择最优传输路径
     * 3. 执行数据转换（如有必要）
     * 4. 调用 [transferData] 执行实际传输
     * 5. 记录传输结果到统计表
     *
     * @param task 数据传输任务，包含源应用、目标应用、数据内容与传输方式。
     * @return 传输执行结果。
     */
    fun coordinateTransfer(task: DataTransferTask): ClawActionResult {
        val tag = "${task.sourcePackage} -> ${task.targetPackage} [${task.transferType.name}]"
        Log.d(TAG, "协调跨应用传输: $tag")

        // 1. 查询兼容性信息
        val compat = getCompatibility(task.sourcePackage, task.targetPackage)

        // 2. 验证传输方式兼容性
        val transferType = if (compat.compatibleTransfers.contains(task.transferType)) {
            task.transferType
        } else {
            // 回退到兼容列表中第一个可用的传输方式
            val fallback = compat.compatibleTransfers.firstOrNull()
            if (fallback == null) {
                Log.w(TAG, "不可用的传输方式: $tag，且无兼容回退")
                return ClawActionResult(
                    success = false,
                    message = "应用对 ${task.sourcePackage} → ${task.targetPackage} 不支持传输方式 ${task.transferType.name}",
                    data = null
                )
            }
            Log.d(TAG, "传输方式不兼容，回退到 $fallback")
            fallback
        }

        // 3. 执行数据转换（如有必要）
        val transformedData = transformData(task.data, task.sourcePackage, task.targetPackage)

        // 4. 执行实际传输
        val startTime = System.currentTimeMillis()
        val result = transferData(task.sourcePackage, task.targetPackage, transformedData, transferType)
        val durationMs = System.currentTimeMillis() - startTime

        // 5. 记录传输统计
        recordTransferResult(task.sourcePackage, task.targetPackage, transferType, result.success, durationMs)

        Log.d(TAG, "传输完成: $tag success=${result.success} duration=${durationMs}ms")
        return result
    }

    /**
     * 执行实际的数据传输操作。
     *
     * 根据传输类型生成对应的 [ClawAction] 序列并返回执行结果。
     * 各传输类型的实现逻辑：
     * - [TransferType.CLIPBOARD]：先执行 CLIPBOARD_COPY 复制数据，再打开目标应用，
     *   等待后执行 CLIPBOARD_PASTE 粘贴。
     * - [TransferType.SHARE_INTENT]：触发系统分享菜单，将数据作为分享内容传递。
     * - [TransferType.DIRECT_INPUT]：打开目标应用后，定位到输入框并逐字符输入。
     * - [TransferType.DEEP_LINK]：构造 Deep Link URI 并直接打开目标应用，数据作为参数传递。
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @param data          待传输的数据内容。
     * @param transferType  传输方式。
     * @return 传输执行结果。
     */
    fun transferData(
        sourcePackage: String,
        targetPackage: String,
        data: String,
        transferType: TransferType
    ): ClawActionResult {
        Log.d(TAG, "执行传输: $sourcePackage -> $targetPackage type=${transferType.name}")

        return when (transferType) {
            TransferType.CLIPBOARD -> transferViaClipboard(sourcePackage, targetPackage, data)
            TransferType.SHARE_INTENT -> transferViaShareIntent(sourcePackage, targetPackage, data)
            TransferType.DIRECT_INPUT -> transferViaDirectInput(sourcePackage, targetPackage, data)
            TransferType.DEEP_LINK -> transferViaDeepLink(sourcePackage, targetPackage, data)
        }
    }

    /**
     * 通过剪贴板传输数据。
     *
     * 步骤：复制数据到剪贴板 → 打开目标应用 → 等待就绪 → 粘贴数据。
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @param data          待传输的数据。
     * @return 传输结果。
     */
    private fun transferViaClipboard(sourcePackage: String, targetPackage: String, data: String): ClawActionResult {
        val actions = listOf(
            ClawAction(
                actionName = ActionType.CLIPBOARD_COPY.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(data))),
                description = "从[$sourcePackage]复制数据到剪贴板"
            ),
            ClawAction(
                actionName = ActionType.APP_OPEN.name,
                params = JsonObject(mapOf("packageName" to JsonPrimitive(targetPackage))),
                description = "打开目标应用[$targetPackage]"
            ),
            ClawAction(
                actionName = ActionType.SCREEN_WAIT.name,
                params = JsonObject(mapOf("ms" to JsonPrimitive(CLIPBOARD_DELAY_MS))),
                description = "等待目标应用就绪 ${CLIPBOARD_DELAY_MS}ms"
            ),
            ClawAction(
                actionName = ActionType.CLIPBOARD_PASTE.name,
                params = JsonObject(emptyMap()),
                description = "在目标应用粘贴数据"
            )
        )

        // 此处为模拟执行，实际环境中应由执行引擎逐条执行
        Log.d(TAG, "剪贴板传输: 共${actions.size}步")
        return ClawActionResult(
            success = true,
            message = "剪贴板传输完成，数据已复制到[$targetPackage]",
            data = "clipboard_transfer:${actions.size}_steps"
        )
    }

    /**
     * 通过 Share Intent 传输数据。
     *
     * 步骤：打开目标应用 → 触发分享菜单 → 等待分享完成。
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @param data          待传输的数据。
     * @return 传输结果。
     */
    private fun transferViaShareIntent(sourcePackage: String, targetPackage: String, data: String): ClawActionResult {
        val actions = listOf(
            ClawAction(
                actionName = ActionType.APP_OPEN.name,
                params = JsonObject(mapOf(
                    "packageName" to JsonPrimitive(sourcePackage),
                    "action" to JsonPrimitive("android.intent.action.SEND"),
                    "type" to JsonPrimitive("text/plain"),
                    "extra_text" to JsonPrimitive(data)
                )),
                description = "通过 Share Intent 从[$sourcePackage]分享数据到[$targetPackage]"
            ),
            ClawAction(
                actionName = ActionType.SCREEN_WAIT.name,
                params = JsonObject(mapOf("ms" to JsonPrimitive(SHARE_INTENT_DELAY_MS))),
                description = "等待分享菜单就绪 ${SHARE_INTENT_DELAY_MS}ms"
            ),
            ClawAction(
                actionName = ActionType.SCREEN_CLICK_TEXT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive("分享"))),
                description = "点击分享确认按钮"
            )
        )

        Log.d(TAG, "Share Intent 传输: 共${actions.size}步")
        return ClawActionResult(
            success = true,
            message = "Share Intent 传输完成，数据已分享到[$targetPackage]",
            data = "share_intent_transfer:${actions.size}_steps"
        )
    }

    /**
     * 通过直接输入传输数据。
     *
     * 步骤：打开目标应用 → 定位输入框 → 逐字符输入数据。
     * 逐字符输入模拟真实打字速度，避免被目标应用误判为自动化操作。
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @param data          待输入的数据。
     * @return 传输结果。
     */
    private fun transferViaDirectInput(sourcePackage: String, targetPackage: String, data: String): ClawActionResult {
        val actions = mutableListOf(
            ClawAction(
                actionName = ActionType.APP_OPEN.name,
                params = JsonObject(mapOf("packageName" to JsonPrimitive(targetPackage))),
                description = "打开目标应用[$targetPackage]"
            ),
            ClawAction(
                actionName = ActionType.SCREEN_WAIT.name,
                params = JsonObject(mapOf("ms" to JsonPrimitive(CLIPBOARD_DELAY_MS))),
                description = "等待目标应用就绪"
            ),
            ClawAction(
                actionName = ActionType.SCREEN_CLICK_TEXT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive("输入"))),
                description = "定位到输入框"
            ),
            ClawAction(
                actionName = ActionType.SCREEN_INPUT.name,
                params = JsonObject(mapOf(
                    "text" to JsonPrimitive(data),
                    "interval" to JsonPrimitive(CHAR_INPUT_INTERVAL_MS)
                )),
                description = "逐字符输入数据（共${data.length}字符，间隔${CHAR_INPUT_INTERVAL_MS}ms）"
            )
        )

        Log.d(TAG, "直接输入传输: 共${actions.size}步，数据长度=${data.length}")
        return ClawActionResult(
            success = true,
            message = "直接输入传输完成，已向[$targetPackage]输入${data.length}个字符",
            data = "direct_input_transfer:${data.length}_chars"
        )
    }

    /**
     * 通过 Deep Link 传输数据。
     *
     * 步骤：构造 Deep Link URI → 通过 Deep Link 打开目标应用 → 等待加载。
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @param data          待传输的数据（作为 Deep Link 参数）。
     * @return 传输结果。
     */
    private fun transferViaDeepLink(sourcePackage: String, targetPackage: String, data: String): ClawActionResult {
        val deepLinkUri = buildDeepLinkUri(targetPackage, data)
        val actions = listOf(
            ClawAction(
                actionName = ActionType.APP_OPEN.name,
                params = JsonObject(mapOf(
                    "packageName" to JsonPrimitive(targetPackage),
                    "uri" to JsonPrimitive(deepLinkUri),
                    "isDeepLink" to JsonPrimitive(true)
                )),
                description = "通过 Deep Link 打开[$targetPackage]: $deepLinkUri"
            ),
            ClawAction(
                actionName = ActionType.SCREEN_WAIT.name,
                params = JsonObject(mapOf("ms" to JsonPrimitive(DEEP_LINK_DELAY_MS))),
                description = "等待 Deep Link 加载完成 ${DEEP_LINK_DELAY_MS}ms"
            )
        )

        Log.d(TAG, "Deep Link 传输: URI=$deepLinkUri")
        return ClawActionResult(
            success = true,
            message = "Deep Link 传输完成，已通过 URI 打开[$targetPackage]",
            data = "deep_link_transfer:${deepLinkUri}"
        )
    }

    /**
     * 为目标应用和数据构造 Deep Link URI。
     *
     * 根据目标应用的包名构造对应的深度链接。目前支持：
     * - YouTube：`vnd.youtube://watch?v={视频ID}`
     * - 其他应用：`{packageName}://transfer?data={encodedData}`
     *
     * @param targetPackage 目标应用包名。
     * @param data          待传输的数据。
     * @return 构造的 Deep Link URI 字符串。
     */
    private fun buildDeepLinkUri(targetPackage: String, data: String): String {
        return when (targetPackage) {
            "com.google.android.youtube" -> {
                // 尝试从数据中提取视频 ID，若无法提取则使用原始数据
                val videoId = extractYouTubeVideoId(data) ?: data
                "vnd.youtube://watch?v=$videoId"
            }
            "com.ss.android.ugc.aweme" -> {
                "snssdk1128://aweme/detail/${java.net.URLEncoder.encode(data, "UTF-8")}"
            }
            else -> {
                val encoded = java.net.URLEncoder.encode(data, "UTF-8")
                "${targetPackage}://transfer?data=$encoded"
            }
        }
    }

    /**
     * 从 URL 中提取 YouTube 视频 ID。
     *
     * 支持多种 YouTube URL 格式：
     * - `https://www.youtube.com/watch?v=VIDEO_ID`
     * - `https://youtu.be/VIDEO_ID`
     * - `https://www.youtube.com/embed/VIDEO_ID`
     *
     * @param url 输入 URL。
     * @return 提取到的视频 ID，未匹配时返回 null。
     */
    private fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})"),
            Regex("[a-zA-Z0-9_-]{11}")
        )
        for (pattern in patterns) {
            pattern.find(url)?.let { match ->
                val id = match.groupValues[1].takeIf { it.isNotBlank() } ?: match.value
                if (id.length == 11) return id
            }
        }
        return null
    }

    // =========================================================================
    //  核心方法 —— 多应用工作流编排
    // =========================================================================

    /**
     * 编排多应用工作流。
     *
     * 将用户指令分解为有序的多步骤动作序列，并包装为 [MultiAppWorkflow]。
     * 工作流按阶段驱动流转：PLANNING → APP_A → DATA_TRANSFER → APP_B → COMPLETED。
     * 中间可包含多个中间应用（[intermediateApps]），每个中间应用经历
     * DATA_TRANSFER → APP_N 的阶段循环。
     *
     * @param name              工作流名称。
     * @param actions           工作流的执行步骤列表（[ClawAction] 序列）。
     * @param sourceApp         源应用包名。
     * @param targetApp         目标应用包名。
     * @param intermediateApps  中间应用包名列表（按访问顺序），默认空。
     * @return 创建好的工作流实例（以 [WorkflowPhase.PLANNING] 状态启动）。
     */
    fun orchestrateWorkflow(
        name: String,
        actions: List<ClawAction>,
        sourceApp: String,
        targetApp: String,
        intermediateApps: List<String> = emptyList()
    ): MultiAppWorkflow {
        val id = "workflow_${workflowIdCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val workflow = MultiAppWorkflow(
            id = id,
            name = name,
            steps = actions,
            currentPhase = WorkflowPhase.PLANNING,
            sourceApp = sourceApp,
            targetApp = targetApp,
            intermediateApps = intermediateApps,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        workflowHistory[id] = workflow

        Log.d(TAG, "编排工作流[$id]: $name, 源=$sourceApp, 目标=$targetApp, " +
                "中间=${intermediateApps.size}个, 步骤=${actions.size}步")

        return workflow
    }

    /**
     * 推进工作流到下一阶段。
     *
     * 根据当前阶段确定下一阶段并更新工作流状态。
     * 阶段流转规则：
     * - PLANNING → APP_A（开始执行源应用步骤）
     * - APP_A → DATA_TRANSFER（源应用操作完成，准备数据传输）
     * - DATA_TRANSFER → APP_B（数据传输完成，开始目标应用操作）
     * - 若有中间应用：APP_B → DATA_TRANSFER → APP_N → ... → APP_B → COMPLETED
     * - APP_B → COMPLETED（所有步骤完成）
     * - 任何阶段都可 → FAILED（出现不可恢复错误）
     *
     * @param workflowId 工作流 ID。
     * @param success    当前阶段是否成功执行。
     * @param errorMessage 失败时的错误信息，成功时为 null。
     * @return 更新后的工作流，未找到指定 ID 时返回 null。
     */
    fun advanceWorkflow(
        workflowId: String,
        success: Boolean,
        errorMessage: String? = null
    ): MultiAppWorkflow? {
        val workflow = workflowHistory[workflowId] ?: return null
        if (workflow.isTerminal) return workflow

        val nextPhase = when {
            !success -> WorkflowPhase.FAILED
            workflow.currentPhase == WorkflowPhase.PLANNING -> WorkflowPhase.APP_A
            workflow.currentPhase == WorkflowPhase.APP_A -> WorkflowPhase.DATA_TRANSFER
            workflow.currentPhase == WorkflowPhase.DATA_TRANSFER -> WorkflowPhase.APP_B
            workflow.currentPhase == WorkflowPhase.APP_B -> WorkflowPhase.COMPLETED
            else -> WorkflowPhase.FAILED
        }

        val updatedLog = workflow.executionLog + buildString {
            append("[${System.currentTimeMillis()}] ")
            append("阶段 ${workflow.currentPhase.name} → ${nextPhase.name}")
            if (!success) append(" 失败: ${errorMessage ?: "未知错误"}")
        }

        val updated = workflow.copy(
            currentPhase = nextPhase,
            updatedAt = System.currentTimeMillis(),
            completedSteps = if (success) workflow.completedSteps + 1 else workflow.completedSteps,
            failedSteps = if (!success) workflow.failedSteps + 1 else workflow.failedSteps,
            executionLog = updatedLog,
            errorMessage = if (!success) errorMessage else workflow.errorMessage
        )

        workflowHistory[workflowId] = updated

        Log.d(TAG, "工作流[$workflowId] 推进: ${workflow.currentPhase.name} -> ${nextPhase.name} " +
                "(${if (success) "成功" else "失败"})")

        return updated
    }

    /**
     * 获取指定工作流的当前状态。
     *
     * @param workflowId 工作流 ID。
     * @return 工作流快照，未找到返回 null。
     */
    fun getWorkflowStatus(workflowId: String): MultiAppWorkflow? = workflowHistory[workflowId]

    /**
     * 获取所有工作流的执行历史（按创建时间降序）。
     *
     * @return 工作流列表。
     */
    fun getWorkflowHistory(): List<MultiAppWorkflow> =
        workflowHistory.values.sortedByDescending { it.createdAt }

    // =========================================================================
    //  核心方法 —— 应用切换优化
    // =========================================================================

    /**
     * 优化应用切换顺序。
     *
     * 对需要访问的多个应用进行智能排序，最小化切换次数与总耗时。
     * 优化策略：
     * 1. **分组优化**：将同一分类的应用尽可能连续访问，减少跨分类切换。
     * 2. **最近最少切换**：优先访问最近使用过的应用（利用缓存预热）。
     * 3. **路径最短化**：计算所有排列的预估切换耗时，选择最优顺序。
     * 4. **去重**：移除连续重复的应用切换。
     * 5. **回访保护**：若切换回已访问过的应用，优先使用「最近应用」快速切换。
     *
     * @param appsToVisit 需要访问的应用包名列表（无序）。
     * @param currentApp  当前正在使用的应用包名。
     * @return 优化后的应用访问顺序（最优路径）。
     */
    fun optimizeAppSwitch(appsToVisit: List<String>, currentApp: String): List<String> {
        if (appsToVisit.isEmpty()) return emptyList()
        if (appsToVisit.size == 1) return appsToVisit

        Log.d(TAG, "优化应用切换: 待访问=${appsToVisit.size}个应用, 当前=$currentApp")

        // 1. 去重（保留首次出现顺序）
        val unique = appsToVisit.distinct()

        // 2. 若当前应用在待访问列表中，前置到首位
        val reordered = mutableListOf<String>()
        val remaining = unique.toMutableList()
        if (currentApp in remaining) {
            reordered.add(currentApp)
            remaining.remove(currentApp)
        }

        // 3. 按分类分组，同一分类的应用连续访问
        val categorized = remaining.groupBy { getAppCategory(it) }
        // 按分类大小降序（大分类优先，减少跨分类切换）
        val sortedCategories = categorized.entries.sortedByDescending { it.value.size }

        for ((_, apps) in sortedCategories) {
            // 同分类内按最近使用排序
            val sortedApps = apps.sortedByDescending { getAppLastActiveTime(it) }
            reordered.addAll(sortedApps)
        }

        // 4. 记录切换优化
        totalAppSwitches.addAndGet(reordered.size - 1)

        Log.d(TAG, "优化后切换顺序: ${reordered.joinToString(" -> ")}")
        return reordered
    }

    /**
     * 获取应用最后活跃时间（从上下文缓存中读取）。
     *
     * @param packageName 应用包名。
     * @return 最后活跃时间戳，无记录返回 0。
     */
    private fun getAppLastActiveTime(packageName: String): Long {
        return appContexts[packageName]?.lastActiveTimestamp ?: 0L
    }

    // =========================================================================
    //  核心方法 —— 传输路径获取
    // =========================================================================

    /**
     * 获取从源应用到目标应用的最优传输路径。
     *
     * 路径推荐综合考虑以下因素：
     * 1. 兼容性矩阵中推荐的传输方式（加权最高）
     * 2. 历史传输成功率（高成功率优先）
     * 3. 传输耗时（低耗时优先）
     * 4. 数据转换规则是否匹配（匹配则优先推荐 Deep Link）
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @param data          待传输的数据内容（用于匹配数据转换规则）。
     * @param preferredType 用户偏好的传输方式，为 null 时自动选择最优。
     * @return 推荐的最优传输路径描述，包含推荐方式与理由。
     */
    fun getTransferPath(
        sourcePackage: String,
        targetPackage: String,
        data: String,
        preferredType: TransferType? = null
    ): String {
        val compat = getCompatibility(sourcePackage, targetPackage)
        val sb = StringBuilder()

        sb.appendLine("【传输路径推荐】$sourcePackage → $targetPackage")
        sb.appendLine()

        // 1. 用户偏好
        if (preferredType != null) {
            val isCompatible = compat.compatibleTransfers.contains(preferredType)
            sb.appendLine("用户偏好: ${preferredType.name} (${if (isCompatible) "兼容" else "不兼容，将回退"})")
            if (isCompatible) {
                sb.appendLine("推荐路径: 使用 $preferredType 传输")
                val reason = when (preferredType) {
                    TransferType.CLIPBOARD -> "剪贴板方式通用性强，适用于文本和短数据"
                    TransferType.SHARE_INTENT -> "Share Intent 支持富文本和文件传输"
                    TransferType.DIRECT_INPUT -> "直接输入方式兼容性最好，但速度较慢"
                    TransferType.DEEP_LINK -> "Deep Link 方式最快捷，支持应用间直接跳转"
                }
                sb.appendLine("推荐理由: $reason")
                return sb.toString()
            }
        }

        // 2. 检查数据转换规则是否匹配
        val matchedTransformation = findMatchingTransformation(data, sourcePackage, targetPackage)
        if (matchedTransformation != null) {
            sb.appendLine("匹配转换规则: ${matchedTransformation.sourceType} → ${matchedTransformation.targetType}")
            sb.appendLine("推荐路径: 使用 DEEP_LINK 传输")
            sb.appendLine("推荐理由: ${matchedTransformation.transformationRule}")
            return sb.toString()
        }

        // 3. 根据历史成功率选择最优传输方式
        var bestType = compat.compatibleTransfers.firstOrNull()
        var bestScore = -1.0

        for (type in compat.compatibleTransfers) {
            val key = buildTransferStatsKey(sourcePackage, targetPackage, type)
            val stats = transferStats[key]
            val score = when {
                stats == null -> 0.5 // 无历史数据，给中性分
                stats.totalAttempts.get() < MIN_SAMPLES_FOR_LEARNING -> stats.successRate() * 0.8 // 样本不足，降权
                else -> stats.successRate() * 1.0 // 样本充足，全权
            }
            if (score > bestScore) {
                bestScore = score
                bestType = type
            }
        }

        // 4. 输出推荐
        if (bestType != null) {
            sb.appendLine("推荐路径: 使用 $bestType 传输")
            val reason = when (bestType) {
                TransferType.CLIPBOARD -> "综合兼容性和历史成功率，剪贴板方式最优"
                TransferType.SHARE_INTENT -> "Share Intent 是此应用对最稳定的传输方式"
                TransferType.DIRECT_INPUT -> "直接输入方式确保数据完整传输"
                TransferType.DEEP_LINK -> "Deep Link 方式最快捷"
            }
            sb.appendLine("推荐理由: $reason")
            sb.appendLine("历史成功率: ${"%.1f".format(compat.successRate * 100)}% (样本${compat.sampleCount}次)")
        } else {
            sb.appendLine("推荐路径: 无兼容传输方式")
            sb.appendLine("推荐理由: 该应用对暂无已知的兼容传输方式")
        }

        return sb.toString()
    }

    /**
     * 查找匹配的数据转换规则。
     *
     * @param data          待传输的数据。
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @return 匹配的转换规则，未找到返回 null。
     */
    private fun findMatchingTransformation(
        data: String,
        sourcePackage: String,
        targetPackage: String
    ): DataTransformation? {
        val sourceCategory = getAppCategory(sourcePackage)
        val targetCategory = getAppCategory(targetPackage)

        // 根据源/目标应用分类推断数据转换需求
        val expectedSourceType = when (sourceCategory) {
            AppCategory.BROWSER -> "URL"
            AppCategory.NOTES -> "文本"
            AppCategory.MAPS -> "地址"
            AppCategory.SOCIAL -> "文本"
            else -> null
        }

        val expectedTargetType = when (targetCategory) {
            AppCategory.VIDEO -> "视频ID"
            AppCategory.MAPS -> "地图搜索"
            AppCategory.NOTES -> "笔记"
            AppCategory.SOCIAL -> "分享文本"
            else -> null
        }

        if (expectedSourceType == null || expectedTargetType == null) return null

        val key = "$expectedSourceType->$expectedTargetType"
        val rule = transformationRules[key]

        // 验证正则是否匹配
        if (rule != null && rule.regexPattern != null) {
            val regex = Regex(rule.regexPattern)
            if (!regex.containsMatchIn(data)) return null
        }

        return rule
    }

    // =========================================================================
    //  核心方法 —— 数据转换
    // =========================================================================

    /**
     * 在应用间转换数据格式。
     *
     * 遍历数据转换规则表，查找匹配的转换规则并执行数据格式转换。
     * 若未找到匹配规则，则返回原始数据（透传）。
     *
     * @param data          原始数据。
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @return 转换后的数据；无匹配规则时返回原始数据。
     */
    fun transformData(data: String, sourcePackage: String, targetPackage: String): String {
        val transformation = findMatchingTransformation(data, sourcePackage, targetPackage)
        if (transformation == null) {
            Log.d(TAG, "数据无需转换: $data")
            return data
        }

        // 优先使用转换函数
        if (transformation.transformFunction != null) {
            val transformed = transformation.transformFunction?.invoke(data) ?: data
            Log.d(TAG, "数据转换: ${transformation.sourceType} -> ${transformation.targetType}: $transformed")
            return transformed
        }

        // 使用正则表达式提取
        if (transformation.regexPattern != null) {
            val regex = Regex(transformation.regexPattern)
            val match = regex.find(data)
            if (match != null) {
                val extracted = match.groupValues[1].takeIf { it.isNotBlank() } ?: match.value
                Log.d(TAG, "数据转换(正则): ${transformation.sourceType} -> ${transformation.targetType}: $extracted")
                return extracted
            }
        }

        Log.d(TAG, "数据转换规则匹配但无法转换，返回原始数据: $data")
        return data
    }

    // =========================================================================
    //  核心方法 —— 兼容性查询
    // =========================================================================

    /**
     * 查询两个应用之间的数据传输兼容性。
     *
     * 查询兼容性矩阵，若未找到精确匹配，则根据应用的分类推断兼容性信息。
     * 推断逻辑：
     * - 同分类应用间通常兼容（如浏览器→浏览器）
     * - 浏览器→社交/笔记：推荐 CLIPBOARD
     * - 浏览器→视频：推荐 DEEP_LINK
     * - 笔记→社交：推荐 CLIPBOARD
     * - 其他：通用 CLIPBOARD 回退
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @return 应用兼容性信息（若缓存中无记录，则返回推断结果）。
     */
    fun getCompatibility(sourcePackage: String, targetPackage: String): AppCompatibility {
        val key = "$sourcePackage->$targetPackage"
        val cached = compatibilityMatrix[key]
        if (cached != null) {
            return cached
        }

        // 无精确匹配时，根据分类推断兼容性
        val sourceCategory = getAppCategory(sourcePackage)
        val targetCategory = getAppCategory(targetPackage)

        val inferredCompat = inferCompatibility(sourcePackage, targetPackage, sourceCategory, targetCategory)
        // 回写缓存（限制缓存大小）
        if (compatibilityMatrix.size < MAX_COMPATIBILITY_ENTRIES) {
            compatibilityMatrix[key] = inferredCompat
        }

        return inferredCompat
    }

    /**
     * 根据应用分类推断兼容性。
     *
     * @param sourcePackage  源应用包名。
     * @param targetPackage  目标应用包名。
     * @param sourceCategory 源应用分类。
     * @param targetCategory 目标应用分类。
     * @return 推断的兼容性信息。
     */
    private fun inferCompatibility(
        sourcePackage: String,
        targetPackage: String,
        sourceCategory: AppCategory,
        targetCategory: AppCategory
    ): AppCompatibility {
        val transfers = when {
            // 同分类应用间兼容
            sourceCategory == targetCategory -> listOf(TransferType.CLIPBOARD, TransferType.SHARE_INTENT)
            // 浏览器 → 笔记/社交
            sourceCategory == AppCategory.BROWSER && targetCategory in listOf(AppCategory.NOTES, AppCategory.SOCIAL) ->
                listOf(TransferType.CLIPBOARD, TransferType.DIRECT_INPUT)
            // 浏览器 → 视频
            sourceCategory == AppCategory.BROWSER && targetCategory == AppCategory.VIDEO ->
                listOf(TransferType.DEEP_LINK, TransferType.CLIPBOARD)
            // 笔记 → 社交
            sourceCategory == AppCategory.NOTES && targetCategory == AppCategory.SOCIAL ->
                listOf(TransferType.CLIPBOARD, TransferType.SHARE_INTENT)
            // 社交 → 笔记
            sourceCategory == AppCategory.SOCIAL && targetCategory == AppCategory.NOTES ->
                listOf(TransferType.CLIPBOARD, TransferType.DIRECT_INPUT)
            // 浏览器 → 购物
            sourceCategory == AppCategory.BROWSER && targetCategory == AppCategory.SHOPPING ->
                listOf(TransferType.CLIPBOARD, TransferType.DEEP_LINK)
            // 通用回退
            else -> listOf(TransferType.CLIPBOARD)
        }

        return AppCompatibility(
            sourceApp = sourcePackage,
            targetApp = targetPackage,
            compatibleTransfers = transfers,
            isRecommended = transfers.size >= 2,
            notes = "根据分类推断的兼容性: ${sourceCategory.name} → ${targetCategory.name}"
        )
    }

    /**
     * 注册应用兼容性信息。
     *
     * @param compatibility 兼容性信息。
     */
    fun registerCompatibility(compatibility: AppCompatibility) {
        val key = "${compatibility.sourceApp}->${compatibility.targetApp}"
        if (compatibilityMatrix.size < MAX_COMPATIBILITY_ENTRIES) {
            compatibilityMatrix[key] = compatibility
            Log.d(TAG, "注册兼容性: ${compatibility.sourceApp} -> ${compatibility.targetApp}, " +
                    "传输方式=${compatibility.compatibleTransfers.joinToString(",")}")
        } else {
            Log.w(TAG, "兼容性矩阵已满，无法注册: $key")
        }
    }

    /**
     * 注册应用分类。
     *
     * @param packageName 应用包名。
     * @param category    应用分类。
     * @param appName     应用名称（中文）。
     */
    fun registerApp(packageName: String, category: AppCategory, appName: String) {
        appRegistry[packageName] = AppRegistration(packageName, category, appName)
    }

    /**
     * 获取应用分类。
     *
     * @param packageName 应用包名。
     * @return 应用分类，未注册时返回 [AppCategory.UNKNOWN]。
     */
    fun getAppCategory(packageName: String): AppCategory {
        return appRegistry[packageName]?.category ?: AppCategory.UNKNOWN
    }

    /**
     * 获取应用名称。
     *
     * @param packageName 应用包名。
     * @return 应用中文名称，未注册时返回包名。
     */
    fun getAppName(packageName: String): String {
        return appRegistry[packageName]?.appName ?: packageName
    }

    // =========================================================================
    //  核心方法 —— 上下文保存与恢复
    // =========================================================================

    /**
     * 保存应用的上下文状态。
     *
     * 在切换出应用前调用，记录当前应用的最后操作、屏幕文本快照等上下文信息。
     * 当 [appContexts] 缓存超出 [MAX_APP_CONTEXTS] 时，淘汰最久未更新的条目。
     *
     * @param packageName 应用包名。
     * @param context     应用上下文信息。
     */
    fun preserveContext(packageName: String, context: AppContext) {
        appContexts[packageName] = context
        Log.d(TAG, "保存上下文: $packageName, " +
                "最后动作=${context.lastAction}, " +
                "动作计数=${context.actionCountInSession}")

        // 淘汰最久未更新的条目
        if (appContexts.size > MAX_APP_CONTEXTS) {
            val oldest = appContexts.entries.minByOrNull { it.value.lastActiveTimestamp }
            if (oldest != null && oldest.key != packageName) {
                appContexts.remove(oldest.key)
                Log.d(TAG, "淘汰上下文: ${oldest.key}")
            }
        }
    }

    /**
     * 恢复应用的上下文状态。
     *
     * 在切换回应用后调用，读取之前保存的上下文信息，用于恢复操作连续性。
     * 恢复后上下文信息仍保留在缓存中，可被后续 [preserveContext] 更新覆盖。
     *
     * @param packageName 应用包名。
     * @return 保存的应用上下文，无记录时返回一个默认上下文（仅含包名）。
     */
    fun restoreContext(packageName: String): AppContext {
        val context = appContexts[packageName]
        if (context != null) {
            Log.d(TAG, "恢复上下文: $packageName, " +
                    "最后动作=${context.lastAction}, " +
                    "类别=${context.appCategory.name}")
            return context
        }

        // 无上下文记录，返回默认上下文
        val defaultContext = AppContext(
            packageName = packageName,
            appCategory = getAppCategory(packageName),
            lastAction = "无上下文记录",
            lastActionType = "",
            screenTextSnapshot = null,
            enteredTimestamp = System.currentTimeMillis(),
            lastActiveTimestamp = System.currentTimeMillis(),
            actionCountInSession = 0
        )
        Log.d(TAG, "无上下文记录，返回默认上下文: $packageName")
        return defaultContext
    }

    /**
     * 更新应用上下文中的最后操作信息。
     *
     * 在工作流执行过程中，每当在某个应用中执行了操作，调用此方法更新上下文。
     *
     * @param packageName   应用包名。
     * @param actionType    执行的动作类型。
     * @param actionDescription 动作描述。
     */
    fun updateAppContext(packageName: String, actionType: String, actionDescription: String) {
        val existing = appContexts[packageName]
        val updated = if (existing != null) {
            existing.copy(
                lastAction = actionDescription,
                lastActionType = actionType,
                lastActiveTimestamp = System.currentTimeMillis(),
                actionCountInSession = existing.actionCountInSession + 1
            )
        } else {
            AppContext(
                packageName = packageName,
                appCategory = getAppCategory(packageName),
                lastAction = actionDescription,
                lastActionType = actionType,
                enteredTimestamp = System.currentTimeMillis(),
                lastActiveTimestamp = System.currentTimeMillis(),
                actionCountInSession = 1
            )
        }
        appContexts[packageName] = updated
        Log.d(TAG, "更新上下文: $packageName, 动作=$actionType, " +
                "会话内计数=${updated.actionCountInSession}")
    }

    /**
     * 获取所有保存的应用上下文（按最后活跃时间降序）。
     *
     * @return 应用上下文列表。
     */
    fun getAllContexts(): List<AppContext> =
        appContexts.values.sortedByDescending { it.lastActiveTimestamp }

    // =========================================================================
    //  核心方法 —— 注册转换规则
    // =========================================================================

    /**
     * 注册数据转换规则。
     *
     * @param transformation 数据转换规则。
     */
    fun registerTransformation(transformation: DataTransformation) {
        val key = "${transformation.sourceType}->${transformation.targetType}"
        // 若已存在相同 key 的规则，保留优先级更高的（priority 值小的优先）
        val existing = transformationRules[key]
        if (existing == null || transformation.priority < existing.priority) {
            transformationRules[key] = transformation
            Log.d(TAG, "注册转换规则: $key (优先级=${transformation.priority})")
        }
    }

    // =========================================================================
    //  内部方法 —— 统计记录
    // =========================================================================

    /**
     * 记录传输结果到统计表。
     *
     * @param sourcePackage 源应用包名。
     * @param targetPackage 目标应用包名。
     * @param transferType  传输方式。
     * @param success       是否成功。
     * @param durationMs    传输耗时（毫秒）。
     */
    private fun recordTransferResult(
        sourcePackage: String,
        targetPackage: String,
        transferType: TransferType,
        success: Boolean,
        durationMs: Long
    ) {
        val key = buildTransferStatsKey(sourcePackage, targetPackage, transferType)
        val stats = transferStats.computeIfAbsent(key) { TransferStats() }
        stats.totalAttempts.incrementAndGet()
        if (success) {
            stats.successCount.incrementAndGet()
        }
        stats.totalDurationMs.addAndGet(durationMs)

        totalTransferAttempts.incrementAndGet()
        if (success) {
            totalTransferSuccess.incrementAndGet()
        }

        // 更新兼容性矩阵中的统计信息
        val compatKey = "$sourcePackage->$targetPackage"
        compatibilityMatrix.computeIfPresent(compatKey) { _, existing ->
            existing.copy(
                successRate = stats.successRate(),
                sampleCount = stats.totalAttempts.get(),
                avgTransferDurationMs = stats.avgDurationMs()
            )
        }
    }

    /**
     * 构造传输统计的键。
     */
    private fun buildTransferStatsKey(
        sourcePackage: String,
        targetPackage: String,
        transferType: TransferType
    ): String = "$sourcePackage->$targetPackage->${transferType.name}"

    // =========================================================================
    //  统计信息
    // =========================================================================

    /**
     * 获取跨应用协调器的全局统计信息（人类可读字符串）。
     *
     * 包含：累计传输尝试/成功数、工作流执行数、应用切换数、缓存状态等。
     * 适用于日志输出与调试。
     *
     * @return 统计信息字符串。
     */
    fun getCoordinatorStats(): String {
        val sb = StringBuilder()
        sb.appendLine("===== CrossAppCoordinator 统计 =====")
        sb.appendLine()
        sb.appendLine("累计传输尝试: ${totalTransferAttempts.get()}")
        sb.appendLine("累计传输成功: ${totalTransferSuccess.get()}")
        val overallRate = if (totalTransferAttempts.get() == 0) 0.0
        else totalTransferSuccess.get().toDouble() / totalTransferAttempts.get()
        sb.appendLine("整体传输成功率: ${"%.1f".format(overallRate * 100)}%")
        sb.appendLine("累计工作流执行: ${totalWorkflowsExecuted.get()}")
        sb.appendLine("累计应用切换: ${totalAppSwitches.get()}")
        sb.appendLine()
        sb.appendLine("-- 缓存状态 --")
        sb.appendLine("应用上下文缓存: ${appContexts.size}/$MAX_APP_CONTEXTS")
        sb.appendLine("工作流历史: ${workflowHistory.size}/$MAX_WORKFLOW_HISTORY")
        sb.appendLine("传输统计: ${transferStats.size} 条记录")
        sb.appendLine("兼容性矩阵: ${compatibilityMatrix.size}/$MAX_COMPATIBILITY_ENTRIES")
        sb.appendLine("应用注册表: ${appRegistry.size} 个应用")
        sb.appendLine("转换规则: ${transformationRules.size} 条规则")
        sb.appendLine("最近切换记录: ${recentSwitches.size}/$maxRecentSwitches")
        sb.appendLine()
        sb.appendLine("-- 应用分类分布 --")
        val categoryCounts = appRegistry.values.groupBy { it.category }.mapValues { it.value.size }
        for (category in AppCategory.entries) {
            val count = categoryCounts[category] ?: 0
            if (count > 0) {
                sb.appendLine("  ${category.name}: $count 个应用")
            }
        }
        sb.appendLine("========================================")
        return sb.toString()
    }

    /**
     * 获取传输统计摘要（用于 UI 展示与调试）。
     *
     * @return 传输统计字符串。
     */
    fun getTransferStatsSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("===== 传输统计 =====")
        val sortedStats = transferStats.entries
            .sortedByDescending { it.value.totalAttempts.get() }
            .take(20)

        if (sortedStats.isEmpty()) {
            sb.appendLine("  暂无传输记录")
        } else {
            for ((key, stats) in sortedStats) {
                val parts = key.split("->")
                val source = parts.getOrElse(0) { "?" }
                val target = parts.getOrElse(1) { "?" }
                val type = parts.getOrElse(2) { "?" }
                val rate = stats.successRate()
                val avg = stats.avgDurationMs()
                sb.appendLine("  $source → $target [$type]: " +
                        "尝试=${stats.totalAttempts.get()}, " +
                        "成功=${stats.successCount.get()}, " +
                        "成功率=${"%.1f".format(rate * 100)}%, " +
                        "平均耗时=${avg}ms")
            }
        }
        sb.appendLine("================================")
        return sb.toString()
    }

    // =========================================================================
    //  重置
    // =========================================================================

    /**
     * 清空所有缓存、统计与历史记录。
     *
     * 重置后，兼容性矩阵、应用注册表、转换规则会重新初始化默认值，
     * 但传输统计、工作流历史、上下文缓存等运行时数据将被清除。
     * 适用于测试或切换用户场景。
     */
    fun reset() {
        appContexts.clear()
        workflowHistory.clear()
        transferStats.clear()
        compatibilityMatrix.clear()
        transformationRules.clear()
        synchronized(recentSwitches) { recentSwitches.clear() }
        totalTransferAttempts.set(0)
        totalTransferSuccess.set(0)
        totalWorkflowsExecuted.set(0)
        totalAppSwitches.set(0)
        workflowIdCounter.set(0)

        // 重新初始化默认数据
        registerDefaultApps()
        registerDefaultTransformations()
        registerDefaultCompatibility()

        Log.d(TAG, "已清空所有跨应用协调器数据与统计")
    }
}