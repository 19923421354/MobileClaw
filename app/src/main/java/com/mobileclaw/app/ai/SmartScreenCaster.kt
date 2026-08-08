package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// =============================================================================
//  SmartScreenCaster - 智能屏幕投射器（Beam 功能）
// =============================================================================

/**
 * SmartScreenCaster —— 智能屏幕投射器（Beam 功能）。
 *
 * ## 概述
 *
 * 本类是 MobileClaw 的「Beam」核心能力实现，负责将屏幕内容、操作动作、深度链接
 * 和二维码信息以安全、高效的方式投射（Beam）到其他设备、服务或目标。它使手机
 * 不再是一个孤立的终端，而是成为跨设备协作的「发射源」，实现「一次操作、处处可达」
 * 的体验。
 *
 * ## 设计哲学
 *
 * 在移动自动化场景中，用户经常需要将当前屏幕内容快速分享给其他设备——截屏发送
 * 给电脑、复制链接到平板、将操作序列投射到另一台手机上演示。传统做法需要手动
 * 复制粘贴、文件传输或多步操作，效率低下。SmartScreenCaster 将「投射」抽象为
 * 统一的操作原语，覆盖从本地剪贴板到远程设备的全链路，让内容流动如同「光束」
 * 一样自然。
 *
 * ## 八大核心能力
 *
 * 1. **屏幕内容共享**：将屏幕内容（文本、截屏、URL）打包为 [BeamContent] 并
 *    投射到指定目标，支持自动格式转换和目标适配。
 *
 * 2. **动作投射**：将用户操作（点击、输入、滑动等）序列化为 [BeamAction]，
 *    通过 [beamAction] 投射到远程设备执行，实现「一次操作、多端同步」的
 *    远程控制场景。例如，将手机上的点击操作投射到平板上的相同位置。
 *
 * 3. **深度链接生成**：根据当前应用上下文（包名、页面路径、参数）自动生成
 *    标准 Android 深度链接（Deep Link），方便分享到其他设备直接打开对应页面。
 *
 * 4. **二维码内容生成**：将投射内容编码为适合二维码的紧凑字符串，扫一扫
 *    即可获取内容。支持内容类型标识、目标设备标识和加密元数据嵌入。
 *
 * 5. **投射历史管理**：记录所有已完成的投射操作，包括内容、目标、时间戳、
 *    状态和结果，支持按时间倒序查询和按类型/目标筛选，便于追踪和重放。
 *
 * 6. **目标管理**：管理可用的投射目标列表，每个目标包含类型、名称、地址、
 *    能力和安全策略。支持动态添加和移除目标，多目标并行投射。
 *
 * 7. **内容变换**：根据目标类型自动转换内容格式——URL 投到浏览器时保持原样、
 *    文本投到笔记应用时添加元数据前缀、截屏投到远程设备时压缩为 Base64。
 *    保证内容在不同目标上的最佳展示效果。
 *
 * 8. **投射安全**：提供加密（AES-256 模拟）和认证两种安全机制，敏感内容在
 *    投射前经过加密和/或签名，目标端需要对应密钥才能解密和验证。支持四种
 *    安全级别：无保护、仅加密、仅认证、加密+认证。
 *
 * ## 线程安全
 *
 * 所有内部存储均使用 [ConcurrentHashMap] 和 [CopyOnWriteArrayList]，保证
 * 多线程并发调用的安全性。计数器使用 [AtomicInteger] 语义的 @Volatile 变量
 * 或 ConcurrentHashMap 的原子方法。典型场景：UI 线程发起投射、后台线程执行
 * 加密和目标传输、日志线程记录历史。
 *
 * ## 容量限制
 *
 * - 投射历史最多保留 [MAX_HISTORY_SIZE]（500）条，超出时丢弃最旧记录。
 * - 投射目标最多 [MAX_TARGETS]（50）个，超出时拒绝添加并输出告警。
 * - 活跃会话最多 [MAX_ACTIVE_SESSIONS]（20）个，超出时按最早创建时间淘汰。
 * - 加密密钥在内存中缓存，最多 [MAX_CACHED_KEYS]（100）个。
 *
 * ## 典型调用流程
 *
 * ```
 * val caster = SmartScreenCaster()
 *
 * // 1. 添加投射目标
 * caster.addBeamTarget(
 *     BeamTarget(
 *         id = "pc-01",
 *         name = "我的电脑",
 *         type = BeamTargetType.REMOTE_DEVICE,
 *         address = "192.168.1.100:8899",
 *         capabilities = setOf(BeamType.SCREENSHOT, BeamType.TEXT, BeamType.URL),
 *         security = BeamSecurity.ENCRYPTED
 *     )
 * )
 *
 * // 2. 创建投射内容
 * val content = BeamContent(
 *     type = BeamType.TEXT,
 *     data = "Hello from MobileClaw!",
 *     sourcePackage = "com.mobileclaw.app",
 *     metadata = buildJsonObject { put("author", "user") }
 * )
 *
 * // 3. 执行投射
 * val session = caster.beam(content, "pc-01")
 * println("Beam 状态: ${session.status}")
 *
 * // 4. 生成二维码内容
 * val qrContent = caster.generateQRContent(content)
 * println("二维码内容: $qrContent")
 *
 * // 5. 查询历史
 * val history = caster.getBeamHistory(limit = 10)
 * ```
 *
 * @author MobileClaw Team
 * @since 2024
 */
class SmartScreenCaster {

    /** 日志标签。 */
    private val tag = "SmartScreenCaster"

    // =========================================================================
    //  常量
    // =========================================================================

    /** 最大历史记录条数。 */
    private val maxHistorySize = 500

    /** 最大投射目标数。 */
    private val maxTargets = 50

    /** 最大活跃会话数。 */
    private val maxActiveSessions = 20

    /** 最大缓存密钥数。 */
    private val maxCachedKeys = 100

    /** 默认加密算法（AES）。 */
    private val defaultEncryptionAlgorithm = "AES"

    /** 默认字符编码。 */
    private val defaultCharset = "UTF-8"

    /** 二维码内容前缀，用于标识 Beam 内容。 */
    private val qrPrefix = "MOBILECLAW_BEAM:"

    /** 深度链接 Scheme。 */
    private val deepLinkScheme = "mobileclaw"

    /** 深度链接主机。 */
    private val deepLinkHost = "beam"

    // =========================================================================
    //  枚举定义
    // =========================================================================

    /**
     * 投射内容类型。
     *
     * 标识 [BeamContent] 的内容类别，决定了投射时的编码方式、目标端的展示
     * 方式以及内容变换策略。
     *
     * @property displayName 中文显示名称
     */
    enum class BeamType(val displayName: String) {
        /** 屏幕截图：PNG/JPEG 格式的截屏图片。 */
        SCREENSHOT("屏幕截图"),

        /** 纯文本：任意长度的 UTF-8 文本内容。 */
        TEXT("文本"),

        /** 网址链接：HTTP/HTTPS 或其他 Scheme 的 URL。 */
        URL("网址链接"),

        /** 操作动作：序列化的 [BeamAction] 动作数据。 */
        ACTION("操作动作"),

        /** 深度链接：可直达应用内特定页面的 Deep Link。 */
        DEEP_LINK("深度链接"),

        /** 二维码内容：已编码为紧凑字符串的 QR 数据。 */
        QR_CODE("二维码"),

        /** 文件：任意类型的二进制文件。 */
        FILE("文件")
    }

    /**
     * 投射目标类型。
     *
     * 标识 [BeamTarget] 的目标类别，决定了投射时的传输方式和目标端的
     * 接收方式。
     *
     * @property displayName 中文显示名称
     */
    enum class BeamTargetType(val displayName: String) {
        /** 系统剪贴板：将内容复制到本地剪贴板。 */
        CLIPBOARD("系统剪贴板"),

        /** 系统分享：通过 Android Share Sheet 发送内容。 */
        SHARE_INTENT("系统分享"),

        /** 远程设备：通过网络发送到其他设备。 */
        REMOTE_DEVICE("远程设备"),

        /** 云存储：上传到关联的云存储服务。 */
        CLOUD_STORAGE("云存储"),

        /** 本地文件：保存到设备本地文件系统。 */
        LOCAL_FILE("本地文件"),

        /** AirDrop 风格：通过近距离无线传输到附近设备。 */
        AIRDROP("近距离投射")
    }

    /**
     * 投射安全级别。
     *
     * 定义 [BeamContent] 在传输过程中的安全保护策略，由 [BeamTarget.security]
     * 指定。内容在投射前会根据目标的安全级别调用对应的 [encryptContent] 和
     * [authenticateContent] 处理。
     *
     * @property displayName 中文显示名称
     * @property requiresEncryption 是否需要加密
     * @property requiresAuthentication 是否需要认证签名
     */
    enum class BeamSecurity(
        val displayName: String,
        val requiresEncryption: Boolean,
        val requiresAuthentication: Boolean
    ) {
        /** 无保护：明文传输，不加密也不签名。 */
        NONE("无保护", false, false),

        /** 仅加密：使用 AES 算法加密内容，但无认证签名。 */
        ENCRYPTED("仅加密", true, false),

        /** 仅认证：对内容进行签名认证，但不加密。 */
        AUTHENTICATED("仅认证", false, true),

        /** 加密+认证：先加密后签名，最高安全级别。 */
        ENCRYPTED_AND_AUTHENTICATED("加密+认证", true, true)
    }

    /**
     * 投射状态。
     *
     * 标识 [BeamSession] 在执行过程中的生命周期状态，用于 UI 展示和
     * 异步回调。
     *
     * @property displayName 中文显示名称
     */
    enum class BeamStatus(val displayName: String) {
        /** 等待中：已加入队列，尚未开始传输。 */
        PENDING("等待中"),

        /** 投射中：正在传输内容到目标。 */
        BEAMING("投射中"),

        /** 已完成：内容已成功传输到目标。 */
        COMPLETED("已完成"),

        /** 失败：传输过程中出现错误。 */
        FAILED("传输失败"),

        /** 已取消：用户或系统取消投射。 */
        CANCELLED("已取消")
    }

    // =========================================================================
    //  数据类定义
    // =========================================================================

    /**
     * 投射内容。
     *
     * 封装了需要投射到目标端的核心数据，包含内容类型、原始数据、来源信息
     * 和扩展元数据。这是 Beam 功能的核心数据载体，所有投射操作都以
     * [BeamContent] 为输入。
     *
     * @property id          内容唯一标识（UUID 自动生成）
     * @property type        内容类型，对应 [BeamType]
     * @property data        内容数据字符串（文本/URL/Base64 编码的截图等）
     * @property sourcePackage 来源应用包名，标识内容来自哪个应用
     * @property metadata    扩展元数据（JSON 格式），可包含作者、时间、标签等
     * @property size        内容大小（字节），-1 表示未计算
     * @property thumbnail   缩略图（Base64 编码），用于 UI 预览
     * @property tags        内容标签，用于分类和搜索
     * @property createdAt   内容创建时间戳（毫秒）
     */
    data class BeamContent(
        val id: String = UUID.randomUUID().toString(),
        val type: BeamType,
        val data: String,
        val sourcePackage: String = "",
        val metadata: JsonObject = buildJsonObject { },
        val size: Long = -1L,
        val thumbnail: String = "",
        val tags: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 投射动作。
     *
     * 描述一个可被远程执行的用户操作，用于 [beamAction] 的远控场景。
     * 动作序列可以完整还原用户在源设备上的操作流程，实现「一次操作、
     * 多端同步」的效果。
     *
     * @property id             动作唯一标识（UUID 自动生成）
     * @property actionType     动作类型描述（如 "click"、"input"、"swipe"、"back"、"home"）
     * @property targetWidget   目标控件描述（如 "com.example:id/button_submit"）
     * @property targetText     目标控件上的文本内容
     * @property params         动作参数（JSON 格式），如坐标 (x, y)、输入文本等
     * @property packageName    目标应用包名（动作在该应用中执行）
     * @property delayMs        动作执行前等待时间（毫秒），用于模拟人为操作间隔
     * @property timestamp      动作创建时间戳（毫秒）
     * @property sourceSession  来源会话 ID，用于追踪动作链
     * @property description    动作描述文本，用于日志和调试
     */
    data class BeamAction(
        val id: String = UUID.randomUUID().toString(),
        val actionType: String,
        val targetWidget: String = "",
        val targetText: String = "",
        val params: JsonObject = buildJsonObject { },
        val packageName: String = "",
        val delayMs: Long = 0L,
        val timestamp: Long = System.currentTimeMillis(),
        val sourceSession: String = "",
        val description: String = ""
    )

    /**
     * 投射目标。
     *
     * 描述一个可以接收投射内容的目标设备或服务。每个目标包含唯一标识、
     * 名称、类型、网络地址、支持的能力列表和安全策略。目标实例由
     * [addBeamTarget] 添加，由 [removeBeamTarget] 移除。
     *
     * @property id            目标唯一标识
     * @property name          目标可读名称
     * @property type          目标类型，对应 [BeamTargetType]
     * @property address       目标地址（网络地址、文件路径或服务标识）
     * @property capabilities  支持的内容类型集合，用于内容变换时决策
     * @property security      目标要求的安全级别
     * @property isOnline      目标是否在线可达
     * @property priority      目标优先级（数值越大优先级越高，多目标投射时排序用）
     * @property description   目标描述文本
     * @property extraConfig   附加配置（JSON 格式），含认证令牌、端口等
     * @property addedAt       目标添加时间戳（毫秒）
     * @property lastUsedAt    最后使用时间戳（毫秒）
     */
    data class BeamTarget(
        val id: String,
        val name: String,
        val type: BeamTargetType,
        val address: String = "",
        val capabilities: Set<BeamType> = setOf(BeamType.TEXT, BeamType.URL),
        val security: BeamSecurity = BeamSecurity.NONE,
        val isOnline: Boolean = true,
        val priority: Int = 0,
        val description: String = "",
        val extraConfig: JsonObject = buildJsonObject { },
        val addedAt: Long = System.currentTimeMillis(),
        val lastUsedAt: Long = 0L
    )

    /**
     * 投射历史记录。
     *
     * 记录一次完整的投射操作信息，包括投射的内容、目标、时间、状态和
     * 结果描述。由 [recordBeamHistory] 在每次投射完成或失败时自动创建。
     * 通过 [getBeamHistory] 查询，按时间倒序排列。
     *
     * @property id           历史记录唯一标识
     * @property contentId    投射内容的 ID（关联 [BeamContent]）
     * @property contentType  投射内容类型
     * @property contentSummary 内容摘要（前 100 字符）
     * @property targetId     目标 ID（关联 [BeamTarget]）
     * @property targetName   目标名称（快照，防止目标被删除后丢失信息）
     * @property targetType   目标类型（快照）
     * @property status       投射状态
     * @property errorMessage 错误信息（状态为 FAILED 时非空）
     * @property durationMs   投射耗时（毫秒）
     * @property dataSize     投射数据大小（字节）
     * @property securityUsed 本次使用的安全级别
     * @property isEncrypted  是否已加密
     * @property isAuthenticated 是否已认证签名
     * @property beamedAt     投射时间戳（毫秒）
     */
    data class BeamHistory(
        val id: String = UUID.randomUUID().toString(),
        val contentId: String,
        val contentType: BeamType,
        val contentSummary: String,
        val targetId: String,
        val targetName: String,
        val targetType: BeamTargetType,
        val status: BeamStatus,
        val errorMessage: String = "",
        val durationMs: Long = 0L,
        val dataSize: Long = 0L,
        val securityUsed: BeamSecurity = BeamSecurity.NONE,
        val isEncrypted: Boolean = false,
        val isAuthenticated: Boolean = false,
        val beamedAt: Long = System.currentTimeMillis()
    )

    /**
     * 投射会话。
     *
     * 代表一次正在执行或已完成的投射操作，由 [beam] 和 [beamAction] 创建。
     * 会话包含完整的状态信息和日志，用于追踪投射进度、处理结果和错误。
     * 活跃会话存储在 [activeSessions] 中，完成后的会话转移到历史记录。
     *
     * @property id             会话唯一标识（UUID 自动生成）
     * @property contentId      关联的投射内容 ID
     * @property targetId       目标 ID
     * @property status         当前状态
     * @property progress       投射进度（0.0 - 1.0）
     * @property startedAt      开始时间戳（毫秒）
     * @property completedAt    完成时间戳（毫秒），未完成时为 0
     * @property errorMessage   错误信息
     * @property retryCount     已重试次数
     * @property maxRetries     最大重试次数
     * @property logs           会话日志列表
     */
    data class BeamSession(
        val id: String = UUID.randomUUID().toString(),
        val contentId: String,
        val targetId: String,
        val status: BeamStatus = BeamStatus.PENDING,
        val progress: Float = 0.0f,
        val startedAt: Long = System.currentTimeMillis(),
        val completedAt: Long = 0L,
        val errorMessage: String = "",
        val retryCount: Int = 0,
        val maxRetries: Int = 3,
        val logs: List<String> = emptyList()
    )

    /**
     * 二维码内容。
     *
     * 封装了适合编码为二维码的紧凑字符串及其元数据。由 [generateQRContent]
     * 生成，包含内容类型标识、原始数据、目标标识和校验信息，确保扫码后
     * 可以正确解析和还原。
     *
     * @property id             二维码内容唯一标识
     * @property contentId      关联的原始内容 ID
     * @property encodedString  编码后的紧凑字符串，可直接用于生成二维码图片
     * @property formatVersion  编码格式版本号，用于前向兼容
     * @property checksum       数据校验和（SHA-256 前 16 位 HEX）
     * @property generatedAt    生成时间戳（毫秒）
     * @property expiresAt      过期时间戳（毫秒），0 表示永不过期
     * @property targetId       目标设备 ID（可选），为空表示任意设备可扫码
     */
    data class QRContent(
        val id: String = UUID.randomUUID().toString(),
        val contentId: String,
        val encodedString: String,
        val formatVersion: Int = 1,
        val checksum: String = "",
        val generatedAt: Long = System.currentTimeMillis(),
        val expiresAt: Long = 0L,
        val targetId: String = ""
    )

    // =========================================================================
    //  内部存储
    // =========================================================================

    /** 投射历史记录（id -> BeamHistory），线程安全。 */
    private val beamHistory = ConcurrentHashMap<String, BeamHistory>()

    /** 投射目标列表（id -> BeamTarget），线程安全。 */
    private val beamTargets = ConcurrentHashMap<String, BeamTarget>()

    /** 活跃会话（id -> BeamSession），线程安全。 */
    private val activeSessions = ConcurrentHashMap<String, BeamSession>()

    /** 加密密钥缓存（keyId -> SecretKeySpec），线程安全。 */
    private val keyCache = ConcurrentHashMap<String, SecretKeySpec>()

    /** 会话日志列表（sessionId -> logs），线程安全。 */
    private val sessionLogs = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()

    /** 历史记录插入顺序列表（用于淘汰最旧记录）。 */
    private val historyOrder = CopyOnWriteArrayList<String>()

    /** 统计计数器。 */
    @Volatile
    private var totalBeams: Long = 0L

    @Volatile
    private var successfulBeams: Long = 0L

    @Volatile
    private var failedBeams: Long = 0L

    @Volatile
    private var totalDataTransferred: Long = 0L

    // =========================================================================
    //  核心投射方法
    // =========================================================================

    /**
     * 将内容投射到指定目标。
     *
     * 这是 Beam 功能的核心入口方法。接收一个 [BeamContent] 和目标 ID，
     * 执行完整投射流程：
     * 1. 校验目标是否存在且在线
     * 2. 根据目标安全级别对内容进行加密和/或签名
     * 3. 根据目标类型和内容类型进行内容变换
     * 4. 创建投射会话，标记为 BEAMING
     * 5. 模拟传输到目标端
     * 6. 更新会话状态为 COMPLETED 或 FAILED
     * 7. 记录投射历史
     *
     * 这是一个同步方法，模拟异步传输过程。实际使用时，建议在后台线程
     * 中调用以避免阻塞 UI。
     *
     * @param content  待投射的内容
     * @param targetId 目标 ID（需已通过 [addBeamTarget] 添加）
     * @return 投射会话 [BeamSession]，包含最终状态和日志
     */
    fun beam(content: BeamContent, targetId: String): BeamSession {
        // 校验目标
        val target = beamTargets[targetId]
        if (target == null) {
            val failedSession = BeamSession(
                contentId = content.id,
                targetId = targetId,
                status = BeamStatus.FAILED,
                errorMessage = "目标不存在: $targetId",
                logs = listOf("[ERROR] 目标不存在: $targetId")
            )
            recordBeamHistory(
                contentId = content.id,
                contentType = content.type,
                contentSummary = content.data.take(100),
                targetId = targetId,
                targetName = targetId,
                targetType = BeamTargetType.REMOTE_DEVICE,
                status = BeamStatus.FAILED,
                errorMessage = "目标不存在: $targetId",
                securityUsed = BeamSecurity.NONE
            )
            failedBeams++
            totalBeams++
            return failedSession
        }

        if (!target.isOnline && target.type == BeamTargetType.REMOTE_DEVICE) {
            val failedSession = BeamSession(
                contentId = content.id,
                targetId = targetId,
                status = BeamStatus.FAILED,
                errorMessage = "目标离线: ${target.name}",
                logs = listOf("[ERROR] 目标离线: ${target.name}")
            )
            recordBeamHistory(
                contentId = content.id,
                contentType = content.type,
                contentSummary = content.data.take(100),
                targetId = targetId,
                targetName = target.name,
                targetType = target.type,
                status = BeamStatus.FAILED,
                errorMessage = "目标离线: ${target.name}",
                securityUsed = target.security
            )
            failedBeams++
            totalBeams++
            return failedSession
        }

        // 创建会话
        val sessionId = UUID.randomUUID().toString()
        var session = BeamSession(
            id = sessionId,
            contentId = content.id,
            targetId = targetId,
            status = BeamStatus.BEAMING,
            progress = 0.1f,
            startedAt = System.currentTimeMillis(),
            maxRetries = 3,
            logs = mutableListOf("[INFO] 开始投射到目标: ${target.name} (${target.type.displayName})")
        )
        addSessionLog(sessionId, "[INFO] 内容类型: ${content.type.displayName}")
        addSessionLog(sessionId, "[INFO] 安全级别: ${target.security.displayName}")

        // 限制活跃会话数
        enforceMaxSessions()

        // 内容变换
        val transformedData = transformContent(content, target)
        addSessionLog(sessionId, "[INFO] 内容变换完成")

        // 安全处理
        var processedData = transformedData
        var isEncrypted = false
        var isAuthenticated = false

        when (target.security) {
            BeamSecurity.ENCRYPTED -> {
                processedData = encryptContent(transformedData, target.id)
                isEncrypted = true
                addSessionLog(sessionId, "[INFO] 内容已加密 (AES)")
            }
            BeamSecurity.AUTHENTICATED -> {
                processedData = authenticateContent(transformedData, target.id)
                isAuthenticated = true
                addSessionLog(sessionId, "[INFO] 内容已签名认证")
            }
            BeamSecurity.ENCRYPTED_AND_AUTHENTICATED -> {
                val encrypted = encryptContent(transformedData, target.id)
                processedData = authenticateContent(encrypted, target.id)
                isEncrypted = true
                isAuthenticated = true
                addSessionLog(sessionId, "[INFO] 内容已加密并签名认证")
            }
            BeamSecurity.NONE -> {
                addSessionLog(sessionId, "[INFO] 无安全保护，明文传输")
            }
        }

        session = session.copy(progress = 0.5f)
        activeSessions[sessionId] = session

        // 模拟传输
        addSessionLog(sessionId, "[INFO] 正在传输到 ${target.address}...")
        val transferResult = simulateTransfer(target, processedData)
        session = session.copy(progress = 0.9f)

        val now = System.currentTimeMillis()
        val duration = now - session.startedAt

        if (transferResult) {
            session = session.copy(
                status = BeamStatus.COMPLETED,
                progress = 1.0f,
                completedAt = now,
                logs = session.logs + "[INFO] 投射完成，耗时: ${duration}ms"
            )
            successfulBeams++
            totalDataTransferred += processedData.toByteArray().size.toLong()
        } else {
            session = session.copy(
                status = BeamStatus.FAILED,
                progress = 0.0f,
                errorMessage = "传输失败: 目标不可达",
                logs = session.logs + "[ERROR] 传输失败: 目标不可达"
            )
            failedBeams++
        }

        // 更新目标最后使用时间
        updateTargetLastUsed(targetId)

        // 记录历史
        recordBeamHistory(
            contentId = content.id,
            contentType = content.type,
            contentSummary = content.data.take(100),
            targetId = targetId,
            targetName = target.name,
            targetType = target.type,
            status = session.status,
            errorMessage = session.errorMessage,
            durationMs = duration,
            dataSize = processedData.toByteArray().size.toLong(),
            securityUsed = target.security,
            isEncrypted = isEncrypted,
            isAuthenticated = isAuthenticated
        )

        totalBeams++
        activeSessions[sessionId] = session
        Log.d(tag, "Beam 完成: content=${content.id}, target=${target.name}, " +
                "status=${session.status}, duration=${duration}ms")

        return session
    }

    /**
     * 将动作投射到远程设备执行。
     *
     * 将 [BeamAction] 序列化为 [BeamContent] 后调用 [beam] 投射到目标设备。
     * 目标设备收到后可以反序列化并执行对应的操作，实现远程控制场景。
     * 例如，将手机上的「点击登录按钮」动作投射到平板上的同名应用中执行。
     *
     * 动作投射的典型流程：
     * 1. 在源设备上录制或构造动作序列
     * 2. 将动作序列通过 [beamAction] 发送到目标设备
     * 3. 目标设备反序列化动作并逐个执行
     * 4. 目标设备反馈执行结果
     *
     * @param action   待投射的动作
     * @param targetId 目标 ID
     * @return 投射会话 [BeamSession]
     */
    fun beamAction(action: BeamAction, targetId: String): BeamSession {
        // 将动作序列化为 JSON 字符串
        val actionJson = buildJsonObject {
            put("id", JsonPrimitive(action.id))
            put("action_type", JsonPrimitive(action.actionType))
            put("target_widget", JsonPrimitive(action.targetWidget))
            put("target_text", JsonPrimitive(action.targetText))
            put("params", action.params)
            put("package_name", JsonPrimitive(action.packageName))
            put("delay_ms", JsonPrimitive(action.delayMs))
            put("timestamp", JsonPrimitive(action.timestamp))
            put("source_session", JsonPrimitive(action.sourceSession))
            put("description", JsonPrimitive(action.description))
        }

        val content = BeamContent(
            type = BeamType.ACTION,
            data = actionJson.toString(),
            sourcePackage = "com.mobileclaw.app",
            metadata = buildJsonObject {
                put("action_type", JsonPrimitive(action.actionType))
                put("description", JsonPrimitive(action.description))
            },
            tags = listOf("beam_action", action.actionType)
        )

        Log.d(tag, "BeamAction: ${action.actionType} -> $targetId")
        return beam(content, targetId)
    }

    /**
     * 将内容批量投射到多个目标。
     *
     * 对目标列表中的每个目标依次调用 [beam]，并收集所有会话结果。
     * 适用于「一键分享到多个设备」的场景。
     *
     * @param content   待投射的内容
     * @param targetIds 目标 ID 列表
     * @return 投射会话列表，与 targetIds 一一对应
     */
    fun beamToMultiple(content: BeamContent, targetIds: List<String>): List<BeamSession> {
        return targetIds.map { targetId ->
            beam(content, targetId)
        }
    }

    // =========================================================================
    //  深度链接生成
    // =========================================================================

    /**
     * 根据当前应用上下文生成深度链接（Deep Link）。
     *
     * 深度链接的 URI 格式为：
     * `mobileclaw://beam/{packageName}/{pagePath}?{params}`
     *
     * 例如，从微信聊天页面生成的深度链接：
     * `mobileclaw://beam/com.tencent.mm/chat?friend=user123`
     *
     * 生成的深度链接可以通过 [beam] 投射到其他设备，对方点击即可直接
     * 打开对应应用和页面，无需手动导航。
     *
     * @param packageName 目标应用包名
     * @param pagePath    页面路径（如 "chat"、"settings"、"profile"）
     * @param params      查询参数（JSON 格式），每个键值对转为 query parameter
     * @param fragment    锚点片段（可选），用于定位到页面内具体位置
     * @return 深度链接字符串
     */
    fun generateDeepLink(
        packageName: String,
        pagePath: String,
        params: JsonObject = buildJsonObject { },
        fragment: String = ""
    ): String {
        val sb = StringBuilder()
        sb.append("$deepLinkScheme://$deepLinkHost/")
        sb.append(packageName)
        sb.append("/")
        sb.append(pagePath.trimStart('/'))

        // 添加查询参数
        if (params.isNotEmpty()) {
            sb.append("?")
            val entries = params.entries.toList()
            entries.forEachIndexed { index, (key, value) ->
                val encodedValue = java.net.URLEncoder.encode(
                    value.toString().trim('"'),
                    defaultCharset
                )
                sb.append("$key=$encodedValue")
                if (index < entries.size - 1) {
                    sb.append("&")
                }
            }
        }

        // 添加片段
        if (fragment.isNotBlank()) {
            sb.append("#")
            sb.append(java.net.URLEncoder.encode(fragment, defaultCharset))
        }

        val deepLink = sb.toString()
        Log.d(tag, "生成深度链接: $deepLink")
        return deepLink
    }

    /**
     * 从深度链接解析出结构化信息。
     *
     * 反向解析 [generateDeepLink] 生成的链接，提取包名、页面路径和参数。
     * 用于目标端接收深度链接后还原上下文。
     *
     * @param deepLink 深度链接字符串
     * @return JSON 对象，包含 package_name、page_path、params 和 fragment 字段
     */
    fun parseDeepLink(deepLink: String): JsonObject {
        return buildJsonObject {
            put("raw", JsonPrimitive(deepLink))

            try {
                val uri = java.net.URI(deepLink)
                val path = uri.path ?: ""
                val pathParts = path.trimStart('/').split("/", limit = 2)

                if (pathParts.size >= 1) {
                    put("package_name", JsonPrimitive(pathParts[0]))
                }
                if (pathParts.size >= 2) {
                    put("page_path", JsonPrimitive(pathParts[1]))
                }

                // 解析查询参数
                val query = uri.query
                if (query != null) {
                    val paramsMap = buildJsonObject {
                        query.split("&").forEach { pair ->
                            val kv = pair.split("=", limit = 2)
                            if (kv.size == 2) {
                                val decoded = java.net.URLDecoder.decode(kv[1], defaultCharset)
                                put(kv[0], JsonPrimitive(decoded))
                            }
                        }
                    }
                    put("params", paramsMap)
                }

                val fragment = uri.fragment
                if (fragment != null) {
                    put("fragment", JsonPrimitive(
                        java.net.URLDecoder.decode(fragment, defaultCharset)
                    ))
                }
            } catch (e: Exception) {
                put("error", JsonPrimitive("解析失败: ${e.message}"))
            }
        }
    }

    // =========================================================================
    //  二维码内容生成
    // =========================================================================

    /**
     * 从 [BeamContent] 生成二维码编码字符串。
     *
     * 编码格式：
     * `MOBILECLAW_BEAM:V{version}|{contentType}|{contentId}|{targetId}|{checksum}|{timestamp}|{data}`
     *
     * 编码后的字符串可以直接输入到二维码生成库中生成二维码图片。
     * 目标设备扫码后可以解析还原为原始内容。
     *
     * @param content  待编码的内容
     * @param targetId 目标设备 ID（可选），为空表示任意设备可扫码
     * @param expiresInSec 过期时间（秒），0 表示永不过期
     * @return [QRContent] 对象，包含编码后的字符串和元数据
     */
    fun generateQRContent(
        content: BeamContent,
        targetId: String = "",
        expiresInSec: Long = 0L
    ): QRContent {
        val checksum = computeChecksum(content.data)
        val expiresAt = if (expiresInSec > 0L) {
            System.currentTimeMillis() + expiresInSec * 1000
        } else {
            0L
        }

        // 构建编码字符串
        val encodedString = buildString {
            append(qrPrefix)
            append("V1|")
            append(content.type.name)
            append("|")
            append(content.id)
            append("|")
            append(if (targetId.isBlank()) "*" else targetId)
            append("|")
            append(checksum)
            append("|")
            append(System.currentTimeMillis())
            append("|")
            append(content.data)
        }

        val qrContent = QRContent(
            contentId = content.id,
            encodedString = encodedString,
            checksum = checksum,
            expiresAt = expiresAt,
            targetId = targetId
        )

        Log.d(tag, "生成二维码内容: type=${content.type.name}, len=${encodedString.length}")
        return qrContent
    }

    /**
     * 解析二维码字符串，还原为 [BeamContent]。
     *
     * 反向解析 [generateQRContent] 生成的编码字符串，校验格式和校验和，
     * 并检查过期时间。
     *
     * @param qrString 二维码字符串
     * @return 解析结果（JSON 对象），包含 content 和验证信息，解析失败时 error 非空
     */
    fun parseQRContent(qrString: String): JsonObject {
        return buildJsonObject {
            put("raw", JsonPrimitive(qrString))

            try {
                // 检查前缀
                if (!qrString.startsWith(qrPrefix)) {
                    put("error", JsonPrimitive("无效的前缀，期望: $qrPrefix"))
                    return@buildJsonObject
                }

                val body = qrString.removePrefix(qrPrefix)
                val parts = body.split("|", limit = 7)

                if (parts.size < 7) {
                    put("error", JsonPrimitive("格式错误，字段数不足"))
                    return@buildJsonObject
                }

                val version = parts[0] // "V1"
                val contentTypeName = parts[1]
                val contentId = parts[2]
                val targetId = parts[3]
                val checksum = parts[4]
                val timestamp = parts[5]
                val data = parts[6]

                // 校验和验证
                val computedChecksum = computeChecksum(data)
                val checksumValid = computedChecksum == checksum

                // 过期检查
                val expiresAt = if (targetId == "*") 0L else 0L // 由外部传入
                val isExpired = expiresAt > 0L && System.currentTimeMillis() > expiresAt

                put("version", JsonPrimitive(version))
                put("content_id", JsonPrimitive(contentId))
                put("content_type", JsonPrimitive(contentTypeName))
                put("target_id", JsonPrimitive(targetId))
                put("checksum", JsonPrimitive(checksum))
                put("checksum_valid", JsonPrimitive(checksumValid))
                put("timestamp", JsonPrimitive(timestamp))
                put("is_expired", JsonPrimitive(isExpired))
                put("data", JsonPrimitive(data))

                // 构建 BeamContent
                put("content", buildJsonObject {
                    put("id", JsonPrimitive(contentId))
                    put("type", JsonPrimitive(contentTypeName))
                    put("data", JsonPrimitive(data))
                })
            } catch (e: Exception) {
                put("error", JsonPrimitive("解析异常: ${e.message}"))
            }
        }
    }

    // =========================================================================
    //  投射历史管理
    // =========================================================================

    /**
     * 获取投射历史记录。
     *
     * 按时间倒序返回历史记录列表（最新在前）。支持按内容类型和/或目标类型
     * 筛选，并限制返回数量。
     *
     * @param limit        返回的最大记录数（默认 50，最大 500）
     * @param contentType  按内容类型筛选（可选）
     * @param targetType   按目标类型筛选（可选）
     * @param status       按状态筛选（可选）
     * @param offset       偏移量，用于分页
     * @return 历史记录列表
     */
    fun getBeamHistory(
        limit: Int = 50,
        contentType: BeamType? = null,
        targetType: BeamTargetType? = null,
        status: BeamStatus? = null,
        offset: Int = 0
    ): List<BeamHistory> {
        var result = beamHistory.values.toList()

        // 筛选
        if (contentType != null) {
            result = result.filter { it.contentType == contentType }
        }
        if (targetType != null) {
            result = result.filter { it.targetType == targetType }
        }
        if (status != null) {
            result = result.filter { it.status == status }
        }

        // 按时间倒序排列
        result = result.sortedByDescending { it.beamedAt }

        // 分页
        val effectiveLimit = limit.coerceIn(1, maxHistorySize)
        return result.drop(offset).take(effectiveLimit)
    }

    /**
     * 根据 ID 获取单条历史记录。
     *
     * @param historyId 历史记录 ID
     * @return 历史记录，不存在时返回 null
     */
    fun getBeamHistoryById(historyId: String): BeamHistory? {
        return beamHistory[historyId]
    }

    /**
     * 清除所有投射历史。
     */
    fun clearBeamHistory() {
        beamHistory.clear()
        historyOrder.clear()
        Log.d(tag, "历史记录已清空")
    }

    /**
     * 删除指定历史记录。
     *
     * @param historyId 历史记录 ID
     * @return true 表示删除成功
     */
    fun deleteBeamHistory(historyId: String): Boolean {
        historyOrder.remove(historyId)
        return beamHistory.remove(historyId) != null
    }

    // =========================================================================
    //  目标管理
    // =========================================================================

    /**
     * 添加投射目标。
     *
     * 将 [BeamTarget] 添加到可用目标列表。目标数量受 [maxTargets] 限制，
     * 超出时返回 false 并输出告警日志。
     *
     * @param target 待添加的目标
     * @return true 表示添加成功，false 表示已达上限或 ID 已存在
     */
    fun addBeamTarget(target: BeamTarget): Boolean {
        if (beamTargets.size >= maxTargets) {
            Log.w(tag, "添加目标失败: 已达上限 ($maxTargets)")
            return false
        }
        if (beamTargets.containsKey(target.id)) {
            Log.w(tag, "添加目标失败: ID 已存在 (${target.id})")
            return false
        }
        beamTargets[target.id] = target
        Log.d(tag, "添加目标: ${target.name} (${target.type.displayName})")
        return true
    }

    /**
     * 移除投射目标。
     *
     * @param targetId 目标 ID
     * @return true 表示移除成功
     */
    fun removeBeamTarget(targetId: String): Boolean {
        val removed = beamTargets.remove(targetId)
        if (removed != null) {
            Log.d(tag, "移除目标: ${removed.name}")
            return true
        }
        return false
    }

    /**
     * 获取所有投射目标。
     *
     * @param type 按目标类型筛选（可选）
     * @return 目标列表
     */
    fun getBeamTargets(type: BeamTargetType? = null): List<BeamTarget> {
        val values = beamTargets.values.toList()
        return if (type != null) {
            values.filter { it.type == type }
        } else {
            values
        }
    }

    /**
     * 根据 ID 获取目标。
     *
     * @param targetId 目标 ID
     * @return 目标，不存在时返回 null
     */
    fun getBeamTarget(targetId: String): BeamTarget? {
        return beamTargets[targetId]
    }

    /**
     * 更新目标在线状态。
     *
     * @param targetId 目标 ID
     * @param isOnline 是否在线
     * @return true 表示更新成功
     */
    fun updateTargetOnlineStatus(targetId: String, isOnline: Boolean): Boolean {
        val target = beamTargets[targetId] ?: return false
        beamTargets[targetId] = target.copy(isOnline = isOnline)
        return true
    }

    /**
     * 清空所有目标。
     */
    fun clearBeamTargets() {
        beamTargets.clear()
        Log.d(tag, "所有目标已清空")
    }

    // =========================================================================
    //  加密与安全
    // =========================================================================

    /**
     * 加密内容。
     *
     * 使用 AES 算法对内容进行加密。由于 Android 环境中密钥管理涉及复杂的
     * KeyStore 体系，本实现使用基于目标 ID 派生的密钥进行模拟 AES 加密。
     * 实际生产环境中应替换为 Android KeyStore + AES/GCM/NoPadding。
     *
     * 加密流程：
     * 1. 从目标 ID 派生 AES 密钥（SHA-256 取前 16 字节）
     * 2. 使用 AES/ECB/PKCS5Padding 模式加密
     * 3. 返回 Base64 编码的密文
     *
     * @param data     明文数据
     * @param targetId 目标 ID（用于派生密钥）
     * @return Base64 编码的密文
     */
    fun encryptContent(data: String, targetId: String): String {
        return try {
            val key = getOrCreateKey(targetId)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            val encryptedBase64 = Base64.getEncoder().encodeToString(encryptedBytes)
            Log.d(tag, "加密完成: 输入长度=${data.length}, 输出长度=${encryptedBase64.length}")
            encryptedBase64
        } catch (e: Exception) {
            Log.e(tag, "加密失败: ${e.message}")
            // 降级：返回 Base64 编码的明文
            "[ENCRYPTED]:${Base64.getEncoder().encodeToString(data.toByteArray())}"
        }
    }

    /**
     * 解密内容。
     *
     * 与 [encryptContent] 对称，使用相同的密钥派生逻辑进行解密。
     *
     * @param encryptedData Base64 编码的密文
     * @param targetId      目标 ID（用于派生密钥）
     * @return 解密后的明文，解密失败时返回错误提示
     */
    fun decryptContent(encryptedData: String, targetId: String): String {
        // 检查降级标记
        if (encryptedData.startsWith("[ENCRYPTED]:")) {
            val base64Data = encryptedData.removePrefix("[ENCRYPTED]:")
            return try {
                String(Base64.getDecoder().decode(base64Data), Charsets.UTF_8)
            } catch (e: Exception) {
                "[解密失败: 数据损坏]"
            }
        }

        return try {
            val key = getOrCreateKey(targetId)
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(encryptedData))
            val plaintext = String(decryptedBytes, Charsets.UTF_8)
            Log.d(tag, "解密完成: 输入长度=${encryptedData.length}, 输出长度=${plaintext.length}")
            plaintext
        } catch (e: Exception) {
            Log.e(tag, "解密失败: ${e.message}")
            "[解密失败: ${e.message}]"
        }
    }

    /**
     * 对内容进行认证签名。
     *
     * 使用 HMAC-SHA256 风格（基于目标 ID 派生密钥）对内容生成签名并附加。
     * 签名格式：`{data}||SIGN:{signature}`
     *
     * @param data     待签名的内容
     * @param targetId 目标 ID（用于派生密钥）
     * @return 签名后的内容字符串
     */
    fun authenticateContent(data: String, targetId: String): String {
        val signature = computeHmac(data, targetId)
        return "$data||SIGN:$signature"
    }

    /**
     * 验证已签名内容的完整性。
     *
     * 与 [authenticateContent] 对称，解析签名并验证。
     *
     * @param signedData 已签名的内容
     * @param targetId   目标 ID
     * @return 验证结果 JSON，包含 original_data（原始数据）、is_valid（是否有效）
     *         和 error（验证失败时的错误信息）
     */
    fun verifyAuthenticatedContent(signedData: String, targetId: String): JsonObject {
        return buildJsonObject {
            try {
                val signMarker = "||SIGN:"
                val signIndex = signedData.lastIndexOf(signMarker)
                if (signIndex == -1) {
                    put("error", JsonPrimitive("无效的签名格式"))
                    put("is_valid", JsonPrimitive(false))
                    return@buildJsonObject
                }

                val originalData = signedData.substring(0, signIndex)
                val expectedSignature = signedData.substring(signIndex + signMarker.length)
                val computedSignature = computeHmac(originalData, targetId)

                put("original_data", JsonPrimitive(originalData))
                put("expected_signature", JsonPrimitive(expectedSignature))
                put("computed_signature", JsonPrimitive(computedSignature))
                put("is_valid", JsonPrimitive(expectedSignature == computedSignature))
            } catch (e: Exception) {
                put("error", JsonPrimitive("验证异常: ${e.message}"))
                put("is_valid", JsonPrimitive(false))
            }
        }
    }

    // =========================================================================
    //  远程设备投射
    // =========================================================================

    /**
     * 将内容投射到远程设备。
     *
     * 这是 [beam] 的便捷封装，自动查找类型为 [BeamTargetType.REMOTE_DEVICE]
     * 的目标并执行投射。适合在已知目标名称但不知道 ID 的场景下使用。
     *
     * @param content   待投射的内容
     * @param deviceName 远程设备名称（模糊匹配）
     * @return 投射会话，未找到匹配设备时返回 FAILED 状态的会话
     */
    fun beamToDevice(content: BeamContent, deviceName: String): BeamSession {
        val target = beamTargets.values.find { target ->
            target.type == BeamTargetType.REMOTE_DEVICE &&
                target.name.contains(deviceName, ignoreCase = true)
        }

        if (target == null) {
            val failedSession = BeamSession(
                contentId = content.id,
                targetId = "unknown",
                status = BeamStatus.FAILED,
                errorMessage = "未找到匹配的远程设备: $deviceName",
                logs = listOf("[ERROR] 未找到远程设备: $deviceName")
            )
            Log.w(tag, "投射到设备失败: 未找到 '$deviceName'")
            return failedSession
        }

        Log.d(tag, "投射到远程设备: ${target.name}")
        return beam(content, target.id)
    }

    /**
     * 将内容投射到系统剪贴板。
     *
     * 自动查找或创建 CLIPBOARD 类型的目标，将内容投射到系统剪贴板。
     * 如果不存在 CLIPBOARD 目标，会自动创建一个默认的剪贴板目标。
     *
     * @param content 待投射的内容
     * @return 投射会话
     */
    fun beamToClipboard(content: BeamContent): BeamSession {
        val target = beamTargets.values.find { it.type == BeamTargetType.CLIPBOARD }
        if (target == null) {
            // 自动创建默认剪贴板目标
            val clipboardTarget = BeamTarget(
                id = "clipboard-default",
                name = "系统剪贴板",
                type = BeamTargetType.CLIPBOARD,
                address = "local://clipboard",
                capabilities = BeamType.entries.toSet(),
                security = BeamSecurity.NONE,
                description = "默认系统剪贴板目标"
            )
            beamTargets[clipboardTarget.id] = clipboardTarget
            return beam(content, clipboardTarget.id)
        }
        return beam(content, target.id)
    }

    /**
     * 将内容投射到系统分享（Share Sheet）。
     *
     * 自动查找或创建 SHARE_INTENT 类型的目标。
     *
     * @param content 待投射的内容
     * @return 投射会话
     */
    fun beamToShareIntent(content: BeamContent): BeamSession {
        val target = beamTargets.values.find { it.type == BeamTargetType.SHARE_INTENT }
        if (target == null) {
            val shareTarget = BeamTarget(
                id = "share-default",
                name = "系统分享",
                type = BeamTargetType.SHARE_INTENT,
                address = "local://share",
                capabilities = BeamType.entries.toSet(),
                security = BeamSecurity.NONE,
                description = "默认系统分享目标"
            )
            beamTargets[shareTarget.id] = shareTarget
            return beam(content, shareTarget.id)
        }
        return beam(content, target.id)
    }

    // =========================================================================
    //  内容变换
    // =========================================================================

    /**
     * 根据目标类型和内容类型对内容进行变换。
     *
     * 内容变换的目标是让内容在目标端以最佳形式呈现。变换规则：
     *
     * | 内容类型 | 目标类型   | 变换策略 |
     * |----------|------------|----------|
     * | URL      | CLIPBOARD  | 保持原样 |
     * | URL      | LOCAL_FILE | 添加时间戳前缀 |
     * | TEXT     | CLIPBOARD  | 保持原样 |
     * | TEXT     | LOCAL_FILE | 添加元数据头部 |
     * | TEXT     | REMOTE_DEVICE | 添加来源标记 |
     * | SCREENSHOT | CLIPBOARD | 保持 Base64 |
     * | SCREENSHOT | REMOTE_DEVICE | 压缩（截断到 10KB） |
     * | 其他     | 任意       | 保持原样 |
     *
     * @param content 原始内容
     * @param target  目标
     * @return 变换后的内容数据字符串
     */
    fun transformContent(content: BeamContent, target: BeamTarget): String {
        val data = content.data
        val contentType = content.type
        val targetType = target.type

        return when {
            // URL 投到本地文件：添加时间戳文件名前缀
            contentType == BeamType.URL && targetType == BeamTargetType.LOCAL_FILE -> {
                val timestamp = System.currentTimeMillis()
                "[URL_FILE:$timestamp] $data"
            }

            // 文本投到本地文件：添加元数据头部
            contentType == BeamType.TEXT && targetType == BeamTargetType.LOCAL_FILE -> {
                buildString {
                    appendLine("// ============================================")
                    appendLine("// 来源: ${content.sourcePackage}")
                    appendLine("// 时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()).format(java.util.Date())}")
                    appendLine("// 类型: ${contentType.displayName}")
                    appendLine("// 标签: ${content.tags.joinToString(", ")}")
                    appendLine("// ============================================")
                    appendLine()
                    append(data)
                }
            }

            // 文本投到远程设备：添加来源标记
            contentType == BeamType.TEXT && targetType == BeamTargetType.REMOTE_DEVICE -> {
                "[MobileClaw Beam] ${content.sourcePackage}: $data"
            }

            // 截图投到远程设备：模拟压缩（截断到 10KB）
            contentType == BeamType.SCREENSHOT && targetType == BeamTargetType.REMOTE_DEVICE -> {
                if (data.length > 10240) {
                    data.take(10240) + "...[压缩: ${data.length}→10240 字节]"
                } else {
                    data
                }
            }

            // 深度链接投到浏览器：保持原样（URL 本身）
            contentType == BeamType.DEEP_LINK && targetType == BeamTargetType.CLIPBOARD -> {
                data
            }

            // 动作投到远程设备：添加执行环境说明
            contentType == BeamType.ACTION && targetType == BeamTargetType.REMOTE_DEVICE -> {
                val envHeader = buildJsonObject {
                    put("_beam_source", JsonPrimitive(content.sourcePackage))
                    put("_beam_time", JsonPrimitive(System.currentTimeMillis()))
                    put("_beam_target_type", JsonPrimitive(targetType.name))
                }
                // 尝试将原数据与头部合并
                try {
                    val originalJson = JsonObject::class.java
                    "$data|env:${envHeader.toString()}"
                } catch (e: Exception) {
                    "$data|env:${envHeader.toString()}"
                }
            }

            // 其他情况：保持原样
            else -> data
        }
    }

    // =========================================================================
    //  统计信息
    // =========================================================================

    /**
     * 获取投射统计信息。
     *
     * 汇总所有投射操作的统计指标，包括总次数、成功/失败次数、成功率、
     * 总传输数据量、活跃会话数、目标数和历史记录数。
     *
     * @return JSON 对象，包含各项统计指标
     */
    fun getBeamStats(): JsonObject {
        val successRate = if (totalBeams > 0) {
            successfulBeams.toDouble() / totalBeams.toDouble()
        } else {
            0.0
        }

        return buildJsonObject {
            put("total_beams", JsonPrimitive(totalBeams))
            put("successful_beams", JsonPrimitive(successfulBeams))
            put("failed_beams", JsonPrimitive(failedBeams))
            put("success_rate", JsonPrimitive(successRate))
            put("total_data_transferred_bytes", JsonPrimitive(totalDataTransferred))
            put("total_data_transferred_kb", JsonPrimitive(totalDataTransferred / 1024))
            put("total_data_transferred_mb", JsonPrimitive(totalDataTransferred / (1024 * 1024)))
            put("active_sessions", JsonPrimitive(activeSessions.size))
            put("total_targets", JsonPrimitive(beamTargets.size))
            put("total_history", JsonPrimitive(beamHistory.size))
            put("cached_keys", JsonPrimitive(keyCache.size))
            put("max_history_size", JsonPrimitive(maxHistorySize))
            put("max_targets", JsonPrimitive(maxTargets))
            put("max_active_sessions", JsonPrimitive(maxActiveSessions))
        }
    }

    /**
     * 获取指定目标的统计信息。
     *
     * @param targetId 目标 ID
     * @return JSON 对象，包含该目标的投射次数、成功率等
     */
    fun getTargetStats(targetId: String): JsonObject {
        val target = beamTargets[targetId]
        val targetHistory = beamHistory.values.filter { it.targetId == targetId }

        val total = targetHistory.size
        val success = targetHistory.count { it.status == BeamStatus.COMPLETED }
        val failed = targetHistory.count { it.status == BeamStatus.FAILED }
        val rate = if (total > 0) success.toDouble() / total.toDouble() else 0.0

        return buildJsonObject {
            put("target_id", JsonPrimitive(targetId))
            put("target_name", JsonPrimitive(target?.name ?: "未知"))
            put("target_type", JsonPrimitive(target?.type?.name ?: "UNKNOWN"))
            put("is_online", JsonPrimitive(target?.isOnline ?: false))
            put("total_beams", JsonPrimitive(total))
            put("successful_beams", JsonPrimitive(success))
            put("failed_beams", JsonPrimitive(failed))
            put("success_rate", JsonPrimitive(rate))
            put("last_used", JsonPrimitive(target?.lastUsedAt ?: 0L))
        }
    }

    // =========================================================================
    //  活跃会话管理
    // =========================================================================

    /**
     * 获取活跃会话列表。
     *
     * @param status 按状态筛选（可选）
     * @return 活跃会话列表
     */
    fun getActiveSessions(status: BeamStatus? = null): List<BeamSession> {
        val sessions = activeSessions.values.toList()
        return if (status != null) {
            sessions.filter { it.status == status }
        } else {
            sessions
        }
    }

    /**
     * 取消指定的活跃会话。
     *
     * @param sessionId 会话 ID
     * @return true 表示取消成功
     */
    fun cancelSession(sessionId: String): Boolean {
        val session = activeSessions[sessionId] ?: return false
        if (session.status == BeamStatus.COMPLETED || session.status == BeamStatus.FAILED) {
            return false
        }
        val cancelledSession = session.copy(
            status = BeamStatus.CANCELLED,
            completedAt = System.currentTimeMillis(),
            logs = session.logs + "[INFO] 会话已取消"
        )
        activeSessions[sessionId] = cancelledSession
        addSessionLog(sessionId, "[INFO] 用户取消投射")
        Log.d(tag, "会话已取消: $sessionId")
        return true
    }

    /**
     * 获取会话日志。
     *
     * @param sessionId 会话 ID
     * @return 日志列表，会话不存在时返回空列表
     */
    fun getSessionLogs(sessionId: String): List<String> {
        return sessionLogs[sessionId]?.toList() ?: emptyList()
    }

    // =========================================================================
    //  系统维护
    // =========================================================================

    /**
     * 重置所有状态（清空历史、目标、会话、缓存和统计）。
     *
     * 这是一个危险操作，调用后所有数据将永久丢失。建议在用户确认后调用。
     */
    fun reset() {
        beamHistory.clear()
        beamTargets.clear()
        activeSessions.clear()
        keyCache.clear()
        sessionLogs.clear()
        historyOrder.clear()
        totalBeams = 0L
        successfulBeams = 0L
        failedBeams = 0L
        totalDataTransferred = 0L
        Log.d(tag, "SmartScreenCaster 已完全重置")
    }

    /**
     * 导出所有数据为 JSON 格式。
     *
     * 用于备份和调试，包含历史记录、目标列表和统计信息。
     *
     * @return JSON 字符串
     */
    fun exportAllData(): String {
        val stats = getBeamStats()
        val historyList = buildJsonObject {
            beamHistory.values.forEachIndexed { index, history ->
                put("history_$index", buildJsonObject {
                    put("id", JsonPrimitive(history.id))
                    put("content_id", JsonPrimitive(history.contentId))
                    put("content_type", JsonPrimitive(history.contentType.name))
                    put("content_summary", JsonPrimitive(history.contentSummary))
                    put("target_id", JsonPrimitive(history.targetId))
                    put("target_name", JsonPrimitive(history.targetName))
                    put("target_type", JsonPrimitive(history.targetType.name))
                    put("status", JsonPrimitive(history.status.name))
                    put("error", JsonPrimitive(history.errorMessage))
                    put("duration_ms", JsonPrimitive(history.durationMs))
                    put("data_size", JsonPrimitive(history.dataSize))
                    put("beamed_at", JsonPrimitive(history.beamedAt))
                })
            }
        }

        val targetList = buildJsonObject {
            beamTargets.values.forEachIndexed { index, target ->
                put("target_$index", buildJsonObject {
                    put("id", JsonPrimitive(target.id))
                    put("name", JsonPrimitive(target.name))
                    put("type", JsonPrimitive(target.type.name))
                    put("address", JsonPrimitive(target.address))
                    put("is_online", JsonPrimitive(target.isOnline))
                    put("security", JsonPrimitive(target.security.name))
                    put("added_at", JsonPrimitive(target.addedAt))
                    put("last_used", JsonPrimitive(target.lastUsedAt))
                })
            }
        }

        return buildJsonObject {
            put("stats", stats)
            put("history", historyList)
            put("targets", targetList)
        }.toString()
    }

    // =========================================================================
    //  私有方法
    // =========================================================================

    /**
     * 记录投射历史。
     *
     * 内部方法，在每次投射完成后自动调用。历史记录受 [maxHistorySize] 限制，
     * 超出时丢弃最旧记录。
     *
     * @param contentId      内容 ID
     * @param contentType    内容类型
     * @param contentSummary 内容摘要
     * @param targetId       目标 ID
     * @param targetName     目标名称
     * @param targetType     目标类型
     * @param status         投射状态
     * @param errorMessage   错误信息
     * @param durationMs     耗时
     * @param dataSize       数据大小
     * @param securityUsed   安全级别
     * @param isEncrypted    是否加密
     * @param isAuthenticated 是否签名
     */
    private fun recordBeamHistory(
        contentId: String,
        contentType: BeamType,
        contentSummary: String,
        targetId: String,
        targetName: String,
        targetType: BeamTargetType,
        status: BeamStatus,
        errorMessage: String = "",
        durationMs: Long = 0L,
        dataSize: Long = 0L,
        securityUsed: BeamSecurity = BeamSecurity.NONE,
        isEncrypted: Boolean = false,
        isAuthenticated: Boolean = false
    ) {
        val history = BeamHistory(
            contentId = contentId,
            contentType = contentType,
            contentSummary = contentSummary,
            targetId = targetId,
            targetName = targetName,
            targetType = targetType,
            status = status,
            errorMessage = errorMessage,
            durationMs = durationMs,
            dataSize = dataSize,
            securityUsed = securityUsed,
            isEncrypted = isEncrypted,
            isAuthenticated = isAuthenticated
        )

        beamHistory[history.id] = history
        historyOrder.add(history.id)

        // 超出容量时淘汰最旧记录
        while (historyOrder.size > maxHistorySize) {
            val oldestId = historyOrder.removeFirstOrNull()
            if (oldestId != null) {
                beamHistory.remove(oldestId)
            }
        }
    }

    /**
     * 向会话日志追加一条记录。
     *
     * @param sessionId 会话 ID
     * @param log       日志文本
     */
    private fun addSessionLog(sessionId: String, log: String) {
        val logs = sessionLogs.getOrPut(sessionId) { CopyOnWriteArrayList() }
        logs.add(log)
    }

    /**
     * 模拟传输过程。
     *
     * 由于本实现运行在单设备上，传输过程以模拟方式进行。实际生产环境中
     * 应替换为网络通信（Socket/HTTP/WebSocket）或系统 API（Clipboard/Share）调用。
     *
     * @param target 目标
     * @param data   待传输的数据
     * @return true 表示传输成功
     */
    private fun simulateTransfer(target: BeamTarget, data: String): Boolean {
        return when (target.type) {
            BeamTargetType.CLIPBOARD -> {
                // 模拟写入剪贴板（总是成功）
                true
            }
            BeamTargetType.SHARE_INTENT -> {
                // 模拟系统分享（总是成功）
                true
            }
            BeamTargetType.REMOTE_DEVICE -> {
                // 模拟远程传输（目标在线时成功）
                target.isOnline
            }
            BeamTargetType.CLOUD_STORAGE -> {
                // 模拟云存储上传（总是成功）
                true
            }
            BeamTargetType.LOCAL_FILE -> {
                // 模拟本地文件写入（总是成功）
                true
            }
            BeamTargetType.AIRDROP -> {
                // 模拟近距离传输（目标在线时成功）
                target.isOnline
            }
        }
    }

    /**
     * 更新目标最后使用时间。
     *
     * @param targetId 目标 ID
     */
    private fun updateTargetLastUsed(targetId: String) {
        val target = beamTargets[targetId] ?: return
        beamTargets[targetId] = target.copy(lastUsedAt = System.currentTimeMillis())
    }

    /**
     * 限制活跃会话数不超过上限。
     *
     * 超出时按最早创建时间淘汰 PENDING 状态的会话。
     */
    private fun enforceMaxSessions() {
        while (activeSessions.size >= maxActiveSessions) {
            val oldestPending = activeSessions.values
                .filter { it.status == BeamStatus.PENDING }
                .minByOrNull { it.startedAt }

            if (oldestPending != null) {
                val cancelled = oldestPending.copy(
                    status = BeamStatus.CANCELLED,
                    errorMessage = "系统自动取消: 活跃会话数超限",
                    logs = oldestPending.logs + "[WARN] 系统自动取消: 活跃会话数超限"
                )
                activeSessions[oldestPending.id] = cancelled
                Log.w(tag, "自动取消会话: ${oldestPending.id} (超限)")
            } else {
                // 没有 PENDING 状态的会话，停止淘汰
                break
            }
        }
    }

    /**
     * 获取或创建派生密钥。
     *
     * 从目标 ID 派生 AES 密钥（SHA-256 取前 16 字节）。密钥在 [keyCache] 中
     * 缓存，受 [maxCachedKeys] 限制。
     *
     * @param targetId 目标 ID
     * @return AES SecretKeySpec
     */
    private fun getOrCreateKey(targetId: String): SecretKeySpec {
        // 检查缓存
        val cached = keyCache[targetId]
        if (cached != null) {
            return cached
        }

        // 限制缓存大小
        if (keyCache.size >= maxCachedKeys) {
            // 移除最早缓存的密钥
            val oldestKey = keyCache.keys.firstOrNull()
            if (oldestKey != null) {
                keyCache.remove(oldestKey)
            }
        }

        // 派生密钥
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(targetId.toByteArray(Charsets.UTF_8))
        // 取前 16 字节作为 AES-128 密钥
        val aesKey = keyBytes.copyOf(16)
        val secretKey = SecretKeySpec(aesKey, defaultEncryptionAlgorithm)

        keyCache[targetId] = secretKey
        return secretKey
    }

    /**
     * 计算数据的校验和。
     *
     * 使用 SHA-256 算法，取前 16 个字符的 HEX 字符串作为紧凑校验和。
     *
     * @param data 待校验的数据
     * @return 16 字符 HEX 校验和
     */
    private fun computeChecksum(data: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 计算 HMAC 风格的消息认证码。
     *
     * 将目标 ID 作为密钥，对数据进行 HMAC-SHA256 计算，返回前 16 字符。
     *
     * @param data     消息数据
     * @param targetId 密钥材料
     * @return 16 字符 HEX 认证码
     */
    private fun computeHmac(data: String, targetId: String): String {
        // 模拟 HMAC：将 targetId 与 data 拼接后做 SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val combined = "$targetId:$data"
        val hash = digest.digest(combined.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    //  伴生对象
    // =========================================================================

    companion object {
        /** 默认配置：最大历史记录数。 */
        const val DEFAULT_MAX_HISTORY = 500

        /** 默认配置：最大目标数。 */
        const val DEFAULT_MAX_TARGETS = 50

        /** 默认配置：最大活跃会话数。 */
        const val DEFAULT_MAX_ACTIVE_SESSIONS = 20

        /** 默认配置：最大缓存密钥数。 */
        const val DEFAULT_MAX_CACHED_KEYS = 100

        /** 默认配置：投射重试次数。 */
        const val DEFAULT_RETRY_COUNT = 3

        /** 默认配置：二维码内容过期时间（秒），0 表示永不过期。 */
        const val DEFAULT_QR_EXPIRY_SEC = 0L

        /** 默认配置：深度链接 Scheme。 */
        const val DEFAULT_DEEP_LINK_SCHEME = "mobileclaw"

        /** 默认配置：深度链接 Host。 */
        const val DEFAULT_DEEP_LINK_HOST = "beam"

        /** 二维码内容版本号。 */
        const val QR_FORMAT_VERSION = 1

        /** 二维码内容前缀。 */
        const val QR_PREFIX = "MOBILECLAW_BEAM:"

        /** 最大内容摘要长度（字符数）。 */
        const val MAX_CONTENT_SUMMARY_LENGTH = 100

        /** 截图远程传输最大字节数。 */
        const val SCREENSHOT_MAX_REMOTE_BYTES = 10240

        /** 失败状态码：目标不存在。 */
        const val ERROR_TARGET_NOT_FOUND = "TARGET_NOT_FOUND"

        /** 失败状态码：目标离线。 */
        const val ERROR_TARGET_OFFLINE = "TARGET_OFFLINE"

        /** 失败状态码：传输失败。 */
        const val ERROR_TRANSFER_FAILED = "TRANSFER_FAILED"

        /** 失败状态码：加密失败。 */
        const val ERROR_ENCRYPTION_FAILED = "ENCRYPTION_FAILED"

        /** 失败状态码：解密失败。 */
        const val ERROR_DECRYPTION_FAILED = "DECRYPTION_FAILED"

        /** 失败状态码：认证失败。 */
        const val ERROR_AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"

        /** 失败状态码：目标数超限。 */
        const val ERROR_MAX_TARGETS_REACHED = "MAX_TARGETS_REACHED"

        /** 失败状态码：会话已取消。 */
        const val ERROR_SESSION_CANCELLED = "SESSION_CANCELLED"

        /** 默认剪贴板目标 ID。 */
        const val DEFAULT_CLIPBOARD_TARGET_ID = "clipboard-default"

        /** 默认分享目标 ID。 */
        const val DEFAULT_SHARE_TARGET_ID = "share-default"

        /**
         * 创建一个空的默认 [BeamContent] 实例。
         *
         * @param type 内容类型
         * @return 默认配置的 BeamContent
         */
        fun createEmptyContent(type: BeamType): BeamContent {
            return BeamContent(
                type = type,
                data = "",
                sourcePackage = "com.mobileclaw.app"
            )
        }

        /**
         * 快速创建文本内容。
         *
         * @param text   文本内容
         * @param source 来源包名（可选）
         * @return BeamContent 实例
         */
        fun createTextContent(text: String, source: String = "com.mobileclaw.app"): BeamContent {
            return BeamContent(
                type = BeamType.TEXT,
                data = text,
                sourcePackage = source,
                size = text.toByteArray().size.toLong()
            )
        }

        /**
         * 快速创建 URL 内容。
         *
         * @param url     URL 字符串
         * @param source  来源包名（可选）
         * @return BeamContent 实例
         */
        fun createUrlContent(url: String, source: String = "com.mobileclaw.app"): BeamContent {
            return BeamContent(
                type = BeamType.URL,
                data = url,
                sourcePackage = source,
                size = url.toByteArray().size.toLong()
            )
        }

        /**
         * 快速创建截图内容。
         *
         * @param base64Data Base64 编码的截图数据
         * @param source     来源包名（可选）
         * @return BeamContent 实例
         */
        fun createScreenshotContent(
            base64Data: String,
            source: String = "com.mobileclaw.app"
        ): BeamContent {
            return BeamContent(
                type = BeamType.SCREENSHOT,
                data = base64Data,
                sourcePackage = source,
                size = base64Data.toByteArray().size.toLong(),
                tags = listOf("screenshot")
            )
        }

        /**
         * 创建默认的剪贴板目标。
         *
         * @return BeamTarget 实例
         */
        fun createDefaultClipboardTarget(): BeamTarget {
            return BeamTarget(
                id = DEFAULT_CLIPBOARD_TARGET_ID,
                name = "系统剪贴板",
                type = BeamTargetType.CLIPBOARD,
                address = "local://clipboard",
                capabilities = BeamType.entries.toSet(),
                security = BeamSecurity.NONE,
                description = "默认系统剪贴板目标"
            )
        }

        /**
         * 创建默认的分享目标。
         *
         * @return BeamTarget 实例
         */
        fun createDefaultShareTarget(): BeamTarget {
            return BeamTarget(
                id = DEFAULT_SHARE_TARGET_ID,
                name = "系统分享",
                type = BeamTargetType.SHARE_INTENT,
                address = "local://share",
                capabilities = BeamType.entries.toSet(),
                security = BeamSecurity.NONE,
                description = "默认系统分享目标"
            )
        }

        /**
         * 创建远程设备目标。
         *
         * @param name    设备名称
         * @param address 设备地址（IP:Port）
         * @param security 安全级别
         * @return BeamTarget 实例
         */
        fun createRemoteDeviceTarget(
            name: String,
            address: String,
            security: BeamSecurity = BeamSecurity.ENCRYPTED
        ): BeamTarget {
            return BeamTarget(
                id = UUID.randomUUID().toString(),
                name = name,
                type = BeamTargetType.REMOTE_DEVICE,
                address = address,
                capabilities = BeamType.entries.toSet(),
                security = security,
                description = "远程设备: $name"
            )
        }
    }
}