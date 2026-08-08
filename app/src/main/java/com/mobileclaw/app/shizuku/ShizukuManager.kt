package com.mobileclaw.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Shizuku 连接状态。
 */
sealed class ShizukuState {
    /** Shizuku 未安装或未运行。 */
    object NotInstalled : ShizukuState()

    /** Shizuku 已运行但尚未授权。 */
    object Unauthorized : ShizukuState()

    /** Shizuku 已运行且已授权，可以执行系统级操作。 */
    object Authorized : ShizukuState()
}

/**
 * Shizuku 权限管理器。
 *
 * 单例对象，负责管理 Shizuku 的连接状态、权限申请与状态监听。
 * 通过该管理器可以统一判断 Shizuku 是否可用、申请权限并观察权限变化。
 *
 * 使用 [dev.rikka.shizuku:api] 提供的 [rikka.shizuku.Shizuku] 作为底层入口。
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    /** 权限请求码。 */
    private const val PERMISSION_REQUEST_CODE = 1001

    /** 挂起等待授权的回调。 */
    private val pendingPermissionCallbacks = mutableListOf<(Boolean) -> Unit>()

    /** Shizuku binder 接收监听器。 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        refreshState()
    }

    /** Shizuku binder 断开监听器。 */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        updateState(ShizukuState.NotInstalled)
    }

    /** 权限请求结果监听器。 */
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission request result: granted=$granted")
            refreshState()
            // 通知所有挂起等待的协程
            synchronized(pendingPermissionCallbacks) {
                pendingPermissionCallbacks.forEach { it(granted) }
                pendingPermissionCallbacks.clear()
            }
        }

    /** 当前 Shizuku 状态的 StateFlow，UI 层可观察。 */
    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotInstalled)

    /** 暴露给外部的只读状态流。 */
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    /** 标记监听器是否已注册。 */
    private var listenersRegistered = false

    /**
     * 初始化监听器。应在 Application 或 Activity 创建时调用一次。
     */
    fun init() {
        if (listenersRegistered) return
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            listenersRegistered = true
            Log.d(TAG, "Shizuku listeners registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Shizuku listeners", e)
        }
        refreshState()
    }

    /**
     * 释放监听器。在应用退出或不再使用 Shizuku 时调用。
     */
    fun destroy() {
        if (!listenersRegistered) return
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove Shizuku listeners", e)
        }
        listenersRegistered = false
    }

    /**
     * 强制重新绑定 Shizuku/STELLAR 服务。
     *
     * 核心问题：Shizuku SDK 的 ShizukuProvider 在应用启动时调用 bindService()，
     * 如果此时 STELLAR 服务尚未运行，绑定失败后不会自动重试。
     * STELLAR 虽然有 Shizuku 兼容层会主动推送 Binder，但如果我们的应用
     * 在 STELLAR 启动时未运行，就会错过 Binder 推送。
     *
     * 解决方案：
     * 1. 移除并重新注册监听器（sticky listener 会立即触发如果 Binder 已收到）
     * 2. 尝试通过 ContentResolver 直接调用 Provider
     * 3. 安排多次延迟重检（覆盖用户从 STELLAR 返回的时机）
     *
     * @param context 上下文，用于访问 ContentResolver
     */
    fun forceRebind(context: Context) {
        Log.d(TAG, "forceRebind: attempting re-bind")

        // 1. 移除并重新注册监听器
        if (listenersRegistered) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
                Shizuku.removeRequestPermissionResultListener(permissionResultListener)
                listenersRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "forceRebind: failed to remove listeners", e)
            }
        }

        // 重新注册（sticky listener 会在 Binder 已存在时立即触发）
        init()

        // 2. 尝试通过 ContentResolver 调用 Provider
        try {
            val uri = Uri.parse("content://${context.packageName}.shizuku")
            context.contentResolver.call(uri, "pingBinder", null, null)
        } catch (e: Exception) {
            Log.d(TAG, "forceRebind: provider call failed (expected if service not running)", e)
        }

        // 3. 安排多次延迟重检，覆盖用户从 STELLAR 返回的时机
        val handler = Handler(Looper.getMainLooper())
        listOf(300L, 1000L, 2000L, 4000L, 6000L, 10000L).forEach { delay ->
            handler.postDelayed({ refreshState() }, delay)
        }
    }

    /**
     * 公开的刷新状态方法，供外部调用触发状态更新。
     */
    fun refreshStatePublic() {
        refreshState()
    }

    /**
     * 判断 Shizuku 是否已安装并正在运行（binder 可用）。
     *
     * @return true 表示 Shizuku 进程正在运行
     */
    fun isShizukuInstalled(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "pingBinder failed", e)
            false
        }
    }

    /**
     * 判断 Shizuku 是否可用（已安装运行且已授权）。
     *
     * @return true 表示可执行系统级操作
     */
    fun isShizukuAvailable(): Boolean {
        return isShizukuInstalled() && checkPermission()
    }

    /**
     * 检查当前是否已获得 Shizuku 权限。
     *
     * @return true 表示已授权
     */
    fun checkPermission(): Boolean {
        return try {
            if (!isShizukuInstalled()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "checkPermission failed", e)
            false
        }
    }

    /**
     * 同步请求 Shizuku 权限授权。该方法仅触发系统授权弹窗，不等待结果。
     * 如需等待授权结果，请使用 [requestPermissionAsync]。
     */
    fun requestPermission() {
        try {
            if (!isShizukuInstalled()) {
                Log.w(TAG, "Cannot request permission: Shizuku not running")
                return
            }
            if (checkPermission()) {
                Log.d(TAG, "Permission already granted")
                return
            }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    /**
     * 挂起式请求 Shizuku 权限，等待用户授权结果。
     *
     * @return true 表示用户已授权
     */
    suspend fun requestPermissionAsync(): Boolean {
        // 若已授权直接返回
        if (checkPermission()) return true
        if (!isShizukuInstalled()) return false

        return suspendCancellableCoroutine { cont ->
            val callback: (Boolean) -> Unit = { granted ->
                if (cont.isActive) cont.resume(granted)
            }
            synchronized(pendingPermissionCallbacks) {
                pendingPermissionCallbacks.add(callback)
            }
            cont.invokeOnCancellation {
                synchronized(pendingPermissionCallbacks) {
                    pendingPermissionCallbacks.remove(callback)
                }
            }
            // 触发权限请求
            try {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                Log.e(TAG, "requestPermissionAsync failed", e)
                synchronized(pendingPermissionCallbacks) {
                    pendingPermissionCallbacks.remove(callback)
                }
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    /**
     * 观察权限/连接状态变化的冷 Flow。
     * 每当 Shizuku binder 连接、断开或权限变化时发射最新状态。
     */
    fun observeState(): Flow<ShizukuState> = callbackFlow {
        // 先发射当前状态
        trySend(_state.value)
        // 通过收集内部 StateFlow 订阅后续状态变化，
        // 当收集者被取消时，awaitClose 会自动取消收集。
        val sub = launch {
            _state.collect { st ->
                trySend(st)
            }
        }
        awaitClose { sub.cancel() }
    }

    /**
     * 刷新当前状态并更新 StateFlow。
     */
    private fun refreshState() {
        val newState = when {
            !isShizukuInstalled() -> ShizukuState.NotInstalled
            checkPermission() -> ShizukuState.Authorized
            else -> ShizukuState.Unauthorized
        }
        updateState(newState)
    }

    /**
     * 更新状态。
     */
    private fun updateState(newState: ShizukuState) {
        if (_state.value != newState) {
            Log.d(TAG, "State changed: ${_state.value} -> $newState")
            _state.value = newState
        }
    }
}
