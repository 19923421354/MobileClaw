package com.mobileclaw.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 屏幕无障碍服务
 *
 * 继承自 [AccessibilityService]，是整个屏幕操控能力的核心入口。
 * 通过单例引用 [instance] 供其他模块（如 [ScreenController]）获取服务实例，
 * 进而执行点击、滑动、截屏、UI 树获取等操作。
 *
 * 服务需在系统“无障碍设置”中由用户手动开启，开启后：
 * 1. [onServiceConnected] 会被回调，完成单例赋值与服务配置初始化；
 * 2. [onAccessibilityEvent] 会持续接收界面变化事件；
 * 3. 通过 [getRootInActiveWindowSafe] 等方法可获取当前界面节点树。
 *
 * 静态能力（截屏、手势模拟、获取窗口内容）在 res/xml/accessibility_config.xml 中声明，
 * 运行时事件监听范围在 [configureService] 中进行增量配置。
 */
class ScreenAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenA11yService"

        /**
         * 服务单例引用。服务连接后非 null，断开后置 null。
         * 使用 @Volatile 保证多线程可见性。
         */
        @Volatile
        var instance: ScreenAccessibilityService? = null
            private set

        /**
         * 判断服务是否已连接并可用。
         *
         * @return true 表示服务已连接
         */
        fun isConnected(): Boolean = instance != null
    }

    /** 最近一次窗口状态变化事件对应的包名，便于其他模块感知当前前台应用 */
    @Volatile
    var lastWindowPackage: String? = null
        private set

    /**
     * 服务连接成功时回调。
     *
     * 在此完成单例赋值与运行时服务配置，确保监听所有应用的关键事件。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接，开始初始化配置")
        configureService()
        // 通知 Application 服务已就绪
        try {
            com.mobileclaw.app.MobileClawApp.instance.onAccessibilityConnected()
        } catch (e: Exception) {
            Log.w(TAG, "通知 Application 失败", e)
        }
    }

    /**
     * 配置无障碍服务信息。
     *
     * 注意：本服务的静态能力（如截屏 canTakeScreenshots、手势 canPerformGestures、
     * 获取窗口内容 canRetrieveWindowContent）由 XML 配置文件 accessibility_config.xml 声明。
     * 此处在运行时基于已有的 [serviceInfo] 进行增量修改，仅调整监听的应用包名、事件类型与标志位，
     * 避免新建 [AccessibilityServiceInfo] 覆盖掉 XML 中声明的能力。
     */
    private fun configureService() {
        try {
            // 基于已有 serviceInfo 增量修改，保留 XML 声明的静态能力
            val info: AccessibilityServiceInfo = serviceInfo ?: AccessibilityServiceInfo()
            info.apply {
                // packageNames = null 表示监听所有应用
                packageNames = null
                // 监听的事件类型：窗口状态变化、窗口内容变化、点击、滚动
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED
                // 通用反馈类型
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                // 在保留已有标志位的基础上，附加报告视图ID与获取交互窗口的标志
                flags = flags or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                // 事件通知间隔（毫秒）
                notificationTimeout = 100L
            }
            // Android 14+ 通过反射设置 canTakeScreenshots（XML 属性在旧版 build-tools 中不被识别）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    val field = AccessibilityServiceInfo::class.java.getField("canTakeScreenshots")
                    field.setBoolean(info, true)
                } catch (e: NoSuchFieldException) {
                    // API 34 以下没有此字段，忽略
                }
            }
            serviceInfo = info
            Log.i(TAG, "无障碍服务运行时配置完成")
        } catch (e: Exception) {
            Log.e(TAG, "配置无障碍服务失败", e)
        }
    }

    /**
     * 接收并处理无障碍事件。
     *
     * 当前实现主要记录界面变化日志，并维护 [lastWindowPackage]。
     * 可根据业务需要在此处扩展事件分发逻辑（如通知观察者）。
     *
     * @param event 无障碍事件，可能为 null
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 窗口状态变化（通常对应前台应用切换 / 新界面打开）
                val pkg = event.packageName?.toString()
                if (pkg != null) {
                    lastWindowPackage = pkg
                }
                Log.d(TAG, "窗口状态变化: package=$pkg, class=${event.className}")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.v(TAG, "窗口内容变化: package=${event.packageName}")
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "控件被点击: ${event.className}")
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                Log.d(TAG, "控件发生滚动")
            }
            else -> {
                // 其他事件类型暂不处理
            }
        }
    }

    /**
     * 服务被系统中断时回调。
     */
    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    /**
     * 服务被销毁时回调，清理单例引用。
     */
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        lastWindowPackage = null
        Log.i(TAG, "无障碍服务已断开")
    }

    // ==================== 节点查询相关方法 ====================

    /**
     * 安全地获取当前活动窗口的根节点。
     * 对异常进行捕获，避免因无权限或窗口未就绪导致崩溃。
     *
     * @return 根节点 [AccessibilityNodeInfo]，失败时返回 null
     */
    fun getRootInActiveWindowSafe(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "获取活动窗口根节点失败", e)
            null
        }
    }

    /**
     * 根据显示文本查找当前界面上的控件节点。
     *
     * @param text 要查找的文本（大小写不敏感，支持部分匹配）
     * @return 匹配到的节点列表，无匹配时返回空列表
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = getRootInActiveWindowSafe() ?: return emptyList()
        return try {
            root.findAccessibilityNodeInfosByText(text)
        } catch (e: Exception) {
            Log.e(TAG, "根据文本查找节点失败: $text", e)
            emptyList()
        }
    }

    /**
     * 根据视图资源 ID 查找当前界面上的控件节点。
     *
     * @param viewId 视图ID，格式如 "com.android.settings:id/title"
     * @return 匹配到的节点列表，无匹配时返回空列表
     */
    fun findNodesById(viewId: String): List<AccessibilityNodeInfo> {
        val root = getRootInActiveWindowSafe() ?: return emptyList()
        return try {
            root.findAccessibilityNodeInfosByViewId(viewId)
        } catch (e: Exception) {
            Log.e(TAG, "根据ID查找节点失败: $viewId", e)
            emptyList()
        }
    }

    /**
     * 获取当前拥有输入焦点的节点（用于文本输入场景）。
     *
     * @return 焦点节点，失败时返回 null
     */
    fun findFocusedNode(): AccessibilityNodeInfo? {
        val root = getRootInActiveWindowSafe() ?: return null
        return try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            Log.e(TAG, "查找焦点节点失败", e)
            null
        }
    }
}
