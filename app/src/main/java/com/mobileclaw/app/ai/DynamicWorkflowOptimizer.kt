package com.mobileclaw.app.ai

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

// =============================================================================
//  DynamicWorkflowOptimizer - 动态工作流优化器
// =============================================================================

/**
 * 动态工作流优化器 —— 从执行历史中学习并持续优化多步任务工作流。
 *
 * 核心理念：用户在手机上重复执行相似的多步任务（如「打开微信 -> 搜索联系人 ->
 * 发送消息」）。每次都让 AI 从零规划既浪费 Token 又增加延迟。本系统将常见工作流
 * 模板化，并基于真实执行历史动态优化步骤，使工作流越用越快、越用越稳。
 *
 * 六大核心能力：
 * 1. 工作流模板：预置常见多步任务工作流，并支持运行时注册新模板。每个工作流
 *    包含有序的 [WorkflowStep] 列表、分类、统计指标与评分。
 * 2. 动态优化：基于执行历史对工作流步骤进行优化——移除冗余等待、合并连续等待、
 *    裁剪过长等待、依据学习数据校正预估时长与成功率。
 * 3. 替代路径查找：当某一步骤失败时，优先使用该步骤预定义的替代步骤；若无替代，
 *    则尝试跳过非关键步骤；最后从同类高评分工作流中借鉴可行路径。
 * 4. 步骤时序优化：按「前驱动作类型 -> 当前动作类型」学习步骤间的典型耗时预算，
 *    用于裁剪 SCREEN_WAIT 的过量等待，逐步逼近最优等待时长。
 * 5. 工作流评分：按成功率、效率（耗时）、Token 效率三维打分，
 *    overall = successRate * 0.5 + efficiency * 0.3 + tokenEfficiency * 0.2。
 * 6. 自动进化：失败率 > 70% 且尝试 >= 10 次的工作流被标记弃用；
 *    成功率 > 90% 且尝试 >= 5 次的工作流被提升（提交其优化步骤、解除弃用）。
 *
 * 线程安全：
 * - 工作流存储、步骤统计、步骤对时序均使用 [ConcurrentHashMap]，可被多线程并发调用。
 * - 单个工作流的字段更新通过 `synchronized(workflow)` 保证原子性。
 * - 全局计数使用 @Volatile + private set，保证可见性的同时禁止外部修改。
 * - 典型场景：UI 线程查询工作流、后台执行线程记录执行结果与触发优化。
 *
 * 使用方式：
 * ```
 * val optimizer = DynamicWorkflowOptimizer()
 * // 注册自定义工作流
 * optimizer.registerWorkflow(Workflow(
 *     id = "my_flow", name = "我的流程",
 *     category = WorkflowCategory.CUSTOM,
 *     steps = listOf(WorkflowStep("APP_OPEN", mapOf("name" to "微信"), 1500L))
 * ))
 * // 按用户指令查找匹配工作流
 * val wf = optimizer.findWorkflow("用微信给张三发消息")
 * // 优化工作流
 * val result = optimizer.optimizeWorkflow(wf!!)
 * // 记录执行结果
 * optimizer.recordExecution(wf.id, success = true, durationMs = 5200, tokensUsed = 180, failedStepIndex = -1)
 * // 步骤失败时寻找替代路径
 * val alt = optimizer.findAlternativePath(wf, failedStepIndex = 2)
 * // 触发自动进化
 * optimizer.evolveWorkflows()
 * ```
 */
class DynamicWorkflowOptimizer {

    private companion object {
        const val TAG = "DynamicWorkflowOpt"

        // —— 评分权重 ——
        /** 成功率权重。 */
        const val SCORE_WEIGHT_SUCCESS = 0.5f
        /** 效率（耗时）权重。 */
        const val SCORE_WEIGHT_EFFICIENCY = 0.3f
        /** Token 效率权重。 */
        const val SCORE_WEIGHT_TOKEN = 0.2f

        // —— 进化阈值 ——
        /** 弃用阈值：失败率超过此值且尝试次数达标即弃用。 */
        const val DEPRECATION_FAIL_RATE = 0.7f
        /** 弃用最小尝试次数。 */
        const val DEPRECATION_MIN_ATTEMPTS = 10
        /** 提升阈值：成功率超过此值且尝试次数达标即提升。 */
        const val PROMOTION_SUCCESS_RATE = 0.9f
        /** 提升最小尝试次数。 */
        const val PROMOTION_MIN_ATTEMPTS = 5

        // —— 容量与学习参数 ——
        /** 最大工作流数量。 */
        const val MAX_WORKFLOWS = 100
        /** EMA 平滑系数（新观测占比）。 */
        const val EMA_ALPHA = 0.3
        /** 评分基准：预期最大耗时（毫秒），超过则效率为 0。 */
        const val EXPECTED_MAX_DURATION_MS = 30_000L
        /** 评分基准：预期最大 Token 数，超过则 Token 效率为 0。 */
        const val EXPECTED_MAX_TOKENS = 2000
        /** 每移除一个步骤预估节省的 Token 数。 */
        const val TOKENS_PER_STEP = 50
        /** 过长等待阈值（毫秒），超过则触发裁剪。 */
        const val EXCESSIVE_WAIT_MS = 5000L
        /** 裁剪后的默认等待时长（毫秒）。 */
        const val DEFAULT_TRIMMED_WAIT_MS = 1500L
        /** 等待裁剪触发的学习值倍数：当 ms > 学习值 * 此倍数时裁剪。 */
        const val WAIT_LEARN_MULTIPLIER = 1.5
        /** findWorkflow 的最低匹配分阈值。 */
        const val MATCH_THRESHOLD = 0.2f
    }

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 工作流分类。
     * - COMMUNICATION：通讯类（微信、QQ、短信等）
     * - SOCIAL_MEDIA：社交媒体类（抖音、微博、小红书等）
     * - PRODUCTIVITY：效率工具类（截图分享、文件处理等）
     * - ENTERTAINMENT：娱乐类（音乐、视频等）
     * - SYSTEM：系统操作类（清理缓存、调节亮度等）
     * - CUSTOM：用户自定义
     */
    enum class WorkflowCategory {
        COMMUNICATION,
        SOCIAL_MEDIA,
        PRODUCTIVITY,
        ENTERTAINMENT,
        SYSTEM,
        CUSTOM
    }

    /**
     * 优化变更类型。
     * - MERGED_STEPS：合并连续步骤（如连续等待合并）
     * - REMOVED_UNNECESSARY_STEP：移除不必要步骤（如末尾等待）
     * - REMOVED_REDUNDANT_WAIT：移除冗余等待
     * - ADJUSTED_WAIT_TIME：调整等待时长
     * - REORDERED_STEPS：重排步骤顺序
     * - UPDATED_ESTIMATE：依据学习数据校正预估时长/成功率
     */
    enum class ChangeType {
        MERGED_STEPS,
        REMOVED_UNNECESSARY_STEP,
        REMOVED_REDUNDANT_WAIT,
        ADJUSTED_WAIT_TIME,
        REORDERED_STEPS,
        UPDATED_ESTIMATE
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 单个工作流步骤。
     *
     * @property actionType 动作类型（对应 [ActionType] 的名称，如 "APP_OPEN"、"SCREEN_WAIT"）
     * @property params 动作参数（键值对，如 mapOf("packageName" to "com.tencent.mm")）
     * @property estimatedDurationMs 预估耗时（毫秒）
     * @property successRate 历史成功率（0.0-1.0）
     * @property alternatives 替代步骤列表（当本步骤失败时可尝试）
     */
    data class WorkflowStep(
        val actionType: String,
        val params: Map<String, String> = emptyMap(),
        var estimatedDurationMs: Long = 0L,
        var successRate: Float = 1f,
        val alternatives: List<WorkflowStep> = emptyList()
    )

    /**
     * 工作流评分。
     *
     * @property successRate 成功率（0.0-1.0）
     * @property efficiency 效率分（基于平均耗时，0.0-1.0，耗时越低越高）
     * @property tokenEfficiency Token 效率分（基于平均 Token，0.0-1.0，越低越高）
     * @property overall 综合评分 = successRate * 0.5 + efficiency * 0.3 + tokenEfficiency * 0.2
     */
    data class WorkflowScore(
        val successRate: Float = 0f,
        val efficiency: Float = 0f,
        val tokenEfficiency: Float = 0f,
        val overall: Float = 0f
    )

    /**
     * 工作流定义。
     *
     * @property id 唯一标识
     * @property name 工作流名称（用于展示与匹配）
     * @property steps 标准步骤列表
     * @property category 工作流分类
     * @property successCount 累计成功次数
     * @property failCount 累计失败次数
     * @property avgDurationMs 平均执行耗时（毫秒，EMA）
     * @property avgTokens 平均 Token 消耗（EMA）
     * @property score 当前评分快照
     * @property deprecated 是否已弃用
     * @property createdAt 创建时间戳（毫秒）
     * @property lastUsed 最后使用时间戳（毫秒）
     * @property optimizedSteps 最近一次优化产出的步骤列表（提升时提交为标准步骤）
     */
    data class Workflow(
        val id: String,
        val name: String,
        var steps: List<WorkflowStep>,
        val category: WorkflowCategory,
        var successCount: Int = 0,
        var failCount: Int = 0,
        var avgDurationMs: Long = 0L,
        var avgTokens: Int = 0,
        var score: WorkflowScore = WorkflowScore(),
        var deprecated: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        var lastUsed: Long = System.currentTimeMillis(),
        var optimizedSteps: List<WorkflowStep> = emptyList()
    ) {
        /** 总尝试次数。 */
        val totalAttempts: Int get() = successCount + failCount

        /** 当前失败率（0.0-1.0）。 */
        val failRate: Float
            get() = if (totalAttempts > 0) failCount.toFloat() / totalAttempts else 0f

        /** 当前成功率（0.0-1.0）。 */
        val successRate: Float
            get() = if (totalAttempts > 0) successCount.toFloat() / totalAttempts else 0f
    }

    /**
     * 单项优化变更记录。
     *
     * @property type 变更类型
     * @property description 变更描述（中文）
     * @property stepIndex 涉及的原始步骤下标（-1 表示全局变更）
     */
    data class OptimizationChange(
        val type: ChangeType,
        val description: String,
        val stepIndex: Int
    )

    /**
     * 工作流优化结果。
     *
     * @property original 原始步骤列表
     * @property optimized 优化后步骤列表
     * @property changes 应用的变更列表
     * @property estimatedSavingsMs 预估节省耗时（毫秒）
     * @property estimatedTokenSavings 预估节省 Token 数
     */
    data class OptimizationResult(
        val original: List<WorkflowStep>,
        val optimized: List<WorkflowStep>,
        val changes: List<OptimizationChange>,
        val estimatedSavingsMs: Long,
        val estimatedTokenSavings: Int
    )

    /**
     * 替代执行路径。
     *
     * @property steps 替代步骤列表
     * @property reason 选择该替代路径的原因
     * @property estimatedSuccessRate 预估成功率（0.0-1.0）
     */
    data class AlternativePath(
        val steps: List<WorkflowStep>,
        val reason: String,
        val estimatedSuccessRate: Float
    )

    /**
     * 步骤级统计（按 actionType 聚合）。
     * 用于校正步骤预估耗时与成功率。
     */
    private data class StepStats(
        var avgDurationMs: Long = 0L,
        var count: Int = 0,
        var successCount: Int = 0,
        var failCount: Int = 0
    ) {
        val successRate: Float
            get() = if (count > 0) successCount.toFloat() / count else 0f
    }

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 工作流存储（id -> Workflow）。 */
    private val workflows = ConcurrentHashMap<String, Workflow>()

    /** 步骤级统计（actionType -> StepStats），用于校正预估与成功率。 */
    private val stepStats = ConcurrentHashMap<String, StepStats>()

    /**
     * 步骤对时序学习（"前驱动作类型->当前动作类型" -> 典型耗时预算，毫秒）。
     * 主要用于裁剪 SCREEN_WAIT 的过量等待，逐步逼近最优等待时长。
     */
    private val pairTimings = ConcurrentHashMap<String, Long>()

    // ============================================================
    // 全局统计计数
    // ============================================================

    /** 累计执行优化次数。 */
    @Volatile
    var totalOptimizations: Int = 0
        private set

    /** 累计预估节省耗时（毫秒）。 */
    @Volatile
    var totalEstimatedSavingsMs: Long = 0L
        private set

    /** 累计预估节省 Token 数。 */
    @Volatile
    var totalEstimatedTokenSavings: Int = 0
        private set

    /** 累计找到的替代路径数。 */
    @Volatile
    var totalAlternativePathsFound: Int = 0
        private set

    /** 最近一次进化弃用的工作流数。 */
    @Volatile
    var lastDeprecatedCount: Int = 0
        private set

    /** 最近一次提升的工作流数。 */
    @Volatile
    var lastPromotedCount: Int = 0
        private set

    // ============================================================
    // 初始化
    // ============================================================

    init {
        registerDefaultTemplates()
    }

    /** 注册预置的常见工作流模板。 */
    private fun registerDefaultTemplates() {
        // —— 微信发送消息（通讯类）——
        registerWorkflow(
            Workflow(
                id = "wechat_send_message",
                name = "微信发送消息",
                category = WorkflowCategory.COMMUNICATION,
                steps = listOf(
                    WorkflowStep("APP_OPEN", mapOf("packageName" to "com.tencent.mm"), 1500L, 0.95f),
                    WorkflowStep("SCREEN_WAIT", mapOf("ms" to "2000"), 2000L, 1f),
                    WorkflowStep(
                        "SCREEN_CLICK_TEXT", mapOf("text" to "搜索"), 500L, 0.85f,
                        alternatives = listOf(
                            WorkflowStep("SCREEN_FIND_AND_CLICK", mapOf("text" to "搜索"), 600L, 0.8f)
                        )
                    ),
                    WorkflowStep("SCREEN_INPUT", mapOf("text" to "{联系人}"), 800L, 0.9f),
                    WorkflowStep("SCREEN_WAIT", mapOf("ms" to "1000"), 1000L, 1f),
                    WorkflowStep(
                        "SCREEN_CLICK_TEXT", mapOf("text" to "{联系人}"), 500L, 0.8f,
                        alternatives = listOf(
                            WorkflowStep("SCREEN_CLICK", mapOf("x" to "540", "y" to "800"), 400L, 0.7f)
                        )
                    ),
                    WorkflowStep("SCREEN_INPUT", mapOf("text" to "{消息内容}"), 800L, 0.9f),
                    WorkflowStep(
                        "SCREEN_CLICK_TEXT", mapOf("text" to "发送"), 500L, 0.85f,
                        alternatives = listOf(
                            WorkflowStep("SCREEN_KEY", mapOf("key" to "ENTER"), 200L, 0.6f)
                        )
                    )
                )
            )
        )

        // —— 抖音搜索视频（社交媒体类）——
        registerWorkflow(
            Workflow(
                id = "douyin_search",
                name = "抖音搜索视频",
                category = WorkflowCategory.SOCIAL_MEDIA,
                steps = listOf(
                    WorkflowStep("APP_OPEN", mapOf("packageName" to "com.ss.android.ugc.aweme"), 1500L, 0.95f),
                    WorkflowStep("SCREEN_WAIT", mapOf("ms" to "2500"), 2500L, 1f),
                    WorkflowStep("SCREEN_CLICK_TEXT", mapOf("text" to "搜索"), 500L, 0.85f),
                    WorkflowStep("SCREEN_INPUT", mapOf("text" to "{关键词}"), 800L, 0.9f),
                    WorkflowStep("SCREEN_KEY", mapOf("key" to "ENTER"), 300L, 0.8f)
                )
            )
        )

        // —— 截图并分享（效率工具类）——
        registerWorkflow(
            Workflow(
                id = "screenshot_share",
                name = "截图并分享",
                category = WorkflowCategory.PRODUCTIVITY,
                steps = listOf(
                    WorkflowStep("SCREEN_KEY", mapOf("key" to "SCREENSHOT"), 500L, 0.9f),
                    WorkflowStep("SCREEN_WAIT", mapOf("ms" to "800"), 800L, 1f),
                    WorkflowStep(
                        "SCREEN_CLICK_TEXT", mapOf("text" to "分享"), 500L, 0.8f,
                        alternatives = listOf(
                            WorkflowStep("SCREEN_CLICK_TEXT", mapOf("text" to "发送"), 500L, 0.75f)
                        )
                    )
                )
            )
        )

        // —— 播放音乐（娱乐类）——
        registerWorkflow(
            Workflow(
                id = "play_music",
                name = "播放音乐",
                category = WorkflowCategory.ENTERTAINMENT,
                steps = listOf(
                    WorkflowStep("APP_OPEN", mapOf("packageName" to "com.netease.cloudmusic"), 1500L, 0.95f),
                    WorkflowStep("SCREEN_WAIT", mapOf("ms" to "2000"), 2000L, 1f),
                    WorkflowStep("SCREEN_CLICK_TEXT", mapOf("text" to "播放"), 500L, 0.85f)
                )
            )
        )

        // —— 清理缓存（系统类）——
        registerWorkflow(
            Workflow(
                id = "clear_cache",
                name = "清理缓存",
                category = WorkflowCategory.SYSTEM,
                steps = listOf(
                    WorkflowStep("SYSTEM_CLEAR_CACHE", emptyMap(), 1000L, 0.9f),
                    WorkflowStep("SCREEN_WAIT", mapOf("ms" to "500"), 500L, 1f)
                )
            )
        )

        // —— 打开应用（自定义类，通用模板）——
        registerWorkflow(
            Workflow(
                id = "open_app_generic",
                name = "打开应用",
                category = WorkflowCategory.CUSTOM,
                steps = listOf(
                    WorkflowStep("APP_OPEN", mapOf("name" to "{应用名}"), 1500L, 0.9f),
                    WorkflowStep("SCREEN_WAIT", mapOf("ms" to "2000"), 2000L, 1f)
                )
            )
        )
    }

    // ============================================================
    // 工作流注册与查询
    // ============================================================

    /**
     * 注册一个工作流。
     *
     * 若 id 已存在则覆盖更新。当工作流总数达到上限（[MAX_WORKFLOWS]）且为新注册时，
     * 按「优先弃用、其次最久未使用」的策略淘汰一个工作流。
     *
     * @param workflow 待注册的工作流
     * @return true 表示注册成功；false 表示已达上限且无可淘汰项
     */
    fun registerWorkflow(workflow: Workflow): Boolean {
        val isNew = !workflows.containsKey(workflow.id)
        if (isNew && workflows.size >= MAX_WORKFLOWS) {
            // 淘汰策略：优先弃用项，其次最久未使用
            val victim = workflows.values
                .sortedWith(compareByDescending<Workflow> { it.deprecated }.thenBy { it.lastUsed })
                .firstOrNull()
            if (victim != null) {
                workflows.remove(victim.id)
                Log.d(TAG, "工作流数达上限($MAX_WORKFLOWS)，淘汰: ${victim.name}")
            } else {
                Log.w(TAG, "工作流注册失败：已达上限且无可淘汰项")
                return false
            }
        }
        workflows[workflow.id] = workflow
        Log.d(TAG, "注册工作流: ${workflow.name} (${workflow.category}, ${workflow.steps.size}步)")
        return true
    }

    /**
     * 根据用户指令查找最匹配的工作流。
     *
     * 匹配策略：对每个未弃用工作流，基于名称与步骤参数的 token 重叠度计算匹配分，
     * 返回得分最高且超过 [MATCH_THRESHOLD] 的工作流。
     *
     * @param userCommand 用户自然语言指令
     * @return 最匹配的工作流，未找到返回 null
     */
    fun findWorkflow(userCommand: String): Workflow? {
        if (userCommand.isBlank()) return null
        val commandTokens = tokenize(userCommand)
        if (commandTokens.isEmpty()) return null

        var best: Workflow? = null
        var bestScore = 0f
        for (workflow in workflows.values) {
            if (workflow.deprecated) continue
            val score = matchScore(workflow, commandTokens)
            if (score > bestScore) {
                bestScore = score
                best = workflow
            }
        }
        return if (bestScore >= MATCH_THRESHOLD) best else null
    }

    /** 获取所有工作流（按最后使用时间降序，用于 UI 展示）。 */
    fun getAllWorkflows(): List<Workflow> =
        workflows.values.sortedByDescending { it.lastUsed }

    /** 获取已弃用的工作流列表。 */
    fun getDeprecatedWorkflows(): List<Workflow> =
        workflows.values.filter { it.deprecated }

    // ============================================================
    // 工作流优化
    // ============================================================

    /**
     * 优化指定工作流的步骤序列。
     *
     * 优化策略：
     * 1. 合并连续的 SCREEN_WAIT 步骤（累加等待时长，并依据学习值封顶）。
     * 2. 裁剪过长等待：当等待超过 [EXCESSIVE_WAIT_MS]，或显著高于学习到的典型值时，
     *    下调至学习值或默认值。
     * 3. 移除末尾多余的等待步骤（工作流末尾等待无意义）。
     * 4. 依据 [stepStats] 学习数据校正每个步骤的预估耗时与成功率。
     *
     * 优化结果会写回 [Workflow.optimizedSteps]，并在提升时提交为标准步骤。
     *
     * @param workflow 待优化的工作流
     * @return 优化结果（含变更明细与预估节省）
     */
    fun optimizeWorkflow(workflow: Workflow): OptimizationResult {
        val original = workflow.steps
        val changes = mutableListOf<OptimizationChange>()
        val optimized = mutableListOf<WorkflowStep>()

        var i = 0
        while (i < original.size) {
            val step = original[i]
            val next = original.getOrNull(i + 1)

            // 1. 合并连续等待步骤
            if (isWaitStep(step) && next != null && isWaitStep(next)) {
                val mergedMs = parseWaitMs(step) + parseWaitMs(next)
                val learned = learnedWaitFor(original, i)
                val finalMs = if (learned != null) minOf(mergedMs, learned) else mergedMs
                optimized.add(
                    WorkflowStep(
                        actionType = "SCREEN_WAIT",
                        params = mapOf("ms" to finalMs.toString()),
                        estimatedDurationMs = finalMs,
                        successRate = step.successRate
                    )
                )
                changes.add(
                    OptimizationChange(
                        ChangeType.MERGED_STEPS,
                        "合并连续等待步骤($i,${i + 1})，总等待${finalMs}ms",
                        i
                    )
                )
                i += 2
                continue
            }

            // 2. 裁剪过长等待
            if (isWaitStep(step)) {
                val ms = parseWaitMs(step)
                val learned = learnedWaitFor(original, i)
                val tooLong = ms > EXCESSIVE_WAIT_MS ||
                    (learned != null && learned > 0L && ms > learned * WAIT_LEARN_MULTIPLIER)
                if (tooLong) {
                    val newMs = learned?.takeIf { it > 0L } ?: DEFAULT_TRIMMED_WAIT_MS
                    optimized.add(
                        step.copy(
                            params = mapOf("ms" to newMs.toString()),
                            estimatedDurationMs = newMs
                        )
                    )
                    changes.add(
                        OptimizationChange(
                            ChangeType.ADJUSTED_WAIT_TIME,
                            "裁剪过长等待: ${ms}ms -> ${newMs}ms (步骤$i)",
                            i
                        )
                    )
                    i++
                    continue
                }
            }

            // 3. 移除末尾多余等待
            if (isWaitStep(step) && i == original.size - 1) {
                changes.add(
                    OptimizationChange(
                        ChangeType.REMOVED_UNNECESSARY_STEP,
                        "移除末尾多余等待步骤($i)",
                        i
                    )
                )
                i++
                continue
            }

            // 4. 依据学习数据校正预估耗时与成功率
            val stats = stepStats[step.actionType]
            val updatedDuration = if (stats != null && stats.count > 0) {
                (EMA_ALPHA * stats.avgDurationMs + (1 - EMA_ALPHA) * step.estimatedDurationMs).toLong()
            } else {
                step.estimatedDurationMs
            }
            val updatedRate = stats?.successRate ?: step.successRate
            optimized.add(step.copy(estimatedDurationMs = updatedDuration, successRate = updatedRate))
            if (stats != null && stats.count > 0) {
                changes.add(
                    OptimizationChange(
                        ChangeType.UPDATED_ESTIMATE,
                        "校正预估(步骤$i ${step.actionType}): 时长${step.estimatedDurationMs}->${updatedDuration}ms, 成功率${"%.2f".format(updatedRate)}",
                        i
                    )
                )
            }
            i++
        }

        val originalTotal = original.sumOf { it.estimatedDurationMs }
        val optimizedTotal = optimized.sumOf { it.estimatedDurationMs }
        val savingsMs = (originalTotal - optimizedTotal).coerceAtLeast(0L)
        val removedCount = (original.size - optimized.size).coerceAtLeast(0)
        val tokenSavings = removedCount * TOKENS_PER_STEP

        // 更新全局统计
        totalOptimizations++
        totalEstimatedSavingsMs += savingsMs
        totalEstimatedTokenSavings += tokenSavings

        // 写回优化步骤
        synchronized(workflow) {
            workflow.optimizedSteps = optimized
        }

        Log.d(
            TAG,
            "优化工作流[${workflow.name}]: ${original.size}步->${optimized.size}步, " +
                    "变更${changes.size}项, 节省${savingsMs}ms/${tokenSavings}token"
        )

        return OptimizationResult(original, optimized, changes, savingsMs, tokenSavings)
    }

    // ============================================================
    // 替代路径查找
    // ============================================================

    /**
     * 当某一步骤失败时，寻找替代执行路径。
     *
     * 查找优先级：
     * 1. 使用失败步骤预定义的 [WorkflowStep.alternatives]（取成功率最高者）。
     * 2. 若失败步骤为非关键步骤（等待/按键），直接跳过该步骤继续执行。
     * 3. 从同类、未弃用且包含相同动作的高评分工作流中借鉴完整路径。
     *
     * @param workflow 当前工作流
     * @param failedStepIndex 失败步骤下标
     * @return 替代路径，未找到返回 null
     */
    fun findAlternativePath(workflow: Workflow, failedStepIndex: Int): AlternativePath? {
        val steps = workflow.steps
        if (failedStepIndex !in steps.indices) return null
        val failedStep = steps[failedStepIndex]

        // 1. 优先使用预定义替代步骤
        if (failedStep.alternatives.isNotEmpty()) {
            val best = failedStep.alternatives.maxByOrNull { it.successRate } ?: return null
            val altSteps = steps.subList(0, failedStepIndex).toList() +
                    best +
                    steps.subList(failedStepIndex + 1, steps.size).toList()
            totalAlternativePathsFound++
            Log.d(TAG, "找到替代路径(替代步骤): ${failedStep.actionType} -> ${best.actionType}")
            return AlternativePath(
                steps = altSteps,
                reason = "步骤$failedStepIndex(${failedStep.actionType})失败，改用替代步骤${best.actionType}",
                estimatedSuccessRate = best.successRate
            )
        }

        // 2. 非关键步骤（等待/按键）直接跳过
        if (isWaitStep(failedStep) || failedStep.actionType == "SCREEN_KEY") {
            val altSteps = steps.subList(0, failedStepIndex).toList() +
                    steps.subList(failedStepIndex + 1, steps.size).toList()
            totalAlternativePathsFound++
            Log.d(TAG, "找到替代路径(跳过非关键步骤): ${failedStep.actionType}")
            return AlternativePath(
                steps = altSteps,
                reason = "步骤$failedStepIndex(${failedStep.actionType})为非关键步骤，跳过后继续执行",
                estimatedSuccessRate = 0.8f
            )
        }

        // 3. 从同类高评分工作流借鉴路径
        val targetAction = failedStep.actionType
        val candidate = workflows.values
            .filter {
                it.id != workflow.id && !it.deprecated && it.category == workflow.category
            }
            .filter { wf ->
                wf.steps.any { s -> s.actionType == targetAction } ||
                        wf.steps.any { s -> s.alternatives.any { a -> a.actionType == targetAction } }
            }
            .maxByOrNull { it.score.overall }

        if (candidate != null) {
            totalAlternativePathsFound++
            Log.d(TAG, "找到替代路径(借鉴工作流): ${candidate.name}")
            return AlternativePath(
                steps = candidate.steps,
                reason = "参考同类高评分工作流「${candidate.name}」的执行路径",
                estimatedSuccessRate = candidate.score.successRate
            )
        }

        Log.w(TAG, "未找到替代路径: ${workflow.name} 步骤$failedStepIndex(${failedStep.actionType})")
        return null
    }

    // ============================================================
    // 执行记录与统计
    // ============================================================

    /**
     * 记录一次工作流执行结果。
     *
     * 更新内容：
     * - 工作流级：成功/失败计数、平均耗时（EMA）、平均 Token（EMA）、最后使用时间、评分。
     * - 步骤级（[stepStats]）：按 actionType 聚合的耗时与成功率。
     *   成功时所有步骤均计入；失败时仅计入到失败步骤（含）。
     * - 步骤对时序（[pairTimings]）：相邻步骤对的典型耗时预算，用于等待优化。
     *
     * @param workflowId 工作流 id
     * @param success 是否成功
     * @param durationMs 执行耗时（毫秒）
     * @param tokensUsed 消耗 Token 数
     * @param failedStepIndex 失败步骤下标（成功时传 -1）
     */
    fun recordExecution(
        workflowId: String,
        success: Boolean,
        durationMs: Long,
        tokensUsed: Int,
        failedStepIndex: Int = -1
    ) {
        val workflow = workflows[workflowId] ?: run {
            Log.w(TAG, "记录执行失败：未知工作流 id=$workflowId")
            return
        }
        val steps = workflow.steps
        if (steps.isEmpty()) return

        // 计算实际执行过的步骤范围与单步耗时预算
        val ranCount = if (success) steps.size else (failedStepIndex + 1).coerceIn(0, steps.size)
        val perStepBudget = if (ranCount > 0) durationMs / ranCount else 0L

        // 更新工作流级统计
        synchronized(workflow) {
            if (success) workflow.successCount++ else workflow.failCount++
            workflow.avgDurationMs = if (workflow.avgDurationMs == 0L) {
                durationMs
            } else {
                (EMA_ALPHA * durationMs + (1 - EMA_ALPHA) * workflow.avgDurationMs).toLong()
            }
            workflow.avgTokens = if (workflow.avgTokens == 0) {
                tokensUsed
            } else {
                (EMA_ALPHA * tokensUsed + (1 - EMA_ALPHA) * workflow.avgTokens).toInt()
            }
            workflow.lastUsed = System.currentTimeMillis()
            workflow.score = computeScore(workflow)
        }

        // 更新步骤级统计与步骤对时序
        val ranIndices: IntRange = if (success) {
            steps.indices
        } else {
            0..failedStepIndex.coerceIn(0, steps.size - 1)
        }
        for (idx in ranIndices) {
            val step = steps[idx]
            val stats = stepStats.computeIfAbsent(step.actionType) { StepStats() }
            synchronized(stats) {
                stats.count++
                stats.avgDurationMs = if (stats.avgDurationMs == 0L) {
                    perStepBudget
                } else {
                    (EMA_ALPHA * perStepBudget + (1 - EMA_ALPHA) * stats.avgDurationMs).toLong()
                }
                // 失败时仅最后一步（失败步）计为失败，其余执行过的步骤视为成功
                val stepSuccess = success || idx < failedStepIndex
                if (stepSuccess) stats.successCount++ else stats.failCount++
            }
            // 更新相邻步骤对时序学习
            if (idx > 0) {
                val prevType = steps[idx - 1].actionType
                val key = "$prevType->${step.actionType}"
                pairTimings.compute(key) { _, existing ->
                    if (existing == null || existing == 0L) {
                        perStepBudget
                    } else {
                        (EMA_ALPHA * perStepBudget + (1 - EMA_ALPHA) * existing).toLong()
                    }
                }
            }
        }

        Log.d(
            TAG,
            "记录执行[${workflow.name}]: ${if (success) "成功" else "失败"} " +
                    "${durationMs}ms/${tokensUsed}token (累计${workflow.totalAttempts}次)"
        )
    }

    /**
     * 计算工作流评分。
     *
     * overall = successRate * 0.5 + efficiency * 0.3 + tokenEfficiency * 0.2
     * - efficiency：基于平均耗时相对 [EXPECTED_MAX_DURATION_MS] 的反比。
     * - tokenEfficiency：基于平均 Token 相对 [EXPECTED_MAX_TOKENS] 的反比。
     */
    private fun computeScore(workflow: Workflow): WorkflowScore {
        val attempts = workflow.totalAttempts
        val successRate = if (attempts > 0) workflow.successCount.toFloat() / attempts else 0f
        val efficiency = (EXPECTED_MAX_DURATION_MS - workflow.avgDurationMs)
            .coerceAtLeast(0L).toFloat() / EXPECTED_MAX_DURATION_MS
        val tokenEfficiency = (EXPECTED_MAX_TOKENS - workflow.avgTokens)
            .coerceAtLeast(0).toFloat() / EXPECTED_MAX_TOKENS
        val overall = successRate * SCORE_WEIGHT_SUCCESS +
                efficiency * SCORE_WEIGHT_EFFICIENCY +
                tokenEfficiency * SCORE_WEIGHT_TOKEN
        return WorkflowScore(
            successRate = successRate.coerceIn(0f, 1f),
            efficiency = efficiency.coerceIn(0f, 1f),
            tokenEfficiency = tokenEfficiency.coerceIn(0f, 1f),
            overall = overall.coerceIn(0f, 1f)
        )
    }

    // ============================================================
    // 工作流排名与统计
    // ============================================================

    /**
     * 获取评分最高的工作流列表。
     *
     * @param limit 返回数量上限
     * @return 按综合评分降序排列的未弃用工作流列表
     */
    fun getTopWorkflows(limit: Int): List<Workflow> {
        return workflows.values
            .filter { !it.deprecated }
            .sortedByDescending { it.score.overall }
            .take(limit.coerceAtLeast(0))
    }

    /**
     * 获取工作流统计摘要（用于 UI 展示与调试）。
     */
    fun getWorkflowStats(): String {
        val total = workflows.size
        val active = workflows.values.count { !it.deprecated }
        val deprecated = total - active
        val attempts = workflows.values.sumOf { it.totalAttempts }
        val successes = workflows.values.sumOf { it.successCount }
        val overallSuccessRate = if (attempts > 0) successes.toFloat() / attempts else 0f
        val top = getTopWorkflows(1).firstOrNull()
        return buildString {
            append("工作流统计: 共${total}个 (活跃${active}/弃用${deprecated}) | ")
            append("总执行${attempts}次, 成功${successes}次 (")
            append("%.1f%%".format(overallSuccessRate * 100))
            append(") | ")
            append("最佳工作流: ${top?.name ?: "无"} (评分")
            append("%.2f".format(top?.score?.overall ?: 0f))
            append(")")
        }
    }

    // ============================================================
    // 自动进化
    // ============================================================

    /**
     * 触发工作流自动进化。
     *
     * - 弃用：失败率 > [DEPRECATION_FAIL_RATE] 且尝试 >= [DEPRECATION_MIN_ATTEMPTS]。
     * - 提升：成功率 > [PROMOTION_SUCCESS_RATE] 且尝试 >= [PROMOTION_MIN_ATTEMPTS]，
     *   解除弃用标记，并将 [Workflow.optimizedSteps] 提交为标准步骤。
     *
     * 进化结果计入 [lastDeprecatedCount] 与 [lastPromotedCount]。
     */
    fun evolveWorkflows() {
        var deprecatedN = 0
        var promotedN = 0
        for (workflow in workflows.values) {
            synchronized(workflow) {
                val attempts = workflow.totalAttempts
                if (attempts == 0) return@synchronized
                val failRate = workflow.failCount.toFloat() / attempts
                val successRate = workflow.successCount.toFloat() / attempts

                when {
                    failRate > DEPRECATION_FAIL_RATE && attempts >= DEPRECATION_MIN_ATTEMPTS -> {
                        if (!workflow.deprecated) {
                            workflow.deprecated = true
                            deprecatedN++
                            Log.i(
                                TAG,
                                "工作流已弃用: ${workflow.name} " +
                                        "(失败率${"%.0f".format(failRate * 100)}%, 尝试${attempts}次)"
                            )
                        }
                    }
                    successRate > PROMOTION_SUCCESS_RATE && attempts >= PROMOTION_MIN_ATTEMPTS -> {
                        if (workflow.deprecated) workflow.deprecated = false
                        // 提交优化步骤为标准步骤
                        if (workflow.optimizedSteps.isNotEmpty()) {
                            workflow.steps = workflow.optimizedSteps
                            workflow.optimizedSteps = emptyList()
                        }
                        promotedN++
                        Log.i(
                            TAG,
                            "工作流已提升: ${workflow.name} " +
                                    "(成功率${"%.0f".format(successRate * 100)}%, 尝试${attempts}次)"
                        )
                    }
                }
            }
        }
        lastDeprecatedCount = deprecatedN
        lastPromotedCount = promotedN
        Log.i(TAG, "工作流进化完成: 弃用${deprecatedN}个, 提升${promotedN}个")
    }

    /**
     * 获取优化与进化的综合摘要（用于 UI 展示与调试）。
     */
    fun getOptimizationSummary(): String {
        return buildString {
            append("优化统计: 已执行${totalOptimizations}次优化 | ")
            append("累计节省${totalEstimatedSavingsMs}ms, ${totalEstimatedTokenSavings}token | ")
            append("替代路径找到${totalAlternativePathsFound}条 | ")
            append("最近进化: 弃用${lastDeprecatedCount}, 提升${lastPromotedCount} | ")
            append("步骤学习: ${stepStats.size}类动作, ${pairTimings.size}对时序")
        }
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 判断是否为等待类步骤。 */
    private fun isWaitStep(step: WorkflowStep): Boolean =
        step.actionType == "SCREEN_WAIT" || step.actionType.contains("WAIT", ignoreCase = true)

    /** 解析等待步骤的 ms 参数，缺失时回退到预估耗时。 */
    private fun parseWaitMs(step: WorkflowStep): Long =
        step.params["ms"]?.toLongOrNull() ?: step.estimatedDurationMs.coerceAtLeast(0L)

    /**
     * 查询某等待步骤学习到的典型等待时长。
     * 基于「前驱动作类型 -> 当前动作类型」的步骤对时序学习。
     *
     * @return 学习到的典型耗时预算，无数据返回 null
     */
    private fun learnedWaitFor(steps: List<WorkflowStep>, index: Int): Long? {
        val step = steps.getOrNull(index) ?: return null
        if (!isWaitStep(step)) return null
        val prevType = steps.getOrNull(index - 1)?.actionType ?: return null
        return pairTimings["$prevType->${step.actionType}"]
    }

    /**
     * 文本分词：按标点/空格切分，并对中文做双字滑窗以提升匹配召回。
     */
    private fun tokenize(text: String): Set<String> {
        val lower = text.lowercase().trim()
        if (lower.isEmpty()) return emptySet()
        val tokens = mutableSetOf<String>()
        // 按标点和空格分词
        lower.split(Regex("[\\s,。！？，、,.!?;:：；()（）\\[\\]「」【】_]+"))
            .filter { it.isNotBlank() }
            .forEach { tokens.add(it) }
        // 中文双字滑窗
        val cjk = lower.filter { it.code in 0x4E00..0x9FFF }
        for (i in 0 until cjk.length - 1) {
            tokens.add(cjk.substring(i, i + 2))
        }
        return tokens
    }

    /**
     * 计算工作流与用户指令的匹配分（0.0-1.0+）。
     * 名称重叠权重 0.6，步骤参数重叠权重 0.4，名称直接包含加 0.1。
     */
    private fun matchScore(workflow: Workflow, commandTokens: Set<String>): Float {
        val nameTokens = tokenize(workflow.name)
        val stepTokens = workflow.steps.flatMap { step ->
            val paramTokens = step.params.values.flatMap { tokenize(it) }
            tokenize(step.actionType) + paramTokens
        }.toSet()
        val workflowTokens = nameTokens + stepTokens

        val nameOverlap = if (nameTokens.isNotEmpty()) {
            nameTokens.intersect(commandTokens).size.toFloat() / nameTokens.size
        } else 0f
        val stepOverlap = if (workflowTokens.isNotEmpty()) {
            workflowTokens.intersect(commandTokens).size.toFloat() / workflowTokens.size
        } else 0f
        val directContains = commandTokens.any { workflow.name.lowercase().contains(it) }

        var score = nameOverlap * 0.6f + stepOverlap * 0.4f
        if (directContains) score += 0.1f
        return score
    }

    // ============================================================
    // 重置
    // ============================================================

    /** 清空所有工作流、学习数据与统计计数（预置模板也会被清除）。 */
    fun clear() {
        workflows.clear()
        stepStats.clear()
        pairTimings.clear()
        totalOptimizations = 0
        totalEstimatedSavingsMs = 0L
        totalEstimatedTokenSavings = 0
        totalAlternativePathsFound = 0
        lastDeprecatedCount = 0
        lastPromotedCount = 0
        Log.d(TAG, "已清空所有工作流数据与统计")
    }
}
