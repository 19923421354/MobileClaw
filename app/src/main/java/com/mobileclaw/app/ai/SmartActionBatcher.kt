package com.mobileclaw.app.ai

import com.mobileclaw.app.ai.ActionType
import com.mobileclaw.app.ai.ClawAction
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 智能动作批处理器 —— 将动作序列智能划分为可并行/串行的执行批次。
 *
 * 核心问题：AI 返回的动作序列中，部分动作相互独立（如获取系统信息、复制剪贴板、
 * 读取通知），若全部串行执行会浪费大量时间；而改变屏幕状态的动作（点击、滑动、
 * 输入、按键、打开应用）又必须严格按序执行，否则会互相干扰导致操作失败。
 *
 * 解决方案：分析动作间的依赖关系，将相互独立的动作归入同一批次并行执行，
 * 在存在依赖的批次之间插入最优等待时间，并预估整体执行耗时。
 *
 * 依赖规则：
 * - APP_OPEN 后续动作必须等待应用启动完成（应用切换依赖）
 * - SCREEN_INPUT 后接屏幕操作 / 点击后接输入：需等待输入完成或输入框获焦（输入依赖）
 * - 屏幕变更类动作（点击/滑动/按键等）后接屏幕动作：需等待屏幕稳定（屏幕变更依赖）
 * - SCREEN_WAIT 动作合并到前序批次，延长其等待时间
 * - 非屏幕动作（SYSTEM_*、CLIPBOARD_*、NOTIFY_* 等）不依赖屏幕状态，可与任意动作并行
 *
 * 并行规则：
 * - 非屏幕动作可与任意动作并行
 * - 屏幕变更类动作之间必须串行
 * - 多个 SCREEN_SCREENSHOT 可并行截屏
 * - SCREEN_WAIT 不参与并行（由合并逻辑处理）
 *
 * 耗时预估（单动作）：
 * - SCREEN_CLICK=200ms, SCREEN_CLICK_TEXT=300ms, SCREEN_INPUT=300ms, SCREEN_SWIPE=400ms
 * - APP_OPEN=2000ms, SCREEN_WAIT=配置ms, SCREEN_KEY=100ms, SCREEN_SCREENSHOT=500ms
 * - 其他动作默认 300ms
 */
class SmartActionBatcher {

    /**
     * 动作依赖类型，描述当前动作对前序动作的依赖关系。
     */
    enum class DependencyType {
        /** 无依赖，可与前序动作并行执行。 */
        NONE,
        /** 屏幕状态变更依赖：前序动作改变了屏幕，后续需等待屏幕稳定。 */
        SCREEN_CHANGE,
        /** 应用切换依赖：前序动作打开了新应用，后续需等待启动完成。 */
        APP_CHANGE,
        /** 输入依赖：前序为输入或点击输入框，后续需等待输入完成或获取焦点。 */
        INPUT_DEPENDENCY,
        /** 需要等待：显式 SCREEN_WAIT 动作，应合并到前序批次。 */
        WAIT_REQUIRED
    }

    /**
     * 单个动作的依赖关系描述。
     *
     * @property action 当前动作
     * @property dependsOn 所依赖的前序动作（首个动作为 null）
     * @property dependencyType 依赖类型
     */
    data class ActionDependency(
        val action: ClawAction,
        val dependsOn: ClawAction?,
        val dependencyType: DependencyType
    )

    /**
     * 动作批次：一组可并行或需串行执行的动作。
     *
     * @property actions 批次内动作列表（保持原始顺序）
     * @property estimatedDurationMs 批次预估执行耗时（毫秒，含前置等待）
     * @property canParallelize 批次内动作是否可并行执行
     * @property reason 批次划分原因与执行说明
     */
    data class Batch(
        val actions: List<ClawAction>,
        val estimatedDurationMs: Long,
        val canParallelize: Boolean,
        val reason: String
    )

    /**
     * 批次执行计划：完整的批次划分与耗时预估。
     *
     * @property batches 所有批次列表（按执行顺序）
     * @property totalEstimatedMs 整体预估耗时（毫秒）
     * @property parallelizableCount 可并行批次数
     * @property sequentialCount 串行批次数
     */
    data class BatchPlan(
        val batches: List<Batch>,
        val totalEstimatedMs: Long,
        val parallelizableCount: Int,
        val sequentialCount: Int
    )

    companion object {
        /** 默认最大批次大小。 */
        private const val DEFAULT_MAX_BATCH_SIZE = 5

        /** SCREEN_WAIT 未指定 ms 时的默认等待时长。 */
        private const val DEFAULT_WAIT_MS = 500L

        /** 未明确耗时的动作默认耗时。 */
        private const val DEFAULT_DURATION_MS = 300L

        /** 应用切换后建议等待时长（毫秒）。 */
        private const val APP_CHANGE_WAIT_MS = 2000L

        /** 屏幕状态变更后建议等待时长（毫秒）。 */
        private const val SCREEN_CHANGE_WAIT_MS = 500L

        /** 输入依赖建议等待时长（毫秒）。 */
        private const val INPUT_DEPENDENCY_WAIT_MS = 500L
    }

    /** 累计已规划动作总数。 */
    private val totalActionsPlanned = AtomicInteger(0)

    /** 累计已生成批次总数。 */
    private val totalBatchesPlanned = AtomicInteger(0)

    /** 累计可并行批次总数。 */
    private val totalParallelizableBatches = AtomicInteger(0)

    /** 累计串行批次总数。 */
    private val totalSequentialBatches = AtomicInteger(0)

    /** 累计预估总耗时（毫秒）。 */
    private val totalEstimatedDurationMs = AtomicLong(0L)

    /** 最近一次批次规划结果，用于统计查询。 */
    @Volatile
    private var lastPlan: BatchPlan? = null

    // =========================================================================
    //  耗时预估
    // =========================================================================

    /**
     * 估算单个动作的执行耗时。
     *
     * 各类型动作的耗时经验值：
     * - SCREEN_CLICK=200ms, SCREEN_CLICK_TEXT=300ms, SCREEN_INPUT=300ms, SCREEN_SWIPE=400ms
     * - APP_OPEN=2000ms, SCREEN_WAIT=配置ms, SCREEN_KEY=100ms, SCREEN_SCREENSHOT=500ms
     * - 其他动作默认 300ms
     *
     * @param action 待估算的动作
     * @return 预估耗时（毫秒）
     */
    fun estimateDuration(action: ClawAction): Long {
        val type = action.type ?: return DEFAULT_DURATION_MS
        return when (type) {
            ActionType.SCREEN_CLICK -> 200L
            ActionType.SCREEN_CLICK_TEXT -> 300L
            ActionType.SCREEN_INPUT -> 300L
            ActionType.SCREEN_SWIPE -> 400L
            ActionType.APP_OPEN -> 2000L
            ActionType.SCREEN_WAIT -> action.ms ?: DEFAULT_WAIT_MS
            ActionType.SCREEN_KEY -> 100L
            ActionType.SCREEN_SCREENSHOT -> 500L
            else -> DEFAULT_DURATION_MS
        }
    }

    // =========================================================================
    //  依赖分析
    // =========================================================================

    /**
     * 分析动作序列中每个动作对其前序动作的依赖关系。
     *
     * 首个动作无依赖（[dependsOn] 为 null，类型为 [DependencyType.NONE]）；
     * 后续动作依据与前序动作的类型组合判定依赖类型。
     *
     * @param actions 动作列表（保持原始顺序）
     * @return 依赖关系列表，与输入动作一一对应
     */
    fun analyzeDependencies(actions: List<ClawAction>): List<ActionDependency> {
        if (actions.isEmpty()) return emptyList()

        val result = ArrayList<ActionDependency>(actions.size)
        actions.forEachIndexed { index, action ->
            if (index == 0) {
                result.add(ActionDependency(action, null, DependencyType.NONE))
            } else {
                val prev = actions[index - 1]
                result.add(ActionDependency(action, prev, classifyDependency(prev, action)))
            }
        }
        return result
    }

    /**
     * 判定 [current] 对 [prev] 的依赖类型。
     *
     * 优先级：显式等待 > 应用切换 > 输入依赖 > 屏幕变更 > 无依赖。
     */
    private fun classifyDependency(prev: ClawAction, current: ClawAction): DependencyType {
        val prevType = prev.type ?: return DependencyType.NONE
        val currType = current.type ?: return DependencyType.NONE

        // SCREEN_WAIT 动作：标记为需等待（由批次合并逻辑处理）
        if (currType == ActionType.SCREEN_WAIT) return DependencyType.WAIT_REQUIRED

        // 非屏幕动作不依赖屏幕状态，可与任意动作并行
        if (isNonScreen(currType)) return DependencyType.NONE

        // 应用切换依赖：APP_OPEN 后续动作必须等待应用启动完成
        if (prevType == ActionType.APP_OPEN) return DependencyType.APP_CHANGE

        // 输入依赖：输入后立即操作需等待输入完成；点击后输入需等待输入框获焦
        if (prevType == ActionType.SCREEN_INPUT && isScreenChanging(currType)) {
            return DependencyType.INPUT_DEPENDENCY
        }
        if (isClickLike(prevType) && currType == ActionType.SCREEN_INPUT) {
            return DependencyType.INPUT_DEPENDENCY
        }

        // 屏幕状态变更依赖：屏幕变更类动作后，后续屏幕动作需等待屏幕稳定
        if (isScreenChanging(prevType)) return DependencyType.SCREEN_CHANGE

        return DependencyType.NONE
    }

    /** 判断动作类型是否为屏幕变更类（会改变屏幕状态，必须串行）。 */
    private fun isScreenChanging(type: ActionType): Boolean = when (type) {
        ActionType.SCREEN_CLICK,
        ActionType.SCREEN_CLICK_TEXT,
        ActionType.SCREEN_LONG_CLICK,
        ActionType.SCREEN_DOUBLE_CLICK,
        ActionType.SCREEN_FIND_AND_CLICK,
        ActionType.SCREEN_SWIPE,
        ActionType.SCREEN_SCROLL_TO_TEXT,
        ActionType.SCREEN_INPUT,
        ActionType.SCREEN_KEY,
        ActionType.APP_OPEN,
        ActionType.APP_CLOSE -> true
        else -> false
    }

    /** 判断动作类型是否为点击类（用于输入依赖判定）。 */
    private fun isClickLike(type: ActionType): Boolean = when (type) {
        ActionType.SCREEN_CLICK,
        ActionType.SCREEN_CLICK_TEXT,
        ActionType.SCREEN_LONG_CLICK,
        ActionType.SCREEN_DOUBLE_CLICK,
        ActionType.SCREEN_FIND_AND_CLICK -> true
        else -> false
    }

    /** 判断动作类型是否为非屏幕动作（不改变屏幕状态，可与任意动作并行）。 */
    private fun isNonScreen(type: ActionType): Boolean = when (type) {
        ActionType.SYSTEM_GET_INFO,
        ActionType.SYSTEM_KILL_PROCESS,
        ActionType.SYSTEM_CLEAR_CACHE,
        ActionType.SYSTEM_SET_VOLUME,
        ActionType.SYSTEM_SET_BRIGHTNESS,
        ActionType.CLIPBOARD_COPY,
        ActionType.CLIPBOARD_PASTE,
        ActionType.NOTIFY_READ,
        ActionType.NOTIFY_SEND,
        ActionType.MEDIA_CONTROL,
        ActionType.SHELL_EXEC,
        ActionType.FILE_READ,
        ActionType.FILE_WRITE,
        ActionType.TIMER_SET,
        ActionType.APP_LIST,
        ActionType.APP_SEARCH,
        ActionType.APP_INSTALL,
        ActionType.APP_UNINSTALL -> true
        else -> false
    }

    // =========================================================================
    //  并行判定
    // =========================================================================

    /**
     * 判断两个动作是否可以并行执行。
     *
     * 规则：
     * - SCREEN_WAIT 不参与并行（由合并逻辑处理）
     * - 多个 SCREEN_SCREENSHOT 可并行截屏
     * - 任一为屏幕变更类动作 → 必须串行
     * - 其余（非屏幕动作之间、屏幕只读动作之间、或二者混合）均可并行
     *
     * @param a1 动作一
     * @param a2 动作二
     * @return true 表示可并行执行
     */
    fun canParallelize(a1: ClawAction, a2: ClawAction): Boolean {
        val t1 = a1.type
        val t2 = a2.type
        // 无法识别类型的动作保守串行
        if (t1 == null || t2 == null) return false

        // SCREEN_WAIT 不参与并行（由合并逻辑处理）
        if (t1 == ActionType.SCREEN_WAIT || t2 == ActionType.SCREEN_WAIT) return false

        // 多个 SCREEN_SCREENSHOT 可以并行截屏
        if (t1 == ActionType.SCREEN_SCREENSHOT && t2 == ActionType.SCREEN_SCREENSHOT) return true

        // 任一为屏幕变更类动作 → 必须串行
        if (isScreenChanging(t1) || isScreenChanging(t2)) return false

        // 其余（非屏幕动作之间、屏幕只读动作之间、或二者混合）均可并行
        return true
    }

    // =========================================================================
    //  批次规划
    // =========================================================================

    /**
     * 将动作序列规划为若干执行批次。
     *
     * 规划流程：
     * 1. 分析每个动作对前序动作的依赖关系；
     * 2. 将相互独立（可并行）的连续动作归入同一批次，受 [maxBatchSize] 限制；
     * 3. 遇到依赖（屏幕变更/应用切换/输入依赖）时关闭当前批次并开启新批次，
     *    在新批次前插入最优等待时间；
     * 4. SCREEN_WAIT 动作合并到当前批次，延长其等待时间；
     * 5. 预估各批次及整体执行耗时。
     *
     * @param actions 待规划的动作列表
     * @param maxBatchSize 单个批次最大动作数（不小于 1）
     * @return 批次执行计划
     */
    fun planBatches(
        actions: List<ClawAction>,
        maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
    ): BatchPlan {
        if (actions.isEmpty()) {
            val emptyPlan = BatchPlan(emptyList(), 0L, 0, 0)
            lastPlan = emptyPlan
            return emptyPlan
        }

        val effectiveMaxSize = maxBatchSize.coerceAtLeast(1)
        val dependencies = analyzeDependencies(actions)

        // 分组缓冲：每组保留动作顺序
        val groups = mutableListOf<MutableList<ClawAction>>()
        val groupWaitBefore = mutableListOf<Long>()
        val groupDepDescription = mutableListOf<String>()

        for (dep in dependencies) {
            val action = dep.action
            val currentGroup = groups.lastOrNull()?.takeIf { it.isNotEmpty() }

            // SCREEN_WAIT 优先合并到当前批次（延长其等待时间）
            if (action.type == ActionType.SCREEN_WAIT && currentGroup != null) {
                currentGroup.add(action)
                continue
            }

            // 判断是否需要开启新批次
            val startNewBatch = when {
                currentGroup == null -> true
                dep.dependencyType != DependencyType.NONE -> true
                currentGroup.size >= effectiveMaxSize -> true
                else -> {
                    val canJoin = currentGroup.all { existing -> canParallelize(existing, action) }
                    !canJoin
                }
            }

            if (startNewBatch) {
                // 非首组的等待时间取决于依赖类型
                val waitMs = if (groups.isEmpty()) 0L else waitForDependency(dep.dependencyType)
                groups.add(mutableListOf(action))
                groupWaitBefore.add(waitMs)
                groupDepDescription.add(describeDependency(dep.dependencyType))
            } else {
                currentGroup!!.add(action)
            }
        }

        // 构建最终批次列表
        val batches = groups.mapIndexed { i, groupActions ->
            val parallelizable = computeCanParallelize(groupActions)
            val execDuration = computeBatchDuration(groupActions, parallelizable)
            val waitBefore = groupWaitBefore[i]
            val reason = buildBatchReason(
                groupActions, parallelizable, groupDepDescription[i], waitBefore
            )
            Batch(groupActions.toList(), execDuration + waitBefore, parallelizable, reason)
        }

        val totalMs = batches.sumOf { it.estimatedDurationMs }
        val parallelizableCount = batches.count { it.canParallelize }
        val sequentialCount = batches.count { !it.canParallelize }

        val plan = BatchPlan(batches, totalMs, parallelizableCount, sequentialCount)

        // 更新累计统计
        totalActionsPlanned.addAndGet(actions.size)
        totalBatchesPlanned.addAndGet(batches.size)
        totalParallelizableBatches.addAndGet(parallelizableCount)
        totalSequentialBatches.addAndGet(sequentialCount)
        totalEstimatedDurationMs.addAndGet(totalMs)
        lastPlan = plan

        return plan
    }

    /** 根据依赖类型返回批次间应插入的最优等待时长。 */
    private fun waitForDependency(depType: DependencyType): Long = when (depType) {
        DependencyType.APP_CHANGE -> APP_CHANGE_WAIT_MS
        DependencyType.SCREEN_CHANGE -> SCREEN_CHANGE_WAIT_MS
        DependencyType.INPUT_DEPENDENCY -> INPUT_DEPENDENCY_WAIT_MS
        DependencyType.WAIT_REQUIRED -> 0L
        DependencyType.NONE -> 0L
    }

    /** 将依赖类型转为中文描述。 */
    private fun describeDependency(depType: DependencyType): String = when (depType) {
        DependencyType.NONE -> "无依赖"
        DependencyType.SCREEN_CHANGE -> "屏幕状态变更"
        DependencyType.APP_CHANGE -> "应用切换"
        DependencyType.INPUT_DEPENDENCY -> "输入依赖"
        DependencyType.WAIT_REQUIRED -> "显式等待"
    }

    /** 判断批次内非等待动作是否两两可并行（动作数大于 1 才可能为真）。 */
    private fun computeCanParallelize(actions: List<ClawAction>): Boolean {
        val execActions = actions.filter { it.type != ActionType.SCREEN_WAIT }
        if (execActions.size <= 1) return false
        for (i in execActions.indices) {
            for (j in i + 1 until execActions.size) {
                if (!canParallelize(execActions[i], execActions[j])) return false
            }
        }
        return true
    }

    /**
     * 计算批次纯执行耗时（不含前置等待）。
     *
     * 可并行批次取各动作耗时的最大值；串行批次取各动作耗时之和；
     * 合并的 SCREEN_WAIT 等待时间累加到结果。
     */
    private fun computeBatchDuration(actions: List<ClawAction>, canParallelize: Boolean): Long {
        val execActions = actions.filter { it.type != ActionType.SCREEN_WAIT }
        val waitMs = actions.filter { it.type == ActionType.SCREEN_WAIT }
            .sumOf { it.ms ?: DEFAULT_WAIT_MS }
        val execDuration = when {
            execActions.isEmpty() -> 0L
            canParallelize -> execActions.maxOf { estimateDuration(it) }
            else -> execActions.sumOf { estimateDuration(it) }
        }
        return execDuration + waitMs
    }

    /** 构建批次的中文说明（含前置等待、执行方式、合并等待）。 */
    private fun buildBatchReason(
        actions: List<ClawAction>,
        canParallelize: Boolean,
        depDescription: String,
        waitBeforeMs: Long
    ): String = buildString {
        if (waitBeforeMs > 0L) {
            append("前置等待${waitBeforeMs}ms（$depDescription）；")
        }
        val execActions = actions.filter { it.type != ActionType.SCREEN_WAIT }
        val mergedWaitCount = actions.size - execActions.size
        if (execActions.isEmpty()) {
            append("纯等待批次")
        } else {
            when {
                canParallelize -> append("并行执行${execActions.size}个独立动作")
                execActions.size == 1 -> append("串行执行：${describeActionBrief(execActions.first())}")
                else -> append("串行执行${execActions.size}个动作")
            }
            if (mergedWaitCount > 0) {
                append("（含${mergedWaitCount}个合并等待）")
            }
        }
    }

    /** 获取动作的简短中文描述，用于批次说明。 */
    private fun describeActionBrief(action: ClawAction): String {
        val type = action.type
        return type?.description ?: action.actionName.ifBlank { "未知动作" }
    }

    // =========================================================================
    //  统计
    // =========================================================================

    /**
     * 获取批次规划统计摘要。
     *
     * 包含最近一次规划的批次数、并行/串行分布与预估耗时，
     * 以及累计规划的动作数、批次数与总耗时。
     *
     * @return 统计摘要字符串
     */
    fun getBatchStats(): String = buildString {
        append("动作批处理统计: ")
        val plan = lastPlan
        if (plan == null) {
            append("尚未规划任何批次")
            return@buildString
        }
        append("最近规划: 批次${plan.batches.size}(并行${plan.parallelizableCount}/串行${plan.sequentialCount}) ")
        append("预估${plan.totalEstimatedMs}ms; ")
        append("累计: 动作${totalActionsPlanned.get()} 批次${totalBatchesPlanned.get()} ")
        append("并行${totalParallelizableBatches.get()} 串行${totalSequentialBatches.get()} ")
        append("总耗时${totalEstimatedDurationMs.get()}ms")
    }
}
