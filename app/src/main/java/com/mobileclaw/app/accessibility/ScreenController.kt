package com.mobileclaw.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 屏幕控制器
 *
 * 依赖 [ScreenAccessibilityService]，封装基于无障碍服务的屏幕操控能力，包括：
 * - 点击（按坐标 / 按节点）
 * - 滑动（上 / 下 / 左 / 右 / 自定义坐标）
 * - 长按
 * - 文本输入
 * - 系统按键（返回 / Home / 最近任务）
 * - 截屏
 * - 查找屏幕文本元素
 * - 等待元素出现（协程挂起）
 * - 获取当前界面 UI 树
 *
 * 手势模拟基于 [GestureDescription] API（Android 7.0 / API 24+，本项目 minSdk 29 满足）。
 *
 * 注意：
 * 1. 动作类方法（点击、滑动、按键等）返回 [Boolean] 表示是否成功；
 *    查询类方法（findTextElements / getUiTree）返回数据，失败时以 null 或空集合表示；
 *    截屏返回 [Bitmap]，失败时返回 null。
 * 2. 手势派发内部使用 [CountDownLatch] 同步等待回调，会阻塞调用线程，
 *    请勿在主线程调用，建议在子线程或协程中使用。
 *
 * @property service 无障碍服务实例
 */
class ScreenController(
    private val service: ScreenAccessibilityService
) {

    companion object {
        private const val TAG = "ScreenController"

        /** 默认点击手势持续时间（毫秒） */
        private const val DEFAULT_CLICK_DURATION_MS = 50L

        /** 默认长按手势持续时间（毫秒） */
        private const val DEFAULT_LONG_PRESS_DURATION_MS = 1000L

        /** 默认滑动手势持续时间（毫秒） */
        private const val DEFAULT_SWIPE_DURATION_MS = 300L

        /** 默认等待元素出现的超时时间（毫秒） */
        private const val DEFAULT_WAIT_TIMEOUT_MS = 5000L

        /** 等待元素时的轮询间隔（毫秒） */
        private const val WAIT_POLL_INTERVAL_MS = 200L
    }

    /** 截屏回调执行器，使用独立单线程，避免阻塞手势线程 */
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    // ==================== 点击操作 ====================

    /**
     * 通过坐标点击屏幕指定位置。
     *
     * 使用 [GestureDescription] 模拟一次短按手势。
     *
     * @param x 点击位置 X 坐标（屏幕像素）
     * @param y 点击位置 Y 坐标（屏幕像素）
     * @return true 表示手势执行成功
     */
    fun click(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, DEFAULT_CLICK_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAndWait(gesture, DEFAULT_CLICK_DURATION_MS + 1000L)
    }

    /**
     * 通过节点点击指定控件。
     *
     * 执行策略（按优先级）：
     * 1. 直接对节点执行 [AccessibilityNodeInfo.ACTION_CLICK]；
     * 2. 向上查找可点击的父节点并执行点击；
     * 3. 退化为点击节点边界中心坐标。
     *
     * @param node 目标控件节点
     * @return true 表示点击成功
     */
    fun click(node: AccessibilityNodeInfo): Boolean {
        // 1. 直接对节点执行点击动作
        try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        } catch (e: Exception) {
            Log.w(TAG, "节点 ACTION_CLICK 失败，尝试父节点", e)
        }

        // 2. 向上寻找可点击的父节点执行点击
        var parent = node.parent
        while (parent != null) {
            try {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "父节点点击失败", e)
            }
            parent = try {
                parent.parent
            } catch (e: Exception) {
                null
            }
        }

        // 3. 退化为点击节点中心坐标
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return click(rect.exactCenterX(), rect.exactCenterY())
    }

    // ==================== 滑动操作 ====================

    /**
     * 按指定起止坐标执行滑动手势。
     *
     * @param startX 起点X坐标
     * @param startY 起点Y坐标
     * @param endX 终点X坐标
     * @param endY 终点Y坐标
     * @param durationMs 滑动持续时间（毫秒）
     * @return true 表示手势执行成功
     */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAndWait(gesture, durationMs + 1000L)
    }

    /**
     * 向上滑动（手指由屏幕下方移向上方，使页面内容向上滚动，查看下方内容）。
     *
     * @return true 表示手势执行成功
     */
    fun swipeUp(): Boolean {
        val size = getScreenSize()
        if (size.x == 0 || size.y == 0) return false
        val centerX = size.x / 2f
        return swipe(centerX, size.y * 0.8f, centerX, size.y * 0.2f)
    }

    /**
     * 向下滑动（手指由屏幕上方移向下方，使页面内容向下滚动，查看上方内容）。
     *
     * @return true 表示手势执行成功
     */
    fun swipeDown(): Boolean {
        val size = getScreenSize()
        if (size.x == 0 || size.y == 0) return false
        val centerX = size.x / 2f
        return swipe(centerX, size.y * 0.2f, centerX, size.y * 0.8f)
    }

    /**
     * 向左滑动（手指由屏幕右侧移向左侧，通常用于切换到下一个页面/标签）。
     *
     * @return true 表示手势执行成功
     */
    fun swipeLeft(): Boolean {
        val size = getScreenSize()
        if (size.x == 0 || size.y == 0) return false
        val centerY = size.y / 2f
        return swipe(size.x * 0.8f, centerY, size.x * 0.2f, centerY)
    }

    /**
     * 向右滑动（手指由屏幕左侧移向右侧，通常用于切换到上一个页面/标签）。
     *
     * @return true 表示手势执行成功
     */
    fun swipeRight(): Boolean {
        val size = getScreenSize()
        if (size.x == 0 || size.y == 0) return false
        val centerY = size.y / 2f
        return swipe(size.x * 0.2f, centerY, size.x * 0.8f, centerY)
    }

    // ==================== 长按操作 ====================

    /**
     * 在指定坐标执行长按手势。
     *
     * @param x 长按位置 X 坐标
     * @param y 长按位置 Y 坐标
     * @param durationMs 长按持续时间（毫秒），默认 1000ms
     * @return true 表示手势执行成功
     */
    fun longPress(
        x: Float,
        y: Float,
        durationMs: Long = DEFAULT_LONG_PRESS_DURATION_MS
    ): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAndWait(gesture, durationMs + 1000L)
    }

    // ==================== 文本输入 ====================

    /**
     * 向指定节点输入文本。
     *
     * 先对节点执行 [AccessibilityNodeInfo.ACTION_FOCUS] 获取焦点，
     * 再通过 [AccessibilityNodeInfo.ACTION_SET_TEXT] 设置文本内容。
     * 该方式适用于可编辑的 EditText 等控件。
     *
     * @param node 目标可编辑节点
     * @param text 要输入的文本
     * @return true 表示文本设置成功
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            // 聚焦输入框
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            // 通过 ACTION_SET_TEXT 设置文本
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败: $text", e)
            false
        }
    }

    /**
     * 向当前拥有输入焦点的节点输入文本。
     *
     * @param text 要输入的文本
     * @return true 表示文本设置成功，若无焦点节点则返回 false
     */
    fun inputText(text: String): Boolean {
        // 1. 尝试当前焦点节点
        val focused = service.findFocusedNode()
        if (focused != null && focused.isEditable) {
            val result = inputText(focused, text)
            if (result) return true
        }

        // 2. 焦点不存在或不可编辑 -> 查找可编辑的 EditText 节点
        val root = service.getRootInActiveWindowSafe() ?: run {
            Log.w(TAG, "无法获取活动窗口，输入文本失败")
            return false
        }
        val editableNode = findEditableNode(root)
        if (editableNode != null) {
            Log.d(TAG, "找到可编辑节点，尝试输入文本")
            return inputText(editableNode, text)
        }

        // 3. 焦点存在但不可编辑 -> 尝试在焦点节点上输入（有些控件不标记 isEditable 但仍可输入）
        if (focused != null) {
            Log.d(TAG, "焦点节点不可编辑，但仍尝试输入")
            return inputText(focused, text)
        }

        Log.w(TAG, "未找到可编辑节点，无法输入文本")
        return false
    }

    /** 递归查找可编辑的文本输入节点。 */
    private fun findEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 8) return null
        // 检查当前节点是否可编辑
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            return node
        }
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    // ==================== 系统按键 ====================

    /**
     * 模拟按下返回键。
     *
     * @return true 表示动作执行成功
     */
    fun pressBack(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK, "返回")
    }

    /**
     * 模拟按下 Home 键（回到桌面）。
     *
     * @return true 表示动作执行成功
     */
    fun pressHome(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME, "Home")
    }

    /**
     * 模拟按下最近任务键（打开多任务界面）。
     *
     * @return true 表示动作执行成功
     */
    fun pressRecents(): Boolean {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "最近任务")
    }

    // ==================== 截屏 ====================

    /**
     * 截取当前屏幕。
     *
     * 基于 [AccessibilityService.takeScreenshot]，需 Android 11（API 30）及以上版本，
     * 且服务已在 accessibility_config.xml 中声明 canTakeScreenshots。
     * 在更低版本上调用会返回 null。
     *
     * 内部使用 [CountDownLatch] 同步等待截屏结果，将硬件缓冲区转换为
     * [Bitmap.Config.ARGB_8888] 格式的可变副本以便后续处理。
     *
     * @return 截图 [Bitmap]，失败或版本不支持时返回 null
     */
    fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "截屏功能需要 Android 11（API 30）及以上版本")
            return null
        }

        val latch = CountDownLatch(1)
        var screenshot: Bitmap? = null

        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val buffer = result.hardwareBuffer
                            val colorSpace = result.colorSpace
                            if (buffer != null) {
                                screenshot = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                // 务必关闭硬件缓冲区，避免内存泄漏
                                buffer.close()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "处理截屏结果失败", e)
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "截屏失败，错误码: $errorCode")
                        latch.countDown()
                    }
                }
            )
            // 等待截屏完成，最多 3 秒
            latch.await(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "截屏调用异常", e)
        }

        return screenshot
    }

    // ==================== 查找与等待 ====================

    /**
     * 查找屏幕上包含指定文本的元素，并返回其 [UiNode] 数据快照列表。
     *
     * @param text 要查找的文本
     * @return 匹配到的 UI 节点列表，无匹配时返回空列表
     */
    fun findTextElements(text: String): List<UiNode> {
        return service.findNodesByText(text).map { UiNode.fromAccessibilityNodeInfo(it) }
    }

    /**
     * 查找并点击：自动滚动查找指定文本并点击。
     *
     * 策略：
     * 1. 先在当前屏幕查找文本，找到则点击
     * 2. 未找到则向下滑动最多 5 次，每次滑动后重新查找
     * 3. 向上滑动最多 3 次
     * 4. 仍未找到则返回 false
     *
     * 优化：减少延迟（300ms），提升滚动查找效率
     *
     * @param text 要查找并点击的文本
     * @return true 表示找到并点击成功
     */
    suspend fun findAndClick(text: String): Boolean {
        // 1. 先在当前屏幕查找
        val nodes = service.findNodesByText(text)
        if (nodes.isNotEmpty()) {
            val target = nodes.first()
            return click(target)
        }
        // 2. 向下滑动查找，最多 5 次
        repeat(5) {
            delay(300)
            swipeUp()
            delay(400)
            val found = service.findNodesByText(text)
            if (found.isNotEmpty()) {
                return click(found.first())
            }
        }
        // 3. 向上滑动查找，最多 3 次
        repeat(3) {
            delay(300)
            swipeDown()
            delay(400)
            val found = service.findNodesByText(text)
            if (found.isNotEmpty()) {
                return click(found.first())
            }
        }
        return false
    }

    /**
     * 滚动到指定文本可见。
     *
     * 优化：减少延迟，提升查找效率
     *
     * @param text 要滚动到的文本
     * @return true 表示文本已出现在屏幕上
     */
    suspend fun scrollToText(text: String): Boolean {
        // 先检查当前屏幕
        if (service.findNodesByText(text).isNotEmpty()) return true
        // 向下滑动查找
        repeat(5) {
            delay(200)
            swipeUp()
            delay(400)
            if (service.findNodesByText(text).isNotEmpty()) return true
        }
        // 向上滑动查找
        repeat(3) {
            delay(200)
            swipeDown()
            delay(500)
            if (service.findNodesByText(text).isNotEmpty()) return true
        }
        return false
    }

    /**
     * 等待包含指定文本的元素出现在屏幕上。
     *
     * 该方法为挂起函数，在 [timeoutMs] 时间内以固定间隔轮询屏幕，
     * 一旦发现匹配元素立即返回 true；超时未发现则返回 false。
     *
     * @param text 要等待的文本
     * @param timeoutMs 超时时间（毫秒），默认 5000ms
     * @return true 表示在超时前发现元素
     */
    suspend fun waitForElement(
        text: String,
        timeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS
    ): Boolean {
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (service.findNodesByText(text).isNotEmpty()) return true
            delay(WAIT_POLL_INTERVAL_MS)
            elapsed += WAIT_POLL_INTERVAL_MS
        }
        // 最后再检查一次，避免刚好在超时边界遗漏
        return service.findNodesByText(text).isNotEmpty()
    }

    // ==================== UI 树 ====================

    /**
     * 获取当前界面所有控件的 UI 树结构（根节点数据快照）。
     *
     * 从活动窗口根节点递归构建 [UiNode]，包含文本、类名、边界、可点击性、
     * 可滚动性及子节点等信息，可直接通过 [UiNode.toJson] 序列化。
     *
     * @return 根节点 [UiNode]，无法获取根节点时返回 null
     */
    fun getUiTree(): UiNode? {
        val root = service.getRootInActiveWindowSafe() ?: return null
        return UiNode.fromAccessibilityNodeInfo(root)
    }

    // ==================== 内部工具方法 ====================

    /**
     * 派发手势并同步等待执行结果。
     *
     * 通过 [CountDownLatch] 阻塞等待 [AccessibilityService.GestureResultCallback] 回调，
     * 以同步方式返回手势是否成功完成。
     *
     * @param gesture 手势描述
     * @param timeoutMs 等待超时时间（毫秒）
     * @return true 表示手势完成；派发被拒绝、被取消或超时均返回 false
     */
    private fun dispatchGestureAndWait(gesture: GestureDescription, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var success = false

        val dispatched = try {
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?) {
                        success = true
                        latch.countDown()
                    }

                    override fun onCancelled(gesture: GestureDescription?) {
                        Log.w(TAG, "手势被取消")
                        success = false
                        latch.countDown()
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "派发手势异常", e)
            return false
        }

        if (!dispatched) {
            Log.e(TAG, "手势派发被拒绝（服务可能未启用 canPerformGestures）")
            return false
        }

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "等待手势结果时线程被中断", e)
        }
        return success
    }

    /**
     * 获取屏幕真实尺寸（像素）。
     *
     * @return 屏幕尺寸 [Point]，获取失败时 x/y 为 0
     */
    private fun getScreenSize(): Point {
        val point = Point()
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(point)
        } catch (e: Exception) {
            Log.e(TAG, "获取屏幕尺寸失败", e)
        }
        return point
    }

    /**
     * 执行全局动作（系统按键），并统一处理异常与日志。
     *
     * @param action 全局动作常量（如 [AccessibilityService.GLOBAL_ACTION_BACK]）
     * @param name 动作名称，用于日志
     * @return true 表示执行成功
     */
    private fun performGlobalAction(action: Int, name: String): Boolean {
        return try {
            val ok = service.performGlobalAction(action)
            if (!ok) {
                Log.w(TAG, "全局动作执行失败: $name")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "全局动作异常: $name", e)
            false
        }
    }
}
