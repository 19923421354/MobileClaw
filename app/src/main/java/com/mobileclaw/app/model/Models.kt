package com.mobileclaw.app.model

import kotlinx.serialization.Serializable

/**
 * 所有数据模型定义。
 *
 * 该文件集中定义 MobileClaw 应用中跨模块复用的数据结构，
 * 包括 Shell 执行结果、系统信息、应用/进程信息以及 AI 动作模型。
 * 所有需要序列化（例如持久化或传递给 UI 层）的模型均标注了 [Serializable]。
 */

// ----------------------------------------------------------------------------------
// Shell 执行结果
// ----------------------------------------------------------------------------------

/**
 * Shell 命令执行结果。
 *
 * @property stdout  标准输出内容
 * @property stderr  标准错误输出内容
 * @property exitCode 退出码；0 表示成功，非 0 表示失败
 */
@Serializable
data class ShellResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1
) {
    /** 命令是否执行成功（退出码为 0）。 */
    val isSuccess: Boolean get() = exitCode == 0
}

// ----------------------------------------------------------------------------------
// 系统信息相关模型
// ----------------------------------------------------------------------------------

/**
 * 内存信息（单位：字节）。
 *
 * @property total          设备总内存
 * @property available      当前可用内存
 * @property used           已使用内存（total - available）
 * @property usagePercent   内存使用率（0-100）
 */
@Serializable
data class MemoryInfo(
    val total: Long = 0L,
    val available: Long = 0L,
    val used: Long = 0L,
    val usagePercent: Float = 0f
)

/**
 * CPU 信息。
 *
 * @property cores    CPU 核心数
 * @property maxFreq  最大频率（kHz）
 * @property minFreq  最小频率（kHz）
 * @property curFreq  当前频率（kHz）
 * @property usage    CPU 使用率（0-100）
 */
@Serializable
data class CpuInfo(
    val cores: Int = 0,
    val maxFreq: Long = 0L,
    val minFreq: Long = 0L,
    val curFreq: Long = 0L,
    val usage: Float = 0f
)

/**
 * 电池信息。
 *
 * @property level       电量百分比（0-100）
 * @property temperature 电池温度（0.1℃ 为单位，如 310 表示 31.0℃）
 * @property isCharging  是否正在充电
 * @property technology  电池技术类型（如 "Li-ion"）
 */
@Serializable
data class BatteryInfo(
    val level: Int = 0,
    val temperature: Int = 0,
    val isCharging: Boolean = false,
    val technology: String = ""
)

/**
 * 存储信息（单位：字节）。
 *
 * @property total          存储总容量
 * @property available      可用容量
 * @property used           已使用容量
 * @property usagePercent   存储使用率（0-100）
 */
@Serializable
data class StorageInfo(
    val total: Long = 0L,
    val available: Long = 0L,
    val used: Long = 0L,
    val usagePercent: Float = 0f
)

/**
 * 系统信息汇总。
 *
 * 聚合内存、CPU、电池与存储信息，便于一次性获取整机状态。
 */
@Serializable
data class SystemInfo(
    val memInfo: MemoryInfo = MemoryInfo(),
    val cpuInfo: CpuInfo = CpuInfo(),
    val batteryInfo: BatteryInfo = BatteryInfo(),
    val storageInfo: StorageInfo = StorageInfo()
)

// ----------------------------------------------------------------------------------
// 应用与进程信息
// ----------------------------------------------------------------------------------

/**
 * 已安装应用信息。
 *
 * @property packageName  应用包名
 * @property appName      应用名称
 * @property versionName  版本名
 * @property isSystem     是否为系统应用
 * @property installTime  首次安装时间（毫秒时间戳）
 */
@Serializable
data class AppInfo(
    val packageName: String = "",
    val appName: String = "",
    val versionName: String = "",
    val isSystem: Boolean = false,
    val installTime: Long = 0L
)

/**
 * 运行中的进程信息。
 *
 * @property pid         进程 ID
 * @property name        进程名
 * @property memoryUsage 内存占用（KB）
 * @property cpuUsage    CPU 使用率（0-100）
 */
@Serializable
data class ProcessInfo(
    val pid: Int = 0,
    val name: String = "",
    val memoryUsage: Long = 0L,
    val cpuUsage: Float = 0f
)

// ----------------------------------------------------------------------------------
// AI 动作与执行结果模型
// ----------------------------------------------------------------------------------

/**
 * AI 解析后的动作类型枚举。
 */
@Serializable
enum class ClawActionType {
    /** 执行 Shell 命令 */
    SHELL,
    /** 打开应用 */
    OPEN_APP,
    /** 卸载应用 */
    UNINSTALL_APP,
    /** 清除应用数据 */
    CLEAR_APP_DATA,
    /** 终止进程 */
    KILL_PROCESS,
    /** 获取系统信息 */
    GET_SYSTEM_INFO,
    /** 获取应用列表 */
    GET_APP_LIST,
    /** 获取进程列表 */
    GET_PROCESS_LIST,
    /** 自定义动作 */
    CUSTOM
}

/**
 * AI 解析后的动作模型。
 *
 * 由 AI 解析用户自然语言指令后生成，交由执行器执行。
 *
 * @property type   动作类型
 * @property params 动作参数键值对（如命令内容、目标包名等）
 */
@Serializable
data class ClawAction(
    val type: ClawActionType = ClawActionType.CUSTOM,
    val params: Map<String, String> = emptyMap()
)

/**
 * 执行结果模型。
 *
 * @property success 是否执行成功
 * @property message 结果描述信息
 * @property data    附带数据（序列化为 JSON 字符串）
 */
@Serializable
data class ClawResult(
    val success: Boolean = false,
    val message: String = "",
    val data: String = ""
) {
    companion object {
        /** 构造一个成功结果。 */
        fun success(message: String, data: String = ""): ClawResult =
            ClawResult(success = true, message = message, data = data)

        /** 构造一个失败结果。 */
        fun failure(message: String, data: String = ""): ClawResult =
            ClawResult(success = false, message = message, data = data)
    }
}
