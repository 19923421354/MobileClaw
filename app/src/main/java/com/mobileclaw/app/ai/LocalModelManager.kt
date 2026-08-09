package com.mobileclaw.app.ai

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// =================================================================================================
//  模型格式枚举
// =================================================================================================

/**
 * 支持的本地模型文件格式枚举。
 *
 * 用于标识 [ModelInfo.format] 字段，帮助管理器决定如何加载和推理模型。
 * 不同格式需要不同的推理后端（GGUF 需 llama.cpp，ONNX 需 ONNX Runtime，TFLite 需 TensorFlow Lite）。
 *
 * @property extension 文件扩展名（不含点号），用于文件过滤和存储后缀检查
 */
enum class ModelFormat(val extension: String) {

    /** GGUF 格式（llama.cpp 生态），适用于 CPU/混合架构量化模型，如 Qwen、Gemma、Phi 等。 */
    GGUF("gguf"),

    /** ONNX 格式（Open Neural Network Exchange），支持跨框架互操作，可配合 ONNX Runtime 加速。 */
    ONNX("onnx"),

    /** TFLite 格式（TensorFlow Lite），适用于 Android 端侧部署，支持 GPU/NNAPI 加速。 */
    TFLITE("tflite"),

    /** 未知或未识别的格式，管理器将拒绝加载。 */
    UNKNOWN("");

    companion object {
        /**
         * 根据文件扩展名推断模型格式。
         *
         * @param fileName 文件名（含扩展名）
         * @return 匹配的 [ModelFormat]，无法识别时返回 [UNKNOWN]
         */
        fun fromFileName(fileName: String): ModelFormat {
            val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return entries.firstOrNull { it.extension == ext } ?: UNKNOWN
        }
    }
}

// =================================================================================================
//  模型生命周期状态枚举
// =================================================================================================

/**
 * 模型生命周期状态枚举。
 *
 * 描述一个模型从「未下载」到「已加载」再到「卸载」的完整生命周期。
 * 管理器通过 [getState] 返回当前活跃模型的状态，UI 层可据此展示进度或操作按钮。
 *
 * 状态转换图：
 * ```
 * NOT_DOWNLOADED → DOWNLOADING → DOWNLOADED → LOADING → LOADED
 *                                                           ↓
 *                                                    UNLOADING → NOT_DOWNLOADED
 * 任一状态 → ERROR（发生异常时）
 * ```
 */
enum class ModelState {

    /** 模型尚未下载到本地存储。 */
    NOT_DOWNLOADED,

    /** 模型正在下载中（可查看 [DownloadProgress] 获取进度）。 */
    DOWNLOADING,

    /** 模型已下载到本地，校验通过，等待加载。 */
    DOWNLOADED,

    /** 模型正在加载到内存中（可能涉及文件读取、内存映射、量化等操作）。 */
    LOADING,

    /** 模型已成功加载到内存，可随时用于推理。 */
    LOADED,

    /** 模型正在从内存中卸载以释放资源。 */
    UNLOADING,

    /** 模型处于错误状态，可查看错误信息了解详情。 */
    ERROR
}

// =================================================================================================
//  下载状态枚举
// =================================================================================================

/**
 * 模型文件下载状态枚举。
 *
 * 细化跟踪单个模型的下载流程，从待处理到校验完成。
 * 与 [ModelState.DOWNLOADING] 配合使用，提供更细粒度的进度反馈。
 */
enum class DownloadStatus {

    /** 等待下载（在队列中排队）。 */
    PENDING,

    /** 正在下载中。 */
    DOWNLOADING,

    /** 已暂停（用户主动暂停或网络中断进入等待重试）。 */
    PAUSED,

    /** 下载完成，文件已保存到本地。 */
    COMPLETED,

    /** 下载失败（网络错误、磁盘空间不足等）。 */
    FAILED,

    /** 下载完成且 SHA-256 校验通过。 */
    VERIFIED
}

// =================================================================================================
//  推理后端枚举
// =================================================================================================

/**
 * 推理后端选择枚举。
 *
 * 定义 [LocalModelManager] 在推理时使用的后端策略。
 * 支持纯云端、纯本地、混合（云端优先、本地兜底）、自动回退四种模式。
 * 用户可在设置中切换，管理器根据当前可用模型自动适配。
 */
enum class InferenceBackend {

    /** 云端 API 推理（如 DeepSeek、通义千问等远程 API）。 */
    CLOUD_API,

    /** 本地模型推理（使用下载到设备上的模型）。 */
    LOCAL_MODEL,

    /** 混合模式：优先使用本地模型，本地不可用时回退到云端 API。 */
    HYBRID,

    /** 自动回退：尝试本地模型 → 尝试云端 API → 返回错误。 */
    FALLBACK
}

// =================================================================================================
//  模型信息数据类
// =================================================================================================

/**
 * 模型来源的完整信息描述。
 *
 * 代表一个可供下载的 AI 模型的所有元数据。管理器内置多个预配置的模型源
 * （见 [LocalModelManager.Companion.PRESET_MODELS]），用户也可通过 API 添加自定义模型源。
 *
 * 注意：实际部署时，请将 [url] 替换为真实可下载的模型文件链接。
 * 当前使用 Hugging Face 的占位 URL，格式符合真实路径规范。
 *
 * @property id          模型唯一标识符（如 "qwen2.5-0.5b"）
 * @property name        模型人类可读名称（如 "Qwen2.5-0.5B-Instruct"）
 * @property url         模型文件的下载 URL（支持 GGUF/ONNX/TFLite 格式）
 * @property format      模型文件格式 [ModelFormat]
 * @property size        模型文件大小（字节），用于下载前检查存储空间
 * @property version     模型版本号（如 "1.0"、"v2"）
 * @property description 模型的中文描述，展示给用户了解模型特点
 * @property requiredRam 模型推理所需的最小 RAM（字节），用于 [autoSelectModel] 设备兼容性判断
 * @property checksum    模型的 SHA-256 校验和（十六进制字符串），用于下载后校验完整性
 * @property isDefault   是否为默认模型（应用首次启动时自动下载的模型）
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val url: String,
    val format: ModelFormat,
    val size: Long,
    val version: String,
    val description: String,
    val requiredRam: Long,
    val checksum: String,
    val isDefault: Boolean = false
)

// =================================================================================================
//  已下载模型数据类
// =================================================================================================

/**
 * 已下载到本地存储的模型记录。
 *
 * 管理器维护一个本地索引（[downloadedModels]），记录每个已下载模型的详细信息。
 * 应用重启后，管理器会扫描模型存储目录，重建此索引。
 *
 * @property modelInfo     关联的 [ModelInfo] 引用（通过 id 关联）
 * @property localPath     模型文件在设备上的绝对路径
 * @property downloadTime  下载完成的时间戳（毫秒）
 * @property fileSize      实际文件大小（字节），可能与 [ModelInfo.size] 略有差异
 * @property checksumValid SHA-256 校验是否通过（下载后自动校验，也可手动触发）
 */
data class DownloadedModel(
    val modelInfo: ModelInfo,
    val localPath: String,
    val downloadTime: Long,
    val fileSize: Long,
    val checksumValid: Boolean
)

// =================================================================================================
//  下载进度数据类
// =================================================================================================

/**
 * 模型下载的实时进度数据。
 *
 * 通过 [getDownloadProgress] 获取，UI 可据此展示进度条和速度信息。
 * 所有数值字段均为线程安全的快照值。
 *
 * @property modelId          模型 ID
 * @property bytesDownloaded 已下载的字节数
 * @property totalBytes       总字节数（从 Content-Length 获取，未知时为 -1）
 * @property percentage       下载百分比（0.0 ~ 100.0，总字节数未知时为 -1.0）
 * @property speedBytesPerSec 当前下载速度（字节/秒）
 * @property status           当前下载状态 [DownloadStatus]
 */
data class DownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Double,
    val speedBytesPerSec: Long,
    val status: DownloadStatus
)

// =================================================================================================
//  模型配置数据类
// =================================================================================================

/**
 * 模型管理器的运行配置。
 *
 * 用户可通过设置界面修改这些配置项，管理器在启动时从本地持久化存储加载。
 * 配置变更通过 [setAutoDownload] 等方法即时生效。
 *
 * @property autoDownload     是否在应用启动时自动下载默认模型
 * @property autoLoad         是否在下载完成后自动加载模型到内存
 * @property defaultModelId   默认模型的 ID（首次启动时使用）
 * @property maxStorageBytes  模型存储空间配额上限（字节），超限时自动清理旧模型
 * @property allowedFormats   允许下载的模型格式列表，为空时允许所有格式
 */
data class ModelConfig(
    val autoDownload: Boolean = true,
    val autoLoad: Boolean = true,
    val defaultModelId: String = "qwen2.5-0.5b",
    val maxStorageBytes: Long = 4L * 1024 * 1024 * 1024, // 4 GB
    val allowedFormats: List<ModelFormat> = emptyList()   // 空表示允许所有
)

// =================================================================================================
//  推理请求数据类
// =================================================================================================

/**
 * 本地模型推理请求参数。
 *
 * 封装一次文本生成推理所需的所有参数。Manager 的 [generate] 方法接收此对象。
 * 当前实现为模拟推理，未来接入真实推理引擎后，这些参数将直接透传给底层引擎。
 *
 * @property prompt        输入提示词（文本生成的上文）
 * @property maxTokens     生成的最大 Token 数量（默认 512）
 * @property temperature   采样温度（0.0 ~ 2.0，默认 0.7，越低越确定）
 * @property topP          Top-P 采样参数（0.0 ~ 1.0，默认 0.9）
 * @property stopSequences 停止生成序列列表（遇到任意序列时停止生成）
 */
data class GenerationRequest(
    val prompt: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val stopSequences: List<String> = emptyList()
)

// =================================================================================================
//  推理结果数据类
// =================================================================================================

/**
 * 本地模型推理结果。
 *
 * 封装一次 [generate] 调用的输出结果，包含生成的文本和使用统计。
 * 模拟模式下 [tokensUsed] 为估算值，真实推理引擎接入后将提供精确值。
 *
 * @property text        生成的文本内容
 * @property tokensUsed  本次推理消耗的 Token 数量（估算或精确）
 * @property durationMs  推理耗时（毫秒）
 * @property modelId     执行推理的模型 ID
 */
data class GenerationResult(
    val text: String,
    val tokensUsed: Int,
    val durationMs: Long,
    val modelId: String
)

// =================================================================================================
//  存储信息数据类
// =================================================================================================

/**
 * 模型存储空间使用信息。
 *
 * 通过 [getStorageInfo] 获取，用于管理界面展示存储使用情况和配额。
 *
 * @property usedBytes       所有已下载模型占用的总字节数
 * @property freeBytes       存储目录所在分区的可用空间（字节）
 * @property totalBytes      存储目录所在分区的总空间（字节）
 * @property quotaBytes      模型存储配额上限（字节）
 * @property modelCount      已下载的模型数量
 */
data class StorageInfo(
    val usedBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
    val quotaBytes: Long,
    val modelCount: Int
)

// =================================================================================================
//  模型下载状态内部类
// =================================================================================================

/**
 * 模型下载任务的内部状态跟踪。
 *
 * 管理器内部使用，不对外暴露。通过 [ConcurrentHashMap] 维护所有下载任务的状态。
 * 包含下载速度计算所需的上次采样点和字节数。
 *
 * @property modelId      模型 ID
 * @property status       当前下载状态
 * @property bytesDone    已下载字节数
 * @property totalBytes   总字节数（-1 表示未知）
 * @property job          下载协程的 Job，用于取消
 * @property speedBytes   当前速度（字节/秒）
 * @property lastBytes    上次采样时的已下载字节数
 * @property lastSampleNs 上次采样的时间戳（纳秒）
 */
private class DownloadTask(
    val modelId: String,
    var status: DownloadStatus = DownloadStatus.PENDING,
    var bytesDone: Long = 0L,
    var totalBytes: Long = -1L,
    var job: Job? = null,
    var speedBytes: Long = 0L,
    var lastBytes: Long = 0L,
    var lastSampleNs: Long = System.nanoTime()
)

// =================================================================================================
//  LocalModelManager —— 本地 AI 模型管理器
// =================================================================================================

/**
 * LocalModelManager - 本地 AI 模型下载、加载、部署与推理管理器
 *
 * ## 概述
 * 本类是 MobileClaw 的本地 AI 模型管理核心。它负责：
 *
 * 1. **模型下载**：从预配置的 URL 下载 AI 模型文件（GGUF/ONNX/TFLite），
 *    支持断点续传、进度跟踪、SHA-256 校验、自动重试。
 * 2. **存储管理**：管理已下载模型文件，包括查询、删除、存储配额控制。
 * 3. **自动加载**：应用启动时自动加载默认模型，提供完整的状态跟踪。
 * 4. **自动部署**：根据设备硬件能力（RAM、存储空间）自动选择最优模型，
 *    支持回退链：若首选模型不可用，自动尝试次优模型。
 * 5. **推理接口**：提供统一的 [generate] 抽象接口，当前为模拟实现，
 *    预留了真实推理引擎的接入点（见 [ModelInferenceEngine] 接口）。
 *
 * ## 线程安全
 * 所有内部状态使用 [ConcurrentHashMap] 和 [AtomicReference] 保证线程安全。
 * 下载和加载操作通过协程在 [Dispatchers.IO] 上执行，主线程安全无忧。
 *
 * ## 使用示例
 * ```kotlin
 * val manager = LocalModelManager(applicationContext)
 *
 * // 启动时自动加载默认模型
 * manager.initialize()
 *
 * // 手动下载模型
 * manager.downloadModel("qwen2.5-0.5b")
 *
 * // 查看下载进度
 * val progress = manager.getDownloadProgress("qwen2.5-0.5b")
 *
 * // 推理（模拟）
 * val result = manager.generate(GenerationRequest(prompt = "你好"))
 * println(result.text)
 *
 * // 获取当前状态
 * val state = manager.getState()
 * ```
 *
 * @param context Android 应用上下文（用于获取文件存储目录）
 * @param scope   协程作用域（可选），默认使用 [SupervisorJob] + [Dispatchers.Default]
 * @param config  初始配置（可选），默认使用 [ModelConfig] 的默认值
 */
class LocalModelManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val config: ModelConfig = ModelConfig()
) {

    // =========================================================================
    //  常量与配置
    // =========================================================================

    companion object {
        /** 日志标签。 */
        private const val TAG = "LocalModelManager"

        /** 模型存储根目录名称（位于应用内部存储或外部存储）。 */
        private const val MODELS_DIR_NAME = "local_models"

        /** 下载临时文件后缀。 */
        private const val TMP_SUFFIX = ".tmp"

        /** 下载的默认缓冲区大小（8 KB）。 */
        private const val BUFFER_SIZE = 8192

        /** 下载超时（毫秒）。 */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** 读取超时（毫秒）。 */
        private const val READ_TIMEOUT_MS = 30_000L

        /** 最大重试次数。 */
        private const val MAX_RETRIES = 3

        /** 重试间隔基础等待时间（毫秒），指数退避。 */
        private const val RETRY_BASE_DELAY_MS = 2_000L

        /** 进度更新间隔（毫秒），用于限速进度推送频率。 */
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L

        /** 速度平滑窗口的采样数量（用于计算平均下载速度）。 */
        private const val SPEED_SAMPLE_WINDOW = 5

        /** 默认最大 Token 数。 */
        private const val DEFAULT_MAX_TOKENS = 512

        /** 默认生成温度。 */
        private const val DEFAULT_TEMPERATURE = 0.7f

        /** 默认 Top-P 值。 */
        private const val DEFAULT_TOP_P = 0.9f

        /** 模拟推理模式下，每 Token 的模拟耗时（毫秒）。 */
        private const val SIMULATED_MS_PER_TOKEN = 50L

        /** 模拟推理模式下，生成回复的 Token 数与输入 Token 数的比例系数。 */
        private const val SIMULATED_OUTPUT_RATIO = 0.6

        // =====================================================================
        //  预置模型源
        // =====================================================================

        /**
         * 预置的模型下载源列表。
         *
         * 包含 5 个精选的适合移动端部署的轻量级模型：
         * 1. Qwen2.5-0.5B-Instruct（GGUF，Q4_K_M 量化，约 350MB）—— 默认模型
         * 2. Gemma-2-2B-it-GGUF（Q4_K_M 量化，约 1.3GB）
         * 3. Phi-3-mini-4k-instruct（GGUF，Q4_K_M 量化，约 2.2GB）
         * 4. TinyLlama-1.1B-Chat（GGUF，Q4_K_M 量化，约 700MB）
         * 5. Qwen2.5-1.5B-Instruct（GGUF，Q4_K_M 量化，约 950MB）
         *
         * 注意：URL 为 Hugging Face 格式的占位链接，实际部署时请替换为真实链接。
         * SHA-256 校验和为占位值，实际使用前应替换为真实校验和。
         */
        val PRESET_MODELS: List<ModelInfo> = listOf(
            ModelInfo(
                id = "qwen2.5-0.5b",
                name = "Qwen2.5-0.5B-Instruct",
                url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 352_321_536L, // ~350 MB
                version = "1.0",
                description = "通义千问 0.5B 轻量级指令模型，经 Q4_K_M 量化后约 350MB，" +
                    "适合 2GB 以上内存设备，具备良好的中文理解和生成能力，为默认推荐模型。",
                requiredRam = 2L * 1024 * 1024 * 1024, // 2 GB
                checksum = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
                isDefault = true
            ),
            ModelInfo(
                id = "gemma-2-2b",
                name = "Gemma-2-2B-it-GGUF",
                url = "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 1_396_736_000L, // ~1.3 GB
                version = "2.0",
                description = "Google Gemma 2 2B 指令模型，Q4_K_M 量化后约 1.3GB，" +
                    "英文能力出色，适合 3GB 以上内存设备，作为次要推荐模型。",
                requiredRam = 3L * 1024 * 1024 * 1024, // 3 GB
                checksum = "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3",
                isDefault = false
            ),
            ModelInfo(
                id = "phi-3-mini",
                name = "Phi-3-mini-4k-instruct",
                url = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 2_359_296_000L, // ~2.2 GB
                version = "3.0",
                description = "Microsoft Phi-3 迷你指令模型，3.8B 参数经 Q4_K_M 量化后约 2.2GB，" +
                    "推理能力强，适合 4GB 以上内存设备，适合复杂任务场景。",
                requiredRam = 4L * 1024 * 1024 * 1024, // 4 GB
                checksum = "c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4",
                isDefault = false
            ),
            ModelInfo(
                id = "tinyllama-1.1b",
                name = "TinyLlama-1.1B-Chat",
                url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 734_003_200L, // ~700 MB
                version = "1.0",
                description = "TinyLlama 1.1B 聊天模型，极致轻量仅 700MB，" +
                    "适合 1.5GB 以上内存设备，可作为低端设备的首选回退模型。",
                requiredRam = 1536L * 1024 * 1024, // 1.5 GB
                checksum = "d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5",
                isDefault = false
            ),
            ModelInfo(
                id = "qwen2.5-1.5b",
                name = "Qwen2.5-1.5B-Instruct",
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 996_147_200L, // ~950 MB
                version = "1.0",
                description = "通义千问 1.5B 中等规模指令模型，Q4_K_M 量化后约 950MB，" +
                    "在中文理解和生成质量上优于 0.5B 版本，适合 2.5GB 以上内存设备。",
                requiredRam = 2560L * 1024 * 1024, // 2.5 GB
                checksum = "e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6",
                isDefault = false
            ),
            ModelInfo(
                id = "qwen2.5-coder-0.5b",
                name = "Qwen2.5-Coder-0.5B",
                url = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 440_401_920L, // ~420 MB
                version = "1.0",
                description = "通义千问 0.5B 代码指令模型，Q4_K_M 量化后约 420MB，" +
                    "专注代码生成与理解，适合 2GB 以上内存设备。",
                requiredRam = 2L * 1024 * 1024 * 1024, // 2 GB
                checksum = "f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7",
                isDefault = false
            ),
            ModelInfo(
                id = "deepseek-coder-1.3b",
                name = "DeepSeek-Coder-1.3B",
                url = "https://huggingface.co/TheBloke/deepseek-coder-1.3b-instruct-GGUF/resolve/main/deepseek-coder-1.3b-instruct-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 933_232_640L, // ~890 MB
                version = "1.0",
                description = "DeepSeek 1.3B 代码指令模型，Q4_K_M 量化后约 890MB，" +
                    "代码补全与生成能力出色，适合 3GB 以上内存设备。",
                requiredRam = 3L * 1024 * 1024 * 1024, // 3 GB
                checksum = "a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8",
                isDefault = false
            ),
            ModelInfo(
                id = "qwen2.5-7b",
                name = "Qwen2.5-7B (Q4_K_M)",
                url = "https://huggingface.co/Qwen/Qwen2.5-7B-Instruct-GGUF/resolve/main/qwen2.5-7b-instruct-q4_k_m.gguf",
                format = ModelFormat.GGUF,
                size = 4_398_046_512L, // ~4.1 GB
                version = "1.0",
                description = "通义千问 7B 指令模型，Q4_K_M 量化后约 4.1GB，" +
                    "高质量中文理解与生成，适合 8GB 以上内存设备。",
                requiredRam = 8L * 1024 * 1024 * 1024, // 8 GB
                checksum = "b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9",
                isDefault = false
            )
        )
    }

    // =========================================================================
    //  内部状态
    // =========================================================================

    /** 模型存储目录。 */
    private val modelsDir: File by lazy {
        val dir = File(context.filesDir, MODELS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    /** 模型来源映射（id -> ModelInfo），包含预置模型和用户添加的模型。 */
    private val modelSources = ConcurrentHashMap<String, ModelInfo>()

    /** 已下载模型记录（id -> DownloadedModel）。 */
    private val downloadedModels = ConcurrentHashMap<String, DownloadedModel>()

    /** 下载任务状态跟踪（id -> DownloadTask）。 */
    private val downloadTasks = ConcurrentHashMap<String, DownloadTask>()

    /** 当前加载到内存中的模型 ID（null 表示未加载任何模型）。 */
    private val loadedModelId = AtomicReference<String?>(null)

    /** 当前模型状态（由内部状态机驱动更新）。 */
    private val _modelState = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** 当前推理后端。 */
    private val _backend = AtomicReference(InferenceBackend.LOCAL_MODEL)

    /** 用户自定义回退回调：当本地模型不可用时，调用此回调进行云端推理。 */
    private var cloudFallback: (suspend (GenerationRequest) -> GenerationResult)? = null

    /** 是否已初始化。 */
    private val initialized = AtomicBoolean(false)

    /** 初始化锁，防止并发初始化。 */
    private val initMutex = Mutex()

    /** 下载操作的互斥锁（按模型 ID 隔离）。 */
    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    /** 是否启用自动下载。 */
    @Volatile
    private var autoDownloadEnabled: Boolean = config.autoDownload

    /** 是否启用自动加载。 */
    @Volatile
    private var autoLoadEnabled: Boolean = config.autoLoad

    /** 当前配置。 */
    private val currentConfig = AtomicReference(config)

    /** 推理引擎实例（null 表示未加载引擎）。 */
    private val inferenceEngine = AtomicReference<ModelInferenceEngine?>(null)

    /** 错误消息。 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // =========================================================================
    //  初始化
    // =========================================================================

    /**
     * 初始化模型管理器。
     *
     * 执行以下操作：
     * 1. 加载预置模型源到 [modelSources]
     * 2. 扫描本地存储，重建已下载模型索引
     * 3. 如果启用了自动下载且默认模型未下载，开始下载默认模型
     * 4. 如果启用了自动加载且默认模型已下载，自动加载到内存
     *
     * 此方法应在 Application.onCreate() 中调用一次。
     * 多次调用是安全的，只有第一次会实际执行初始化。
     *
     * @param onReady 初始化完成后的回调（可选），在主线程调用
     */
    suspend fun initialize(onReady: (() -> Unit)? = null) = withContext(Dispatchers.IO) {
        if (!initialized.compareAndSet(false, true)) {
            android.util.Log.i(TAG, "initialize: 已经初始化，跳过")
            withContext(Dispatchers.Main) { onReady?.invoke() }
            return@withContext
        }

        android.util.Log.i(TAG, "initialize: 开始初始化 LocalModelManager...")

        // 1. 加载模型源
        loadModelSources()

        // 2. 扫描本地已下载模型
        scanDownloadedModels()

        // 3. 自动下载
        if (autoDownloadEnabled) {
            val defaultModel = modelSources[config.defaultModelId]
            if (defaultModel != null && !isModelDownloaded(config.defaultModelId)) {
                android.util.Log.i(TAG, "initialize: 自动下载默认模型: ${config.defaultModelId}")
                // 非阻塞启动下载
                scope.launch {
                    downloadModelInternal(defaultModel, isAutoDownload = true)
                }
            }
        }

        // 4. 自动加载
        if (autoLoadEnabled) {
            val defaultModel = modelSources[config.defaultModelId]
            if (defaultModel != null && isModelDownloaded(config.defaultModelId)) {
                android.util.Log.i(TAG, "initialize: 自动加载默认模型: ${config.defaultModelId}")
                scope.launch {
                    loadModelInternal(config.defaultModelId)
                }
            }
        }

        android.util.Log.i(TAG, "initialize: 初始化完成")
        withContext(Dispatchers.Main) { onReady?.invoke() }
    }

    /**
     * 加载模型源到内存。
     *
     * 将预置模型列表 [PRESET_MODELS] 加载到 [modelSources] 映射中。
     * 未来可扩展支持从本地 JSON 文件或远程配置加载自定义模型源。
     */
    private fun loadModelSources() {
        modelSources.clear()
        for (model in PRESET_MODELS) {
            modelSources[model.id] = model
        }
        android.util.Log.i(TAG, "loadModelSources: 已加载 ${modelSources.size} 个模型源")
    }

    /**
     * 扫描本地存储目录，重建已下载模型的索引。
     *
     * 遍历 [modelsDir] 下的所有模型文件，排除临时文件 (.tmp)，
     * 根据文件名匹配 [modelSources] 中的模型信息，重建 [downloadedModels] 索引。
     */
    private fun scanDownloadedModels() {
        downloadedModels.clear()

        val files = modelsDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory || !file.isFile) continue
            val fileName = file.name
            // 跳过临时文件
            if (fileName.endsWith(TMP_SUFFIX)) continue

            // 根据文件名匹配模型源
            val matchedModel = modelSources.values.firstOrNull { modelInfo ->
                fileName.contains(modelInfo.id, ignoreCase = true) ||
                    fileName.contains(modelInfo.name, ignoreCase = true)
            }

            if (matchedModel != null) {
                // 校验文件
                val checksumValid = if (matchedModel.checksum.isNotBlank()) {
                    try {
                        verifyChecksum(file, matchedModel.checksum)
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "校验失败: ${file.name}", e)
                        false
                    }
                } else {
                    true // 没有校验和则跳过校验
                }

                val downloaded = DownloadedModel(
                    modelInfo = matchedModel,
                    localPath = file.absolutePath,
                    downloadTime = file.lastModified(),
                    fileSize = file.length(),
                    checksumValid = checksumValid
                )
                downloadedModels[matchedModel.id] = downloaded
                android.util.Log.i(TAG, "scanDownloadedModels: 发现已下载模型: ${matchedModel.name} " +
                    "[校验=${if (checksumValid) "通过" else "失败"}]")
            } else {
                // 未匹配到模型源的未知文件，保留但不索引
                android.util.Log.w(TAG, "scanDownloadedModels: 未知模型文件: $fileName")
            }
        }

        android.util.Log.i(TAG, "scanDownloadedModels: 扫描完成，共 ${downloadedModels.size} 个已下载模型")
    }

    // =========================================================================
    //  模型下载
    // =========================================================================

    /**
     * 开始下载指定模型。
     *
     * 如果模型已下载且校验通过，则直接返回成功。
     * 如果模型正在下载，返回当前进度。
     * 支持断点续传（如果本地存在未完成的临时文件）。
     * 下载完成后自动进行 SHA-256 校验，校验通过后更新索引。
     *
     * @param modelId 要下载的模型 ID
     * @throws IllegalArgumentException 如果 modelId 不存在于模型源中
     */
    fun downloadModel(modelId: String) {
        // 如果模型源还未加载，先尝试加载
        if (modelSources.isEmpty()) {
            loadModelSources()
        }

        val modelInfo = modelSources[modelId]
            ?: throw IllegalArgumentException("模型 '$modelId' 不存在于模型源中")

        // 检查是否已下载
        val existing = downloadedModels[modelId]
        if (existing != null && existing.checksumValid) {
            android.util.Log.i(TAG, "downloadModel: 模型 '$modelId' 已下载且校验通过，跳过")
            _modelState.value = ModelState.DOWNLOADED
            return
        }

        // 检查是否正在下载
        val existingTask = downloadTasks[modelId]
        if (existingTask != null &&
            (existingTask.status == DownloadStatus.DOWNLOADING || existingTask.status == DownloadStatus.PENDING)
        ) {
            android.util.Log.i(TAG, "downloadModel: 模型 '$modelId' 正在下载中")
            return
        }

        // 启动下载协程
        scope.launch {
            downloadModelInternal(modelInfo, isAutoDownload = false)
        }
    }

    /**
     * 取消指定模型的下载。
     *
     * 取消正在进行的下载任务，并删除已下载的临时文件。
     * 如果模型不在下载中，此方法不执行任何操作。
     *
     * @param modelId 要取消下载的模型 ID
     */
    fun cancelDownload(modelId: String) {
        val task = downloadTasks[modelId] ?: return
        task.job?.cancel()
        task.status = DownloadStatus.PAUSED
        _modelState.value = ModelState.NOT_DOWNLOADED
        android.util.Log.i(TAG, "cancelDownload: 已取消模型 '$modelId' 的下载")
    }

    /**
     * 获取指定模型的下载进度。
     *
     * @param modelId 模型 ID
     * @return [DownloadProgress] 对象，如果模型不在下载中则返回 null
     */
    fun getDownloadProgress(modelId: String): DownloadProgress? {
        val task = downloadTasks[modelId] ?: return null
        return DownloadProgress(
            modelId = task.modelId,
            bytesDownloaded = task.bytesDone,
            totalBytes = task.totalBytes,
            percentage = if (task.totalBytes > 0) {
                (task.bytesDone.toDouble() / task.totalBytes.toDouble()) * 100.0
            } else {
                -1.0
            },
            speedBytesPerSec = task.speedBytes,
            status = task.status
        )
    }

    /**
     * 将 huggingface.co URL 转换为 hf-mirror.com 镜像 URL。
     *
     * 如果原始 URL 不是 huggingface.co 域名，返回 null。
     * 转换规则：将 https://huggingface.co/ 替换为 https://hf-mirror.com/。
     *
     * @param originalUrl 原始下载 URL
     * @return 镜像 URL，如果原始 URL 不是 huggingface.co 则返回 null
     */
    private fun deriveMirrorUrl(originalUrl: String): String? {
        return if (originalUrl.startsWith("https://huggingface.co/")) {
            originalUrl.replace("https://huggingface.co/", "https://hf-mirror.com/")
        } else {
            null
        }
    }

    /**
     * 探测指定 URL 的文件大小和 Range 支持。
     *
     * 发送 HTTP HEAD 请求，从响应头解析 Content-Length（文件大小）
     * 和 Accept-Ranges（是否支持断点续传）。
     * 用于下载前评估目标服务器状态。
     *
     * @param url 要探测的 URL
     * @return 包含 (文件大小, 是否支持断点续传) 的 Pair，失败时返回 null
     */
    private suspend fun probeFileSize(url: String): Pair<Long, Boolean>? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
            connection.readTimeout = READ_TIMEOUT_MS.toInt()
            connection.setRequestProperty("User-Agent", "MobileClaw/1.0")
            connection.instanceFollowRedirects = true
            connection.requestMethod = "HEAD"
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.w(TAG, "probeFileSize: HEAD 请求失败, HTTP $responseCode, URL: $url")
                return@withContext null
            }

            val contentLength = connection.contentLengthLong
            val acceptRanges = connection.getHeaderField("Accept-Ranges")
            val supportsRange = acceptRanges != null &&
                acceptRanges.equals("bytes", ignoreCase = true)

            android.util.Log.i(TAG, "probeFileSize: URL=$url, size=$contentLength, range=$supportsRange")
            Pair(contentLength, supportsRange)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "probeFileSize: 探测失败, URL=$url, error=${e.message}")
            null
        }
    }

    /**
     * 从指定 URL 单线程下载文件到临时文件。
     *
     * 支持断点续传：
     * - 如果临时文件已存在且服务端支持 Range 请求（通过 [probeFileSize] 确认），
     *   自动设置 Range 头从断点处续传
     * - 下载过程中实时更新 [DownloadTask] 的进度和速度字段
     *
     * 调用方需确保 [tmpFile] 的父目录已存在。
     *
     * @param url     下载 URL
     * @param tmpFile 目标临时文件
     * @param task    下载任务状态跟踪对象
     * @return true 表示下载成功，false 表示失败
     */
    private suspend fun downloadFile(
        url: String,
        tmpFile: File,
        task: DownloadTask
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
            connection.readTimeout = READ_TIMEOUT_MS.toInt()
            connection.setRequestProperty("User-Agent", "MobileClaw/1.0")

            val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
            if (existingBytes > 0) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
                android.util.Log.i(TAG, "downloadFile: 断点续传, 已下载 $existingBytes 字节, URL: $url")
            }

            connection.connect()
            val responseCode = connection.responseCode

            // 判断响应是否有效：现有文件需要 206，新文件需要 200
            val isValidResponse = when {
                existingBytes > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL -> true
                existingBytes == 0L && responseCode == HttpURLConnection.HTTP_OK -> true
                else -> false
            }
            if (!isValidResponse) {
                android.util.Log.w(TAG, "downloadFile: 无效响应码 $responseCode, URL: $url")
                return@withContext false
            }

            // 从响应头解析总大小
            val contentLength = when {
                responseCode == HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange = connection.getHeaderField("Content-Range")
                    if (contentRange != null) {
                        contentRange.substringAfter('/').toLongOrNull() ?: -1L
                    } else {
                        connection.contentLengthLong
                    }
                }
                else -> connection.contentLengthLong
            }
            if (contentLength > 0) {
                task.totalBytes = contentLength
            }

            task.bytesDone = existingBytes

            val inputStream = connection.inputStream
            val outputStream = if (existingBytes > 0) {
                FileOutputStream(tmpFile, true)
            } else {
                FileOutputStream(tmpFile)
            }

            var lastProgressUpdate = 0L
            val speedSamples = ArrayDeque<Pair<Long, Long>>()

            inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!isActive) {
                            throw CancellationException("下载被取消")
                        }

                        output.write(buffer, 0, bytesRead)
                        task.bytesDone += bytesRead

                        val now = System.nanoTime()
                        val nowMs = System.currentTimeMillis()

                        // 限速更新进度
                        if (nowMs - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                            lastProgressUpdate = nowMs
                            speedSamples.addLast(now to task.bytesDone)
                            while (speedSamples.size > SPEED_SAMPLE_WINDOW) {
                                speedSamples.removeFirst()
                            }
                            if (speedSamples.size >= 2) {
                                val first = speedSamples.first()
                                val last = speedSamples.last()
                                val elapsedSec = (last.first - first.first) / 1_000_000_000.0
                                if (elapsedSec > 0.1) {
                                    task.speedBytes = ((last.second - first.second) / elapsedSec).roundToLong()
                                }
                            }
                        }
                    }
                }
            }

            android.util.Log.i(TAG, "downloadFile: 下载完成, URL: $url, 大小: ${task.bytesDone} 字节")
            true

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "downloadFile: 下载异常, URL: $url, error: ${e.message}")
            false
        }
    }

    /**
     * 模型下载的内部实现。
     *
     * 包含完整的下载流程：
     * 1. 检查磁盘空间
     * 2. 镜像优先策略：先尝试 hf-mirror.com 镜像，失败后回退到 huggingface.co
     * 3. 每个 URL 先探测文件大小和 Range 支持
     * 4. 支持断点续传（基于临时文件）
     * 5. 下载完成后进行 SHA-256 校验
     * 6. 更新已下载模型索引
     *
     * @param modelInfo    要下载的模型信息
     * @param isAutoDownload 是否为自动下载（影响日志级别）
     */
    private suspend fun downloadModelInternal(
        modelInfo: ModelInfo,
        isAutoDownload: Boolean
    ) = withContext(Dispatchers.IO) {
        val modelId = modelInfo.id
        val mutex = downloadMutexes.getOrPut(modelId) { Mutex() }

        mutex.withLock {
            // 再次检查是否已下载（可能在等待锁的过程中已被下载）
            val existing = downloadedModels[modelId]
            if (existing != null && existing.checksumValid) {
                android.util.Log.i(TAG, "downloadModelInternal: 模型 '$modelId' 已下载，跳过")
                _modelState.value = ModelState.DOWNLOADED
                return@withLock
            }

            // 创建或获取下载任务
            val task = downloadTasks.getOrPut(modelId) { DownloadTask(modelId) }
            task.status = DownloadStatus.DOWNLOADING
            _modelState.value = ModelState.DOWNLOADING

            val targetFile = File(modelsDir, "${modelInfo.name}.${modelInfo.format.extension}")
            val tmpFile = File(modelsDir, "${modelInfo.name}.${modelInfo.format.extension}$TMP_SUFFIX")

            // =================================================================
            //  镜像优先策略：构建候选 URL 列表
            //  顺序：hf-mirror.com（镜像）→ huggingface.co（原始）
            // =================================================================
            val candidateUrls = mutableListOf<String>()
            val mirrorUrl = deriveMirrorUrl(modelInfo.url)
            if (mirrorUrl != null) {
                candidateUrls.add(mirrorUrl)
            }
            // 原始 URL 始终作为回退
            if (modelInfo.url != mirrorUrl) {
                candidateUrls.add(modelInfo.url)
            }

            var downloadSuccess = false
            var lastError: String? = null

            // 遍历候选 URL（镜像优先，原始回退）
            for ((urlIndex, currentUrl) in candidateUrls.withIndex()) {
                val urlLabel = if (urlIndex == 0 && mirrorUrl != null) "hf-mirror.com" else "huggingface.co"
                android.util.Log.i(TAG, "downloadModelInternal: 尝试 URL [#$urlIndex] $urlLabel: $currentUrl")

                // 检查是否被取消
                if (!isActive) {
                    task.status = DownloadStatus.PAUSED
                    _modelState.value = ModelState.NOT_DOWNLOADED
                    return@withLock
                }

                // 检查磁盘空间（首次下载时检查）
                if (urlIndex == 0) {
                    val freeBytes = getFreeSpace()
                    if (modelInfo.size > 0 && freeBytes < modelInfo.size * 1.2) {
                        android.util.Log.e(TAG, "downloadModelInternal: 磁盘空间不足，" +
                            "需要 ${modelInfo.size} 字节，可用 $freeBytes 字节")
                        task.status = DownloadStatus.FAILED
                        _modelState.value = ModelState.ERROR
                        _errorMessage.value = "磁盘空间不足"
                        downloadTasks.remove(modelId)
                        return@withLock
                    }
                }

                // 探测文件大小和 Range 支持
                val probeResult = probeFileSize(currentUrl)
                if (probeResult == null) {
                    android.util.Log.w(TAG, "downloadModelInternal: 探测失败，跳过 URL: $currentUrl")
                    lastError = "文件大小探测失败"
                    continue
                }

                val (remoteSize, supportsRange) = probeResult
                if (remoteSize > 0) {
                    task.totalBytes = remoteSize
                }

                // 如果临时文件已存在且大小超过远程文件，说明是旧文件，删除重下
                if (tmpFile.exists() && remoteSize > 0 && tmpFile.length() >= remoteSize) {
                    android.util.Log.i(TAG, "downloadModelInternal: 临时文件已完整，跳过下载")
                    tmpFile.delete()
                }

                // 单 URL 重试下载
                var urlRetryCount = 0
                var urlSuccess = false

                while (urlRetryCount <= MAX_RETRIES && !urlSuccess) {
                    try {
                        if (!isActive) {
                            task.status = DownloadStatus.PAUSED
                            _modelState.value = ModelState.NOT_DOWNLOADED
                            return@withLock
                        }

                        android.util.Log.i(TAG, "downloadModelInternal: 开始下载 [$urlLabel] " +
                            "(第 ${urlRetryCount + 1} 次尝试)")

                        // 调用 downloadFile 执行单线程下载
                        urlSuccess = downloadFile(currentUrl, tmpFile, task)

                        if (urlSuccess) {
                            android.util.Log.i(TAG, "downloadModelInternal: [$urlLabel] 下载成功")
                        } else {
                            urlRetryCount++
                            if (urlRetryCount <= MAX_RETRIES) {
                                val delayMs = RETRY_BASE_DELAY_MS * (1L shl (urlRetryCount - 1))
                                android.util.Log.w(TAG, "downloadModelInternal: [$urlLabel] 下载失败，" +
                                    "等待 ${delayMs}ms 后重试 (${urlRetryCount}/$MAX_RETRIES)...")
                                delay(delayMs)
                            } else {
                                lastError = "[$urlLabel] 下载失败，已重试 $MAX_RETRIES 次"
                                android.util.Log.w(TAG, "downloadModelInternal: $lastError")
                            }
                        }
                    } catch (e: CancellationException) {
                        android.util.Log.i(TAG, "downloadModelInternal: 模型 '$modelId' 下载被取消")
                        task.status = DownloadStatus.PAUSED
                        _modelState.value = ModelState.NOT_DOWNLOADED
                        return@withLock
                    } catch (e: Exception) {
                        urlRetryCount++
                        android.util.Log.w(TAG, "downloadModelInternal: [$urlLabel] 异常: ${e.message}")

                        if (urlRetryCount <= MAX_RETRIES) {
                            val delayMs = RETRY_BASE_DELAY_MS * (1L shl (urlRetryCount - 1))
                            delay(delayMs)
                        } else {
                            lastError = "[$urlLabel] 异常: ${e.message}"
                        }
                    }
                }

                if (urlSuccess) {
                    downloadSuccess = true
                    break
                }

                // 当前 URL 失败，准备尝试下一个（清理临时文件重新下载）
                android.util.Log.i(TAG, "downloadModelInternal: 切换到下一个 URL")
            }

            // =================================================================
            //  下载结果处理
            // =================================================================
            if (!downloadSuccess) {
                task.status = DownloadStatus.FAILED
                _modelState.value = ModelState.ERROR
                _errorMessage.value = lastError ?: "所有下载源均失败"
                android.util.Log.e(TAG, "downloadModelInternal: 模型 '$modelId' 所有下载源均失败: $lastError")
                downloadTasks.remove(modelId)
                return@withLock
            }

            // 下载完成，校验文件
            task.status = DownloadStatus.COMPLETED
            val fileSize = tmpFile.length()
            android.util.Log.i(TAG, "downloadModelInternal: 下载完成，文件大小: ${formatBytes(fileSize)}")

            // SHA-256 校验
            val checksumValid = if (modelInfo.checksum.isNotBlank()) {
                android.util.Log.i(TAG, "downloadModelInternal: 开始 SHA-256 校验...")
                val valid = verifyChecksum(tmpFile, modelInfo.checksum)
                if (valid) {
                    android.util.Log.i(TAG, "downloadModelInternal: SHA-256 校验通过")
                } else {
                    android.util.Log.w(TAG, "downloadModelInternal: SHA-256 校验失败，" +
                        "预期: ${modelInfo.checksum}")
                }
                valid
            } else {
                true
            }

            // 重命名临时文件为目标文件
            if (tmpFile.renameTo(targetFile)) {
                android.util.Log.i(TAG, "downloadModelInternal: 文件已保存到: ${targetFile.absolutePath}")
            } else {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }

            // 更新已下载模型索引
            val downloaded = DownloadedModel(
                modelInfo = modelInfo,
                localPath = targetFile.absolutePath,
                downloadTime = System.currentTimeMillis(),
                fileSize = fileSize,
                checksumValid = checksumValid
            )
            downloadedModels[modelId] = downloaded
            task.status = if (checksumValid) DownloadStatus.VERIFIED else DownloadStatus.COMPLETED

            _modelState.value = if (checksumValid) ModelState.DOWNLOADED else ModelState.ERROR

            // 清理下载任务
            downloadTasks.remove(modelId)

            // 自动加载
            if (autoLoadEnabled && checksumValid) {
                scope.launch {
                    loadModelInternal(modelId)
                }
            }

            android.util.Log.i(TAG, "downloadModelInternal: 模型 '$modelId' 下载${if (checksumValid) "并校验" else ""}成功")
        } // mutex.withLock
    }

    // =========================================================================
    //  模型加载与卸载
    // =========================================================================

    /**
     * 加载指定模型到内存。
     *
     * 加载过程中状态变为 [ModelState.LOADING]。
     * 加载成功后状态变为 [ModelState.LOADED]。
     * 如果模型未下载，状态保持 [ModelState.NOT_DOWNLOADED] 并记录错误。
     *
     * 当前实现为模拟加载（仅记录加载状态），实际 LLM 推理引擎接入后，
     * 此处应调用 Native 库进行模型初始化（如 llama.cpp 的 llama_load_model）。
     *
     * @param modelId 要加载的模型 ID
     */
    fun loadModel(modelId: String) {
        scope.launch {
            loadModelInternal(modelId)
        }
    }

    /**
     * 加载模型的内部实现。
     *
     * @param modelId 要加载的模型 ID
     */
    private suspend fun loadModelInternal(modelId: String) {
        val downloaded = downloadedModels[modelId]
        if (downloaded == null) {
            _errorMessage.value = "模型 '$modelId' 尚未下载，无法加载"
            _modelState.value = ModelState.NOT_DOWNLOADED
            android.util.Log.w(TAG, "loadModelInternal: 模型 '$modelId' 未下载")
            return
        }

        // 如果已加载，跳过
        if (loadedModelId.get() == modelId && _modelState.value == ModelState.LOADED) {
            android.util.Log.i(TAG, "loadModelInternal: 模型 '$modelId' 已加载，跳过")
            return
        }

        _modelState.value = ModelState.LOADING
        android.util.Log.i(TAG, "loadModelInternal: 开始加载模型 '$modelId'...")

        try {
            val file = File(downloaded.localPath)
            if (!file.exists()) {
                throw IOException("模型文件不存在: ${downloaded.localPath}")
            }

            // 使用真实推理引擎 LocalInferenceEngine
            android.util.Log.i(TAG, "loadModelInternal: 使用 LocalInferenceEngine 加载模型")
            val engine = LocalInferenceEngine(
                nCtx = 2048,
                nThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
                nGpuLayers = 0
            )
            engine.load(file.absolutePath)
            inferenceEngine.set(engine)
            android.util.Log.i(TAG, "loadModelInternal: 模型 '$modelId' 加载完成，引擎类型: ${engine.getEngineInfo()["type"]}")

            loadedModelId.set(modelId)
            _modelState.value = ModelState.LOADED
            _errorMessage.value = null
            android.util.Log.i(TAG, "loadModelInternal: 模型 '$modelId' 加载成功")

        } catch (e: Exception) {
            _modelState.value = ModelState.ERROR
            _errorMessage.value = "模型加载失败: ${e.message}"
            loadedModelId.set(null)
            android.util.Log.e(TAG, "loadModelInternal: 模型 '$modelId' 加载失败", e)
        }
    }

    /**
     * 卸载指定模型，释放内存。
     *
     * 卸载过程中状态变为 [ModelState.UNLOADING]。
     * 卸载完成后如果 [loadedModelId] 指向该模型，则置空。
     * 如果当前没有加载任何模型，此方法不执行任何操作。
     *
     * @param modelId 要卸载的模型 ID
     */
    fun unloadModel(modelId: String) {
        scope.launch {
            _modelState.value = ModelState.UNLOADING
            android.util.Log.i(TAG, "unloadModel: 开始卸载模型 '$modelId'...")

            try {
                withContext(Dispatchers.IO) {
                    // 使用真实推理引擎卸载
                    val engine = inferenceEngine.get()
                    if (engine != null) {
                        engine.unload()
                        android.util.Log.i(TAG, "unloadModel: 推理引擎已卸载")
                    }
                }

                // 如果当前加载的正是该模型，清除引用
                if (loadedModelId.get() == modelId) {
                    loadedModelId.set(null)
                    inferenceEngine.set(null)
                }

                _modelState.value = ModelState.NOT_DOWNLOADED
                android.util.Log.i(TAG, "unloadModel: 模型 '$modelId' 卸载成功")

            } catch (e: Exception) {
                _modelState.value = ModelState.ERROR
                _errorMessage.value = "模型卸载失败: ${e.message}"
                android.util.Log.e(TAG, "unloadModel: 模型 '$modelId' 卸载失败", e)
            }
        }
    }

    // =========================================================================
    //  模型查询
    // =========================================================================

    /**
     * 获取当前加载到内存中的模型。
     *
     * @return 当前加载的 [DownloadedModel]，如果未加载任何模型则返回 null
     */
    fun getLoadedModel(): DownloadedModel? {
        val id = loadedModelId.get() ?: return null
        return downloadedModels[id]
    }

    /**
     * 获取所有已下载（可用）的模型列表。
     *
     * @return 已下载模型列表，按下载时间降序排列（最新的在前）
     */
    fun getAvailableModels(): List<DownloadedModel> {
        return downloadedModels.values
            .sortedByDescending { it.downloadTime }
    }

    /**
     * 获取所有模型源（包括已下载和未下载的）。
     *
     * @return 模型源列表，保持预置顺序
     */
    fun getAllModelSources(): List<ModelInfo> {
        if (modelSources.isEmpty()) {
            loadModelSources()
        }
        return modelSources.values.toList()
    }

    /**
     * 删除已下载的模型文件。
     *
     * 如果模型正在加载中，会先卸载再删除。
     * 删除操作会同时移除 [downloadedModels] 索引和磁盘文件。
     *
     * @param modelId 要删除的模型 ID
     * @return true 表示删除成功，false 表示模型不存在或删除失败
     */
    fun deleteModel(modelId: String): Boolean {
        val downloaded = downloadedModels[modelId] ?: return false

        // 如果当前加载的是这个模型，先卸载
        if (loadedModelId.get() == modelId) {
            unloadModel(modelId)
        }

        return try {
            val file = File(downloaded.localPath)
            val deleted = file.delete()
            downloadedModels.remove(modelId)
            downloadTasks.remove(modelId)

            // 同时删除可能存在的临时文件
            val tmpFile = File(modelsDir, "${downloaded.modelInfo.name}.${downloaded.modelInfo.format.extension}$TMP_SUFFIX")
            if (tmpFile.exists()) {
                tmpFile.delete()
            }

            if (deleted) {
                android.util.Log.i(TAG, "deleteModel: 模型 '$modelId' 已删除")
            } else {
                android.util.Log.w(TAG, "deleteModel: 文件删除失败，但已从索引移除: '$modelId'")
            }
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "deleteModel: 删除模型 '$modelId' 失败", e)
            false
        }
    }

    // =========================================================================
    //  模型导入（从本地文件系统导入已有模型文件）
    // =========================================================================

    /**
     * 从本地文件系统导入已有的模型文件（如 .gguf 文件）。
     *
     * 将指定路径的模型文件复制到模型管理目录，并构建索引。
     * 支持导入的文件格式：GGUF (.gguf)、ONNX (.onnx)、TFLite (.tflite)
     *
     * @param filePath 模型文件的绝对路径
     * @param customName 自定义模型名称（可选，不指定则使用文件名）
     * @return 导入后的模型 ID，失败返回 null
     */
    suspend fun importModel(filePath: String, customName: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(filePath)
            if (!sourceFile.exists() || !sourceFile.isFile) {
                android.util.Log.e(TAG, "importModel: 文件不存在: $filePath")
                return@withContext null
            }

            // 检测文件格式
            val format = ModelFormat.fromFileName(sourceFile.name)
            if (format == ModelFormat.UNKNOWN) {
                android.util.Log.w(TAG, "importModel: 未知文件格式，跳过: ${sourceFile.name}")
                return@withContext null
            }

            // 生成唯一的模型 ID
            val modelId = "imported_${sourceFile.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9_]"), "_")}_${System.currentTimeMillis() % 10000}"

            val modelName = customName ?: sourceFile.nameWithoutExtension

            // 目标文件路径
            val targetFile = File(modelsDir, "${modelName}.${format.extension}")
            if (targetFile.exists()) {
                android.util.Log.w(TAG, "importModel: 目标文件已存在，跳过: ${targetFile.name}")
                return@withContext null
            }

            // 复制文件到模型目录
            android.util.Log.i(TAG, "importModel: 正在复制文件: ${sourceFile.absolutePath} -> ${targetFile.absolutePath}")
            sourceFile.copyTo(targetFile, overwrite = false)
            android.util.Log.i(TAG, "importModel: 文件复制完成")

            // 创建 ModelInfo
            val modelInfo = ModelInfo(
                id = modelId,
                name = modelName,
                url = "local://$filePath",
                format = format,
                size = targetFile.length(),
                version = "1.0",
                description = "从本地导入的模型: $filePath",
                requiredRam = 0L,
                checksum = "",
                isDefault = false
            )

            // 添加到模型源
            modelSources[modelId] = modelInfo

            // 添加到已下载索引
            val downloaded = DownloadedModel(
                modelInfo = modelInfo,
                localPath = targetFile.absolutePath,
                downloadTime = System.currentTimeMillis(),
                fileSize = targetFile.length(),
                checksumValid = true // 本地导入的模型跳过校验
            )
            downloadedModels[modelId] = downloaded

            android.util.Log.i(TAG, "importModel: 模型导入成功: $modelName ($modelId)")
            return@withContext modelId

        } catch (e: Exception) {
            android.util.Log.e(TAG, "importModel: 导入失败", e)
            return@withContext null
        }
    }

    /**
     * 扫描本地文件系统中所有可导入的模型文件。
     *
     * 在指定目录递归查找所有支持的模型格式文件。
     *
     * @param directory 要扫描的目录路径（默认：外部存储下载目录）
     * @return 可导入的模型文件路径列表
     */
    fun scanImportableModels(directory: String? = null): List<String> {
        val scanDir = if (directory != null) {
            File(directory)
        } else {
            // 默认扫描外部存储的 Download 目录
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
        }

        if (!scanDir.exists() || !scanDir.isDirectory) {
            android.util.Log.w(TAG, "scanImportableModels: 目录不存在: $scanDir")
            return emptyList()
        }

        val supportedExtensions = setOf("gguf", "onnx", "tflite")
        val files = scanDir.listFiles() ?: return emptyList()

        val importableFiles = files.filter { file ->
            file.isFile && !file.name.startsWith(".") &&
                file.extension.lowercase() in supportedExtensions
        }.map { it.absolutePath }

        android.util.Log.i(TAG, "scanImportableModels: 在 $scanDir 中找到 ${importableFiles.size} 个可导入模型")
        return importableFiles
    }

    // =========================================================================
    //  模型自动选择
    // =========================================================================

    /**
     * 根据设备硬件能力自动选择最佳可用模型。
     *
     * 选择策略：
     * 1. 优先选择已下载且校验通过的模型
     * 2. 在已下载模型中，根据设备 RAM 选择最匹配的模型
     * 3. 如果首选模型内存不足，回退到下一个
     * 4. 如果没有已下载模型，选择与设备最匹配的未下载模型并自动下载
     *
     * 回退链优先级（按所需 RAM 从小到大）：
     * TinyLlama-1.1B (1.5GB) → Qwen2.5-0.5B (2GB) → Qwen2.5-1.5B (2.5GB)
     * → Gemma-2-2B (3GB) → Phi-3-mini (4GB)
     *
     * @return 自动选择的模型 ID，如果没有合适的模型则返回 null
     */
    fun autoSelectModel(): String? {
        val deviceRam = getDeviceRam()
        android.util.Log.i(TAG, "autoSelectModel: 设备 RAM: ${formatBytes(deviceRam)}")

        // 按所需 RAM 从小到大排序的模型源
        val sortedModels = modelSources.values.sortedBy { it.requiredRam }

        // 第一阶段：从已下载模型中选最优
        val downloadedIds = downloadedModels.values
            .filter { it.checksumValid }
            .map { it.modelInfo.id }
            .toSet()

        // 在已下载模型中，按所需 RAM 从大到小选择（尽量用更强的模型）
        val bestDownloaded = sortedModels
            .filter { it.id in downloadedIds && it.requiredRam <= deviceRam }
            .lastOrNull()

        if (bestDownloaded != null) {
            android.util.Log.i(TAG, "autoSelectModel: 从已下载模型中选择: ${bestDownloaded.name}")
            return bestDownloaded.id
        }

        // 第二阶段：从所有模型源中选择最适合设备且未下载的
        val bestFit = sortedModels
            .filter { it.requiredRam <= deviceRam }
            .lastOrNull()

        if (bestFit != null) {
            android.util.Log.i(TAG, "autoSelectModel: 推荐下载: ${bestFit.name} (需 ${formatBytes(bestFit.requiredRam)} RAM)")
            return bestFit.id
        }

        // 第三阶段：设备 RAM 不足，选择所需 RAM 最小的模型
        val smallest = sortedModels.firstOrNull()
        if (smallest != null) {
            android.util.Log.w(TAG, "autoSelectModel: 设备 RAM 不足，选择最小模型: ${smallest.name}")
            return smallest.id
        }

        android.util.Log.e(TAG, "autoSelectModel: 没有可用模型")
        return null
    }

    // =========================================================================
    //  推理接口
    // =========================================================================

    /**
     * 模型推理引擎接口。
     *
     * 定义本地模型推理的统一抽象。所有具体的推理后端（llama.cpp、ONNX Runtime、
     * TFLite GPU Delegate 等）都应实现此接口。
     *
     * 当前 [LocalModelManager] 使用 [SimulatedInferenceEngine] 作为默认实现，
     * 返回模板化响应。接入真实推理引擎时，只需实现此接口并替换 [inferenceEngine] 即可。
     *
     * 接口设计原则：
     * - [load] 和 [unload] 管理模型生命周期
     * - [generate] 为挂起函数，支持协程取消
     * - [getModelInfo] 返回运行时模型信息
     * - [isLoaded] 快速检查引擎状态
     */
    interface ModelInferenceEngine {

        /**
         * 加载模型文件到引擎。
         *
         * 此方法应在 [Dispatchers.IO] 上调用，可能耗时较长。
         * 实现应处理文件不存在、格式不兼容等错误。
         *
         * @param modelPath 模型文件的绝对路径
         * @param config    推理配置参数（如 context size、线程数等）
         * @throws IOException 模型文件不存在或无法读取时抛出
         * @throws IllegalArgumentException 模型格式不兼容时抛出
         */
        suspend fun load(modelPath: String, config: Map<String, Any> = emptyMap())

        /**
         * 卸载模型，释放所有资源。
         *
         * 调用后 [isLoaded] 应返回 false。
         * 多次调用是安全的。
         */
        suspend fun unload()

        /**
         * 执行文本生成推理。
         *
         * @param request 推理请求参数
         * @return 生成结果
         * @throws IllegalStateException 引擎未加载模型时抛出
         */
        suspend fun generate(request: GenerationRequest): GenerationResult

        /**
         * 引擎是否已加载模型。
         */
        val isLoaded: Boolean

        /**
         * 获取当前加载模型的运行时信息。
         *
         * @return 模型信息映射，包含 context size、线程数、GPU 层数等
         */
        fun getModelInfo(): Map<String, Any>
    }

    /**
     * 模拟推理引擎实现。
     *
     * 当前默认使用的推理引擎，返回预设的模板化回复。
     * 主要用于：
     * 1. 开发阶段验证管理器的完整流程
     * 2. 真实模型下载完成前的功能测试
     * 3. UI 层开发和调试
     *
     * TODO: 接入真实推理引擎后，替换此实现
     *
     * @property modelPath 当前加载的模型路径
     */
    class SimulatedInferenceEngine : ModelInferenceEngine {

        /** 当前加载的模型路径。 */
        @Volatile
        private var modelPath: String? = null

        /** 引擎加载时间戳。 */
        private var loadTimeMs: Long = 0L

        /** 引擎配置参数。 */
        private val engineConfig = ConcurrentHashMap<String, Any>()

        override var isLoaded: Boolean = false
            private set

        override suspend fun load(modelPath: String, config: Map<String, Any>) {
            android.util.Log.i(TAG, "SimulatedInferenceEngine.load: 模拟加载模型: $modelPath")

            // 模拟加载耗时
            delay(1000)

            this.modelPath = modelPath
            this.loadTimeMs = System.currentTimeMillis()
            engineConfig.clear()
            engineConfig.putAll(config)
            isLoaded = true

            android.util.Log.i(TAG, "SimulatedInferenceEngine.load: 模拟加载完成")
        }

        override suspend fun unload() {
            android.util.Log.i(TAG, "SimulatedInferenceEngine.unload: 模拟卸载模型")
            delay(200)
            modelPath = null
            isLoaded = false
            engineConfig.clear()
            android.util.Log.i(TAG, "SimulatedInferenceEngine.unload: 模拟卸载完成")
        }

        override suspend fun generate(request: GenerationRequest): GenerationResult {
            if (!isLoaded) {
                throw IllegalStateException("推理引擎未加载模型，请先调用 load()")
            }

            android.util.Log.i(TAG, "SimulatedInferenceEngine.generate: 模拟推理开始")
            val startTime = System.currentTimeMillis()

            // 模拟推理延迟（根据 maxTokens 估算）
            val simulatedTokens = (request.maxTokens * SIMULATED_OUTPUT_RATIO).toInt().coerceIn(1, 512)
            val simulatedDelay = simulatedTokens * SIMULATED_MS_PER_TOKEN
            delay(simulatedDelay)

            // 生成回复
            // TODO: 真实推理引擎接入点
            // 当接入真实推理引擎时，此处应执行：
            // ```
            // val result = nativeEngine.generate(
            //     prompt = request.prompt,
            //     maxTokens = request.maxTokens,
            //     temperature = request.temperature,
            //     topP = request.topP,
            //     stopSequences = request.stopSequences
            // )
            // return GenerationResult(
            //     text = result.text,
            //     tokensUsed = result.tokensUsed,
            //     durationMs = result.durationMs,
            //     modelId = modelPath?.substringAfterLast(File.separator) ?: "unknown"
            // )
            // ```
            val response = buildSimulatedResponse(request.prompt)
            val durationMs = System.currentTimeMillis() - startTime

            android.util.Log.i(TAG, "SimulatedInferenceEngine.generate: 模拟推理完成，" +
                "耗时 ${durationMs}ms，模拟 Token 数: $simulatedTokens")

            return GenerationResult(
                text = response,
                tokensUsed = simulatedTokens,
                durationMs = durationMs,
                modelId = modelPath?.substringAfterLast(File.separator) ?: "simulated"
            )
        }

        override fun getModelInfo(): Map<String, Any> {
            return mapOf(
                "modelPath" to (modelPath ?: "未加载"),
                "isLoaded" to isLoaded,
                "loadTimeMs" to loadTimeMs,
                "engineConfig" to engineConfig.toMap()
            )
        }

        /**
         * 构建模拟回复。
         *
         * 根据输入提示词返回预设的模板化回复。
         * 真实引擎接入后，此方法将被替换为实际的模型推理调用。
         *
         * @param prompt 输入提示词
         * @return 模拟生成的回复文本
         */
        private fun buildSimulatedResponse(prompt: String): String {
            val trimmed = prompt.trim()

            return when {
                trimmed.contains("你好", ignoreCase = true) ||
                    trimmed.contains("hello", ignoreCase = true) ||
                    trimmed.contains("hi", ignoreCase = true) -> {
                    "你好！我是 MobileClaw 的本地 AI 助手。我已经成功加载到设备上，" +
                        "可以为你提供离线智能服务。当前使用的是模拟推理引擎，待真实模型下载完成后，" +
                        "我将具备更强大的理解和生成能力。请问有什么可以帮你的？"
                }
                trimmed.contains("你是谁", ignoreCase = true) ||
                    trimmed.contains("who are you", ignoreCase = true) -> {
                    "我是 MobileClaw 本地 AI 模型管理器。我的职责是管理设备上的 AI 模型，" +
                        "包括下载、加载、卸载和推理。当前运行在模拟模式下，真实模型引擎接入后" +
                        "我将能提供完整的端侧 AI 推理能力。"
                }
                trimmed.contains("帮助", ignoreCase = true) ||
                    trimmed.contains("help", ignoreCase = true) -> {
                    "以下是我能提供的帮助：\n\n" +
                        "1. 模型下载：从预置源下载 AI 模型（支持断点续传和校验）\n" +
                        "2. 模型管理：查看已下载模型、删除模型、查看存储空间\n" +
                        "3. 自动部署：根据设备能力自动选择最佳模型\n" +
                        "4. 本地推理：在设备上运行 AI 模型，无需网络连接\n\n" +
                        "你可以通过设置界面管理模型，或直接对我说 \"下载模型\"。"
                }
                trimmed.contains("下载", ignoreCase = true) ||
                    trimmed.contains("模型", ignoreCase = true) -> {
                    "我支持下载以下模型（当前为模拟模式，实际下载需要真实模型链接）：\n" +
                        "- Qwen2.5-0.5B-Instruct（约 350MB，默认推荐）\n" +
                        "- Gemma-2-2B-it（约 1.3GB）\n" +
                        "- Phi-3-mini-4k-instruct（约 2.2GB）\n" +
                        "- TinyLlama-1.1B-Chat（约 700MB）\n" +
                        "- Qwen2.5-1.5B-Instruct（约 950MB）\n\n" +
                        "请使用 downloadModel() 方法开始下载。"
                }
                else -> {
                    "收到你的消息了。当前为本地模拟推理模式，我的回复基于预设模板生成。\n\n" +
                        "你说了: \"${trimmed.take(100)}\"\n\n" +
                        "待真实模型部署完成后，我将能根据你的输入进行智能生成。"
                }
            }
        }
    }

    /**
     * 执行文本生成推理。
     *
     * 根据当前推理后端策略选择合适的推理路径：
     * - [InferenceBackend.LOCAL_MODEL]：使用本地模型推理
     * - [InferenceBackend.CLOUD_API]：使用云端 API 回退
     * - [InferenceBackend.HYBRID]：优先本地，失败则回退云端
     * - [InferenceBackend.FALLBACK]：按顺序尝试本地→云端→错误
     *
     * 当前本地推理使用 [SimulatedInferenceEngine]，返回模板化回复。
     *
     * @param request 推理请求参数
     * @return 生成结果
     * @throws IllegalStateException 当无可用推理后端时抛出
     */
    suspend fun generate(request: GenerationRequest): GenerationResult {
        val backend = _backend.get()

        return when (backend) {
            InferenceBackend.LOCAL_MODEL -> {
                generateLocal(request)
            }
            InferenceBackend.CLOUD_API -> {
                generateCloud(request)
                    ?: throw IllegalStateException("云端推理未配置，请通过 setCloudFallback() 设置回退回调")
            }
            InferenceBackend.HYBRID -> {
                val localResult = try {
                    generateLocal(request)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "generate: 本地推理失败，回退到云端: ${e.message}")
                    null
                }
                localResult ?: generateCloud(request)
                    ?: throw IllegalStateException("混合模式下本地和云端均不可用")
            }
            InferenceBackend.FALLBACK -> {
                // 尝试本地
                try {
                    return generateLocal(request)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "generate: 本地推理失败，尝试云端: ${e.message}")
                }

                // 尝试云端
                val cloudResult = generateCloud(request)
                if (cloudResult != null) return cloudResult

                // 都失败
                throw IllegalStateException("回退模式下所有推理后端均不可用")
            }
        }
    }

    /**
     * 使用本地模型执行推理。
     *
     * @param request 推理请求
     * @return 生成结果
     * @throws IllegalStateException 本地模型未加载时抛出
     */
    private suspend fun generateLocal(request: GenerationRequest): GenerationResult {
        val engine = inferenceEngine.get()
        if (engine != null && engine.isLoaded) {
            return engine.generate(request)
        }

        // 引擎未加载，尝试自动加载
        val loadedId = loadedModelId.get()
        if (loadedId != null) {
            android.util.Log.i(TAG, "generateLocal: 引擎未初始化，重新加载: $loadedId")
            loadModelInternal(loadedId)
            delay(500) // 等待加载完成
            val engineAfterLoad = inferenceEngine.get()
            if (engineAfterLoad != null && engineAfterLoad.isLoaded) {
                return engineAfterLoad.generate(request)
            }
        }

        // 引擎和模型都不可用，使用模拟引擎兜底
        android.util.Log.w(TAG, "generateLocal: 无可用模型，使用模拟引擎兜底")
        val simEngine = SimulatedInferenceEngine()
        // 标记为已加载（模拟模式）
        try {
            simEngine.load("simulated://default")
        } catch (_: Exception) {
            // 忽略模拟加载的错误
        }
        inferenceEngine.compareAndSet(null, simEngine)
        return simEngine.generate(request)
    }

    /**
     * 使用云端 API 执行推理。
     *
     * @param request 推理请求
     * @return 生成结果，如果未配置云端回退则返回 null
     */
    private suspend fun generateCloud(request: GenerationRequest): GenerationResult? {
        val fallback = cloudFallback ?: return null
        return try {
            fallback(request)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "generateCloud: 云端推理失败", e)
            null
        }
    }

    // =========================================================================
    //  配置管理
    // =========================================================================

    /**
     * 启用或禁用自动下载。
     *
     * 启用后，应用启动时（[initialize] 中）会自动下载默认模型。
     * 如果当前已初始化，启用时会立即检查并启动默认模型下载。
     *
     * @param enabled true 启用自动下载，false 禁用
     */
    fun setAutoDownload(enabled: Boolean) {
        autoDownloadEnabled = enabled
        android.util.Log.i(TAG, "setAutoDownload: 自动下载已${if (enabled) "启用" else "禁用"}")

        // 如果启用且已初始化，立即开始下载默认模型
        if (enabled && initialized.get()) {
            val defaultModel = modelSources[config.defaultModelId]
            if (defaultModel != null && !isModelDownloaded(config.defaultModelId)) {
                scope.launch {
                    downloadModelInternal(defaultModel, isAutoDownload = true)
                }
            }
        }
    }

    /**
     * 设置云端推理回退回调。
     *
     * 当本地模型不可用或用户选择云端后端时，管理器将调用此回调进行云端推理。
     * 调用方应返回完整的 [GenerationResult]。
     *
     * @param fallback 云端推理的挂起函数，接收 [GenerationRequest] 返回 [GenerationResult]
     */
    fun setCloudFallback(fallback: suspend (GenerationRequest) -> GenerationResult) {
        this.cloudFallback = fallback
        android.util.Log.i(TAG, "setCloudFallback: 云端回退已设置")
    }

    /**
     * 设置推理后端。
     *
     * @param backend 推理后端策略
     */
    fun setBackend(backend: InferenceBackend) {
        _backend.set(backend)
        android.util.Log.i(TAG, "setBackend: 推理后端已切换为: $backend")
    }

    /**
     * 获取当前推理后端。
     */
    fun getBackend(): InferenceBackend = _backend.get()

    // =========================================================================
    //  存储管理
    // =========================================================================

    /**
     * 获取模型存储空间使用信息。
     *
     * @return [StorageInfo] 包含使用量、剩余空间、配额等信息
     */
    fun getStorageInfo(): StorageInfo {
        val usedBytes = downloadedModels.values.sumOf { it.fileSize }
        val (freeBytes, totalBytes) = getPartitionSpace()

        return StorageInfo(
            usedBytes = usedBytes,
            freeBytes = freeBytes,
            totalBytes = totalBytes,
            quotaBytes = currentConfig.get().maxStorageBytes,
            modelCount = downloadedModels.size
        )
    }

    /**
     * 检查存储空间是否超过配额，如果超过则自动清理最旧的模型。
     *
     * @return true 表示已清理，false 表示无需清理或清理失败
     */
    fun enforceStorageQuota(): Boolean {
        val storageInfo = getStorageInfo()
        if (storageInfo.usedBytes <= storageInfo.quotaBytes) {
            return false
        }

        val overQuota = storageInfo.usedBytes - storageInfo.quotaBytes
        android.util.Log.w(TAG, "enforceStorageQuota: 存储超限 ${formatBytes(overQuota)}，开始清理...")

        // 按下载时间升序排列（最旧的在前），逐个删除直到低于配额
        val sortedByOldest = downloadedModels.values.sortedBy { it.downloadTime }
        var freedBytes = 0L

        for (model in sortedByOldest) {
            if (freedBytes >= overQuota) break
            // 跳过默认模型
            if (model.modelInfo.isDefault) continue

            val file = File(model.localPath)
            if (file.delete()) {
                freedBytes += model.fileSize
                downloadedModels.remove(model.modelInfo.id)
                android.util.Log.i(TAG, "enforceStorageQuota: 已删除模型 ${model.modelInfo.name}")
            }
        }

        android.util.Log.i(TAG, "enforceStorageQuota: 清理完成，释放了 ${formatBytes(freedBytes)}")
        return freedBytes > 0
    }

    // =========================================================================
    //  状态查询
    // =========================================================================

    /**
     * 获取当前模型管理器的整体状态。
     *
     * 返回一个包含所有关键状态信息的 Map，便于 UI 层一次性获取完整状态。
     *
     * @return 状态信息映射，包含以下键：
     * - state: 当前模型状态 [ModelState]
     * - loadedModelId: 当前加载的模型 ID（可能为 null）
     * - loadedModelName: 当前加载的模型名称
     * - downloadCount: 已下载模型数量
     * - totalModels: 总模型源数量
     * - backend: 当前推理后端
     * - autoDownload: 是否启用自动下载
     * - autoLoad: 是否启用自动加载
     * - storageUsed: 已用存储（字节）
     * - storageQuota: 存储配额（字节）
     * - errorMessage: 错误消息（可能为 null）
     */
    fun getState(): Map<String, Any> {
        val loadedModel = loadedModelId.get()?.let { downloadedModels[it] }
        val storageInfo = getStorageInfo()

        return mapOf(
            "state" to _modelState.value,
            "loadedModelId" to (loadedModelId.get() ?: ""),
            "loadedModelName" to (loadedModel?.modelInfo?.name ?: "无"),
            "downloadCount" to downloadedModels.size,
            "totalModels" to modelSources.size,
            "backend" to _backend.get(),
            "autoDownload" to autoDownloadEnabled,
            "autoLoad" to autoLoadEnabled,
            "storageUsed" to storageInfo.usedBytes,
            "storageQuota" to storageInfo.quotaBytes,
            "errorMessage" to (_errorMessage.value ?: "")
        )
    }

    /**
     * 检查指定模型是否已下载且校验通过。
     *
     * @param modelId 模型 ID
     * @return true 表示已下载且校验通过
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val downloaded = downloadedModels[modelId]
        return downloaded != null && downloaded.checksumValid
    }

    /**
     * 获取指定模型的详细信息。
     *
     * @param modelId 模型 ID
     * @return [ModelInfo] 对象，如果不存在则返回 null
     */
    fun getModelInfo(modelId: String): ModelInfo? = modelSources[modelId]

    // =========================================================================
    //  校验工具
    // =========================================================================

    /**
     * 计算文件的 SHA-256 校验和。
     *
     * @param file 要计算的文件
     * @return SHA-256 校验和的十六进制字符串
     * @throws IOException 文件读取失败时抛出
     */
    fun calculateChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 验证文件的 SHA-256 校验和。
     *
     * @param file    要验证的文件
     * @param expectedChecksum 预期的校验和（十六进制字符串）
     * @return true 表示校验通过
     */
    fun verifyChecksum(file: File, expectedChecksum: String): Boolean {
        return try {
            val actual = calculateChecksum(file)
            actual.equals(expectedChecksum, ignoreCase = true)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "verifyChecksum: 校验失败", e)
            false
        }
    }

    // =========================================================================
    //  设备信息工具
    // =========================================================================

    /**
     * 获取设备可用 RAM（字节）。
     *
     * 通过 [ActivityManager.MemoryInfo] 获取设备实际可用内存。
     * 如果获取失败，返回一个保守的默认值（2GB）。
     *
     * @return 设备可用 RAM（字节）
     */
    private fun getDeviceRam(): Long {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager ?: return 2L * 1024 * 1024 * 1024
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            memInfo.totalMem
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getDeviceRam: 获取失败，使用默认值 2GB", e)
            2L * 1024 * 1024 * 1024
        }
    }

    /**
     * 获取模型存储目录所在分区的空间信息。
     *
     * @return Pair(可用空间, 总空间) 单位字节
     */
    private fun getPartitionSpace(): Pair<Long, Long> {
        return try {
            val stat = StatFs(modelsDir.absolutePath)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            val totalBlocks = stat.blockCountLong
            (availableBlocks * blockSize) to (totalBlocks * blockSize)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getPartitionSpace: 获取失败", e)
            0L to 0L
        }
    }

    /**
     * 获取模型存储目录所在分区的可用空间。
     *
     * @return 可用空间（字节）
     */
    private fun getFreeSpace(): Long {
        return try {
            val stat = StatFs(modelsDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getFreeSpace: 获取失败", e)
            Long.MAX_VALUE
        }
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    /**
     * 格式化字节数为人类可读的字符串。
     *
     * @param bytes 字节数
     * @return 格式化后的字符串（如 "350.0 MB"、"1.3 GB"）
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * 获取当前时间戳的格式化字符串。
     *
     * @return 格式化时间字符串（如 "2026-08-05 14:30:00"）
     */
    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    // =========================================================================
    //  查询方法
    // =========================================================================

    /**
     * 检查是否有模型已加载到内存并可用于推理。
     *
     * @return true 如果模型已加载且推理引擎就绪
     */
    fun isModelLoaded(): Boolean {
        val engine = inferenceEngine.get()
        return (engine != null && engine.isLoaded) ||
            (loadedModelId.get() != null && downloadedModels.containsKey(loadedModelId.get()))
    }

    /**
     * 流式推理，每次生成一个 token 就通过 Flow 发射。
     *
     * 仅当推理引擎是 [LocalInferenceEngine] 时支持流式输出。
     * 其他引擎回退到一次性生成。
     *
     * @param request 推理请求参数
     * @return 文本流
     */
    suspend fun generateStream(request: GenerationRequest): kotlinx.coroutines.flow.Flow<String> {
        val engine = inferenceEngine.get()
        if (engine is LocalInferenceEngine && engine.isLoaded) {
            return engine.generateStream(request)
        }

        // 回退：一次性生成
        return kotlinx.coroutines.flow.flow {
            val result = generate(request)
            if (result.text.isNotEmpty()) {
                emit(result.text)
            }
        }
    }

    // =========================================================================
    //  资源清理
    // =========================================================================

    /**
     * 释放所有资源。
     *
     * 卸载当前加载的模型，取消所有下载任务，清空所有索引。
     * 调用后管理器不再可用，需要重新创建实例。
     *
     * 应在 Application.onTerminate() 或不再需要管理器时调用。
     */
    fun destroy() {
        android.util.Log.i(TAG, "destroy: 开始释放资源...")

        // 卸载当前模型
        val loadedId = loadedModelId.get()
        if (loadedId != null) {
            val engine = inferenceEngine.get()
            if (engine != null) {
                scope.launch {
                    try {
                        engine.unload()
                    } catch (_: Exception) { }
                }
            }
        }

        // 取消所有下载任务
        for ((id, task) in downloadTasks) {
            task.job?.cancel()
            android.util.Log.i(TAG, "destroy: 已取消下载: $id")
        }

        // 清空所有状态
        downloadedModels.clear()
        downloadTasks.clear()
        modelSources.clear()
        loadedModelId.set(null)
        inferenceEngine.set(null)
        cloudFallback = null
        _modelState.value = ModelState.NOT_DOWNLOADED
        _errorMessage.value = null
        initialized.set(false)

        // 取消协程作用域
        scope.cancel()

        android.util.Log.i(TAG, "destroy: 资源释放完成")
    }
}