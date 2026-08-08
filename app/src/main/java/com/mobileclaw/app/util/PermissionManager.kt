package com.mobileclaw.app.util

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限管理器
 *
 * 统一管理 MobileClaw 所需的所有运行时权限与特殊权限的检测与申请：
 * - 基本运行时权限（存储、通知、手机状态等）
 * - 特殊权限（悬浮窗、管理存储、使用情况访问、忽略电池优化）
 * - 无障碍服务状态
 * - Shizuku/STELLAR 等调试权限状态
 *
 * 使用 [PermissionStatus] 统一描述每项权限的状态，便于 UI 展示。
 */
object PermissionManager {

    /** 日志标签 */
    private const val TAG = "PermissionManager"

    /** 基本运行时权限请求码 */
    const val REQUEST_CODE_BASIC = 2001

    /** Shizuku 及兼容应用（STELLAR 等）的包名列表 */
    val SHIZUKU_COMPATIBLE_PACKAGES = listOf(
        "moe.shizuku.privileged.api",     // Shizuku 新版（官方）
        "moe.shizuku.privilegedapi",      // Shizuku 旧版
        "moe.shizuku.manager",            // Shizuku Manager（历史包名）
        "rikka.shizuku.server",           // Shizuku Server
        "roro.stellar.manager",           // STELLAR（Shizuku 协议兼容分支，正确包名）
        "com.stellar.privileged.api",     // STELLAR 备用包名
        "com.rosan.dhizuku",              // Dhizuku（共享 Shizuku 权限模型）
        "com.android.shellms",            // ShellMS
        "com.goshujinsan.shellms",        // ShellMS 备用
        "io.github.muntashirakon.adb",    // ADB on phone
        "com.farmerbb.taskbar"            // Taskbar（辅助显示）
    )

    /** STELLAR 的正确包名（Shizuku 协议兼容分支） */
    const val STELLAR_PACKAGE = "roro.stellar.manager"

    /** Shizuku/STELLAR 服务绑定 Intent action（用于回退检测服务是否运行） */
    const val SHIZUKU_SERVICE_ACTION = "rikka.shizuku.intent.action.BIND_SHIZUKU"

    /** Shizuku 官方下载页 */
    const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/"

    /**
     * STELLAR 下载页。
     *
     * STELLAR 无统一官方网站，主要通过各应用市场分发，此处指向搜索引擎结果页，
     * 便于用户自行选择可信下载来源。
     */
    const val STELLAR_DOWNLOAD_URL = "https://www.bing.com/search?q=STELLAR+roro.stellar.manager+APK+下载"

    /** 已知存在自启动管理页的厂商列表 */
    private val AUTO_START_MANUFACTURERS = setOf(
        "xiaomi", "redmi", "huawei", "honor", "oppo", "vivo",
        "samsung", "meizu", "letv", "asus", "oneplus", "realme", "iqoo"
    )

    /**
     * 权限状态数据类。
     *
     * @param name 权限显示名
     * @param granted 是否已授权
     * @param required 是否必需（false 表示可选/增强功能）
     * @param intentTarget 跳转设置页面的 Intent action 或包名
     */
    data class PermissionStatus(
        val name: String,
        val granted: Boolean,
        val required: Boolean,
        val description: String,
        val type: PermissionType
    )

    enum class PermissionType {
        ACCESSIBILITY,
        OVERLAY,
        STORAGE,
        NOTIFICATION,
        BATTERY_OPTIMIZATION,
        USAGE_STATS,
        SHIZUKU,
        AUTO_START,
        PHONE_STATE,
        CAMERA,
        LOCATION,
        MICROPHONE,
        CONTACTS,
        CALENDAR,
        SMS,
        CALL_LOG
    }

    /**
     * 获取所有需要检查的权限状态列表。
     */
    fun getAllPermissions(context: Context): List<PermissionStatus> {
        return listOf(
            PermissionStatus(
                name = "无障碍服务",
                granted = isAccessibilityEnabled(context),
                required = true,
                description = "核心权限，用于屏幕操控（点击/滑动/输入/截屏）",
                type = PermissionType.ACCESSIBILITY
            ),
            PermissionStatus(
                name = "存储权限",
                granted = hasStoragePermission(context),
                required = false,
                description = "用于截图保存、文件读写",
                type = PermissionType.STORAGE
            ),
            PermissionStatus(
                name = "悬浮窗权限",
                granted = hasOverlayPermission(context),
                required = false,
                description = "允许后台启动应用，是打开其他应用的关键权限",
                type = PermissionType.OVERLAY
            ),
            PermissionStatus(
                name = "通知权限",
                granted = hasNotificationPermission(context),
                required = false,
                description = "用于显示操作进度通知",
                type = PermissionType.NOTIFICATION
            ),
            PermissionStatus(
                name = "电池优化白名单",
                granted = isIgnoringBatteryOptimizations(context),
                required = false,
                description = "防止后台被杀，保持持续运行",
                type = PermissionType.BATTERY_OPTIMIZATION
            ),
            PermissionStatus(
                name = "使用情况访问",
                granted = hasUsageStatsPermission(context),
                required = false,
                description = "查看应用使用情况",
                type = PermissionType.USAGE_STATS
            ),
            PermissionStatus(
                name = "调试权限 (Shizuku/STELLAR)",
                granted = isShizukuAvailable(context),
                required = false,
                description = getShizukuStatusDescription(context),
                type = PermissionType.SHIZUKU
            ),
            PermissionStatus(
                name = "自启动权限",
                granted = isAutoStartEnabled(context),
                required = false,
                description = "厂商后台保活权限，确保开机/后台自动运行",
                type = PermissionType.AUTO_START
            ),
            PermissionStatus(
                name = "电话状态",
                granted = hasPhoneStatePermission(context),
                required = false,
                description = "获取设备信息",
                type = PermissionType.PHONE_STATE
            ),
            PermissionStatus(
                name = "相机权限",
                granted = hasCameraPermission(context),
                required = false,
                description = "用于扫码、拍照等场景",
                type = PermissionType.CAMERA
            ),
            PermissionStatus(
                name = "定位权限",
                granted = hasLocationPermission(context),
                required = false,
                description = "用于基于位置的任务",
                type = PermissionType.LOCATION
            ),
            PermissionStatus(
                name = "麦克风权限",
                granted = hasMicrophonePermission(context),
                required = false,
                description = "用于语音输入与录音",
                type = PermissionType.MICROPHONE
            ),
            PermissionStatus(
                name = "通讯录权限",
                granted = hasContactsPermission(context),
                required = false,
                description = "用于查找联系人信息",
                type = PermissionType.CONTACTS
            ),
            PermissionStatus(
                name = "日历权限",
                granted = hasCalendarPermission(context),
                required = false,
                description = "用于日历日程操作",
                type = PermissionType.CALENDAR
            ),
            PermissionStatus(
                name = "短信权限",
                granted = hasSmsPermission(context),
                required = false,
                description = "用于读取短信",
                type = PermissionType.SMS
            ),
            PermissionStatus(
                name = "通话记录权限",
                granted = hasCallLogPermission(context),
                required = false,
                description = "用于读取通话记录",
                type = PermissionType.CALL_LOG
            )
        )
    }

    // ==================== 权限检测方法 ====================

    /**
     * 检查无障碍服务是否已启用。
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        // 优先检查服务单例是否已连接
        if (com.mobileclaw.app.accessibility.ScreenAccessibilityService.isConnected()) {
            return true
        }
        // 回退：检查系统无障碍服务列表
        return try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val serviceName = "${context.packageName}/com.mobileclaw.app.accessibility.ScreenAccessibilityService"
            enabledServices.contains(serviceName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查是否有存储权限。
     * Android 11+ 使用 MANAGE_EXTERNAL_STORAGE，低版本使用 READ/WRITE_EXTERNAL_STORAGE。
     */
    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 检查是否有悬浮窗权限。
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 检查是否有通知权限（Android 13+）。
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 检查是否在电池优化白名单中。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    /**
     * 检查是否有使用情况访问权限。
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查是否有电话状态权限。
     */
    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有相机权限。
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有定位权限（精确定位 + 粗略定位）。
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有麦克风（录音）权限。
     */
    fun hasMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有通讯录权限（读 + 写）。
     */
    fun hasContactsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有日历权限（读 + 写）。
     */
    fun hasCalendarPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有短信读取权限。
     */
    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有通话记录读取权限。
     */
    fun hasCallLogPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查自启动权限状态（厂商特定，无法精确检测）。
     *
     * 自启动权限是部分厂商（小米/华为/OPPO/vivo 等）定制的后台保活机制，Android 原生并无
     * 对应 API，因此无法精确检测用户是否已授权。
     *
     * 策略：始终返回 true，不在权限列表中显示为"未授权"（避免误导用户）。
     * 用户可通过点击该权限项跳转到厂商自启动设置页手动确认。
     */
    fun isAutoStartEnabled(context: Context): Boolean {
        // 无法精确检测，返回 true 避免误报
        // 用户可通过点击权限项跳转到厂商设置页确认
        return true
    }

    /**
     * 检查 Shizuku 或兼容应用是否可用（已运行且已授权）。
     * 支持 Shizuku、STELLAR 等 Shizuku 协议兼容应用。
     *
     * 检测逻辑：
     * 1. 通过 Shizuku SDK 的 pingBinder 检测服务是否正在运行；
     * 2. 若 binder 可用，再检查应用是否已获得 Shizuku 权限授权；
     * 3. 若 binder 不可用但应用已安装，返回 false（需要用户启动服务并授权）。
     *
     * 注意：仅检测到兼容应用已安装但服务未启动或未授权时，返回 false。
     */
    fun isShizukuAvailable(context: Context): Boolean {
        // 通过 Shizuku SDK 检测：pingBinder（服务是否运行）+ checkSelfPermission（是否已授权）
        return try {
            com.mobileclaw.app.shizuku.ShizukuManager.isShizukuAvailable()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查 Shizuku/STELLAR 是否已安装（不论是否已启动/授权）。
     * 用于 UI 区分"未安装"和"已安装但未启动"两种状态。
     */
    fun isShizukuInstalled(context: Context): Boolean {
        // 先检查 Shizuku SDK 的 binder 是否可用
        try {
            if (com.mobileclaw.app.shizuku.ShizukuManager.isShizukuInstalled()) {
                return true
            }
        } catch (e: Exception) {
            // ignore
        }
        // 检查是否安装了兼容应用
        return getInstalledShizukuApp(context) != null
    }

    /**
     * 获取 Shizuku/STELLAR 的详细状态描述，用于 UI 展示。
     */
    private fun getShizukuStatusDescription(context: Context): String {
        val installed = isShizukuInstalled(context)
        if (!installed) {
            return "未安装。用于高级操作（Shell、安装应用等），点击下载"
        }
        // 已安装，检查 binder 是否可用
        val binderAlive = try {
            com.mobileclaw.app.shizuku.ShizukuManager.isShizukuInstalled()
        } catch (e: Exception) {
            false
        }
        if (!binderAlive) {
            val appName = getInstalledShizukuApp(context) ?: "调试应用"
            return "已安装$appName，点击重新连接（若已启动服务请稍候自动检测）"
        }
        // binder 可用，检查是否已授权
        val authorized = try {
            com.mobileclaw.app.shizuku.ShizukuManager.checkPermission()
        } catch (e: Exception) {
            false
        }
        return if (authorized) {
            "已授权，可用于高级操作（Shell、安装应用等）"
        } else {
            "服务已启动但未授权，点击授权"
        }
    }

    /**
     * 检查是否安装了某个应用。
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 获取已安装的 Shizuku 兼容应用包名。
     */
    fun getInstalledShizukuApp(context: Context): String? {
        for (pkg in SHIZUKU_COMPATIBLE_PACKAGES) {
            if (isAppInstalled(context, pkg)) return pkg
        }
        return null
    }

    // ==================== 权限申请方法 ====================

    /**
     * 申请基本运行时权限（存储、通知、电话状态，以及相机、定位、麦克风、
     * 通讯录、日历、短信、通话记录等）。
     */
    fun requestBasicPermissions(activity: Activity) {
        val permissions = mutableListOf<String>().apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.READ_PHONE_STATE)
            // 新增运行时权限
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.WRITE_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_CALL_LOG)
        }.toTypedArray()

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissions, REQUEST_CODE_BASIC)
        }
    }

    /**
     * 跳转到存储管理权限设置页（Android 11+）。
     */
    fun requestStoragePermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 跳转到悬浮窗权限设置页。
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 通过 Shizuku 一键启用无障碍服务。
     *
     * 使用 `settings put secure` 命令，无需用户手动进入设置页面。
     * 需要 Shizuku/STELLAR 已授权且服务正在运行。
     *
     * 原理：
     * 1. 读取当前已启用的无障碍服务列表
     * 2. 追加本应用的服务组件名
     * 3. 写回系统设置
     * 4. 确保 accessibility_enabled = 1
     *
     * @return true 表示成功启用，false 表示失败（Shizuku 不可用或命令执行失败）
     */
    fun enableAccessibilityViaShizuku(context: Context): Boolean {
        if (!com.mobileclaw.app.shizuku.ShizukuManager.isShizukuAvailable()) {
            Log.d(TAG, "enableAccessibilityViaShizuku: Shizuku is not available")
            return false
        }

        val serviceComponent = "${context.packageName}/com.mobileclaw.app.accessibility.ScreenAccessibilityService"

        return try {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val executor = com.mobileclaw.app.debug.ShellExecutor(context)

                // 1. 读取当前已启用的无障碍服务列表
                val readResult = executor.execute(
                    "settings get secure enabled_accessibility_services",
                    timeoutMs = 5000L
                )
                val currentServices = readResult.stdout.trim()

                // 如果已包含我们的服务，说明已启用
                if (currentServices.contains(serviceComponent)) {
                    Log.d(TAG, "accessibility service already enabled")
                    return@runBlocking true
                }

                // 2. 追加我们的服务到列表
                val newServices = if (currentServices.isBlank() || currentServices == "null") {
                    serviceComponent
                } else {
                    "$currentServices:$serviceComponent"
                }

                Log.d(TAG, "Setting enabled_accessibility_services = $newServices")

                // 3. 写入新列表
                val writeResult = executor.execute(
                    "settings put secure enabled_accessibility_services \"$newServices\"",
                    timeoutMs = 5000L
                )

                if (writeResult.exitCode != 0) {
                    Log.w(TAG, "settings put failed: exitCode=${writeResult.exitCode} stderr=${writeResult.stderr}")
                    return@runBlocking false
                }

                // 4. 确保无障碍功能全局开启
                executor.execute(
                    "settings put secure accessibility_enabled 1",
                    timeoutMs = 3000L
                )

                // 5. 短暂等待后验证
                kotlinx.coroutines.delay(500)
                val verifyResult = executor.execute(
                    "settings get secure enabled_accessibility_services",
                    timeoutMs = 3000L
                )
                val verified = verifyResult.stdout.contains(serviceComponent)
                Log.d(TAG, "enableAccessibilityViaShizuku: verified=$verified")
                verified
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableAccessibilityViaShizuku failed", e)
            false
        }
    }

    /**
     * 请求无障碍服务权限（智能版）。
     *
     * 执行策略：
     * 1. 优先尝试通过 Shizuku 一键启用（无需用户手动操作）
     * 2. Shizuku 不可用或失败时，跳转到系统无障碍设置页让用户手动开启
     *
     * Android 10+ 使用 [Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS] 直接打开
     * 本应用的无障碍服务详情页，比通用设置页更精确。
     */
    fun requestAccessibilityPermission(context: Context) {
        // 先尝试通过 Shizuku 一键启用
        if (context is Activity) {
            android.widget.Toast.makeText(
                context,
                "正在尝试一键开启无障碍服务…",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            val success = enableAccessibilityViaShizuku(context)
            if (success) {
                android.widget.Toast.makeText(
                    context,
                    "✅ 无障碍服务已一键开启！",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                // 通知控制器检查状态
                if (com.mobileclaw.app.accessibility.ScreenAccessibilityService.isConnected()) {
                    com.mobileclaw.app.MobileClawApp.instance.onAccessibilityConnected()
                }
                return
            }
        }

        // Shizuku 不可用或失败，跳转到设置页
        // 打开无障碍服务设置页，让用户手动开启
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        android.widget.Toast.makeText(
            context,
            "请在「已安装的服务」中找到「灵爪」并开启开关",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    /**
     * 跳转到电池优化白名单设置页。
     */
    fun requestBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 跳转到使用情况访问权限设置页。
     */
    fun requestUsageStatsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 跳转到自启动权限设置页（不同厂商使用不同的 Activity 组件）。
     *
     * 若无法识别当前厂商或对应设置页不存在，则回退到本应用的应用详情页，
     * 由用户在权限/电池相关入口中自行查找。
     */
    fun requestAutoStartPermission(context: Context) {
        val intent = getAutoStartIntent(context)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
                .onFailure { openAppDetailsSettings(context) }
        } else {
            // 未识别厂商，打开应用详情页让用户自行查找自启动入口
            openAppDetailsSettings(context)
        }
    }

    /**
     * 根据厂商构造自启动管理页的 Intent。
     *
     * @return 可解析的目标 Intent，若当前厂商未适配或设置页不存在则返回 null
     */
    private fun getAutoStartIntent(context: Context): Intent? {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val candidates: List<Intent> = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                listOf(componentIntent("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"))

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                listOf(
                    componentIntent("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    componentIntent("com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity")
                )

            manufacturer.contains("oppo") || manufacturer.contains("realme") ||
                manufacturer.contains("oneplus") ->
                listOf(
                    componentIntent("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    componentIntent("com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    componentIntent("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.FloatingWindowListActivity")
                )

            manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
                listOf(
                    componentIntent("com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    componentIntent("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                )

            manufacturer.contains("samsung") ->
                listOf(componentIntent("com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"))

            manufacturer.contains("meizu") ->
                listOf(componentIntent("com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"))

            manufacturer.contains("letv") ->
                listOf(componentIntent("com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity"))

            manufacturer.contains("asus") ->
                listOf(componentIntent("com.asus.mobilemanager",
                    "com.asus.mobilemanager.entry.FunctionActivity").apply {
                    putExtra("showFragment", "com.asus.mobilemanager.autostart.AutoStartActivity")
                })

            else -> emptyList()
        }

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        }
        return null
    }

    /** 构造显式组件 Intent 的快捷方法 */
    private fun componentIntent(pkg: String, cls: String): Intent {
        return Intent().setComponent(ComponentName(pkg, cls))
    }

    /** 回退入口：打开本应用的应用详情设置页 */
    private fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 打开已安装的 Shizuku 兼容应用。
     */
    fun openShizukuApp(context: Context) {
        val pkg = getInstalledShizukuApp(context)
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        // 未安装，打开 Shizuku 的 Play Store / 应用商店页面
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // 回退到浏览器
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /**
     * 智能处理 Shizuku/STELLAR 权限请求。
     *
     * 根据当前状态自动选择最佳操作：
     * 1. 未安装 → 弹出安装引导
     * 2. 已安装但服务未启动 → 打开应用让用户启动服务
     * 3. 服务已启动但未授权 → 调用 Shizuku.requestPermission() 弹出授权对话框
     * 4. 已授权 → 提示无需操作
     */
    fun requestShizukuPermission(context: Context) {
        // 1. 检查是否已安装
        if (!isShizukuInstalled(context)) {
            if (context is Activity) {
                openShizukuInstallGuide(context)
            } else {
                downloadAndInstallShizuku(context)
            }
            return
        }

        // 2. 先尝试强制重绑（解决 STELLAR 已启动但 Binder 未检测到的问题）
        com.mobileclaw.app.shizuku.ShizukuManager.forceRebind(context)

        // 3. 检查 binder 是否可用（服务是否已启动）
        val binderAlive = try {
            com.mobileclaw.app.shizuku.ShizukuManager.isShizukuInstalled()
        } catch (e: Exception) {
            false
        }

        if (!binderAlive) {
            // Binder 仍未检测到，打开 STELLAR 让用户启动服务
            openShizukuApp(context)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                android.widget.Toast.makeText(
                    context,
                    "请在打开的应用中启动服务（通过无线调试或Root），返回后将自动检测",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }, 500)
            // 启动后周期性检测 Binder 状态，一旦检测到就自动刷新
            scheduleShizukuRecheck(context)
            return
        }

        // 4. 服务已启动，检查是否已授权
        val authorized = try {
            com.mobileclaw.app.shizuku.ShizukuManager.checkPermission()
        } catch (e: Exception) {
            false
        }

        if (authorized) {
            android.widget.Toast.makeText(
                context,
                "调试权限已授权，无需重复操作",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 5. 未授权，调用 Shizuku.requestPermission() 弹出授权对话框
        try {
            com.mobileclaw.app.shizuku.ShizukuManager.requestPermission()
            android.widget.Toast.makeText(
                context,
                "请在弹出的对话框中点击「允许」",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            // requestPermission 失败，回退到打开应用
            openShizukuApp(context)
        }
    }

    // ==================== 一键安装调试软件 ====================

    /**
     * 周期性检测 Shizuku/STELLAR Binder 状态。
     *
     * 用户打开 STELLAR 后，可能需要几秒到十几秒才能启动服务并推送 Binder。
     * 此方法每 2 秒检测一次，持续 30 秒，一旦检测到 Binder 可用就停止。
     *
     * @param context 上下文
     */
    fun scheduleShizukuRecheck(context: Context) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val maxAttempts = 15  // 最多检测 15 次（30 秒）
        var attempts = 0

        val runnable = object : Runnable {
            override fun run() {
                attempts++
                // 强制重绑并刷新状态
                com.mobileclaw.app.shizuku.ShizukuManager.forceRebind(context)

                val available = try {
                    com.mobileclaw.app.shizuku.ShizukuManager.isShizukuAvailable()
                } catch (e: Exception) {
                    false
                }

                if (available) {
                    android.widget.Toast.makeText(
                        context,
                        "调试权限已连接！",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return  // 检测到，停止轮询
                }

                if (attempts < maxAttempts) {
                    handler.postDelayed(this, 2000)
                }
            }
        }
        handler.postDelayed(runnable, 2000)
    }

    /**
     * 打开浏览器下载 Shizuku APK（官方下载页）。
     */
    fun downloadAndInstallShizuku(context: Context) {
        openUrlInBrowser(context, SHIZUKU_DOWNLOAD_URL)
    }

    /**
     * 打开浏览器下载 STELLAR APK。
     *
     * STELLAR 无统一官方网站，主要通过各应用市场分发：
     * 1. 优先尝试打开应用市场详情页（market:// scheme）；
     * 2. 若设备无可用市场，则回退到浏览器搜索下载。
     */
    fun downloadAndInstallStellar(context: Context) {
        // 优先尝试应用市场详情页
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$STELLAR_PACKAGE")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (marketIntent.resolveActivity(context.packageManager) != null) {
            runCatching { context.startActivity(marketIntent) }
            return
        }
        // 回退到浏览器搜索下载
        openUrlInBrowser(context, STELLAR_DOWNLOAD_URL)
    }

    /**
     * 使用浏览器打开指定 URL。
     */
    private fun openUrlInBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * 显示 Shizuku/STELLAR 安装引导对话框。
     *
     * - 若已检测到 Shizuku 兼容应用安装，提供"打开已安装应用"入口；
     * - 否则提供"下载 Shizuku"与"下载 STELLAR"两个入口。
     *
     * 注意：对话框需要 Activity 上下文；若传入非 Activity 上下文，将回退为直接打开下载页。
     */
    fun openShizukuInstallGuide(context: Context) {
        if (context !is Activity) {
            // 非 Activity 上下文无法直接弹窗，回退为打开下载页
            downloadAndInstallShizuku(context)
            return
        }

        val installed = getInstalledShizukuApp(context)
        val message = buildString {
            append("MobileClaw 可通过 Shizuku / STELLAR 获取高级调试权限（安装应用、执行 Shell 等）。\n\n")
            if (installed != null) {
                append("检测到已安装：$installed\n")
                append("请打开该应用，通过 ADB 无线调试或 Root 启动服务后，回到本应用授权。")
            } else {
                append("推荐二选一：\n")
                append("• Shizuku（官方，需 ADB 无线调试启动）\n")
                append("• STELLAR（Shizuku 兼容分支，功能更全）\n\n")
                append("安装并启动服务后，回到本应用授权即可。")
            }
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("安装调试权限应用")
            .setMessage(message)
            .setCancelable(true)

        if (installed != null) {
            builder.setPositiveButton("打开已安装应用") { _, _ -> openShizukuApp(context) }
            builder.setNegativeButton("下载其他版本") { _, _ -> downloadAndInstallShizuku(context) }
        } else {
            builder.setPositiveButton("下载 Shizuku") { _, _ -> downloadAndInstallShizuku(context) }
            builder.setNeutralButton("下载 STELLAR") { _, _ -> downloadAndInstallStellar(context) }
            builder.setNegativeButton("取消", null)
        }
        builder.show()
    }

    /**
     * 根据 [PermissionType] 跳转到对应的权限设置页。
     */
    fun requestPermission(context: Context, type: PermissionType) {
        when (type) {
            PermissionType.ACCESSIBILITY -> requestAccessibilityPermission(context)
            PermissionType.OVERLAY -> requestOverlayPermission(context)
            PermissionType.STORAGE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestStoragePermission(context)
                } else {
                    if (context is Activity) {
                        requestBasicPermissions(context)
                    }
                }
            }
            PermissionType.NOTIFICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.BATTERY_OPTIMIZATION -> requestBatteryOptimization(context)
            PermissionType.USAGE_STATS -> requestUsageStatsPermission(context)
            PermissionType.SHIZUKU -> requestShizukuPermission(context)
            PermissionType.AUTO_START -> requestAutoStartPermission(context)
            PermissionType.PHONE_STATE -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.CAMERA -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.LOCATION -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.MICROPHONE -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.CONTACTS -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.CALENDAR -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.SMS -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
            PermissionType.CALL_LOG -> {
                if (context is Activity) {
                    requestBasicPermissions(context)
                }
            }
        }
    }

    /**
     * 一键申请所有可批量申请的权限，并引导用户到特殊权限页面。
     *
     * 包括：
     * 1. 基本运行时权限（存储、通知、相机等）
     * 2. 存储管理权限（Android 11+）
     * 3. 悬浮窗权限
     * 4. 电池优化白名单
     * 5. 无障碍服务（必需，跳转系统设置页）
     * 6. Shizuku/STELLAR 调试权限
     * 7. 使用情况访问权限
     * 8. 自启动权限
     *
     * 所有权限按顺序以引导对话框展示，用户点击"去开启"后跳转对应设置页。
     */
    fun requestAllPermissions(activity: Activity) {
        // 1. 先批量申请运行时权限
        requestBasicPermissions(activity)

        // 收集所有需要引导的权限步骤
        val steps = mutableListOf<PermissionStep>()

        // 2. 无障碍服务（必需，核心权限）
        if (!isAccessibilityEnabled(activity)) {
            steps.add(PermissionStep(
                title = "无障碍服务",
                message = "【必需权限】灵爪的核心操控能力依赖无障碍服务。\n\n" +
                        "开启步骤：\n" +
                        "1. 在设置页面找到「已安装的服务」\n" +
                        "2. 找到「灵爪」并开启开关\n" +
                        "3. 在弹出的确认对话框中点击「确定」\n\n" +
                        "🔑 这是让灵爪能帮你点击、滑动、输入的关键！"
            ) { requestAccessibilityPermission(activity) })
        }

        // 3. 存储管理权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasStoragePermission(activity)) {
            steps.add(PermissionStep(
                title = "存储管理权限",
                message = "用于截图保存、文件读写等操作，建议开启。\n\n" +
                        "开启后灵爪可以保存截图、读取配置文件等。"
            ) { requestStoragePermission(activity) })
        }

        // 4. 悬浮窗权限
        if (!hasOverlayPermission(activity)) {
            steps.add(PermissionStep(
                title = "悬浮窗权限",
                message = "允许从后台启动其他应用，是「打开应用」功能的关键权限，强烈建议开启。\n\n" +
                        "开启后灵爪可以自动帮你打开微信、抖音等应用。"
            ) { requestOverlayPermission(activity) })
        }

        // 5. 电池优化白名单
        if (!isIgnoringBatteryOptimizations(activity)) {
            steps.add(PermissionStep(
                title = "电池优化白名单",
                message = "防止后台被杀，保持灵爪持续运行，建议开启。\n\n" +
                        "开启后灵爪在后台也不会被系统清理。"
            ) { requestBatteryOptimization(activity) })
        }

        // 6. Shizuku/STELLAR 调试权限
        if (!isShizukuAvailable(activity)) {
            steps.add(PermissionStep(
                title = "调试权限 (Shizuku/STELLAR)",
                message = "【可选但推荐】提供高级系统操作能力，如执行 Shell 命令、安装应用等。\n\n" +
                        "当前状态：${getShizukuStatusDescription(activity)}\n\n" +
                        "推荐安装 STELLAR（Shizuku 兼容增强版），无需电脑即可启用。"
            ) { requestShizukuPermission(activity) })
        }

        // 7. 使用情况访问权限
        if (!hasUsageStatsPermission(activity)) {
            steps.add(PermissionStep(
                title = "使用情况访问",
                message = "允许灵爪查看正在运行的应用，用于智能识别当前操作环境。\n\n" +
                        "可选权限，不影响核心功能。"
            ) { requestUsageStatsPermission(activity) })
        }

        // 8. 自启动权限（仅特定厂商需要）
        if (!isAutoStartEnabled(activity)) {
            steps.add(PermissionStep(
                title = "自启动权限",
                message = "【厂商特定】防止系统清理后台，确保灵爪在开机或后台能自动运行。\n\n" +
                        "如果发现灵爪经常在后台被关闭，建议开启此权限。"
            ) { requestAutoStartPermission(activity) })
        }

        if (steps.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("权限已就绪")
                .setMessage("✅ 所有权限均已授权，可以开始使用灵爪了！")
                .setPositiveButton("好的", null)
                .show()
            return
        }

        // 显示权限引导步骤
        showPermissionStep(activity, steps, 0)
    }

    // ==================== 无障碍激活后自动申请辅助权限 ====================

    /**
     * 待引导开启的权限步骤。
     *
     * @property title 权限名称
     * @property message 引导说明
     * @property action 点击"去开启"时执行的动作（通常为跳转对应设置页）
     */
    private data class PermissionStep(
        val title: String,
        val message: String,
        val action: () -> Unit
    )

    /**
     * 无障碍服务激活后，自动引导用户依次申请其他辅助权限。
     *
     * 当无障碍服务刚激活时调用本方法，将依次通过 AlertDialog 引导用户开启：
     * 存储权限 -> 悬浮窗权限 -> 电池优化白名单（仅引导尚未授权的项）。
     *
     * 实现说明：每个步骤的对话框在用户点击"去开启"或"稍后"并关闭后，才会展示下一个步骤，
     * 因此用户从设置页返回后即可看到下一项待开启权限的引导。
     *
     * @param Activity 上下文，用于弹窗与跳转设置页
     */
    fun autoRequestPermissionsAfterAccessibility(activity: Activity) {
        // 收集尚未授权的辅助权限步骤
        val steps = mutableListOf<PermissionStep>()
        if (!hasStoragePermission(activity)) {
            steps.add(
                PermissionStep(
                    title = "存储权限",
                    message = "用于截图保存、文件读写，建议开启。"
                ) { requestStoragePermission(activity) }
            )
        }
        if (!hasOverlayPermission(activity)) {
            steps.add(
                PermissionStep(
                    title = "悬浮窗权限",
                    message = "允许从后台启动其他应用，是「打开应用」功能的关键权限，强烈建议开启。"
                ) { requestOverlayPermission(activity) }
            )
        }
        if (!isIgnoringBatteryOptimizations(activity)) {
            steps.add(
                PermissionStep(
                    title = "电池优化白名单",
                    message = "防止后台被杀，保持持续运行，建议开启。"
                ) { requestBatteryOptimization(activity) }
            )
        }
        if (steps.isEmpty()) return
        showPermissionStep(activity, steps, 0)
    }

    /**
     * 递归展示权限引导步骤。
     *
     * @param index 当前步骤下标，每关闭一个对话框即推进到下一项
     */
    private fun showPermissionStep(activity: Activity, steps: List<PermissionStep>, index: Int) {
        if (index >= steps.size) return
        // Activity 已销毁则终止引导，避免 BadTokenException
        if (activity.isFinishing || activity.isDestroyed) return

        val step = steps[index]
        AlertDialog.Builder(activity)
            .setTitle("开启${step.title}")
            .setMessage(step.message)
            .setCancelable(false)
            .setPositiveButton("去开启") { dialog, _ ->
                step.action.invoke()
                dialog.dismiss()
            }
            .setNegativeButton("稍后", null)
            .setOnDismissListener {
                // 当前步骤已处理（开启或跳过），展示下一个待开启权限
                showPermissionStep(activity, steps, index + 1)
            }
            .show()
    }

    /**
     * 统计已授权的权限数量。
     */
    fun countGrantedPermissions(context: Context): Pair<Int, Int> {
        val all = getAllPermissions(context)
        val granted = all.count { it.granted }
        return Pair(granted, all.size)
    }

    // ==================== 一键快捷配置 ====================

    /**
     * 一键快捷配置入口。
     *
     * 极简权限配置流程，只处理最核心的权限：
     * 1. 无障碍服务（必需）—— 优先通过 Shizuku 一键启用，失败则跳转设置页
     * 2. Shizuku/STELLAR（可选）—— 用于一键启用无障碍 + 高级功能
     *
     * 相比 [requestAllPermissions] 的逐步骤引导，本方法追求最小化用户操作：
     * - 有 Shizuku → 一键开启无障碍，全程无需用户离开应用
     * - 无 Shizuku → 先引导安装 Shizuku，然后一键开启无障碍
     * - 无障碍已开启 → 提示已完成，可直接使用
     */
    fun quickSetup(activity: Activity) {
        if (activity.isFinishing) return

        // 无障碍已开启？直接完成
        if (isAccessibilityEnabled(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("🎉 配置已完成")
                .setMessage("无障碍服务已开启，你可以直接使用灵爪了！\n\n" +
                        "输入指令即可控制手机，例如：\n" +
                        "• 「打开微信」\n" +
                        "• 「截个屏」\n" +
                        "• 「帮我查天气」")
                .setPositiveButton("开始使用", null)
                .show()
            return
        }

        // 检查 Shizuku 是否可用
        val shizukuAvailable = com.mobileclaw.app.shizuku.ShizukuManager.isShizukuAvailable()

        if (shizukuAvailable) {
            // Shizuku 已就绪 → 一键开启无障碍
            AlertDialog.Builder(activity)
                .setTitle("⚡ 一键配置")
                .setMessage("检测到 Shizuku/STELLAR 调试权限已就绪，可以一键开启无障碍服务，无需进入系统设置。\n\n" +
                        "点击「一键开启」即可完成配置。")
                .setPositiveButton("🚀 一键开启") { _, _ ->
                    enableAccessibilityViaShizuku(activity)
                    // 延迟检测是否已连接
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (isAccessibilityEnabled(activity)) {
                            AlertDialog.Builder(activity)
                                .setTitle("✅ 配置成功")
                                .setMessage("无障碍服务已开启，你可以直接使用灵爪了！\n\n" +
                                        "输入指令即可控制手机，例如：\n" +
                                        "• 「打开微信」\n" +
                                        "• 「截个屏」\n" +
                                        "• 「帮我查天气」")
                                .setPositiveButton("开始使用", null)
                                .show()
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("⚠️ 一键开启失败")
                                .setMessage("一键开启未能生效，这可能是因为系统限制。\n\n" +
                                        "请点击「手动设置」前往系统设置页面手动开启无障碍服务。")
                                .setPositiveButton("手动设置") { _, _ ->
                                    requestAccessibilityPermission(activity)
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }, 1500)
                }
                .setNegativeButton("稍后再说", null)
                .show()
        } else {
            // Shizuku 不可用 → 引导安装或手动设置
            val hasShizukuApp = getInstalledShizukuApp(activity) != null

            val message = buildString {
                appendLine("灵爪需要无障碍服务来操控屏幕。")
                appendLine()
                if (!hasShizukuApp) {
                    appendLine("💡 推荐安装 STELLAR（Shizuku 兼容版），安装后即可一键开启无障碍服务，无需手动操作。")
                    appendLine()
                } else {
                    appendLine("💡 检测到已安装 Shizuku/STELLAR，但服务尚未启动。请打开后启动服务，即可一键开启无障碍。")
                    appendLine()
                }
                append("或者直接前往系统设置手动开启无障碍服务。")
            }

            AlertDialog.Builder(activity)
                .setTitle("配置无障碍服务")
                .setMessage(message)
                .setPositiveButton("手动设置") { _, _ ->
                    requestAccessibilityPermission(activity)
                }
                .apply {
                    if (!hasShizukuApp) {
                        setNeutralButton("安装 STELLAR") { _, _ ->
                            openShizukuInstallGuide(activity)
                        }
                    } else {
                        setNeutralButton("打开 Shizuku") { _, _ ->
                            openShizukuApp(activity)
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}
