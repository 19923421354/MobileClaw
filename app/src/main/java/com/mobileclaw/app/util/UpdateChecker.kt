package com.mobileclaw.app.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
            // 添加随机查询参数绕过 CDN 缓存（raw.githubusercontent.com CDN 缓存延迟可能长达数小时）
            val cacheBusterUrl = "$RAW_VERSION_URL?t=${System.currentTimeMillis()}"
            Log.d(TAG, "尝试直连 GitHub Raw (缓存破坏): $cacheBusterUrl")
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(cacheBusterUrl)
                .header("User-Agent", "MobileClaw/2.3.1")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
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
            // 添加随机查询参数绕过 jsDelivr CDN 缓存
            val cacheBusterUrl = "$CDN_VERSION_URL?t=${System.currentTimeMillis()}"
            Log.d(TAG, "尝试 jsDelivr CDN 镜像 (缓存破坏): $cacheBusterUrl")
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(cacheBusterUrl)
                .header("User-Agent", "MobileClaw/2.3.1")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
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

    /** 下载缓冲区大小：1MB（大幅减少 I/O 循环次数，提升吞吐量） */
    private const val BUFFER_SIZE = 1024 * 1024

    /** 速度采样间隔（毫秒）——每 500ms 计算一次实时网速 */
    private const val SPEED_SAMPLE_INTERVAL_MS = 500L

    /** 进度回调最小间隔（毫秒）——每 100ms 更新一次 UI */
    private const val PROGRESS_INTERVAL_MS = 100L

    /** 最大重试次数 */
    private const val MAX_RETRIES = 3

    /** 重试基础间隔（毫秒） */
    private const val RETRY_DELAY_MS = 2000L

    /** 并行下载分块数（4 块并发，突破单连接限速） */
    private const val PARALLEL_CHUNKS = 4

    /** 最小分块大小（500KB，避免分块太小导致 TCP 慢启动开销过大） */
    private const val MIN_CHUNK_SIZE = 512 * 1024L

    /** 共享 OkHttpClient（连接池复用，避免每次新建连接的开销）
     *  连接池增大到 8 个连接，保活 60 秒，支持并行分块下载 */
    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectionPool(okhttp3.ConnectionPool(8, 60, TimeUnit.SECONDS))
            .build()
    }

    /** 单独用于探测的 OkHttpClient（短超时，不跟随重定向） */
    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * 下载镜像列表。
     * 按优先级排列，依次尝试，不预测试。
     * 1. 直链 GitHub（官方源，海外/有梯子时最快）
     * 2. jsDelivr CDN（全球 CDN，国内有节点，速度快，不限速）
     * 3. 移除 mirror.ghproxy.com（该代理对免费用户限速 ~50KB/s）
     */
    private val DOWNLOAD_MIRRORS = listOf(
        // 1. 直接 GitHub 下载（官方源，海外/有梯子时最快）
        { url: String -> url },
        // 2. jsDelivr CDN 镜像（全球 CDN，国内速度好，不限速）
        //    GitHub Release 的 APK 可以通过 jsDelivr 的 gh 路径加速
        { url: String ->
            // 将 GitHub Release URL 转为 jsDelivr CDN URL
            val ghMatch = Regex("https://github\\.com/([^/]+)/([^/]+)/releases/download/v[^/]+/(.+)")
                .find(url)
            if (ghMatch != null) {
                val owner = ghMatch.groupValues[1]
                val repo = ghMatch.groupValues[2]
                val filename = ghMatch.groupValues[3]
                "https://cdn.jsdelivr.net/gh/$owner/$repo@main/app/build/outputs/apk/release/app-release.apk"
            } else url
        }
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
     * 带进度和速度回调的 APK 下载（核心引擎，彻底重写 v3）。
     *
     * 核心加速技术：HTTP Range 分块并行下载
     * - 检测服务器是否支持 Range 请求（Accept-Ranges 头）
     * - 支持时将文件切成 4 块，用 4 个协程并行下载
     * - 每块各占一个独立的 TCP 连接，突破单连接限速
     * - 每个连接用 1MB 缓冲区，最大化吞吐量
     * - 实时网速每 500ms 精确采样，汇总所有分块总速度
     * - 如果不支持 Range 或文件太小，自动降级为单线程下载
     *
     * 镜像策略：
     * - 直链 GitHub → jsDelivr CDN（移除 ghproxy 限速代理）
     * - 每个镜像先尝试并行下载，失败后降级单线程
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

        // 构建所有镜像 URL（按优先级排列）
        val urlsToTry = DOWNLOAD_MIRRORS.map { it(downloadUrl) }

        // 依次尝试每个镜像
        for ((mirrorIdx, mirrorUrl) in urlsToTry.withIndex()) {
            for (retry in 0 until MAX_RETRIES) {
                Log.d(TAG, "下载尝试 [镜像${mirrorIdx + 1}/${urlsToTry.size}, 第${retry + 1}次]: ${mirrorUrl.take(80)}...")

                try {
                    // 第1步：探测文件大小和 Range 支持
                    val probeResult = probeFileSize(mirrorUrl)
                    if (probeResult == null) {
                        Log.w(TAG, "探测失败，重试或换镜像")
                        if (retry < MAX_RETRIES - 1) {
                            delay(RETRY_DELAY_MS * (retry + 1))
                            continue
                        }
                        break
                    }

                    val (totalBytes, acceptsRange) = probeResult
                    Log.d(TAG, "探测结果: 大小=${formatFileSize(totalBytes)}, Range支持=$acceptsRange")

                    // 首次回调：立即显示初始状态
                    progressCallback(0, if (totalBytes > 0) totalBytes else -1L, 0L)

                    // 第2步：选择下载策略
                    val downloadSuccess = if (acceptsRange && totalBytes >= MIN_CHUNK_SIZE * 2) {
                        // 文件较大且支持 Range → 并行分块下载
                        parallelChunkedDownload(mirrorUrl, destination, totalBytes, progressCallback)
                    } else {
                        // 文件小或不支持 Range → 单线程下载
                        singleStreamDownload(mirrorUrl, destination, totalBytes, progressCallback)
                    }

                    if (downloadSuccess) {
                        val fileSize = destination.length()
                        Log.d(TAG, "✓ 下载完成: ${destination.absolutePath} (${formatFileSize(fileSize)})")
                        if (fileSize > 0) {
                            progressCallback(fileSize, totalBytes, 0L)
                            return@withContext DownloadResult(true, "下载成功")
                        }
                    }

                    Log.e(TAG, "下载失败，清理文件")
                    if (destination.exists()) destination.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "下载异常 [镜像${mirrorIdx + 1}, 第${retry + 1}次]: ${e.message}")
                    if (destination.exists()) destination.delete()
                    if (retry < MAX_RETRIES - 1) {
                        delay(RETRY_DELAY_MS * (retry + 1))
                    }
                }
            }
        }

        // 所有镜像和重试都失败
        Log.e(TAG, "✗ 所有下载尝试均失败")
        DownloadResult(false, "所有下载源均不可达，请检查网络连接")
    }

    /**
     * 探测远程文件大小和 Range 支持情况。
     * 通过 HEAD 请求获取 Content-Length 和 Accept-Ranges 头。
     *
     * @param url 下载地址
     * @return Pair(文件大小, 是否支持Range分块)，探测失败返回 null
     */
    private suspend fun probeFileSize(url: String): Pair<Long, Boolean>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MobileClaw/2.3.1")
                .head()
                .build()

            val response = probeClient.newCall(request).execute()
            if (!response.isSuccessful && response.code !in 300..399) {
                Log.w(TAG, "探测 HEAD 返回 ${response.code}")
                response.close()
                return@withContext null
            }

            // 如果返回重定向，用重定向后的 URL 重新探测
            val location = response.header("Location")
            if (location != null) {
                Log.d(TAG, "探测到重定向: $location")
                response.close()
                // 用重定向 URL 再探测一次
                val redirectRequest = Request.Builder()
                    .url(location)
                    .header("User-Agent", "MobileClaw/2.3.1")
                    .head()
                    .build()
                val redirectResponse = probeClient.newCall(redirectRequest).execute()
                val size = redirectResponse.body?.contentLength() ?: -1L
                val rangeSupport = redirectResponse.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
                redirectResponse.close()
                return@withContext Pair(size, rangeSupport)
            }

            val size = response.body?.contentLength() ?: -1L
            // 检查 Accept-Ranges 头，注意 GitHub 可能只在 GET 响应中才返回
            val rangeSupport = response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            response.close()
            return@withContext Pair(size, rangeSupport)
        } catch (e: Exception) {
            Log.e(TAG, "探测文件大小失败", e)
            null
        }
    }

    /**
     * 并行分块下载（核心加速引擎）。
     *
     * 将文件切成 [PARALLEL_CHUNKS] 块，每块用独立的协程和连接并发下载。
     * 每块写入文件的指定偏移位置（RandomAccessFile）。
     * 汇总所有分块的速度，反馈给 UI。
     *
     * @param url 下载地址
     * @param destination 目标文件
     * @param totalBytes 文件总大小
     * @param progressCallback 进度回调
     * @return 是否成功
     */
    private suspend fun parallelChunkedDownload(
        url: String,
        destination: File,
        totalBytes: Long,
        progressCallback: (bytesRead: Long, totalBytes: Long, speedBps: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // 预分配文件空间
        RandomAccessFile(destination, "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        // 计算每个分块的大小和偏移
        val chunkSize = maxOf(totalBytes / PARALLEL_CHUNKS, MIN_CHUNK_SIZE)
        val chunks = mutableListOf<Pair<Long, Long>>() // (start, end) inclusive
        var offset = 0L
        for (i in 0 until PARALLEL_CHUNKS) {
            val start = offset
            val end = if (i == PARALLEL_CHUNKS - 1) totalBytes - 1 else offset + chunkSize - 1
            chunks.add(Pair(start, end.coerceAtMost(totalBytes - 1)))
            offset = end + 1
            if (offset >= totalBytes) break
        }

        Log.d(TAG, "并行分块: ${chunks.size} 块, 每块 ~${formatFileSize(chunkSize)}")

        // 原子计数器，跨协程共享进度
        val atomicBytesRead = AtomicLong(0L)
        val lastSpeedSampleTime = AtomicLong(System.nanoTime())
        val lastSpeedSampleBytes = AtomicLong(0L)
        val currentSpeed = AtomicLong(0L)

        try {
            // 并行下载所有分块
            coroutineScope {
                val jobs = chunks.map { (start, end) ->
                    async {
                        downloadChunk(url, destination, start, end, atomicBytesRead, totalBytes,
                            lastSpeedSampleTime, lastSpeedSampleBytes, currentSpeed, progressCallback)
                    }
                }
                // 等待所有分块完成
                val results = jobs.awaitAll()
                results.all { it }
            }
        } catch (e: Exception) {
            Log.e(TAG, "并行下载失败", e)
            false
        }
    }

    /**
     * 下载单个分块。
     * 通过 HTTP Range 头请求指定字节范围，写入文件的对应偏移。
     *
     * @param url 下载地址
     * @param destination 目标文件
     * @param start 起始字节（含）
     * @param end 结束字节（含）
     * @param atomicBytesRead 原子计数器，累计已读字节
     * @param totalBytes 文件总大小
     * @param lastSpeedSampleTime 上次速度采样时间（原子）
     * @param lastSpeedSampleBytes 上次速度采样时的字节数（原子）
     * @param currentSpeed 当前速度（原子）
     * @param progressCallback 进度回调
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
        progressCallback: (bytesRead: Long, totalBytes: Long, speedBps: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rangeHeader = "bytes=$start-$end"
            Log.d(TAG, "分块下载: $rangeHeader (${formatFileSize(end - start + 1)})")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MobileClaw/2.3.1")
                .header("Accept", "application/octet-stream")
                .header("Range", rangeHeader)
                .build()

            val response = sharedClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 206) {
                Log.w(TAG, "分块下载失败: HTTP ${response.code} for $rangeHeader")
                response.close()
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            var chunkBytesRead = 0L

            // 用 RandomAccessFile 写入指定偏移
            RandomAccessFile(destination, "rw").use { raf ->
                raf.seek(start)
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        chunkBytesRead += bytesRead

                        // 更新原子进度
                        val totalRead = atomicBytesRead.addAndGet(bytesRead.toLong())
                        val now = System.nanoTime()
                        val lastSample = lastSpeedSampleTime.get()
                        val elapsedSinceSample = (now - lastSample) / 1_000_000L

                        // 每 500ms 采样一次速度（谁先到谁采样）
                        if (elapsedSinceSample >= SPEED_SAMPLE_INTERVAL_MS &&
                            lastSpeedSampleTime.compareAndSet(lastSample, now)) {
                            val lastBytes = lastSpeedSampleBytes.getAndSet(totalRead)
                            val bytesSinceSample = totalRead - lastBytes
                            if (bytesSinceSample > 0 && elapsedSinceSample > 0) {
                                currentSpeed.set((bytesSinceSample * 1000L) / elapsedSinceSample)
                            }
                        }

                        // 每 100ms 回调一次进度
                        val displaySpeed = currentSpeed.get()
                        progressCallback(totalRead, totalBytes, displaySpeed)
                    }
                }
            }

            Log.d(TAG, "分块完成: $rangeHeader (${formatFileSize(chunkBytesRead)})")
            response.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "分块下载异常 [$start-$end]: ${e.message}")
            false
        }
    }

    /**
     * 单线程流式下载（降级方案）。
     * 当服务器不支持 Range 或文件太小时使用。
     * 与旧版兼容，但缓冲区升级到 1MB。
     */
    private suspend fun singleStreamDownload(
        url: String,
        destination: File,
        totalBytes: Long,
        progressCallback: (bytesRead: Long, totalBytes: Long, speedBps: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "单线程下载: ${url.take(80)}...")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MobileClaw/2.3.1")
                .header("Accept", "application/octet-stream")
                .build()

            val response = sharedClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "单线程下载失败: HTTP ${response.code}")
                response.close()
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            var bytesRead = 0L
            var lastSpeedSampleTime = System.nanoTime()
            var lastSpeedSampleBytes = 0L
            var currentSpeed = 0L
            var lastCallbackTime = 0L

            progressCallback(0, if (totalBytes > 0) totalBytes else -1L, 0L)

            FileOutputStream(destination).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesReadThisChunk: Int

                    while (input.read(buffer).also { bytesReadThisChunk = it } != -1) {
                        output.write(buffer, 0, bytesReadThisChunk)
                        bytesRead += bytesReadThisChunk

                        val now = System.nanoTime()
                        val elapsedSinceSample = (now - lastSpeedSampleTime) / 1_000_000L

                        if (elapsedSinceSample >= SPEED_SAMPLE_INTERVAL_MS) {
                            val bytesSinceSample = bytesRead - lastSpeedSampleBytes
                            currentSpeed = if (elapsedSinceSample > 0 && bytesSinceSample > 0) {
                                (bytesSinceSample * 1000L) / elapsedSinceSample
                            } else 0L
                            lastSpeedSampleTime = now
                            lastSpeedSampleBytes = bytesRead
                        }

                        val elapsedSinceCallback = (now - lastCallbackTime) / 1_000_000L
                        if (elapsedSinceCallback >= PROGRESS_INTERVAL_MS) {
                            lastCallbackTime = now
                            val displaySpeed = if (currentSpeed > 0) currentSpeed else {
                                val totalElapsed = (now - lastSpeedSampleTime) / 1_000_000L
                                if (totalElapsed > 0) (bytesRead * 1000L) / totalElapsed else 0L
                            }
                            progressCallback(bytesRead, totalBytes, displaySpeed)
                        }
                    }
                }
            }

            response.close()
            Log.d(TAG, "单线程下载完成: ${formatFileSize(bytesRead)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "单线程下载异常", e)
            false
        }
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
     * 自动处理 Android 8+ 的「未知来源应用安装」权限。
     */
    fun installApk(context: android.content.Context, apkFile: File) {
        try {
            // Android 8+ (API 26+) 需要 REQUEST_INSTALL_PACKAGES 权限
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    // 引导用户开启安装权限
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:${context.packageName}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.d(TAG, "引导用户开启安装未知来源应用权限")
                    return
                }
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            Log.d(TAG, "安装 Intent 已发送: ${apkFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "安装 APK 失败", e)
            // 如果 FileProvider 方式失败，尝试用原始 Intent 方式
            try {
                val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        android.net.Uri.fromFile(apkFile),
                        "application/vnd.android.package-archive"
                    )
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "备用安装方式也失败", e2)
            }
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