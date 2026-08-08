package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 任务分解策略。
 *
 * 定义将一条复杂用户指令拆分为子任务时所采用的拓扑结构。不同策略决定了子任务
 * 之间的执行顺序与依赖关系。
 *
 * - [SEQUENTIAL] 顺序分解：拆分为按序执行的步骤（打开应用 -> 导航 -> 操作），
 *   子任务之间存在严格的先后依赖。
 * - [CONDITIONAL] 条件分解：拆分为带条件的分支（若 X 则 Y 否则 Z），由条件检测
 *   子任务决定后续执行哪条分支。
 * - [PARALLEL] 并行分解：识别彼此独立、可并行执行的子任务，子任务之间无依赖。
 * - [HYBRID] 混合分解：组合顺序、条件与并行多种策略，先并行执行独立操作，
 *   再顺序/条件执行存在依赖的操作。
 *
 * @property description 策略的人类可读描述。
 */
enum class DecompositionStrategy(val description: String) {
    SEQUENTIAL("顺序分解：拆分为按序执行的步骤（打开应用 -> 导航 -> 操作）"),
    CONDITIONAL("条件分解：拆分为带条件的分支（若 X 则 Y 否则 Z）"),
    PARALLEL("并行分解：识别可并行执行的独立子任务"),
    HYBRID("混合分解：组合顺序、条件与并行多种策略")
}

/**
 * 子任务执行阶段。
 *
 * 描述单个子任务在其生命周期中所处的状态，用于进度追踪、检查点判定与失败处理。
 *
 * - [PLANNING] 规划中：已生成但尚未开始执行。
 * - [EXECUTING] 执行中：正在执行该子任务的动作序列。
 * - [CHECKPOINT] 检查点：已到达可回滚的安全点（前序已落盘，可恢复）。
 * - [COMPLETED] 已完成：子任务执行成功。
 * - [FAILED] 失败：子任务执行失败，可能触发重新分解或回滚。
 *
 * @property description 阶段的人类可读描述。
 */
enum class TaskPhase(val description: String) {
    PLANNING("规划中：尚未开始执行"),
    EXECUTING("执行中：正在执行该子任务"),
    CHECKPOINT("检查点：已到达可回滚的安全点"),
    COMPLETED("已完成：子任务执行成功"),
    FAILED("失败：子任务执行失败")
}

/**
 * 任务复杂度等级。
 *
 * 由 [TaskDecomposer.assessComplexity] 评估得出，决定是否需要分解以及分解粒度。
 *
 * @property level 等级数值（0=微操作, 1=简单, 2=中等, 3=复杂）。
 * @property description 等级的人类可读描述。
 */
enum class ComplexityLevel(val level: Int, val description: String) {
    TRIVIAL(0, "微操作：极简单步，无需分解"),
    SIMPLE(1, "简单：单步操作，无需分解"),
    MODERATE(2, "中等：2-5 步，建议顺序分解"),
    COMPLEX(3, "复杂：6+ 步或多分支，必须分解")
}

/**
 * 子任务依赖类型。
 *
 * 描述两个子任务之间依赖关系的语义，用于执行调度器决定何时启动后继子任务。
 *
 * - [SEQUENTIAL] 顺序依赖：后继必须在前驱完成后才能执行。
 * - [CONDITIONAL] 条件依赖：后继是否执行取决于前驱（条件检测）的判断结果。
 * - [PARALLEL_SYNC] 并行同步：多个并行子任务需全部完成后才能推进到汇合点。
 * - [ROLLBACK] 回滚依赖：前驱失败时触发后继回滚动作。
 *
 * @property description 依赖类型的人类可读描述。
 */
enum class DependencyType(val description: String) {
    SEQUENTIAL("顺序依赖：后继必须在前驱完成后执行"),
    CONDITIONAL("条件依赖：后继是否执行取决于前驱的判断结果"),
    PARALLEL_SYNC("并行同步：多个并行子任务需同步汇合"),
    ROLLBACK("回滚依赖：前驱失败时触发后继回滚")
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 子任务 —— 分解后的最小可执行单元。
 *
 * 一个子任务封装了一组逻辑相关的 [ClawAction]，具备独立的描述、执行阶段、
 * 依赖关系与进度权重。执行引擎按依赖拓扑逐个（或并行）执行子任务。
 *
 * @property id 唯一标识。
 * @property description 子任务的人类可读描述。
 * @property actions 该子任务包含的动作列表（按执行顺序排列）。
 * @property strategy 该子任务所属的分解策略。
 * @property phase 当前执行阶段。
 * @property dependsOn 依赖的子任务 id 列表（这些子任务必须先完成）。
 * @property condition 执行条件（CONDITIONAL 策略下使用，如「屏幕包含登录」）。
 * @property branchLabel 分支标签（条件分支标识，如 "true"/"false"）。
 * @property estimatedSteps 预估步数（通常等于动作数，用于进度估算）。
 * @property progressWeight 进度权重（同辈子任务权重之和归一化后用于进度百分比）。
 * @property canRunInParallel 是否可与其他子任务并行执行。
 * @property isCritical 是否为关键子任务（失败则整体任务失败）。
 * @property createdAt 创建时间戳（毫秒）。
 */
data class SubTask(
    val id: String,
    val description: String,
    val actions: List<ClawAction> = emptyList(),
    val strategy: DecompositionStrategy = DecompositionStrategy.SEQUENTIAL,
    val phase: TaskPhase = TaskPhase.PLANNING,
    val dependsOn: List<String> = emptyList(),
    val condition: String? = null,
    val branchLabel: String? = null,
    val estimatedSteps: Int = 1,
    val progressWeight: Float = 1f,
    val canRunInParallel: Boolean = false,
    val isCritical: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 是否已完成。 */
    val isCompleted: Boolean get() = phase == TaskPhase.COMPLETED

    /** 是否失败。 */
    val isFailed: Boolean get() = phase == TaskPhase.FAILED

    /** 是否处于终态（完成或失败）。 */
    val isTerminal: Boolean get() = phase == TaskPhase.COMPLETED || phase == TaskPhase.FAILED
}

/**
 * 子任务依赖关系。
 *
 * 表示「[fromTaskId] 完成后（或满足条件后）才可执行 [toTaskId]」的有向依赖。
 *
 * @property fromTaskId 前驱子任务 id（被依赖项）。
 * @property toTaskId 后继子任务 id（依赖项）。
 * @property type 依赖类型。
 * @property description 依赖关系的人类可读说明。
 */
data class TaskDependency(
    val fromTaskId: String,
    val toTaskId: String,
    val type: DependencyType = DependencyType.SEQUENTIAL,
    val description: String = ""
)

/**
 * 检查点 —— 用于子任务失败时回滚的安全快照。
 *
 * 在关键子任务执行前创建检查点，记录其回滚动作序列。当后续子任务失败时，
 * 可执行检查点的 [rollbackActions] 将设备状态恢复到该检查点时刻，从而避免
 * 残留的中间状态（如停留在错误页面、打开了不该打开的应用等）。
 *
 * @property id 唯一标识。
 * @property taskId 关联的子任务 id（该子任务执行前的检查点）。
 * @property description 检查点的人类可读描述。
 * @property rollbackActions 回滚动作列表（失败时执行这些动作以恢复状态）。
 * @property snapshotSummary 快照摘要（记录检查点时刻的关键上下文）。
 * @property createdAt 创建时间戳（毫秒）。
 */
data class Checkpoint(
    val id: String,
    val taskId: String,
    val description: String,
    val rollbackActions: List<ClawAction> = emptyList(),
    val snapshotSummary: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 复杂度评估结果。
 *
 * 由 [TaskDecomposer.assessComplexity] 产出，综合指令文本与 AI 返回的动作序列
 * 给出复杂度等级、评分、预估步数、是否需要分解以及推荐的分解策略。
 *
 * @property level 复杂度等级。
 * @property score 复杂度评分（0.0-1.0，越高越复杂）。
 * @property estimatedStepCount 预估总步数。
 * @property needsDecomposition 是否需要分解。
 * @property recommendedStrategy 推荐的分解策略。
 * @property detectedSignals 检测到的复杂度信号（用于日志与可解释性）。
 */
data class ComplexityAssessment(
    val level: ComplexityLevel,
    val score: Float,
    val estimatedStepCount: Int,
    val needsDecomposition: Boolean,
    val recommendedStrategy: DecompositionStrategy,
    val detectedSignals: List<String> = emptyList()
)

/**
 * 进度估算结果。
 *
 * 由 [TaskDecomposer.estimateProgress] 产出，基于已完成子任务的权重与当前执行
 * 子任务的内部进度，给出整体进度百分比与剩余步数预估。
 *
 * @property overallProgress 整体进度百分比（0-100）。
 * @property completedWeight 已完成子任务的权重和。
 * @property totalWeight 全部子任务的权重和。
 * @property completedCount 已完成子任务数。
 * @property totalCount 子任务总数。
 * @property currentTaskId 正在执行的子任务 id（无则为 null）。
 * @property estimatedRemainingSteps 预估剩余步数。
 */
data class ProgressEstimate(
    val overallProgress: Float,
    val completedWeight: Float,
    val totalWeight: Float,
    val completedCount: Int,
    val totalCount: Int,
    val currentTaskId: String?,
    val estimatedRemainingSteps: Int
)

/**
 * 分解计划 —— 一次分解的完整产物。
 *
 * 包含原始指令、采用的策略、子任务列表、依赖关系、检查点与复杂度评估。
 * 计划创建后会被登记到 [TaskDecomposer] 的活跃计划表中，支持后续阶段更新与
 * 动态重新分解。
 *
 * @property id 计划唯一标识。
 * @property rootCommand 原始用户指令。
 * @property strategy 采用的分解策略。
 * @property subTasks 子任务列表（按建议执行顺序排列）。
 * @property dependencies 子任务依赖关系列表。
 * @property checkpoints 检查点列表。
 * @property assessment 复杂度评估结果。
 * @property createdAt 创建时间戳（毫秒）。
 * @property updatedAt 最后更新时间戳（毫秒）。
 */
data class DecompositionPlan(
    val id: String,
    val rootCommand: String,
    val strategy: DecompositionStrategy,
    val subTasks: List<SubTask>,
    val dependencies: List<TaskDependency>,
    val checkpoints: List<Checkpoint>,
    val assessment: ComplexityAssessment,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    /** 子任务总数。 */
    val taskCount: Int get() = subTasks.size

    /** 已完成子任务数。 */
    val completedCount: Int get() = subTasks.count { it.isCompleted }

    /** 失败子任务数。 */
    val failedCount: Int get() = subTasks.count { it.isFailed }

    /** 是否所有子任务均已进入终态。 */
    val isFinished: Boolean get() = subTasks.isNotEmpty() && subTasks.all { it.isTerminal }
}

// =============================================================================
//  TaskDecomposer —— 任务分解器
// =============================================================================

/**
 * TaskDecomposer —— 任务分解器
 *
 * 将复杂的用户指令自动拆分为可管理、可追踪、可回滚的子任务。它是 MobileClaw
 * 执行引擎的「规划层」：在 AI 给出原始动作序列之后、执行器真正执行之前，
 * 对动作序列进行结构化分解，使长链路任务具备进度可见、失败可回滚、上下文
 * 变化可重规划的能力。
 *
 * ### 六大核心能力
 * 1. **复杂度评估**（[assessComplexity]）：综合指令文本（委托 [TaskComplexityAnalyzer]
 *    进行基础分级）与 AI 返回的动作序列，评估任务复杂度等级、评分、预估步数，
 *    并判定是否需要分解、推荐何种分解策略。
 * 2. **分解策略**（[decompose]）：
 *    - [DecompositionStrategy.SEQUENTIAL] 顺序分解：在 APP_OPEN、APP_CLOSE、
 *      SCREEN_INPUT、SCREEN_TEXT_EXISTS、ANSWER 等自然边界处切分，并将
 *      「输入 + 提交」聚合为一个子任务。
 *    - [DecompositionStrategy.CONDITIONAL] 条件分解：以 SCREEN_TEXT_EXISTS
 *      动作或「如果/否则」语义作为分支点，生成条件检测子任务与 true/false 分支。
 *    - [DecompositionStrategy.PARALLEL] 并行分解：识别截图、获取系统信息、
 *      清理缓存等不依赖应用状态的独立操作，标记为可并行。
 *    - [DecompositionStrategy.HYBRID] 混合分解：先并行执行独立操作，再以
 *      PARALLEL_SYNC 汇合后顺序/条件执行存在依赖的操作。
 * 3. **依赖追踪**（[getDependencies]）：为子任务构建有向依赖图，支持顺序、
 *    条件、并行同步、回滚四种依赖语义。
 * 4. **检查点管理**（[createCheckpoints]）：在关键子任务（切换应用、破坏性
 *    操作）前创建检查点，记录回滚动作序列，供失败时恢复设备状态。
 * 5. **进度估算**（[estimateProgress]）：基于子任务权重与完成情况，给出
 *    整体进度百分比与剩余步数预估。
 * 6. **动态重新分解**（[reDecompose]）：当某子任务失败或上下文变化时，
 *    将失败子任务拆分为更小的重试子任务，或为其生成替代动作，并重建依赖图
 *    与检查点。
 *
 * ### 线程安全
 * - 活跃计划表使用 [ConcurrentHashMap]，可被多线程并发查询与更新。
 * - 全局计数与 id 生成使用 [AtomicInteger]，保证原子自增。
 * - 计划的阶段更新（[updateTaskPhase]）与重新分解（[reDecompose]）通过
 *   `ConcurrentHashMap.compute` 以键级锁保证原子性。
 * - 典型场景：UI 线程查询计划与进度，后台执行线程更新子任务阶段并触发重分解。
 *
 * ### 典型调用流程
 * ```
 * val decomposer = TaskDecomposer()
 * // 1. 评估复杂度
 * val assessment = decomposer.assessComplexity("用微信给张三发消息你好", actions)
 * // 2. 分解任务
 * var plan = decomposer.decompose("用微信给张三发消息你好", actions)
 * // 3. 创建检查点（decompose 内部已自动创建，亦可单独调用）
 * val checkpoints = decomposer.createCheckpoints(plan)
 * // 4. 执行过程中更新阶段
 * decomposer.updateTaskPhase(plan.id, plan.subTasks[0].id, TaskPhase.COMPLETED)
 * // 5. 估算进度
 * val progress = decomposer.estimateProgress(plan)
 * // 6. 某子任务失败后重新分解
 * plan = decomposer.reDecompose(plan, failedTaskId = "task-3", context = "元素未找到")
 * ```
 */
class TaskDecomposer {

    // =========================================================================
    //  常量与模式定义
    // =========================================================================

    private companion object {
        const val TAG = "TaskDecomposer"

        // —— 复杂度判定步数阈值 ——
        /** 中等任务最大步数（超过即需分解）。 */
        const val MODERATE_MAX_STEPS = 5

        // —— 重新分解参数 ——
        /** 失败子任务拆分时，回滚动作的等待延长倍数。 */
        const val WAIT_RETRY_GROWTH = 1.5f

        // —— 关键词集合 ——

        /** 标志「提交」语义的文本关键词，用于将「输入 + 提交」聚合为一个子任务。 */
        val SUBMIT_KEYWORDS = setOf(
            "发送", "确定", "搜索", "登录", "提交", "确认", "完成", "下一步",
            "save", "ok", "confirm", "send", "search", "login", "next"
        )

        /** 不依赖应用状态、可并行执行的独立动作类型集合。 */
        val INDEPENDENT_ACTION_TYPES = setOf(
            ActionType.SCREEN_SCREENSHOT,
            ActionType.SYSTEM_GET_INFO,
            ActionType.SYSTEM_CLEAR_CACHE,
            ActionType.SYSTEM_SET_VOLUME,
            ActionType.SYSTEM_SET_BRIGHTNESS,
            ActionType.MEDIA_CONTROL,
            ActionType.CLIPBOARD_COPY,
            ActionType.NOTIFY_READ
        )

        /** 常见应用名称，用于检测指令是否涉及多应用切换。 */
        val KNOWN_APPS = listOf(
            "微信", "抖音", "QQ", "支付宝", "淘宝", "知乎", "微博", "钉钉",
            "B站", "小红书", "京东", "拼多多", "快手", "美团", "网易云音乐",
            "QQ音乐", "高德", "百度", "今日头条", "腾讯视频", "爱奇艺",
            "百度网盘", "天猫", "饿了么", "飞书", "设置"
        )

        // —— 复杂度信号正则模式 ——

        /** 条件分支语义模式（如果/否则/存在…就）。 */
        val CONDITIONAL_PATTERNS = listOf(
            Regex("(?i)(如果|要是|假如|倘若|若|万一|的话)"),
            Regex("(?i)(否则|不然|要不|不然的话)"),
            Regex("(?i)(存在|包含|出现).{0,8}(就|则|才)")
        )

        /** 并行执行语义模式（同时/并且/一边…一边/分别）。 */
        val PARALLEL_PATTERNS = listOf(
            Regex("(?i)(同时|并且|并行|与此同时|一边.{1,8}一边|既.{1,8}又|分别)"),
            Regex("(?i)(另外|此外).{0,6}(截图|清理|获取|查看|关闭|打开)")
        )

        /** 动作动词集合，用于统计指令的多步程度。 */
        val ACTION_VERBS = listOf(
            "打开", "关闭", "点击", "输入", "搜索", "发送", "滑动", "滚动",
            "截图", "等待", "返回", "复制", "粘贴", "删除", "安装", "卸载",
            "下载", "播放", "保存", "分享", "转发", "设置", "修改", "查看",
            "清理", "添加", "创建"
        )
    }

    // =========================================================================
    //  状态字段（全部线程安全）
    // =========================================================================

    /** 活跃分解计划表（planId -> DecompositionPlan）。 */
    private val activePlans = ConcurrentHashMap<String, DecompositionPlan>()

    /** 子任务 id 自增计数器。 */
    private val taskCounter = AtomicInteger(0)

    /** 检查点 id 自增计数器。 */
    private val checkpointCounter = AtomicInteger(0)

    /** 计划 id 自增计数器。 */
    private val planCounter = AtomicInteger(0)

    /** 累计分解次数。 */
    private val _totalDecompositions = AtomicInteger(0)

    /** 累计重新分解次数。 */
    private val _totalReDecompositions = AtomicInteger(0)

    /** 累计创建检查点数。 */
    private val _totalCheckpointsCreated = AtomicInteger(0)

    /** 累计分解次数。 */
    val totalDecompositions: Int get() = _totalDecompositions.get()

    /** 累计重新分解次数。 */
    val totalReDecompositions: Int get() = _totalReDecompositions.get()

    /** 累计创建检查点数。 */
    val totalCheckpointsCreated: Int get() = _totalCheckpointsCreated.get()

    // =========================================================================
    //  复杂度评估
    // =========================================================================

    /**
     * 评估用户指令的复杂度，并判定是否需要分解及推荐策略。
     *
     * 评估流程：
     * 1. 委托 [TaskComplexityAnalyzer.analyze] 获得基础复杂度等级（MICRO/SIMPLE/
     *    MEDIUM/COMPLEX），映射为 [ComplexityLevel]。
     * 2. 检测条件分支语义（[CONDITIONAL_PATTERNS]）与并行语义（[PARALLEL_PATTERNS]）。
     * 3. 统计动作动词数与涉及的应用数，判断多步程度与多应用切换。
     * 4. 结合 AI 返回的动作序列，统计动作数、条件检测动作与可并行操作数。
     * 5. 综合以上信号计算复杂度评分（0.0-1.0），并给出推荐分解策略。
     *
     * @param command 用户自然语言指令。
     * @param actions AI 返回的动作序列（可选；为空时仅基于指令文本评估）。
     * @return 复杂度评估结果。
     */
    fun assessComplexity(
        command: String,
        actions: List<ClawAction> = emptyList()
    ): ComplexityAssessment {
        val signals = mutableListOf<String>()

        // 1. 基础复杂度（委托 TaskComplexityAnalyzer）
        val base = TaskComplexityAnalyzer.analyze(command)
        val level = when (base) {
            TaskComplexityAnalyzer.Complexity.MICRO -> ComplexityLevel.TRIVIAL
            TaskComplexityAnalyzer.Complexity.SIMPLE -> ComplexityLevel.SIMPLE
            TaskComplexityAnalyzer.Complexity.MEDIUM -> ComplexityLevel.MODERATE
            TaskComplexityAnalyzer.Complexity.COMPLEX -> ComplexityLevel.COMPLEX
            TaskComplexityAnalyzer.Complexity.UNLIMITED -> ComplexityLevel.COMPLEX
        }
        signals.add("基础分析：${base.name}")

        // 2. 条件 / 并行语义检测
        val hasConditional = CONDITIONAL_PATTERNS.any { it.containsMatchIn(command) }
        if (hasConditional) signals.add("检测到条件分支语义")

        val hasParallel = PARALLEL_PATTERNS.any { it.containsMatchIn(command) }
        if (hasParallel) signals.add("检测到并行执行语义")

        // 3. 动作动词数与多应用检测
        val verbCount = countActionVerbs(command)
        if (verbCount >= 3) signals.add("动作动词数=$verbCount（多步）")

        val appMentions = countAppMentions(command)
        if (appMentions >= 2) signals.add("涉及多应用切换($appMentions)")

        // 4. 动作序列信号
        val actionCount = actions.size
        if (actionCount > 0) signals.add("AI 返回动作数=$actionCount")

        val hasTextExists = actions.any { it.type == ActionType.SCREEN_TEXT_EXISTS }
        if (hasTextExists) signals.add("包含条件检测动作(TEXT_EXISTS)")

        val independentCount = actions.count { it.type in INDEPENDENT_ACTION_TYPES }
        if (independentCount >= 2) signals.add("包含 $independentCount 个可并行操作")

        // 5. 预估步数
        val estimatedSteps = when {
            actionCount > 0 -> actionCount
            level == ComplexityLevel.TRIVIAL -> 1
            level == ComplexityLevel.SIMPLE -> 1
            level == ComplexityLevel.MODERATE -> 3
            else -> 8
        }

        // 6. 复杂度评分
        val score = computeComplexityScore(
            level, verbCount, appMentions, actionCount, hasConditional, hasParallel
        )

        // 7. 是否需要分解
        val needsDecomposition = level.level >= ComplexityLevel.MODERATE.level ||
            actionCount > MODERATE_MAX_STEPS ||
            hasConditional ||
            independentCount >= 2

        // 8. 推荐策略
        val strategy = when {
            hasConditional && hasParallel -> DecompositionStrategy.HYBRID
            hasConditional -> DecompositionStrategy.CONDITIONAL
            hasParallel || independentCount >= 2 -> DecompositionStrategy.PARALLEL
            else -> DecompositionStrategy.SEQUENTIAL
        }

        return ComplexityAssessment(
            level = level,
            score = score,
            estimatedStepCount = estimatedSteps,
            needsDecomposition = needsDecomposition,
            recommendedStrategy = strategy,
            detectedSignals = signals
        )
    }

    /**
     * 综合各信号计算复杂度评分（0.0-1.0）。
     *
     * 评分由基础等级分、动词数加分、多应用加分、动作数加分、条件/并行加分叠加，
     * 并截断到 [0, 1] 区间。
     */
    private fun computeComplexityScore(
        level: ComplexityLevel,
        verbCount: Int,
        appMentions: Int,
        actionCount: Int,
        hasConditional: Boolean,
        hasParallel: Boolean
    ): Float {
        val baseScore = when (level) {
            ComplexityLevel.TRIVIAL -> 0.1f
            ComplexityLevel.SIMPLE -> 0.25f
            ComplexityLevel.MODERATE -> 0.55f
            ComplexityLevel.COMPLEX -> 0.85f
        }
        var score = baseScore
        score += minOf(verbCount, 5) / 5f * 0.1f
        score += minOf(appMentions, 3) / 3f * 0.1f
        if (actionCount > 0) {
            score += minOf(actionCount - 1, 10) / 10f * 0.15f
        }
        if (hasConditional) score += 0.08f
        if (hasParallel) score += 0.08f
        return score.coerceIn(0f, 1f)
    }

    // =========================================================================
    //  任务分解
    // =========================================================================

    /**
     * 分解用户指令为子任务计划。
     *
     * 流程：
     * 1. 调用 [assessComplexity] 评估复杂度。
     * 2. 若不需要分解，则将整条指令作为单个子任务返回。
     * 3. 否则按推荐策略分发到对应的分解器：
     *    - [decomposeSequential] / [decomposeConditional] /
     *      [decomposeParallel] / [decomposeHybrid]。
     * 4. 自动创建检查点（[createCheckpoints]）。
     * 5. 将计划登记到活跃计划表并返回。
     *
     * 当 [actions] 非空时，基于动作序列进行精确分解；为空时基于指令文本生成
     * 规划骨架（子任务动作留空，待 AI 后续填充）。
     *
     * @param command 用户自然语言指令。
     * @param actions AI 返回的动作序列（可选）。
     * @return 分解计划。
     */
    fun decompose(
        command: String,
        actions: List<ClawAction> = emptyList()
    ): DecompositionPlan {
        val assessment = assessComplexity(command, actions)
        val planId = nextPlanId()

        val strategy: DecompositionStrategy
        val result: DecomposeResult

        if (!assessment.needsDecomposition) {
            // 无需分解：整条指令作为单个子任务
            strategy = DecompositionStrategy.SEQUENTIAL
            val singleTask = SubTask(
                id = nextTaskId(),
                description = command,
                actions = actions,
                strategy = strategy,
                estimatedSteps = maxOf(1, actions.size),
                progressWeight = 1f,
                isCritical = true
            )
            result = DecomposeResult(listOf(singleTask), emptyList())
        } else {
            strategy = assessment.recommendedStrategy
            result = when (strategy) {
                DecompositionStrategy.SEQUENTIAL -> decomposeSequential(command, actions, assessment)
                DecompositionStrategy.CONDITIONAL -> decomposeConditional(command, actions, assessment)
                DecompositionStrategy.PARALLEL -> decomposeParallel(command, actions, assessment)
                DecompositionStrategy.HYBRID -> decomposeHybrid(command, actions, assessment)
            }
        }

        val checkpoints = createCheckpoints(result.subTasks, strategy)

        val plan = DecompositionPlan(
            id = planId,
            rootCommand = command,
            strategy = strategy,
            subTasks = result.subTasks,
            dependencies = result.dependencies,
            checkpoints = checkpoints,
            assessment = assessment
        )

        activePlans[planId] = plan
        _totalDecompositions.incrementAndGet()
        Log.d(
            TAG,
            "分解任务[${strategy.name}]: ${result.subTasks.size}个子任务, " +
                "复杂度=${assessment.level.name} -> $command"
        )
        return plan
    }

    /**
     * 顺序分解：将动作序列按自然边界切分为按序执行的子任务。
     *
     * 切分边界：APP_OPEN（含其后跟随的等待）、APP_CLOSE、SCREEN_TEXT_EXISTS、
     * SCREEN_INPUT（含其后跟随的「提交」动作）、ANSWER。其余动作累积到当前缓冲，
     * 在遇到边界时落盘为一个子任务。子任务之间以 [DependencyType.SEQUENTIAL]
     * 串联，形成线性依赖链。
     */
    private fun decomposeSequential(
        command: String,
        actions: List<ClawAction>,
        assessment: ComplexityAssessment
    ): DecomposeResult {
        val tasks = if (actions.isNotEmpty()) {
            splitActionsSequential(actions)
        } else {
            planSkeletonSequential(command, assessment)
        }
        val deps = buildSequentialDependencies(tasks)
        return DecomposeResult(tasks, deps)
    }

    /**
     * 条件分解：以条件检测为分支点生成 true/false 分支。
     *
     * 结构：条件检测子任务 ->（条件成立分支 / 条件不成立分支）。条件检测动作取自
     * 动作序列中的 SCREEN_TEXT_EXISTS；若动作序列无此类动作，则从指令文本提取
     * 条件语义。不成立分支默认为回滚（按返回键），标记为非关键。
     */
    private fun decomposeConditional(
        command: String,
        actions: List<ClawAction>,
        assessment: ComplexityAssessment
    ): DecomposeResult {
        val tasks = mutableListOf<SubTask>()
        val deps = mutableListOf<TaskDependency>()

        // 条件检测子任务
        val condActions = actions.filter { it.type == ActionType.SCREEN_TEXT_EXISTS }
        val condTask = SubTask(
            id = nextTaskId(),
            description = "条件检测",
            actions = condActions,
            strategy = DecompositionStrategy.CONDITIONAL,
            estimatedSteps = maxOf(1, condActions.size),
            progressWeight = 0.2f,
            isCritical = true
        )
        tasks.add(condTask)

        // 提取条件文本
        val condText = condActions.firstOrNull()?.text
            ?: extractConditionFromCommand(command)
            ?: "目标条件"

        // 条件成立分支：条件检测之外的全部动作
        val trueActions = actions.filter { it.type != ActionType.SCREEN_TEXT_EXISTS }
        val trueBranch = SubTask(
            id = nextTaskId(),
            description = "条件成立分支：$condText",
            actions = trueActions,
            strategy = DecompositionStrategy.CONDITIONAL,
            dependsOn = listOf(condTask.id),
            condition = "屏幕包含「$condText」",
            branchLabel = "true",
            estimatedSteps = maxOf(1, trueActions.size),
            progressWeight = 0.6f,
            isCritical = true
        )
        tasks.add(trueBranch)
        deps.add(
            TaskDependency(
                fromTaskId = condTask.id,
                toTaskId = trueBranch.id,
                type = DependencyType.CONDITIONAL,
                description = "条件成立时执行"
            )
        )

        // 条件不成立分支：回滚 / 提示（非关键）
        val falseBranch = SubTask(
            id = nextTaskId(),
            description = "条件不成立分支：回滚或提示",
            actions = listOf(buildKeyAction("BACK", "条件不成立，返回")),
            strategy = DecompositionStrategy.CONDITIONAL,
            dependsOn = listOf(condTask.id),
            condition = "屏幕不包含「$condText」",
            branchLabel = "false",
            estimatedSteps = 1,
            progressWeight = 0.2f,
            isCritical = false
        )
        tasks.add(falseBranch)
        deps.add(
            TaskDependency(
                fromTaskId = condTask.id,
                toTaskId = falseBranch.id,
                type = DependencyType.CONDITIONAL,
                description = "条件不成立时执行"
            )
        )

        return DecomposeResult(tasks, deps)
    }

    /**
     * 并行分解：识别不依赖应用状态的独立操作，标记为可并行。
     *
     * 动作序列中属于 [INDEPENDENT_ACTION_TYPES] 的操作各自成为独立并行子任务；
     * 其余存在依赖的动作先经顺序分解后作为不可并行子任务。当无动作序列时，基于
     * 指令文本检测到的意图生成并行骨架。
     */
    private fun decomposeParallel(
        command: String,
        actions: List<ClawAction>,
        assessment: ComplexityAssessment
    ): DecomposeResult {
        val tasks = mutableListOf<SubTask>()
        val deps = mutableListOf<TaskDependency>()

        if (actions.isNotEmpty()) {
            val independent = actions.filter { it.type in INDEPENDENT_ACTION_TYPES }
            val dependent = actions.filter { it.type !in INDEPENDENT_ACTION_TYPES }

            // 存在依赖的动作：先顺序分解，标记为不可并行
            var prevDepId: String? = null
            if (dependent.isNotEmpty()) {
                val depTasks = splitActionsSequential(dependent)
                for (t in depTasks) {
                    val task = t.copy(
                        strategy = DecompositionStrategy.PARALLEL,
                        canRunInParallel = false
                    )
                    tasks.add(task)
                    prevDepId?.let { prev ->
                        deps.add(
                            TaskDependency(
                                fromTaskId = prev,
                                toTaskId = task.id,
                                type = DependencyType.SEQUENTIAL,
                                description = "顺序执行"
                            )
                        )
                    }
                    prevDepId = task.id
                }
            }

            // 独立操作：各自成为可并行子任务（彼此无依赖）
            for (action in independent) {
                tasks.add(
                    SubTask(
                        id = nextTaskId(),
                        description = "（并行）${action.type?.description ?: action.actionName}",
                        actions = listOf(action),
                        strategy = DecompositionStrategy.PARALLEL,
                        canRunInParallel = true,
                        isCritical = false,
                        estimatedSteps = 1,
                        progressWeight = 0.5f
                    )
                )
            }
        } else {
            // 无动作序列：基于指令意图生成并行骨架
            val intents = detectIntents(command)
            for (intent in intents) {
                tasks.add(
                    SubTask(
                        id = nextTaskId(),
                        description = "（并行）$intent",
                        strategy = DecompositionStrategy.PARALLEL,
                        canRunInParallel = true,
                        estimatedSteps = 1,
                        progressWeight = 1f
                    )
                )
            }
        }

        if (tasks.isEmpty()) {
            tasks.add(
                SubTask(
                    id = nextTaskId(),
                    description = command,
                    strategy = DecompositionStrategy.PARALLEL,
                    estimatedSteps = 1,
                    progressWeight = 1f
                )
            )
        }

        return DecomposeResult(tasks, deps)
    }

    /**
     * 混合分解：先并行执行独立操作，再以 PARALLEL_SYNC 汇合后顺序/条件执行依赖操作。
     *
     * 阶段一：独立操作（[INDEPENDENT_ACTION_TYPES]）作为并行子任务。
     * 阶段二：依赖操作经顺序或条件分解，其首任务以 [DependencyType.PARALLEL_SYNC]
     * 依赖全部阶段一并行任务，形成汇合点。
     */
    private fun decomposeHybrid(
        command: String,
        actions: List<ClawAction>,
        assessment: ComplexityAssessment
    ): DecomposeResult {
        val tasks = mutableListOf<SubTask>()
        val deps = mutableListOf<TaskDependency>()

        if (actions.isNotEmpty()) {
            val independent = actions.filter { it.type in INDEPENDENT_ACTION_TYPES }
            val dependent = actions.filter { it.type !in INDEPENDENT_ACTION_TYPES }

            // 阶段一：并行独立操作
            val parallelTasks = independent.map { action ->
                SubTask(
                    id = nextTaskId(),
                    description = "（并行）${action.type?.description ?: action.actionName}",
                    actions = listOf(action),
                    strategy = DecompositionStrategy.PARALLEL,
                    canRunInParallel = true,
                    isCritical = false,
                    estimatedSteps = 1,
                    progressWeight = 0.4f
                )
            }
            tasks.addAll(parallelTasks)

            // 阶段二：依赖操作（顺序或条件分解）
            val condPresent = dependent.any { it.type == ActionType.SCREEN_TEXT_EXISTS }
            val phase2Result = if (dependent.isEmpty()) {
                DecomposeResult(emptyList(), emptyList())
            } else if (condPresent) {
                decomposeConditional(command, dependent, assessment)
            } else {
                val depTasks = splitActionsSequential(dependent)
                DecomposeResult(depTasks, buildSequentialDependencies(depTasks))
            }
            // 阶段二子任务权重按 0.6 缩放，与阶段一并存于同一计划
            val phase2Tasks = phase2Result.subTasks.map { it.copy(progressWeight = it.progressWeight * 0.6f) }
            tasks.addAll(phase2Tasks)
            deps.addAll(phase2Result.dependencies)

            // 汇合点：阶段二首任务依赖全部阶段一并行任务
            val firstPhase2 = phase2Tasks.firstOrNull()
            if (firstPhase2 != null && parallelTasks.isNotEmpty()) {
                for (pt in parallelTasks) {
                    deps.add(
                        TaskDependency(
                            fromTaskId = pt.id,
                            toTaskId = firstPhase2.id,
                            type = DependencyType.PARALLEL_SYNC,
                            description = "并行任务汇合后执行顺序阶段"
                        )
                    )
                }
            }
        } else {
            // 无动作序列：基于指令意图生成混合骨架
            val intents = detectIntents(command)
            for ((idx, intent) in intents.withIndex()) {
                tasks.add(
                    SubTask(
                        id = nextTaskId(),
                        description = if (idx == 0) "（并行）$intent" else intent,
                        strategy = if (idx == 0) DecompositionStrategy.PARALLEL else DecompositionStrategy.SEQUENTIAL,
                        canRunInParallel = idx == 0,
                        estimatedSteps = 1,
                        progressWeight = 1f
                    )
                )
            }
            if (tasks.size >= 2) {
                deps.add(
                    TaskDependency(
                        fromTaskId = tasks[0].id,
                        toTaskId = tasks[1].id,
                        type = DependencyType.PARALLEL_SYNC,
                        description = "并行任务汇合"
                    )
                )
            }
        }

        if (tasks.isEmpty()) {
            tasks.add(
                SubTask(
                    id = nextTaskId(),
                    description = command,
                    strategy = DecompositionStrategy.HYBRID,
                    estimatedSteps = 1,
                    progressWeight = 1f
                )
            )
        }

        return DecomposeResult(tasks, deps)
    }

    // =========================================================================
    //  顺序分解辅助
    // =========================================================================

    /**
     * 将动作序列按自然边界切分为顺序子任务。
     *
     * 切分规则见 [decomposeSequential] 的文档。缓冲中的动作在遇到边界时落盘为一个
     * 子任务，描述由 [describeActions] 生成。
     */
    private fun splitActionsSequential(actions: List<ClawAction>): List<SubTask> {
        val tasks = mutableListOf<SubTask>()
        var buffer = mutableListOf<ClawAction>()

        /** 将当前缓冲落盘为一个子任务。 */
        fun flush() {
            if (buffer.isEmpty()) return
            tasks.add(
                SubTask(
                    id = nextTaskId(),
                    description = describeActions(buffer),
                    actions = buffer.toList(),
                    strategy = DecompositionStrategy.SEQUENTIAL,
                    estimatedSteps = buffer.size,
                    progressWeight = 1f
                )
            )
            buffer = mutableListOf()
        }

        var i = 0
        while (i < actions.size) {
            val action = actions[i]
            when (action.type) {
                ActionType.APP_OPEN -> {
                    // 打开应用：聚合并跟随的等待
                    flush()
                    val group = mutableListOf(action)
                    var j = i + 1
                    while (j < actions.size && actions[j].type == ActionType.SCREEN_WAIT) {
                        group.add(actions[j])
                        j++
                    }
                    tasks.add(
                        SubTask(
                            id = nextTaskId(),
                            description = "打开应用「${appName(action)}」",
                            actions = group,
                            strategy = DecompositionStrategy.SEQUENTIAL,
                            estimatedSteps = group.size,
                            progressWeight = 1f,
                            isCritical = true
                        )
                    )
                    i = j
                }

                ActionType.APP_CLOSE -> {
                    flush()
                    tasks.add(
                        SubTask(
                            id = nextTaskId(),
                            description = "关闭应用「${action.packageName ?: ""}」",
                            actions = listOf(action),
                            strategy = DecompositionStrategy.SEQUENTIAL,
                            estimatedSteps = 1,
                            progressWeight = 0.5f
                        )
                    )
                    i++
                }

                ActionType.SCREEN_TEXT_EXISTS -> {
                    // 条件检测：作为独立子任务（顺序上下文中的检测点）
                    flush()
                    tasks.add(
                        SubTask(
                            id = nextTaskId(),
                            description = "检测条件「${action.text ?: ""}」",
                            actions = listOf(action),
                            strategy = DecompositionStrategy.SEQUENTIAL,
                            estimatedSteps = 1,
                            progressWeight = 0.5f
                        )
                    )
                    i++
                }

                ActionType.SCREEN_INPUT -> {
                    // 输入：聚合紧跟的「提交」动作（如发送/搜索/回车）
                    flush()
                    val group = mutableListOf(action)
                    val next = actions.getOrNull(i + 1)
                    if (next != null && isSubmitAction(next)) {
                        group.add(next)
                        i += 2
                    } else {
                        i++
                    }
                    tasks.add(
                        SubTask(
                            id = nextTaskId(),
                            description = "输入「${action.text ?: ""}」" + if (group.size > 1) "并提交" else "",
                            actions = group,
                            strategy = DecompositionStrategy.SEQUENTIAL,
                            estimatedSteps = group.size,
                            progressWeight = 1f
                        )
                    )
                }

                ActionType.ANSWER -> {
                    // 总结回答：末尾非关键子任务
                    flush()
                    tasks.add(
                        SubTask(
                            id = nextTaskId(),
                            description = "总结回答",
                            actions = listOf(action),
                            strategy = DecompositionStrategy.SEQUENTIAL,
                            estimatedSteps = 1,
                            progressWeight = 0.5f,
                            isCritical = false
                        )
                    )
                    i++
                }

                else -> {
                    // 其余动作：缓冲到当前子任务；若缓冲为空且当前是等待，则独立成任务
                    if (action.type == ActionType.SCREEN_WAIT && buffer.isEmpty()) {
                        tasks.add(
                            SubTask(
                                id = nextTaskId(),
                                description = "等待${action.ms ?: 0}ms",
                                actions = listOf(action),
                                strategy = DecompositionStrategy.SEQUENTIAL,
                                estimatedSteps = 1,
                                progressWeight = 0.3f,
                                isCritical = false
                            )
                        )
                    } else {
                        buffer.add(action)
                    }
                    i++
                }
            }
        }
        flush()
        return tasks
    }

    /**
     * 基于指令文本生成顺序规划骨架（无动作序列时使用）。
     *
     * 通过 [detectIntents] 识别指令中的意图（打开应用、搜索、输入发送、截图等），
     * 依次生成描述性子任务；若无法识别意图，则按预估步数生成等分骨架。
     */
    private fun planSkeletonSequential(
        command: String,
        assessment: ComplexityAssessment
    ): List<SubTask> {
        val intents = detectIntents(command)
        if (intents.isEmpty()) {
            val steps = assessment.estimatedStepCount.coerceAtLeast(2)
            return (1..steps).map { k ->
                SubTask(
                    id = nextTaskId(),
                    description = "步骤$k（待 AI 规划具体动作）",
                    strategy = DecompositionStrategy.SEQUENTIAL,
                    estimatedSteps = 1,
                    progressWeight = 1f
                )
            }
        }
        return intents.map { intent ->
            SubTask(
                id = nextTaskId(),
                description = intent,
                strategy = DecompositionStrategy.SEQUENTIAL,
                estimatedSteps = 1,
                progressWeight = 1f
            )
        }
    }

    /**
     * 为顺序子任务列表构建线性依赖链（前驱 -> 后继）。
     */
    private fun buildSequentialDependencies(tasks: List<SubTask>): List<TaskDependency> {
        val deps = mutableListOf<TaskDependency>()
        for (k in 1 until tasks.size) {
            deps.add(
                TaskDependency(
                    fromTaskId = tasks[k - 1].id,
                    toTaskId = tasks[k].id,
                    type = DependencyType.SEQUENTIAL,
                    description = "${tasks[k - 1].description} 完成后执行 ${tasks[k].description}"
                )
            )
        }
        return deps
    }

    // =========================================================================
    //  检查点管理
    // =========================================================================

    /**
     * 为计划中的子任务创建检查点。
     *
     * 在关键子任务（[SubTask.isCritical] 为真）或会改变设备状态的操作（APP_OPEN、
     * APP_UNINSTALL、SYSTEM_CLEAR_CACHE）执行前创建检查点，并生成对应的回滚动作
     * 序列（[buildRollbackActions]）。
     *
     * @param plan 分解计划。
     * @return 检查点列表。
     */
    fun createCheckpoints(plan: DecompositionPlan): List<Checkpoint> =
        createCheckpoints(plan.subTasks, plan.strategy)

    /**
     * 内部检查点创建实现。
     */
    private fun createCheckpoints(
        subTasks: List<SubTask>,
        strategy: DecompositionStrategy
    ): List<Checkpoint> {
        val checkpoints = mutableListOf<Checkpoint>()
        for (task in subTasks) {
            // 仅在关键任务或改变设备状态的任务前创建检查点
            val changesState = task.actions.any { action ->
                action.type == ActionType.APP_OPEN ||
                    action.type == ActionType.APP_UNINSTALL ||
                    action.type == ActionType.SYSTEM_CLEAR_CACHE
            }
            if (!task.isCritical && !changesState) continue

            val rollback = buildRollbackActions(task)
            checkpoints.add(
                Checkpoint(
                    id = nextCheckpointId(),
                    taskId = task.id,
                    description = "「${task.description}」执行前检查点",
                    rollbackActions = rollback,
                    snapshotSummary = "策略=${strategy.name}, 步数=${task.estimatedSteps}"
                )
            )
        }
        _totalCheckpointsCreated.addAndGet(checkpoints.size)
        return checkpoints
    }

    /**
     * 为子任务构建回滚动作序列。
     *
     * - 若子任务包含 APP_OPEN，则回滚为 APP_CLOSE 该应用。
     * - 若子任务包含破坏性操作（卸载/写文件/清缓存/杀进程），追加返回键。
     * - 若无上述，则回滚为按返回键。
     */
    private fun buildRollbackActions(task: SubTask): List<ClawAction> {
        val rollback = mutableListOf<ClawAction>()

        val openAction = task.actions.firstOrNull { it.type == ActionType.APP_OPEN }
        if (openAction != null) {
            val pkg = openAction.packageName
            rollback.add(
                ClawAction(
                    actionName = ActionType.APP_CLOSE.name,
                    params = if (pkg != null) {
                        JsonObject(mapOf("packageName" to JsonPrimitive(pkg)))
                    } else {
                        JsonObject(emptyMap())
                    },
                    description = "回滚：关闭「${pkg ?: "应用"}」"
                )
            )
        }

        val hasDestructive = task.actions.any {
            it.type == ActionType.APP_UNINSTALL ||
                it.type == ActionType.FILE_WRITE ||
                it.type == ActionType.SYSTEM_CLEAR_CACHE ||
                it.type == ActionType.SYSTEM_KILL_PROCESS
        }
        if (hasDestructive || rollback.isEmpty()) {
            rollback.add(buildKeyAction("BACK", "回滚：返回上一步"))
        }
        return rollback
    }

    // =========================================================================
    //  进度估算
    // =========================================================================

    /**
     * 基于计划内子任务当前阶段估算整体进度。
     *
     * 已完成（[TaskPhase.COMPLETED]）子任务贡献其全部权重；执行中
     * （[TaskPhase.EXECUTING]）子任务按 0.5 的内部进度折算；其余不计入。
     *
     * @param plan 分解计划。
     * @return 进度估算结果。
     */
    fun estimateProgress(plan: DecompositionPlan): ProgressEstimate {
        val completedIds = plan.subTasks
            .filter { it.isCompleted }
            .map { it.id }
            .toSet()
        val currentId = plan.subTasks
            .firstOrNull { it.phase == TaskPhase.EXECUTING }?.id
        return estimateProgress(plan, completedIds, currentId, 0.5f)
    }

    /**
     * 基于显式完成集合与当前任务估算整体进度。
     *
     * @param plan 分解计划。
     * @param completedTaskIds 已完成子任务 id 集合。
     * @param currentTaskId 正在执行的子任务 id（可选）。
     * @param currentTaskInternalProgress 当前任务的内部进度（0.0-1.0）。
     * @return 进度估算结果。
     */
    fun estimateProgress(
        plan: DecompositionPlan,
        completedTaskIds: Set<String>,
        currentTaskId: String? = null,
        currentTaskInternalProgress: Float = 0f
    ): ProgressEstimate {
        val totalWeight = plan.subTasks.map { it.progressWeight }.sum().coerceAtLeast(0.0001f)
        val completedWeight = plan.subTasks
            .filter { it.id in completedTaskIds }
            .map { it.progressWeight }
            .sum()

        // 当前任务的折算进度
        var partial = 0f
        var resolvedCurrentId = currentTaskId
        if (resolvedCurrentId == null) {
            resolvedCurrentId = plan.subTasks
                .firstOrNull { it.phase == TaskPhase.EXECUTING }?.id
        }
        if (resolvedCurrentId != null) {
            val ct = plan.subTasks.firstOrNull { it.id == resolvedCurrentId }
            if (ct != null && ct.id !in completedTaskIds) {
                partial = ct.progressWeight * currentTaskInternalProgress.coerceIn(0f, 1f)
            }
        }

        val overall = ((completedWeight + partial) / totalWeight * 100f).coerceIn(0f, 100f)
        val completedCount = plan.subTasks.count { it.id in completedTaskIds }
        val remainingSteps = plan.subTasks
            .filter { it.id !in completedTaskIds }
            .sumOf { it.estimatedSteps }

        return ProgressEstimate(
            overallProgress = overall,
            completedWeight = completedWeight,
            totalWeight = totalWeight,
            completedCount = completedCount,
            totalCount = plan.subTasks.size,
            currentTaskId = resolvedCurrentId,
            estimatedRemainingSteps = remainingSteps
        )
    }

    // =========================================================================
    //  动态重新分解
    // =========================================================================

    /**
     * 在子任务失败或上下文变化后重新分解剩余任务。
     *
     * 策略：
     * 1. 将失败子任务标记为 [TaskPhase.FAILED]。
     * 2. 若失败子任务含多个动作，则拆分为两个更小的重试子任务（重试 A / 重试 B），
     *    重建依赖：原前驱指向重试 A，重试 A -> 重试 B，重试 B 指向原后继。
     * 3. 若失败子任务仅单个动作，则尝试生成替代动作（如 SCREEN_CLICK_TEXT 升级为
     *    SCREEN_FIND_AND_CLICK、SCREEN_WAIT 延长时长）；无替代则保持失败状态。
     * 4. 重新生成检查点并更新计划。
     *
     * @param plan 当前分解计划。
     * @param failedTaskId 失败的子任务 id。
     * @param context 失败原因描述（用于日志）。
     * @return 重新分解后的新计划（id 不变，覆盖活跃表中的旧计划）。
     */
    fun reDecompose(
        plan: DecompositionPlan,
        failedTaskId: String,
        context: String = ""
    ): DecompositionPlan {
        val failedTask = plan.subTasks.firstOrNull { it.id == failedTaskId }
        val newSubTasks = plan.subTasks.map { task ->
            if (task.id == failedTaskId) task.copy(phase = TaskPhase.FAILED) else task
        }.toMutableList()
        val newDeps = plan.dependencies.toMutableList()

        if (failedTask != null) {
            if (failedTask.actions.size > 1) {
                // 拆分为两个更小的重试子任务
                val mid = failedTask.actions.size / 2
                val part1 = failedTask.actions.subList(0, mid)
                val part2 = failedTask.actions.subList(mid, failedTask.actions.size)
                val retry1 = SubTask(
                    id = nextTaskId(),
                    description = "（重试A）${failedTask.description}",
                    actions = part1,
                    strategy = failedTask.strategy,
                    phase = TaskPhase.PLANNING,
                    estimatedSteps = part1.size,
                    progressWeight = failedTask.progressWeight * 0.5f,
                    isCritical = failedTask.isCritical
                )
                val retry2 = SubTask(
                    id = nextTaskId(),
                    description = "（重试B）${failedTask.description}",
                    actions = part2,
                    strategy = failedTask.strategy,
                    phase = TaskPhase.PLANNING,
                    dependsOn = listOf(retry1.id),
                    estimatedSteps = part2.size,
                    progressWeight = failedTask.progressWeight * 0.5f,
                    isCritical = failedTask.isCritical
                )

                val idx = newSubTasks.indexOfFirst { it.id == failedTaskId }
                if (idx >= 0) {
                    newSubTasks[idx] = retry1
                    newSubTasks.add(idx + 1, retry2)
                }
                // 移除涉及失败任务的全部依赖，并重建
                newDeps.removeAll { it.fromTaskId == failedTaskId || it.toTaskId == failedTaskId }
                newDeps.add(
                    TaskDependency(
                        fromTaskId = retry1.id,
                        toTaskId = retry2.id,
                        type = DependencyType.SEQUENTIAL,
                        description = "重试拆分：A 完成后执行 B"
                    )
                )
                // 原前驱 -> 重试 A，重试 B -> 原后继
                for (dep in plan.dependencies) {
                    when {
                        dep.toTaskId == failedTaskId -> newDeps.add(
                            dep.copy(
                                toTaskId = retry1.id,
                                description = dep.description + "（重定向至重试A）"
                            )
                        )
                        dep.fromTaskId == failedTaskId -> newDeps.add(
                            dep.copy(
                                fromTaskId = retry2.id,
                                description = dep.description + "（重试B作为前驱）"
                            )
                        )
                    }
                }
            } else {
                // 单动作失败：尝试替代动作
                val altAction = buildAlternativeAction(failedTask.actions.firstOrNull())
                if (altAction != null) {
                    val retry = SubTask(
                        id = nextTaskId(),
                        description = "（重试-替代）${failedTask.description}",
                        actions = listOf(altAction),
                        strategy = failedTask.strategy,
                        phase = TaskPhase.PLANNING,
                        estimatedSteps = 1,
                        progressWeight = failedTask.progressWeight,
                        isCritical = failedTask.isCritical
                    )
                    val idx = newSubTasks.indexOfFirst { it.id == failedTaskId }
                    if (idx >= 0) {
                        newSubTasks[idx] = retry
                    }
                    newDeps.removeAll { it.fromTaskId == failedTaskId || it.toTaskId == failedTaskId }
                    for (dep in plan.dependencies) {
                        when {
                            dep.toTaskId == failedTaskId -> newDeps.add(dep.copy(toTaskId = retry.id))
                            dep.fromTaskId == failedTaskId -> newDeps.add(dep.copy(fromTaskId = retry.id))
                        }
                    }
                }
                // 无替代动作：保持 FAILED 状态，交由上层处理
            }
        }

        val newCheckpoints = createCheckpoints(newSubTasks, plan.strategy)
        val newPlan = plan.copy(
            subTasks = newSubTasks.toList(),
            dependencies = newDeps.toList(),
            checkpoints = newCheckpoints,
            updatedAt = System.currentTimeMillis()
        )
        // 以键级锁原子地覆盖活跃表中的计划
        activePlans.compute(newPlan.id) { _, _ -> newPlan }
        _totalReDecompositions.incrementAndGet()
        Log.w(
            TAG,
            "重新分解计划[${newPlan.id}]: 失败子任务=$failedTaskId, " +
                "原因=${context.ifBlank { "未提供" }}"
        )
        return newPlan
    }

    /**
     * 为失败的单动作子任务生成替代动作，以提升重试成功率。
     *
     * - SCREEN_CLICK_TEXT -> SCREEN_FIND_AND_CLICK（自动滚动查找）。
     * - SCREEN_WAIT -> 延长等待时长（按 [WAIT_RETRY_GROWTH] 倍）。
     * - 其余类型无替代，返回 null。
     */
    private fun buildAlternativeAction(action: ClawAction?): ClawAction? {
        if (action == null) return null
        return when (action.type) {
            ActionType.SCREEN_CLICK_TEXT -> action.copy(
                actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
                description = "替代重试：自动滚动查找并点击「${action.text ?: ""}」"
            )

            ActionType.SCREEN_WAIT -> {
                val ms = action.ms ?: 1000L
                val newMs = (ms * WAIT_RETRY_GROWTH).toLong()
                action.copy(
                    params = action.params.withParam("ms", JsonPrimitive(newMs)),
                    description = "替代重试：延长等待至${newMs}ms"
                )
            }

            else -> null
        }
    }

    // =========================================================================
    //  依赖查询
    // =========================================================================

    /**
     * 获取计划中的依赖关系。
     *
     * @param plan 分解计划。
     * @param taskId 指定子任务 id（可选）。为 null 时返回全部依赖；指定时返回
     *               涉及该子任务的依赖（作为前驱或后继）。
     * @return 依赖关系列表。
     */
    fun getDependencies(plan: DecompositionPlan, taskId: String? = null): List<TaskDependency> {
        return if (taskId == null) {
            plan.dependencies
        } else {
            plan.dependencies.filter { it.fromTaskId == taskId || it.toTaskId == taskId }
        }
    }

    // =========================================================================
    //  阶段更新与计划管理
    // =========================================================================

    /**
     * 更新指定计划中某子任务的执行阶段（线程安全）。
     *
     * 通过 `ConcurrentHashMap.compute` 以键级锁保证「读取-修改-写回」的原子性，
     * 避免并发更新相互覆盖。
     *
     * @param planId 计划 id。
     * @param taskId 子任务 id。
     * @param phase 新的执行阶段。
     * @return 更新后的计划；计划不存在则返回 null。
     */
    fun updateTaskPhase(planId: String, taskId: String, phase: TaskPhase): DecompositionPlan? {
        var result: DecompositionPlan? = null
        activePlans.compute(planId) { _, current ->
            if (current == null) {
                null
            } else {
                val updated = current.copy(
                    subTasks = current.subTasks.map { task ->
                        if (task.id == taskId) task.copy(phase = phase) else task
                    },
                    updatedAt = System.currentTimeMillis()
                )
                result = updated
                updated
            }
        }
        return result
    }

    /** 获取活跃计划（线程安全读取）。 */
    fun getActivePlan(planId: String): DecompositionPlan? = activePlans[planId]

    /** 获取全部活跃计划（按更新时间降序）。 */
    fun getAllActivePlans(): List<DecompositionPlan> =
        activePlans.values.sortedByDescending { it.updatedAt }

    /**
     * 清空全部活跃计划与统计计数。
     *
     * 适用于测试或重置场景。
     */
    fun clearAll() {
        activePlans.clear()
        _totalDecompositions.set(0)
        _totalReDecompositions.set(0)
        _totalCheckpointsCreated.set(0)
        taskCounter.set(0)
        checkpointCounter.set(0)
        planCounter.set(0)
        Log.d(TAG, "已清空所有分解计划与统计")
    }

    /**
     * 获取分解器统计摘要（用于 UI 展示与调试）。
     */
    fun getStats(): String = buildString {
        append("任务分解统计: 累计分解${totalDecompositions}次, ")
        append("重分解${totalReDecompositions}次, ")
        append("检查点${totalCheckpointsCreated}个, ")
        append("活跃计划${activePlans.size}个")
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    /** 判断动作是否为「提交」语义（发送/搜索/回车等），用于聚合输入子任务。 */
    private fun isSubmitAction(action: ClawAction): Boolean {
        val type = action.type ?: return false
        if (type == ActionType.SCREEN_KEY) {
            return action.keyName?.uppercase() in setOf("ENTER", "DPAD_CENTER")
        }
        if (type == ActionType.SCREEN_CLICK_TEXT || type == ActionType.SCREEN_FIND_AND_CLICK) {
            val text = action.text ?: return false
            return SUBMIT_KEYWORDS.any { text.contains(it, ignoreCase = true) }
        }
        return false
    }

    /** 获取打开应用动作对应的应用名（优先包名，其次 name 参数，兜底「应用」）。 */
    private fun appName(action: ClawAction): String =
        action.packageName ?: action.name ?: "应用"

    /** 将动作列表描述为人类可读的「动作1 -> 动作2」形式。 */
    private fun describeActions(actions: List<ClawAction>): String =
        actions.joinToString(" -> ") { it.type?.description ?: it.actionName }
            .ifBlank { "操作" }

    /** 构造按键动作。 */
    private fun buildKeyAction(key: String, desc: String): ClawAction =
        ClawAction(
            actionName = ActionType.SCREEN_KEY.name,
            params = JsonObject(mapOf("key" to JsonPrimitive(key))),
            description = desc
        )

    /** 统计指令中包含的动作动词数量。 */
    private fun countActionVerbs(input: String): Int =
        ACTION_VERBS.count { input.contains(it) }

    /** 统计指令中提及的已知应用数量（用于多应用切换检测）。 */
    private fun countAppMentions(input: String): Int =
        KNOWN_APPS.count { input.contains(it) }

    /** 从指令文本中提取条件语义关键词（如「如果…登录」中的「登录」）。 */
    private fun extractConditionFromCommand(command: String): String? {
        val match = Regex(
            "(?i)(?:如果|要是|假如|倘若|若|万一)\\s*" +
                "(?:屏幕|页面)?(?:包含|有|存在|出现)?\\s*([\\u4e00-\\u9fa5a-zA-Z0-9_]{1,12})"
        ).find(command)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    /**
     * 从指令文本中按序检测意图，用于无动作序列时生成规划骨架。
     *
     * 识别意图：打开应用、等待加载、搜索、输入发送、截图、获取系统信息、清理缓存。
     */
    private fun detectIntents(command: String): List<String> {
        val intents = mutableListOf<String>()

        // 打开应用
        val openMatch = Regex("(?i)(打开|启动|开启)\\s*([\\u4e00-\\u9fa5a-zA-Z0-9_]+)").find(command)
        if (openMatch != null) {
            intents.add("打开应用「${openMatch.groupValues[2]}」")
            intents.add("等待应用加载完成")
        }
        // 搜索
        if (Regex("(?i)(搜索|查找|搜一下|查一下)").containsMatchIn(command)) {
            val searchMatch = Regex("(?i)(?:搜索|查找|搜一下|查一下)\\s*([\\u4e00-\\u9fa5a-zA-Z0-9_]+)")
                .find(command)
            intents.add("搜索「${searchMatch?.groupValues?.get(1) ?: "目标"}」")
        }
        // 输入 / 发送
        if (Regex("(?i)(发|发送|输入|说|回复)").containsMatchIn(command)) {
            intents.add("输入内容并发送")
        }
        // 截图
        if (Regex("(?i)(截图|截屏)").containsMatchIn(command)) {
            intents.add("截取屏幕")
        }
        // 系统信息
        if (Regex("(?i)(内存|电量|电池|cpu|存储|系统信息)").containsMatchIn(command)) {
            intents.add("获取系统信息")
        }
        // 清理缓存
        if (Regex("(?i)(清理|清除)\\s*缓存").containsMatchIn(command)) {
            intents.add("清理缓存")
        }
        if (intents.isEmpty()) {
            intents.add("执行用户指令")
        }
        return intents
    }

    /** 生成下一个计划 id。 */
    private fun nextPlanId(): String = "plan-${planCounter.incrementAndGet()}"

    /** 生成下一个子任务 id。 */
    private fun nextTaskId(): String = "task-${taskCounter.incrementAndGet()}"

    /** 生成下一个检查点 id。 */
    private fun nextCheckpointId(): String = "cp-${checkpointCounter.incrementAndGet()}"

    /**
     * 扩展函数：为 [JsonObject] 添加或替换一个键值对，返回新的 [JsonObject]。
     *
     * 由于 [JsonObject] 是不可变的，此方法通过复制底层数据实现「修改」。
     */
    private fun JsonObject.withParam(key: String, value: JsonPrimitive): JsonObject {
        val newMap = this.toMutableMap()
        newMap[key] = value
        return JsonObject(newMap)
    }

    /**
     * 分解中间结果（内部使用）。
     *
     * @property subTasks 子任务列表。
     * @property dependencies 子任务依赖关系列表。
     */
    private data class DecomposeResult(
        val subTasks: List<SubTask>,
        val dependencies: List<TaskDependency>
    )
}
