package com.mobileclaw.app.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.mobileclaw.app.model.AppInfo
import com.mobileclaw.app.model.BatteryInfo
import com.mobileclaw.app.model.CpuInfo
import com.mobileclaw.app.model.MemoryInfo
import com.mobileclaw.app.model.ProcessInfo
import com.mobileclaw.app.model.StorageInfo
import com.mobileclaw.app.model.SystemInfo
import com.mobileclaw.app.shizuku.IShizukuService
import com.mobileclaw.app.shizuku.ShizukuManager
import com.mobileclaw.app.shizuku.ShizukuServiceBinder
import kotlinx.coroutines.delay

/**
 * 系统信息采集器。
 *
 * 通过 [IShizukuService]（运行在 Shizuku 服务进程）执行系统级命令或读取系统文件，
 * 采集内存、CPU、电池、存储、进程与应用等信息，并返回 [model] 包中的结构化数据。
 *
 * @param context 应用上下文，用于绑定 UserService 与访问 PackageManager/StatFs
 */
class SystemInfoCollector(private val context: Context) {

    companion object {
        private const val TAG = "SystemInfoCollector"
        /** CPU 使用率采样间隔（毫秒）。 */
        private const val CPU_SAMPLE_INTERVAL_MS = 200L
        /** 单次 shell 命令默认超时（毫秒）。 */
        private const val SHELL_TIMEOUT_MS = 5000L
    }

    /**
     * 获取（必要时绑定）UserService 实例。
     *
     * @return 已连接的 [IShizukuService]
     * @throws IllegalStateException 当 Shizuku 不可用或服务连接超时
     */
    private suspend fun service(): IShizukuService {
        if (!ShizukuManager.isShizukuAvailable()) {
            throw IllegalStateException("Shizuku is not available")
        }
        if (!ShizukuServiceBinder.isBound()) {
            ShizukuServiceBinder.bind(context)
        }
        return ShizukuServiceBinder.requireService()
    }

    // ==================================================================================
    // 汇总采集
    // ==================================================================================

    /**
     * 一次性采集全部系统信息（内存、CPU、电池、存储）。
     *
     * @return 聚合后的 [SystemInfo]；采集失败的字段返回默认值
     */
    suspend fun collectAll(): SystemInfo {
        return try {
            val s = service()
            val mem = getMemoryInfo(s)
            val cpu = getCpuInfo(s)
            val battery = getBatteryInfo(s)
            val storage = getStorageInfo()
            SystemInfo(mem, cpu, battery, storage)
        } catch (e: Exception) {
            Log.e(TAG, "collectAll failed", e)
            SystemInfo()
        }
    }

    // ==================================================================================
    // 内存信息
    // ==================================================================================

    /**
     * 获取内存信息。通过读取 `/proc/meminfo` 解析 MemTotal 与 MemAvailable。
     */
    suspend fun getMemoryInfo(): MemoryInfo {
        return try {
            getMemoryInfo(service())
        } catch (e: Exception) {
            Log.e(TAG, "getMemoryInfo failed", e)
            MemoryInfo()
        }
    }

    private suspend fun getMemoryInfo(s: IShizukuService): MemoryInfo {
        val content = s.readFile("/proc/meminfo")
        var total = 0L
        var available = 0L
        content.lineSequence().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> total = extractFirstNumber(line) * 1024L
                line.startsWith("MemAvailable:") -> available = extractFirstNumber(line) * 1024L
            }
        }
        // 若 MemAvailable 不可用（旧内核），回退使用 MemFree
        if (available == 0L) {
            content.lineSequence().forEach { line ->
                if (line.startsWith("MemFree:")) available = extractFirstNumber(line) * 1024L
            }
        }
        val used = (total - available).coerceAtLeast(0L)
        val percent = if (total > 0) used.toFloat() / total * 100f else 0f
        return MemoryInfo(total, available, used, percent)
    }

    // ==================================================================================
    // CPU 信息
    // ==================================================================================

    /**
     * 获取 CPU 信息。核心数取自 [Runtime.availableProcessors]，
     * 频率取自 `/sys/devices/system/cpu/cpu0/cpufreq/`，
     * 使用率通过对 `/proc/stat` 两次采样计算得出。
     */
    suspend fun getCpuInfo(): CpuInfo {
        return try {
            getCpuInfo(service())
        } catch (e: Exception) {
            Log.e(TAG, "getCpuInfo failed", e)
            CpuInfo()
        }
    }

    private suspend fun getCpuInfo(s: IShizukuService): CpuInfo {
        val cores = Runtime.getRuntime().availableProcessors()
        val maxFreq = readFreq(s, "cpuinfo_max_freq")
        val minFreq = readFreq(s, "cpuinfo_min_freq")
        val curFreq = readFreq(s, "scaling_cur_freq")
        val usage = calculateCpuUsage(s)
        return CpuInfo(cores, maxFreq, minFreq, curFreq, usage)
    }

    /** 读取 cpu0 的指定频率文件（单位 kHz）。 */
    private suspend fun readFreq(s: IShizukuService, name: String): Long {
        val raw = s.readFile("/sys/devices/system/cpu/cpu0/cpufreq/$name").trim()
        return raw.toLongOrNull() ?: 0L
    }

    /**
     * 通过两次采样 `/proc/stat` 计算 CPU 使用率。
     *
     * 使用率 = (totalDelta - idleDelta) / totalDelta * 100
     */
    private suspend fun calculateCpuUsage(s: IShizukuService): Float {
        return try {
            val t1 = parseCpuTimes(s.readFile("/proc/stat"))
            delay(CPU_SAMPLE_INTERVAL_MS)
            val t2 = parseCpuTimes(s.readFile("/proc/stat"))
            val totalDelta = t2.total - t1.total
            val idleDelta = t2.idle - t1.idle
            if (totalDelta <= 0L) return 0f
            val usage = (totalDelta - idleDelta).toFloat() / totalDelta * 100f
            usage.coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.e(TAG, "calculateCpuUsage failed", e)
            0f
        }
    }

    /** 解析 `/proc/stat` 第一行 `cpu` 的累计时间。 */
    private fun parseCpuTimes(stat: String): CpuTimes {
        val firstLine = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return CpuTimes(0L, 0L)
        val parts = firstLine.trim().split(Regex("\\s+"))
        // parts: [cpu, user, nice, system, idle, iowait, irq, softirq, steal, ...]
        if (parts.size < 5) return CpuTimes(0L, 0L)
        val user = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val nice = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val system = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val idle = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L
        val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0L
        val total = user + nice + system + idle + iowait + irq + softirq + steal
        val idleAll = idle + iowait
        return CpuTimes(total, idleAll)
    }

    // ==================================================================================
    // 电池信息
    // ==================================================================================

    /**
     * 获取电池信息。通过执行 `dumpsys battery` 解析电量、温度、充电状态与技术类型。
     */
    suspend fun getBatteryInfo(): BatteryInfo {
        return try {
            getBatteryInfo(service())
        } catch (e: Exception) {
            Log.e(TAG, "getBatteryInfo failed", e)
            BatteryInfo()
        }
    }

    private suspend fun getBatteryInfo(s: IShizukuService): BatteryInfo {
        // executeShell 返回 [exitCode, stdout, stderr]，stdout 位于索引 1
        val result = s.executeShell("dumpsys battery", SHELL_TIMEOUT_MS)
        val output = result.getOrElse(1) { "" }

        var level = 0
        var temperature = 0
        var technology = ""
        var acPowered = false
        var usbPowered = false
        var wirelessPowered = false

        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("level:") ->
                    level = trimmed.substringAfter(":").trim().toIntOrNull() ?: 0
                trimmed.startsWith("temperature:") ->
                    temperature = trimmed.substringAfter(":").trim().toIntOrNull() ?: 0
                trimmed.startsWith("technology:") ->
                    technology = trimmed.substringAfter(":").trim()
                trimmed.startsWith("AC powered:") ->
                    acPowered = trimmed.substringAfter(":").trim().equals("true", ignoreCase = true)
                trimmed.startsWith("USB powered:") ->
                    usbPowered = trimmed.substringAfter(":").trim().equals("true", ignoreCase = true)
                trimmed.startsWith("Wireless powered:") ->
                    wirelessPowered = trimmed.substringAfter(":").trim().equals("true", ignoreCase = true)
            }
        }
        // 正在接收外部电源即视为充电中
        val isCharging = acPowered || usbPowered || wirelessPowered
        return BatteryInfo(level, temperature, isCharging, technology)
    }

    // ==================================================================================
    // 存储信息
    // ==================================================================================

    /**
     * 获取存储信息。使用 [StatFs] 读取数据分区容量（无需特权权限）。
     */
    fun getStorageInfo(): StorageInfo {
        return try {
            val path = Environment.getDataDirectory().absolutePath
            val stat = StatFs(path)
            val total = stat.totalBytes
            val available = stat.availableBytes
            val used = (total - available).coerceAtLeast(0L)
            val percent = if (total > 0) used.toFloat() / total * 100f else 0f
            StorageInfo(total, available, used, percent)
        } catch (e: Exception) {
            Log.e(TAG, "getStorageInfo failed", e)
            StorageInfo()
        }
    }

    // ==================================================================================
    // 运行中的进程列表
    // ==================================================================================

    /**
     * 获取运行中的进程列表。通过 `ps -A -o PID,RSS,NAME` 解析。
     *
     * @return 进程信息列表
     */
    suspend fun getRunningProcesses(): List<ProcessInfo> {
        return try {
            val s = service()
            val lines = s.getRunningProcessNames()
            // 跳过表头行
            lines.drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
                    val rss = parts[1].toLongOrNull() ?: 0L // 单位 KB
                    val name = parts.subList(2, parts.size).joinToString(" ")
                    ProcessInfo(pid, name, rss, 0f)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRunningProcesses failed", e)
            emptyList()
        }
    }

    // ==================================================================================
    // 已安装应用列表
    // ==================================================================================

    /**
     * 获取已安装应用列表。
     *
     * 包名列表通过 Shizuku 执行 `pm list packages` 获取（特权），
     * 应用名、版本、安装时间等元数据通过本进程 [PackageManager] 补全。
     *
     * @param includeSystem 是否包含系统应用
     * @return 应用信息列表
     */
    suspend fun getInstalledApps(includeSystem: Boolean = false): List<AppInfo> {
        return try {
            val s = service()
            val pkgNames = s.getInstalledPackages(includeSystem)
                .map { it.trim().removePrefix("package:").trim() }
                .filter { it.isNotEmpty() }

            val pm = context.packageManager
            // 一次性查询所有已安装包，避免逐包 IPC 调用
            val pkgMap = try {
                pm.getInstalledPackages(0).associateBy { it.packageName }
            } catch (e: Exception) {
                Log.w(TAG, "getInstalledPackages via PM failed", e)
                emptyMap()
            }

            pkgNames.mapNotNull { pkg ->
                val info = pkgMap[pkg]
                if (info != null && info.applicationInfo != null) {
                    val appInfo = info.applicationInfo!!
                    val appName = try {
                        appInfo.loadLabel(pm).toString()
                    } catch (e: Exception) {
                        pkg
                    }
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    AppInfo(
                        packageName = pkg,
                        appName = appName,
                        versionName = info.versionName ?: "",
                        isSystem = isSystem,
                        installTime = info.firstInstallTime
                    )
                } else {
                    // PackageManager 查询不到时仅保留包名
                    AppInfo(packageName = pkg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledApps failed", e)
            emptyList()
        }
    }

    // ==================================================================================
    // 工具方法
    // ==================================================================================

    /** 从字符串中提取第一个数字。 */
    private fun extractFirstNumber(text: String): Long {
        return Regex("\\d+").find(text)?.value?.toLongOrNull() ?: 0L
    }

    /** CPU 累计时间快照。 */
    private data class CpuTimes(val total: Long, val idle: Long)
}
