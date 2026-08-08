package com.mobileclaw.app.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 更新检查器。
 *
 * 通过多重镜像源获取最新版本信息：
 * 1. jsDelivr CDN（国内可访问，速度快）
 * 2. GitHub Releases API（官方源）
 * 3. Gitee 镜像（国内备用）
 * 4. 直链 HEAD 探测（最终回退）
 *
 * 支持版本对比、更新日志展示、APK 下载与安装。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** GitHub 仓库所有者 */
    private const val GITHUB_OWNER = "19923421354"

    /** GitHub 仓库名 */
    private const val GITHUB_REPO = "MobileClaw"

    /** 1. 直连 GitHub Raw（权威源，无 CDN 缓存延迟问题） */
    private const val RAW_VERSION_URL =
        "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/version.json"

    /** 备用: jsDelivr CDN 镜像（国内可访问，但可能有缓存延迟） */
    private const val CDN_VERSION_URL =
        "https://cdn.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@main/version.json"

    /** 2. GitHub Releases API 地址 */
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** 3. Gitee 镜像（国内备用） */
    private const val GITEE_RELEASES_URL =
        "https://gitee.com/api/v5/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** 下载超时（毫秒） */
    private const val DOWNLOAD_TIMEOUT_MS = 300_000L

    /** 连接测试超时 */
    private const val CONNECT_TIMEOUT_MS = 5000L

    // ========== 版本号比较 ==========

    /** 简单版本号比较（"2.0.2" > "2.0.1"）。仅支持三位数语义化版本。 */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }

    // ========== 网络连通性检测 ==========

    /**
     * 检测网络连通性，判断是否能访问 GitHub。
     * 用于在 UI 上区分"网络错误"和"已是最新版本"。
     */
    suspend fun isNetworkAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()

            // 测试多个源，任意一个通即可
            val testUrls = listOf(
                "https://cdn.jsdelivr.net",
                "https://api.github.com",
                "https://github.com",
                "https://gitee.com"
            )
            for (url in testUrls) {
                try {
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful || response.code in 300..499) {
                        response.close()
                        return@withContext true
                    }
                    response.close()
                } catch (_: Exception) { continue }
            }
            false
        } catch (_: Exception) { false }
    }

    // ========== 主要检查入口 ==========

    /**
     * 检查是否有新版本（多重镜像 + 多重 Fallback）。
     *
     * 检查顺序：
     * 1. jsDelivr CDN（最快，国内可访问）
     * 2. GitHub Releases API（官方源）
     * 3. Gitee API（国内备用）
     * 4. 直链 HEAD 探测（最终回退）
     *
     * @param currentVersion 当前版本号（如 "2.0.5"）
     * @return [UpdateInfo] 有新版本时返回；无更新或失败时返回 null
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // 尝试所有源，按优先级顺序
        val result = tryRawGitHub(currentVersion)   // 1. 直连 GitHub Raw（最快最新，但国内可能被墙）
            ?: tryGitHubApi(currentVersion)          // 2. GitHub API（官方源，权威）
            ?: tryCdnMirror(currentVersion)          // 3. jsDelivr CDN（国内加速，但有缓存延迟）
            ?: tryGiteeApi(currentVersion)           // 4. Gitee API（国内备用）
            ?: tryFallbackCheck(currentVersion)      // 5. 直链 HEAD 探测（最终回退）

        if (result != null) {
            Log.d(TAG, "检查更新完成，发现新版本: ${result.latestVersion}")
        } else {
            Log.d(TAG, "检查更新完成，无新版本或所有源均不可达")
        }
        return@withContext result
    }

    // ========== 各源实现 ==========

    /**
     * 1. 直连 GitHub Raw 读取 version.json（权威源，无缓存延迟）。
     * 直接读取仓库根目录的 version.json，实时获取最新版本信息。
     * 注意：raw.githubusercontent.com 在国内可能被墙，所以后面还有备用源。
     */
    private suspend fun tryRawGitHub(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "尝试直连 GitHub Raw: $RAW_VERSION_URL")
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(RAW_VERSION_URL)
                .header("User-Agent", "MobileClaw")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub Raw 返回 ${response.code}，尝试下一源")
                response.close()
                return@withContext null
            }

            val body = response.body?.string() ?: run {
                response.close()
                return@withContext null
            }
            response.close()

            val json = Json { ignoreUnknownKeys = true }
            val versionInfo = json.decodeFromString<CdnVersionInfo>(body)

            val tagVersion = versionInfo.version.removePrefix("v").removePrefix("V")
            if (compareVersions(tagVersion, currentVersion) <= 0) {
                Log.d(TAG, "GitHub Raw: 当前版本 $currentVersion 已是最新 (latest: $tagVersion)")
                return@withContext null
            }

            // 构建下载链接
            val downloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$tagVersion/MobileClaw-v$tagVersion.apk"

            UpdateInfo(
                latestVersion = tagVersion,
                downloadUrl = downloadUrl,
                apkName = "MobileClaw-v$tagVersion.apk",
                apkSize = 0L,
                changelog = versionInfo.changelog ?: "请查看 GitHub Releases 获取完整更新日志。",
                releaseUrl = versionInfo.releaseUrl
                    ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/v$tagVersion",
                publishedAt = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "GitHub Raw 检查失败", e)
            null
        }
    }

    /**
     * 2. jsDelivr CDN 镜像检查（备用源）。
     * 通过 CDN 读取仓库根目录的 version.json 获取最新版本信息。
     * CDN 在国内可访问，但有缓存延迟（最长数小时）。
     * 改用 @main 分支代替 @latest，减少缓存不一致问题。
     */
    private suspend fun tryCdnMirror(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "尝试 jsDelivr CDN 镜像: $CDN_VERSION_URL")
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(CDN_VERSION_URL)
                .header("User-Agent", "MobileClaw")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "CDN 镜像返回 ${response.code}，尝试下一源")
                response.close()
                return@withContext null
            }

            val body = response.body?.string() ?: run {
                response.close()
                return@withContext null
            }
            response.close()

            val json = Json { ignoreUnknownKeys = true }
            val versionInfo = json.decodeFromString<CdnVersionInfo>(body)

            val tagVersion = versionInfo.version.removePrefix("v").removePrefix("V")
            if (compareVersions(tagVersion, currentVersion) <= 0) {
                Log.d(TAG, "CDN 镜像: 当前版本 $currentVersion 已是最新 (latest: $tagVersion)")
                return@withContext null
            }

            // 构建下载链接（注意：GitHub Release 的 APK 文件名带 v 前缀）
            val downloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$tagVersion/MobileClaw-v$tagVersion.apk"

            UpdateInfo(
                latestVersion = tagVersion,
                downloadUrl = downloadUrl,
                apkName = "MobileClaw-v$tagVersion.apk",
                apkSize = 0L,
                changelog = versionInfo.changelog ?: "请查看 GitHub Releases 获取完整更新日志。",
                releaseUrl = versionInfo.releaseUrl
                    ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/v$tagVersion",
                publishedAt = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "CDN 镜像检查失败", e)
            null
        }
    }

    /**
     * 3. GitHub Releases API 检查。
     * 官方源，在海外或有梯子的环境下可用。
     */
    private suspend fun tryGitHubApi(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "尝试 GitHub Releases API: $RELEASES_API_URL")
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "MobileClaw")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API 返回 ${response.code}")
                response.close()
                return@withContext null
            }

            val body = response.body?.string() ?: run {
                response.close()
                return@withContext null
            }
            response.close()

            val json = Json { ignoreUnknownKeys = true }
            val release = json.decodeFromString<GitHubRelease>(body)

            val tagVersion = release.tagName.removePrefix("v").removePrefix("V")
            if (compareVersions(tagVersion, currentVersion) <= 0) {
                Log.d(TAG, "GitHub API: 当前版本 $currentVersion 已是最新 (latest: $tagVersion)")
                return@withContext null
            }

            // 找第一个 APK 附件
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            val downloadUrl = apkAsset?.browserDownloadUrl
                ?: "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$tagVersion/MobileClaw-$tagVersion.apk"

            UpdateInfo(
                latestVersion = tagVersion,
                downloadUrl = downloadUrl,
                apkName = apkAsset?.name ?: "MobileClaw-$tagVersion.apk",
                apkSize = apkAsset?.size ?: 0L,
                changelog = release.body ?: "暂无更新日志",
                releaseUrl = release.htmlUrl,
                publishedAt = release.publishedAt ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "GitHub API 检查失败", e)
            null
        }
    }

    /**
     * 4. Gitee API 检查（国内备用）。
     * 通过 Gitee Releases API 获取最新版本信息。
     */
    private suspend fun tryGiteeApi(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "尝试 Gitee API: $GITEE_RELEASES_URL")
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(GITEE_RELEASES_URL)
                .header("User-Agent", "MobileClaw")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Gitee API 返回 ${response.code}")
                response.close()
                return@withContext null
            }

            val body = response.body?.string() ?: run {
                response.close()
                return@withContext null
            }
            response.close()

            val json = Json { ignoreUnknownKeys = true }
            val release = json.decodeFromString<GiteeRelease>(body)

            val tagVersion = release.tagName.removePrefix("v").removePrefix("V")
            if (compareVersions(tagVersion, currentVersion) <= 0) {
                Log.d(TAG, "Gitee API: 当前版本 $currentVersion 已是最新 (latest: $tagVersion)")
                return@withContext null
            }

            val downloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$tagVersion/MobileClaw-v$tagVersion.apk"

            UpdateInfo(
                latestVersion = tagVersion,
                downloadUrl = downloadUrl,
                apkName = "MobileClaw-$tagVersion.apk",
                apkSize = 0L,
                changelog = release.body ?: "请查看 GitHub Releases 获取完整更新日志。",
                releaseUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/v$tagVersion",
                publishedAt = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Gitee API 检查失败", e)
            null
        }
    }

    /**
     * 5. 直链 HEAD 探测（最终回退）。
     * 直接尝试访问预期的最新版本 APK 下载链接。
     * 如果服务器返回 200 则说明该版本存在。
     */
    private suspend fun tryFallbackCheck(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            // 尝试检查下一个版本号是否存在（递增补丁版本号）
            val parts = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val nextPatch = if (parts.size >= 3) {
                "${parts[0]}.${parts[1]}.${parts[2] + 1}"
            } else {
                "${currentVersion}.1"
            }

            // 尝试 HEAD 请求检查 APK 是否存在
            val testUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$nextPatch/MobileClaw-v$nextPatch.apk"
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val headRequest = Request.Builder()
                .url(testUrl)
                .head()
                .build()
            val headResponse = client.newCall(headRequest).execute()
            val exists = headResponse.code in 200..302
            headResponse.close()

            if (exists) {
                Log.d(TAG, "直链探测: 发现 v$nextPatch")
                UpdateInfo(
                    latestVersion = nextPatch,
                    downloadUrl = testUrl,
                    apkName = "MobileClaw-v$nextPatch.apk",
                    apkSize = 0L,
                    changelog = "检测到新版本 v$nextPatch（自动检测）\n请前往 GitHub Releases 查看完整更新日志。",
                    releaseUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/tag/v$nextPatch",
                    publishedAt = ""
                )
            } else {
                Log.d(TAG, "直链探测: 未发现新版本 (v$nextPatch 不存在)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "直链探测失败", e)
            null
        }
    }

    // ========== 下载与安装 ==========

    /** 下载缓冲区大小：256KB（比旧版 8KB 大 32 倍，大幅减少 I/O 循环次数） */
    private const val BUFFER_SIZE = 256 * 1024

    /** 速度采样间隔（毫秒）——每 500ms 计算一次实时网速 */
    private const val SPEED_SAMPLE_INTERVAL_MS = 500L

    /** 进度回调最小间隔（毫秒）——每 100ms 更新一次 UI */
    private const val PROGRESS_INTERVAL_MS = 100L

    /** 最大重试次数 */
    private const val MAX_RETRIES = 3

    /** 重试基础间隔（毫秒） */
    private const val RETRY_DELAY_MS = 2000L

    /** 共享 OkHttpClient（连接池复用，避免每次新建连接的开销） */
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS))
            .build()
    }

    /** GitHub Release 下载镜像列表（含国内加速镜像） */
    private val DOWNLOAD_MIRRORS = listOf(
        // 1. 直接 GitHub 下载（官方源，海外/有梯子时最快）
        { url: String -> url },
        // 2. mirror.ghproxy.com 镜像（国内加速，速度较快）
        { url: String -> "https://mirror.ghproxy.com/$url" },
        // 3. ghproxy.net 镜像（国内备用）
        { url: String -> "https://ghproxy.net/$url" },
        // 4. download.fastgit.org 镜像（GitHub 加速）
        { url: String -> url.replace("github.com", "download.fastgit.org") }
    )

    /**
     * 格式化文件大小（自动选择 B/KB/MB）。
     */
    fun formatFileSize(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }

    /**
     * 格式化下载速度（自动选择 B/s / KB/s / MB/s）。
     * 速度值为实时采样计算，反映当前网络状况。
     */
    fun formatSpeed(speedBps: Long): String = when {
        speedBps < 0 -> "未知"
        speedBps < 1024 -> "${speedBps} B/s"
        speedBps < 1024 * 1024 -> "${speedBps / 1024} KB/s"
        else -> "${"%.1f".format(speedBps.toDouble() / (1024 * 1024))} MB/s"
    }

    /**
     * 预测试所有镜像源的速度，选择连接最快的镜像。
     *
     * 通过 HEAD 请求 + 连接时间（毫秒）判断，自动跳过
     * 连接超时或 DNS 解析失败的镜像。
     *
     * @param downloadUrl 原始下载地址
     * @return 最快的镜像 URL
     */
    private fun selectFastestMirror(downloadUrl: String): String {
        val results = mutableListOf<Pair<String, Long>>()
        for (transformer in DOWNLOAD_MIRRORS) {
            val url = transformer(downloadUrl)
            try {
                val start = System.nanoTime()
                val request = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", "MobileClaw/2.0.9")
                    .build()
                val response = sharedClient.newCall(request).execute()
                val elapsed = (System.nanoTime() - start) / 1_000_000L // 毫秒
                response.close()
                results.add(url to elapsed)
                Log.d(TAG, "镜像测速: ${url.take(60)}... = ${elapsed}ms")
            } catch (e: Exception) {
                Log.w(TAG, "镜像测速失败: ${url.take(60)}... = ${e.message}")
                results.add(url to Long.MAX_VALUE)
            }
        }
        val best = results.minByOrNull { it.second } ?: return downloadUrl
        Log.d(TAG, "✓ 选择最快镜像: ${best.first.take(60)}... (${best.second}ms)")
        return best.first
    }

    /**
     * 带进度和速度回调的 APK 下载（核心引擎）。
     *
     * 性能优化：
     * - 256KB 大缓冲区，减少 read/write 循环次数约 97%
     * - OkHttp 连接池复用（4 连接，保活 30 秒），避免重复 TCP 握手
     * - 镜像源 HEAD 预测试，自动选择延迟最低的源
     * - 重试指数退避：2s → 4s → 6s
     *
     * 用户体验：
     * - 实时网速显示（每 500ms 采样计算）
     * - 流畅 UI 更新（每 100ms 回调，不会卡死主线程）
     * - 断点续传检测（已下载且 >1MB 直接跳过）
     *
     * @param downloadUrl 原始下载地址（GitHub Releases URL）
     * @param destination 保存路径
     * @param progressCallback 进度回调：(bytesRead, totalBytes, speedBps) -> Unit
     * @return 下载成功返回 true，失败返回 false
     */
    suspend fun downloadApkWithProgress(
        downloadUrl: String,
        destination: File,
        progressCallback: (bytesRead: Long, totalBytes: Long, speedBps: Long) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        // 如果文件已存在且有效，直接返回成功
        if (destination.exists() && destination.length() > 1_000_000) {
            Log.d(TAG, "文件已存在，跳过下载: ${destination.absolutePath} (${formatFileSize(destination.length())})")
            return@withContext DownloadResult(true, "文件已存在")
        }

        // 确保父目录存在
        destination.parentFile?.mkdirs()

        // 预测试所有镜像，选择最快的镜像源
        val bestMirrorUrl = selectFastestMirror(downloadUrl)

        // 使用选中的最快镜像进行下载，最多重试 MAX_RETRIES 次
        for (retry in 0 until MAX_RETRIES) {
            Log.d(TAG, "下载尝试 [第${retry + 1}次]: ${bestMirrorUrl.take(80)}...")

            try {
                val request = Request.Builder()
                    .url(bestMirrorUrl)
                    .header("User-Agent", "MobileClaw/2.0.9")
                    .header("Accept", "application/octet-stream")
                    .build()

                val response = sharedClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "下载失败: HTTP ${response.code}")
                    response.close()
                    if (retry < MAX_RETRIES - 1) {
                        delay(RETRY_DELAY_MS * (retry + 1)) // 指数退避
                        continue
                    }
                    return@withContext DownloadResult(false, "HTTP ${response.code}")
                }

                val body = response.body
                if (body == null) {
                    Log.w(TAG, "响应体为空")
                    response.close()
                    return@withContext DownloadResult(false, "响应体为空")
                }

                val totalBytes = body.contentLength()
                var bytesRead = 0L
                var lastSpeedSampleTime = System.nanoTime()
                var lastSpeedSampleBytes = 0L
                var currentSpeed = 0L
                var lastCallbackTime = 0L

                FileOutputStream(destination).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesReadThisChunk: Int

                        // 首次回调：告知开始下载
                        progressCallback(0, if (totalBytes > 0) totalBytes else -1L, 0L)

                        // 主下载循环：每次读取 256KB，大幅减少 I/O 次数
                        while (input.read(buffer).also { bytesReadThisChunk = it } != -1) {
                            output.write(buffer, 0, bytesReadThisChunk)
                            bytesRead += bytesReadThisChunk

                            // 实时网速采样：每 500ms 计算一次
                            val now = System.nanoTime()
                            val elapsedSinceSample = (now - lastSpeedSampleTime) / 1_000_000L
                            if (elapsedSinceSample >= SPEED_SAMPLE_INTERVAL_MS) {
                                val bytesSinceSample = bytesRead - lastSpeedSampleBytes
                                currentSpeed = if (elapsedSinceSample > 0) {
                                    (bytesSinceSample * 1000L) / elapsedSinceSample
                                } else 0L
                                lastSpeedSampleTime = now
                                lastSpeedSampleBytes = bytesRead
                            }

                            // UI 更新：每 100ms 回调一次，保证流畅不卡顿
                            val elapsedSinceCallback = (now - lastCallbackTime) / 1_000_000L
                            if (elapsedSinceCallback >= PROGRESS_INTERVAL_MS) {
                                lastCallbackTime = now
                                progressCallback(bytesRead, totalBytes, currentSpeed)
                            }
                        }
                    }
                }

                // 下载完成，最终回调（100% + 最终速度）
                progressCallback(bytesRead, totalBytes, currentSpeed)

                // 校验下载结果
                val fileSize = destination.length()
                Log.d(TAG, "✓ 下载完成: ${destination.absolutePath} (${formatFileSize(fileSize)})")

                if (fileSize == 0L) {
                    Log.e(TAG, "下载的文件大小为 0，视为失败")
                    destination.delete()
                    continue
                }

                return@withContext DownloadResult(true, "下载成功")
            } catch (e: Exception) {
                Log.e(TAG, "下载异常 [第${retry + 1}次]: ${e.message}", e)
                // 清理损坏的文件
                if (destination.exists()) destination.delete()
                if (retry < MAX_RETRIES - 1) {
                    val delay = RETRY_DELAY_MS * (retry + 1) // 指数退避
                    delay(delay)
                }
            }
        }

        // 所有重试都失败
        Log.e(TAG, "✗ 所有下载尝试均失败")
        DownloadResult(false, "所有下载源均不可达，请检查网络连接")
    }

    /**
     * 下载 APK 文件到本地（无进度回调，兼容旧接口）。
     * 内部委托给 [downloadApkWithProgress]。
     *
     * @param downloadUrl 下载地址
     * @param destination 保存路径
     * @return 下载成功返回 true
     */
    suspend fun downloadApk(downloadUrl: String, destination: File): Boolean = withContext(Dispatchers.IO) {
        val result = downloadApkWithProgress(downloadUrl, destination) { _, _, _ -> }
        result.success
    }

    /**
     * 触发 APK 安装（通过系统安装 Intent）。
     */
    fun installApk(context: android.content.Context, apkFile: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "安装 APK 失败", e)
        }
    }

    /**
     * 下载结果。
     *
     * @param success 是否成功
     * @param message 结果描述
     */
    data class DownloadResult(
        val success: Boolean,
        val message: String
    )
}

// ========== 数据类 ==========

/** 更新信息 */
data class UpdateInfo(
    val latestVersion: String,
    val downloadUrl: String,
    val apkName: String,
    val apkSize: Long,
    val changelog: String,
    val releaseUrl: String,
    val publishedAt: String
) {
    /** 格式化后的文件大小 */
    fun formattedSize(): String = when {
        apkSize < 1_000_000 -> "${apkSize / 1000} KB"
        else -> "${"%.1f".format(apkSize.toDouble() / 1_000_000)} MB"
    }
}

/** jsDelivr CDN version.json 响应结构 */
@Serializable
data class CdnVersionInfo(
    @SerialName("version") val version: String,
    @SerialName("versionCode") val versionCode: Int = 0,
    @SerialName("releaseUrl") val releaseUrl: String? = null,
    @SerialName("changelog") val changelog: String? = null
)

/** GitHub Release API 响应结构 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String = ""
)

/** Gitee Release API 响应结构 */
@Serializable
data class GiteeRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("body") val body: String? = null
)