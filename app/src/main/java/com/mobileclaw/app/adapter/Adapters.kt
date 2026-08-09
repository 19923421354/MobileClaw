package com.mobileclaw.app.adapter

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import com.mobileclaw.app.ai.ClawActionResult
import com.mobileclaw.app.ai.KeyType
import com.mobileclaw.app.ai.PhoneState
import com.mobileclaw.app.ai.ScreenController as ScreenControllerInterface
import com.mobileclaw.app.ai.SystemInfoCollector as SystemInfoCollectorInterface
import com.mobileclaw.app.ai.ShellExecutor as ShellExecutorInterface
import com.mobileclaw.app.ai.SwipeDirection
import com.mobileclaw.app.ai.SystemInfoType
import com.mobileclaw.app.accessibility.ScreenAccessibilityService
import com.mobileclaw.app.accessibility.ScreenController as ScreenControllerImpl
import com.mobileclaw.app.debug.ShellExecutor as ShellExecutorImpl
import com.mobileclaw.app.system.SystemInfoCollector as SystemInfoCollectorImpl
import com.mobileclaw.app.model.ShellResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 适配器模块 —— 将底层实现（accessibility/system/debug 包中的具体类）
 * 适配为 ClawController 所需的接口（ai 包中定义的 interface）。
 *
 * 这一层解耦了「执行器接口」与「底层实现」，使得 ClawController 只面向接口编程。
 */

// =============================================================================
//  ScreenControllerAdapter
// =============================================================================

/**
 * 屏幕控制器适配器。
 *
 * 将 [ScreenControllerImpl]（返回 Boolean / Bitmap）适配为
 * [ScreenControllerInterface]（返回 [ClawActionResult]）。
 */
class ScreenControllerAdapter(
    private val impl: ScreenControllerImpl
) : ScreenControllerInterface {

    override suspend fun click(x: Int, y: Int): ClawActionResult {
        val ok = impl.click(x.toFloat(), y.toFloat())
        return if (ok) ClawActionResult.success("点击($x,$y)成功")
        else ClawActionResult.failure("点击($x,$y)失败")
    }

    override suspend fun clickText(text: String): ClawActionResult {
        // 重试3次，每次间隔500ms，处理界面未加载完成的情况
        repeat(3) { attempt ->
            // 方式1: 通过无障碍服务精确查找（findAccessibilityNodeInfosByText 是包含匹配，大小写不敏感）
            val rawNodes = ScreenAccessibilityService.instance?.findNodesByText(text) ?: emptyList()
            if (rawNodes.isNotEmpty()) {
                val target = rawNodes.first()
                val ok = impl.click(target)
                if (ok) return ClawActionResult.success("点击「$text」成功")
                // 节点点击失败，尝试用坐标点击
                val rect = android.graphics.Rect()
                target.getBoundsInScreen(rect)
                val ok2 = impl.click(rect.exactCenterX(), rect.exactCenterY())
                if (ok2) return ClawActionResult.success("点击「$text」成功(坐标方式)")
            }

            // 方式2: 模糊匹配 - 遍历UI树查找contentDescription或text部分匹配的节点
            if (attempt == 0) {
                val fuzzyNode = findNodeByFuzzyText(text)
                if (fuzzyNode != null) {
                    val ok = impl.click(fuzzyNode)
                    if (ok) return ClawActionResult.success("点击「$text」成功(模糊匹配)")
                    val rect = android.graphics.Rect()
                    fuzzyNode.getBoundsInScreen(rect)
                    val ok2 = impl.click(rect.exactCenterX(), rect.exactCenterY())
                    if (ok2) return ClawActionResult.success("点击「$text」成功(模糊匹配坐标)")
                }
            }

            if (attempt < 2) delay(500)
        }
        return ClawActionResult.failure("未找到包含「$text」的元素（已重试3次）")
    }

    /**
     * 模糊查找节点：遍历UI树，查找text或contentDescription包含目标文本的节点。
     * 比findAccessibilityNodeInfosByText更灵活，可以匹配contentDescription。
     */
    private fun findNodeByFuzzyText(text: String): android.view.accessibility.AccessibilityNodeInfo? {
        val service = ScreenAccessibilityService.instance ?: return null
        val root = service.getRootInActiveWindowSafe() ?: return null
        return findNodeByFuzzyTextRecursive(root, text, 0)
    }

    private fun findNodeByFuzzyTextRecursive(
        node: android.view.accessibility.AccessibilityNodeInfo,
        text: String,
        depth: Int
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (depth > 10) return null
        // 检查 text
        val nodeText = node.text?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) || text.contains(nodeText, ignoreCase = true)) {
            if (nodeText.isNotBlank()) return node
        }
        // 检查 contentDescription
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.contains(text, ignoreCase = true)) {
            return node
        }
        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByFuzzyTextRecursive(child, text, depth + 1)
            if (result != null) return result
        }
        return null
    }

    override suspend fun longClick(x: Int?, y: Int?, text: String?): ClawActionResult {
        // 优先按文本长按
        if (!text.isNullOrEmpty()) {
            val rawNodes = ScreenAccessibilityService.instance?.findNodesByText(text) ?: emptyList()
            if (rawNodes.isNotEmpty()) {
                val node = rawNodes.first()
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val ok = impl.longPress(rect.exactCenterX(), rect.exactCenterY())
                return if (ok) ClawActionResult.success("长按「$text」成功")
                else ClawActionResult.failure("长按「$text」失败")
            }
            return ClawActionResult.failure("未找到包含「$text」的元素")
        }
        // 按坐标长按
        if (x != null && y != null) {
            val ok = impl.longPress(x.toFloat(), y.toFloat())
            return if (ok) ClawActionResult.success("长按($x,$y)成功")
            else ClawActionResult.failure("长按($x,$y)失败")
        }
        return ClawActionResult.failure("longClick 缺少 x/y 或 text 参数")
    }

    override suspend fun findAndClick(text: String): ClawActionResult {
        val ok = impl.findAndClick(text)
        return if (ok) ClawActionResult.success("查找并点击「$text」成功")
        else ClawActionResult.failure("未找到「$text」元素（已尝试滚动查找）")
    }

    override suspend fun scrollToText(text: String): ClawActionResult {
        val ok = impl.scrollToText(text)
        return if (ok) ClawActionResult.success("已滚动到「$text」")
        else ClawActionResult.failure("未找到「$text」（已尝试滚动查找）")
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): ClawActionResult {
        val ok = impl.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        return if (ok) ClawActionResult.success("滑动($x1,$y1)->($x2,$y2)成功")
        else ClawActionResult.failure("滑动失败")
    }

    override suspend fun swipeDirection(direction: SwipeDirection): ClawActionResult {
        val ok = when (direction) {
            SwipeDirection.UP -> impl.swipeUp()
            SwipeDirection.DOWN -> impl.swipeDown()
            SwipeDirection.LEFT -> impl.swipeLeft()
            SwipeDirection.RIGHT -> impl.swipeRight()
        }
        return if (ok) ClawActionResult.success("向${direction.description}成功")
        else ClawActionResult.failure("向${direction.description}失败")
    }

    override suspend fun inputText(text: String): ClawActionResult {
        // 重试2次，处理输入框未获取焦点的情况
        repeat(2) { attempt ->
            val ok = impl.inputText(text)
            if (ok) return ClawActionResult.success("输入「$text」成功")
            if (attempt < 1) {
                delay(500)
                // 尝试查找并点击可编辑的EditText节点，而非盲目点击屏幕中央
                val service = ScreenAccessibilityService.instance
                if (service != null) {
                    val root = service.getRootInActiveWindowSafe()
                    if (root != null) {
                        val editableNode = findEditableNodeForInput(root)
                        if (editableNode != null) {
                            // 点击可编辑节点获取焦点
                            val rect = android.graphics.Rect()
                            editableNode.getBoundsInScreen(rect)
                            impl.click(rect.exactCenterX(), rect.exactCenterY())
                            delay(300)
                        }
                    }
                }
            }
        }
        return ClawActionResult.failure("输入文本失败，可能没有焦点输入框")
    }

    /** 递归查找可编辑节点（用于输入文本前自动聚焦输入框）。 */
    private fun findEditableNodeForInput(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int = 0
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (depth > 8) return null
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeForInput(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    override suspend fun pressKey(key: KeyType): ClawActionResult {
        val ok = when (key) {
            KeyType.BACK -> impl.pressBack()
            KeyType.HOME -> impl.pressHome()
            KeyType.RECENTS -> impl.pressRecents()
            KeyType.VOLUME_UP -> execKeyevent(24)
            KeyType.VOLUME_DOWN -> execKeyevent(25)
            KeyType.POWER -> execKeyevent(26)
            KeyType.NOTIFICATION_PANEL -> execShellCommand("cmd statusbar expandNotifications")
            KeyType.SPLIT_SCREEN -> execKeyevent(171)
            KeyType.LOCK_SCREEN -> execKeyevent(223)
            KeyType.QUICK_SETTINGS -> execShellCommand("cmd statusbar expandSettings")
        }
        return if (ok) ClawActionResult.success("按下${key.description}成功")
        else ClawActionResult.failure("按下${key.description}失败")
    }

    /** 通过 input keyevent 执行按键操作。 */
    private fun execKeyevent(keycode: Int): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keycode"))
            proc.waitFor()
            true
        }.getOrDefault(false)
    }

    /** 通过 shell 命令执行系统操作。 */
    private fun execShellCommand(cmd: String): Boolean {
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            proc.waitFor()
            true
        }.getOrDefault(false)
    }

    override suspend fun screenshot(): ClawActionResult {
        val bitmap = impl.takeScreenshot()
            ?: return ClawActionResult.failure("截屏失败，请确保无障碍服务已启用且系统版本>=Android 11")
        return saveBitmap(bitmap)
    }

    override suspend fun wait(ms: Long): ClawActionResult {
        delay(ms)
        return ClawActionResult.success("等待${ms}ms完成")
    }

    override suspend fun doubleClick(x: Int?, y: Int?, text: String?): ClawActionResult {
        // 优先按文本双击
        if (!text.isNullOrEmpty()) {
            val rawNodes = ScreenAccessibilityService.instance?.findNodesByText(text) ?: emptyList()
            if (rawNodes.isNotEmpty()) {
                val node = rawNodes.first()
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                impl.click(cx, cy)
                delay(100)
                val ok = impl.click(cx, cy)
                return if (ok) ClawActionResult.success("双击「$text」成功")
                else ClawActionResult.failure("双击「$text」失败")
            }
            return ClawActionResult.failure("未找到包含「$text」的元素")
        }
        // 按坐标双击
        if (x != null && y != null) {
            impl.click(x.toFloat(), y.toFloat())
            delay(100)
            val ok = impl.click(x.toFloat(), y.toFloat())
            return if (ok) ClawActionResult.success("双击($x,$y)成功")
            else ClawActionResult.failure("双击($x,$y)失败")
        }
        return ClawActionResult.failure("doubleClick 缺少 x/y 或 text 参数")
    }

    override suspend fun getScreenText(): ClawActionResult {
        return try {
            val service = ScreenAccessibilityService.instance
                ?: return ClawActionResult.failure("无障碍服务未启用")
            val root = service.getRootInActiveWindowSafe()
                ?: return ClawActionResult.failure("无法获取屏幕内容")
            val text = collectTextFromNode(root)
            ClawActionResult.success("获取屏幕文本成功", text.take(2000))
        } catch (e: Exception) {
            ClawActionResult.failure("获取屏幕文本失败: ${e.message}")
        }
    }

    override suspend fun textExists(text: String): ClawActionResult {
        return try {
            val service = ScreenAccessibilityService.instance
                ?: return ClawActionResult.failure("无障碍服务未启用")
            val nodes = service.findNodesByText(text)
            val exists = nodes.isNotEmpty()
            ClawActionResult.success(
                if (exists) "文本「$text」存在" else "文本「$text」不存在",
                if (exists) "true" else "false"
            )
        } catch (e: Exception) {
            ClawActionResult.failure("检测文本失败: ${e.message}")
        }
    }

    /** 递归收集无障碍节点树中的文本。 */
    private fun collectTextFromNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int = 0,
        maxDepth: Int = 8
    ): String {
        if (depth > maxDepth) return ""
        val sb = StringBuilder()
        val text = node.text
        if (!text.isNullOrBlank()) {
            sb.appendLine(text.toString())
        }
        val desc = node.contentDescription
        if (!desc.isNullOrBlank() && desc != text) {
            sb.appendLine(desc.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(collectTextFromNode(child, depth + 1, maxDepth))
        }
        return sb.toString()
    }

    /** 将 Bitmap 保存到应用私有缓存目录（不进入相册，自动清理）。 */
    private fun saveBitmap(bitmap: Bitmap): ClawActionResult {
        return try {
            val context = ScreenAccessibilityService.instance
                ?: return ClawActionResult.failure("无法获取上下文")
            // 保存到应用私有缓存目录，不会被相册扫描
            val dir = File(context.cacheDir, "screenshots")
            if (!dir.exists()) dir.mkdirs()
            // 添加 .nomedia 文件，双重保险防止被媒体扫描器收录
            val nomedia = File(dir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "screenshot_$timestamp.png")
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            // 标记为退出时自动删除
            file.deleteOnExit()
            ClawActionResult.success("截屏成功", file.absolutePath)
        } catch (e: Exception) {
            ClawActionResult.failure("保存截图失败: ${e.message}")
        }
    }
}

// =============================================================================
//  SystemInfoCollectorAdapter
// =============================================================================

/**
 * 系统信息采集器适配器。
 *
 * 将 [SystemInfoCollectorImpl] 适配为 [SystemInfoCollectorInterface]。
 * 负责将底层结构化数据（MemoryInfo/CpuInfo 等）转换为 [PhoneState] 和 [ClawActionResult]。
 */
class SystemInfoCollectorAdapter(
    private val impl: SystemInfoCollectorImpl,
    private val shellExecutor: ShellExecutorImpl
) : SystemInfoCollectorInterface {

    override suspend fun getCurrentState(): PhoneState {
        return try {
            val systemInfo = impl.collectAll()
            val screenText = try {
                val service = ScreenAccessibilityService.instance
                if (service != null) {
                    val root = service.getRootInActiveWindowSafe()
                    if (root != null) collectTextFromNode(root) else ""
                } else ""
            } catch (e: Exception) { "" }

            val currentApp = try {
                ScreenAccessibilityService.instance?.lastWindowPackage
            } catch (e: Exception) {
                null
            }

            PhoneState(
                screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels,
                screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels,
                currentAppPackage = currentApp,
                batteryPercent = systemInfo.batteryInfo.level,
                isCharging = systemInfo.batteryInfo.isCharging,
                totalMemoryMb = systemInfo.memInfo.total / (1024 * 1024),
                availableMemoryMb = systemInfo.memInfo.available / (1024 * 1024),
                totalStorageGb = systemInfo.storageInfo.total / (1024 * 1024 * 1024),
                availableStorageGb = systemInfo.storageInfo.available / (1024 * 1024 * 1024),
                cpuUsagePercent = systemInfo.cpuInfo.usage.toInt(),
                currentScreenText = screenText.take(800)
            )
        } catch (e: Exception) {
            PhoneState()
        }
    }

    override suspend fun getSystemInfo(type: SystemInfoType?): ClawActionResult {
        return try {
            val info = impl.collectAll()
            val text = when (type) {
                SystemInfoType.MEMORY -> buildString {
                    appendLine("=== 内存信息 ===")
                    appendLine("总内存: ${info.memInfo.total / 1024 / 1024} MB")
                    appendLine("可用内存: ${info.memInfo.available / 1024 / 1024} MB")
                    appendLine("已用内存: ${info.memInfo.used / 1024 / 1024} MB")
                    appendLine("使用率: ${"%.1f".format(info.memInfo.usagePercent)}%")
                }
                SystemInfoType.CPU -> buildString {
                    appendLine("=== CPU 信息 ===")
                    appendLine("核心数: ${info.cpuInfo.cores}")
                    appendLine("最大频率: ${info.cpuInfo.maxFreq / 1000} MHz")
                    appendLine("最小频率: ${info.cpuInfo.minFreq / 1000} MHz")
                    appendLine("当前频率: ${info.cpuInfo.curFreq / 1000} MHz")
                    appendLine("使用率: ${"%.1f".format(info.cpuInfo.usage)}%")
                }
                SystemInfoType.BATTERY -> buildString {
                    appendLine("=== 电池信息 ===")
                    appendLine("电量: ${info.batteryInfo.level}%")
                    appendLine("温度: ${info.batteryInfo.temperature / 10.0}°C")
                    appendLine("充电中: ${if (info.batteryInfo.isCharging) "是" else "否"}")
                    appendLine("电池技术: ${info.batteryInfo.technology}")
                }
                SystemInfoType.STORAGE -> buildString {
                    appendLine("=== 存储信息 ===")
                    appendLine("总容量: ${"%.1f".format(info.storageInfo.total / 1024.0 / 1024 / 1024)} GB")
                    appendLine("可用: ${"%.1f".format(info.storageInfo.available / 1024.0 / 1024 / 1024)} GB")
                    appendLine("已用: ${"%.1f".format(info.storageInfo.used / 1024.0 / 1024 / 1024)} GB")
                    appendLine("使用率: ${"%.1f".format(info.storageInfo.usagePercent)}%")
                }
                null -> buildString {
                    appendLine("=== 系统综合信息 ===")
                    appendLine("内存: ${info.memInfo.used / 1024 / 1024}/${info.memInfo.total / 1024 / 1024} MB (${"%.1f".format(info.memInfo.usagePercent)}%)")
                    appendLine("CPU: ${info.cpuInfo.cores}核, 使用率${"%.1f".format(info.cpuInfo.usage)}%")
                    appendLine("电池: ${info.batteryInfo.level}%${if (info.batteryInfo.isCharging) "(充电中)" else ""}")
                    appendLine("存储: ${"%.1f".format(info.storageInfo.used / 1024.0 / 1024 / 1024)}/${"%.1f".format(info.storageInfo.total / 1024.0 / 1024 / 1024)} GB")
                }
            }
            ClawActionResult.success(text, text)
        } catch (e: Exception) {
            ClawActionResult.failure("获取系统信息失败: ${e.message}")
        }
    }

    override suspend fun killProcess(pid: Int): ClawActionResult {
        val result = shellExecutor.execute("kill -9 $pid")
        return if (result.isSuccess) ClawActionResult.success("进程 $pid 已结束")
        else ClawActionResult.failure("结束进程 $pid 失败: ${result.stderr}")
    }

    override suspend fun clearCache(): ClawActionResult {
        // 清理应用缓存需要权限，这里执行基础清理
        val result = shellExecutor.execute("rm -rf /data/local/tmp/* 2>/dev/null; echo done")
        return if (result.isSuccess) ClawActionResult.success("缓存清理完成")
        else ClawActionResult.failure("缓存清理失败: ${result.stderr}")
    }

    /** 递归收集无障碍节点树中的文本。 */
    private fun collectTextFromNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int = 0,
        maxDepth: Int = 5
    ): String {
        if (depth > maxDepth) return ""
        val sb = StringBuilder()
        val text = node.text
        if (!text.isNullOrBlank()) {
            sb.appendLine(text.toString())
        }
        val desc = node.contentDescription
        if (!desc.isNullOrBlank() && desc != text) {
            sb.appendLine(desc.toString())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(collectTextFromNode(child, depth + 1, maxDepth))
        }
        return sb.toString()
    }
}

// =============================================================================
//  ShellExecutorAdapter
// =============================================================================

/**
 * Shell 执行器适配器。
 *
 * 将 [ShellExecutorImpl] 适配为 [ShellExecutorInterface]。
 * 补充了应用管理、文件操作、通知等高级操作。
 *
 * 降级策略：当 Shizuku 不可用时，尝试使用本地方式实现：
 * - openApp: 使用 PackageManager.getLaunchIntentForPackage()
 * - closeApp: 使用 ActivityManager.killBackgroundProcesses()（需要 KILL_BACKGROUND_PROCESSES 权限）
 * - readFile/writeFile: 使用 java.io.File
 * - 通知: 直接使用 NotificationManager
 * - 其他需要特权的操作: 返回提示信息
 */
class ShellExecutorAdapter(
    private val impl: ShellExecutorImpl,
    private val context: Context
) : ShellExecutorInterface {

    override suspend fun exec(command: String): ClawActionResult {
        val result = impl.execute(command)
        return if (result.isSuccess) {
            val output = result.stdout.trim()
            if (output.isNotEmpty()) {
                ClawActionResult.success("命令执行成功: $output", output)
            } else {
                ClawActionResult.success("命令执行成功(无输出)")
            }
        } else {
            // 本地执行也失败，给出清晰提示
            val errorDetail = result.stderr.ifBlank { "未知错误" }
            ClawActionResult.failure("命令执行失败: $errorDetail", result.stdout)
        }
    }

    override suspend fun openApp(packageName: String): ClawActionResult {
        val errors = mutableListOf<String>()

        // 预检：确认应用已安装
        val isInstalled = try {
            context.packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: Exception) { false }
        if (!isInstalled) {
            return ClawActionResult.failure("应用 $packageName 未安装，请确认包名或使用 APP_SEARCH 按名称搜索")
        }

        // 方式1：使用 Shizuku 执行 am start（最可靠，不受后台启动限制）
        if (impl.isShizukuAvailable()) {
            // 先尝试 am start -n（指定完整组件名，最精确）
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            val componentName = launchIntent?.component?.flattenToString()
            if (componentName != null) {
                val result1 = impl.execute("am start -n '$componentName'")
                if (result1.isSuccess && !result1.stdout.contains("Error", ignoreCase = true)) {
                    delay(1000) // 等待应用启动
                    if (verifyAppForeground(packageName)) {
                        return ClawActionResult.success("已打开应用 $packageName")
                    }
                } else {
                    errors.add("am start -n: ${result1.stderr.ifBlank { result1.stdout }}")
                }
            }

            // 降级：monkey 命令（通过 Shizuku shell 执行）
            val result2 = impl.execute("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            if (result2.isSuccess) {
                delay(1000)
                if (verifyAppForeground(packageName)) {
                    return ClawActionResult.success("已打开应用 $packageName")
                }
            } else {
                errors.add("monkey(shizuku): ${result2.stderr.ifBlank { result2.stdout }}")
            }
        }

        // 方式2：使用 PackageManager + startActivity（需要悬浮窗权限或应用在前台）
        val hasOverlay = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else true
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                context.startActivity(intent)
                delay(1000)
                if (verifyAppForeground(packageName)) {
                    return ClawActionResult.success("已打开应用 $packageName (本地方式)")
                }
                // 即使验证失败也可能已启动（无障碍服务未就绪等），返回成功但带警告
                return ClawActionResult.success("已尝试打开应用 $packageName (请确认是否成功)")
            } else {
                errors.add("getLaunchIntentForPackage 返回 null（应用无可启动入口）")
            }
        } catch (e: android.content.ActivityNotFoundException) {
            errors.add("ActivityNotFoundException: ${e.message}")
        } catch (e: SecurityException) {
            val hint = if (!hasOverlay) "缺少悬浮窗权限，无法从后台启动应用" else "可能被应用锁拦截"
            errors.add("SecurityException($hint): ${e.message}")
        } catch (e: Exception) {
            errors.add("${e.javaClass.simpleName}: ${e.message}")
        }

        // 方式3：最后尝试 monkey 命令（本地 Runtime.exec，无需 Shizuku）
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>&1"))
            val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (finished && proc.exitValue() == 0) {
                delay(1000)
                if (verifyAppForeground(packageName)) {
                    return ClawActionResult.success("已打开应用 $packageName (monkey方式)")
                }
                return ClawActionResult.success("已尝试打开应用 $packageName (monkey方式，请确认)")
            }
        } catch (e: Exception) {
            errors.add("monkey-local: ${e.message}")
        }

        val overlayHint = if (!hasOverlay && !impl.isShizukuAvailable()) {
            "\n重要：未开启悬浮窗权限，Android 10+可能限制后台启动应用。请授予悬浮窗权限或开启Shizuku。"
        } else ""
        return ClawActionResult.failure(
            "打开应用 $packageName 失败。尝试了多种方式均未成功：\n${errors.joinToString("\n")}$overlayHint"
        )
    }

    /** 等待后验证目标应用是否已成为前台应用。 */
    private suspend fun verifyAppForeground(packageName: String): Boolean {
        delay(300)
        val currentPkg = try {
            ScreenAccessibilityService.instance?.lastWindowPackage
        } catch (e: Exception) { null }
        return currentPkg == packageName
    }

    override suspend fun closeApp(packageName: String): ClawActionResult {
        // 方式1：Shizuku 执行 am force-stop（最可靠）
        if (impl.isShizukuAvailable()) {
            val result = impl.execute("am force-stop $packageName")
            if (result.isSuccess) return ClawActionResult.success("已关闭应用 $packageName")
        }
        // 方式2：使用 ActivityManager.killBackgroundProcesses（需要 KILL_BACKGROUND_PROCESSES 权限）
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(packageName)
            // 方式3：再尝试通过无障碍服务按返回键退出
            val service = ScreenAccessibilityService.instance
            if (service != null) {
                val impl2 = com.mobileclaw.app.accessibility.ScreenController(service)
                impl2.pressBack()
            }
            ClawActionResult.success("已尝试关闭应用 $packageName")
        } catch (e: Exception) {
            ClawActionResult.failure("关闭应用 $packageName 失败: ${e.message}（建议开启 Shizuku 获取更强权限）")
        }
    }

    override suspend fun searchApp(name: String): ClawActionResult {
        return try {
            val pm = context.packageManager
            val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)

            // 构建应用名->包名映射
            data class AppEntry(val label: String, val packageName: String, val score: Int)
            val matches = mutableListOf<AppEntry>()

            for (appInfo in allApps) {
                val appLabel = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { continue }
                val pkg = appInfo.packageName

                // 精确匹配 = 100分
                if (appLabel.equals(name, ignoreCase = true)) {
                    matches.add(AppEntry(appLabel, pkg, 100))
                    continue
                }
                // 应用名包含搜索词 = 80分
                if (appLabel.contains(name, ignoreCase = true)) {
                    matches.add(AppEntry(appLabel, pkg, 80))
                    continue
                }
                // 搜索词包含应用名 = 70分
                if (name.contains(appLabel, ignoreCase = true)) {
                    matches.add(AppEntry(appLabel, pkg, 70))
                    continue
                }
                // 包名包含搜索词 = 50分
                if (pkg.contains(name, ignoreCase = true)) {
                    matches.add(AppEntry(appLabel, pkg, 50))
                    continue
                }
                // 拼音首字母模糊匹配（简单版：检查包名中的关键词）
                val pkgShort = pkg.substringAfterLast('.')
                if (pkgShort.contains(name, ignoreCase = true)) {
                    matches.add(AppEntry(appLabel, pkg, 40))
                    continue
                }
            }

            if (matches.isNotEmpty()) {
                // 按匹配分数排序，取最佳匹配
                matches.sortByDescending { it.score }
                val target = matches.first()
                // 使用 openApp 打开（复用多种启动方式）
                val openResult = openApp(target.packageName)
                if (openResult.success) {
                    ClawActionResult.success("已打开应用「${target.label}」(${target.packageName})", target.packageName)
                } else {
                    // openApp 失败了，再试一次直接 startActivity
                    val intent = pm.getLaunchIntentForPackage(target.packageName)
                    if (intent != null) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                            ClawActionResult.success("已打开应用「${target.label}」(${target.packageName})", target.packageName)
                        } catch (e: Exception) {
                            ClawActionResult.failure("找到应用「${target.label}」但启动失败: ${e.message}")
                        }
                    } else {
                        ClawActionResult.failure("找到应用「${target.label}」但无启动入口")
                    }
                }
            } else {
                ClawActionResult.failure("未找到名称包含「$name」的应用，请确认应用名称")
            }
        } catch (e: Exception) {
            ClawActionResult.failure("搜索应用失败: ${e.message}")
        }
    }

    override suspend fun listApps(): ClawActionResult {
        return try {
            val pm = context.packageManager
            val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            val appList = allApps.mapNotNull { appInfo ->
                val label = try { pm.getApplicationLabel(appInfo).toString() } catch (e: Exception) { null }
                if (label != null) "$label (${appInfo.packageName})" else null
            }.sorted()
            val result = appList.joinToString("\n")
            ClawActionResult.success("共 ${appList.size} 个应用", result)
        } catch (e: Exception) {
            ClawActionResult.failure("列出应用失败: ${e.message}")
        }
    }

    override suspend fun installApp(apkPath: String): ClawActionResult {
        // 安装应用需要特权，Shizuku 不可用时尝试使用系统安装 Intent
        if (impl.isShizukuAvailable()) {
            val result = impl.execute("pm install $apkPath", asRoot = true)
            if (result.isSuccess && result.stdout.contains("Success", ignoreCase = true)) {
                return ClawActionResult.success("应用安装成功: $apkPath")
            } else {
                return ClawActionResult.failure("应用安装失败: ${result.stderr.ifBlank { result.stdout }}")
            }
        }
        // 降级：使用系统安装 Intent
        return try {
            val file = java.io.File(apkPath)
            if (!file.exists()) {
                return ClawActionResult.failure("APK 文件不存在: $apkPath")
            }
            val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
            } else {
                android.net.Uri.fromFile(file)
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ClawActionResult.success("已触发系统安装界面 (本地方式)")
        } catch (e: Exception) {
            ClawActionResult.failure("安装应用失败（需要 Shizuku 权限或 FileProvider 配置）: ${e.message}")
        }
    }

    override suspend fun uninstallApp(packageName: String): ClawActionResult {
        // 优先使用 Shizuku
        if (impl.isShizukuAvailable()) {
            val result = impl.execute("pm uninstall $packageName")
            if (result.isSuccess && result.stdout.contains("Success", ignoreCase = true)) {
                return ClawActionResult.success("应用卸载成功: $packageName")
            } else {
                return ClawActionResult.failure("应用卸载失败: ${result.stderr.ifBlank { result.stdout }}")
            }
        }
        // 降级：使用系统卸载 Intent
        return try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_DELETE,
                android.net.Uri.parse("package:$packageName")
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ClawActionResult.success("已触发系统卸载界面 (本地方式)")
        } catch (e: Exception) {
            ClawActionResult.failure("卸载应用失败: ${e.message}")
        }
    }

    override suspend fun readFile(path: String): ClawActionResult {
        // 优先使用 Shizuku（可读特权文件）
        if (impl.isShizukuAvailable()) {
            val result = impl.execute("cat '$path'")
            if (result.isSuccess) return ClawActionResult.success("读取成功", result.stdout)
        }
        // 降级：直接使用 java.io.File
        return try {
            val file = java.io.File(path)
            if (file.exists() && file.canRead()) {
                ClawActionResult.success("读取成功", file.readText())
            } else {
                ClawActionResult.failure("无法读取文件 $path（文件不存在或无权限，需要 Shizuku）")
            }
        } catch (e: Exception) {
            ClawActionResult.failure("读取文件失败: ${e.message}")
        }
    }

    override suspend fun writeFile(path: String, content: String): ClawActionResult {
        // 优先使用 Shizuku
        if (impl.isShizukuAvailable()) {
            val escaped = content.replace("'", "'\\''").replace("\\", "\\\\")
            val result = impl.execute("echo '$escaped' > '$path'")
            if (result.isSuccess) return ClawActionResult.success("写入成功: $path")
        }
        // 降级：直接使用 java.io.File
        return try {
            val file = java.io.File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ClawActionResult.success("写入成功: $path")
        } catch (e: Exception) {
            ClawActionResult.failure("写入文件失败（可能需要 Shizuku 权限）: ${e.message}")
        }
    }

    override suspend fun readNotifications(): ClawActionResult {
        // 优先使用 Shizuku
        if (impl.isShizukuAvailable()) {
            val result = impl.execute("dumpsys notification --noredact")
            if (result.isSuccess) {
                return ClawActionResult.success("通知读取成功", result.stdout.take(3000))
            }
        }
        // 降级：通过无障碍服务获取通知
        return try {
            val service = com.mobileclaw.app.accessibility.ScreenAccessibilityService.instance
            if (service != null) {
                // 无障碍服务可以通过主动通知获取
                ClawActionResult.success("通知读取（无障碍方式，数据有限）", "")
            } else {
                ClawActionResult.failure("读取通知需要 Shizuku 权限或无障碍服务")
            }
        } catch (e: Exception) {
            ClawActionResult.failure("读取通知失败: ${e.message}")
        }
    }

    override suspend fun sendNotification(title: String, content: String): ClawActionResult {
        return try {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "mobileclaw_agent_v2"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId, "MobileClaw Agent", android.app.NotificationManager.IMPORTANCE_DEFAULT
                )
                mgr.createNotificationChannel(channel)
            }
            val notification = android.app.Notification.Builder(context, channelId)
                .setSmallIcon(com.mobileclaw.app.R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)
                .build()
            mgr.notify(System.currentTimeMillis().toInt(), notification)
            ClawActionResult.success("通知已发送: $title")
        } catch (e: Exception) {
            ClawActionResult.failure("发送通知失败: ${e.message}")
        }
    }

    override suspend fun clipboardCopy(text: String): ClawActionResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("MobileClaw", text)
            clipboard.setPrimaryClip(clip)
            ClawActionResult.success("已复制「$text」到剪贴板")
        } catch (e: Exception) {
            ClawActionResult.failure("复制到剪贴板失败: ${e.message}")
        }
    }

    override suspend fun clipboardPaste(): ClawActionResult {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                // 尝试通过无障碍服务粘贴到当前输入框
                val service = ScreenAccessibilityService.instance
                if (service != null) {
                    val root = service.getRootInActiveWindowSafe()
                    if (root != null) {
                        val focusedNode = findFocusedEditableNode(root)
                        if (focusedNode != null) {
                            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                            ClawActionResult.success("已粘贴剪贴板内容「$text」")
                        } else {
                            ClawActionResult.success("剪贴板内容: $text（未找到输入框，请先点击输入框）", text)
                        }
                    } else {
                        ClawActionResult.success("剪贴板内容: $text", text)
                    }
                } else {
                    ClawActionResult.success("剪贴板内容: $text", text)
                }
            } else {
                ClawActionResult.failure("剪贴板为空")
            }
        } catch (e: Exception) {
            ClawActionResult.failure("粘贴失败: ${e.message}")
        }
    }

    /** 递归查找当前聚焦的可编辑节点。 */
    private fun findFocusedEditableNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        depth: Int = 0
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (depth > 10) return null
        if (node.isFocused && (node.isEditable || node.className?.toString()?.contains("EditText") == true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableNode(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    override suspend fun mediaControl(action: String): ClawActionResult {
        val keyEvent = when (action.uppercase()) {
            "PLAY", "PAUSE", "PLAY_PAUSE" -> 85
            "NEXT", "NEXT_TRACK" -> 87
            "PREVIOUS", "PREV_TRACK" -> 88
            "STOP" -> 86
            else -> return ClawActionResult.failure("不支持的媒体控制动作: $action（支持: PLAY/PAUSE/NEXT/PREVIOUS/STOP）")
        }
        return try {
            // 优先使用 Shizuku
            if (impl.isShizukuAvailable()) {
                val result = impl.execute("input keyevent $keyEvent")
                if (result.isSuccess) return ClawActionResult.success("媒体控制「$action」执行成功")
            }
            // 降级：使用本地 exec
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "input keyevent $keyEvent")).waitFor()
            ClawActionResult.success("媒体控制「$action」执行成功")
        } catch (e: Exception) {
            ClawActionResult.failure("媒体控制失败: ${e.message}")
        }
    }

    override suspend fun setVolume(volume: Int): ClawActionResult {
        return try {
            val clampedVolume = volume.coerceIn(0, 100)
            // 优先使用 Shizuku
            if (impl.isShizukuAvailable()) {
                val result = impl.execute("media volume --stream 3 --set $clampedVolume")
                if (result.isSuccess) return ClawActionResult.success("音量已设置为 $clampedVolume")
            }
            // 降级：使用 AudioManager
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val targetVol = (maxVol * clampedVolume / 100).coerceIn(0, maxVol)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
            ClawActionResult.success("音量已设置为 $clampedVolume%（实际档位: $targetVol/$maxVol）")
        } catch (e: Exception) {
            ClawActionResult.failure("设置音量失败: ${e.message}")
        }
    }

    override suspend fun setBrightness(brightness: Int): ClawActionResult {
        return try {
            val clampedBrightness = brightness.coerceIn(0, 255)
            // 优先使用 Shizuku
            if (impl.isShizukuAvailable()) {
                val result = impl.execute("settings put system screen_brightness $clampedBrightness")
                if (result.isSuccess) {
                    return ClawActionResult.success("亮度已设置为 $clampedBrightness（需要写入设置权限）")
                }
            }
            // 降级：尝试直接写入 Settings.System（需要 WRITE_SETTINGS 权限）
            val canWrite = android.provider.Settings.System.canWrite(context)
            if (canWrite) {
                android.provider.Settings.System.putInt(
                    context.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    clampedBrightness
                )
                ClawActionResult.success("亮度已设置为 $clampedBrightness")
            } else {
                ClawActionResult.failure("设置亮度需要「修改系统设置」权限，请到设置中授权")
            }
        } catch (e: Exception) {
            ClawActionResult.failure("设置亮度失败: ${e.message}")
        }
    }

    override suspend fun setTimer(durationSec: Int): ClawActionResult {
        return try {
            // 使用系统闹钟设置定时器（ACTION_SET_TIMER 需要 API 21+）
            val intent = android.content.Intent("android.intent.action.SET_TIMER").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("android.intent.extra.LENGTH", durationSec)
                putExtra("android.intent.extra.MESSAGE", "MobileClaw 定时器")
                putExtra("android.intent.extra.SKIP_UI", false)
            }
            context.startActivity(intent)
            ClawActionResult.success("已设置 ${durationSec}秒 定时器")
        } catch (e: android.content.ActivityNotFoundException) {
            // 降级：发送通知作为提醒
            try {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val channelId = "mobileclaw_timer_v2"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        channelId, "MobileClaw Timer", android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                    mgr.createNotificationChannel(channel)
                }
                val notification = android.app.Notification.Builder(context, channelId)
                    .setSmallIcon(com.mobileclaw.app.R.drawable.ic_notification)
                    .setContentTitle("定时器")
                    .setContentText("已设置 ${durationSec}秒 定时器（设备不支持系统定时器）")
                    .build()
                mgr.notify(System.currentTimeMillis().toInt(), notification)
                ClawActionResult.success("已通过通知设置 ${durationSec}秒 定时提醒")
            } catch (e2: Exception) {
                ClawActionResult.failure("设置定时器失败：设备不支持系统定时器，且通知发送失败")
            }
        } catch (e: Exception) {
            ClawActionResult.failure("设置定时器失败: ${e.message}")
        }
    }
}
