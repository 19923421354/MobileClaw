package com.mobileclaw.app.ai

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 敏感数据类型枚举。
 *
 * 表示 [SecurityPrivacyManager] 能够识别和处理的各类敏感数据。
 * 每种类型对应一组正则匹配模式，用于 [SecurityPrivacyManager.detectSensitiveData] 的检测逻辑。
 * 其中 [UNKNOWN] 作为兜底类型，用于无法归类的匹配结果。
 *
 * - [PASSWORD] 密码：各类网站的登录密码、应用密码等。
 * - [OTP_CODE] 一次性验证码：短信或邮件中的 4~8 位数字验证码。
 * - [CREDIT_CARD] 信用卡号：符合 Luhn 算法的 16 位银行卡/信用卡号。
 * - [PHONE_NUMBER] 手机号：中国大陆 11 位手机号码。
 * - [ID_NUMBER] 身份证号：中国大陆 18 位公民身份证号码。
 * - [ADDRESS] 地址：包含省市区的详细地址文本。
 * - [EMAIL] 电子邮件地址：符合 RFC 5322 基本格式的邮箱地址。
 * - [API_KEY] API 密钥：常见格式的 Token 或密钥字符串。
 * - [PERSONAL_NAME] 个人姓名：中文姓名（2~4 个汉字）。
 * - [BANK_ACCOUNT] 银行卡号/银行账户：数字账户标识。
 * - [UNKNOWN] 未知类型：无法归类的敏感数据。
 */
enum class DataType {
    /** 密码：各类网站的登录密码、应用密码等。 */
    PASSWORD,

    /** 一次性验证码：短信或邮件中的 4~8 位数字验证码。 */
    OTP_CODE,

    /** 信用卡号：符合 Luhn 算法的 16 位银行卡/信用卡号。 */
    CREDIT_CARD,

    /** 手机号：中国大陆 11 位手机号码。 */
    PHONE_NUMBER,

    /** 身份证号：中国大陆 18 位公民身份证号码。 */
    ID_NUMBER,

    /** 地址：包含省市区的详细地址文本。 */
    ADDRESS,

    /** 电子邮件地址：符合 RFC 5322 基本格式的邮箱地址。 */
    EMAIL,

    /** API 密钥：常见格式的 Token 或密钥字符串。 */
    API_KEY,

    /** 个人姓名：中文姓名（2~4 个汉字）。 */
    PERSONAL_NAME,

    /** 银行卡号/银行账户：数字账户标识。 */
    BANK_ACCOUNT,

    /** 未知类型：无法归类的敏感数据。 */
    UNKNOWN
}

/**
 * 敏感度级别枚举。
 *
 * 表示敏感数据的危害程度或保密等级，用于决定数据脱敏策略
 * 以及安全模式的拦截行为。级别越高，保护措施越严格。
 *
 * - [CRITICAL] 极严重：一旦泄露将造成严重危害（如密码、API Key）。
 * - [HIGH] 高：泄露会造成较大危害（如身份证号、信用卡号）。
 * - [MEDIUM] 中：泄露会造成一定危害（如手机号、地址）。
 * - [LOW] 低：泄露会造成轻微危害（如姓名、邮箱）。
 */
enum class SensitivityLevel {
    /** 极严重：一旦泄露将造成严重危害（如密码、API Key）。 */
    CRITICAL,

    /** 高：泄露会造成较大危害（如身份证号、信用卡号）。 */
    HIGH,

    /** 中：泄露会造成一定危害（如手机号、地址）。 */
    MEDIUM,

    /** 低：泄露会造成轻微危害（如姓名、邮箱）。 */
    LOW
}

/**
 * 审计动作类型枚举。
 *
 * 表示 [SecurityPrivacyManager] 在安全审计日志中记录的动作类别，
 * 用于追踪每次安全事件的处置结果。详见 [SecurityAuditLog.action]。
 *
 * - [EXECUTED] 已执行：动作已正常执行（未涉及敏感数据或权限已满足）。
 * - [BLOCKED] 已拦截：因安全策略或安全模式被拦截。
 * - [MASKED] 已脱敏：敏感数据已被脱敏处理后再发送。
 * - [CONFIRMED] 已确认：用户已确认执行敏感操作。
 * - [REJECTED] 已拒绝：用户拒绝确认敏感操作。
 * - [ERROR] 异常：安全处理过程中发生异常。
 */
enum class AuditAction {
    /** 已执行：动作已正常执行（未涉及敏感数据或权限已满足）。 */
    EXECUTED,

    /** 已拦截：因安全策略或安全模式被拦截。 */
    BLOCKED,

    /** 已脱敏：敏感数据已被脱敏处理后再发送。 */
    MASKED,

    /** 已确认：用户已确认执行敏感操作。 */
    CONFIRMED,

    /** 已拒绝：用户拒绝确认敏感操作。 */
    REJECTED,

    /** 异常：安全处理过程中发生异常。 */
    ERROR
}

/**
 * 安全模式级别枚举。
 *
 * 表示 [SecurityPrivacyManager] 的安全模式等级，等级越高，
 * 被拦截的潜危动作类型越多。通过 [SecurityPrivacyManager.setSafeMode] 设置。
 *
 * - [OFF] 关闭：不拦截任何动作，仅记录日志。
 * - [BASIC] 基础：拦截最高危动作（卸载应用、删除数据、支付操作）。
 * - [STRICT] 严格：在基础之上额外拦截安装应用、执行 Shell 命令、发送消息等。
 * - [PARANOID] 偏执：拦截所有可能产生副作用的操作，仅允许读取和导航类动作。
 */
enum class SafeModeLevel {
    /** 关闭：不拦截任何动作，仅记录日志。 */
    OFF,

    /** 基础：拦截最高危动作（卸载应用、删除数据、支付操作）。 */
    BASIC,

    /** 严格：在基础之上额外拦截安装应用、执行 Shell 命令、发送消息等。 */
    STRICT,

    /** 偏执：拦截所有可能产生副作用的操作，仅允许读取和导航类动作。 */
    PARANOID
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 敏感数据匹配结果。
 *
 * 描述 [SecurityPrivacyManager.detectSensitiveData] 在文本中检测到的一条敏感数据。
 * 包含匹配位置、原始文本片段、数据类型和敏感度级别，供脱敏和审计使用。
 *
 * @property dataType        敏感数据类型。
 * @property sensitivityLevel 敏感度级别，由 [dataType] 在检测时自动映射。
 * @property originalText    原始匹配文本片段。
 * @property startIndex      匹配文本在原始字符串中的起始索引。
 * @property endIndex        匹配文本在原始字符串中的结束索引（不含）。
 * @property maskedText      脱敏后的替换文本，由 [maskSensitiveData] 填充。
 * @property detectedAt      检测时间戳（epoch 毫秒）。
 */
data class SensitiveDataMatch(
    val dataType: DataType,
    val sensitivityLevel: SensitivityLevel,
    val originalText: String,
    val startIndex: Int,
    val endIndex: Int,
    val maskedText: String = "",
    val detectedAt: Long = System.currentTimeMillis()
)

/**
 * 安全审计日志条目。
 *
 * 记录一次安全相关事件的完整信息，供 [SecurityPrivacyManager.logAudit] 写入
 * 审计存储，并通过 [SecurityPrivacyManager.getAuditLog] 查询。
 * 每条日志包含唯一的日志 ID、时间戳和操作详情。
 *
 * @property logId          日志唯一标识符（UUID）。
 * @property timestamp      事件发生时间戳（epoch 毫秒）。
 * @property action         审计动作类型。
 * @property actionType     关联的动作类型名称（如 "APP_UNINSTALL"）。
 * @property dataType       涉及的敏感数据类型（非敏感操作为 null）。
 * @property summary        事件摘要描述（中文）。
 * @property detail         详细描述（中文，含上下文信息）。
 * @property sourceContext  来源上下文描述（如 "screen_text", "user_input", "action_param"）。
 * @property safeModeLevel  记录时的安全模式级别。
 * @property isMasked       是否经过脱敏处理。
 * @property durationMs     处理耗时（毫秒），0 表示未记录。
 */
data class SecurityAuditLog(
    val logId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val action: AuditAction,
    val actionType: String,
    val dataType: DataType? = null,
    val summary: String,
    val detail: String = "",
    val sourceContext: String = "",
    val safeModeLevel: SafeModeLevel = SafeModeLevel.OFF,
    val isMasked: Boolean = false,
    val durationMs: Long = 0L
)

/**
 * 权限检查结果。
 *
 * 描述 [SecurityPrivacyManager.checkPermission] 对某动作类型所需权限的检查结果。
 * 包含所需权限列表、当前缺失权限列表和建议处理方式。
 *
 * @property actionType          检查的动作类型名称。
 * @property requiredPermissions 该动作类型所需的全部权限列表。
 * @property missingPermissions  当前缺失的权限列表。
 * @property hasAllPermissions   是否已具备全部所需权限。
 * @property suggestion          缺失权限时的处理建议（中文）。
 * @property checkedAt           检查时间戳（epoch 毫秒）。
 */
data class PermissionCheck(
    val actionType: String,
    val requiredPermissions: List<String>,
    val missingPermissions: List<String>,
    val hasAllPermissions: Boolean,
    val suggestion: String = "",
    val checkedAt: Long = System.currentTimeMillis()
)

/**
 * 敏感动作描述。
 *
 * 描述 [SecurityPrivacyManager.isSensitiveAction] 识别出的一个需要用户确认的敏感操作。
 * 包含动作类型、风险等级、涉及的数据类型和风险说明。
 *
 * @property actionType      动作类型名称。
 * @param actionDescription  动作的人类可读描述（中文）。
 * @property riskLevel       风险等级描述（"CRITICAL", "HIGH", "MEDIUM", "LOW"）。
 * @property involvedDataTypes 涉及的数据类型列表。
 * @property warningMessage  风险警告消息（中文）。
 * @property requiresConfirmation 是否需要用户确认。
 * @property detectedAt      检测时间戳（epoch 毫秒）。
 */
data class SensitiveAction(
    val actionType: String,
    val actionDescription: String,
    val riskLevel: String,
    val involvedDataTypes: List<DataType>,
    val warningMessage: String,
    val requiresConfirmation: Boolean,
    val detectedAt: Long = System.currentTimeMillis()
)

/**
 * 数据保留策略。
 *
 * 定义 [SecurityPrivacyManager] 自动清理日志和缓存数据的配置参数。
 * 可通过构造器传入自定义策略，或使用 [SecurityPrivacyManager] 的默认策略。
 * 各字段的单位为毫秒，设置为 0 或负数表示不自动清理该类型数据。
 *
 * @property auditLogRetentionMs     审计日志保留时长（毫秒），默认 30 天。
 * @property cacheDataRetentionMs    缓存数据保留时长（毫秒），默认 24 小时。
 * @property sensitiveStatsRetentionMs 敏感数据统计保留时长（毫秒），默认 7 天。
 * @property enableAutoCleanup       是否启用自动清理（默认 true）。
 * @property cleanupIntervalMs       自动清理检查间隔（毫秒），默认 1 小时。
 */
data class DataRetentionPolicy(
    val auditLogRetentionMs: Long = 30L * 24 * 60 * 60 * 1000,  // 30 天
    val cacheDataRetentionMs: Long = 24L * 60 * 60 * 1000,      // 24 小时
    val sensitiveStatsRetentionMs: Long = 7L * 24 * 60 * 60 * 1000, // 7 天
    val enableAutoCleanup: Boolean = true,
    val cleanupIntervalMs: Long = 60L * 60 * 1000  // 1 小时
)

// =============================================================================
//  SecurityPrivacyManager —— 安全与隐私管理
// =============================================================================

/**
 * SecurityPrivacyManager —— 安全与隐私管理
 *
 * 为 MobileClaw 的自动化执行引擎提供全面的安全与隐私保护能力，确保敏感数据
 * 在自动操作过程中不会被泄露，并维护用户隐私与数据安全。
 *
 * ### 八大核心能力
 *
 * 1. **敏感数据检测**：[detectSensitiveData] 使用正则表达式在屏幕文本中检测
 *    密码、OTP 验证码、信用卡号、手机号、身份证号、地址、邮箱、API Key、
 *    个人姓名、银行卡号等 10 类敏感数据，并返回匹配位置与敏感度级别。
 *
 * 2. **数据脱敏**：[maskSensitiveData] 基于检测结果将敏感数据替换为
 *    占位符（如 `***`、`[手机号已隐藏]`），确保发送给 AI API 的数据
 *    不包含原始明文敏感信息。
 *
 * 3. **权限感知执行**：[checkPermission] 维护各动作类型与 Android 权限的
 *    映射关系，在执行前检查权限是否满足，并给出缺失权限的提示与建议。
 *
 * 4. **敏感动作确认**：[isSensitiveAction] 标记需要用户确认的高危操作
 *    （卸载应用、发送消息、支付操作、删除数据等），[requestConfirmation]
 *    模拟确认流程以提醒用户审慎操作。
 *
 * 5. **审计日志**：[logAudit] 将所有安全事件记录到线程安全的审计日志存储中，
 *    [getAuditLog] 支持按条件查询历史日志。
 *
 * 6. **数据保留策略**：[DataRetentionPolicy] 可配置审计日志、缓存数据、
 *    敏感统计的保留时长，[cleanUpOldData] 自动清理过期数据。
 *
 * 7. **安全模式**：[SafeModeLevel] 四级安全模式（OFF / BASIC / STRICT / PARANOID），
 *    [setSafeMode] 动态切换，拦截对应级别的危险操作。
 *
 * 8. **敏感数据统计**：[getSensitiveStats] 汇总各类敏感数据的检测频率、
 *    脱敏次数和拦截次数，帮助用户了解安全态势。
 *
 * ### 各数据类型与敏感度级别映射
 *
 * | 数据类型           | 敏感度级别 | 示例                          |
 * |--------------------|------------|-------------------------------|
 * | PASSWORD           | CRITICAL   | `myp@ssw0rd!`                |
 * | API_KEY            | CRITICAL   | `sk-xxxxxxxxxxxxxxxx`         |
 * | CREDIT_CARD        | HIGH       | `4111 1111 1111 1111`        |
 * | ID_NUMBER          | HIGH       | `110101199001011234`          |
 * | BANK_ACCOUNT       | HIGH       | `6222021234567890`            |
 * | PHONE_NUMBER       | MEDIUM     | `13800138000`                 |
 * | OTP_CODE           | MEDIUM     | `123456`                      |
 * | ADDRESS            | MEDIUM     | `北京市朝阳区xxx`             |
 * | EMAIL              | LOW        | `user@example.com`            |
 * | PERSONAL_NAME      | LOW        | `张三`                        |
 *
 * ### 敏感动作分类与安全模式拦截表
 *
 * | 动作类型               | 风险等级 | BASIC | STRICT | PARANOID |
 * |------------------------|----------|-------|--------|----------|
 * | APP_UNINSTALL          | CRITICAL | 拦截   | 拦截    | 拦截     |
 * | FILE_DELETE            | CRITICAL | 拦截   | 拦截    | 拦截     |
 * | PAYMENT_OPERATION      | CRITICAL | 拦截   | 拦截    | 拦截     |
 * | APP_INSTALL            | HIGH     | 通过   | 拦截    | 拦截     |
 * | SHELL_EXEC             | HIGH     | 通过   | 拦截    | 拦截     |
 * | NOTIFY_SEND            | MEDIUM   | 通过   | 拦截    | 拦截     |
 * | CLIPBOARD_PASTE        | MEDIUM   | 通过   | 通过    | 拦截     |
 * | FILE_WRITE             | MEDIUM   | 通过   | 通过    | 拦截     |
 * | SYSTEM_KILL_PROCESS    | HIGH     | 通过   | 拦截    | 拦截     |
 * | SYSTEM_CLEAR_CACHE     | LOW      | 通过   | 通过    | 拦截     |
 * | 其他只读/导航操作       | 安全     | 通过   | 通过    | 通过     |
 *
 * ### 线程安全
 *
 * - 所有存储均使用 [ConcurrentHashMap]，保证多线程并发读写安全。
 * - 计数器使用 [AtomicInteger] 和 [AtomicLong]。
 * - 可变状态（安全模式、保留策略）使用 [@Volatile] 保证可见性。
 * - 正则表达式 [Pattern] 对象在构造时预编译，线程安全可复用。
 *
 * ### 典型调用流程
 * ```
 * val security = SecurityPrivacyManager()
 *
 * // 1. 检测屏幕文本中的敏感数据
 * val matches = security.detectSensitiveData("验证码: 123456, 密码: myP@ss!")
 * // 返回: [DataType.OTP_CODE, DataType.PASSWORD]
 *
 * // 2. 脱敏后发送给 AI API
 * val maskedText = security.maskSensitiveData("验证码: 123456, 密码: myP@ss!")
 * // 返回: "验证码: [验证码已隐藏], 密码: [密码已隐藏]"
 *
 * // 3. 执行前检查权限
 * val permCheck = security.checkPermission("APP_UNINSTALL")
 * if (!permCheck.hasAllPermissions) {
 *     // 提示用户授予缺失权限
 * }
 *
 * // 4. 检查是否为敏感动作
 * val sensitiveAction = security.isSensitiveAction("APP_UNINSTALL", "卸载微信")
 * if (sensitiveAction.requiresConfirmation) {
 *     val confirmed = security.requestConfirmation(sensitiveAction)
 * }
 *
 * // 5. 记录审计日志
 * security.logAudit(SecurityAuditLog(action = AuditAction.EXECUTED, ...))
 *
 * // 6. 设置安全模式
 * security.setSafeMode(SafeModeLevel.STRICT)
 *
 * // 7. 查询审计日志
 * val logs = security.getAuditLog(limit = 50)
 *
 * // 8. 获取敏感数据检测统计
 * val stats = security.getSensitiveStats()
 *
 * // 9. 手动触发数据清理
 * security.cleanUpOldData()
 * ```
 *
 * @param retentionPolicy 数据保留策略，默认使用 [DataRetentionPolicy] 的默认值。
 * @param initialSafeMode 初始安全模式级别，默认 [SafeModeLevel.BASIC]。
 */
class SecurityPrivacyManager(
    private val retentionPolicy: DataRetentionPolicy = DataRetentionPolicy(),
    private val initialSafeMode: SafeModeLevel = SafeModeLevel.BASIC
) {

    // =========================================================================
    //  常量定义
    // =========================================================================

    companion object {
        /** 日志标签。 */
        private const val TAG = "SecurityPrivacyManager"

        // ---- 正则表达式常量 ----

        /**
         * 密码检测正则。
         *
         * 匹配 8~32 位的常见密码格式，包含至少一个字母和数字组合。
         * 不匹配纯数字或纯字母，以减少误报。
         * 在屏幕文本中，密码通常出现在登录/注册页面。
         */
        private val PASSWORD_PATTERN: Pattern = Pattern.compile(
            "(?:(?=.*[a-zA-Z])(?=.*\\d)[a-zA-Z\\d!@#\$%^&*()_+\\-={}\\[\\]:;\"'<>,.?/~`]{8,32})"
        )

        /**
         * OTP 验证码检测正则。
         *
         * 匹配 4~8 位纯数字验证码，常见于短信验证码场景。
         * 通过前后边界限定避免匹配到长数字串中的片段。
         * 常见触发词：验证码、验证码为、OTP、code 等。
         */
        private val OTP_PATTERN: Pattern = Pattern.compile(
            "(?:\\b\\d{4,8}\\b)"
        )

        /**
         * 信用卡号检测正则（含 Luhn 算法校验说明）。
         *
         * 匹配 13~19 位数字，支持可选的分隔符（空格或连字符）。
         * 常见格式：`4111 1111 1111 1111`、`4111-1111-1111-1111`、`4111111111111111`。
         * 注意：此正则仅做格式匹配，实际 Luhn 校验在 [isValidLuhn] 中实现。
         */
        private val CREDIT_CARD_PATTERN: Pattern = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,19}\\b"
        )

        /**
         * 中国大陆手机号检测正则。
         *
         * 匹配以 1 开头、第二位为 3~9 的 11 位手机号码。
         * 覆盖中国移动、联通、电信、广电等所有号段。
         */
        private val PHONE_PATTERN: Pattern = Pattern.compile(
            "\\b1[3-9]\\d{9}\\b"
        )

        /**
         * 中国大陆身份证号检测正则。
         *
         * 匹配 18 位身份证号码，前 17 位为数字，最后一位为数字或 X/x。
         * 包含出生日期的基本格式校验（年份 1900~2099）。
         * 注意：实际校验和计算在 [isValidIdNumber] 中实现。
         */
        private val ID_NUMBER_PATTERN: Pattern = Pattern.compile(
            "\\b[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]\\b"
        )

        /**
         * 地址检测正则。
         *
         * 匹配包含省/市/区/县/镇/村/路/街/号/室等地址关键词的文本。
         * 地址检测采用关键词匹配策略，可能产生较多误报，
         * 因此敏感度级别设为 [SensitivityLevel.MEDIUM]。
         */
        private val ADDRESS_PATTERN: Pattern = Pattern.compile(
            "(?:[\\u4e00-\\u9fa5]{2,4}(?:省|市|区|县|镇|乡|村|路|街|道|巷|弄|号|楼|层|室|栋|单元|组))"
        )

        /**
         * 电子邮件地址检测正则。
         *
         * 匹配常见格式的邮箱地址，支持点号、下划线、连字符等字符。
         * 符合 RFC 5322 的基本格式要求。
         */
        private val EMAIL_PATTERN: Pattern = Pattern.compile(
            "\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"
        )

        /**
         * API 密钥检测正则。
         *
         * 匹配常见 API 密钥格式：
         * - sk- 开头（OpenAI 风格）
         * - 32 位以上的 Base64 或 Hex 字符串
         * - 包含 key/token/secret 前缀的敏感字符串
         */
        private val API_KEY_PATTERN: Pattern = Pattern.compile(
            "\\b(?:sk-[a-zA-Z0-9]{20,}|[a-zA-Z0-9_-]{32,}|(?:key|token|secret)[:=]\\s*[a-zA-Z0-9_\\-]{16,})\\b"
        )

        /**
         * 中文姓名检测正则。
         *
         * 匹配 2~4 个汉字的中文姓名。
         * 注意：此检测较为粗略，可能将非姓名文本误判为姓名，
         * 因此敏感度级别设为 [SensitivityLevel.LOW]。
         */
        private val NAME_PATTERN: Pattern = Pattern.compile(
            "\\b[\\u4e00-\\u9fa5]{2,4}\\b"
        )

        /**
         * 银行卡号检测正则。
         *
         * 匹配 16~19 位纯数字的银行卡号。
         * 与信用卡号检测的区别在于：银行卡号不包含分隔符，
         * 且长度范围更宽（16~19 位）。
         */
        private val BANK_ACCOUNT_PATTERN: Pattern = Pattern.compile(
            "\\b\\d{16,19}\\b"
        )

        // ---- 敏感动作定义 ----

        /**
         * 危险动作列表：需要用户确认的高风险操作。
         *
         * 键为动作类型名称，值为 Pair(风险等级, 中文描述)。
         * 用于 [isSensitiveAction] 判断动作是否敏感，
         * 以及 [requestConfirmation] 生成确认提示。
         */
        private val SENSITIVE_ACTIONS: Map<String, Pair<String, String>> = mapOf(
            "APP_UNINSTALL" to Pair("CRITICAL", "卸载应用程序"),
            "APP_INSTALL" to Pair("HIGH", "安装应用程序"),
            "FILE_DELETE" to Pair("CRITICAL", "删除文件"),
            "FILE_WRITE" to Pair("MEDIUM", "写入文件"),
            "SHELL_EXEC" to Pair("HIGH", "执行 Shell 命令"),
            "NOTIFY_SEND" to Pair("MEDIUM", "发送通知"),
            "CLIPBOARD_PASTE" to Pair("MEDIUM", "粘贴剪贴板内容"),
            "SYSTEM_KILL_PROCESS" to Pair("HIGH", "结束系统进程"),
            "SYSTEM_CLEAR_CACHE" to Pair("LOW", "清理系统缓存"),
            "SYSTEM_SET_VOLUME" to Pair("LOW", "修改系统音量"),
            "SYSTEM_SET_BRIGHTNESS" to Pair("LOW", "修改系统亮度"),
            "MEDIA_CONTROL" to Pair("LOW", "媒体播放控制"),
            "PAYMENT_OPERATION" to Pair("CRITICAL", "支付操作"),
            "CONTACT_DELETE" to Pair("CRITICAL", "删除联系人"),
            "SMS_SEND" to Pair("CRITICAL", "发送短信"),
            "CALL_PHONE" to Pair("HIGH", "拨打电话"),
            "DATA_EXPORT" to Pair("HIGH", "导出数据"),
            "ACCOUNT_LOGOUT" to Pair("MEDIUM", "退出登录"),
            "MODIFY_SETTINGS" to Pair("MEDIUM", "修改系统设置")
        )

        /**
         * 安全模式对敏感动作的拦截规则。
         *
         * 键为动作类型，值为拦截该动作所需的最低安全模式级别。
         * 例如 "APP_UNINSTALL" 对应 [SafeModeLevel.BASIC]，
         * 意味着安全模式为 BASIC 或更高时拦截此动作。
         */
        private val SAFE_MODE_BLOCK_RULES: Map<String, SafeModeLevel> = mapOf(
            // BASIC 级别拦截（CRITICAL 风险）
            "APP_UNINSTALL" to SafeModeLevel.BASIC,
            "FILE_DELETE" to SafeModeLevel.BASIC,
            "PAYMENT_OPERATION" to SafeModeLevel.BASIC,
            "CONTACT_DELETE" to SafeModeLevel.BASIC,
            "SMS_SEND" to SafeModeLevel.BASIC,

            // STRICT 级别额外拦截（HIGH 风险）
            "APP_INSTALL" to SafeModeLevel.STRICT,
            "SHELL_EXEC" to SafeModeLevel.STRICT,
            "CALL_PHONE" to SafeModeLevel.STRICT,
            "DATA_EXPORT" to SafeModeLevel.STRICT,
            "SYSTEM_KILL_PROCESS" to SafeModeLevel.STRICT,
            "NOTIFY_SEND" to SafeModeLevel.STRICT,
            "ACCOUNT_LOGOUT" to SafeModeLevel.STRICT,

            // PARANOID 级别额外拦截（MEDIUM/LOW 风险）
            "CLIPBOARD_PASTE" to SafeModeLevel.PARANOID,
            "FILE_WRITE" to SafeModeLevel.PARANOID,
            "SYSTEM_CLEAR_CACHE" to SafeModeLevel.PARANOID,
            "SYSTEM_SET_VOLUME" to SafeModeLevel.PARANOID,
            "SYSTEM_SET_BRIGHTNESS" to SafeModeLevel.PARANOID,
            "MEDIA_CONTROL" to SafeModeLevel.PARANOID,
            "MODIFY_SETTINGS" to SafeModeLevel.PARANOID
        )

        // ---- 权限映射 ----

        /**
         * 动作类型与所需 Android 权限的映射关系。
         *
         * 键为动作类型名称，值为该动作所需的权限列表。
         * 用于 [checkPermission] 检查执行权限是否满足。
         * 权限名使用 Android 权限常量字符串（`android.permission.xxx`）。
         */
        private val ACTION_PERMISSION_MAP: Map<String, List<String>> = mapOf(
            "APP_INSTALL" to listOf(
                "android.permission.REQUEST_INSTALL_PACKAGES"
            ),
            "APP_UNINSTALL" to listOf(
                "android.permission.REQUEST_DELETE_PACKAGES"
            ),
            "SHELL_EXEC" to listOf(
                "android.permission.SHELL_EXEC"
            ),
            "FILE_READ" to listOf(
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_IMAGES"
            ),
            "FILE_WRITE" to listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.WRITE_MEDIA_IMAGES"
            ),
            "FILE_DELETE" to listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.MANAGE_EXTERNAL_STORAGE"
            ),
            "CLIPBOARD_READ" to listOf(
                "android.permission.READ_CLIPBOARD"
            ),
            "CLIPBOARD_PASTE" to listOf(
                "android.permission.READ_CLIPBOARD"
            ),
            "NOTIFY_SEND" to listOf(
                "android.permission.POST_NOTIFICATIONS"
            ),
            "NOTIFY_READ" to listOf(
                "android.permission.ACCESS_NOTIFICATIONS"
            ),
            "SMS_SEND" to listOf(
                "android.permission.SEND_SMS"
            ),
            "CALL_PHONE" to listOf(
                "android.permission.CALL_PHONE"
            ),
            "CONTACT_READ" to listOf(
                "android.permission.READ_CONTACTS"
            ),
            "CONTACT_DELETE" to listOf(
                "android.permission.WRITE_CONTACTS"
            ),
            "DATA_EXPORT" to listOf(
                "android.permission.WRITE_EXTERNAL_STORAGE"
            ),
            "SYSTEM_KILL_PROCESS" to listOf(
                "android.permission.KILL_BACKGROUND_PROCESSES"
            ),
            "SYSTEM_CLEAR_CACHE" to listOf(
                "android.permission.CLEAR_APP_CACHE"
            ),
            "SYSTEM_SET_VOLUME" to listOf(
                "android.permission.MODIFY_AUDIO_SETTINGS"
            ),
            "MEDIA_CONTROL" to listOf(
                "android.permission.MEDIA_CONTENT_CONTROL"
            ),
            "ACCESSIBILITY_SERVICE" to listOf(
                "android.permission.BIND_ACCESSIBILITY_SERVICE"
            )
        )

        // ---- 数据隐藏占位符 ----

        /** 密码脱敏占位符。 */
        private const val PASSWORD_PLACEHOLDER = "[密码已隐藏]"

        /** OTP 验证码脱敏占位符。 */
        private const val OTP_PLACEHOLDER = "[验证码已隐藏]"

        /** 信用卡号脱敏占位符。 */
        private const val CREDIT_CARD_PLACEHOLDER = "[银行卡号已隐藏]"

        /** 手机号脱敏占位符。 */
        private const val PHONE_PLACEHOLDER = "[手机号已隐藏]"

        /** 身份证号脱敏占位符。 */
        private const val ID_NUMBER_PLACEHOLDER = "[身份证号已隐藏]"

        /** 地址脱敏占位符。 */
        private const val ADDRESS_PLACEHOLDER = "[地址已隐藏]"

        /** 邮箱脱敏占位符。 */
        private const val EMAIL_PLACEHOLDER = "[邮箱已隐藏]"

        /** API 密钥脱敏占位符。 */
        private const val API_KEY_PLACEHOLDER = "[API密钥已隐藏]"

        /** 个人姓名脱敏占位符。 */
        private const val NAME_PLACEHOLDER = "[姓名已隐藏]"

        /** 银行卡号脱敏占位符。 */
        private const val BANK_ACCOUNT_PLACEHOLDER = "[银行账户已隐藏]"

        /** 未知类型脱敏占位符。 */
        private const val UNKNOWN_PLACEHOLDER = "[敏感数据已隐藏]"

        // ---- 日志与存储限制 ----

        /** 审计日志最大保留条数，超出时按 FIFO 淘汰最旧记录。 */
        private const val MAX_AUDIT_LOG_ENTRIES = 5000

        /** 敏感数据统计最大保留条数。 */
        private const val MAX_SENSITIVE_STATS_ENTRIES = 1000

        /** 单次查询审计日志的最大返回条数。 */
        private const val MAX_AUDIT_QUERY_LIMIT = 200

        /** 单次 [detectSensitiveData] 的最大返回匹配数，防止 OOM。 */
        private const val MAX_MATCHES_PER_DETECT = 100

        /** 单次 [maskSensitiveData] 的最大替换数，防止 OOM。 */
        private const val MAX_MASK_REPLACEMENTS = 50

        /** 文本长度上限（字符数），超过此长度的文本跳过检测以节省性能。 */
        private const val MAX_TEXT_LENGTH = 50000

        /** 中文姓名检测的最小置信度阈值（暂未使用，保留扩展）。 */
        private const val NAME_CONFIDENCE_THRESHOLD = 0.5
    }

    // =========================================================================
    //  数据类型 → 敏感度级别映射
    // =========================================================================

    /**
     * 数据类型到敏感度级别的映射表。
     *
     * 用于 [detectSensitiveData] 在匹配到敏感数据时自动确定其敏感度级别。
     * 该映射在构造时初始化且此后不可变，因此无需同步即可安全读取。
     */
    private val dataTypeSensitivityMap: Map<DataType, SensitivityLevel> = mapOf(
        DataType.PASSWORD to SensitivityLevel.CRITICAL,
        DataType.API_KEY to SensitivityLevel.CRITICAL,
        DataType.CREDIT_CARD to SensitivityLevel.HIGH,
        DataType.ID_NUMBER to SensitivityLevel.HIGH,
        DataType.BANK_ACCOUNT to SensitivityLevel.HIGH,
        DataType.PHONE_NUMBER to SensitivityLevel.MEDIUM,
        DataType.OTP_CODE to SensitivityLevel.MEDIUM,
        DataType.ADDRESS to SensitivityLevel.MEDIUM,
        DataType.EMAIL to SensitivityLevel.LOW,
        DataType.PERSONAL_NAME to SensitivityLevel.LOW,
        DataType.UNKNOWN to SensitivityLevel.LOW
    )

    /**
     * 数据类型到检测正则表达式的映射表。
     *
     * 每种数据类型对应一个预编译的 [Pattern] 对象，用于在文本中执行正则匹配。
     * 注意：[OTP_CODE] 和 [CREDIT_CARD] 的匹配结果需要额外校验
     * （OTP 需结合上下文触发词，信用卡需执行 Luhn 校验），
     * 因此此处使用扩展过滤逻辑而非直接映射。
     */
    private val dataTypePatternMap: Map<DataType, Pattern> = mapOf(
        DataType.PASSWORD to PASSWORD_PATTERN,
        DataType.CREDIT_CARD to CREDIT_CARD_PATTERN,
        DataType.PHONE_NUMBER to PHONE_PATTERN,
        DataType.ID_NUMBER to ID_NUMBER_PATTERN,
        DataType.ADDRESS to ADDRESS_PATTERN,
        DataType.EMAIL to EMAIL_PATTERN,
        DataType.API_KEY to API_KEY_PATTERN,
        DataType.PERSONAL_NAME to NAME_PATTERN,
        DataType.BANK_ACCOUNT to BANK_ACCOUNT_PATTERN
    )

    /**
     * 数据类型到脱敏占位符的映射表。
     *
     * 用于 [maskSensitiveData] 将匹配到的敏感数据替换为对应的占位符文本。
     */
    private val dataTypePlaceholderMap: Map<DataType, String> = mapOf(
        DataType.PASSWORD to PASSWORD_PLACEHOLDER,
        DataType.OTP_CODE to OTP_PLACEHOLDER,
        DataType.CREDIT_CARD to CREDIT_CARD_PLACEHOLDER,
        DataType.PHONE_NUMBER to PHONE_PLACEHOLDER,
        DataType.ID_NUMBER to ID_NUMBER_PLACEHOLDER,
        DataType.ADDRESS to ADDRESS_PLACEHOLDER,
        DataType.EMAIL to EMAIL_PLACEHOLDER,
        DataType.API_KEY to API_KEY_PLACEHOLDER,
        DataType.PERSONAL_NAME to NAME_PLACEHOLDER,
        DataType.BANK_ACCOUNT to BANK_ACCOUNT_PLACEHOLDER,
        DataType.UNKNOWN to UNKNOWN_PLACEHOLDER
    )

    // =========================================================================
    //  状态字段（全部线程安全）
    // =========================================================================

    /**
     * 审计日志存储。
     *
     * 键为日志 ID（UUID 字符串），值为 [SecurityAuditLog] 条目。
     * 使用 [ConcurrentHashMap] 保证多线程并发读写的安全性。
     * 超出 [MAX_AUDIT_LOG_ENTRIES] 时，[logAudit] 会自动淘汰最早的日志。
     */
    private val auditLogs: ConcurrentHashMap<String, SecurityAuditLog> = ConcurrentHashMap()

    /**
     * 审计日志写入顺序队列。
     *
     * 维护日志 ID 的插入顺序，用于 FIFO 淘汰和按时间顺序查询。
     * 键为递增的序列号（从 0 开始），值为日志 ID。
     * 使用 [ConcurrentHashMap] 保证线程安全。
     */
    private val auditLogOrder: ConcurrentHashMap<Long, String> = ConcurrentHashMap()

    /** 审计日志序列号自增计数器。 */
    private val auditLogSequence = AtomicLong(0)

    /**
     * 敏感数据检测统计。
     *
     * 键为 [DataType] 名称字符串，值为该类型的检测计数。
     * 用于 [getSensitiveStats] 汇总统计信息。
     */
    private val detectionStats: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

    /**
     * 敏感数据脱敏计数。
     *
     * 键为 [DataType] 名称字符串，值为该类型的脱敏次数。
     */
    private val maskStats: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

    /**
     * 敏感动作拦截计数。
     *
     * 键为动作类型名称字符串，值为该动作的拦截次数。
     */
    private val blockStats: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

    /**
     * 敏感数据匹配时间戳记录（按数据类型）。
     *
     * 键为 [DataType] 名称字符串，值为最近一次检测到该类型数据的时间戳。
     * 用于 [cleanUpOldData] 判断统计是否过期。
     */
    private val lastDetectedTimestamps: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** 累计检测到的敏感数据总条数。 */
    private val totalDetections = AtomicLong(0)

    /** 累计执行的脱敏操作总次数。 */
    private val totalMasks = AtomicLong(0)

    /** 累计拦截的危险操作总次数。 */
    private val totalBlocks = AtomicLong(0)

    /** 累计用户确认的操作总次数。 */
    private val totalConfirmations = AtomicLong(0)

    /** 累计用户拒绝的操作总次数。 */
    private val totalRejections = AtomicLong(0)

    /** 累计发生的安全处理异常总次数。 */
    private val totalErrors = AtomicLong(0)

    /**
     * 当前安全模式级别。
     *
     * 使用 [@Volatile] 保证多线程可见性，通过 [setSafeMode] 和 [getSafeMode] 访问。
     * 默认值为构造时传入的 [initialSafeMode]。
     */
    @Volatile
    private var currentSafeMode: SafeModeLevel = initialSafeMode

    /**
     * 当前数据保留策略。
     *
     * 使用 [@Volatile] 保证多线程可见性。
     * 默认值为构造时传入的 [retentionPolicy]。
     */
    @Volatile
    private var currentRetentionPolicy: DataRetentionPolicy = retentionPolicy

    /** 上次自动清理的时间戳（epoch 毫秒），0 表示从未清理过。 */
    @Volatile
    private var lastCleanupTimestamp: Long = 0L

    // =========================================================================
    //  核心方法 —— 敏感数据检测
    // =========================================================================

    /**
     * 检测文本中的敏感数据。
     *
     * 使用预编译的正则表达式在输入文本中扫描并识别 10 类敏感数据。
     * 返回按出现顺序排列的 [SensitiveDataMatch] 列表，每个匹配包含
     * 数据类型、敏感度级别、原始文本片段和位置信息。
     *
     * 检测流程：
     * 1. 空文本或超长文本（> [MAX_TEXT_LENGTH]）直接返回空列表。
     * 2. 依次使用各数据类型的正则表达式在文本中查找匹配。
     * 3. 对信用卡号执行 Luhn 校验过滤，减少误报。
     * 4. 对 OTP 验证码结合上下文触发词判定（如「验证码」「code」等）。
     * 5. 去重：同一位置的重叠匹配仅保留敏感度级别最高的。
     * 6. 更新检测统计计数。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param text 要检测的原始文本。
     * @param contextWords 上下文关键词列表（可选），用于提升 OTP 等类型的检测准确率。
     * @return 检测到的敏感数据匹配列表，按出现位置排序。
     */
    fun detectSensitiveData(
        text: String,
        contextWords: List<String> = emptyList()
    ): List<SensitiveDataMatch> {
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) {
            return emptyList()
        }

        val startTime = System.currentTimeMillis()
        val matches = mutableListOf<SensitiveDataMatch>()
        val hasOtpContext = contextWords.any { word ->
            word.contains("验证码") || word.contains("验证") ||
                word.contains("OTP") || word.contains("otp") ||
                word.contains("code") || word.contains("Code") ||
                word.contains("验证码为") || word.contains("验证码是")
        }

        // ---- 遍历各数据类型执行正则匹配 ----

        for ((dataType, pattern) in dataTypePatternMap) {
            val matcher = pattern.matcher(text)
            var matchCount = 0

            while (matcher.find() && matchCount < MAX_MATCHES_PER_DETECT) {
                val matchedText = matcher.group()
                val startIndex = matcher.start()
                val endIndex = matcher.end()

                // 对信用卡号执行 Luhn 校验过滤
                if (dataType == DataType.CREDIT_CARD) {
                    val digitsOnly = matchedText.replace(Regex("[ -]"), "")
                    if (digitsOnly.length < 13 || digitsOnly.length > 19) continue
                    if (!isValidLuhn(digitsOnly)) continue
                }

                // OTP 验证码需要上下文触发词才判定
                if (dataType == DataType.OTP_CODE) {
                    // 仅当有 OTP 上下文或文本本身很短（4~6 位纯数字在 OTP 语境下很常见）
                    if (!hasOtpContext && matchedText.length < 6) continue
                }

                // 对姓名检测做长度过滤（2~4 字中文名，排除纯数字匹配）
                if (dataType == DataType.PERSONAL_NAME) {
                    val chineseChars = matchedText.count { it in '\u4e00'..'\u9fa5' }
                    if (chineseChars < 2 || chineseChars > 4) continue
                }

                val sensitivity = dataTypeSensitivityMap[dataType] ?: SensitivityLevel.LOW

                val match = SensitiveDataMatch(
                    dataType = dataType,
                    sensitivityLevel = sensitivity,
                    originalText = matchedText,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    detectedAt = System.currentTimeMillis()
                )
                matches.add(match)
                matchCount++
            }
        }

        // ---- OTP 验证码额外检测（纯数字 4~8 位，结合上下文触发词） ----
        if (hasOtpContext) {
            val otpMatcher = OTP_PATTERN.matcher(text)
            while (otpMatcher.find()) {
                val matchedText = otpMatcher.group()
                // 避免与信用卡/银行卡号重复
                val isAlreadyMatched = matches.any { m ->
                    m.startIndex == otpMatcher.start() && m.endIndex == otpMatcher.end()
                }
                if (!isAlreadyMatched) {
                    matches.add(
                        SensitiveDataMatch(
                            dataType = DataType.OTP_CODE,
                            sensitivityLevel = SensitivityLevel.MEDIUM,
                            originalText = matchedText,
                            startIndex = otpMatcher.start(),
                            endIndex = otpMatcher.end(),
                            detectedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        // ---- 去重：重叠匹配仅保留敏感度级别最高的 ----
        val deduplicated = deduplicateOverlappingMatches(matches)

        // ---- 更新统计 ----
        for (match in deduplicated) {
            val typeName = match.dataType.name
            detectionStats.computeIfAbsent(typeName) { AtomicInteger(0) }
                .incrementAndGet()
            lastDetectedTimestamps[typeName] = System.currentTimeMillis()
        }
        totalDetections.addAndGet(deduplicated.size.toLong())

        val duration = System.currentTimeMillis() - startTime

        // 记录审计日志（仅在有匹配时记录）
        if (deduplicated.isNotEmpty()) {
            val dataTypeSummary = deduplicated.groupBy { it.dataType }
                .map { (type, list) -> "${type.name}(${list.size}处)" }
                .joinToString(", ")
            logAudit(
                SecurityAuditLog(
                    action = AuditAction.MASKED,
                    actionType = "SENSITIVE_DATA_DETECT",
                    summary = "检测到敏感数据: $dataTypeSummary",
                    detail = "共检测到 ${deduplicated.size} 处敏感数据，包括 $dataTypeSummary",
                    sourceContext = "screen_text",
                    safeModeLevel = currentSafeMode,
                    isMasked = false,
                    durationMs = duration
                )
            )
        }

        Log.d(TAG, "detectSensitiveData: 检测到 ${deduplicated.size} 处敏感数据，耗时 ${duration}ms")
        return deduplicated
    }

    /**
     * 对重叠的敏感数据匹配结果进行去重。
     *
     * 当多个正则匹配到同一位置的文本时，仅保留敏感度级别最高的那一个。
     * 例如当「张三」既匹配 [PERSONAL_NAME] 又匹配 [ADDRESS] 时，
     * 保留敏感度更高的结果。
     *
     * @param matches 原始匹配列表（含重叠）。
     * @return 去重后的匹配列表，按起始位置排序。
     */
    private fun deduplicateOverlappingMatches(matches: List<SensitiveDataMatch>): List<SensitiveDataMatch> {
        if (matches.isEmpty()) return emptyList()

        // 按起始位置排序
        val sorted = matches.sortedBy { it.startIndex }
        val result = mutableListOf<SensitiveDataMatch>()

        for (match in sorted) {
            var isOverlapping = false
            val iterator = result.listIterator()

            while (iterator.hasNext()) {
                val existing = iterator.next()
                // 判断是否重叠
                if (match.startIndex < existing.endIndex && match.endIndex > existing.startIndex) {
                    isOverlapping = true
                    // 保留敏感度级别更高的
                    if (match.sensitivityLevel.ordinal > existing.sensitivityLevel.ordinal) {
                        iterator.set(match)
                    }
                    break
                }
            }

            if (!isOverlapping) {
                result.add(match)
            }
        }

        return result
    }

    /**
     * 校验数字字符串是否符合 Luhn 算法。
     *
     * Luhn 算法（又称模 10 算法）是一种简单的校验和算法，
     * 用于验证信用卡号、银行卡号等数字标识的合法性。
     *
     * @param digits 纯数字字符串。
     * @return 如果符合 Luhn 算法返回 true，否则返回 false。
     */
    private fun isValidLuhn(digits: String): Boolean {
        if (digits.any { !it.isDigit() }) return false
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var n = digits[i] - '0'
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    /**
     * 校验身份证号码的校验位是否合法。
     *
     * 中国大陆 18 位身份证号码的最后一位为校验位，
     * 根据前 17 位数字通过加权计算得出。
     *
     * @param idNumber 18 位身份证号码（含校验位，最后一位可为 X/x）。
     * @return 如果校验位合法返回 true，否则返回 false。
     */
    private fun isValidIdNumber(idNumber: String): Boolean {
        if (idNumber.length != 18) return false
        val chars = idNumber.uppercase(Locale.getDefault()).toCharArray()
        val weights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
        val checkCodes = charArrayOf('1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2')

        var sum = 0
        for (i in 0 until 17) {
            if (!chars[i].isDigit()) return false
            sum += (chars[i] - '0') * weights[i]
        }
        val expectedCheckCode = checkCodes[sum % 11]
        return chars[17] == expectedCheckCode
    }

    // =========================================================================
    //  核心方法 —— 数据脱敏
    // =========================================================================

    /**
     * 对文本中的敏感数据进行脱敏处理。
     *
     * 先调用 [detectSensitiveData] 检测敏感数据，然后将每处匹配替换为
     * 对应的占位符文本（如 `[密码已隐藏]`、`[手机号已隐藏]`）。
     * 替换从文本末尾向前进行，以避免索引偏移。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param text 原始文本。
     * @param contextWords 上下文关键词列表（可选），传递给 [detectSensitiveData]。
     * @return 脱敏后的文本，以及脱敏详情列表。
     */
    fun maskSensitiveData(
        text: String,
        contextWords: List<String> = emptyList()
    ): Pair<String, List<SensitiveDataMatch>> {
        val startTime = System.currentTimeMillis()

        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) {
            return Pair(text, emptyList())
        }

        // 先检测敏感数据
        val matches = detectSensitiveData(text, contextWords)

        if (matches.isEmpty()) {
            return Pair(text, emptyList())
        }

        // 从后向前替换，避免索引偏移
        val limitedMatches = if (matches.size > MAX_MASK_REPLACEMENTS) {
            matches.takeLast(MAX_MASK_REPLACEMENTS)
        } else {
            matches
        }

        val maskedMatches = limitedMatches.map { match ->
            match.copy(
                maskedText = dataTypePlaceholderMap[match.dataType]
                    ?: UNKNOWN_PLACEHOLDER
            )
        }

        val sb = StringBuilder(text)
        // 按结束位置降序排列（从后往前替换）
        val sortedByEnd = maskedMatches.sortedByDescending { it.endIndex }

        for (match in sortedByEnd) {
            val placeholder = dataTypePlaceholderMap[match.dataType] ?: UNKNOWN_PLACEHOLDER
            sb.replace(match.startIndex, match.endIndex, placeholder)
        }

        val maskedText = sb.toString()

        // 更新脱敏统计
        for (match in maskedMatches) {
            val typeName = match.dataType.name
            maskStats.computeIfAbsent(typeName) { AtomicInteger(0) }
                .incrementAndGet()
        }
        totalMasks.addAndGet(maskedMatches.size.toLong())

        val duration = System.currentTimeMillis() - startTime

        // 记录审计日志
        val dataTypeSummary = maskedMatches.groupBy { it.dataType }
            .map { (type, list) -> "${type.name}(${list.size}处)" }
            .joinToString(", ")
        logAudit(
            SecurityAuditLog(
                action = AuditAction.MASKED,
                actionType = "DATA_MASKING",
                summary = "已脱敏 $dataTypeSummary",
                detail = "共脱敏 ${maskedMatches.size} 处敏感数据，包括 $dataTypeSummary",
                sourceContext = "screen_text",
                safeModeLevel = currentSafeMode,
                isMasked = true,
                durationMs = duration
            )
        )

        Log.d(TAG, "maskSensitiveData: 脱敏 ${maskedMatches.size} 处，耗时 ${duration}ms")
        return Pair(maskedText, maskedMatches)
    }

    // =========================================================================
    //  核心方法 —— 权限检查
    // =========================================================================

    /**
     * 检查执行指定动作类型所需的权限是否已具备。
     *
     * 根据 [ACTION_PERMISSION_MAP] 中的权限映射关系，检查所有必需的权限
     * 是否都在 [grantedPermissions] 列表中。如果缺少权限，返回的 [PermissionCheck]
     * 会包含缺失列表和处理建议。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param actionType 要检查的动作类型名称（如 "APP_UNINSTALL"）。
     * @param grantedPermissions 当前已授予的权限列表。
     * @return 权限检查结果。
     */
    fun checkPermission(
        actionType: String,
        grantedPermissions: List<String> = emptyList()
    ): PermissionCheck {
        val requiredPermissions = ACTION_PERMISSION_MAP[actionType] ?: emptyList()
        val missingPermissions = requiredPermissions.filter { it !in grantedPermissions }

        val hasAll = missingPermissions.isEmpty()
        val suggestion = if (hasAll) {
            ""
        } else {
            val missingNames = missingPermissions.joinToString("、") { perm ->
                perm.removePrefix("android.permission.")
            }
            "缺少以下权限: $missingNames。请前往系统设置 > 应用权限中授予相应权限。"
        }

        return PermissionCheck(
            actionType = actionType,
            requiredPermissions = requiredPermissions,
            missingPermissions = missingPermissions,
            hasAllPermissions = hasAll,
            suggestion = suggestion,
            checkedAt = System.currentTimeMillis()
        )
    }

    /**
     * 批量检查多个动作类型的权限。
     *
     * 对 [checkPermission] 的批量调用封装，用于在一次检查中评估多个动作的权限状态。
     *
     * @param actionTypes 要检查的动作类型名称列表。
     * @param grantedPermissions 当前已授予的权限列表。
     * @return 动作类型到 [PermissionCheck] 的映射。
     */
    fun checkPermissions(
        actionTypes: List<String>,
        grantedPermissions: List<String> = emptyList()
    ): Map<String, PermissionCheck> {
        val result = ConcurrentHashMap<String, PermissionCheck>()
        for (actionType in actionTypes) {
            result[actionType] = checkPermission(actionType, grantedPermissions)
        }
        return result
    }

    // =========================================================================
    //  核心方法 —— 敏感动作判定
    // =========================================================================

    /**
     * 判断一个动作是否为敏感操作，需要用户确认。
     *
     * 根据 [SENSITIVE_ACTIONS] 和 [SAFE_MODE_BLOCK_RULES] 综合判断：
     * 1. 如果动作类型在 [SENSITIVE_ACTIONS] 中，标记为需要确认。
     * 2. 如果当前安全模式 >= 该动作的拦截级别，标记为高危。
     * 3. 生成风险警告消息。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param actionType 动作类型名称（如 "APP_UNINSTALL"）。
     * @param actionDescription 动作的具体描述（如 "卸载微信"）。
     * @param involvedData 涉及的数据类型列表（可选）。
     * @return 敏感动作描述。
     */
    fun isSensitiveAction(
        actionType: String,
        actionDescription: String = "",
        involvedData: List<DataType> = emptyList()
    ): SensitiveAction {
        val actionInfo = SENSITIVE_ACTIONS[actionType]
        val riskLevel = actionInfo?.first ?: "LOW"
        val defaultDescription = actionInfo?.second ?: actionType
        val description = actionDescription.ifBlank { defaultDescription }

        // 判断是否需要确认
        val needsConfirmation = actionInfo != null

        // 判断是否会被安全模式拦截
        val blockLevel = SAFE_MODE_BLOCK_RULES[actionType]
        val willBeBlocked = blockLevel != null && currentSafeMode.ordinal >= blockLevel.ordinal

        // 生成风险警告消息
        val warningMessage = buildString {
            append("检测到高风险操作: ")
            append(description)
            append("。风险等级: $riskLevel。")
            if (willBeBlocked) {
                append("当前安全模式为 ${currentSafeMode.name}，此操作将被拦截。")
            } else {
                append("建议您确认此操作是否安全。")
            }
            if (involvedData.isNotEmpty()) {
                append("涉及数据类型: ${involvedData.joinToString("、") { it.name }}。")
            }
        }

        return SensitiveAction(
            actionType = actionType,
            actionDescription = description,
            riskLevel = riskLevel,
            involvedDataTypes = involvedData,
            warningMessage = warningMessage,
            requiresConfirmation = needsConfirmation && !willBeBlocked,
            detectedAt = System.currentTimeMillis()
        )
    }

    /**
     * 请求用户确认敏感操作。
     *
     * 模拟确认流程，返回用户是否确认执行。在实际应用中，此方法会触发 UI 弹窗
     * 或通知用户审慎操作。当前实现通过 [Log] 输出警告，并基于动作风险等级
     * 自动决定默认行为（高风险默认拒绝，低风险默认通过）。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param sensitiveAction 敏感动作描述，由 [isSensitiveAction] 返回。
     * @param autoReject 是否自动拒绝（用于安全模式拦截场景），默认 false。
     * @return true 表示用户已确认，false 表示用户拒绝或已拦截。
     */
    fun requestConfirmation(
        sensitiveAction: SensitiveAction,
        autoReject: Boolean = false
    ): Boolean {
        val startTime = System.currentTimeMillis()

        if (autoReject) {
            Log.w(TAG, "requestConfirmation: 安全模式已拦截操作: ${sensitiveAction.actionDescription}")
            totalRejections.incrementAndGet()
            logAudit(
                SecurityAuditLog(
                    action = AuditAction.BLOCKED,
                    actionType = sensitiveAction.actionType,
                    summary = "安全模式已拦截: ${sensitiveAction.actionDescription}",
                    detail = "安全模式级别: ${currentSafeMode.name}，风险等级: ${sensitiveAction.riskLevel}",
                    sourceContext = "security_mode",
                    safeModeLevel = currentSafeMode,
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
            return false
        }

        // 在实际应用中，这里应该弹出 UI 确认对话框
        // 当前实现基于风险等级做默认决策，同时记录日志
        val isConfirmed = when (sensitiveAction.riskLevel) {
            "CRITICAL" -> {
                Log.w(TAG, "requestConfirmation: 高风险操作需要用户确认: ${sensitiveAction.warningMessage}")
                // 高风险操作默认拒绝，等待用户手动确认
                false
            }
            "HIGH" -> {
                Log.w(TAG, "requestConfirmation: 中高风险操作建议确认: ${sensitiveAction.warningMessage}")
                false
            }
            else -> {
                Log.d(TAG, "requestConfirmation: 低风险操作: ${sensitiveAction.warningMessage}")
                true
            }
        }

        if (isConfirmed) {
            totalConfirmations.incrementAndGet()
            logAudit(
                SecurityAuditLog(
                    action = AuditAction.CONFIRMED,
                    actionType = sensitiveAction.actionType,
                    summary = "用户已确认操作: ${sensitiveAction.actionDescription}",
                    detail = sensitiveAction.warningMessage,
                    sourceContext = "user_confirmation",
                    safeModeLevel = currentSafeMode,
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
        } else {
            totalRejections.incrementAndGet()
            logAudit(
                SecurityAuditLog(
                    action = AuditAction.REJECTED,
                    actionType = sensitiveAction.actionType,
                    summary = "用户已拒绝操作: ${sensitiveAction.actionDescription}",
                    detail = sensitiveAction.warningMessage,
                    sourceContext = "user_confirmation",
                    safeModeLevel = currentSafeMode,
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
        }

        return isConfirmed
    }

    /**
     * 检查并拦截安全模式禁止的操作。
     *
     * 组合了 [isSensitiveAction] 和 [requestConfirmation] 的便捷方法。
     * 如果当前安全模式级别 >= 动作的拦截级别，则自动拦截并返回 true。
     * 否则返回 false 表示动作可以继续执行。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param actionType 动作类型名称。
     * @param actionDescription 动作描述。
     * @return true 表示该动作已被安全模式拦截，不应执行；false 表示可以继续。
     */
    fun isBlockedBySafeMode(
        actionType: String,
        actionDescription: String = ""
    ): Boolean {
        val blockLevel = SAFE_MODE_BLOCK_RULES[actionType]
        if (blockLevel != null && currentSafeMode.ordinal >= blockLevel.ordinal) {
            val sensitive = isSensitiveAction(actionType, actionDescription)
            totalBlocks.incrementAndGet()
            blockStats.computeIfAbsent(actionType) { AtomicInteger(0) }
                .incrementAndGet()

            logAudit(
                SecurityAuditLog(
                    action = AuditAction.BLOCKED,
                    actionType = actionType,
                    summary = "安全模式已拦截: ${sensitive.actionDescription}",
                    detail = "安全模式: ${currentSafeMode.name}，拦截级别: ${blockLevel.name}，风险等级: ${sensitive.riskLevel}",
                    sourceContext = "safe_mode_check",
                    safeModeLevel = currentSafeMode,
                    durationMs = 0L
                )
            )

            Log.w(TAG, "isBlockedBySafeMode: 安全模式 ${currentSafeMode.name} 已拦截 $actionType")
            return true
        }
        return false
    }

    // =========================================================================
    //  核心方法 —— 审计日志管理
    // =========================================================================

    /**
     * 记录一条安全审计日志。
     *
     * 将 [SecurityAuditLog] 条目写入线程安全的审计日志存储。
     * 自动分配日志 ID（如果未设置）并记录时间戳。
     * 当日志条目数超过 [MAX_AUDIT_LOG_ENTRIES] 时，自动淘汰最早的日志。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param log 要记录的审计日志条目。
     */
    fun logAudit(log: SecurityAuditLog) {
        val logId = log.logId.ifBlank { UUID.randomUUID().toString() }
        val entry = log.copy(logId = logId)

        auditLogs[logId] = entry
        val seq = auditLogSequence.incrementAndGet()
        auditLogOrder[seq] = logId

        // 淘汰超出上限的旧日志（FIFO）
        while (auditLogs.size > MAX_AUDIT_LOG_ENTRIES) {
            val oldestSeq = auditLogOrder.keys.minOrNull() ?: break
            val oldestId = auditLogOrder.remove(oldestSeq)
            if (oldestId != null) {
                auditLogs.remove(oldestId)
            }
        }

        // 统计错误类型
        if (log.action == AuditAction.ERROR) {
            totalErrors.incrementAndGet()
        }

        Log.d(TAG, "logAudit: [${log.action}] ${log.summary}")
    }

    /**
     * 查询审计日志。
     *
     * 支持按动作类型、审计动作、数据类型和时间范围过滤。
     * 返回的日志按时间倒序排列（最新的在前）。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param actionTypeFilter 按动作类型过滤（可选）。
     * @param auditActionFilter 按审计动作过滤（可选）。
     * @param dataTypeFilter 按数据类型过滤（可选）。
     * @param startTime 起始时间戳（epoch 毫秒，可选）。
     * @param endTime 结束时间戳（epoch 毫秒，可选）。
     * @param limit 最大返回条数，默认 50，最大 [MAX_AUDIT_QUERY_LIMIT]。
     * @return 符合条件的审计日志列表，按时间倒序排列。
     */
    fun getAuditLog(
        actionTypeFilter: String? = null,
        auditActionFilter: AuditAction? = null,
        dataTypeFilter: DataType? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int = 50
    ): List<SecurityAuditLog> {
        val effectiveLimit = limit.coerceIn(1, MAX_AUDIT_QUERY_LIMIT)

        // 从最新的序列号开始遍历
        val sortedSeqs = auditLogOrder.keys.sortedDescending()
        val result = mutableListOf<SecurityAuditLog>()

        for (seq in sortedSeqs) {
            if (result.size >= effectiveLimit) break

            val logId = auditLogOrder[seq] ?: continue
            val log = auditLogs[logId] ?: continue

            // 应用过滤条件
            if (actionTypeFilter != null && !log.actionType.contains(actionTypeFilter, ignoreCase = true)) {
                continue
            }
            if (auditActionFilter != null && log.action != auditActionFilter) {
                continue
            }
            if (dataTypeFilter != null && log.dataType != dataTypeFilter) {
                continue
            }
            if (startTime != null && log.timestamp < startTime) {
                continue
            }
            if (endTime != null && log.timestamp > endTime) {
                continue
            }

            result.add(log)
        }

        return result
    }

    /**
     * 获取审计日志的统计概览。
     *
     * 返回各类审计动作的计数、总日志条数、以及按动作类型聚合的统计。
     *
     * @return 审计日志统计信息映射。
     */
    fun getAuditLogStats(): Map<String, Any> {
        val actionCounts = mutableMapOf<String, Int>()
        var totalCount = 0

        for (log in auditLogs.values) {
            val actionName = log.action.name
            actionCounts[actionName] = (actionCounts[actionName] ?: 0) + 1
            totalCount++
        }

        return mapOf(
            "totalLogs" to totalCount,
            "actionCounts" to actionCounts,
            "currentSafeMode" to currentSafeMode.name,
            "retentionPolicyDays" to (currentRetentionPolicy.auditLogRetentionMs / (24 * 60 * 60 * 1000))
        )
    }

    // =========================================================================
    //  核心方法 —— 数据清理与保留策略
    // =========================================================================

    /**
     * 清理过期数据。
     *
     * 根据 [DataRetentionPolicy] 的设置，自动清理以下三类过期数据：
     * 1. 审计日志：超过 [auditLogRetentionMs] 的日志条目。
     * 2. 敏感数据统计：超过 [sensitiveStatsRetentionMs] 的统计记录。
     * 3. 缓存数据：超过 [cacheDataRetentionMs] 的缓存数据。
     *
     * 如果 [enableAutoCleanup] 为 false，则不执行任何清理操作。
     * 该方法可在任意线程安全调用。
     *
     * @return 清理的条目数统计：[auditLogs, stats, cache] 分别表示各类清理数量。
     */
    fun cleanUpOldData(): Map<String, Int> {
        if (!currentRetentionPolicy.enableAutoCleanup) {
            Log.d(TAG, "cleanUpOldData: 自动清理已禁用，跳过")
            return mapOf("auditLogs" to 0, "stats" to 0, "cache" to 0)
        }

        val startTime = System.currentTimeMillis()
        val now = System.currentTimeMillis()
        var cleanedAuditLogs = 0
        var cleanedStats = 0
        var cleanedCache = 0

        // ---- 清理过期的审计日志 ----
        val auditLogCutoff = now - currentRetentionPolicy.auditLogRetentionMs
        val expiredLogIds = auditLogs.filter { (_, log) ->
            log.timestamp < auditLogCutoff
        }.keys

        for (logId in expiredLogIds) {
            auditLogs.remove(logId)
            cleanedAuditLogs++
        }
        // 同时清理 order 映射中已删除的条目
        auditLogOrder.entries.removeAll { (_, id) -> !auditLogs.containsKey(id) }

        // ---- 清理过期的敏感数据统计 ----
        val statsCutoff = now - currentRetentionPolicy.sensitiveStatsRetentionMs
        val expiredTypes = lastDetectedTimestamps.filter { (_, timestamp) ->
            timestamp < statsCutoff
        }.keys

        for (typeName in expiredTypes) {
            detectionStats.remove(typeName)
            maskStats.remove(typeName)
            blockStats.remove(typeName)
            lastDetectedTimestamps.remove(typeName)
            cleanedStats++
        }

        // ---- 清理过期的缓存数据（当前为占位，未来扩展） ----
        // 目前无缓存数据需要清理，保留扩展接口
        cleanedCache = 0

        lastCleanupTimestamp = now

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "cleanUpOldData: 清理完成，审计日志: $cleanedAuditLogs 条，统计: $cleanedStats 项，耗时 ${duration}ms")

        // 记录清理审计日志
        if (cleanedAuditLogs > 0 || cleanedStats > 0) {
            logAudit(
                SecurityAuditLog(
                    action = AuditAction.EXECUTED,
                    actionType = "DATA_CLEANUP",
                    summary = "数据清理完成，已清理 $cleanedAuditLogs 条审计日志、$cleanedStats 项统计",
                    detail = "清理阈值: 审计日志 ${currentRetentionPolicy.auditLogRetentionMs}ms，统计 ${currentRetentionPolicy.sensitiveStatsRetentionMs}ms",
                    sourceContext = "data_retention",
                    safeModeLevel = currentSafeMode,
                    durationMs = duration
                )
            )
        }

        return mapOf(
            "auditLogs" to cleanedAuditLogs,
            "stats" to cleanedStats,
            "cache" to cleanedCache
        )
    }

    /**
     * 更新数据保留策略。
     *
     * 允许在运行时动态调整各类数据的保留时长和清理配置。
     * 设置新策略后，下次 [cleanUpOldData] 调用将按新策略执行。
     *
     * @param policy 新的数据保留策略。
     */
    fun setRetentionPolicy(policy: DataRetentionPolicy) {
        currentRetentionPolicy = policy
        Log.d(TAG, "setRetentionPolicy: 已更新数据保留策略，审计日志保留 ${policy.auditLogRetentionMs}ms")
    }

    /**
     * 获取当前数据保留策略。
     *
     * @return 当前 [DataRetentionPolicy]。
     */
    fun getRetentionPolicy(): DataRetentionPolicy = currentRetentionPolicy

    // =========================================================================
    //  核心方法 —— 敏感数据统计
    // =========================================================================

    /**
     * 获取敏感数据检测与处理统计概览。
     *
     * 返回各类敏感数据的检测次数、脱敏次数和拦截次数，
     * 以及总体统计信息。用于安全态势仪表盘和用户隐私报告。
     *
     * 线程安全，可在任意线程调用。
     *
     * @return 包含以下键的统计映射：
     * - `totalDetections` (Long): 累计检测到的敏感数据总条数。
     * - `totalMasks` (Long): 累计执行的脱敏操作总次数。
     * - `totalBlocks` (Long): 累计拦截的危险操作总次数。
     * - `totalConfirmations` (Long): 累计用户确认的操作总次数。
     * - `totalRejections` (Long): 累计用户拒绝的操作总次数。
     * - `totalErrors` (Long): 累计安全处理异常总次数。
     * - `detectionStats` (Map<String, Int>): 各数据类型检测次数。
     * - `maskStats` (Map<String, Int>): 各数据类型脱敏次数。
     * - `blockStats` (Map<String, Int>): 各动作类型拦截次数。
     * - `currentSafeMode` (String): 当前安全模式级别。
     * - `auditLogCount` (Int): 当前审计日志总数。
     */
    fun getSensitiveStats(): Map<String, Any> {
        val detectionStatsCopy = detectionStats.mapValues { it.value.get() }
        val maskStatsCopy = maskStats.mapValues { it.value.get() }
        val blockStatsCopy = blockStats.mapValues { it.value.get() }

        return mapOf(
            "totalDetections" to totalDetections.get(),
            "totalMasks" to totalMasks.get(),
            "totalBlocks" to totalBlocks.get(),
            "totalConfirmations" to totalConfirmations.get(),
            "totalRejections" to totalRejections.get(),
            "totalErrors" to totalErrors.get(),
            "detectionStats" to detectionStatsCopy,
            "maskStats" to maskStatsCopy,
            "blockStats" to blockStatsCopy,
            "currentSafeMode" to currentSafeMode.name,
            "auditLogCount" to auditLogs.size
        )
    }

    /**
     * 获取指定数据类型的检测详情。
     *
     * 返回该数据类型在检测统计中的详细信息，包括检测次数、脱敏次数和最近检测时间。
     *
     * @param dataType 要查询的数据类型。
     * @return 包含该类型统计信息的映射，如果该类型从未被检测到则返回空映射。
     */
    fun getDataTypeStats(dataType: DataType): Map<String, Any> {
        val typeName = dataType.name
        val detectionCount = detectionStats[typeName]?.get() ?: 0
        val maskCount = maskStats[typeName]?.get() ?: 0
        val lastDetected = lastDetectedTimestamps[typeName] ?: 0L

        if (detectionCount == 0 && maskCount == 0) {
            return emptyMap()
        }

        return mapOf(
            "dataType" to typeName,
            "sensitivityLevel" to (dataTypeSensitivityMap[dataType] ?: SensitivityLevel.LOW).name,
            "detectionCount" to detectionCount,
            "maskCount" to maskCount,
            "lastDetected" to lastDetected
        )
    }

    // =========================================================================
    //  核心方法 —— 安全模式管理
    // =========================================================================

    /**
     * 设置安全模式级别。
     *
     * 动态切换安全模式，决定哪些危险操作将被自动拦截。
     * 设置后立即生效，影响后续 [isSensitiveAction]、[requestConfirmation]
     * 和 [isBlockedBySafeMode] 的判定结果。
     *
     * 线程安全，可在任意线程调用。
     *
     * @param level 目标安全模式级别。
     * @see SafeModeLevel
     */
    fun setSafeMode(level: SafeModeLevel) {
        val previousMode = currentSafeMode
        currentSafeMode = level

        Log.i(TAG, "setSafeMode: 安全模式已切换: ${previousMode.name} -> ${level.name}")

        // 记录审计日志
        logAudit(
            SecurityAuditLog(
                action = AuditAction.EXECUTED,
                actionType = "SAFE_MODE_CHANGE",
                summary = "安全模式已切换: ${previousMode.name} -> ${level.name}",
                detail = "新安全模式将拦截 ${getBlockedActionCount(level)} 类危险操作",
                sourceContext = "safe_mode_settings",
                safeModeLevel = level,
                durationMs = 0L
            )
        )
    }

    /**
     * 获取当前安全模式级别。
     *
     * @return 当前 [SafeModeLevel]。
     */
    fun getSafeMode(): SafeModeLevel = currentSafeMode

    /**
     * 获取指定安全模式下被拦截的动作数量。
     *
     * @param level 安全模式级别。
     * @return 被拦截的动作类型数量。
     */
    private fun getBlockedActionCount(level: SafeModeLevel): Int {
        return SAFE_MODE_BLOCK_RULES.count { (_, blockLevel) ->
            level.ordinal >= blockLevel.ordinal
        }
    }

    /**
     * 获取安全模式拦截的动作列表。
     *
     * 返回当前安全模式下所有被拦截的动作类型名称列表。
     *
     * @return 被拦截的动作类型名称列表。
     */
    fun getBlockedActions(): List<String> {
        return SAFE_MODE_BLOCK_RULES.filter { (_, blockLevel) ->
            currentSafeMode.ordinal >= blockLevel.ordinal
        }.keys.toList()
    }

    /**
     * 获取安全模式级别的描述文本。
     *
     * @param level 安全模式级别（可选，默认返回当前级别的描述）。
     * @return 安全模式的中文描述。
     */
    fun getSafeModeDescription(level: SafeModeLevel? = null): String {
        val targetLevel = level ?: currentSafeMode
        return when (targetLevel) {
            SafeModeLevel.OFF -> "安全模式已关闭。所有操作均可执行，仅记录审计日志，不拦截任何操作。"
            SafeModeLevel.BASIC -> "基础安全模式。已拦截最高危操作（卸载应用、删除数据、支付操作、发送短信、删除联系人）。适用于日常使用。"
            SafeModeLevel.STRICT -> "严格安全模式。在基础模式之上，额外拦截安装应用、执行 Shell 命令、拨打电话、导出数据、结束进程、发送通知等操作。适用于敏感环境。"
            SafeModeLevel.PARANOID -> "偏执安全模式。拦截所有可能产生副作用的操作，仅允许读取和导航类操作。适用于最高安全需求场景。"
        }
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    /**
     * 重置所有统计计数和审计日志。
     *
     * 清空检测统计、脱敏统计、拦截统计和审计日志存储。
     * 安全模式级别和数据保留策略不受影响。
     * 此操作用于测试或用户主动重置场景。
     *
     * 线程安全，可在任意线程调用。
     */
    fun resetStats() {
        detectionStats.clear()
        maskStats.clear()
        blockStats.clear()
        lastDetectedTimestamps.clear()
        auditLogs.clear()
        auditLogOrder.clear()

        totalDetections.set(0)
        totalMasks.set(0)
        totalBlocks.set(0)
        totalConfirmations.set(0)
        totalRejections.set(0)
        totalErrors.set(0)
        auditLogSequence.set(0)

        Log.d(TAG, "resetStats: 所有统计和审计日志已重置")
    }

    /**
     * 导出审计日志为可读文本格式。
     *
     * 将审计日志导出为格式化的文本字符串，便于分享或存档。
     * 支持按条件过滤，默认导出最近 100 条。
     *
     * @param limit 导出的日志条数，默认 100。
     * @return 格式化后的审计日志文本。
     */
    fun exportAuditLogs(limit: Int = 100): String {
        val logs = getAuditLog(limit = limit)
        if (logs.isEmpty()) return "暂无审计日志记录。"

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("  MobileClaw 安全审计日志导出")
        sb.appendLine("  导出时间: ${dateFormat.format(Date())}")
        sb.appendLine("  当前安全模式: ${currentSafeMode.name}")
        sb.appendLine("  日志总数: ${auditLogs.size}")
        sb.appendLine("========================================")
        sb.appendLine()

        for ((index, log) in logs.withIndex()) {
            sb.appendLine("--- 条目 ${index + 1} ---")
            sb.appendLine("  日志 ID: ${log.logId}")
            sb.appendLine("  时间: ${dateFormat.format(Date(log.timestamp))}")
            sb.appendLine("  动作: ${log.action.name}")
            sb.appendLine("  类型: ${log.actionType}")
            log.dataType?.let { sb.appendLine("  数据类型: ${it.name}") }
            sb.appendLine("  摘要: ${log.summary}")
            if (log.detail.isNotBlank()) {
                sb.appendLine("  详情: ${log.detail}")
            }
            if (log.sourceContext.isNotBlank()) {
                sb.appendLine("  来源: ${log.sourceContext}")
            }
            sb.appendLine("  安全模式: ${log.safeModeLevel.name}")
            sb.appendLine("  已脱敏: ${if (log.isMasked) "是" else "否"}")
            if (log.durationMs > 0) {
                sb.appendLine("  耗时: ${log.durationMs}ms")
            }
            sb.appendLine()
        }

        sb.appendLine("========================================")
        sb.appendLine("  导出结束，共 ${logs.size} 条记录")
        sb.appendLine("========================================")

        return sb.toString()
    }

    /**
     * 获取安全与隐私管理的整体状态摘要。
     *
     * 返回一个包含当前配置状态、统计数据和健康检查结果的综合映射，
     * 用于监控面板或调试输出。
     *
     * @return 状态摘要映射。
     */
    fun getStatusSummary(): Map<String, Any> {
        val blockedActions = getBlockedActions()
        return mapOf(
            "safeMode" to currentSafeMode.name,
            "safeModeDescription" to getSafeModeDescription(),
            "blockedActionCount" to blockedActions.size,
            "blockedActions" to blockedActions,
            "auditLogCount" to auditLogs.size,
            "totalDetections" to totalDetections.get(),
            "totalMasks" to totalMasks.get(),
            "totalBlocks" to totalBlocks.get(),
            "totalConfirmations" to totalConfirmations.get(),
            "totalRejections" to totalRejections.get(),
            "totalErrors" to totalErrors.get(),
            "retentionPolicy" to mapOf(
                "auditLogRetentionDays" to (currentRetentionPolicy.auditLogRetentionMs / (24 * 60 * 60 * 1000)),
                "cacheRetentionHours" to (currentRetentionPolicy.cacheDataRetentionMs / (60 * 60 * 1000)),
                "statsRetentionDays" to (currentRetentionPolicy.sensitiveStatsRetentionMs / (24 * 60 * 60 * 1000)),
                "autoCleanupEnabled" to currentRetentionPolicy.enableAutoCleanup
            ),
            "lastCleanupTime" to if (lastCleanupTimestamp > 0) lastCleanupTimestamp else "从未清理"
        )
    }

    // =========================================================================
    //  初始化块
    // =========================================================================

    init {
        Log.i(TAG, "SecurityPrivacyManager 初始化完成，初始安全模式: ${initialSafeMode.name}")
    }
}