package com.mobileclaw.app.ai

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用状态管理器 —— 管理应用生命周期状态，追踪应用状态转换，优化应用状态以提升任务执行效率。
 *
 * ============================================================
 * 核心理念
 * ============================================================
 *
 * 在手机自动化操控场景中，AI 需要频繁地打开、关闭、切换应用。如果系统不维护应用的状态信息，
 * AI 将无法判断「微信是否已经在后台」「抖音是否刚打开」「某个应用是否已崩溃」，导致：
 * 重复打开已在前台的应用浪费 Token 和时长；在已崩溃的应用上继续执行导致后续动作全部失败；
 * 无法感知内存压力导致低内存场景下的操作失效率增加；每次启动应用都走完整流程无法利用预热优化。
 *
 * 本管理器通过显式的应用生命周期状态管理，让 AI 始终知晓每个应用的当前状态、健康情况、
 * 资源消耗，并基于这些信息做出最优的启动决策。
 *
 * ============================================================
 * 生命周期状态机
 * ============================================================
 *
 * ```
 *     CLOSED ──→ LAUNCHING ──→ FOREGROUND ──→ BACKGROUND ──→ SUSPENDED
 *       ↑            │              │               │             │
 *       └────────────┴──── CLOSED ──┘───────────────┘─────────────┘
 *                    │
 *                    ▼
 *                 CRASHED ──→ CLOSED
 * ```
 *
 * 八大核心能力：
 * 1. 应用生命周期追踪：为每个应用维护独立的生命周期状态机，支持状态查询和历史追溯。
 * 2. 状态转换管理：管理并优化应用状态转换，非法转换会被拒绝并记录日志。
 * 3. 重启优化：检测应用当前状态决定冷启动还是热启动，减少不必要的启动开销。
 * 4. 状态持久化：将应用状态信息持久化到本地文件，支持崩溃恢复后的状态还原。
 * 5. 应用健康监控：监控应用健康状况（ANR检测、崩溃检测、内存压力），异常时及时标记。
 * 6. 资源管理：追踪每个应用的资源使用情况（内存、电量、耗时），为资源调度提供数据支撑。
 * 7. 启动优化：通过预预热、启动标志配置、深度链接支持等方式优化应用启动。
 * 8. 线程安全：全部存储使用 [ConcurrentHashMap]，复合操作使用同步锁保护。
 *
 * 使用方式：
 * ```
 * val mgr = AppStateManager()
 * mgr.trackAppState("com.tencent.mm", AppLifecycleState.LAUNCHING)
 * mgr.trackAppState("com.tencent.mm", AppLifecycleState.FOREGROUND)
 * val state = mgr.getAppState("com.tencent.mm")
 * val config = mgr.optimizeLaunch("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
 * mgr.persistState()
 * mgr.restoreState()
 * ```
 */
class AppStateManager {

    // ============================================================
    // 日志标签
    // ============================================================

    private val tag = "AppStateManager"

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 应用生命周期状态。
     *
     * 状态转换约束：
     * - CLOSED → LAUNCHING / UNKNOWN
     * - LAUNCHING → FOREGROUND / BACKGROUND / CRASHED / CLOSED / UNKNOWN
     * - FOREGROUND → BACKGROUND / CRASHED / CLOSED / UNKNOWN
     * - BACKGROUND → FOREGROUND / SUSPENDED / CLOSED / CRASHED / UNKNOWN
     * - SUSPENDED → FOREGROUND / CLOSED / UNKNOWN
     * - CRASHED → CLOSED / LAUNCHING / UNKNOWN
     * - UNKNOWN → CLOSED / LAUNCHING / UNKNOWN
     */
    enum class AppLifecycleState {
        /** 应用未运行，初始状态。 */
        CLOSED,
        /** 应用正在启动中（从发起启动到主界面渲染完成）。 */
        LAUNCHING,
        /** 应用在前台运行，用户可见且可交互。 */
        FOREGROUND,
        /** 应用在后台运行，用户不可见但仍可执行任务。 */
        BACKGROUND,
        /** 应用被系统挂起（如低内存回收），进程可能仍存在但已无活动。 */
        SUSPENDED,
        /** 应用已崩溃（ANR、闪退、异常退出）。 */
        CRASHED,
        /** 状态未知（初始化或恢复失败时的兜底值）。 */
        UNKNOWN
    }

    /**
     * 应用健康状态。
     *
     * @property value 健康等级数值（值越大越严重）
     */
    enum class HealthStatus(val value: Int) {
        /** 健康：应用运行正常。 */
        HEALTHY(0),
        /** 警告：存在轻微异常（偶发卡顿），但可继续运行。 */
        WARNING(1),
        /** 无响应：主线程卡死超过阈值，需要干预。 */
        UNRESPONSIVE(2),
        /** 已崩溃：应用已异常退出，需要重新启动。 */
        CRASHED(3),
        /** 低内存：设备内存不足，应用可能被系统回收。 */
        LOW_MEMORY(4)
    }

    /**
     * 启动方式 —— 描述应用启动的路径和优化策略。
     */
    enum class LaunchMethod {
        /** 标准启动：从 CLOSED 状态冷启动，走完整启动流程。 */
        STANDARD,
        /** 深度链接启动：通过 URL Scheme 或 Intent 深度链接打开应用指定页面。 */
        DEEP_LINK,
        /** 热启动：应用已在后台，直接切换回前台（耗时最短）。 */
        WARM_START,
        /** 预预热启动：已提前预加载了应用进程或资源，启动速度优于标准启动。 */
        PRE_WARMED
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 应用状态 —— 单个应用的完整状态快照。
     *
     * @property packageName      应用包名（唯一标识）
     * @property lifecycleState   当前生命周期状态
     * @property healthStatus     当前健康状态
     * @property resourceUsage    资源使用情况
     * @property launchConfig     启动配置信息
     * @property lastTransition   最后状态转换信息
     * @property foregroundTime   累计前台运行时间（毫秒）
     * @property backgroundTime   累计后台运行时间（毫秒）
     * @property launchCount      累计启动次数
     * @property crashCount       累计崩溃次数
     * @property lastLaunchTime   最后启动时间戳（毫秒）
     * @property lastForegroundTime  最后进入前台时间戳
     * @property lastBackgroundTime  最后进入后台时间戳
     * @property lastCrashTime    最后崩溃时间戳，从未崩溃为 0
     * @property lastCrashReason  最后崩溃原因，从未崩溃为 null
     * @property isPinned         是否固定（固定应用不会被 LRU 淘汰）
     * @property tags             自定义标签
     * @property metadata         扩展元数据（键值对）
     */
    data class AppState(
        val packageName: String,
        var lifecycleState: AppLifecycleState = AppLifecycleState.CLOSED,
        var healthStatus: HealthStatus = HealthStatus.HEALTHY,
        var resourceUsage: ResourceUsage = ResourceUsage(),
        var launchConfig: LaunchConfig = LaunchConfig(),
        var lastTransition: StateTransition? = null,
        var foregroundTime: Long = 0L,
        var backgroundTime: Long = 0L,
        var launchCount: Int = 0,
        var crashCount: Int = 0,
        var lastLaunchTime: Long = 0L,
        var lastForegroundTime: Long = 0L,
        var lastBackgroundTime: Long = 0L,
        var lastCrashTime: Long = 0L,
        var lastCrashReason: String? = null,
        var isPinned: Boolean = false,
        val tags: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val metadata: MutableMap<String, String> = ConcurrentHashMap()
    )

    /**
     * 状态转换记录 —— 单次应用状态转换的完整记录。
     *
     * @property fromState     转换前的状态
     * @property toState       转换后的状态
     * @property timestamp     转换时间戳（毫秒）
     * @property trigger       触发原因（USER_ACTION、SYSTEM_EVENT、CRASH 等）
     * @property durationMs    转换耗时（毫秒）
     * @property success       转换是否成功
     * @property errorMessage  转换失败时的错误信息
     */
    data class StateTransition(
        val fromState: AppLifecycleState,
        val toState: AppLifecycleState,
        val timestamp: Long,
        val trigger: String = "UNKNOWN",
        val durationMs: Long = 0L,
        val success: Boolean = true,
        val errorMessage: String? = null
    )

    /**
     * 应用健康信息 —— 应用健康状况的综合评估。
     *
     * @property status             健康状态
     * @property lastHeartbeat      最后心跳时间戳，0 表示从未收到
     * @property heartbeatIntervalMs  心跳间隔（毫秒）
     * @property anrCount           ANR 累计次数
     * @property anrTimestamps      最近 ANR 时间戳列表
     * @property crashCount         崩溃累计次数
     * @property crashTimestamps    最近崩溃时间戳列表
     * @property responseTimeMs     最近响应时间（毫秒），-1 表示未知
     * @property avgResponseTimeMs  平均响应时间（毫秒），-1 表示未知
     * @property memoryPressure     内存压力等级（0.0-1.0）
     * @property threadCount        当前线程数，-1 表示未知
     * @property cpuUsagePercent    CPU 使用率百分比（0-100），-1 表示未知
     * @property evaluationTime     评估时间戳
     */
    data class AppHealth(
        val status: HealthStatus = HealthStatus.HEALTHY,
        val lastHeartbeat: Long = 0L,
        val heartbeatIntervalMs: Long = 5000L,
        val anrCount: Int = 0,
        val anrTimestamps: List<Long> = emptyList(),
        val crashCount: Int = 0,
        val crashTimestamps: List<Long> = emptyList(),
        val responseTimeMs: Long = -1L,
        val avgResponseTimeMs: Long = -1L,
        val memoryPressure: Float = 0.0f,
        val threadCount: Int = -1,
        val cpuUsagePercent: Float = -1f,
        val evaluationTime: Long = System.currentTimeMillis()
    )

    /**
     * 资源使用情况 —— 单个应用的资源消耗统计。
     *
     * @property memoryKb         当前内存占用（KB），-1 表示未知
     * @property peakMemoryKb     峰值内存占用（KB）
     * @property batteryPercent   电池消耗占比（0.0-100.0），-1 表示未知
     * @property foregroundTimeMs 前台运行时间（毫秒）
     * @property backgroundTimeMs 后台运行时间（毫秒）
     * @property totalCpuTimeMs   CPU 总耗时（毫秒）
     * @property networkTxBytes   网络发送字节数
     * @property networkRxBytes   网络接收字节数
     * @property diskReadBytes    磁盘读取字节数
     * @property diskWriteBytes   磁盘写入字节数
     * @property startCount       启动次数
     * @property lastUpdated      最后更新时间戳
     */
    data class ResourceUsage(
        var memoryKb: Long = -1L,
        var peakMemoryKb: Long = 0L,
        var batteryPercent: Float = -1f,
        var foregroundTimeMs: Long = 0L,
        var backgroundTimeMs: Long = 0L,
        var totalCpuTimeMs: Long = 0L,
        var networkTxBytes: Long = 0L,
        var networkRxBytes: Long = 0L,
        var diskReadBytes: Long = 0L,
        var diskWriteBytes: Long = 0L,
        var startCount: Int = 0,
        var lastUpdated: Long = System.currentTimeMillis()
    )

    /**
     * 启动配置 —— 应用的启动参数和优化策略。
     *
     * @property preferredLaunchMethod  优先启动方式
     * @property launchFlags            启动 Intent 标志位
     * @property deepLinkUri            深度链接 URI（可选）
     * @property deepLinkAction         深度链接 Action（可选）
     * @property preWarmEnabled         是否启用预预热
     * @property preWarmTimeoutMs       预预热超时时间（毫秒）
     * @property warmStartEnabled       是否允许热启动
     * @property clearTaskOnNewIntent   是否在启动时清除任务栈
     * @property launchTimeoutMs        启动超时时间（毫秒）
     * @property retryOnFailure         启动失败时是否自动重试
     * @property maxRetryCount          最大重试次数
     * @property preferredActivity       首选 Activity 类名（可选）
     * @property extraExtras             额外 Intent Extra 参数
     * @property lastUpdated             最后更新时间戳
     */
    data class LaunchConfig(
        var preferredLaunchMethod: LaunchMethod = LaunchMethod.STANDARD,
        var launchFlags: Int = 0,
        var deepLinkUri: String? = null,
        var deepLinkAction: String? = null,
        var preWarmEnabled: Boolean = false,
        var preWarmTimeoutMs: Long = 5000L,
        var warmStartEnabled: Boolean = true,
        var clearTaskOnNewIntent: Boolean = false,
        var launchTimeoutMs: Long = 15000L,
        var retryOnFailure: Boolean = true,
        var maxRetryCount: Int = 3,
        var preferredActivity: String? = null,
        val extraExtras: MutableMap<String, String> = ConcurrentHashMap(),
        var lastUpdated: Long = System.currentTimeMillis()
    )

    /**
     * 应用状态记录 —— 持久化使用的完整状态记录。
     *
     * @property packageName      应用包名
     * @property lifecycleState   生命周期状态名称
     * @property healthStatus     健康状态名称
     * @property launchCount      累计启动次数
     * @property crashCount       累计崩溃次数
     * @property foregroundTime   累计前台时间（毫秒）
     * @property backgroundTime   累计后台时间（毫秒）
     * @property lastLaunchTime   最后启动时间戳
     * @property lastForegroundTime  最后前台时间戳
     * @property lastBackgroundTime  最后后台时间戳
     * @property lastCrashTime    最后崩溃时间戳
     * @property lastCrashReason  最后崩溃原因
     * @property memoryKb         内存占用（KB）
     * @property tags             标签列表
     * @property recordTimestamp  记录时间戳
     */
    data class AppStateRecord(
        val packageName: String,
        val lifecycleState: String,
        val healthStatus: String,
        val launchCount: Int,
        val crashCount: Int,
        val foregroundTime: Long,
        val backgroundTime: Long,
        val lastLaunchTime: Long,
        val lastForegroundTime: Long,
        val lastBackgroundTime: Long,
        val lastCrashTime: Long,
        val lastCrashReason: String?,
        val memoryKb: Long,
        val tags: List<String>,
        val recordTimestamp: Long
    )

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 应用状态存储（packageName → AppState），线程安全。 */
    private val appStates = ConcurrentHashMap<String, AppState>()

    /** 应用健康信息存储（packageName → AppHealth），线程安全。 */
    private val appHealthMap = ConcurrentHashMap<String, AppHealth>()

    /** 应用状态历史存储（packageName → 状态历史列表），线程安全。 */
    private val stateHistory = ConcurrentHashMap<String, ConcurrentLinkedDeque<StateTransition>>()

    /** 应用资源使用历史存储（packageName → 资源快照列表），线程安全。 */
    private val resourceHistory = ConcurrentHashMap<String, ConcurrentLinkedDeque<ResourceUsage>>()

    /** 当前前台应用包名。 */
    @Volatile
    private var currentForegroundApp: String? = null

    /** 状态转换同步锁，保证复合操作的原子性。 */
    private val stateLock = Any()

    /** 持久化同步锁，防止并发读写持久化文件。 */
    private val persistLock = Any()

    // ============================================================
    // 配置常量（Companion）
    // ============================================================

    companion object {
        /** 最大追踪应用数（默认 50），超出按 LRU 淘汰。 */
        private const val MAX_TRACKED_APPS = 50
        /** 每个应用最多保留的状态历史条数（默认 100）。 */
        private const val MAX_HISTORY_PER_APP = 100
        /** 状态历史全局最大保留条数（默认 5000）。 */
        private const val MAX_TOTAL_HISTORY = 5000
        /** 持久化文件最大大小（字节，默认 1MB）。 */
        private const val MAX_PERSIST_FILE_SIZE = 1 * 1024 * 1024
        /** 持久化文件名。 */
        private const val PERSIST_FILE_NAME = "app_state_manager_state.json"
        /** 默认持久化目录名。 */
        private const val DEFAULT_PERSIST_DIR = "app_state_manager"
        /** 心跳超时阈值（毫秒），默认 30 秒无心跳视为无响应。 */
        private const val HEARTBEAT_TIMEOUT_MS = 30_000L
        /** ANR 检测阈值（毫秒），主线程阻塞超过此值视为 ANR 前兆。 */
        private const val ANR_DETECTION_THRESHOLD_MS = 5000L
        /** 内存压力检测阈值（KB），应用内存占用超过此值视为高内存压力。 */
        private const val MEMORY_PRESSURE_THRESHOLD_KB = 500_000L
        /** 启动超时检测阈值（毫秒）。 */
        private const val LAUNCH_TIMEOUT_THRESHOLD_MS = 30_000L
        /** 最多保留的 ANR 记录数。 */
        private const val MAX_ANR_RECORDS = 10
        /** 最多保留的崩溃记录数。 */
        private const val MAX_CRASH_RECORDS = 10
        /** 预预热提前启动时间（毫秒）。 */
        private const val PRE_WARM_ADVANCE_TIME_MS = 2000L
        /** 冷启动预期耗时（毫秒）。 */
        private const val COLD_START_EXPECTED_MS = 3000L
        /** 热启动预期耗时（毫秒）。 */
        private const val WARM_START_EXPECTED_MS = 500L
        /** 预预热启动预期耗时（毫秒）。 */
        private const val PRE_WARMED_START_EXPECTED_MS = 800L
        /** 健康检查间隔（毫秒），默认 60 秒。 */
        private const val HEALTH_CHECK_INTERVAL_MS = 60_000L
        /** 自动持久化间隔（毫秒），默认 5 分钟。 */
        private const val AUTO_PERSIST_INTERVAL_MS = 5 * 60 * 1000L
        /** 启动跟踪超时时间（毫秒），LAUNCHING 超过此时间视为启动失败。 */
        private const val LAUNCH_TRACKING_TIMEOUT_MS = 30_000L
        /** 应用关闭后保留状态信息的冷却时间（毫秒）。 */
        private const val STATE_COOLDOWN_MS = 60_000L

        /** 有效状态转换规则表（from → to 是否合法）。 */
        private val VALID_TRANSITIONS: Map<AppLifecycleState, Set<AppLifecycleState>> = mapOf(
            AppLifecycleState.CLOSED to setOf(AppLifecycleState.LAUNCHING, AppLifecycleState.UNKNOWN),
            AppLifecycleState.LAUNCHING to setOf(
                AppLifecycleState.FOREGROUND, AppLifecycleState.BACKGROUND,
                AppLifecycleState.CRASHED, AppLifecycleState.CLOSED, AppLifecycleState.UNKNOWN
            ),
            AppLifecycleState.FOREGROUND to setOf(
                AppLifecycleState.BACKGROUND, AppLifecycleState.CRASHED,
                AppLifecycleState.CLOSED, AppLifecycleState.UNKNOWN
            ),
            AppLifecycleState.BACKGROUND to setOf(
                AppLifecycleState.FOREGROUND, AppLifecycleState.SUSPENDED,
                AppLifecycleState.CLOSED, AppLifecycleState.CRASHED, AppLifecycleState.UNKNOWN
            ),
            AppLifecycleState.SUSPENDED to setOf(
                AppLifecycleState.FOREGROUND, AppLifecycleState.CLOSED, AppLifecycleState.UNKNOWN
            ),
            AppLifecycleState.CRASHED to setOf(
                AppLifecycleState.CLOSED, AppLifecycleState.LAUNCHING, AppLifecycleState.UNKNOWN
            ),
            AppLifecycleState.UNKNOWN to setOf(
                AppLifecycleState.CLOSED, AppLifecycleState.LAUNCHING, AppLifecycleState.UNKNOWN
            )
        )

        /** 时间格式化器（用于日志和调试输出）。 */
        private val timeFormatter = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    // ============================================================
    // 统计计数
    // ============================================================

    private val totalTrackedApps = AtomicInteger(0)
    private val totalTransitions = AtomicLong(0)
    private val totalInvalidTransitions = AtomicInteger(0)
    private val totalPersistCount = AtomicInteger(0)
    private val totalRestoreCount = AtomicInteger(0)
    private val totalOptimizeRequests = AtomicInteger(0)
    private val totalHealthChecks = AtomicInteger(0)

    /** 持久化文件路径。 */
    private var persistDir: String = ""

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * 初始化管理器，设置持久化目录。建议在 Application.onCreate 中调用。
     *
     * @param persistDirectory 持久化目录路径，为空时使用默认缓存目录
     */
    fun initialize(persistDirectory: String = "") {
        persistDir = if (persistDirectory.isBlank()) {
            "${System.getProperty("java.io.tmpdir")}/$DEFAULT_PERSIST_DIR"
        } else {
            persistDirectory
        }
        val dir = File(persistDir)
        if (!dir.exists()) dir.mkdirs()
        Log.d(tag, "初始化完成，持久化目录: $persistDir")
    }

    // ============================================================
    // 核心方法：应用状态追踪
    // ============================================================

    /**
     * 追踪应用状态 —— 记录或更新一个应用的生命周期状态。
     *
     * 内部自动处理：创建新状态、合法性校验、统计更新、前台指针维护、LRU 淘汰。
     *
     * @param packageName 应用包名（唯一标识）
     * @param newState    新的生命周期状态
     * @param trigger     触发原因，默认 AUTO
     * @return 更新后的 [AppState]，包名为空返回 null
     */
    fun trackAppState(
        packageName: String,
        newState: AppLifecycleState,
        trigger: String = "AUTO"
    ): AppState? {
        if (packageName.isBlank()) {
            Log.w(tag, "trackAppState 失败: 包名为空")
            return null
        }

        synchronized(stateLock) {
            val state = appStates.computeIfAbsent(packageName) { pkg ->
                totalTrackedApps.incrementAndGet()
                Log.d(tag, "开始追踪应用: $pkg (第 ${totalTrackedApps.get()} 个)")
                AppState(packageName = pkg)
            }

            val oldState = state.lifecycleState
            val now = System.currentTimeMillis()

            if (!isValidTransition(oldState, newState)) {
                totalInvalidTransitions.incrementAndGet()
                Log.w(tag, "非法转换: $packageName ${oldState.name} → ${newState.name}")
                return state
            }

            val transition = StateTransition(
                fromState = oldState, toState = newState, timestamp = now,
                trigger = trigger, durationMs = computeTransitionDuration(oldState, newState, state),
                success = true, errorMessage = null
            )

            state.lifecycleState = newState
            state.lastTransition = transition
            appendStateHistory(packageName, transition)
            totalTransitions.incrementAndGet()
            updateStateStatistics(state, oldState, newState, now)

            if (newState == AppLifecycleState.FOREGROUND) {
                currentForegroundApp = packageName
            } else if (oldState == AppLifecycleState.FOREGROUND &&
                newState != AppLifecycleState.FOREGROUND &&
                currentForegroundApp == packageName
            ) {
                currentForegroundApp = null
            }

            enforceMaxTrackedApps()
            Log.d(tag, "状态转换: $packageName ${oldState.name} → ${newState.name} (触发: $trigger)")
            return state
        }
    }

    /**
     * 获取指定应用的状态信息。
     *
     * @param packageName 应用包名
     * @return [AppState]，未追踪返回 null
     */
    fun getAppState(packageName: String): AppState? = appStates[packageName]

    /**
     * 状态转换 —— 显式执行一次状态转换，带合法性校验和统计更新。
     *
     * @param packageName 应用包名
     * @param targetState 目标状态
     * @param trigger     触发原因
     * @return [StateTransitionResult] 包含转换结果和详细信息
     */
    fun transitionState(
        packageName: String,
        targetState: AppLifecycleState,
        trigger: String = "AUTO"
    ): StateTransitionResult {
        if (packageName.isBlank()) {
            return StateTransitionResult(false, "包名为空", null)
        }

        synchronized(stateLock) {
            val state = appStates.computeIfAbsent(packageName) { pkg ->
                totalTrackedApps.incrementAndGet()
                AppState(packageName = pkg)
            }

            val oldState = state.lifecycleState
            val now = System.currentTimeMillis()

            if (!isValidTransition(oldState, targetState)) {
                totalInvalidTransitions.incrementAndGet()
                return StateTransitionResult(
                    success = false,
                    errorMessage = "非法转换: ${oldState.name} → ${targetState.name}",
                    transition = StateTransition(
                        fromState = oldState, toState = targetState, timestamp = now,
                        trigger = trigger, durationMs = 0L, success = false,
                        errorMessage = "非法转换: ${oldState.name} → ${targetState.name}"
                    )
                )
            }

            val transition = StateTransition(
                fromState = oldState, toState = targetState, timestamp = now,
                trigger = trigger, durationMs = computeTransitionDuration(oldState, targetState, state),
                success = true, errorMessage = null
            )

            state.lifecycleState = targetState
            state.lastTransition = transition
            appendStateHistory(packageName, transition)
            totalTransitions.incrementAndGet()
            updateStateStatistics(state, oldState, targetState, now)

            if (targetState == AppLifecycleState.FOREGROUND) {
                currentForegroundApp = packageName
            } else if (oldState == AppLifecycleState.FOREGROUND &&
                targetState != AppLifecycleState.FOREGROUND &&
                currentForegroundApp == packageName
            ) {
                currentForegroundApp = null
            }

            enforceMaxTrackedApps()
            return StateTransitionResult(success = true, errorMessage = null, transition = transition)
        }
    }

    // ============================================================
    // 启动优化
    // ============================================================

    /**
     * 优化启动决策 —— 根据应用当前状态和历史信息，生成最优的启动配置。
     *
     * 决策逻辑：FOREGROUND → 无需启动返回 null；BACKGROUND 且热启动可用 → WARM_START；
     * 预预热有效 → PRE_WARMED；提供深度链接 → DEEP_LINK；否则 STANDARD 冷启动。
     *
     * @param packageName    应用包名
     * @param targetActivity 目标 Activity 类名（可选）
     * @param deepLinkUri    深度链接 URI（可选）
     * @return 优化后的 [LaunchConfig]，应用已在 FOREGROUND 时返回 null
     */
    fun optimizeLaunch(
        packageName: String,
        targetActivity: String? = null,
        deepLinkUri: String? = null
    ): LaunchConfig? {
        if (packageName.isBlank()) return null

        totalOptimizeRequests.incrementAndGet()
        val state = appStates[packageName] ?: return createDefaultLaunchConfig(packageName, targetActivity, deepLinkUri)

        val currentState = state.lifecycleState
        val config = state.launchConfig
        val now = System.currentTimeMillis()

        if (currentState == AppLifecycleState.FOREGROUND) {
            Log.d(tag, "启动优化 [$packageName]: 已在 FOREGROUND，无需启动")
            return null
        }

        if ((currentState == AppLifecycleState.BACKGROUND || currentState == AppLifecycleState.SUSPENDED) && config.warmStartEnabled) {
            config.preferredLaunchMethod = LaunchMethod.WARM_START
            config.preferredActivity = targetActivity
            config.launchFlags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            config.launchTimeoutMs = if (currentState == AppLifecycleState.SUSPENDED) 8000L else 5000L
            config.lastUpdated = now
            Log.d(tag, "启动优化 [$packageName]: 推荐 WARM_START")
            return config.copy()
        }

        if (config.preWarmEnabled && isPreWarmValid(packageName, now)) {
            config.preferredLaunchMethod = LaunchMethod.PRE_WARMED
            config.preferredActivity = targetActivity
            config.launchFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            config.launchTimeoutMs = 10000L
            config.lastUpdated = now
            Log.d(tag, "启动优化 [$packageName]: 推荐 PRE_WARMED")
            return config.copy()
        }

        if (!deepLinkUri.isNullOrBlank()) {
            config.preferredLaunchMethod = LaunchMethod.DEEP_LINK
            config.deepLinkUri = deepLinkUri
            config.preferredActivity = targetActivity
            config.launchFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            config.launchTimeoutMs = 15000L
            config.lastUpdated = now
            Log.d(tag, "启动优化 [$packageName]: 推荐 DEEP_LINK ($deepLinkUri)")
            return config.copy()
        }

        config.preferredLaunchMethod = LaunchMethod.STANDARD
        config.preferredActivity = targetActivity
        config.launchFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        config.launchTimeoutMs = 15000L
        config.lastUpdated = now
        if (currentState == AppLifecycleState.CRASHED) {
            config.clearTaskOnNewIntent = true
            config.retryOnFailure = true
            config.maxRetryCount = 2
            Log.d(tag, "启动优化 [$packageName]: 推荐 STANDARD (崩溃后重启)")
        } else {
            Log.d(tag, "启动优化 [$packageName]: 推荐 STANDARD (冷启动)")
        }
        return config.copy()
    }

    // ============================================================
    // 应用健康监控
    // ============================================================

    /**
     * 获取指定应用的健康信息。
     *
     * @param packageName 应用包名
     * @return [AppHealth]，未追踪返回默认实例
     */
    fun getAppHealth(packageName: String): AppHealth {
        if (packageName.isBlank()) return AppHealth(status = HealthStatus.HEALTHY)
        return appHealthMap.getOrPut(packageName) { AppHealth(status = HealthStatus.HEALTHY) }
    }

    /**
     * 更新应用健康状态。
     *
     * @param packageName 应用包名
     * @param health      新的健康信息
     */
    fun updateAppHealth(packageName: String, health: AppHealth) {
        if (packageName.isBlank()) return
        appHealthMap[packageName] = health
        if (health.status == HealthStatus.CRASHED) {
            synchronized(stateLock) {
                val state = appStates[packageName]
                if (state != null && state.lifecycleState != AppLifecycleState.CRASHED) {
                    trackAppState(packageName, AppLifecycleState.CRASHED, "HEALTH_MONITOR")
                    state.crashCount++
                    state.lastCrashTime = System.currentTimeMillis()
                    state.lastCrashReason = "健康监控检测到崩溃"
                }
            }
        }
        Log.d(tag, "健康更新 [$packageName]: ${health.status.name}")
    }

    /**
     * 执行全面的应用健康检查：心跳超时检测、启动超时检测、内存压力检测。
     * 建议每 [HEALTH_CHECK_INTERVAL_MS] 调用一次。
     *
     * @return 健康检查结果列表
     */
    fun performHealthCheck(): List<HealthCheckResult> {
        totalHealthChecks.incrementAndGet()
        val results = mutableListOf<HealthCheckResult>()
        val now = System.currentTimeMillis()

        for ((packageName, state) in appStates) {
            val health = appHealthMap[packageName] ?: continue
            val issues = mutableListOf<String>()
            var newStatus = health.status

            if (health.lastHeartbeat > 0 && now - health.lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                if (state.lifecycleState == AppLifecycleState.FOREGROUND ||
                    state.lifecycleState == AppLifecycleState.BACKGROUND
                ) {
                    newStatus = HealthStatus.UNRESPONSIVE; issues.add("心跳超时")
                }
            }
            if (state.lifecycleState == AppLifecycleState.LAUNCHING &&
                state.lastLaunchTime > 0 && now - state.lastLaunchTime > LAUNCH_TRACKING_TIMEOUT_MS
            ) {
                newStatus = HealthStatus.UNRESPONSIVE; issues.add("启动超时")
            }
            if (state.resourceUsage.memoryKb > MEMORY_PRESSURE_THRESHOLD_KB) {
                newStatus = HealthStatus.LOW_MEMORY; issues.add("内存压力 (${state.resourceUsage.memoryKb}KB)")
            }

            if (newStatus != health.status) {
                appHealthMap[packageName] = health.copy(status = newStatus, evaluationTime = now)
            }
            results.add(HealthCheckResult(packageName, newStatus, issues, now))
        }

        Log.d(tag, "健康检查完成: ${results.size} 个应用, 异常: ${results.count { it.status != HealthStatus.HEALTHY }} 个")
        return results
    }

    /**
     * 记录应用的心跳信号。心跳超时会被健康检查标记为 UNRESPONSIVE。
     *
     * @param packageName    应用包名
     * @param responseTimeMs 本次响应时间（毫秒，可选）
     */
    fun recordHeartbeat(packageName: String, responseTimeMs: Long = -1L) {
        if (packageName.isBlank()) return
        appHealthMap.compute(packageName) { _, existing ->
            val current = existing ?: AppHealth()
            val newAvg = if (responseTimeMs > 0 && current.avgResponseTimeMs > 0) {
                (current.avgResponseTimeMs + responseTimeMs) / 2
            } else if (responseTimeMs > 0) {
                responseTimeMs
            } else {
                current.avgResponseTimeMs
            }
            current.copy(
                status = HealthStatus.HEALTHY, lastHeartbeat = System.currentTimeMillis(),
                responseTimeMs = responseTimeMs, avgResponseTimeMs = newAvg,
                evaluationTime = System.currentTimeMillis()
            )
        }
    }

    /**
     * 报告应用 ANR 事件。
     *
     * @param packageName 应用包名
     */
    fun reportAnr(packageName: String) {
        if (packageName.isBlank()) return
        val now = System.currentTimeMillis()
        appHealthMap.compute(packageName) { _, existing ->
            val current = existing ?: AppHealth()
            val newAnrs = (current.anrTimestamps + now).takeLast(MAX_ANR_RECORDS)
            current.copy(
                status = HealthStatus.UNRESPONSIVE, anrCount = current.anrCount + 1,
                anrTimestamps = newAnrs, evaluationTime = now
            )
        }
        Log.w(tag, "ANR 报告 [$packageName]: 第 ${getAppHealth(packageName).anrCount} 次")
    }

    /**
     * 报告应用崩溃事件。自动更新 [AppState] 的崩溃统计。
     *
     * @param packageName 应用包名
     * @param reason      崩溃原因描述
     */
    fun reportCrash(packageName: String, reason: String) {
        if (packageName.isBlank()) return
        val now = System.currentTimeMillis()

        appHealthMap.compute(packageName) { _, existing ->
            val current = existing ?: AppHealth()
            val newCrashes = (current.crashTimestamps + now).takeLast(MAX_CRASH_RECORDS)
            current.copy(
                status = HealthStatus.CRASHED, crashCount = current.crashCount + 1,
                crashTimestamps = newCrashes, evaluationTime = now
            )
        }

        synchronized(stateLock) {
            val state = appStates[packageName]
            if (state != null) {
                trackAppState(packageName, AppLifecycleState.CRASHED, "CRASH_REPORT")
                state.crashCount++; state.lastCrashTime = now; state.lastCrashReason = reason
            }
        }
        Log.w(tag, "崩溃报告 [$packageName]: $reason")
    }

    /**
     * 报告内存压力事件。
     *
     * @param packageName   应用包名
     * @param memoryKb      当前内存占用（KB）
     * @param pressureLevel 内存压力等级（0.0-1.0）
     */
    fun reportMemoryPressure(packageName: String, memoryKb: Long, pressureLevel: Float) {
        if (packageName.isBlank()) return
        appHealthMap.compute(packageName) { _, existing ->
            (existing ?: AppHealth()).copy(
                status = HealthStatus.LOW_MEMORY, memoryPressure = pressureLevel.coerceIn(0f, 1f),
                evaluationTime = System.currentTimeMillis()
            )
        }
        val state = appStates[packageName]
        if (state != null) { state.resourceUsage.memoryKb = memoryKb; state.resourceUsage.lastUpdated = System.currentTimeMillis() }
        Log.d(tag, "内存压力报告 [$packageName]: ${"%.0f".format(pressureLevel * 100)}% ($memoryKb KB)")
    }

    // ============================================================
    // 资源管理
    // ============================================================

    /**
     * 获取指定应用的资源使用情况。
     *
     * @param packageName 应用包名
     * @return [ResourceUsage]，未追踪的应用返回默认实例
     */
    fun getResourceUsage(packageName: String): ResourceUsage {
        return appStates[packageName]?.resourceUsage ?: ResourceUsage()
    }

    /**
     * 更新指定应用的资源使用情况。负值将被忽略以保护有效数据。
     *
     * @param packageName 应用包名
     * @param usage       新的资源使用数据
     */
    fun updateResourceUsage(packageName: String, usage: ResourceUsage) {
        if (packageName.isBlank()) return
        val state = appStates[packageName] ?: return

        synchronized(stateLock) {
            val current = state.resourceUsage
            if (usage.memoryKb >= 0) {
                current.memoryKb = usage.memoryKb
                if (usage.memoryKb > current.peakMemoryKb) current.peakMemoryKb = usage.memoryKb
            }
            if (usage.batteryPercent >= 0) current.batteryPercent = usage.batteryPercent
            if (usage.totalCpuTimeMs >= 0) current.totalCpuTimeMs = usage.totalCpuTimeMs
            if (usage.networkTxBytes >= 0) current.networkTxBytes = usage.networkTxBytes
            if (usage.networkRxBytes >= 0) current.networkRxBytes = usage.networkRxBytes
            if (usage.diskReadBytes >= 0) current.diskReadBytes = usage.diskReadBytes
            if (usage.diskWriteBytes >= 0) current.diskWriteBytes = usage.diskWriteBytes
            current.lastUpdated = System.currentTimeMillis()

            val history = resourceHistory.computeIfAbsent(packageName) { ConcurrentLinkedDeque() }
            history.addFirst(current.copy())
            while (history.size > MAX_HISTORY_PER_APP) history.pollLast()
        }
    }

    /**
     * 获取应用的资源使用历史快照列表。
     *
     * @param packageName 应用包名
     * @param maxCount    最大返回条数，默认 10
     * @return 资源使用历史列表（按时间倒序）
     */
    fun getResourceHistory(packageName: String, maxCount: Int = 10): List<ResourceUsage> {
        return resourceHistory[packageName]?.toList()?.take(maxCount) ?: emptyList()
    }

    // ============================================================
    // 状态持久化
    // ============================================================

    /**
     * 持久化状态 —— 将所有应用状态信息保存到本地 JSON 文件。
     *
     * @return true 表示持久化成功
     */
    fun persistState(): Boolean {
        synchronized(persistLock) {
            try {
                if (persistDir.isBlank()) { Log.w(tag, "持久化失败: 目录未设置"); return false }
                val dir = File(persistDir); if (!dir.exists()) dir.mkdirs()
                val persistFile = File(dir, PERSIST_FILE_NAME)
                if (persistFile.exists() && persistFile.length() > MAX_PERSIST_FILE_SIZE) {
                    persistFile.delete()
                }

                val now = System.currentTimeMillis()
                val records = appStates.values.map { state ->
                    AppStateRecord(
                        packageName = state.packageName,
                        lifecycleState = state.lifecycleState.name,
                        healthStatus = state.healthStatus.name,
                        launchCount = state.launchCount, crashCount = state.crashCount,
                        foregroundTime = state.foregroundTime, backgroundTime = state.backgroundTime,
                        lastLaunchTime = state.lastLaunchTime, lastForegroundTime = state.lastForegroundTime,
                        lastBackgroundTime = state.lastBackgroundTime, lastCrashTime = state.lastCrashTime,
                        lastCrashReason = state.lastCrashReason, memoryKb = state.resourceUsage.memoryKb,
                        tags = state.tags.toList(), recordTimestamp = now
                    )
                }
                persistFile.writeText(buildPersistJson(records, now))
                totalPersistCount.incrementAndGet()
                Log.d(tag, "状态持久化完成: ${records.size} 个应用 (第 ${totalPersistCount.get()} 次)")
                return true
            } catch (e: Exception) {
                Log.e(tag, "状态持久化失败: ${e.message}"); return false
            }
        }
    }

    /**
     * 恢复状态 —— 从本地 JSON 文件恢复之前持久化的应用状态信息。
     *
     * @return true 表示至少恢复了一个应用
     */
    fun restoreState(): Boolean {
        synchronized(persistLock) {
            try {
                if (persistDir.isBlank()) { Log.w(tag, "状态恢复失败: 目录未设置"); return false }
                val persistFile = File(persistDir, PERSIST_FILE_NAME)
                if (!persistFile.exists()) { Log.d(tag, "状态恢复: 持久化文件不存在"); return false }

                val json = persistFile.readText()
                if (json.isBlank()) { Log.w(tag, "状态恢复: 文件为空"); return false }

                val records = parsePersistJson(json)
                if (records.isEmpty()) { Log.w(tag, "状态恢复: 解析结果为空"); return false }

                var restoredCount = 0
                synchronized(stateLock) {
                    for (record in records) {
                        if (record.packageName.isBlank()) continue
                        val lifecycleState = runCatching { AppLifecycleState.valueOf(record.lifecycleState) }
                            .getOrDefault(AppLifecycleState.UNKNOWN)
                        val healthStatus = runCatching { HealthStatus.valueOf(record.healthStatus) }
                            .getOrDefault(HealthStatus.HEALTHY)

                        val state = AppState(
                            packageName = record.packageName, lifecycleState = lifecycleState,
                            healthStatus = healthStatus,
                            resourceUsage = ResourceUsage(memoryKb = record.memoryKb),
                            launchCount = record.launchCount, crashCount = record.crashCount,
                            foregroundTime = record.foregroundTime, backgroundTime = record.backgroundTime,
                            lastLaunchTime = record.lastLaunchTime, lastForegroundTime = record.lastForegroundTime,
                            lastBackgroundTime = record.lastBackgroundTime, lastCrashTime = record.lastCrashTime,
                            lastCrashReason = record.lastCrashReason,
                            tags = ConcurrentHashMap.newKeySet<String>().also { it.addAll(record.tags) }
                        )
                        if (lifecycleState == AppLifecycleState.FOREGROUND) currentForegroundApp = record.packageName
                        appStates[record.packageName] = state
                        restoredCount++
                    }
                }
                totalRestoreCount.incrementAndGet()
                Log.d(tag, "状态恢复完成: $restoredCount 个应用 (第 ${totalRestoreCount.get()} 次)")
                return restoredCount > 0
            } catch (e: Exception) {
                Log.e(tag, "状态恢复失败: ${e.message}"); return false
            }
        }
    }

    // ============================================================
    // 查询方法
    // ============================================================

    /** 获取当前前台应用包名。 */
    fun getForegroundApp(): String? = currentForegroundApp

    /** 获取所有处于指定生命周期状态的应用列表。 */
    fun getAppsInState(state: AppLifecycleState): List<String> {
        return appStates.values.filter { it.lifecycleState == state }.map { it.packageName }
    }

    /**
     * 获取指定应用的状态历史记录。
     *
     * @param packageName 应用包名
     * @param maxCount    最大返回条数，默认 50
     * @return 状态转换历史列表（按时间倒序）
     */
    fun getStateHistory(packageName: String, maxCount: Int = 50): List<StateTransition> {
        return stateHistory[packageName]?.toList()?.take(maxCount) ?: emptyList()
    }

    /** 获取所有被追踪的应用包名列表。 */
    fun getTrackedApps(): List<String> = appStates.keys.toList()

    /** 获取被追踪的应用数量。 */
    fun getTrackedAppCount(): Int = appStates.size

    /** 检查应用是否被追踪。 */
    fun isAppTracked(packageName: String): Boolean = appStates.containsKey(packageName)

    /** 获取应用的启动配置，未追踪返回默认配置。 */
    fun getLaunchConfig(packageName: String): LaunchConfig {
        return appStates[packageName]?.launchConfig ?: LaunchConfig()
    }

    /** 更新应用的启动配置。 */
    fun updateLaunchConfig(packageName: String, config: LaunchConfig) {
        val state = appStates[packageName] ?: return
        state.launchConfig = config.copy(lastUpdated = System.currentTimeMillis())
    }

    // ============================================================
    // 应用标记管理
    // ============================================================

    /** 固定应用（固定应用不会被 LRU 淘汰）。 */
    fun setAppPinned(packageName: String, pinned: Boolean) {
        appStates[packageName]?.isPinned = pinned
        Log.d(tag, "应用固定 [$packageName]: $pinned")
    }

    /** 为应用添加自定义标签。 */
    fun addAppTag(packageName: String, tag: String) {
        appStates[packageName]?.tags?.add(tag)
    }

    /** 移除应用的自定义标签。 */
    fun removeAppTag(packageName: String, tag: String) {
        appStates[packageName]?.tags?.remove(tag)
    }

    /** 获取应用的所有标签。 */
    fun getAppTags(packageName: String): Set<String> {
        return appStates[packageName]?.tags?.toSet() ?: emptySet()
    }

    /** 按标签查找应用。 */
    fun findAppsByTag(tag: String): List<String> {
        return appStates.values.filter { tag in it.tags }.map { it.packageName }
    }

    // ============================================================
    // 维护方法
    // ============================================================

    /** 清空所有应用状态数据与统计计数（持久化文件不会被删除）。 */
    fun clear() {
        synchronized(stateLock) {
            appStates.clear(); appHealthMap.clear(); stateHistory.clear(); resourceHistory.clear()
            currentForegroundApp = null
            totalTrackedApps.set(0); totalTransitions.set(0); totalInvalidTransitions.set(0)
            totalOptimizeRequests.set(0); totalHealthChecks.set(0)
        }
        Log.d(tag, "已清空所有应用状态数据")
    }

    /** 删除持久化文件。 */
    fun deletePersistFile(): Boolean {
        val f = File(persistDir, PERSIST_FILE_NAME); return !f.exists() || f.delete()
    }

    /**
     * 移除指定应用的状态信息（用于已卸载的应用）。
     *
     * @return true 表示移除成功
     */
    fun removeApp(packageName: String): Boolean {
        synchronized(stateLock) {
            val removed = appStates.remove(packageName) != null
            appHealthMap.remove(packageName); stateHistory.remove(packageName); resourceHistory.remove(packageName)
            if (currentForegroundApp == packageName) currentForegroundApp = null
            if (removed) Log.d(tag, "已移除应用状态: $packageName")
            return removed
        }
    }

    /**
     * 获取管理器统计摘要。
     *
     * @return 多行摘要文本
     */
    fun getSummary(): String {
        val foreground = currentForegroundApp ?: "无"
        val stateCounts = AppLifecycleState.entries.associateWith { state ->
            appStates.values.count { it.lifecycleState == state }
        }
        val healthCounts = HealthStatus.entries.associateWith { status ->
            appHealthMap.values.count { it.status == status }
        }
        return buildString {
            appendLine("===== AppStateManager 统计摘要 =====")
            appendLine("追踪应用: ${appStates.size} / $MAX_TRACKED_APPS")
            appendLine("前台应用: $foreground")
            appendLine("状态分布:"); stateCounts.forEach { (s, c) -> if (c > 0) appendLine("  ${s.name}: $c") }
            appendLine("健康分布:"); healthCounts.forEach { (s, c) -> if (c > 0) appendLine("  ${s.name}: $c") }
            appendLine("总转换次数: ${totalTransitions.get()}")
            appendLine("非法转换拒绝: ${totalInvalidTransitions.get()}")
            appendLine("启动优化请求: ${totalOptimizeRequests.get()}")
            appendLine("健康检查次数: ${totalHealthChecks.get()}")
            appendLine("持久化次数: ${totalPersistCount.get()}")
            appendLine("恢复次数: ${totalRestoreCount.get()}")
            append("状态历史: ${stateHistory.values.sumOf { it.size }} 条")
        }
    }

    // ============================================================
    // 内部工具方法
    // ============================================================

    /**
     * 判断状态转换是否合法。
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true 表示合法
     */
    private fun isValidTransition(from: AppLifecycleState, to: AppLifecycleState): Boolean {
        if (from == to) return true
        return VALID_TRANSITIONS[from]?.contains(to) == true
    }

    /**
     * 计算有意义的转换耗时（如启动耗时、恢复耗时）。
     */
    private fun computeTransitionDuration(from: AppLifecycleState, to: AppLifecycleState, state: AppState): Long {
        val now = System.currentTimeMillis()
        return when {
            from == AppLifecycleState.LAUNCHING && to == AppLifecycleState.FOREGROUND -> now - state.lastLaunchTime
            from == AppLifecycleState.LAUNCHING && to == AppLifecycleState.BACKGROUND -> now - state.lastLaunchTime
            from == AppLifecycleState.FOREGROUND && to == AppLifecycleState.BACKGROUND -> now - state.lastForegroundTime
            from == AppLifecycleState.BACKGROUND && to == AppLifecycleState.FOREGROUND -> now - state.lastBackgroundTime
            else -> 0L
        }
    }

    /**
     * 根据状态转换更新统计信息（前台时间、后台时间、启动/崩溃次数等）。
     */
    private fun updateStateStatistics(state: AppState, oldState: AppLifecycleState, newState: AppLifecycleState, now: Long) {
        when (newState) {
            AppLifecycleState.LAUNCHING -> { state.launchCount++; state.lastLaunchTime = now }
            AppLifecycleState.FOREGROUND -> {
                if (state.lastBackgroundTime > 0 && oldState == AppLifecycleState.BACKGROUND) {
                    state.backgroundTime += now - state.lastBackgroundTime
                    state.resourceUsage.backgroundTimeMs = state.backgroundTime
                }
                state.lastForegroundTime = now
            }
            AppLifecycleState.BACKGROUND -> {
                if (state.lastForegroundTime > 0 && oldState == AppLifecycleState.FOREGROUND) {
                    state.foregroundTime += now - state.lastForegroundTime
                    state.resourceUsage.foregroundTimeMs = state.foregroundTime
                }
                state.lastBackgroundTime = now
            }
            AppLifecycleState.CRASHED -> { state.crashCount++; state.lastCrashTime = now }
            else -> {}
        }
    }

    /**
     * 将状态转换记录追加到历史列表中，维护历史和全局上限。
     */
    private fun appendStateHistory(packageName: String, transition: StateTransition) {
        val history = stateHistory.computeIfAbsent(packageName) { ConcurrentLinkedDeque() }
        history.addFirst(transition)
        while (history.size > MAX_HISTORY_PER_APP) history.pollLast()

        var total = stateHistory.values.sumOf { it.size }
        while (total > MAX_TOTAL_HISTORY) {
            var evicted = false
            for (entry in stateHistory.entries) {
                if (entry.value.size > 1) { entry.value.pollLast(); total--; evicted = true; break }
            }
            if (!evicted) break
        }
    }

    /**
     * 维护追踪应用数量上限，超出时按 LRU 淘汰。
     * 淘汰策略：固定应用不移除 > CLOSED（冷却期已过）> CLOSED > UNKNOWN > 其他。
     */
    private fun enforceMaxTrackedApps() {
        while (appStates.size > MAX_TRACKED_APPS) {
            val now = System.currentTimeMillis()
            val candidate = appStates.values
                .filter { !it.isPinned }
                .minWithOrNull(compareBy<AppState> { state ->
                    val lastTransition = state.lastTransition
                    when {
                        state.lifecycleState == AppLifecycleState.CLOSED &&
                            lastTransition != null &&
                            now - lastTransition.timestamp > STATE_COOLDOWN_MS -> 0
                        state.lifecycleState == AppLifecycleState.CLOSED -> 1
                        state.lifecycleState == AppLifecycleState.UNKNOWN -> 2
                        else -> 3
                    }
                }.thenBy { it.lastTransition?.timestamp ?: 0L })

            if (candidate != null) {
                val pkg = candidate.packageName
                appStates.remove(pkg); appHealthMap.remove(pkg)
                stateHistory.remove(pkg); resourceHistory.remove(pkg)
                if (currentForegroundApp == pkg) currentForegroundApp = null
                Log.d(tag, "LRU 淘汰应用: $pkg")
            } else break
        }
    }

    /** 检查应用的预预热是否仍然有效。 */
    private fun isPreWarmValid(packageName: String, now: Long): Boolean {
        val state = appStates[packageName] ?: return false
        return state.lastLaunchTime > 0 && (now - state.lastLaunchTime) < PRE_WARM_ADVANCE_TIME_MS
    }

    /** 创建默认启动配置。 */
    private fun createDefaultLaunchConfig(packageName: String, targetActivity: String?, deepLinkUri: String?): LaunchConfig {
        val config = LaunchConfig(
            preferredLaunchMethod = if (!deepLinkUri.isNullOrBlank()) LaunchMethod.DEEP_LINK else LaunchMethod.STANDARD,
            deepLinkUri = deepLinkUri, preferredActivity = targetActivity,
            launchFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            launchTimeoutMs = 15000L, retryOnFailure = true, maxRetryCount = 3,
            warmStartEnabled = true, preWarmEnabled = false, lastUpdated = System.currentTimeMillis()
        )
        if (!deepLinkUri.isNullOrBlank()) {
            config.launchFlags = config.launchFlags or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return config
    }

    // ============================================================
    // JSON 序列化/反序列化（手动实现，避免 kotlinx.serialization 依赖）
    // ============================================================

    /** 构建持久化 JSON 字符串。 */
    private fun buildPersistJson(records: List<AppStateRecord>, timestamp: Long): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"version\": 1,")
        sb.appendLine("  \"timestamp\": $timestamp,")
        sb.append("  \"foreground_app\": ")
        sb.append(if (currentForegroundApp != null) "\"${escapeJson(currentForegroundApp!!)}\"" else "null")
        sb.appendLine(",")
        sb.appendLine("  \"apps\": [")

        for ((index, record) in records.withIndex()) {
            sb.appendLine("    {")
            sb.appendLine("      \"package_name\": \"${escapeJson(record.packageName)}\",")
            sb.appendLine("      \"lifecycle_state\": \"${escapeJson(record.lifecycleState)}\",")
            sb.appendLine("      \"health_status\": \"${escapeJson(record.healthStatus)}\",")
            sb.appendLine("      \"launch_count\": ${record.launchCount},")
            sb.appendLine("      \"crash_count\": ${record.crashCount},")
            sb.appendLine("      \"foreground_time\": ${record.foregroundTime},")
            sb.appendLine("      \"background_time\": ${record.backgroundTime},")
            sb.appendLine("      \"last_launch_time\": ${record.lastLaunchTime},")
            sb.appendLine("      \"last_foreground_time\": ${record.lastForegroundTime},")
            sb.appendLine("      \"last_background_time\": ${record.lastBackgroundTime},")
            sb.appendLine("      \"last_crash_time\": ${record.lastCrashTime},")
            sb.append("      \"last_crash_reason\": ")
            sb.append(if (record.lastCrashReason != null) "\"${escapeJson(record.lastCrashReason!!)}\"" else "null")
            sb.appendLine(",")
            sb.appendLine("      \"memory_kb\": ${record.memoryKb},")
            sb.append("      \"tags\": [")
            sb.append(record.tags.joinToString(", ") { "\"${escapeJson(it)}\"" })
            sb.appendLine("],")
            sb.appendLine("      \"record_timestamp\": ${record.recordTimestamp}")
            sb.append("    }")
            if (index < records.size - 1) sb.append(",")
            sb.appendLine()
        }

        sb.appendLine("  ]")
        sb.append("}")
        return sb.toString()
    }

    /** 解析持久化 JSON 字符串为应用状态记录列表。 */
    private fun parsePersistJson(json: String): List<AppStateRecord> {
        val records = mutableListOf<AppStateRecord>()
        try {
            val appsKey = "\"apps\""
            val appsIdx = json.indexOf(appsKey)
            if (appsIdx < 0) return records
            val arrStart = json.indexOf('[', appsIdx + appsKey.length)
            if (arrStart < 0) return records
            val arrEnd = findMatchingBracket(json, arrStart)
            if (arrEnd < 0) return records

            val content = json.substring(arrStart + 1, arrEnd)
            var depth = 0; var objStart = -1
            for (i in content.indices) {
                when (content[i]) {
                    '{' -> { if (depth == 0) objStart = i; depth++ }
                    '}' -> { depth--; if (depth == 0 && objStart >= 0) {
                        parseAppStateRecord(content.substring(objStart, i + 1))?.let { records.add(it) }
                        objStart = -1
                    }}
                }
            }
        } catch (e: Exception) { Log.e(tag, "解析 JSON 失败: ${e.message}") }
        return records
    }

    /** 从 JSON 对象字符串中解析单个 [AppStateRecord]。 */
    private fun parseAppStateRecord(json: String): AppStateRecord? {
        return try {
            val extract = { key: String -> extractJsonValue(json, key) }
            val pkg = extract("package_name") ?: return null
            AppStateRecord(
                packageName = pkg,
                lifecycleState = extract("lifecycle_state") ?: "UNKNOWN",
                healthStatus = extract("health_status") ?: "HEALTHY",
                launchCount = extract("launch_count")?.toIntOrNull() ?: 0,
                crashCount = extract("crash_count")?.toIntOrNull() ?: 0,
                foregroundTime = extract("foreground_time")?.toLongOrNull() ?: 0L,
                backgroundTime = extract("background_time")?.toLongOrNull() ?: 0L,
                lastLaunchTime = extract("last_launch_time")?.toLongOrNull() ?: 0L,
                lastForegroundTime = extract("last_foreground_time")?.toLongOrNull() ?: 0L,
                lastBackgroundTime = extract("last_background_time")?.toLongOrNull() ?: 0L,
                lastCrashTime = extract("last_crash_time")?.toLongOrNull() ?: 0L,
                lastCrashReason = extract("last_crash_reason"),
                memoryKb = extract("memory_kb")?.toLongOrNull() ?: -1L,
                tags = extractJsonArray(json, "tags"),
                recordTimestamp = extract("record_timestamp")?.toLongOrNull() ?: System.currentTimeMillis()
            )
        } catch (e: Exception) { Log.e(tag, "解析记录失败: ${e.message}"); null }
    }

    /** 从 JSON 中提取指定键的值。 */
    private fun extractJsonValue(json: String, key: String): String? {
        val searchKey = "\"$key\""
        val ki = json.indexOf(searchKey)
        if (ki < 0) return null
        val ci = json.indexOf(':', ki + searchKey.length)
        if (ci < 0) return null
        val trimmed = json.substring(ci + 1).trimStart()
        return when {
            trimmed.startsWith('"') -> {
                val quotePos = trimmed.indexOf('"')
                val end = findStringEnd(json, ci + 1 + quotePos)
                if (end > 0) json.substring(ci + 2 + quotePos, end)
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("\\n", "\n").replace("\\t", "\t")
                else null
            }
            trimmed.startsWith("null") -> null
            trimmed.startsWith("true") -> "true"
            trimmed.startsWith("false") -> "false"
            else -> {
                val end = trimmed.indexOfFirst { it == ',' || it == '}' || it == ']' || it == '\n' }
                if (end > 0) trimmed.substring(0, end).trim() else null
            }
        }
    }

    /** 从 JSON 中提取指定键的字符串数组值。 */
    private fun extractJsonArray(json: String, key: String): List<String> {
        val searchKey = "\"$key\""
        val ki = json.indexOf(searchKey)
        if (ki < 0) return emptyList()
        val ci = json.indexOf(':', ki + searchKey.length)
        if (ci < 0) return emptyList()
        val arrStart = json.indexOf('[', ci)
        if (arrStart < 0) return emptyList()
        val arrEnd = findMatchingBracket(json, arrStart)
        if (arrEnd < 0) return emptyList()
        val content = json.substring(arrStart + 1, arrEnd)
        if (content.isBlank()) return emptyList()

        val items = mutableListOf<String>()
        var i = 0
        while (i < content.length) {
            if (content[i] == '"') {
                val end = findStringEnd(content, i)
                if (end > i) {
                    items.add(content.substring(i + 1, end).replace("\\\"", "\"").replace("\\\\", "\\"))
                    i = end + 1
                } else i++
            } else i++
        }
        return items
    }

    /** 查找匹配的闭合括号位置（支持 [] 和 {}）。 */
    private fun findMatchingBracket(json: String, openIndex: Int): Int {
        val openChar = json[openIndex]
        val closeChar = when (openChar) { '[' -> ']'; '{' -> '}'; else -> return -1 }
        var depth = 1; var i = openIndex + 1; var inStr = false
        while (i < json.length && depth > 0) {
            val ch = json[i]
            if (inStr) { if (ch == '"' && json[i - 1] != '\\') inStr = false }
            else { when (ch) { '"' -> inStr = true; openChar -> depth++; closeChar -> depth-- } }
            i++
        }
        return if (depth == 0) i - 1 else -1
    }

    /** 查找 JSON 字符串值的结束引号位置（正确处理转义）。 */
    private fun findStringEnd(json: String, quoteIndex: Int): Int {
        var i = quoteIndex + 1
        while (i < json.length) { when (json[i]) { '\\' -> i++; '"' -> return i }; i++ }
        return -1
    }

    /** 转义 JSON 字符串中的特殊字符。 */
    private fun escapeJson(str: String): String = str
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    /** 格式化时间戳为可读字符串。 */
    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0) return "N/A"
        return timeFormatter.format(Date(timestamp))
    }

    /** 格式化持续时间为可读字符串。 */
    private fun formatDuration(ms: Long): String {
        if (ms < 0) return "0秒"
        val seconds = ms / 1000
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds < 3600 -> "${seconds / 60}分钟"
            seconds < 86400 -> "${seconds / 3600}小时"
            else -> "${seconds / 86400}天"
        }
    }
}

// ============================================================
// 外部数据类定义
// ============================================================

/**
 * 状态转换结果 —— 由 [AppStateManager.transitionState] 返回。
 *
 * @property success      转换是否成功
 * @property errorMessage 转换失败时的错误信息，成功时为 null
 * @property transition   转换记录，失败时也可能包含信息
 */
data class StateTransitionResult(
    val success: Boolean,
    val errorMessage: String?,
    val transition: AppStateManager.StateTransition?
)

/**
 * 健康检查结果 —— 由 [AppStateManager.performHealthCheck] 返回。
 *
 * @property packageName 应用包名
 * @property status      健康状态
 * @property issues      检测到的问题列表
 * @property checkTime   检查时间戳
 */
data class HealthCheckResult(
    val packageName: String,
    val status: AppStateManager.HealthStatus,
    val issues: List<String>,
    val checkTime: Long
)