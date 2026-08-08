package com.mobileclaw.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService 实现。
 *
 * 该文件包含三部分内容：
 * 1. [IShizukuService] —— 自定义的跨进程接口（手动实现 AIDL 风格的 Binder Stub），
 *    客户端（应用进程）通过它与运行在 Shizuku 服务进程中的代码通信。
 * 2. [ShizukuService] —— UserService 的具体实现，运行在 Shizuku 服务进程中，
 *    以 shell（ADB）或 root 身份执行系统级操作。在此进程中没有非 SDK 接口限制。
 * 3. [ShizukuServiceBinder] —— 绑定管理器，封装 [Shizuku.bindUserService] 的生命周期，
 *    供 [com.mobileclaw.app.system.SystemInfoCollector] 与
 *    [com.mobileclaw.app.debug.ShellExecutor] 复用。
 *
 * 注意：为保证 Shizuku 能按类名加载该服务，[ShizukuService] 类不应被混淆
 * （建议在 proguard-rules.pro 中保留该类及其接口）。
 */

// ==================================================================================
// 第一部分：跨进程接口 IShizukuService 及其 Binder Stub
// ==================================================================================

/**
 * 运行在 Shizuku 服务进程中的系统级操作接口。
 *
 * 为避免与 Shizuku 内部的 `moe.shizuku.server.IShizukuService` 混淆，这里命名为
 * `IShizukuService`（位于 `com.mobileclaw.app.shizuku` 包下）。由于本接口使用自定义
 * descriptor，二者不会冲突。
 *
 * 所有返回复杂数据的方法均使用 [String] 或 [List] 形式，以简化跨进程序列化，
 * 具体结构化解析由调用方（如 [com.mobileclaw.app.system.SystemInfoCollector]）完成。
 */
interface IShizukuService : IInterface {

    companion object {
        /** Binder 接口描述符。 */
        const val DESCRIPTOR = "com.mobileclaw.app.shizuku.IShizukuService"
    }

    /**
     * 获取系统属性（等价于 `SystemProperties.get`）。
     *
     * @param name 属性名，例如 `ro.build.version.release`
     * @return 属性值；不存在时返回 null
     */
    fun getSystemProperty(name: String): String?

    /**
     * 设置系统属性（等价于 `SystemProperties.set`，需要 root 或 shell 权限）。
     *
     * @param name  属性名
     * @param value 属性值
     */
    fun setSystemProperty(name: String, value: String)

    /**
     * 在 Shizuku 进程中执行 shell 命令（以 shell/root 身份运行）。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间（毫秒），超时后会强制结束进程
     * @return 包含三项的列表：[exitCode, stdout, stderr]
     */
    fun executeShell(command: String, timeoutMs: Long): List<String>

    /**
     * 读取指定路径文件内容（以 shell/root 权限）。
     *
     * @param path 文件绝对路径
     * @return 文件文本内容；读取失败时返回空字符串
     */
    fun readFile(path: String): String

    /**
     * 获取运行中的进程列表。
     *
     * @return `ps -A -o PID,RSS,NAME` 的输出行列表（含表头）
     */
    fun getRunningProcessNames(): List<String>

    /**
     * 获取已安装应用包名列表。
     *
     * @param includeSystem 是否包含系统应用
     * @return `pm list packages` 的输出行列表
     */
    fun getInstalledPackages(includeSystem: Boolean): List<String>

    /**
     * Binder Stub 的抽象实现，等价于 AIDL 自动生成的 Stub。
     *
     * 服务端 [ShizukuService] 继承该类并实现各方法；
     * 客户端通过 [asInterface] 获取 [Proxy] 代理对象进行远程调用。
     */
    abstract class Stub : Binder(), IShizukuService {

        init {
            // 注册接口描述符，使 queryLocalInterface 可正常工作
            this.attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        /**
         * 将 IBinder 转换为 [IShizukuService]。
         * 若 binder 运行于当前进程，返回本地实现；否则返回远程代理。
         */
        companion object {
            // 自定义方法事务码（IBinder.FIRST_CALL_TRANSACTION = 1）
            // 按 AIDL 惯例设为 public，以便嵌套 Proxy 类访问
            const val TRANSACTION_getSystemProperty = IBinder.FIRST_CALL_TRANSACTION
            const val TRANSACTION_setSystemProperty = IBinder.FIRST_CALL_TRANSACTION + 1
            const val TRANSACTION_executeShell = IBinder.FIRST_CALL_TRANSACTION + 2
            const val TRANSACTION_readFile = IBinder.FIRST_CALL_TRANSACTION + 3
            const val TRANSACTION_getRunningProcessNames = IBinder.FIRST_CALL_TRANSACTION + 4
            const val TRANSACTION_getInstalledPackages = IBinder.FIRST_CALL_TRANSACTION + 5

            /** Shizuku 销毁 UserService 时使用的事务码。 */
            const val TRANSACTION_destroy = 16777115

            /**
             * 将 [IBinder] 转换为 [IShizukuService] 接口对象。
             *
             * @param binder 远程 binder，可为 null
             * @return 接口代理；binder 为 null 时返回 null
             */
            @JvmStatic
            fun asInterface(binder: IBinder?): IShizukuService? {
                if (binder == null) return null
                val iin = binder.queryLocalInterface(DESCRIPTOR)
                if (iin is IShizukuService) {
                    // 同进程，直接返回本地实现
                    return iin
                }
                // 跨进程，返回代理
                return Proxy(binder)
            }
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }

                TRANSACTION_getSystemProperty -> {
                    data.enforceInterface(DESCRIPTOR)
                    val name = data.readString() ?: ""
                    val result = this.getSystemProperty(name)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }

                TRANSACTION_setSystemProperty -> {
                    data.enforceInterface(DESCRIPTOR)
                    val name = data.readString() ?: ""
                    val value = data.readString() ?: ""
                    this.setSystemProperty(name, value)
                    reply?.writeNoException()
                    return true
                }

                TRANSACTION_executeShell -> {
                    data.enforceInterface(DESCRIPTOR)
                    val command = data.readString() ?: ""
                    val timeoutMs = data.readLong()
                    val result = this.executeShell(command, timeoutMs)
                    reply?.writeNoException()
                    reply?.writeStringList(result)
                    return true
                }

                TRANSACTION_readFile -> {
                    data.enforceInterface(DESCRIPTOR)
                    val path = data.readString() ?: ""
                    val result = this.readFile(path)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }

                TRANSACTION_getRunningProcessNames -> {
                    data.enforceInterface(DESCRIPTOR)
                    val result = this.getRunningProcessNames()
                    reply?.writeNoException()
                    reply?.writeStringList(result)
                    return true
                }

                TRANSACTION_getInstalledPackages -> {
                    data.enforceInterface(DESCRIPTOR)
                    // 布尔值使用 int 传递，兼容低版本 API（Parcel.writeBoolean 自 API 33 起可用）
                    val includeSystem = data.readInt() != 0
                    val result = this.getInstalledPackages(includeSystem)
                    reply?.writeNoException()
                    reply?.writeStringList(result)
                    return true
                }

                TRANSACTION_destroy -> {
                    // Shizuku 服务端要求销毁 UserService 时会发送此事务
                    data.enforceInterface(DESCRIPTOR)
                    this.onDestroy()
                    reply?.writeNoException()
                    // 在独立线程中退出，确保回复能正常发送
                    Thread {
                        try {
                            Thread.sleep(100)
                        } catch (ignored: InterruptedException) {
                        }
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }.start()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        /**
         * 服务端在销毁前执行的清理逻辑，子类可覆盖。
         */
        open fun onDestroy() {
            // 默认无操作
        }

        /**
         * 远程代理实现，等价于 AIDL 自动生成的 Proxy。
         */
        private class Proxy(private val mRemote: IBinder) : IShizukuService {

            override fun asBinder(): IBinder = mRemote

            override fun getSystemProperty(name: String): String? {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(name)
                    mRemote.transact(TRANSACTION_getSystemProperty, data, reply, 0)
                    reply.readException()
                    reply.readString()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun setSystemProperty(name: String, value: String) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(name)
                    data.writeString(value)
                    mRemote.transact(TRANSACTION_setSystemProperty, data, reply, 0)
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun executeShell(command: String, timeoutMs: Long): List<String> {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(command)
                    data.writeLong(timeoutMs)
                    mRemote.transact(TRANSACTION_executeShell, data, reply, 0)
                    reply.readException()
                    reply.createStringArrayList() ?: emptyList()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun readFile(path: String): String {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(path)
                    mRemote.transact(TRANSACTION_readFile, data, reply, 0)
                    reply.readException()
                    reply.readString() ?: ""
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun getRunningProcessNames(): List<String> {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    mRemote.transact(TRANSACTION_getRunningProcessNames, data, reply, 0)
                    reply.readException()
                    reply.createStringArrayList() ?: emptyList()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun getInstalledPackages(includeSystem: Boolean): List<String> {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(if (includeSystem) 1 else 0)
                    mRemote.transact(TRANSACTION_getInstalledPackages, data, reply, 0)
                    reply.readException()
                    reply.createStringArrayList() ?: emptyList()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }
}

// ==================================================================================
// 第二部分：ShizukuService —— UserService 具体实现（运行在 Shizuku 服务进程）
// ==================================================================================

/**
 * Shizuku UserService 的具体实现。
 *
 * 该类由 Shizuku 服务端按类名加载并实例化，运行于独立的特权进程中，
 * 身份为 shell（UID 2000，通过 ADB 启动）或 root（UID 0，通过 Magisk/Sui 启动）。
 * 在此进程中可自由调用隐藏 API、读写系统文件、执行特权命令。
 *
 * @constructor Shizuku 支持无参构造或带 [Context] 的构造，此处同时提供两者。
 */
class ShizukuService() : IShizukuService.Stub() {

    companion object {
        private const val TAG = "ShizukuService"
    }

    /** 无参构造，Shizuku 默认使用该构造实例化服务。 */
    init {
        Log.i(TAG, "ShizukuService created (no-arg)")
    }

    /** 带 Context 的构造（可选）。注意：UserService 进程中许多 Context API 不可用。 */
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this() {
        Log.i(TAG, "ShizukuService created (with context)")
    }

    /**
     * 通过反射调用隐藏 API `android.os.SystemProperties.get`。
     */
    override fun getSystemProperty(name: String): String? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val getMethod = cls.getMethod("get", String::class.java)
            getMethod.invoke(null, name) as? String
        } catch (e: Exception) {
            Log.e(TAG, "getSystemProperty($name) failed", e)
            null
        }
    }

    /**
     * 通过反射调用隐藏 API `android.os.SystemProperties.set`。
     */
    override fun setSystemProperty(name: String, value: String) {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val setMethod = cls.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, name, value)
        } catch (e: Exception) {
            Log.e(TAG, "setSystemProperty($name, $value) failed", e)
        }
    }

    /**
     * 执行 shell 命令并捕获 stdout、stderr 与退出码。
     *
     * 使用独立线程读取输出流，避免管道缓冲区写满导致死锁。
     * 超时后会强制销毁进程。
     */
    override fun executeShell(command: String, timeoutMs: Long): List<String> {
        var process: Process? = null
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process = proc

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            // 在独立线程中读取输出流，防止管道阻塞造成 waitFor 死锁
            val stdoutThread = Thread {
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { stdoutBuilder.append(it).append('\n') }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read stdout failed", e)
                }
            }
            val stderrThread = Thread {
                try {
                    proc.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { stderrBuilder.append(it).append('\n') }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read stderr failed", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                // 超时，强制结束进程
                proc.destroyForcibly()
                stdoutThread.join(200)
                stderrThread.join(200)
                stderrBuilder.append("[timeout after ").append(timeoutMs).append("ms]")
                return listOf("-1", stdoutBuilder.toString(), stderrBuilder.toString())
            }

            // 等待输出流读取完成
            stdoutThread.join(1000)
            stderrThread.join(1000)

            val exitCode = try {
                proc.exitValue()
            } catch (e: Exception) {
                -1
            }
            return listOf(exitCode.toString(), stdoutBuilder.toString(), stderrBuilder.toString())
        } catch (e: Exception) {
            Log.e(TAG, "executeShell failed: $command", e)
            return listOf("-1", "", e.message ?: "execution failed")
        } finally {
            process?.destroy()
        }
    }

    /**
     * 读取文件内容。优先直接读取，失败时回退到 `cat` 命令。
     */
    override fun readFile(path: String): String {
        // 直接读取（适用于 /proc、/sys 等可读文件）
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return file.readText()
            }
        } catch (e: Exception) {
            Log.d(TAG, "direct read failed for $path, fallback to cat", e)
        }
        // 回退到 cat 命令（适用于需要 shell 权限的文件）
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("cat", path))
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (finished) {
                process.inputStream.bufferedReader().readText()
            } else {
                process.destroyForcibly()
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "readFile($path) failed", e)
            ""
        }
    }

    /**
     * 通过 `ps` 命令获取运行中的进程列表。
     */
    override fun getRunningProcessNames(): List<String> {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A -o PID,RSS,NAME"))
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (finished) {
                process.inputStream.bufferedReader().readLines()
            } else {
                process.destroyForcibly()
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRunningProcessNames failed", e)
            emptyList()
        }
    }

    /**
     * 通过 `pm list packages` 获取已安装应用列表。
     *
     * @param includeSystem true 获取全部应用，false 仅获取第三方应用
     */
    override fun getInstalledPackages(includeSystem: Boolean): List<String> {
        return try {
            val cmd = if (includeSystem) "pm list packages" else "pm list packages -3"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (finished) {
                process.inputStream.bufferedReader().readLines()
            } else {
                process.destroyForcibly()
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledPackages failed", e)
            emptyList()
        }
    }

    /** 销毁前的清理逻辑。 */
    override fun onDestroy() {
        Log.i(TAG, "ShizukuService onDestroy")
    }
}

// ==================================================================================
// 第三部分：ShizukuServiceBinder —— UserService 绑定管理器
// ==================================================================================

/**
 * UserService 绑定管理器。
 *
 * 封装 [Shizuku.bindUserService] / [Shizuku.unbindUserService] 的生命周期，
 * 在应用进程内维护一个到 [ShizukuService] 的连接。
 * [com.mobileclaw.app.system.SystemInfoCollector] 与
 * [com.mobileclaw.app.debug.ShellExecutor] 共用此连接。
 *
 * 使用相同 tag 的多次 bindUserService 会连接到同一个运行中的服务实例。
 */
object ShizukuServiceBinder {

    private const val TAG = "ShizukuServiceBinder"
    private const val SERVICE_TAG = "mobileclaw_service"
    private const val SERVICE_VERSION = 1
    private const val PROCESS_SUFFIX = "shizuku_service"

    /** 绑定超时（毫秒）。 */
    private const val BIND_TIMEOUT_MS = 5000L

    @Volatile
    private var service: IShizukuService? = null

    @Volatile
    private var bound = false

    private var args: Shizuku.UserServiceArgs? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "UserService connected")
            service = binder?.let { IShizukuService.Stub.asInterface(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService disconnected")
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "UserService binding died")
            service = null
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.w(TAG, "UserService null binding")
        }
    }

    /**
     * 绑定 UserService。
     *
     * @param context 用于构造 [ComponentName] 的上下文
     * @return true 表示绑定请求已发起（不代表服务已就绪，请配合 [requireService] 使用）
     */
    fun bind(context: Context): Boolean {
        if (!ShizukuManager.isShizukuAvailable()) {
            Log.w(TAG, "Shizuku not available, cannot bind UserService")
            return false
        }
        if (bound) {
            Log.d(TAG, "UserService already bound")
            return true
        }
        return try {
            val componentName = ComponentName(context.packageName, ShizukuService::class.java.name)
            val serviceArgs = Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .tag(SERVICE_TAG)
                .version(SERVICE_VERSION)
                .debuggable(false)
                .processNameSuffix(PROCESS_SUFFIX)
            args = serviceArgs
            Shizuku.bindUserService(serviceArgs, connection)
            bound = true
            Log.d(TAG, "bindUserService requested")
            true
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed", e)
            false
        }
    }

    /**
     * 解绑 UserService（不强制销毁远程服务，daemon=false 时应用进程退出后服务自动停止）。
     */
    fun unbind() {
        if (!bound) return
        try {
            args?.let { Shizuku.unbindUserService(it, connection, false) }
        } catch (e: Exception) {
            Log.e(TAG, "unbindUserService failed", e)
        }
        bound = false
        service = null
    }

    /**
     * 获取当前已连接的服务实例（可能为 null）。
     */
    fun getService(): IShizukuService? = service

    /**
     * 判断是否已绑定。
     */
    fun isBound(): Boolean = bound

    /**
     * 挂起等待服务连接就绪。
     *
     * @param timeoutMs 等待超时时间
     * @return 已连接的服务实例
     * @throws IllegalStateException 超时或服务不可用时抛出
     */
    suspend fun requireService(timeoutMs: Long = BIND_TIMEOUT_MS): IShizukuService {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (service == null && System.currentTimeMillis() < deadline) {
            if (!ShizukuManager.isShizukuAvailable()) {
                throw IllegalStateException("Shizuku not available")
            }
            kotlinx.coroutines.delay(100)
        }
        return service ?: throw IllegalStateException("Shizuku UserService not connected")
    }

    /**
     * 判断 Shizuku 服务端是否以 root 身份运行（UID 0）。
     *
     * 当返回 true 时，[executeShell] 等操作将以 root 权限执行。
     */
    fun isRootMode(): Boolean {
        return try {
            ShizukuManager.isShizukuAvailable() && Shizuku.getUid() == 0
        } catch (e: Exception) {
            Log.e(TAG, "isRootMode failed", e)
            false
        }
    }
}
