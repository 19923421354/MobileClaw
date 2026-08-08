package com.mobileclaw.app.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 执行管道优化器 —— 在动作分发前进行参数预检和智能路由。
 *
 * 核心问题：大量动作失败源于参数缺失或格式错误（如缺少 text、包名为空），
 * 这些错误本可以在调用执行器前就拦截，避免浪费无障碍调用和时间。
 *
 * 功能：
 * - 参数预检：执行前验证必填参数是否齐全且格式正确
 * - 参数修正：自动修正常见参数问题（如包名大小写、文本去空格）
 * - 执行建议：对于可能失败的动作提供替代建议
 * - 动作去重：检测连续重复的相同动作，避免无效重试
 */
object ExecutionPipelineOptimizer {

    /** 预检结果。 */
    sealed class PreCheckResult {
        /** 通过：可以执行。 */
        data class Pass(val correctedAction: ClawAction) : PreCheckResult()

        /** 失败：参数有误，不应执行。 */
        data class Fail(val reason: String, val suggestion: String? = null) : PreCheckResult()

        /** 跳过：动作重复或无意义，跳过执行。 */
        data class Skip(val reason: String) : PreCheckResult()
    }

    /** 记录最近执行的动作，用于去重检测。 */
    private val recentActions = ArrayDeque<RecentAction>()

    /** 单条最近动作记录。 */
    private data class RecentAction(
        val actionKey: String,
        val timestamp: Long,
        val success: Boolean
    )

    /** 去重检测窗口（毫秒）。 */
    private const val DEDUP_WINDOW_MS = 3000L

    /** 最大连续失败次数（同一动作）。 */
    private const val MAX_CONSECUTIVE_FAILURES = 2

    /**
     * 执行前预检：验证参数完整性和格式正确性。
     *
     * @param action 待执行的动作
     * @return 预检结果
     */
    fun preCheck(action: ClawAction): PreCheckResult {
        // 1. 去重检测：连续相同动作且最近失败 -> 跳过
        val actionKey = buildActionKey(action)
        val now = System.currentTimeMillis()
        val recent = recentActions.toList()
        val recentSameActions = recent.filter {
            it.actionKey == actionKey && now - it.timestamp < DEDUP_WINDOW_MS
        }
        val recentFailures = recentSameActions.count { !it.success }
        if (recentFailures >= MAX_CONSECUTIVE_FAILURES) {
            return PreCheckResult.Skip(
                "动作在${DEDUP_WINDOW_MS}ms内已失败${recentFailures}次，跳过避免无效重试"
            )
        }

        // 2. 参数预检（按动作类型）
        return when (action.type) {
            ActionType.SCREEN_CLICK -> {
                val x = action.x
                val y = action.y
                if (x == null || y == null) {
                    PreCheckResult.Fail(
                        "SCREEN_CLICK 缺少 x/y 坐标参数",
                        "改用 SCREEN_CLICK_TEXT{text:\"按钮文字\"} 按文本点击"
                    )
                } else if (x < 0 || y < 0) {
                    PreCheckResult.Fail("坐标不能为负数: x=$x, y=$y")
                } else {
                    PreCheckResult.Pass(action)
                }
            }

            ActionType.SCREEN_CLICK_TEXT -> {
                val text = action.text
                when {
                    text.isNullOrEmpty() ->
                        PreCheckResult.Fail("SCREEN_CLICK_TEXT 缺少 text 参数")
                    text.isBlank() ->
                        PreCheckResult.Fail("SCREEN_CLICK_TEXT 的 text 参数为空白")
                    text.length > 50 ->
                        PreCheckResult.Fail(
                            "text 参数过长(${text.length}字符)，建议缩短到50字符以内",
                            "只保留按钮的关键文字，去掉修饰词"
                        )
                    else -> PreCheckResult.Pass(action)
                }
            }

            ActionType.SCREEN_INPUT -> {
                val text = action.text
                when {
                    text == null ->
                        PreCheckResult.Fail("SCREEN_INPUT 缺少 text 参数")
                    text.isEmpty() ->
                        PreCheckResult.Skip("text 参数为空字符串，无需输入")
                    text.length > 500 ->
                        PreCheckResult.Fail(
                            "输入文本过长(${text.length}字符)，可能超出输入框限制",
                            "分段输入或精简内容"
                        )
                    else -> PreCheckResult.Pass(action)
                }
            }

            ActionType.APP_OPEN -> {
                val pkg = action.packageName
                val name = action.name
                when {
                    pkg.isNullOrEmpty() && name.isNullOrEmpty() ->
                        PreCheckResult.Fail(
                            "APP_OPEN 缺少 packageName 或 name 参数",
                            "使用 packageName:\"com.tencent.mm\" 或 name:\"微信\""
                        )
                    !pkg.isNullOrEmpty() && pkg.contains(" ") ->
                        PreCheckResult.Fail("包名不能包含空格: $pkg")
                    !pkg.isNullOrEmpty() && !pkg.contains(".") ->
                        PreCheckResult.Fail(
                            "包名格式可能不正确（应包含至少一个点）: $pkg",
                            "微信=com.tencent.mm 抖音=com.ss.android.ugc.aweme"
                        )
                    else -> PreCheckResult.Pass(action)
                }
            }

            ActionType.SCREEN_SWIPE -> {
                val dir = action.swipeDirection
                val hasCoords = action.x1 != null && action.y1 != null &&
                                action.x2 != null && action.y2 != null
                if (dir == null && !hasCoords) {
                    PreCheckResult.Fail(
                        "SCREEN_SWIPE 缺少 direction 或 x1/y1/x2/y2 参数",
                        "使用 direction:\"UP\" 或指定坐标 x1,y1,x2,y2"
                    )
                } else {
                    PreCheckResult.Pass(action)
                }
            }

            ActionType.SCREEN_KEY -> {
                if (action.key == null) {
                    PreCheckResult.Fail(
                        "SCREEN_KEY 缺少有效 key 参数",
                        "可用: BACK, HOME, RECENTS, VOLUME_UP, VOLUME_DOWN"
                    )
                } else {
                    PreCheckResult.Pass(action)
                }
            }

            ActionType.SCREEN_WAIT -> {
                val ms = action.ms
                if (ms == null || ms <= 0) {
                    PreCheckResult.Fail("SCREEN_WAIT 缺少有效 ms 参数（需大于0）")
                } else if (ms > 10000) {
                    PreCheckResult.Skip("等待时间过长(${ms}ms)，自动截断为3000ms").let {
                        PreCheckResult.Pass(action.copy(
                            params = JsonObject(action.params.toMap().mapValues { (k, v) ->
                                if (k == "ms") JsonPrimitive(3000L) else v
                            })
                        ))
                    }
                } else {
                    PreCheckResult.Pass(action)
                }
            }

            ActionType.SCREEN_FIND_AND_CLICK -> {
                val text = action.text
                if (text.isNullOrEmpty()) {
                    PreCheckResult.Fail("SCREEN_FIND_AND_CLICK 缺少 text 参数")
                } else {
                    PreCheckResult.Pass(action)
                }
            }

            ActionType.ANSWER -> PreCheckResult.Pass(action)

            else -> PreCheckResult.Pass(action)
        }
    }

    /**
     * 记录动作执行结果，用于后续去重检测。
     */
    fun recordResult(action: ClawAction, success: Boolean) {
        val key = buildActionKey(action)
        recentActions.addLast(RecentAction(key, System.currentTimeMillis(), success))
        // 清理过期记录
        val now = System.currentTimeMillis()
        while (recentActions.isNotEmpty() && now - recentActions.first().timestamp > DEDUP_WINDOW_MS * 3) {
            recentActions.removeFirst()
        }
        // 限制队列大小
        while (recentActions.size > 20) {
            recentActions.removeFirst()
        }
    }

    /**
     * 构建动作唯一标识键（用于去重检测）。
     */
    private fun buildActionKey(action: ClawAction): String {
        return "${action.type}|${action.text ?: action.packageName ?: action.name ?: ""}|${action.x ?: ""},${action.y ?: ""}"
    }

    /**
     * 清空历史记录。
     */
    fun clear() {
        recentActions.clear()
    }

    /**
     * 获取最近动作统计（用于调试）。
     */
    fun getStats(): String {
        val now = System.currentTimeMillis()
        val recent = recentActions.filter { now - it.timestamp < DEDUP_WINDOW_MS }
        val successes = recent.count { it.success }
        val failures = recent.count { !it.success }
        return "最近${recent.size}个动作: 成功$successes 失败$failures"
    }
}
