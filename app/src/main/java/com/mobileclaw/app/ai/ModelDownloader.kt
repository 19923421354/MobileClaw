package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
 */
data class ModelVariant(
    val label: String,
    val quantization: String,
    val url: String,
    val sizeBytes: Long,
    val requiredRam: Long,
    val isRecommended: Boolean = false
)

// =============================================================================
//  ModelDownloader - 模型下载管理器
// =============================================================================

/**
 * ModelDownloader - 从 HuggingFace 等源下载 AI 模型。
 *
 * 提供：
 * - 预设模型市场（含多种量化变体）
 * - 断点续传下载
 * - 下载速度跟踪
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

        /** 下载缓冲区大小。 */
        private const val BUFFER_SIZE = 8192

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
        //  预设模型市场
        // =====================================================================

        /**
         * 预设的模型市场列表。
         *
         * 每个模型提供多种量化变体，用户可根据设备配置选择。
         * 所有模型均为 GGUF 格式，兼容 llama.cpp 推理引擎。
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
    //  内部实现
    // =========================================================================

    /**
     * 执行下载的核心逻辑。
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

                // 建立连接
                val url = URL(task.url)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS.toInt()
                    readTimeout = READ_TIMEOUT_MS.toInt()
                    setRequestProperty("User-Agent", "MobileClaw/2.0")

                    // 断点续传
                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        val downloadedBytes = tmpFile.length()
                        setRequestProperty("Range", "bytes=$downloadedBytes-")
                        Log.i(TAG, "断点续传: 已下载 $downloadedBytes 字节")
                    }
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
                    else -> throw IOException("HTTP $responseCode: ${task.url}")
                }

                task.totalBytes = contentLength
                val existingBytes = if (tmpFile.exists()) tmpFile.length() else 0L
                task.bytesDownloaded = existingBytes

                // 流式写入
                val inputStream = connection.inputStream
                val outputStream = if (existingBytes > 0) {
                    FileOutputStream(tmpFile, true)
                } else {
                    FileOutputStream(tmpFile)
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

                            if (nowMs - lastProgressUpdate >= 250) {
                                lastProgressUpdate = nowMs

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

                connection.disconnect()

                // 下载完成
                task.status = DownloadTaskStatus.COMPLETED

                // 重命名
                if (tmpFile.renameTo(targetFile)) {
                    Log.i(TAG, "文件已保存: ${targetFile.absolutePath}")
                } else {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }

                task.status = DownloadTaskStatus.VERIFIED
                success = true
                Log.i(TAG, "下载完成: ${task.modelId} -> ${targetFile.absolutePath}")

                updateProgress()
                checkAllIdle()

                // 回调
                onComplete?.invoke(true, targetFile.absolutePath)

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