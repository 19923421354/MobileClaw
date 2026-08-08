package com.mobileclaw.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mobileclaw.app.MobileClawApp
import com.mobileclaw.app.MainActivity
import com.mobileclaw.app.ModelManagementActivity
import com.mobileclaw.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮快捷面板服务。
 *
 * 在屏幕边缘显示一个可拖拽的悬浮按钮，点击展开快捷操作面板：
 * - 截屏、返回、Home、最近任务
 * - 快速打开微信/支付宝等常用应用
 * - 语音输入入口
 * - 展开后输入自定义指令
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限（悬浮窗权限）。
 *
 * 使用方式：
 * ```
 * FloatingPanelService.start(context)  // 显示悬浮按钮
 * FloatingPanelService.stop(context)   // 隐藏悬浮按钮
 * FloatingPanelService.toggle(context) // 切换显示/隐藏
 * ```
 */
class FloatingPanelService : Service() {

    companion object {
        private const val CHANNEL_ID = "mobileclaw_floating"
        private const val NOTIFICATION_ID = 1002
        private const val BUTTON_SIZE = 56 // dp
        private const val PANEL_WIDTH = 280 // dp
        private const val PANEL_HEIGHT = 400 // dp

        /** 启动悬浮面板服务。 */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, FloatingPanelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 停止悬浮面板服务。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPanelService::class.java))
        }

        /** 切换悬浮面板显示状态。 */
        fun toggle(context: Context) {
            if (isRunning) {
                stop(context)
            } else {
                start(context)
            }
        }

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var expandedPanel: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        showFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        hideFloatingButton()
        hideExpandedPanel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================================
    //  悬浮按钮
    // =========================================================================

    /** 创建并显示可拖拽的悬浮按钮。 */
    private fun showFloatingButton() {
        if (floatingButton != null) return

        val sizePx = dp(BUTTON_SIZE)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC6C5CE7"))
            setStroke(dp(2), Color.parseColor("#FFFFFF"))
        }

        val button = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = drawable
            elevation = dp(6).toFloat()
        }

        val icon = TextView(this).apply {
            text = "爪"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
        }
        button.addView(icon)

        // 拖拽 + 点击逻辑
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams(v).x
                    initialY = layoutParams(v).y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    updateButtonPosition(
                        initialX + dx.toInt(),
                        initialY + dy.toInt()
                    )
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 点击：展开面板
                        showExpandedPanel()
                    }
                    // 吸附到屏幕边缘
                    snapToEdge()
                }
            }
            true
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(200)
        }

        windowManager.addView(button, params)
        floatingButton = button
    }

    /** 隐藏悬浮按钮。 */
    private fun hideFloatingButton() {
        floatingButton?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        floatingButton = null
    }

    /** 更新按钮位置。 */
    private fun updateButtonPosition(x: Int, y: Int) {
        floatingButton?.let { view ->
            val params = layoutParams(view)
            params.x = x
            params.y = y
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
    }

    /** 吸附到最近的屏幕边缘。 */
    private fun snapToEdge() {
        floatingButton?.let { view ->
            val params = layoutParams(view)
            val screenWidth = resources.displayMetrics.widthPixels
            params.x = if (params.x + view.width / 2 < screenWidth / 2) 0 else screenWidth - view.width
            try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
        }
    }

    // =========================================================================
    //  展开面板
    // =========================================================================

    /** 显示展开的快捷操作面板。 */
    private fun showExpandedPanel() {
        if (expandedPanel != null) {
            hideExpandedPanel()
            return
        }

        val panel = createExpandedPanel()
        val params = WindowManager.LayoutParams(
            dp(PANEL_WIDTH),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(panel, params)
            expandedPanel = panel
        } catch (e: Exception) {
            Toast.makeText(this, "显示面板失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 隐藏展开面板。 */
    private fun hideExpandedPanel() {
        expandedPanel?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        expandedPanel = null
    }

    /** 创建展开的操作面板视图。 */
    private fun createExpandedPanel(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F0FFFFFF"))
                setStroke(dp(1), Color.parseColor("#E0E0E0"))
            }
            elevation = dp(10).toFloat()
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 标题栏
        val title = TextView(this).apply {
            text = "灵爪快捷面板"
            setTextColor(Color.parseColor("#333333"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(title)

        // 快捷操作按钮网格
        val gridLayout = createQuickActionGrid()
        container.addView(gridLayout)

        // 模型管理按钮
        val modelBtnRow = createModelManagementRow()
        container.addView(modelBtnRow)

        // 常用应用快捷行
        val appRow = createAppShortcutRow()
        container.addView(appRow)

        // 关闭按钮
        val closeBtn = TextView(this).apply {
            text = "收起"
            setTextColor(Color.parseColor("#666666"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { hideExpandedPanel() }
        }
        container.addView(closeBtn)

        return container
    }

    /** 创建快捷操作按钮网格。 */
    private fun createQuickActionGrid(): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val quickActions = listOf(
            "截屏" to "截个屏",
            "返回" to "按返回键",
            "主屏" to "按Home键",
            "多任务" to "打开最近任务",
            "清理" to "清理缓存",
            "音量+" to "音量增大",
            "音量-" to "音量减小",
            "锁屏" to "锁屏"
        )

        // 每行4个按钮
        quickActions.chunked(4).forEach { rowActions ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowActions.forEach { (label, command) ->
                val btn = createActionButton(label) {
                    executeCommand(command)
                    hideExpandedPanel()
                }
                row.addView(btn)
            }
            grid.addView(row)
        }

        return grid
    }

    /** 创建模型管理按钮行。 */
    private fun createModelManagementRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val btnView = createActionButton("模型管理") {
            val intent = Intent(this@FloatingPanelService, ModelManagementActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            hideExpandedPanel()
        }
        // 让模型管理按钮占满整行
        btnView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        // 设置一个不同的背景色突出显示
        val bg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#E8F0FE"))
            setStroke(dp(1), Color.parseColor("#1A73E8"))
        }
        btnView.background = bg
        (btnView as TextView).setTextColor(Color.parseColor("#1A73E8"))
        row.addView(btnView)

        return row
    }

    /** 创建常用应用快捷行。 */
    private fun createAppShortcutRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        val apps = listOf("微信" to "打开微信", "支付宝" to "打开支付宝", "抖音" to "打开抖音", "设置" to "打开设置")
        apps.forEach { (label, command) ->
            val btn = createActionButton(label) {
                executeCommand(command)
                hideExpandedPanel()
            }
            row.addView(btn)
        }

        return row
    }

    /** 创建单个操作按钮。 */
    private fun createActionButton(label: String, onClick: () -> Unit): View {
        val margin = dp(4)
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#333333"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#F0F0F0"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener { onClick() }
        }
    }

    // =========================================================================
    //  命令执行
    // =========================================================================

    /** 执行快捷指令。 */
    private fun executeCommand(command: String) {
        val controller = MobileClawApp.clawController
        if (controller == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "执行: $command", Toast.LENGTH_SHORT).show()

        scope.launch {
            controller.execute(command, object : com.mobileclaw.app.ai.ClawCallback {
                override fun onStatusUpdate(status: String) {
                    scope.launch {
                        Toast.makeText(this@FloatingPanelService, status, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFinalResult(result: String) {
                    scope.launch {
                        Toast.makeText(this@FloatingPanelService, result, Toast.LENGTH_LONG).show()
                    }
                }
                override fun onError(error: Throwable) {
                    scope.launch {
                        Toast.makeText(this@FloatingPanelService, "出错: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    // =========================================================================
    //  通知与工具方法
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "悬浮面板服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮快捷面板运行"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("灵爪悬浮面板")
                .setContentText("悬浮快捷面板运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("灵爪悬浮面板")
                .setContentText("悬浮快捷面板运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        }
    }

    /** dp 转 px。 */
    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt()

    /** 获取 View 的 LayoutParams（WindowManager.LayoutParams）。 */
    @Suppress("UNCHECKED_CAST")
    private fun layoutParams(view: View): WindowManager.LayoutParams =
        view.layoutParams as WindowManager.LayoutParams
}
