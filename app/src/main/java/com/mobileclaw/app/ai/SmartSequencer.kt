package com.mobileclaw.app.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 智能动作排序器 —— 自动优化动作序列，插入必要的等待。
 *
 * 核心问题：AI 返回的动作序列经常缺少必要的等待，导致：
 * - 打开应用后立即点击 → 页面还没加载完，点击失败
 * - 输入文本后立即点击发送 → 文本还没输入完，发送了空内容
 * - 点击后立即滑动 → 点击效果还没生效，滑动打乱了操作
 *
 * 解决方案：分析相邻动作的依赖关系，自动在需要等待的位置插入 SCREEN_WAIT。
 *
 * 依赖规则：
 * - APP_OPEN 后必须等待（应用需要时间启动）
 * - SCREEN_CLICK_TEXT/SCREEN_CLICK 后，如果下一个是 SCREEN_INPUT → 等待（等输入框获取焦点）
 * - SCREEN_INPUT 后，如果下一个是 SCREEN_CLICK_TEXT → 等待（等输入完成）
 * - SCREEN_SWIPE/SCREEN_SCROLL_TO_TEXT 后 → 等待（等页面滚动稳定）
 * - SCREEN_KEY 后，如果下一个不是 SCREEN_KEY → 等待（等按键效果生效）
 *
 * 额外优化：
 * - 合并连续的 SCREEN_KEY 动作（如音量减5次 → 一次设置音量）
 * - 移除冗余的 SCREEN_WAIT（连续多个 WAIT 合并为最大的一个）
 * - 检测并移除死循环模式（A→B→A→B 重复）
 */
object SmartSequencer {

    /** 依赖规则：键 = 前一个动作类型，值 = 后一个动作类型 → 需要等待(ms)。 */
    private val DEPENDENCY_WAITS = mapOf(
        // 打开应用后必须等待
        ActionType.APP_OPEN to ActionType.SCREEN_CLICK to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_CLICK_TEXT to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_INPUT to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_FIND_AND_CLICK to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_SWIPE to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_SCROLL_TO_TEXT to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_DOUBLE_CLICK to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_LONG_CLICK to 2000L,
        ActionType.APP_OPEN to ActionType.SCREEN_GET_TEXT to 1500L,
        ActionType.APP_OPEN to ActionType.SCREEN_TEXT_EXISTS to 1500L,
        // 点击后输入需要等待（等输入框获取焦点）
        ActionType.SCREEN_CLICK to ActionType.SCREEN_INPUT to 500L,
        ActionType.SCREEN_CLICK_TEXT to ActionType.SCREEN_INPUT to 500L,
        ActionType.SCREEN_FIND_AND_CLICK to ActionType.SCREEN_INPUT to 500L,
        // 输入后点击需要等待（等输入完成）
        ActionType.SCREEN_INPUT to ActionType.SCREEN_CLICK to 500L,
        ActionType.SCREEN_INPUT to ActionType.SCREEN_CLICK_TEXT to 500L,
        // 滑动/滚动后需要等待（等页面稳定）
        ActionType.SCREEN_SWIPE to ActionType.SCREEN_CLICK to 800L,
        ActionType.SCREEN_SWIPE to ActionType.SCREEN_CLICK_TEXT to 800L,
        ActionType.SCREEN_SWIPE to ActionType.SCREEN_INPUT to 800L,
        ActionType.SCREEN_SWIPE to ActionType.SCREEN_FIND_AND_CLICK to 800L,
        ActionType.SCREEN_SCROLL_TO_TEXT to ActionType.SCREEN_CLICK to 800L,
        ActionType.SCREEN_SCROLL_TO_TEXT to ActionType.SCREEN_CLICK_TEXT to 500L,
        // 按键后非按键操作需要等待
        ActionType.SCREEN_KEY to ActionType.SCREEN_CLICK to 500L,
        ActionType.SCREEN_KEY to ActionType.SCREEN_CLICK_TEXT to 500L,
        ActionType.SCREEN_KEY to ActionType.SCREEN_INPUT to 500L,
        ActionType.SCREEN_KEY to ActionType.APP_OPEN to 500L
    )

    /** 默认等待时间（当依赖规则未明确指定时）。 */
    private const val DEFAULT_WAIT_MS = 500L

    /**
     * 优化动作序列：插入必要的等待，合并冗余动作。
     *
     * @param actions 原始动作列表
     * @return 优化后的动作列表（可能包含插入的 SCREEN_WAIT）
     */
    fun optimize(actions: List<ClawAction>): List<ClawAction> {
        if (actions.size <= 1) return actions

        val optimized = mutableListOf<ClawAction>()
        var prevType: ActionType? = null

        for (action in actions) {
            val currentType = action.type

            // 跳过 ANSWER（不参与排序优化）
            if (currentType == ActionType.ANSWER) {
                optimized.add(action)
                continue
            }

            // 检查是否需要在当前动作前插入等待
            if (prevType != null && currentType != null) {
                val waitMs = DEPENDENCY_WAITS[prevType to currentType]
                if (waitMs != null && waitMs > 0) {
                    // 检查前一个动作是否已经是 WAIT（避免连续 WAIT）
                    val lastAction = optimized.lastOrNull()
                    if (lastAction?.type == ActionType.SCREEN_WAIT) {
                        // 如果前一个 WAIT 时间不够，取最大值
                        val existingMs = lastAction.ms ?: 0L
                        if (existingMs < waitMs) {
                            optimized[optimized.lastIndex] = ClawAction(
                                actionName = ActionType.SCREEN_WAIT.name,
                                params = JsonObject(mapOf("ms" to JsonPrimitive(waitMs))),
                                description = "智能等待${waitMs}ms"
                            )
                        }
                    } else if (lastAction?.type != ActionType.SCREEN_WAIT) {
                        // 插入智能等待
                        optimized.add(ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(waitMs))),
                            description = "智能等待${waitMs}ms"
                        ))
                    }
                }
            }

            optimized.add(action)
            prevType = currentType
        }

        // 后处理：合并连续的 SCREEN_KEY（如连续音量键）
        val merged = mergeConsecutiveKeys(optimized)

        // 后处理：检测死循环模式
        val deduped = removeLoopPatterns(merged)

        return deduped
    }

    /**
     * 合并连续的相同 SCREEN_KEY 动作。
     * 例如：VOLUME_DOWN × 5 → 一个 VOLUME_DOWN（执行器内部会处理多次按键）
     */
    private fun mergeConsecutiveKeys(actions: List<ClawAction>): List<ClawAction> {
        if (actions.size <= 2) return actions

        val result = mutableListOf<ClawAction>()
        var i = 0
        while (i < actions.size) {
            val current = actions[i]
            if (current.type == ActionType.SCREEN_KEY) {
                // 计算连续相同按键的数量
                var count = 1
                while (i + count < actions.size &&
                       actions[i + count].type == ActionType.SCREEN_KEY &&
                       actions[i + count].keyName == current.keyName) {
                    count++
                }
                if (count > 1) {
                    // 合并为一个动作，在 description 中注明次数
                    result.add(current.copy(
                        description = "${current.description}（×${count}次）"
                    ))
                    i += count
                    continue
                }
            }
            result.add(current)
            i++
        }
        return result
    }

    /**
     * 检测并移除简单的死循环模式（A→B→A→B 重复）。
     * 如果检测到超过2次的 A→B 循环，截断到第二次。
     */
    private fun removeLoopPatterns(actions: List<ClawAction>): List<ClawAction> {
        if (actions.size < 6) return actions

        val result = actions.toMutableList()
        // 检测 A→B→A→B→A→B 模式（至少3次循环）
        for (start in result.indices) {
            if (start + 5 >= result.size) break
            val a = result[start]
            val b = result[start + 1]
            // 跳过 WAIT 动作
            if (a.type == ActionType.SCREEN_WAIT || b.type == ActionType.SCREEN_WAIT) continue

            var loopCount = 0
            var idx = start
            while (idx + 1 < result.size) {
                val x = result[idx]
                val y = result[idx + 1]
                // 比较 actionName 和主要参数
                if (sameAction(x, a) && sameAction(y, b)) {
                    loopCount++
                    idx += 2
                } else {
                    break
                }
            }

            if (loopCount >= 3) {
                // 检测到死循环，截断到第二次循环
                val keepCount = start + 4 // 保留 A→B→A→B
                val removed = result.size - keepCount
                if (removed > 0) {
                    while (result.size > keepCount) {
                        result.removeAt(result.lastIndex)
                    }
                    // 添加一个 ANSWER 表示检测到循环
                    result.add(ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf(
                            "text" to JsonPrimitive("检测到操作循环，已自动终止。可能目标元素不存在或页面未正确加载。")
                        )),
                        description = "死循环检测"
                    ))
                    return result
                }
            }
        }
        return result
    }

    /** 比较两个动作是否实质相同（类型+关键参数）。 */
    private fun sameAction(a: ClawAction, b: ClawAction): Boolean {
        if (a.type != b.type) return false
        return when (a.type) {
            ActionType.SCREEN_CLICK_TEXT, ActionType.SCREEN_FIND_AND_CLICK,
            ActionType.SCREEN_SCROLL_TO_TEXT, ActionType.SCREEN_INPUT ->
                a.text == b.text
            ActionType.SCREEN_CLICK -> a.x == b.x && a.y == b.y
            ActionType.SCREEN_SWIPE -> a.swipeDirectionName == b.swipeDirectionName
            ActionType.SCREEN_KEY -> a.keyName == b.keyName
            ActionType.APP_OPEN -> a.packageName == b.packageName
            else -> a.actionName == b.actionName
        }
    }

    /**
     * 估算优化后的动作序列中插入的等待总时间。
     * 用于 UI 展示和性能分析。
     */
    fun estimateWaitTime(actions: List<ClawAction>): Long {
        return actions.filter { it.type == ActionType.SCREEN_WAIT }
            .sumOf { it.ms ?: 0L }
    }
}
