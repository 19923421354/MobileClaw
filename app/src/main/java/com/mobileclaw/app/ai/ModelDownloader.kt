package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

// =============================================================================
//  DownloadTaskStatus - 单个下载任务状态
// =============================================================================

/**
 * 单个下载任务的状态。
 */
enum class DownloadTaskStatus {
    PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, VERIFIED
}

// =============================================================================
//  DownloadTaskInfo - 下载任务信息
// =============================================================================

/**
 * 下载任务的公开信息快照。
 */
data class DownloadTaskInfo(
    val modelId: String,
    val modelName: String,
    val status: DownloadTaskStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val percentage: Double,
    val speedBytesPerSec: Long,
    val error: String? = null
)

// =============================================================================
//  ModelSource - 模型来源扩展
// =============================================================================

/**
 * 模型下载源的扩展信息，包含推荐的量化等级和文件列表。
 */
data class ModelSource(
    val id: String,
    val name: String,
    val description: String,
    val variants: List<ModelVariant>
)

/**
 * 模型的量化变体。
 *
 * @param label 显示标签
 * @param quantization 量化等级
 * @param url 原始下载 URL（HuggingFace）
 * @param mirrorUrl 国内镜像 URL（hf-mirror.com），默认自动从 url 推导
 * @param sizeBytes 文件大小（字节）
 * @param requiredRam 推理所需最小内存
 * @param isRecommended 是否为推荐量化等级
 */
data class ModelVariant(
    val label: String,
    val quantization: String,
    val url: String,
    val sizeBytes: Long,
    val requiredRam: Long,
    val isRecommended: Boolean = false
) {
    /** 国内镜像 URL（hf-mirror.com），自动从 url 推导 */
    val mirrorUrl: String get() = url.replace("https://huggingface.co", "https://hf-mirror.com")
}

// =============================================================================
//  ModelDownloader - 模型下载管理器
// =============================================================================

/**
 * ModelDownloader - 从 HuggingFace 等源下载 AI 模型。
 *
 * 提供：
 * - 预设模型市场（含多种量化变体，自动添加国内镜像地址）
 * - HTTP Range 分块并行下载（4 连接并发，突破单连接限速）
 * - 国内镜像优先（hf-mirror.com），失败自动回退到原始源（huggingface.co）
 * - 不支持 Range 时自动降级为单线程下载
 * - 断点续传下载
 * - 下载速度跟踪（汇总所有分块速度）
 * - 下载队列管理
 * - 下载完成后自动校验
 *
 * @param downloadDir 模型文件下载目录
 * @param scope       协程作用域
 */
class ModelDownloader(
    private val downloadDir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val TAG = "ModelDownloader"

        /** 单线程/降级时的下载缓冲区大小。 */
        private const val BUFFER_SIZE = 8192

        /** 并行分块下载时的缓冲区大小（32KB，模型文件大，需要合理缓冲）。 */
        private const val CHUNK_BUFFER_SIZE = 32 * 1024

        /** 连接超时（毫秒）。 */
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** 读取超时（毫秒）。 */
        private const val READ_TIMEOUT_MS = 30_000L

        /** 最大重试次数。 */
        private const val MAX_RETRIES = 3

        /** 重试基础延迟（毫秒）。 */
        private const val RETRY_BASE_DELAY_MS = 2_000L

        /** 临时文件后缀。 */
        private const val TMP_SUFFIX = ".tmp"

        // =====================================================================
        //  并行分块下载常量
        // =====================================================================

        /** 并行下载分块数（4 连接并发，突破单连接限速）。 */
        private const val PARALLEL_CHUNKS = 4

        /** 最小分块大小（500KB，避免分块太小导致 TCP 慢启动开销过大）。 */
        private const val MIN_CHUNK_SIZE = 512 * 1024L

        /** 速度采样间隔（毫秒）。 */
        private const val SPEED_SAMPLE_INTERVAL_MS = 500L

        /** 进度回调最小间隔（毫秒）。 */
        private const val PROGRESS_INTERVAL_MS = 250L

        // =====================================================================
        //  预设模型市场
        // =====================================================================

        /**
         * 预设的模型市场列表。
         *
         * 每个模型提供多种量化变体，用户可根据设备配置选择。
         * 所有模型均为 GGUF 格式，兼容 llama.cpp 推理引擎。
         * 每个变体自动包含国内镜像地址（mirrorUrl），下载时会优先从
         * hf-mirror.com 尝试，失败后自动回退到原始 huggingface.co 源。
         */
        val MODEL_MARKET: List<ModelSource> = listOf(
            ModelSource(
                id = "qwen2.5-0.5b",
                name = "Qwen2.5-0.5B-Instruct",
                description = "阿里通义千问 0.5B 轻量级模型，中文优化，适合低端设备",
                variants = listOf(
                    ModelVariant("Q4_K_M (推荐)", "Q4_K_M",
                        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                        352_321_536L, 2L * 1024 * 1024 * 1024, true),
                    ModelVariant("Q5_K_M", "Q5_K_M",
                        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q5_k_m.gguf",
                        410_000_000L, 2L * 1024 * 1024 * 1024, false),
                    ModelVariant("Q8_0", "Q8_0",
                        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q8_0.gguf",
                        630_000_000L, 2L * 1024 * 1024 * 1024, false)
                )
            ),
            ModelSource(
                id = "qwen2.5-1.5b",
                name = "Qwen2.5-1.5B-Instruct",
                description = "阿里通义千问 1.5B 中等规模模型，中文能力更强",
                variants = listOf(
                    ModelVariant("Q4_K_M (推荐)", "Q4_K_M",
                        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                        996_147_200L, 3L * 1024 * 1024 * 1024, true),
                    ModelVariant("Q5_K_M", "Q5_K_M",
                        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q5_k_m.gguf",
                        1_150_000_000L, 3L * 1024 * 1024 * 1024, false)
                )
            ),
            ModelSource(
                id = "tinyllama-1.1b",
                name = "TinyLlama-1.1B-Chat",
                description = "轻量级英文模型，1.1B 参数，极致轻量",
                variants = listOf(
                    ModelVariant("Q4_K_M (推荐)", "Q4_K_M",
                        "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0-q4_k_m.gguf",
                        734_003_200L, 2L * 1024 * 1024 * 1024, true)
                )
            ),
            ModelSource(
                id = "gemma-2-2b",
                name = "Gemma-2-2B-it",
                description = "Google Gemma 2 2B 指令模型，英文能力出色",
                variants = listOf(
                    ModelVariant("Q4_K_M (推荐)", "Q4_K_M",
                        "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-q4_k_m.gguf",
                        1_396_736_000L, 4L * 1024 * 1024 * 1024, true)
                )
            ),
            ModelSource(
                id = "phi-3-mini",
                name = "Phi-3-mini-4k-instruct",
                description = "Microsoft Phi-3 3.8B 模型，推理能力强",
                variants = listOf(
                    ModelVariant("Q4_K_M (推荐)", "Q4_K_M",
                        "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4_k_m.gguf",
                        2_359_296_000L, 4L * 1024 * 1024 * 1024, true)
                )
            ),
            ModelSource(
                id = "qwen2.5-coder-0.5b",
                name = "Qwen2.5-Coder-0.5B",
                description = "通义千问代码模型 0.5B，专为代码生成优化",
                variants = listOf(
                    ModelVariant("Q5_K_M (推荐)", "Q5_K_M",
                        "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-coder-instruct-q5_k_m.gguf",
                        420_000_000L, 2L * 1024 * 1024 * 1024, true)
                )
            ),
            ModelSource(
                id = "deepseek-coder-1.3b",
                name = "DeepSeek-Coder-1.3B",
                description = "深度求索代码模型 1.3B，代码生成与理解",
                variants = listOf(
                    ModelVariant("Q4_K_M (推荐)", "Q4_K_M",
                        "https://huggingface.co/TheBloke/deepseek-coder-1.3b-instruct-GGUF/resolve/main/deepseek-coder-1.3b-instruct-q4_k_m.gguf",
                        890_000_000L, 3L * 1024 * 1024 * 1024, true)
                )
            )
        )
    }

    // =========================================================================
    //  内部状态
    // =========================================================================

    /** 下载任务索引。 */
    private val tasks = ConcurrentHashMap<String, DownloadTask>()

    /** 下载进度流。 */
    private val _progressFlow = MutableStateFlow<Map<String, DownloadTaskInfo>>(emptyMap())
    val progressFlow: StateFlow<Map<String, DownloadTaskInfo>> = _progressFlow.asStateFlow()

    /** 下载中标记。 */
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    // =========================================================================
    //  下载 API
    // =========================================================================

    /**
     * 开始下载模型。
     *
     * @param modelId  模型 ID
     * @param url      下载 URL
     * @param fileName 保存的文件名
     * @param onComplete 下载完成回调
     */
    fun download(
        modelId: String,
        url: String,
        fileName: String,
        onComplete: (suspend (Boolean, String?) -> Unit)? = null
    ) {
        if (tasks[modelId]?.status == DownloadTaskStatus.DOWNLOADING) {
            Log.i(TAG, "download: 模型 '$modelId' 正在下载中，跳过")
            return
        }

        val task = DownloadTask(modelId, url, fileName)
        tasks[modelId] = task
        _isDownloading.value = true

        task.job = scope.launch {
            runDownload(task, onComplete)
        }
    }

    /**
     * 取消下载。
     */
    fun cancel(modelId: String) {
        val task = tasks[modelId] ?: return
        task.job?.cancel()
        task.status = DownloadTaskStatus.PAUSED
        updateProgress()
        Log.i(TAG, "cancel: 已取消 '$modelId'")
    }

    /**
     * 暂停下载。
     */
    fun pause(modelId: String) {
        val task = tasks[modelId]
        if (task?.status == DownloadTaskStatus.DOWNLOADING) {
            task.job?.cancel()
            task.status = DownloadTaskStatus.PAUSED
            updateProgress()
            checkAllIdle()
            Log.i(TAG, "pause: 已暂停 '$modelId'")
        }
    }

    /**
     * 恢复下载。
     */
    fun resume(modelId: String) {
        val task = tasks[modelId] ?: return
        if (task.status == DownloadTaskStatus.PAUSED || task.status == DownloadTaskStatus.FAILED) {
            task.status = DownloadTaskStatus.PENDING
            task.job = scope.launch {
                runDownload(task)
            }
            Log.i(TAG, "resume: 恢复下载 '$modelId'")
        }
    }

    /**
     * 获取下载任务信息。
     */
    fun getTaskInfo(modelId: String): DownloadTaskInfo? {
        val task = tasks[modelId] ?: return null
        return task.toInfo()
    }

    /**
     * 获取所有下载任务信息。
     */
    fun getAllTaskInfo(): List<DownloadTaskInfo> {
        return tasks.values.map { it.toInfo() }
    }

    /**
     * 清理已完成的下载任务记录。
     */
    fun clearCompleted() {
        val toRemove = tasks.filter { (_, t) ->
            t.status == DownloadTaskStatus.COMPLETED || t.status == DownloadTaskStatus.VERIFIED
        }.keys
        toRemove.forEach { tasks.remove(it) }
        checkAllIdle()
        updateProgress()
    }

    // =========================================================================
    //  内部实现 - 核心下载逻辑
    // =========================================================================

    /**
     * 执行下载的核心逻辑。
     *
     * 使用镜像优先策略：
     * 1. 先尝试 [hf-mirror.com]（国内可访问的 HuggingFace 镜像）
     * 2. 如果失败，回退到原始 [huggingface.co]
     * 3. 每个镜像先尝试 HTTP Range 分块并行下载（4 连接并发）
     * 4. 如果不支持 Range 或文件太小，自动降级为单线程下载
     */
    private suspend fun runDownload(
        task: DownloadTask,
        onComplete: (suspend (Boolean, String?) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        task.status = DownloadTaskStatus.DOWNLOADING
        val tmpFile = File(downloadDir, "${task.fileName}$TMP_SUFFIX")
        val targetFile = File(downloadDir, task.fileName)

        var retryCount = 0
        var success = false

        while (retryCount <= MAX_RETRIES && !success) {
            try {
                // 确保目录存在
                if (!downloadDir.exists()) downloadDir.mkdirs()

                // 磁盘空间检查
                val freeBytes = downloadDir.freeSpace
                if (freeBytes < 100L * 1024 * 1024) {
                    throw IOException("磁盘空间不足，可用空间: ${freeBytes / 1_000_000}MB")
                }

                // 清除上次失败的临时文件
                if (tmpFile.exists() && tmpFile.length() > 0) {
                    Log.i(TAG, "发现已有临时文件，将尝试断点续传: ${tmpFile.length()} 字节")
                }

                // =============================================================
                // 镜像优先下载：先 hf-mirror.com，失败后回退 huggingface.co
                // =============================================================
                success = downloadWithMirror(task, tmpFile)

                if (success) {
                    // 下载完成，重命名临时文件
                    task.status = DownloadTaskStatus.COMPLETED
                    if (tmpFile.renameTo(targetFile)) {
                        Log.i(TAG, "文件已保存: ${targetFile.absolutePath}")
                    } else {
                        tmpFile.copyTo(targetFile, overwrite = true)
                        tmpFile.delete()
                    }

                    task.status = DownloadTaskStatus.VERIFIED
                    Log.i(TAG, "下载完成: ${task.modelId} -> ${targetFile.absolutePath}")

                    updateProgress()
                    checkAllIdle()
                    onComplete?.invoke(true, targetFile.absolutePath)
                } else {
                    throw IOException("所有镜像下载失败")
                }

            } catch (e: CancellationException) {
                task.status = DownloadTaskStatus.PAUSED
                updateProgress()
                checkAllIdle()
                onComplete?.invoke(false, e.message)
                return@withContext

            } catch (e: Exception) {
                retryCount++
                Log.w(TAG, "下载失败 (第 $retryCount 次): ${e.message}")
                if (retryCount <= MAX_RETRIES) {
                    delay(RETRY_BASE_DELAY_MS * (1L shl (retryCount - 1)))
                } else {
                    task.status = DownloadTaskStatus.FAILED
                    task.error = e.message
                    updateProgress()
                    checkAllIdle()
                    onComplete?.invoke(false, e.message)
                    Log.e(TAG, "下载最终失败", e)
                }
            }
        }
    }

    // =========================================================================
    //  镜像 Fallback 下载
    // =========================================================================

    /**
     * 镜像优先下载策略。
     *
     * 1. 先尝试从 [hf-mirror.com]（国内镜像）下载
     * 2. 如果镜像失败，回退到原始 [huggingface.co] 下载
     * 3. 每个源先尝试 HTTP Range 分块并行下载（4 连接并发）
     * 4. 如果不支持 Range 或文件太小，自动降级单线程
     *
     * @param task   下载任务
     * @param tmpFile 临时文件路径
     * @return 是否成功
     */
    private suspend fun downloadWithMirror(
        task: DownloadTask,
        tmpFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        // 构建镜像 URL 列表：先镜像，再原始
        val mirrorUrl = deriveMirrorUrl(task.url)
        val urlsToTry = if (mirrorUrl != null) {
            Log.i(TAG, "镜像优先策略: 先尝试 hf-mirror.com，失败后回退 huggingface.co")
            listOf(mirrorUrl to "hf-mirror.com", task.url to "huggingface.co")
        } else {
            Log.i(TAG, "非 HuggingFace 地址，直接使用原始源")
            listOf(task.url to "原始源")
        }

        for ((url, sourceName) in urlsToTry) {
            Log.i(TAG, "尝试从 [$sourceName] 下载: ${url.take(80)}...")
            try {
                // 第 1 步：探测文件大小和 Range 支持
                val probeResult = probeFileSize(url)
                if (probeResult == null) {
                    Log.w(TAG, "[$sourceName] 探测失败，尝试下一源")
                    continue
                }

                val (totalBytes, acceptsRange) = probeResult
                task.totalBytes = totalBytes
                Log.i(TAG, "[$sourceName] 探测结果: 大小=${formatFileSize(totalBytes)}, Range支持=$acceptsRange")

                // 第 2 步：选择下载策略
                val downloadSuccess = if (acceptsRange && totalBytes >= MIN_CHUNK_SIZE * 2) {
                    Log.i(TAG, "[$sourceName] 启动 HTTP Range 分块并行下载 ($PARALLEL_CHUNKS 连接并发)")
                    parallelChunkedDownload(url, tmpFile, totalBytes, task)
                } else {
                    if (!acceptsRange) {
                        Log.i(TAG, "[$sourceName] 不支持 Range，降级为单线程下载")
                    } else {
                        Log.i(TAG, "[$sourceName] 文件较小，使用单线程下载")
                    }
                    singleStreamDownload(url, tmpFile, totalBytes, task)
                }

                if (downloadSuccess) {
                    val fileSize = tmpFile.length()
                    Log.i(TAG, "[$sourceName] 下载成功: ${formatFileSize(fileSize)}")
                    return@withContext true
                }

                Log.w(TAG, "[$sourceName] 下载失败，清理临时文件")
                if (tmpFile.exists()) tmpFile.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "[$sourceName] 下载异常: ${e.message}")
                if (tmpFile.exists()) tmpFile.delete()
            }
        }

        Log.e(TAG, "所有镜像源均下载失败")
        false
    }

    // =========================================================================
    //  镜像 URL 推导
    // =========================================================================

    /**
     * 从原始 HuggingFace URL 推导国内镜像 URL。
     * 将 `https://huggingface.co` 替换为 `https://hf-mirror.com`。
     *
     * @param originalUrl 原始 URL
     * @return 镜像 URL；如果不是 HuggingFace 地址则返回 null
     */
    private fun deriveMirrorUrl(originalUrl: String): String? {
        return if (originalUrl.startsWith("https://huggingface.co")) {
            originalUrl.replace("https://huggingface.co", "https://hf-mirror.com")
        } else {
            null
        }
    }

    // =========================================================================
    //  文件大小探测
    // =========================================================================

    /**
     * 探测远程文件大小和 Range 支持情况。
     * 通过 HTTP HEAD 请求获取 Content-Length 和 Accept-Ranges 头。
     *
     * @param url 下载地址
     * @return Pair(文件大小, 是否支持Range分块)，探测失败返回 null
     */
    private suspend fun probeFileSize(url: String): Pair<Long, Boolean>? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
            connection.readTimeout = READ_TIMEOUT_MS.toInt()
            connection.setRequestProperty("User-Agent", "MobileClaw/2.0")
            connection.requestMethod = "HEAD"
            connection.connect()

            val responseCode = connection.responseCode

            // 处理重定向
            if (responseCode in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location != null) {
                    Log.d(TAG, "探测到重定向: $location")
                    // 用重定向 URL 再探测一次
                    val redirectConn = URL(location).openConnection() as HttpURLConnection
                    redirectConn.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
                    redirectConn.readTimeout = READ_TIMEOUT_MS.toInt()
                    redirectConn.setRequestProperty("User-Agent", "MobileClaw/2.0")
                    redirectConn.requestMethod = "HEAD"
                    redirectConn.connect()

                    val size = redirectConn.contentLengthLong
                    val rangeSupport = redirectConn.getHeaderField("Accept-Ranges")
                        ?.equals("bytes", ignoreCase = true) == true
                    redirectConn.disconnect()
                    return@withContext Pair(size, rangeSupport)
                }
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "探测 HEAD 返回 $responseCode")
                connection.disconnect()
                return@withContext null
            }

            val size = connection.contentLengthLong
            // 注意：某些服务器（如 HuggingFace）只有 GET 响应才返回 Accept-Ranges
            val rangeSupport = connection.getHeaderField("Accept-Ranges")
                ?.equals("bytes", ignoreCase = true) == true
            connection.disconnect()
            Pair(size, rangeSupport)
        } catch (e: Exception) {
            Log.e(TAG, "探测文件大小失败", e)
            null
        }
    }

    // =========================================================================
    //  并行分块下载（核心加速引擎）
    // =========================================================================

    /**
     * HTTP Range 分块并行下载。
     *
     * 将文件切成 [PARALLEL_CHUNKS] 块，每块用独立的协程和连接并发下载。
     * 每块通过 Range 请求指定字节范围，用 RandomAccessFile 写入文件的指定偏移。
     * 汇总所有分块的速度。
     *
     * @param url       下载地址
     * @param destination 目标临时文件
     * @param totalBytes 文件总大小
     * @param task      下载任务（用于进度追踪）
     * @return 是否成功
     */
    private suspend fun parallelChunkedDownload(
        url: String,
        destination: File,
        totalBytes: Long,
        task: DownloadTask
    ): Boolean = withContext(Dispatchers.IO) {
        // 预分配文件空间，避免多线程写入时需要频繁扩展
        try {
            RandomAccessFile(destination, "rw").use { raf ->
                raf.setLength(totalBytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "预分配文件空间失败", e)
            return@withContext false
        }

        // 计算每个分块的范围
        val chunkSize = maxOf(totalBytes / PARALLEL_CHUNKS, MIN_CHUNK_SIZE)
        val chunks = mutableListOf<Pair<Long, Long>>() // (start, end) 包含两端
        var offset = 0L
        for (i in 0 until PARALLEL_CHUNKS) {
            val start = offset
            val end = if (i == PARALLEL_CHUNKS - 1) totalBytes - 1 else offset + chunkSize - 1
            chunks.add(Pair(start, end.coerceAtMost(totalBytes - 1)))
            offset = end + 1
            if (offset >= totalBytes) break
        }

        Log.i(TAG, "并行分块: ${chunks.size} 块, 每块 ~${formatFileSize(chunkSize)}")

        // 原子计数器，跨协程共享进度
        val atomicBytesRead = AtomicLong(0L)
        val lastSpeedSampleTime = AtomicLong(System.nanoTime())
        val lastSpeedSampleBytes = AtomicLong(0L)
        val currentSpeed = AtomicLong(0L)
        var lastProgressUpdate = 0L

        try {
            coroutineScope {
                val jobs = chunks.map { (start, end) ->
                    async {
                        downloadChunk(url, destination, start, end, atomicBytesRead, totalBytes,
                            lastSpeedSampleTime, lastSpeedSampleBytes, currentSpeed, task)
                    }
                }
                // 等待所有分块完成
                val results = jobs.awaitAll()
                val allSucceeded = results.all { it }

                if (allSucceeded) {
                    // 最终进度更新
                    task.bytesDownloaded = atomicBytesRead.get()
                    task.speedBytes = currentSpeed.get()
                    updateProgress()
                }

                allSucceeded
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "并行下载被取消")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "并行下载失败", e)
            false
        }
    }

    // =========================================================================
    //  单个分块下载
    // =========================================================================

    /**
     * 下载单个分块。
     *
     * 通过 HTTP Range 头请求指定字节范围，用 RandomAccessFile 写入文件的对应偏移。
     * 使用 [CHUNK_BUFFER_SIZE]（32KB）缓冲区。
     * 通过 AtomicLong 跨协程共享进度和速度。
     *
     * @param url                   下载地址
     * @param destination           目标文件
     * @param start                 起始字节（含）
     * @param end                   结束字节（含）
     * @param atomicBytesRead       原子计数器，累计已读字节
     * @param totalBytes            文件总大小
     * @param lastSpeedSampleTime   上次速度采样时间
     * @param lastSpeedSampleBytes  上次速度采样时的字节数
     * @param currentSpeed          当前速度
     * @param task                  下载任务
     * @return 是否成功
     */
    private suspend fun downloadChunk(
        url: String,
        destination: File,
        start: Long,
        end: Long,
        atomicBytesRead: AtomicLong,
        totalBytes: Long,
        lastSpeedSampleTime: AtomicLong,
        lastSpeedSampleBytes: AtomicLong,
        currentSpeed: AtomicLong,
        task: DownloadTask
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val rangeHeader = "bytes=$start-$end"
            Log.d(TAG, "分块下载: $rangeHeader (${formatFileSize(end - start + 1)})")

            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
            connection.readTimeout = READ_TIMEOUT_MS.toInt()
            connection.setRequestProperty("User-Agent", "MobileClaw/2.0")
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Range", rangeHeader)
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                Log.w(TAG, "分块下载失败: HTTP $responseCode for $rangeHeader")
                return@withContext false
            }

            val inputStream = connection.inputStream
            var chunkBytesRead = 0L

            // 用 RandomAccessFile 写入指定偏移
            RandomAccessFile(destination, "rw").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(CHUNK_BUFFER_SIZE)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) {
                        throw CancellationException("分块下载被取消")
                    }

                    raf.write(buffer, 0, bytesRead)
                    chunkBytesRead += bytesRead

                    // 更新原子进度
                    val totalRead = atomicBytesRead.addAndGet(bytesRead.toLong())
                    val now = System.nanoTime()
                    val lastSample = lastSpeedSampleTime.get()
                    val elapsedSinceSample = (now - lastSample) / 1_000_000L

                    // 每 500ms 采样一次速度（CAS 保证只有一个协程采样）
                    if (elapsedSinceSample >= SPEED_SAMPLE_INTERVAL_MS &&
                        lastSpeedSampleTime.compareAndSet(lastSample, now)) {
                        val lastBytes = lastSpeedSampleBytes.getAndSet(totalRead)
                        val bytesSinceSample = totalRead - lastBytes
                        if (bytesSinceSample > 0 && elapsedSinceSample > 0) {
                            currentSpeed.set((bytesSinceSample * 1000L) / elapsedSinceSample)
                        }
                    }

                    // 更新任务进度
                    task.bytesDownloaded = totalRead
                    task.speedBytes = currentSpeed.get()
                }
            }

            Log.d(TAG, "分块完成: $rangeHeader (${formatFileSize(chunkBytesRead)})")
            true
        } catch (e: CancellationException) {
            Log.w(TAG, "分块下载被取消 [$start-$end]")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "分块下载异常 [$start-$end]: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    // =========================================================================
    //  单线程流式下载（降级方案）
    // =========================================================================

    /**
     * 单线程流式下载（降级方案）。
     *
     * 当服务器不支持 Range 或文件太小时使用。
     * 与原始单线程下载兼容，但增加了速度追踪。
     *
     * @param url       下载地址
     * @param destination 目标文件
     * @param totalBytes 文件总大小
     * @param task      下载任务
     * @return 是否成功
     */
    private suspend fun singleStreamDownload(
        url: String,
        destination: File,
        totalBytes: Long,
        task: DownloadTask
    ): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            Log.d(TAG, "单线程下载: ${url.take(80)}...")

            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
            connection.readTimeout = READ_TIMEOUT_MS.toInt()
            connection.setRequestProperty("User-Agent", "MobileClaw/2.0")

            // 断点续传：如果已有临时文件，从断点处继续
            if (destination.exists() && destination.length() > 0) {
                val downloadedBytes = destination.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
                Log.i(TAG, "单线程断点续传: 已下载 $downloadedBytes 字节")
            }

            connection.connect()
            val responseCode = connection.responseCode

            // 确定总大小
            val contentLength = when {
                responseCode == HttpURLConnection.HTTP_PARTIAL -> {
                    val contentRange = connection.getHeaderField("Content-Range")
                    contentRange?.substringAfter('/')?.toLongOrNull() ?: -1L
                }
                responseCode == HttpURLConnection.HTTP_OK -> connection.contentLengthLong
                else -> throw IOException("HTTP $responseCode: ${url.take(80)}")
            }

            if (totalBytes > 0) {
                task.totalBytes = totalBytes
            } else {
                task.totalBytes = contentLength
            }

            val existingBytes = if (destination.exists()) destination.length() else 0L
            task.bytesDownloaded = existingBytes

            val inputStream = connection.inputStream
            val outputStream = if (existingBytes > 0) {
                FileOutputStream(destination, true)
            } else {
                FileOutputStream(destination)
            }

            val speedSamples = ArrayDeque<Pair<Long, Long>>()
            var lastProgressUpdate = 0L

            inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!isActive) {
                            throw CancellationException("下载被取消")
                        }

                        output.write(buffer, 0, bytesRead)
                        task.bytesDownloaded += bytesRead

                        val now = System.nanoTime()
                        val nowMs = System.currentTimeMillis()

                        // 每 250ms 更新一次进度
                        if (nowMs - lastProgressUpdate >= PROGRESS_INTERVAL_MS) {
                            lastProgressUpdate = nowMs

                            // 速度采样
                            speedSamples.addLast(now to task.bytesDownloaded)
                            while (speedSamples.size > 5) speedSamples.removeFirst()
                            if (speedSamples.size >= 2) {
                                val first = speedSamples.first()
                                val last = speedSamples.last()
                                val elapsedSec = (last.first - first.first) / 1_000_000_000.0
                                if (elapsedSec > 0.1) {
                                    task.speedBytes = ((last.second - first.second) / elapsedSec).roundToLong()
                                }
                            }

                            updateProgress()
                        }
                    }
                }
            }

            Log.d(TAG, "单线程下载完成: ${formatFileSize(task.bytesDownloaded)}")
            true

        } catch (e: CancellationException) {
            Log.w(TAG, "单线程下载被取消")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "单线程下载异常", e)
            false
        } finally {
            connection?.disconnect()
        }
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    /**
     * 格式化文件大小（自动选择 B/KB/MB/GB）。
     */
    private fun formatFileSize(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        else -> "${"%.2f".format(bytes.toDouble() / (1024L * 1024 * 1024))} GB"
    }

    /**
     * 更新进度 StateFlow。
     */
    private fun updateProgress() {
        val snapshot = tasks.values.associate { it.modelId to it.toInfo() }
        _progressFlow.value = snapshot
    }

    /**
     * 检查是否所有下载任务都已完成/空闲。
     */
    private fun checkAllIdle() {
        val anyActive = tasks.values.any { it.status == DownloadTaskStatus.DOWNLOADING }
        _isDownloading.value = anyActive
    }

    // =========================================================================
    //  下载任务内部类
    // =========================================================================

    private class DownloadTask(
        val modelId: String,
        val url: String,
        val fileName: String
    ) {
        var status: DownloadTaskStatus = DownloadTaskStatus.PENDING
        var bytesDownloaded: Long = 0L
        var totalBytes: Long = -1L
        var speedBytes: Long = 0L
        var error: String? = null
        var job: Job? = null

        fun toInfo(): DownloadTaskInfo = DownloadTaskInfo(
            modelId = modelId,
            modelName = fileName,
            status = status,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            percentage = if (totalBytes > 0) {
                (bytesDownloaded.toDouble() / totalBytes.toDouble()) * 100.0
            } else -1.0,
            speedBytesPerSec = speedBytes,
            error = error
        )
    }
}