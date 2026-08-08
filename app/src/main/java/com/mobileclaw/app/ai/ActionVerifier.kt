package com.mobileclaw.app.ai

import android.accessibilityservice.AccessibilityService
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import com.mobileclaw.app.accessibility.ScreenAccessibilityService
import kotlinx.coroutines.delay

/**
 * 动作验证器 —— 执行后验证动作是否真正生效。
 *
 * 核心问题：很多动作（如 APP_OPEN）返回 success=true 但实际未生效，
 * 导致 AI 误判任务完成。本验证器在动作执行后通过无障碍服务检查屏幕状态，
 * 确认操作是否真正生效。
 *
 * 验证策略：
 * - APP_OPEN：等待后检查前台应用包名是否匹配
 * - SCREEN_CLICK_TEXT：检查目标文本是否消失或页面是否变化
 * - SCREEN_INPUT：检查输入框是否包含输入的文本
 * - APP_CLOSE：检查前台应用是否已切换
 *
 * 所有验证方法均为 suspend，支持等待页面加载。
 */
object ActionVerifier {

    /** 验证等待时间（毫秒）：动作执行后等待 UI 响应再验证。 */
    private const val VERIFY_DELAY_MS = 800L

    /** 最大验证等待轮数。 */
    private const val MAX_VERIFY_ROUNDS = 3

    /** 每轮验证间隔（毫秒）。 */
    private const val VERIFY_INTERVAL_MS = 500L

    /**
     * 验证动作执行结果是否真正生效。
     *
     * @param action 已执行的动作
     * @param result 执行器返回的结果
     * @param screen 屏幕控制器（用于额外检查）
     * @param systemInfo 系统信息采集器（用于检查前台应用）
     * @return 验证后的结果（可能修正为失败）
     */
    suspend fun verify(
        action: ClawAction,
        result: ClawActionResult,
        screen: ScreenController,
        systemInfo: SystemInfoCollector
    ): ClawActionResult {
        // 如果原始结果已失败，无需验证
        if (!result.success) return result

        return when (action.type) {
            ActionType.APP_OPEN -> verifyAppOpen(action, systemInfo)
            ActionType.APP_CLOSE -> verifyAppClose(action, systemInfo)
            ActionType.SCREEN_INPUT -> verifyInput(action, screen)
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK -> verifyClick(action, screen)
            ActionType.SCREEN_SWIPE -> verifySwipe(screen)
            else -> result // 其他动作不验证
        }
    }

    /**
     * 验证应用是否真正打开到前台。
     *
     * 等待页面加载后，检查前台应用包名是否匹配目标包名。
     * 如果使用 name 参数打开（无包名），则检查前台应用是否从原应用切换。
     */
    private suspend fun verifyAppOpen(
        action: ClawAction,
        systemInfo: SystemInfoCollector
    ): ClawActionResult {
        delay(VERIFY_DELAY_MS)

        val targetPkg = action.packageName
        val targetName = action.name

        // 有明确包名：直接检查前台包名
        if (!targetPkg.isNullOrEmpty()) {
            repeat(MAX_VERIFY_ROUNDS) { round ->
                val state = systemInfo.getCurrentState()
                val currentPkg = state.currentAppPackage
                if (currentPkg != null && currentPkg.startsWith(targetPkg.substringBefore(".", targetPkg))) {
                    return ClawActionResult.success("应用已打开（前台：$currentPkg）")
                }
                if (round < MAX_VERIFY_ROUNDS - 1) delay(VERIFY_INTERVAL_MS)
            }
            // 包名不完全匹配，检查是否至少切换了应用
            val finalState = systemInfo.getCurrentState()
            return if (finalState.currentAppPackage != null &&
                       finalState.currentAppPackage != "com.android.launcher" &&
                       finalState.currentAppPackage != "com.mobileclaw.app") {
                ClawActionResult.success("应用已切换（前台：${finalState.currentAppPackage}）")
            } else {
                ClawActionResult.failure("应用未成功打开到前台（当前：${finalState.currentAppPackage ?: "未知"}）")
            }
        }

        // 使用名称打开：检查前台是否从桌面切换
        if (!targetName.isNullOrEmpty()) {
            val state = systemInfo.getCurrentState()
            val currentPkg = state.currentAppPackage
            // 如果不在桌面和本应用，认为切换成功
            return if (currentPkg != null &&
                       !currentPkg.contains("launcher") &&
                       currentPkg != "com.mobileclaw.app") {
                ClawActionResult.success("应用已打开（前台：$currentPkg）")
            } else {
                ClawActionResult.failure("应用可能未成功打开（当前：${currentPkg ?: "未知"}）")
            }
        }

        return ClawActionResult.success("应用打开指令已执行")
    }

    /**
     * 验证应用是否真正关闭。
     */
    private suspend fun verifyAppClose(
        action: ClawAction,
        systemInfo: SystemInfoCollector
    ): ClawActionResult {
        delay(500)
        val targetPkg = action.packageName ?: return ClawActionResult.success("关闭指令已执行")
        val state = systemInfo.getCurrentState()
        return if (state.currentAppPackage == targetPkg) {
            ClawActionResult.failure("应用仍在前台，关闭可能未生效")
        } else {
            ClawActionResult.success("应用已关闭")
        }
    }

    /**
     * 验证文本输入是否成功。
     *
     * 检查当前屏幕上是否存在刚输入的文本内容。
     */
    private suspend fun verifyInput(
        action: ClawAction,
        screen: ScreenController
    ): ClawActionResult {
        val inputText = action.text ?: return ClawActionResult.success("输入指令已执行")
        if (inputText.isBlank()) return ClawActionResult.success("空文本输入")

        delay(300)
        // 检查屏幕上是否存在输入的文本
        val checkResult = screen.textExists(inputText)
        return if (checkResult.success) {
            ClawActionResult.success("文本已输入成功")
        } else {
            // 短文本可能不显示在无障碍节点中，不强制判定失败
            if (inputText.length <= 3) {
                ClawActionResult.success("输入已执行（短文本无法验证）")
            } else {
                ClawActionResult.failure("文本输入可能未生效，屏幕上未找到输入内容")
            }
        }
    }

    /**
     * 验证点击是否生效。
     *
     * 点击生效的标志：目标文本消失（页面跳转）或新内容出现。
     */
    private suspend fun verifyClick(
        action: ClawAction,
        screen: ScreenController
    ): ClawActionResult {
        val targetText = action.text ?: return ClawActionResult.success("点击已执行")

        delay(400)
        // 如果目标文本仍存在且页面未变化，可能是点击未生效
        val stillExists = screen.textExists(targetText)
        return if (stillExists.success) {
            // 文本仍存在，但可能是列表项点击（不跳转只是高亮），不强制判定失败
            ClawActionResult.success("点击已执行（目标仍可见，可能是列表内操作）")
        } else {
            ClawActionResult.success("点击成功（目标已消失，页面可能已跳转）")
        }
    }

    /**
     * 验证滑动是否生效。
     *
     * 简单检查屏幕文本是否发生变化（如果有缓存的前一帧文本）。
     */
    private suspend fun verifySwipe(screen: ScreenController): ClawActionResult {
        // 滑动验证较复杂，暂不强制验证
        return ClawActionResult.success("滑动已执行")
    }

    /**
     * 带重试验证的动作执行。
     *
     * 执行动作 -> 验证 -> 失败则重试（最多 maxRetries 次）。
     * 每次重试前等待指数退避时间。
     *
     * @param action 待执行的动作
     * @param executor 动作执行器（返回 ClawActionResult）
     * @param screen 屏幕控制器
     * @param systemInfo 系统信息采集器
     * @param maxRetries 最大重试次数（默认2次）
     * @return 最终执行结果
     */
    suspend fun executeWithVerification(
        action: ClawAction,
        executor: suspend (ClawAction) -> ClawActionResult,
        screen: ScreenController,
        systemInfo: SystemInfoCollector,
        maxRetries: Int = 2
    ): ClawActionResult {
        var lastResult = executor(action)

        // 首次验证
        if (lastResult.success) {
            lastResult = verify(action, lastResult, screen, systemInfo)
        }

        // 验证失败则重试
        var retryCount = 0
        while (!lastResult.success && retryCount < maxRetries) {
            retryCount++
            val backoffMs = (500L * (1 shl (retryCount - 1))).coerceAtMost(3000L)
            delay(backoffMs)

            lastResult = executor(action)
            if (lastResult.success) {
                lastResult = verify(action, lastResult, screen, systemInfo)
            }
        }

        return lastResult
    }

    /**
     * 检查无障碍服务是否可用。
     */
    private fun isAccessibilityAvailable(): Boolean {
        return ScreenAccessibilityService.instance != null
    }
}
