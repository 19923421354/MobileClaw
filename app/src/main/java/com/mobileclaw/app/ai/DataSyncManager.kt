package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 数据同步管理器 —— 管理应用内部状态、云存储和备份恢复之间的数据同步。
 *
 * ============================================================
 * 设计理念
 * ============================================================
 *
 * 在手机自动化操控场景中，AI 需要维护大量状态数据：用户画像、偏好设置、学习到的操作模式、
 * 自动化规则、工作流定义等。这些数据需要在不同存储层之间保持一致性：
 * - 本地内存状态：当前会话的实时数据，读写速度最快
 * - 本地持久化存储：应用关闭后数据不丢失，支持快速恢复
 * - 云端存储：跨设备数据同步，提供灾难恢复能力
 *
 * 本管理器封装了数据同步的完整生命周期，包括：
 * 1. 本地备份与恢复：将应用学习到的状态序列化到本地文件系统
 * 2. 云同步准备：将数据序列化为标准 JSON 格式，供上层云同步模块使用
 * 3. 冲突检测与解决：支持 last-write-wins、merge、keep-both、manual 四种策略
 * 4. 增量同步：基于修改时间戳和变更哈希，只同步变更数据
 * 5. 定时同步调度：支持周期性后台同步，可配置时间间隔
 * 6. 数据版本管理：维护数据版本号，保证前后向兼容
 * 7. 同步统计：追踪每次同步的详细信息，便于监控和调试
 *
 * ============================================================
 * 数据分类体系
 * ============================================================
 *
 * ```
 * 数据类别              │ 备份策略     │ 冲突策略        │ 同步优先级
 * ─────────────────────┼──────────────┼────────────────┼──────────
 * USER_PROFILE         │ 全量         │ MERGE          │ 最高
 * PREFERENCES          │ 全量         │ LAST_WRITE_WINS│ 高
 * LEARNED_RULES        │ 增量         │ MERGE          │ 中
 * CACHES               │ 不持久化     │ LAST_WRITE_WINS│ 低
 * WORKFLOWS            │ 全量         │ KEEP_BOTH      │ 高
 * AUTOMATION_CHAINS    │ 全量         │ KEEP_BOTH      │ 高
 * AUDIT_LOGS           │ 增量         │ MERGE          │ 低
 * ALL                  │ 全量         │ 自定义         │ 视情况
 * ```
 *
 * ============================================================
 * 同步状态机
 * ============================================================
 *
 * ```
 *   IDLE ──→ SYNCING ──→ SUCCESS
 *              │             │
 *              ▼             │
 *            FAILED ────────→ IDLE
 *              │
 *              ▼
 *           CONFLICT ──→ IDLE (手动解决后)
 * ```
 *
 * ============================================================
 * 线程安全设计
 * ============================================================
 *
 * - 全部存储使用 [ConcurrentHashMap] 保证读写线程安全
 * - 复合操作（如检查-更新）使用 [ReentrantReadWriteLock] 保护
 * - 统计计数器使用 [AtomicInteger] / [AtomicLong] 保证原子性
 * - 定时同步使用 [ScheduledExecutorService] 管理
 * - 变更追踪使用 [ConcurrentLinkedDeque] 记录变更历史
 *
 * 使用方式：
 * ```
 * val syncManager = DataSyncManager()
 * syncManager.initialize("/data/data/com.mobileclaw.app/files/sync")
 *
 * // 备份状态到本地
 * syncManager.backupState("user_profile_001", DataCategory.USER_PROFILE, profileData)
 *
 * // 从本地恢复状态
 * val restored = syncManager.restoreState("user_profile_001", DataCategory.USER_PROFILE)
 *
 * // 准备云同步数据
 * val syncPayload = syncManager.prepareSyncData("user_profile_001", DataCategory.USER_PROFILE)
 *
 * // 解决同步冲突
 * val resolved = syncManager.resolveConflict(conflict, ConflictStrategy.MERGE)
 *
 * // 执行增量同步
 * val changes = syncManager.incrementalSync("user_profile_001", DataCategory.USER_PROFILE, remoteData)
 *
 * // 设置定时同步
 * syncManager.scheduleSync(SyncSchedule(daily, intervalHours = 6))
 * ```
 *
 * @author MobileClaw Team
 * @since 2024
 */
class DataSyncManager {

    // ============================================================
    // 日志标签
    // ============================================================

    private val tag = "DataSyncManager"

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 数据类别 —— 定义需要同步的数据分类。
     *
     * 每个类别拥有独立的存储路径、同步策略和冲突处理规则。
     * 类别用于：数据隔离、按需同步、精细化的冲突解决。
     */
    enum class DataCategory {
        /** 用户画像 —— 用户基本信息、行为偏好、使用习惯等。 */
        USER_PROFILE,
        /** 偏好设置 —— 应用配置、UI 偏好、通知设置等。 */
        PREFERENCES,
        /** 学习规则 —— 从用户操作中自动学习到的模式和规则。 */
        LEARNED_RULES,
        /** 缓存数据 —— 临时缓存、中间计算结果，可重新生成。 */
        CACHES,
        /** 工作流 —— 用户定义或系统推荐的工作流定义。 */
        WORKFLOWS,
        /** 自动化链 —— 由多个自动化步骤组成的执行链。 */
        AUTOMATION_CHAINS,
        /** 审计日志 —— 操作记录、错误日志、性能数据。 */
        AUDIT_LOGS,
        /** 全部类别 —— 用于全量同步或备份操作。 */
        ALL
    }

    /**
     * 同步方向 —— 定义数据传输的方向。
     */
    enum class SyncDirection {
        /** 上传：本地数据同步到云端。 */
        UPLOAD,
        /** 下载：云端数据同步到本地。 */
        DOWNLOAD,
        /** 双向同步：本地和云端数据互相合并。 */
        BIDIRECTIONAL
    }

    /**
     * 冲突策略 —— 定义本地和远程数据冲突时的解决方式。
     */
    enum class ConflictStrategy {
        /** 最后写入者获胜：以最新修改时间戳的数据为准。 */
        LAST_WRITE_WINS,
        /**
         * 合并：尝试将两份数据合并。
         * - 对于集合类型：合并两个集合
         * - 对于键值对：以较新版本的键值覆盖
         * - 对于不可合并类型：退化为 LAST_WRITE_WINS
         */
        MERGE,
        /**
         * 两者都保留：将冲突的两份数据都保留。
         * 本地版本和远程版本分别保存，通常用于工作流和自动化链。
         */
        KEEP_BOTH,
        /**
         * 手动解决：标记冲突，等待用户手动选择。
         * 同步状态变为 CONFLICT，直到用户调用 resolveConflict 解决。
         */
        MANUAL
    }

    /**
     * 同步状态 —— 标识当前同步操作的执行状态。
     */
    enum class SyncStatus {
        /** 空闲：没有正在执行的同步操作。 */
        IDLE,
        /** 同步中：正在执行数据同步操作。 */
        SYNCING,
        /** 成功：同步操作已成功完成。 */
        SUCCESS,
        /** 失败：同步操作执行失败。 */
        FAILED,
        /** 冲突：同步过程中检测到数据冲突，等待解决。 */
        CONFLICT
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 同步数据 —— 单个数据项的同步单元。
     *
     * 包含数据内容、元数据、版本信息等，是同步操作的最小单位。
     *
     * @property id           数据唯一标识符
     * @property category     数据类别
     * @property key          数据键名，在同一类别中唯一
     * @property value        JSON 序列化的数据值
     * @property version      数据版本号（单调递增）
     * @property hash         数据内容的 SHA-256 哈希值
     * @property lastModified 最后修改时间戳（毫秒）
     * @property size         数据大小（字节）
     * @property direction    同步方向
     * @property metadata     附加元数据（键值对）
     * @property description  数据描述说明
     */
    data class SyncData(
        val id: String,
        val category: DataCategory,
        val key: String,
        val value: JsonElement,
        val version: Long = 1L,
        val hash: String = "",
        val lastModified: Long = System.currentTimeMillis(),
        val size: Long = 0L,
        val direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        val metadata: MutableMap<String, String> = ConcurrentHashMap(),
        val description: String = ""
    )

    /**
     * 同步清单 —— 一次同步操作的完整清单。
     *
     * 包含参与同步的所有数据项、时间戳、同步方向等信息。
     *
     * @property manifestId      清单唯一标识符
     * @property deviceId        设备标识符
     * @property dataCategory    数据类别
     * @property syncDirection   同步方向
     * @property dataItems       参与同步的数据项列表
     * @property totalItems      总数据项数
     * @property totalSize       总数据大小（字节）
     * @property syncTimestamp   同步发起时间戳
     * @property manifestVersion 清单格式版本号
     * @property sourceVersion   源端数据版本
     * @property targetVersion   目标端数据版本
     * @property checksum        清单校验和
     * @property isFullSync      是否为全量同步
     */
    data class SyncManifest(
        val manifestId: String = UUID.randomUUID().toString(),
        val deviceId: String = "",
        val dataCategory: DataCategory = DataCategory.ALL,
        val syncDirection: SyncDirection = SyncDirection.BIDIRECTIONAL,
        val dataItems: List<SyncData> = emptyList(),
        val totalItems: Int = 0,
        val totalSize: Long = 0L,
        val syncTimestamp: Long = System.currentTimeMillis(),
        val manifestVersion: Int = 1,
        val sourceVersion: Long = 0L,
        val targetVersion: Long = 0L,
        val checksum: String = "",
        val isFullSync: Boolean = true
    )

    /**
     * 同步冲突 —— 记录本地和远程数据之间的冲突详情。
     *
     * @property conflictId        冲突唯一标识符
     * @property dataId            引发冲突的数据项 ID
     * @property category          数据类别
     * @property key               数据键名
     * @property localValue        本地数据值
     * @property remoteValue       远程数据值
     * @property localVersion      本地数据版本号
     * @property remoteVersion     远程数据版本号
     * @property localModified     本地数据修改时间戳
     * @property remoteModified    远程数据修改时间戳
     * @property suggestedStrategy 建议的冲突解决策略
     * @property resolvedStrategy  实际使用的解决策略（解决后填充）
     * @property resolvedValue     解决后的数据值（解决后填充）
     * @property isResolved        是否已解决
     * @property resolvedTimestamp 解决时间戳
     * @property description       冲突描述
     */
    data class SyncConflict(
        val conflictId: String = UUID.randomUUID().toString(),
        val dataId: String,
        val category: DataCategory,
        val key: String,
        val localValue: JsonElement,
        val remoteValue: JsonElement,
        val localVersion: Long,
        val remoteVersion: Long,
        val localModified: Long,
        val remoteModified: Long,
        val suggestedStrategy: ConflictStrategy = ConflictStrategy.MERGE,
        var resolvedStrategy: ConflictStrategy? = null,
        var resolvedValue: JsonElement? = null,
        var isResolved: Boolean = false,
        var resolvedTimestamp: Long = 0L,
        val description: String = ""
    )

    /**
     * 同步调度配置 —— 定义定时同步的调度参数。
     *
     * @property enabled             是否启用定时同步
     * @property intervalHours       同步间隔（小时）
     * @property intervalMinutes     同步间隔（分钟，与 intervalHours 叠加）
     * @property preferredStartHour  首选开始时间（小时，0-23）
     * @property onlyOnWifi          是否仅在 Wi-Fi 下同步
     * @property onlyWhileCharging   是否仅在充电时同步
     * @property dataCategories      需要同步的数据类别列表（空列表表示全部）
     * @property direction           同步方向
     * @property strategy            冲突解决策略
     * @property maxRetryCount       最大重试次数
     * @property retryDelayMinutes   重试间隔（分钟）
     * @property enableIncrementalSync  是否启用增量同步
     * @property description         调度描述
     */
    data class SyncSchedule(
        var enabled: Boolean = true,
        var intervalHours: Int = 24,
        var intervalMinutes: Int = 0,
        var preferredStartHour: Int = 2,
        var onlyOnWifi: Boolean = false,
        var onlyWhileCharging: Boolean = false,
        var dataCategories: List<DataCategory> = emptyList(),
        var direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        var strategy: ConflictStrategy = ConflictStrategy.MERGE,
        var maxRetryCount: Int = 3,
        var retryDelayMinutes: Int = 30,
        var enableIncrementalSync: Boolean = true,
        var description: String = "默认同步调度"
    )

    /**
     * 同步统计 —— 同步操作的详细统计信息。
     *
     * @property lastSyncTime          最后同步时间戳
     * @property lastSyncDurationMs    最后同步耗时（毫秒）
     * @property totalSyncCount        累计同步次数
     * @property successfulSyncCount   成功同步次数
     * @property failedSyncCount       失败同步次数
     * @property conflictCount         检测到冲突的次数
     * @property bytesUploaded         累计上传字节数
     * @property bytesDownloaded       累计下载字节数
     * @property itemsUploaded         累计上传数据项数
     * @property itemsDownloaded       累计下载数据项数
     * @property lastSyncStatus        最后同步状态
     * @property lastErrorMessage      最后错误信息
     * @property averageSyncDurationMs 平均同步耗时（毫秒）
     * @property syncRatePerDay        每日平均同步次数
     * @property firstSyncTime         首次同步时间戳
     * @property dataCategoryStats     按数据类别统计的同步次数
     */
    data class SyncStats(
        var lastSyncTime: Long = 0L,
        var lastSyncDurationMs: Long = 0L,
        var totalSyncCount: Int = 0,
        var successfulSyncCount: Int = 0,
        var failedSyncCount: Int = 0,
        var conflictCount: Int = 0,
        var bytesUploaded: Long = 0L,
        var bytesDownloaded: Long = 0L,
        var itemsUploaded: Int = 0,
        var itemsDownloaded: Int = 0,
        var lastSyncStatus: SyncStatus = SyncStatus.IDLE,
        var lastErrorMessage: String = "",
        var averageSyncDurationMs: Long = 0L,
        var syncRatePerDay: Float = 0f,
        var firstSyncTime: Long = 0L,
        val dataCategoryStats: MutableMap<DataCategory, AtomicInteger> = ConcurrentHashMap()
    )

    /**
     * 备份元数据 —— 描述一次备份操作的元信息。
     *
     * @property backupId         备份唯一标识符
     * @property backupTimestamp  备份时间戳
     * @property dataCategory     备份的数据类别
     * @property itemCount        备份的数据项数量
     * @property totalSize        备份总大小（字节）
     * @property backupVersion    备份格式版本号
     * @property appVersion       应用版本号
     * @property deviceId         设备标识符
     * @property androidVersion   Android 系统版本
     * @property checksum         备份文件校验和
     * @property isEncrypted      是否已加密
     * @property compressionType  压缩类型（如 "gzip", "none"）
     * @property tags             自定义标签
     * @property notes            备份备注
     */
    data class BackupMetadata(
        val backupId: String = UUID.randomUUID().toString(),
        val backupTimestamp: Long = System.currentTimeMillis(),
        val dataCategory: DataCategory = DataCategory.ALL,
        val itemCount: Int = 0,
        val totalSize: Long = 0L,
        val backupVersion: Int = 1,
        val appVersion: String = "",
        val deviceId: String = "",
        val androidVersion: String = "",
        val checksum: String = "",
        val isEncrypted: Boolean = false,
        val compressionType: String = "none",
        val tags: MutableList<String> = CopyOnWriteArrayList(),
        val notes: String = ""
    )

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 数据存储（category → key → SyncData），按类别分层存储。 */
    private val dataStore = ConcurrentHashMap<DataCategory, ConcurrentHashMap<String, SyncData>>()

    /** 同步清单存储（manifestId → SyncManifest）。 */
    private val manifestStore = ConcurrentHashMap<String, SyncManifest>()

    /** 冲突存储（conflictId → SyncConflict），记录待解决的冲突。 */
    private val conflictStore = ConcurrentHashMap<String, SyncConflict>()

    /** 已解决的冲突历史（conflictId → SyncConflict），用于审计。 */
    private val resolvedConflictHistory = ConcurrentHashMap<String, SyncConflict>()

    /** 同步统计信息。 */
    private val syncStats = SyncStats()

    /** 当前同步状态。 */
    @Volatile
    private var currentSyncStatus: SyncStatus = SyncStatus.IDLE

    /** 当前同步的清单 ID。 */
    @Volatile
    private var currentManifestId: String = ""

    /** 设备标识符。 */
    @Volatile
    private var deviceId: String = "UNKNOWN_DEVICE"

    /** 应用版本号。 */
    @Volatile
    private var appVersion: String = "1.0.0"

    /** 数据版本号（全局单调递增）。 */
    private val globalDataVersion = AtomicLong(1L)

    /** 数据变更日志（按时间顺序记录变更），用于增量同步。 */
    private val changeLog = ConcurrentLinkedDeque<ChangeRecord>()

    /** 变更日志最大长度。 */
    @Volatile
    private var maxChangeLogSize: Int = 1000

    /** 备份目录路径。 */
    @Volatile
    private var backupDir: String = ""

    /** 同步调度配置。 */
    private val syncScheduleRef = AtomicReference(SyncSchedule())

    /** 定时同步调度器。 */
    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    /** 读写锁，保护复合操作的原子性。 */
    private val rwLock = ReentrantReadWriteLock()

    /** 备份操作锁，防止并发备份。 */
    private val backupLock = Any()

    /** 同步操作锁，防止并发同步。 */
    private val syncLock = Any()

    /** 数据变更侦听器列表。 */
    private val changeListeners = CopyOnWriteArrayList<OnDataChangeListener>()

    // ============================================================
    // 内部数据类
    // ============================================================

    /**
     * 变更记录 —— 记录单次数据变更的详细信息。
     *
     * @property recordId     记录唯一标识符
     * @property category     变更的数据类别
     * @property key          变更的数据键名
     * @property changeType   变更类型（CREATE / UPDATE / DELETE）
     * @property oldHash      变更前的数据哈希值
     * @property newHash      变更后的数据哈希值
     * @property timestamp    变更时间戳
     * @property version      变更时的数据版本号
     */
    private data class ChangeRecord(
        val recordId: String = UUID.randomUUID().toString(),
        val category: DataCategory,
        val key: String,
        val changeType: ChangeType,
        val oldHash: String = "",
        val newHash: String = "",
        val timestamp: Long = System.currentTimeMillis(),
        val version: Long = 0L
    )

    /**
     * 变更类型枚举。
     */
    private enum class ChangeType {
        /** 创建新数据。 */
        CREATE,
        /** 更新已有数据。 */
        UPDATE,
        /** 删除数据。 */
        DELETE
    }

    // ============================================================
    // 接口定义
    // ============================================================

    /**
     * 数据变更侦听器 —— 当数据发生变更时回调。
     */
    interface OnDataChangeListener {
        /**
         * 数据变更时的回调方法。
         *
         * @param category 变更的数据类别
         * @param key      变更的数据键名
         * @param newData  变更后的数据（删除时为 null）
         * @param changeType 变更类型
         * @param timestamp 变更时间戳
         */
        fun onDataChanged(
            category: DataCategory,
            key: String,
            newData: SyncData?,
            changeType: String,
            timestamp: Long
        )
    }

    // ============================================================
    // 配置常量（Companion）
    // ============================================================

    companion object {
        /** 默认备份目录名。 */
        private const val DEFAULT_BACKUP_DIR = "data_sync_manager"

        /** 备份文件前缀。 */
        private const val BACKUP_FILE_PREFIX = "backup_"

        /** 备份文件扩展名。 */
        private const val BACKUP_FILE_EXTENSION = ".json"

        /** 清单文件前缀。 */
        private const val MANIFEST_FILE_PREFIX = "manifest_"

        /** 最大备份文件大小（字节，默认 10MB）。 */
        private const val MAX_BACKUP_FILE_SIZE = 10 * 1024 * 1024

        /** 最大保留备份文件数。 */
        private const val MAX_BACKUP_FILES = 20

        /** 最大数据项数（单个类别）。 */
        private const val MAX_DATA_ITEMS_PER_CATEGORY = 500

        /** 最大冲突记录数。 */
        private const val MAX_CONFLICT_RECORDS = 100

        /** 默认同步间隔（小时）。 */
        private const val DEFAULT_SYNC_INTERVAL_HOURS = 24

        /** 最小同步间隔（分钟，防止过于频繁的同步）。 */
        private const val MIN_SYNC_INTERVAL_MINUTES = 15

        /** 重试最大延迟（分钟）。 */
        private const val MAX_RETRY_DELAY_MINUTES = 120

        /** 同步超时时间（毫秒，默认 5 分钟）。 */
        private const val SYNC_TIMEOUT_MS = 5 * 60 * 1000L

        /** 大文件阈值（字节，超过此值视为大文件）。 */
        private const val LARGE_FILE_THRESHOLD = 1024 * 1024

        /** 数据版本号键名，用于元数据中标识版本。 */
        private const val META_VERSION_KEY = "data_version"

        /** 数据哈希键名，用于元数据中标识哈希。 */
        private const val META_HASH_KEY = "data_hash"

        /** 数据来源键名，用于元数据中标识来源设备。 */
        private const val META_SOURCE_DEVICE_KEY = "source_device"

        /** 数据来源键名，用于元数据中标识来源设备。 */
        private const val META_MODIFIED_BY_KEY = "modified_by"

        /** 默认同步调度的数据类别列表。 */
        private val DEFAULT_SYNC_CATEGORIES = listOf(
            DataCategory.USER_PROFILE,
            DataCategory.PREFERENCES,
            DataCategory.LEARNED_RULES,
            DataCategory.WORKFLOWS
        )

        /** 序列化 JSON 配置。 */
        private val jsonConfig = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 时间格式化器。 */
        private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    // ============================================================
    // 统计计数
    // ============================================================

    private val totalBackupCount = AtomicInteger(0)
    private val totalRestoreCount = AtomicInteger(0)
    private val totalConflictCount = AtomicInteger(0)
    private val totalResolvedCount = AtomicInteger(0)
    private val totalSyncInitiated = AtomicInteger(0)
    private val totalPrepareCount = AtomicInteger(0)
    private val totalIncrementalSyncCount = AtomicInteger(0)
    private val totalDataItemsTracked = AtomicInteger(0)

    // ============================================================
    // 初始化
    // ============================================================

    /**
     * 初始化管理器，设置备份目录和相关配置。
     * 建议在 Application.onCreate 中或在 DataSyncManager 创建后立即调用。
     *
     * @param backupDirectory 备份目录路径，为空时使用默认缓存目录
     * @param deviceIdentifier 设备标识符，用于区分多设备同步
     * @param appVersionString 应用版本号，用于版本兼容性检查
     * @param maxChangeLogRecords 变更日志最大记录数
     */
    fun initialize(
        backupDirectory: String = "",
        deviceIdentifier: String = "UNKNOWN_DEVICE",
        appVersionString: String = "1.0.0",
        maxChangeLogRecords: Int = 1000
    ) {
        backupDir = if (backupDirectory.isBlank()) {
            "${System.getProperty("java.io.tmpdir")}/$DEFAULT_BACKUP_DIR"
        } else {
            backupDirectory
        }
        val dir = File(backupDir)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        deviceId = deviceIdentifier
        appVersion = appVersionString
        maxChangeLogSize = maxChangeLogRecords
        Log.d(tag, "初始化完成，备份目录: $backupDir，设备ID: $deviceId，版本: $appVersion")
    }

    // ============================================================
    // 核心方法：状态备份
    // ============================================================

    /**
     * 备份状态 —— 将应用的学习状态备份到本地存储。
     *
     * 备份过程包括：
     * 1. 收集指定类别的所有数据项
     * 2. 生成备份元数据（版本、校验和、设备信息等）
     * 3. 序列化为 JSON 格式并写入文件
     * 4. 更新备份统计信息
     * 5. 清理超过最大保留数量的旧备份
     *
     * 线程安全：使用 [backupLock] 保证并发安全。
     *
     * @param category 要备份的数据类别，ALL 表示备份所有类别
     * @param customLabel 可选的自定义备份标签
     * @return [BackupMetadata] 包含备份的元数据信息，失败时返回 null
     */
    fun backupState(
        category: DataCategory = DataCategory.ALL,
        customLabel: String = ""
    ): BackupMetadata? {
        synchronized(backupLock) {
            try {
                Log.d(tag, "开始备份，类别: ${category.name}，标签: $customLabel")

                val categories = if (category == DataCategory.ALL) {
                    DataCategory.entries.filter { it != DataCategory.ALL }
                } else {
                    listOf(category)
                }

                val allItems = mutableListOf<SyncData>()
                var totalSize = 0L

                for (cat in categories) {
                    val store = dataStore[cat]
                    if (store != null) {
                        synchronized(store) {
                            for (item in store.values) {
                                allItems.add(item.copy())
                                totalSize += item.size
                            }
                        }
                    }
                }

                if (allItems.isEmpty()) {
                    Log.w(tag, "备份数据为空，跳过备份")
                    return null
                }

                val backupTimestamp = System.currentTimeMillis()
                val checksum = computeChecksum(allItems)

                val metadata = BackupMetadata(
                    backupTimestamp = backupTimestamp,
                    dataCategory = category,
                    itemCount = allItems.size,
                    totalSize = totalSize,
                    backupVersion = 1,
                    appVersion = appVersion,
                    deviceId = deviceId,
                    androidVersion = android.os.Build.VERSION.RELEASE,
                    checksum = checksum,
                    isEncrypted = false,
                    compressionType = "none",
                    notes = customLabel
                )

                val backupJson = buildJsonObject {
                    put("metadata", buildJsonObject {
                        put("backupId", JsonPrimitive(metadata.backupId))
                        put("backupTimestamp", JsonPrimitive(metadata.backupTimestamp))
                        put("dataCategory", JsonPrimitive(metadata.dataCategory.name))
                        put("itemCount", JsonPrimitive(metadata.itemCount))
                        put("totalSize", JsonPrimitive(metadata.totalSize))
                        put("backupVersion", JsonPrimitive(metadata.backupVersion))
                        put("appVersion", JsonPrimitive(metadata.appVersion))
                        put("deviceId", JsonPrimitive(metadata.deviceId))
                        put("androidVersion", JsonPrimitive(metadata.androidVersion))
                        put("checksum", JsonPrimitive(metadata.checksum))
                        put("isEncrypted", JsonPrimitive(metadata.isEncrypted))
                        put("compressionType", JsonPrimitive(metadata.compressionType))
                        put("notes", JsonPrimitive(metadata.notes))
                    })
                    put("data", buildJsonObject {
                        for (item in allItems) {
                            putJsonObject(item.key) {
                                put("id", JsonPrimitive(item.id))
                                put("category", JsonPrimitive(item.category.name))
                                put("key", JsonPrimitive(item.key))
                                put("value", item.value)
                                put("version", JsonPrimitive(item.version))
                                put("hash", JsonPrimitive(item.hash))
                                put("lastModified", JsonPrimitive(item.lastModified))
                                put("size", JsonPrimitive(item.size))
                                put("direction", JsonPrimitive(item.direction.name))
                                put("description", JsonPrimitive(item.description))
                            }
                        }
                    })
                }

                val backupFileName = "${BACKUP_FILE_PREFIX}${metadata.backupId}$BACKUP_FILE_EXTENSION"
                val backupFile = File(backupDir, backupFileName)
                backupFile.writeText(jsonConfig.encodeToString(JsonElement.serializer(), backupJson))

                totalBackupCount.incrementAndGet()
                cleanupOldBackups()
                notifyDataChange(DataCategory.ALL, "backup_${metadata.backupId}", null, "BACKUP", backupTimestamp)

                Log.d(tag, "备份完成: ${backupFile.absolutePath}，${allItems.size} 项，${totalSize} 字节")
                return metadata
            } catch (e: Exception) {
                Log.e(tag, "备份失败: ${e.message}", e)
                return null
            }
        }
    }

    // ============================================================
    // 核心方法：状态恢复
    // ============================================================

    /**
     * 恢复状态 —— 从本地备份文件恢复应用状态。
     *
     * 恢复过程包括：
     * 1. 扫描备份目录，查找匹配类别的备份文件
     * 2. 验证备份文件的校验和
     * 3. 反序列化 JSON 数据
     * 4. 将数据恢复到 [dataStore] 中
     * 5. 更新版本号确保数据一致性
     * 6. 记录恢复操作到变更日志
     *
     * 线程安全：使用 [rwLock] 的写锁保护数据恢复过程。
     *
     * @param category 要恢复的数据类别
     * @param backupId 可选的备份 ID，为空时恢复最新的备份
     * @return 恢复成功的数据项数量，失败返回 -1
     */
    fun restoreState(
        category: DataCategory = DataCategory.ALL,
        backupId: String = ""
    ): Int {
        try {
            Log.d(tag, "开始恢复，类别: ${category.name}，备份ID: $backupId")

            val backupFile = if (backupId.isNotBlank()) {
                File(backupDir, "${BACKUP_FILE_PREFIX}${backupId}$BACKUP_FILE_EXTENSION")
            } else {
                findLatestBackupFile(category)
            }

            if (backupFile == null || !backupFile.exists()) {
                Log.w(tag, "未找到备份文件，恢复失败")
                return -1
            }

            val jsonText = backupFile.readText()
            val root = jsonConfig.parseToJsonElement(jsonText).jsonObject
            val metadataObj = root["metadata"]?.jsonObject ?: return -1
            val dataObj = root["data"]?.jsonObject ?: return -1

            val storedChecksum = metadataObj["checksum"]?.jsonPrimitive?.content ?: ""
            var restoreCount = 0

            val restoredItems = mutableListOf<SyncData>()

            rwLock.writeLock().lock()
            try {
                for ((key, element) in dataObj) {
                    val itemObj = element.jsonObject
                    val itemCategory = DataCategory.valueOf(
                        itemObj["category"]?.jsonPrimitive?.content ?: "USER_PROFILE"
                    )

                    if (category != DataCategory.ALL && itemCategory != category) {
                        continue
                    }

                    val syncData = SyncData(
                        id = itemObj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                        category = itemCategory,
                        key = key,
                        value = itemObj["value"] ?: JsonPrimitive(""),
                        version = itemObj["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1L,
                        hash = itemObj["hash"]?.jsonPrimitive?.content ?: "",
                        lastModified = itemObj["lastModified"]?.jsonPrimitive?.content?.toLongOrNull()
                            ?: System.currentTimeMillis(),
                        size = itemObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                        direction = try {
                            SyncDirection.valueOf(
                                itemObj["direction"]?.jsonPrimitive?.content ?: "BIDIRECTIONAL"
                            )
                        } catch (e: IllegalArgumentException) {
                            SyncDirection.BIDIRECTIONAL
                        },
                        description = itemObj["description"]?.jsonPrimitive?.content ?: ""
                    )

                    val categoryStore = dataStore.getOrPut(itemCategory) { ConcurrentHashMap() }
                    synchronized(categoryStore) {
                        categoryStore[key] = syncData
                    }

                    restoredItems.add(syncData)
                    restoreCount++
                    totalDataItemsTracked.incrementAndGet()

                    recordChange(itemCategory, key, ChangeType.CREATE, "", syncData.hash, syncData.version)
                }

                verifyChecksum(restoredItems, storedChecksum)
                totalRestoreCount.incrementAndGet()
                Log.d(tag, "恢复完成: 共 $restoreCount 项数据")
            } finally {
                rwLock.writeLock().unlock()
            }

            notifyDataChange(category, "restore_${backupFile.name}", null, "RESTORE", System.currentTimeMillis())
            return restoreCount
        } catch (e: Exception) {
            Log.e(tag, "恢复失败: ${e.message}", e)
            return -1
        }
    }

    // ============================================================
    // 核心方法：准备云同步数据
    // ============================================================

    /**
     * 准备云同步数据 —— 将指定类别的数据序列化为 JSON 格式的同步清单。
     *
     * 准备过程包括：
     * 1. 从 [dataStore] 中收集指定类别的数据
     * 2. 生成 [SyncManifest] 清单（包含版本、校验和、时间戳等）
     * 3. 序列化为 JSON 字符串
     * 4. 支持全量同步和增量同步模式
     *
     * 线程安全：使用 [rwLock] 的读锁保护数据读取。
     *
     * @param dataId   数据标识符（用于定位数据项）
     * @param category 数据类别
     * @param isFullSync 是否为全量同步，false 时只包含增量数据
     * @param sinceTimestamp 增量同步的起始时间戳
     * @return 包含同步清单和数据的 JSON 字符串，失败返回空 JSON 对象
     */
    fun prepareSyncData(
        dataId: String,
        category: DataCategory,
        isFullSync: Boolean = true,
        sinceTimestamp: Long = 0L
    ): String {
        try {
            Log.d(tag, "准备同步数据: dataId=$dataId, category=${category.name}, fullSync=$isFullSync")

            totalPrepareCount.incrementAndGet()

            val categories = if (category == DataCategory.ALL) {
                DataCategory.entries.filter { it != DataCategory.ALL }
            } else {
                listOf(category)
            }

            val dataItems = mutableListOf<SyncData>()

            rwLock.readLock().lock()
            try {
                for (cat in categories) {
                    val store = dataStore[cat]
                    if (store != null) {
                        synchronized(store) {
                            for (item in store.values) {
                                if (isFullSync) {
                                    dataItems.add(item.copy())
                                } else {
                                    // 增量同步：只包含 sinceTimestamp 之后修改的数据
                                    if (item.lastModified >= sinceTimestamp) {
                                        dataItems.add(item.copy())
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                rwLock.readLock().unlock()
            }

            if (dataItems.isEmpty()) {
                Log.d(tag, "同步数据为空，返回空清单")
                return buildJsonObject {
                    put("manifest", JsonPrimitive("empty"))
                    put("message", JsonPrimitive("没有需要同步的数据"))
                }.toString()
            }

            val totalSize = dataItems.sumOf { it.size }
            val checksum = computeChecksum(dataItems)
            val currentVersion = globalDataVersion.get()

            val manifest = SyncManifest(
                deviceId = deviceId,
                dataCategory = category,
                dataItems = dataItems,
                totalItems = dataItems.size,
                totalSize = totalSize,
                syncTimestamp = System.currentTimeMillis(),
                manifestVersion = 1,
                sourceVersion = currentVersion,
                targetVersion = currentVersion,
                checksum = checksum,
                isFullSync = isFullSync
            )

            manifestStore[manifest.manifestId] = manifest
            currentManifestId = manifest.manifestId

            val syncJson = buildJsonObject {
                put("manifest", buildJsonObject {
                    put("manifestId", JsonPrimitive(manifest.manifestId))
                    put("deviceId", JsonPrimitive(manifest.deviceId))
                    put("dataCategory", JsonPrimitive(manifest.dataCategory.name))
                    put("syncDirection", JsonPrimitive(manifest.syncDirection.name))
                    put("totalItems", JsonPrimitive(manifest.totalItems))
                    put("totalSize", JsonPrimitive(manifest.totalSize))
                    put("syncTimestamp", JsonPrimitive(manifest.syncTimestamp))
                    put("manifestVersion", JsonPrimitive(manifest.manifestVersion))
                    put("sourceVersion", JsonPrimitive(manifest.sourceVersion))
                    put("targetVersion", JsonPrimitive(manifest.targetVersion))
                    put("checksum", JsonPrimitive(manifest.checksum))
                    put("isFullSync", JsonPrimitive(manifest.isFullSync))
                })
                put("data", buildJsonObject {
                    for (item in dataItems) {
                        putJsonObject(item.key) {
                            put("id", JsonPrimitive(item.id))
                            put("category", JsonPrimitive(item.category.name))
                            put("key", JsonPrimitive(item.key))
                            put("value", item.value)
                            put("version", JsonPrimitive(item.version))
                            put("hash", JsonPrimitive(item.hash))
                            put("lastModified", JsonPrimitive(item.lastModified))
                            put("size", JsonPrimitive(item.size))
                            put("direction", JsonPrimitive(item.direction.name))
                            put("description", JsonPrimitive(item.description))
                        }
                    }
                })
            }

            Log.d(tag, "同步数据准备完成: ${dataItems.size} 项, ${totalSize} 字节, 清单ID: ${manifest.manifestId}")
            return jsonConfig.encodeToString(JsonElement.serializer(), syncJson)
        } catch (e: Exception) {
            Log.e(tag, "准备同步数据失败: ${e.message}", e)
            return buildJsonObject {
                put("error", JsonPrimitive("准备同步数据失败: ${e.message}"))
            }.toString()
        }
    }

    // ============================================================
    // 核心方法：同步冲突解决
    // ============================================================

    /**
     * 解决同步冲突 —— 根据指定的策略处理本地和远程数据之间的冲突。
     *
     * 支持的冲突解决策略：
     * - [ConflictStrategy.LAST_WRITE_WINS]：比较 [localModified] 和 [remoteModified]，
     *   以较新的时间戳为准。如果时间戳相同，以远程版本为准。
     * - [ConflictStrategy.MERGE]：尝试合并两份数据。
     *   - 如果值类型为 JsonObject，则逐键合并（远程值覆盖本地值）
     *   - 如果值类型为 JsonPrimitive，以远程值优先
     *   - 其他类型退化为 LAST_WRITE_WINS
     * - [ConflictStrategy.KEEP_BOTH]：保留两份数据，本地数据键名后缀 "_local"，
     *   远程数据键名后缀 "_remote"，均写入 dataStore
     * - [ConflictStrategy.MANUAL]：仅标记冲突为已解决，但保留原始值等待消费者处理
     *
     * 线程安全：使用 [rwLock] 的写锁保护数据更新。
     *
     * @param conflict 待解决的冲突对象
     * @param strategy 使用的冲突解决策略
     * @return 解决后的 [SyncData] 对象，解决失败返回 null
     */
    fun resolveConflict(
        conflict: SyncConflict,
        strategy: ConflictStrategy
    ): SyncData? {
        try {
            Log.d(tag, "解决冲突: conflictId=${conflict.conflictId}, strategy=${strategy.name}")

            if (conflict.isResolved) {
                Log.w(tag, "冲突已解决，跳过: ${conflict.conflictId}")
                return conflict.resolvedValue?.let { resolvedValue ->
                    SyncData(
                        id = conflict.dataId,
                        category = conflict.category,
                        key = conflict.key,
                        value = resolvedValue,
                        version = maxOf(conflict.localVersion, conflict.remoteVersion) + 1
                    )
                }
            }

            totalConflictCount.incrementAndGet()
            val resolvedValue: JsonElement
            val resolvedKey: String

            when (strategy) {
                ConflictStrategy.LAST_WRITE_WINS -> {
                    // 最后写入者获胜，以较新的修改时间戳为准
                    if (conflict.localModified >= conflict.remoteModified) {
                        resolvedValue = conflict.localValue
                        resolvedKey = conflict.key
                        Log.d(tag, "LAST_WRITE_WINS: 本地版本获胜 (${conflict.localModified} >= ${conflict.remoteModified})")
                    } else {
                        resolvedValue = conflict.remoteValue
                        resolvedKey = conflict.key
                        Log.d(tag, "LAST_WRITE_WINS: 远程版本获胜 (${conflict.localModified} < ${conflict.remoteModified})")
                    }
                }

                ConflictStrategy.MERGE -> {
                    // 合并策略：尝试合并两份数据
                    val merged = tryMergeValues(conflict.localValue, conflict.remoteValue)
                    resolvedValue = merged
                    resolvedKey = conflict.key
                    Log.d(tag, "MERGE: 数据合并完成")
                }

                ConflictStrategy.KEEP_BOTH -> {
                    // 两者都保留：本地和远程分别以不同键名保存
                    val localKey = "${conflict.key}_local"
                    val remoteKey = "${conflict.key}_remote"

                    val localData = SyncData(
                        id = "${conflict.dataId}_local",
                        category = conflict.category,
                        key = localKey,
                        value = conflict.localValue,
                        version = conflict.localVersion,
                        hash = computeSha256(conflict.localValue.toString()),
                        lastModified = conflict.localModified,
                        direction = SyncDirection.UPLOAD
                    )

                    val remoteData = SyncData(
                        id = "${conflict.dataId}_remote",
                        category = conflict.category,
                        key = remoteKey,
                        value = conflict.remoteValue,
                        version = conflict.remoteVersion,
                        hash = computeSha256(conflict.remoteValue.toString()),
                        lastModified = conflict.remoteModified,
                        direction = SyncDirection.DOWNLOAD
                    )

                    val categoryStore = dataStore.getOrPut(conflict.category) { ConcurrentHashMap() }
                    synchronized(categoryStore) {
                        categoryStore[localKey] = localData
                        categoryStore[remoteKey] = remoteData
                    }

                    totalResolvedCount.incrementAndGet()
                    conflict.resolvedStrategy = strategy
                    conflict.resolvedValue = conflict.localValue // 默认以本地为主
                    conflict.isResolved = true
                    conflict.resolvedTimestamp = System.currentTimeMillis()

                    resolvedConflictHistory[conflict.conflictId] = conflict.copy()
                    conflictStore.remove(conflict.conflictId)

                    Log.d(tag, "KEEP_BOTH: 本地和远程数据均已保留")
                    return localData
                }

                ConflictStrategy.MANUAL -> {
                    // 手动解决：标记为已解决，但保留原始值
                    resolvedValue = conflict.localValue
                    resolvedKey = conflict.key
                    Log.d(tag, "MANUAL: 标记为手动解决，保留本地值")
                }
            }

            val newVersion = maxOf(conflict.localVersion, conflict.remoteVersion) + 1
            val newHash = computeSha256(resolvedValue.toString())

            val resolvedData = SyncData(
                id = conflict.dataId,
                category = conflict.category,
                key = resolvedKey,
                value = resolvedValue,
                version = newVersion,
                hash = newHash,
                lastModified = System.currentTimeMillis(),
                direction = SyncDirection.BIDIRECTIONAL
            )

            val categoryStore = dataStore.getOrPut(conflict.category) { ConcurrentHashMap() }
            synchronized(categoryStore) {
                categoryStore[resolvedKey] = resolvedData
            }

            globalDataVersion.set(newVersion)
            recordChange(conflict.category, resolvedKey, ChangeType.UPDATE, conflict.localValue.hashCode().toString(), newHash, newVersion)
            totalResolvedCount.incrementAndGet()

            conflict.resolvedStrategy = strategy
            conflict.resolvedValue = resolvedValue
            conflict.isResolved = true
            conflict.resolvedTimestamp = System.currentTimeMillis()

            resolvedConflictHistory[conflict.conflictId] = conflict.copy()
            conflictStore.remove(conflict.conflictId)

            Log.d(tag, "冲突解决完成: 策略=${strategy.name}, 新版本=$newVersion")
            return resolvedData
        } catch (e: Exception) {
            Log.e(tag, "解决冲突失败: ${e.message}", e)
            return null
        }
    }

    // ============================================================
    // 核心方法：增量同步
    // ============================================================

    /**
     * 增量同步 —— 只同步本地自上次同步以来发生变更的数据。
     *
     * 增量同步流程：
     * 1. 从 [changeLog] 中获取自 [sinceTimestamp] 以来的变更记录
     * 2. 根据变更记录收集对应的数据项
     * 3. 与远程数据比对版本号和哈希值
     * 4. 识别真正的变更项（排除无实际变化的项）
     * 5. 检测冲突（本地和远程都修改了同一数据项）
     * 6. 对无冲突的变更项直接同步
     * 7. 对有冲突的项创建 [SyncConflict] 并存入 [conflictStore]
     *
     * 线程安全：使用 [syncLock] 保证并发同步安全。
     *
     * @param dataId       数据标识符
     * @param category     数据类别
     * @param remoteData   JSON 格式的远程数据字符串
     * @return 同步结果 JSON 字符串，包含变更项、冲突项、统计信息
     */
    fun incrementalSync(
        dataId: String,
        category: DataCategory,
        remoteData: String
    ): String {
        synchronized(syncLock) {
            try {
                Log.d(tag, "开始增量同步: dataId=$dataId, category=${category.name}")
                currentSyncStatus = SyncStatus.SYNCING
                totalIncrementalSyncCount.incrementAndGet()
                totalSyncInitiated.incrementAndGet()

                val syncStartTime = System.currentTimeMillis()
                val remoteRoot = jsonConfig.parseToJsonElement(remoteData).jsonObject
                val remoteManifest = remoteRoot["manifest"]?.jsonObject
                val remoteDataObj = remoteRoot["data"]?.jsonObject

                if (remoteDataObj == null) {
                    Log.w(tag, "远程数据为空")
                    currentSyncStatus = SyncStatus.FAILED
                    return buildJsonObject {
                        put("status", JsonPrimitive("failed"))
                        put("message", JsonPrimitive("远程数据为空"))
                    }.toString()
                }

                val categories = if (category == DataCategory.ALL) {
                    DataCategory.entries.filter { it != DataCategory.ALL }
                } else {
                    listOf(category)
                }

                val changedItems = mutableListOf<SyncData>()
                val conflictItems = mutableListOf<SyncConflict>()
                val newItems = mutableListOf<SyncData>()
                var bytesTransferred = 0L

                // 遍历远程数据，与本地数据对比
                for ((remoteKey, remoteElement) in remoteDataObj) {
                    val remoteItem = remoteElement.jsonObject
                    val remoteCategoryName = remoteItem["category"]?.jsonPrimitive?.content ?: "USER_PROFILE"
                    val remoteCategory = try {
                        DataCategory.valueOf(remoteCategoryName)
                    } catch (e: IllegalArgumentException) {
                        continue
                    }

                    if (category != DataCategory.ALL && remoteCategory != category) {
                        continue
                    }

                    val remoteVersion = remoteItem["version"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val remoteHash = remoteItem["hash"]?.jsonPrimitive?.content ?: ""
                    val remoteModified = remoteItem["lastModified"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val remoteValue = remoteItem["value"] ?: JsonPrimitive("")

                    val localStore = dataStore[remoteCategory]
                    val localItem = localStore?.get(remoteKey)

                    if (localItem == null) {
                        // 本地不存在，这是新增项
                        val newData = SyncData(
                            id = remoteItem["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
                            category = remoteCategory,
                            key = remoteKey,
                            value = remoteValue,
                            version = remoteVersion,
                            hash = remoteHash,
                            lastModified = remoteModified,
                            size = remoteItem["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                            direction = SyncDirection.DOWNLOAD,
                            description = remoteItem["description"]?.jsonPrimitive?.content ?: ""
                        )
                        newItems.add(newData)
                        bytesTransferred += newData.size
                    } else {
                        // 本地已存在，检查版本和哈希
                        if (localItem.hash != remoteHash) {
                            // 内容不同，检测冲突
                            if (localItem.version >= remoteVersion && localItem.lastModified > remoteModified) {
                                // 本地版本更新或同时修改，检测冲突
                                val conflict = SyncConflict(
                                    dataId = localItem.id,
                                    category = remoteCategory,
                                    key = remoteKey,
                                    localValue = localItem.value,
                                    remoteValue = remoteValue,
                                    localVersion = localItem.version,
                                    remoteVersion = remoteVersion,
                                    localModified = localItem.lastModified,
                                    remoteModified = remoteModified,
                                    suggestedStrategy = ConflictStrategy.MERGE,
                                    description = "本地(版本${localItem.version})和远程(版本${remoteVersion})数据不一致"
                                )
                                conflictItems.add(conflict)
                                conflictStore[conflict.conflictId] = conflict
                            } else {
                                // 远程版本更新，直接更新本地
                                val updatedData = localItem.copy(
                                    value = remoteValue,
                                    version = remoteVersion,
                                    hash = remoteHash,
                                    lastModified = remoteModified,
                                    direction = SyncDirection.DOWNLOAD
                                )
                                changedItems.add(updatedData)
                                bytesTransferred += updatedData.size
                            }
                        }
                    }
                }

                // 应用变更到本地数据存储
                rwLock.writeLock().lock()
                try {
                    for (item in newItems) {
                        val store = dataStore.getOrPut(item.category) { ConcurrentHashMap() }
                        synchronized(store) {
                            store[item.key] = item
                        }
                        recordChange(item.category, item.key, ChangeType.CREATE, "", item.hash, item.version)
                    }

                    for (item in changedItems) {
                        val store = dataStore.getOrPut(item.category) { ConcurrentHashMap() }
                        synchronized(store) {
                            store[item.key] = item
                        }
                        recordChange(item.category, item.key, ChangeType.UPDATE, "", item.hash, item.version)
                    }
                } finally {
                    rwLock.writeLock().unlock()
                }

                // 更新统计信息
                val syncDuration = System.currentTimeMillis() - syncStartTime
                val allChanges = newItems.size + changedItems.size

                syncStats.lastSyncTime = syncStartTime
                syncStats.lastSyncDurationMs = syncDuration
                syncStats.totalSyncCount++
                syncStats.bytesDownloaded += bytesTransferred
                syncStats.itemsDownloaded += allChanges

                if (conflictItems.isNotEmpty()) {
                    currentSyncStatus = SyncStatus.CONFLICT
                    syncStats.conflictCount += conflictItems.size
                    syncStats.lastSyncStatus = SyncStatus.CONFLICT
                } else {
                    currentSyncStatus = SyncStatus.SUCCESS
                    syncStats.successfulSyncCount++
                    syncStats.lastSyncStatus = SyncStatus.SUCCESS
                }

                syncStats.lastErrorMessage = ""
                syncStats.averageSyncDurationMs = if (syncStats.totalSyncCount > 0) {
                    (syncStats.averageSyncDurationMs * (syncStats.totalSyncCount - 1) + syncDuration) / syncStats.totalSyncCount
                } else {
                    syncDuration
                }

                Log.d(tag, "增量同步完成: 新增=$newItems, 变更=$changedItems, 冲突=${conflictItems.size}, 耗时=${syncDuration}ms")

                return buildJsonObject {
                    put("status", JsonPrimitive(currentSyncStatus.name))
                    put("newItems", JsonPrimitive(newItems.size))
                    put("changedItems", JsonPrimitive(changedItems.size))
                    put("conflictItems", JsonPrimitive(conflictItems.size))
                    put("bytesTransferred", JsonPrimitive(bytesTransferred))
                    put("durationMs", JsonPrimitive(syncDuration))
                    put("syncTimestamp", JsonPrimitive(syncStartTime))
                    putJsonArray("conflicts") {
                        for (conflict in conflictItems) {
                            add(buildJsonObject {
                                put("conflictId", JsonPrimitive(conflict.conflictId))
                                put("dataId", JsonPrimitive(conflict.dataId))
                                put("category", JsonPrimitive(conflict.category.name))
                                put("key", JsonPrimitive(conflict.key))
                                put("localVersion", JsonPrimitive(conflict.localVersion))
                                put("remoteVersion", JsonPrimitive(conflict.remoteVersion))
                                put("suggestedStrategy", JsonPrimitive(conflict.suggestedStrategy.name))
                            })
                        }
                    }
                }.toString()
            } catch (e: Exception) {
                Log.e(tag, "增量同步失败: ${e.message}", e)
                currentSyncStatus = SyncStatus.FAILED
                syncStats.failedSyncCount++
                syncStats.lastSyncStatus = SyncStatus.FAILED
                syncStats.lastErrorMessage = e.message ?: "未知错误"

                return buildJsonObject {
                    put("status", JsonPrimitive("failed"))
                    put("error", JsonPrimitive("增量同步失败: ${e.message}"))
                }.toString()
            }
        }
    }

    // ============================================================
    // 核心方法：同步调度
    // ============================================================

    /**
     * 设置并启动定时同步调度。
     *
     * 调度功能说明：
     * - 根据 [SyncSchedule] 配置创建定时任务
     * - 使用 [ScheduledExecutorService] 以固定间隔执行同步
     * - 支持动态调整调度参数
     * - 自动处理重试逻辑
     * - 线程安全，可随时停止或重启
     *
     * 注意：本方法仅创建调度任务，实际同步逻辑需要上层调用 [incrementalSync] 执行。
     * 调度器会触发同步回调，由上层实现具体的同步网络请求。
     *
     * @param schedule 同步调度配置，为 null 时使用默认配置
     * @param syncCallback 同步执行回调，当调度触发时调用
     */
    fun scheduleSync(
        schedule: SyncSchedule? = null,
        syncCallback: ((String, DataCategory) -> String)? = null
    ) {
        try {
            val effectiveSchedule = schedule ?: SyncSchedule()
            syncScheduleRef.set(effectiveSchedule)

            if (!effectiveSchedule.enabled) {
                Log.d(tag, "同步调度已禁用")
                stopScheduler()
                return
            }

            val intervalMinutes = effectiveSchedule.intervalHours * 60 + effectiveSchedule.intervalMinutes
            if (intervalMinutes < MIN_SYNC_INTERVAL_MINUTES) {
                Log.w(tag, "同步间隔过短(${intervalMinutes}分钟)，使用最小间隔 ${MIN_SYNC_INTERVAL_MINUTES} 分钟")
                effectiveSchedule.intervalMinutes = MIN_SYNC_INTERVAL_MINUTES
                effectiveSchedule.intervalHours = 0
            }

            stopScheduler()

            val newScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                val thread = Thread(runnable, "DataSync-Scheduler")
                thread.isDaemon = true
                thread
            }
            scheduler = newScheduler

            // 计算首次执行延迟（如果指定了首选开始时间）
            val initialDelay = calculateInitialDelay(effectiveSchedule.preferredStartHour)

            val effectiveIntervalMinutes = maxOf(
                effectiveSchedule.intervalHours * 60L + effectiveSchedule.intervalMinutes,
                MIN_SYNC_INTERVAL_MINUTES.toLong()
            )

            newScheduler.scheduleAtFixedRate(
                {
                    executeScheduledSync(effectiveSchedule, syncCallback)
                },
                initialDelay,
                TimeUnit.MINUTES.toMillis(effectiveIntervalMinutes),
                TimeUnit.MILLISECONDS
            )

            Log.d(tag, "同步调度已启动: 间隔=${effectiveIntervalMinutes}分钟, " +
                    "首延迟=${initialDelay}ms, 类别=${effectiveSchedule.dataCategories.size}个")
        } catch (e: Exception) {
            Log.e(tag, "启动同步调度失败: ${e.message}", e)
        }
    }

    /**
     * 停止定时同步调度器。
     * 调用此方法后，所有定时同步任务将被取消并清理。
     */
    fun stopScheduler() {
        try {
            scheduler?.let {
                it.shutdownNow()
                if (!it.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.w(tag, "调度器未能在 5 秒内终止")
                }
            }
            scheduler = null
            Log.d(tag, "同步调度器已停止")
        } catch (e: Exception) {
            Log.e(tag, "停止调度器失败: ${e.message}", e)
        }
    }

    // ============================================================
    // 核心方法：获取同步统计
    // ============================================================

    /**
     * 获取同步统计信息 —— 返回当前同步操作的完整统计快照。
     *
     * 统计信息包括：
     * - 累计同步次数、成功次数、失败次数
     * - 字节传输量（上传/下载）
     * - 数据项传输量（上传/下载）
     * - 冲突检测次数
     * - 平均同步耗时
     * - 最后同步状态和错误信息
     *
     * @return [SyncStats] 当前同步统计信息的副本
     */
    fun getSyncStats(): SyncStats {
        return syncStats.copy(
            dataCategoryStats = ConcurrentHashMap(syncStats.dataCategoryStats)
        )
    }

    /**
     * 获取最后同步时间 —— 返回最近一次同步操作的时间戳。
     *
     * @return 最后同步时间戳（毫秒），从未同步时返回 0
     */
    fun getLastSyncTime(): Long {
        return syncStats.lastSyncTime
    }

    /**
     * 获取数据版本号 —— 返回当前全局数据版本号。
     * 版本号在每次数据变更时单调递增，用于同步兼容性检查。
     *
     * @return 当前全局数据版本号
     */
    fun getDataVersion(): Long {
        return globalDataVersion.get()
    }

    /**
     * 设置同步调度配置 —— 动态更新当前同步调度参数。
     *
     * 如果调度器正在运行，会先停止现有调度器，再使用新配置重新启动。
     *
     * @param schedule 新的同步调度配置
     * @param syncCallback 可选的同步执行回调
     */
    fun setSyncSchedule(
        schedule: SyncSchedule,
        syncCallback: ((String, DataCategory) -> String)? = null
    ) {
        Log.d(tag, "更新同步调度: 间隔=${schedule.intervalHours}h${schedule.intervalMinutes}m, " +
                "启用=${schedule.enabled}, 策略=${schedule.strategy.name}")
        scheduleSync(schedule, syncCallback)
    }

    // ============================================================
    // 公共辅助方法
    // ============================================================

    /**
     * 存储数据 —— 将数据项存入同步管理器。
     *
     * @param data 要存储的 [SyncData] 数据项
     * @return 存储成功返回 true，失败返回 false
     */
    fun putData(data: SyncData): Boolean {
        try {
            val category = data.category
            if (category == DataCategory.ALL) {
                Log.w(tag, "不允许将数据分类为 ALL，请指定具体类别")
                return false
            }

            val store = dataStore.getOrPut(category) { ConcurrentHashMap() }
            synchronized(store) {
                if (store.size >= MAX_DATA_ITEMS_PER_CATEGORY && !store.containsKey(data.key)) {
                    Log.w(tag, "类别 ${category.name} 数据项已达上限 ($MAX_DATA_ITEMS_PER_CATEGORY)")
                    return false
                }
                store[data.key] = data
            }

            totalDataItemsTracked.incrementAndGet()
            val newVersion = globalDataVersion.incrementAndGet()
            val newHash = computeSha256(data.value.toString())

            recordChange(category, data.key, ChangeType.CREATE, "", newHash, newVersion)
            notifyDataChange(category, data.key, data, "CREATE", System.currentTimeMillis())

            return true
        } catch (e: Exception) {
            Log.e(tag, "存储数据失败: ${e.message}", e)
            return false
        }
    }

    /**
     * 获取数据 —— 从同步管理器中获取指定数据项。
     *
     * @param category 数据类别
     * @param key      数据键名
     * @return [SyncData] 数据项，不存在返回 null
     */
    fun getData(category: DataCategory, key: String): SyncData? {
        val store = dataStore[category] ?: return null
        synchronized(store) {
            return store[key]
        }
    }

    /**
     * 删除数据 —— 从同步管理器中删除指定数据项。
     *
     * @param category 数据类别
     * @param key      数据键名
     * @return 删除成功返回 true，数据不存在返回 false
     */
    fun deleteData(category: DataCategory, key: String): Boolean {
        val store = dataStore[category] ?: return false
        synchronized(store) {
            val removed = store.remove(key)
            if (removed != null) {
                recordChange(category, key, ChangeType.DELETE, removed.hash, "", globalDataVersion.get())
                notifyDataChange(category, key, null, "DELETE", System.currentTimeMillis())
                return true
            }
            return false
        }
    }

    /**
     * 获取所有待解决的冲突列表。
     *
     * @return 冲突列表，无冲突时返回空列表
     */
    fun getPendingConflicts(): List<SyncConflict> {
        return conflictStore.values.toList()
    }

    /**
     * 获取已解决的冲突历史记录。
     *
     * @return 已解决的冲突列表
     */
    fun getResolvedConflicts(): List<SyncConflict> {
        return resolvedConflictHistory.values.toList()
    }

    /**
     * 获取备份历史列表。
     *
     * @return 备份元数据列表，按时间倒序排列
     */
    fun getBackupHistory(): List<BackupMetadata> {
        val backups = mutableListOf<BackupMetadata>()
        try {
            val dir = File(backupDir)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { file ->
                    file.name.startsWith(BACKUP_FILE_PREFIX) && file.name.endsWith(BACKUP_FILE_EXTENSION)
                }
                if (files != null) {
                    for (file in files.sortedByDescending { it.lastModified() }) {
                        try {
                            val jsonText = file.readText()
                            val root = jsonConfig.parseToJsonElement(jsonText).jsonObject
                            val metadataObj = root["metadata"]?.jsonObject
                            if (metadataObj != null) {
                                val metadata = BackupMetadata(
                                    backupId = metadataObj["backupId"]?.jsonPrimitive?.content ?: "",
                                    backupTimestamp = metadataObj["backupTimestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                                        ?: 0L,
                                    dataCategory = try {
                                        DataCategory.valueOf(
                                            metadataObj["dataCategory"]?.jsonPrimitive?.content ?: "ALL"
                                        )
                                    } catch (e: IllegalArgumentException) {
                                        DataCategory.ALL
                                    },
                                    itemCount = metadataObj["itemCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                                    totalSize = metadataObj["totalSize"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                                    backupVersion = metadataObj["backupVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                                    appVersion = metadataObj["appVersion"]?.jsonPrimitive?.content ?: "",
                                    deviceId = metadataObj["deviceId"]?.jsonPrimitive?.content ?: "",
                                    androidVersion = metadataObj["androidVersion"]?.jsonPrimitive?.content ?: "",
                                    checksum = metadataObj["checksum"]?.jsonPrimitive?.content ?: "",
                                    isEncrypted = metadataObj["isEncrypted"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                                    compressionType = metadataObj["compressionType"]?.jsonPrimitive?.content ?: "none",
                                    notes = metadataObj["notes"]?.jsonPrimitive?.content ?: ""
                                )
                                backups.add(metadata)
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "读取备份文件失败: ${file.name}, ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "获取备份历史失败: ${e.message}", e)
        }
        return backups
    }

    /**
     * 注册数据变更侦听器。
     *
     * @param listener 侦听器实例
     */
    fun registerChangeListener(listener: OnDataChangeListener) {
        if (!changeListeners.contains(listener)) {
            changeListeners.add(listener)
            Log.d(tag, "注册数据变更侦听器: ${listener.javaClass.simpleName}")
        }
    }

    /**
     * 注销数据变更侦听器。
     *
     * @param listener 侦听器实例
     */
    fun unregisterChangeListener(listener: OnDataChangeListener) {
        changeListeners.remove(listener)
        Log.d(tag, "注销数据变更侦听器: ${listener.javaClass.simpleName}")
    }

    /**
     * 获取当前同步状态。
     *
     * @return 当前 [SyncStatus]
     */
    fun getCurrentSyncStatus(): SyncStatus = currentSyncStatus

    /**
     * 获取当前同步的清单 ID。
     *
     * @return 当前清单 ID，无正在进行的同步时返回空字符串
     */
    fun getCurrentManifestId(): String = currentManifestId

    /**
     * 获取被追踪的数据项总数。
     *
     * @return 数据项总数
     */
    fun getTotalDataItems(): Int = totalDataItemsTracked.get()

    /**
     * 获取指定类别的数据项数量。
     *
     * @param category 数据类别
     * @return 数据项数量
     */
    fun getDataItemCount(category: DataCategory): Int {
        val store = dataStore[category] ?: return 0
        synchronized(store) {
            return store.size
        }
    }

    /**
     * 清除所有数据。
     *
     * 警告：此操作不可逆，会清空所有存储的数据、变更日志、冲突记录。
     * 建议在清除前先执行一次全量备份。
     */
    fun clearAllData() {
        rwLock.writeLock().lock()
        try {
            dataStore.clear()
            manifestStore.clear()
            conflictStore.clear()
            resolvedConflictHistory.clear()
            changeLog.clear()
            globalDataVersion.set(1L)
            currentSyncStatus = SyncStatus.IDLE
            currentManifestId = ""
            totalDataItemsTracked.set(0)
            Log.d(tag, "所有数据已清除")
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    // ============================================================
    // 私有辅助方法
    // ============================================================

    /**
     * 记录数据变更到变更日志。
     * 用于增量同步时追踪变更历史。
     *
     * @param category   数据类别
     * @param key        数据键名
     * @param changeType 变更类型
     * @param oldHash    变更前的哈希值
     * @param newHash    变更后的哈希值
     * @param version    变更时的版本号
     */
    private fun recordChange(
        category: DataCategory,
        key: String,
        changeType: ChangeType,
        oldHash: String,
        newHash: String,
        version: Long
    ) {
        val record = ChangeRecord(
            category = category,
            key = key,
            changeType = changeType,
            oldHash = oldHash,
            newHash = newHash,
            version = version
        )
        changeLog.addLast(record)

        // 限制变更日志大小
        while (changeLog.size > maxChangeLogSize) {
            changeLog.pollFirst()
        }
    }

    /**
     * 通知数据变更侦听器。
     *
     * @param category   数据类别
     * @param key        数据键名
     * @param newData    变更后的数据
     * @param changeType 变更类型
     * @param timestamp  变更时间戳
     */
    private fun notifyDataChange(
        category: DataCategory,
        key: String,
        newData: SyncData?,
        changeType: String,
        timestamp: Long
    ) {
        for (listener in changeListeners) {
            try {
                listener.onDataChanged(category, key, newData, changeType, timestamp)
            } catch (e: Exception) {
                Log.e(tag, "通知数据变更失败: ${e.message}", e)
            }
        }
    }

    /**
     * 计算数据的 SHA-256 哈希值。
     *
     * @param content 要计算哈希的字符串
     * @return 十六进制格式的 SHA-256 哈希字符串
     */
    private fun computeSha256(content: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(content.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(tag, "计算 SHA-256 失败: ${e.message}")
            content.hashCode().toLong().toString(16)
        }
    }

    /**
     * 计算一组数据项的校验和。
     * 将所有数据项的键名和哈希值拼接后计算 SHA-256。
     *
     * @param items 数据项列表
     * @return 校验和字符串
     */
    private fun computeChecksum(items: List<SyncData>): String {
        val sortedItems = items.sortedBy { it.key }
        val sb = StringBuilder()
        for (item in sortedItems) {
            sb.append(item.key).append(":").append(item.hash).append("|")
        }
        return computeSha256(sb.toString())
    }

    /**
     * 验证数据项列表的校验和是否与存储的校验和一致。
     *
     * @param items         数据项列表
     * @param storedChecksum 存储的校验和
     * @return 校验通过返回 true
     */
    private fun verifyChecksum(items: List<SyncData>, storedChecksum: String): Boolean {
        if (storedChecksum.isBlank()) {
            Log.w(tag, "校验和为空，跳过校验")
            return true
        }
        val computed = computeChecksum(items)
        val match = computed == storedChecksum
        if (!match) {
            Log.w(tag, "校验和不匹配: 计算值=$computed, 存储值=$storedChecksum")
        }
        return match
    }

    /**
     * 尝试合并两个 JSON 值。
     *
     * 合并规则：
     * - 如果两个值都是 [JsonObject]，则逐键合并，远程值覆盖本地值
     * - 如果至少一个值是 [JsonPrimitive]，以远程值为准
     * - 其他情况以远程值为准
     *
     * @param localValue  本地值
     * @param remoteValue 远程值
     * @return 合并后的 [JsonElement]
     */
    private fun tryMergeValues(localValue: JsonElement, remoteValue: JsonElement): JsonElement {
        return when {
            localValue is JsonObject && remoteValue is JsonObject -> {
                val merged = localValue.toMutableMap()
                for ((key, value) in remoteValue) {
                    val localSub = merged[key]
                    if (localSub != null) {
                        merged[key] = tryMergeValues(localSub, value)
                    } else {
                        merged[key] = value
                    }
                }
                JsonObject(merged)
            }
            else -> remoteValue
        }
    }

    /**
     * 查找最新的备份文件。
     *
     * @param category 数据类别
     * @return 最新的备份 [File]，不存在返回 null
     */
    private fun findLatestBackupFile(category: DataCategory): File? {
        try {
            val dir = File(backupDir)
            if (!dir.exists() || !dir.isDirectory) return null

            val files = dir.listFiles { file ->
                file.name.startsWith(BACKUP_FILE_PREFIX) && file.name.endsWith(BACKUP_FILE_EXTENSION)
            } ?: return null

            // 按文件最后修改时间降序排列
            val sortedFiles = files.sortedByDescending { it.lastModified() }

            if (category == DataCategory.ALL) {
                return sortedFiles.firstOrNull()
            }

            // 遍历文件，查找匹配类别的备份
            for (file in sortedFiles) {
                try {
                    val jsonText = file.readText()
                    val root = jsonConfig.parseToJsonElement(jsonText).jsonObject
                    val metadataObj = root["metadata"]?.jsonObject
                    if (metadataObj != null) {
                        val fileCategory = metadataObj["dataCategory"]?.jsonPrimitive?.content ?: ""
                        if (fileCategory == category.name || fileCategory == "ALL") {
                            return file
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            return sortedFiles.firstOrNull()
        } catch (e: Exception) {
            Log.e(tag, "查找最新备份文件失败: ${e.message}", e)
            return null
        }
    }

    /**
     * 清理旧备份文件，确保不超过最大保留数量。
     * 保留最近的 [MAX_BACKUP_FILES] 个备份。
     */
    private fun cleanupOldBackups() {
        try {
            val dir = File(backupDir)
            if (!dir.exists() || !dir.isDirectory) return

            val files = dir.listFiles { file ->
                file.name.startsWith(BACKUP_FILE_PREFIX) && file.name.endsWith(BACKUP_FILE_EXTENSION)
            }?.sortedByDescending { it.lastModified() } ?: return

            if (files.size > MAX_BACKUP_FILES) {
                val filesToDelete = files.drop(MAX_BACKUP_FILES)
                for (file in filesToDelete) {
                    if (file.delete()) {
                        Log.d(tag, "删除旧备份: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "清理旧备份失败: ${e.message}", e)
        }
    }

    /**
     * 计算首次调度延迟。
     * 如果配置了首选开始时间，则计算当前时间到下次首选开始时间的延迟。
     *
     * @param preferredStartHour 首选开始时间（小时，0-23）
     * @return 延迟毫秒数
     */
    private fun calculateInitialDelay(preferredStartHour: Int): Long {
        val now = System.currentTimeMillis()
        val calendar = java.util.Calendar.getInstance()
        val currentHour = calendar.get(java.util.Calendar.HOUR_OF_DAY)

        return if (preferredStartHour in 0..23 && currentHour < preferredStartHour) {
            // 如果当前时间早于首选开始时间，延迟到首选开始时间
            calendar.set(java.util.Calendar.HOUR_OF_DAY, preferredStartHour)
            calendar.set(java.util.Calendar.MINUTE, 0)
            calendar.set(java.util.Calendar.SECOND, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            maxOf(calendar.timeInMillis - now, 60_000L) // 至少延迟 1 分钟
        } else {
            // 立即开始（延迟 1 分钟给初始化留出时间）
            60_000L
        }
    }

    /**
     * 执行定时同步任务。
     * 由调度器在后台线程中调用。
     *
     * @param schedule     同步调度配置
     * @param syncCallback 同步执行回调
     */
    private fun executeScheduledSync(
        schedule: SyncSchedule,
        syncCallback: ((String, DataCategory) -> String)?
    ) {
        try {
            Log.d(tag, "执行定时同步任务")

            if (currentSyncStatus == SyncStatus.SYNCING) {
                Log.w(tag, "上一次同步尚未完成，跳过本次调度")
                return
            }

            val categories = if (schedule.dataCategories.isEmpty()) {
                DEFAULT_SYNC_CATEGORIES
            } else {
                schedule.dataCategories
            }

            for (category in categories) {
                if (syncCallback != null) {
                    val syncResult = syncCallback.invoke("scheduled_sync_${category.name}", category)
                    Log.d(tag, "定时同步完成: 类别=${category.name}, 结果摘要=${syncResult.take(100)}")
                } else {
                    Log.d(tag, "定时同步触发: 类别=${category.name}（无回调）")
                }
            }

            syncStats.syncRatePerDay = if (syncStats.totalSyncCount > 0) {
                val elapsedDays = (System.currentTimeMillis() - syncStats.firstSyncTime) / (24 * 60 * 60 * 1000f)
                if (elapsedDays > 0) syncStats.totalSyncCount / elapsedDays else 0f
            } else {
                0f
            }

            Log.d(tag, "定时同步任务执行完成")
        } catch (e: Exception) {
            Log.e(tag, "执行定时同步任务失败: ${e.message}", e)
        }
    }

    /**
     * 获取变更日志的快照。
     * 用于调试和增量同步分析。
     *
     * @param maxRecords 最多返回的记录数
     * @return 变更记录列表
     */
    private fun getChangeLogSnapshot(maxRecords: Int = 100): List<ChangeRecord> {
        return changeLog.toList().takeLast(maxRecords)
    }

    /**
     * 获取数据存储的完整快照（用于调试）。
     *
     * @return 按类别分组的键值对映射
     */
    private fun getDataStoreSnapshot(): Map<DataCategory, Map<String, String>> {
        val snapshot = mutableMapOf<DataCategory, Map<String, String>>()
        rwLock.readLock().lock()
        try {
            for ((category, store) in dataStore) {
                val items = mutableMapOf<String, String>()
                synchronized(store) {
                    for ((key, data) in store) {
                        items[key] = "[版本=${data.version}, 哈希=${data.hash.take(8)}..., " +
                                "时间=${formatTimestamp(data.lastModified)}]"
                    }
                }
                snapshot[category] = items
            }
        } finally {
            rwLock.readLock().unlock()
        }
        return snapshot
    }

    /**
     * 格式化时间戳为可读字符串。
     *
     * @param timestamp 时间戳（毫秒）
     * @return 格式化后的时间字符串
     */
    private fun formatTimestamp(timestamp: Long): String {
        return if (timestamp > 0) {
            timeFormatter.format(Date(timestamp))
        } else {
            "N/A"
        }
    }

    // ============================================================
    // 调试和诊断
    // ============================================================

    /**
     * 获取调试信息 —— 返回当前同步管理器的完整状态摘要。
     * 用于诊断和监控。
     *
     * @return 调试信息字符串
     */
    fun getDiagnosticInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("===== DataSyncManager 诊断信息 =====")
        sb.appendLine("设备ID: $deviceId")
        sb.appendLine("应用版本: $appVersion")
        sb.appendLine("备份目录: $backupDir")
        sb.appendLine("当前同步状态: ${currentSyncStatus.name}")
        sb.appendLine("当前清单ID: $currentManifestId")
        sb.appendLine("全局数据版本: ${globalDataVersion.get()}")

        sb.appendLine()
        sb.appendLine("--- 数据存储统计 ---")
        for (category in DataCategory.entries) {
            if (category == DataCategory.ALL) continue
            val count = getDataItemCount(category)
            sb.appendLine("  ${category.name}: $count 项")
        }
        sb.appendLine("  总计: ${totalDataItemsTracked.get()} 项")

        sb.appendLine()
        sb.appendLine("--- 同步统计 ---")
        sb.appendLine("  总同步次数: ${syncStats.totalSyncCount}")
        sb.appendLine("  成功次数: ${syncStats.successfulSyncCount}")
        sb.appendLine("  失败次数: ${syncStats.failedSyncCount}")
        sb.appendLine("  冲突次数: ${syncStats.conflictCount}")
        sb.appendLine("  上传字节: ${syncStats.bytesUploaded}")
        sb.appendLine("  下载字节: ${syncStats.bytesDownloaded}")
        sb.appendLine("  最后同步: ${formatTimestamp(syncStats.lastSyncTime)}")
        sb.appendLine("  平均耗时: ${syncStats.averageSyncDurationMs}ms")

        sb.appendLine()
        sb.appendLine("--- 变更日志 ---")
        sb.appendLine("  当前大小: ${changeLog.size} / $maxChangeLogSize")
        val recentChanges = getChangeLogSnapshot(10)
        for (change in recentChanges.reversed()) {
            sb.appendLine("  [${formatTimestamp(change.timestamp)}] ${change.category.name}:${change.key} " +
                    "[${change.changeType.name}] 版本=${change.version}")
        }

        sb.appendLine()
        sb.appendLine("--- 待解决冲突 ---")
        val pendingConflicts = getPendingConflicts()
        sb.appendLine("  待解决: ${pendingConflicts.size}")
        for (conflict in pendingConflicts.take(5)) {
            sb.appendLine("  ${conflict.conflictId}: ${conflict.category.name}:${conflict.key} " +
                    "[本地v${conflict.localVersion} vs 远程v${conflict.remoteVersion}]")
        }

        sb.appendLine()
        sb.appendLine("--- 调度信息 ---")
        val schedule = syncScheduleRef.get()
        sb.appendLine("  启用: ${schedule.enabled}")
        sb.appendLine("  间隔: ${schedule.intervalHours}h${schedule.intervalMinutes}m")
        sb.appendLine("  策略: ${schedule.strategy.name}")
        sb.appendLine("  增量同步: ${schedule.enableIncrementalSync}")
        sb.appendLine("  调度器运行中: ${scheduler != null && !scheduler!!.isShutdown}")

        sb.appendLine()
        sb.appendLine("--- 统计计数 ---")
        sb.appendLine("  备份次数: ${totalBackupCount.get()}")
        sb.appendLine("  恢复次数: ${totalRestoreCount.get()}")
        sb.appendLine("  冲突解决次数: ${totalResolvedCount.get()}")
        sb.appendLine("  同步数据准备次数: ${totalPrepareCount.get()}")
        sb.appendLine("  增量同步次数: ${totalIncrementalSyncCount.get()}")
        sb.appendLine("  侦听器数量: ${changeListeners.size}")

        sb.appendLine("===== 诊断结束 =====")
        return sb.toString()
    }

    /**
     * 释放资源 —— 清理调度器和其他资源。
     * 在应用销毁时调用。
     */
    fun destroy() {
        Log.d(tag, "销毁 DataSyncManager")
        stopScheduler()
        changeListeners.clear()
        clearAllData()
    }
}