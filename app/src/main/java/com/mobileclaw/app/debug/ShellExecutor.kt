package com.mobileclaw.app.debug

import android.content.Context
import android.util.Log
import com.mobileclaw.app.model.ShellResult
import com.mobileclaw.app.shizuku.IShizukuService
import com.mobileclaw.app.shizuku.ShizukuManager
import com.mobileclaw.app.shizuku.ShizukuServiceBinder
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shell 命令执行器。
 *
 * 采用分级执行策略：
 * 1. 优先使用 Shizuku UserService（shell/root 权限）——当 Shizuku 可用时
 * 2. 回退到本地 Runtime.exec()——当 Shizuku 不可用时，仍可执行非特权命令
 *
 * 特性：
 * - 支持同步（[executeSync]）与异步（[execute] 挂起函数）执行
 * - 返回结构化的 [ShellResult]（stdout、stderr、exitCode）
 * - 支持超时设置
 * - 支持以 root 权限执行（[asRoot] 参数，设备已 root 时通过 su 提权）
 * - Shizuku 不可用时自动降级为本地执行，保证基本功能可用
 *
 * @param context 应用上下文，用于绑定 UserService
 */
class ShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "ShellExecutor"
        /** 默认超时时间（毫秒）。 */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    /**
     * 获取（必要时绑定）UserService 实例。
     *
     * @return 已连接的 [IShizukuService]
     * @throws IllegalStateException 当 Shizuku 不可用或服务连接超时
     */
    private suspend fun getService(): IShizukuService {
        if (!ShizukuManager.isShizukuAvailable()) {
            throw IllegalStateException("Shizuku is not available")
        }
        if (!ShizukuServiceBinder.isBound()) {
            ShizukuServiceBinder.bind(context)
        }
        return ShizukuServiceBinder.requireService()
    }

    /**
     * 异步执行 shell 命令（挂起函数，应在协程中调用）。
     *
     * 执行策略：
     * 1. 若 Shizuku 可用，通过 UserService 以 shell/root 身份执行
     * 2. 若 Shizuku 不可用，回退到本地 Runtime.exec() 执行非特权命令
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间（毫秒），超时后强制结束进程
     * @param asRoot    是否以 root 权限执行。当 Shizuku 非 root 模式且设备已 root 时，
     *                  会通过 `su -c` 提权；Shizuku 已是 root 模式时忽略此参数
     * @return 包含 stdout、stderr、exitCode 的 [ShellResult]
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        asRoot: Boolean = false
    ): ShellResult {
        // 策略 1：Shizuku 可用时，使用 UserService 执行
        if (ShizukuManager.isShizukuAvailable()) {
            return try {
                val service = getService()
                val actualCommand = buildCommand(command, asRoot)
                val result = service.executeShell(actualCommand, timeoutMs)
                ShellResult(
                    exitCode = result.getOrElse(0) { "-1" }.toIntOrNull() ?: -1,
                    stdout = result.getOrElse(1) { "" },
                    stderr = result.getOrElse(2) { "" }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku execution failed, falling back to local: ${e.message}")
                // Shizuku 执行失败，回退到本地执行
                executeLocal(command, timeoutMs, asRoot)
            }
        }

        // 策略 2：Shizuku 不可用，使用本地执行
        return executeLocal(command, timeoutMs, asRoot)
    }

    /**
     * 本地执行 shell 命令（无 Shizuku 时的回退方案）。
     *
     * 使用 Runtime.exec() 执行命令，权限为应用进程权限。
     * 可执行非特权命令（如 ls /sdcard、cat /proc/cpuinfo 等），
     * 但无法执行需要 shell/root 权限的命令（如 pm install、am force-stop 等）。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间
     * @param asRoot    是否尝试以 root 执行（设备已 root 时通过 su -c）
     * @return 执行结果
     */
    private suspend fun executeLocal(
        command: String,
        timeoutMs: Long,
        asRoot: Boolean
    ): ShellResult {
        return try {
            val actualCommand = if (asRoot) {
                // 尝试通过 su 执行（设备已 root 时有效）
                val escaped = command.replace("'", "'\\''")
                arrayOf("su", "-c", "'$escaped'")
            } else {
                arrayOf("sh", "-c", command)
            }

            val process = Runtime.getRuntime().exec(actualCommand)
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            // 独立线程读取输出流，避免管道阻塞
            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { stdoutBuilder.append(it).append('\n') }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read stdout failed", e)
                }
            }
            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { stderrBuilder.append(it).append('\n') }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read stderr failed", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                stdoutThread.join(200)
                stderrThread.join(200)
                stderrBuilder.append("[timeout after ").append(timeoutMs).append("ms]")
                return ShellResult(
                    exitCode = -1,
                    stdout = stdoutBuilder.toString(),
                    stderr = stderrBuilder.toString()
                )
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)

            val exitCode = try { process.exitValue() } catch (e: Exception) { -1 }
            ShellResult(
                exitCode = exitCode,
                stdout = stdoutBuilder.toString(),
                stderr = stderrBuilder.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "local execute failed: $command", e)
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "local execution failed (可能需要 Shizuku/Root 权限)"
            )
        }
    }

    /**
     * 同步执行 shell 命令（阻塞当前线程直到返回）。
     *
     * 注意：请勿在主线程调用，否则会阻塞 UI。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间（毫秒）
     * @param asRoot    是否以 root 权限执行
     * @return 包含 stdout、stderr、exitCode 的 [ShellResult]
     */
    fun executeSync(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        asRoot: Boolean = false
    ): ShellResult {
        return runBlocking { execute(command, timeoutMs, asRoot) }
    }

    /**
     * 判断当前是否以 root 权限执行命令。
     *
     * 当 Shizuku 通过 root（Magisk/Sui）启动时返回 true，此时所有命令均以 root 身份运行。
     *
     * @return true 表示 Shizuku 服务端以 root 身份运行
     */
    fun isRootMode(): Boolean = ShizukuServiceBinder.isRootMode()

    /**
     * 判断 Shizuku 是否可用。
     */
    fun isShizukuAvailable(): Boolean = ShizukuManager.isShizukuAvailable()

    /**
     * 显式绑定 UserService。通常无需手动调用，[execute] 会在需要时自动绑定。
     *
     * @return true 表示绑定请求已发起
     */
    fun bind(): Boolean {
        return ShizukuServiceBinder.bind(context)
    }

    /**
     * 解绑 UserService，释放连接资源。
     */
    fun unbind() {
        ShizukuServiceBinder.unbind()
    }

    /**
     * 根据是否要求 root 构建最终命令。
     *
     * - Shizuku 已是 root 模式：直接执行原命令
     * - 要求 root 但 Shizuku 非 root 模式：通过 `su -c` 提权
     * - 其他：直接执行原命令
     */
    private fun buildCommand(command: String, asRoot: Boolean): String {
        if (!asRoot) return command
        // Shizuku 已是 root 模式则无需再提权
        if (ShizukuServiceBinder.isRootMode()) return command
        // 通过 su -c 提权，对单引号做转义以保证命令完整性
        val escaped = command.replace("'", "'\\''")
        return "su -c '$escaped'"
    }
}
