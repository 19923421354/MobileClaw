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

    /** 1. jsDelivr CDN 镜像（国内可访问，优先使用） */
    private const val CDN_VERSION_URL =
        "https://cdn.jsdelivr.net/gh/$GITHUB_OWNER/$GITHUB_REPO@latest/version.json"

    /** 2. GitHub Releases API 地址 */
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** 3. Gitee 镜像（国内备用） */
    private const val GITEE_RELEASES_URL =
        "https://gitee.com/api/v5/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** 下载超时（毫秒） */
    private const val DOWNLOAD_TIMEOUT_MS = 120_000L

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
        val result = tryCdnMirror(currentVersion)
            ?: tryGitHubApi(currentVersion)
            ?: tryGiteeApi(currentVersion)
            ?: tryFallbackCheck(currentVersion)

        if (result != null) {
            Log.d(TAG, "检查更新完成，版本: ${result.latestVersion}")
        } else {
            Log.d(TAG, "检查更新完成，无新版本或所有源均不可达")
        }
        return@withContext result
    }

    // ========== 各源实现 ==========

    /**
     * 1. jsDelivr CDN 镜像检查。
     * 通过读取仓库根目录的 version.json 获取最新版本信息。
     * CDN 在国内可访问，速度快，推荐用于国内用户。
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

            // 构建下载链接
            val downloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$tagVersion/MobileClaw-$tagVersion.apk"

            UpdateInfo(
                latestVersion = tagVersion,
                downloadUrl = downloadUrl,
                apkName = "MobileClaw-$tagVersion.apk",
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
     * 2. GitHub Releases API 检查。
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
     * 3. Gitee API 检查（国内备用）。
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

            val downloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$tagVersion/MobileClaw-$tagVersion.apk"

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
     * 4. 直链 HEAD 探测（最终回退）。
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
            val testUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/v$nextPatch/MobileClaw-$nextPatch.apk"
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
                    apkName = "MobileClaw-$nextPatch.apk",
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

    /** 下载缓冲区大小：8KB */
    private const val BUFFER_SIZE = 8 * 1024

    /** 最大重试次数 */
    private const val MAX_RETRIES = 3

    /** 重试间隔（毫秒） */
    private const val RETRY_DELAY_MS = 2000L

    /** GitHub Release 下载镜像列表 */
    private val DOWNLOAD_MIRRORS = listOf(
        // 1. 直接 GitHub 下载（官方源）
        { url: String -> url },
        // 2. ghproxy.com 镜像（国内加速）
        { url: String -> "https://mirror.ghproxy.com/$url" },
        // 3. ghproxy.net 镜像（国内备用）
        { url: String -> "https://ghproxy.net/$url" }
    )

    /**
     * 格式化文件大小（自动选择 KB/MB）。
     */
    fun formatFileSize(bytes: Long): String = when {
        bytes < 0 -> "未知"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }

    /**
     * 带进度回调的 APK 下载。
     *
     * 支持：
     * - 实时进度回调（已下载字节数、总字节数）
     * - 多镜像源自动切换
     * - 自动重试（最多 [MAX_RETRIES] 次）
     * - 断点续传（如果文件已存在且长度一致则跳过）
     *
     * @param downloadUrl 原始下载地址（GitHub Releases URL）
     * @param destination 保存路径
     * @param progressCallback 进度回调：(bytesRead, totalBytes) -> Unit
     * @return 下载成功返回 true，失败返回 false
     */
    suspend fun downloadApkWithProgress(
        downloadUrl: String,
        destination: File,
        progressCallback: (bytesRead: Long, totalBytes: Long) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        // 如果文件已存在且有效，直接返回成功
        if (destination.exists() && destination.length() > 1_000_000) {
            Log.d(TAG, "文件已存在，跳过下载: ${destination.absolutePath} (${formatFileSize(destination.length())})")
            return@withContext DownloadResult(true, "文件已存在")
        }

        // 确保父目录存在
        destination.parentFile?.mkdirs()

        // 遍历所有镜像源，每个镜像最多重试 MAX_RETRIES 次
        for ((mirrorIndex, mirrorTransformer) in DOWNLOAD_MIRRORS.withIndex()) {
            for (retry in 0 until MAX_RETRIES) {
                val mirrorUrl = mirrorTransformer(downloadUrl)
                Log.d(TAG, "下载尝试 [镜像$mirrorIndex/重试$retry]: $mirrorUrl")

                try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        .followRedirects(true)
                        .build()

                    val request = Request.Builder()
                        .url(mirrorUrl)
                        .header("User-Agent", "MobileClaw")
                        .header("Accept", "application/octet-stream")
                        .build()

                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        Log.w(TAG, "下载失败: HTTP ${response.code} ($mirrorUrl)")
                        response.close()
                        if (retry < MAX_RETRIES - 1) {
                            delay(RETRY_DELAY_MS)
                            continue
                        }
                        break // 该镜像所有重试用完，切到下一个镜像
                    }

                    val body = response.body
                    if (body == null) {
                        Log.w(TAG, "响应体为空 ($mirrorUrl)")
                        response.close()
                        break
                    }

                    val totalBytes = body.contentLength()
                    var bytesRead = 0L

                    FileOutputStream(destination).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesReadThisChunk: Int

                            // 首次回调：告知开始下载
                            progressCallback(0, if (totalBytes > 0) totalBytes else -1L)

                            while (input.read(buffer).also { bytesReadThisChunk = it } != -1) {
                                output.write(buffer, 0, bytesReadThisChunk)
                                bytesRead += bytesReadThisChunk

                                // 每 64KB 回调一次进度，避免过于频繁
                                if (bytesRead % (64 * 1024) < BUFFER_SIZE) {
                                    progressCallback(bytesRead, totalBytes)
                                }
                            }
                        }
                    }

                    // 下载完成，回调 100%
                    progressCallback(bytesRead, totalBytes)

                    val fileSize = destination.length()
                    Log.d(TAG, "下载完成: ${destination.absolutePath} (${formatFileSize(fileSize)})")

                    if (fileSize == 0L) {
                        Log.e(TAG, "下载的文件大小为 0，视为失败")
                        destination.delete()
                        continue
                    }

                    return@withContext DownloadResult(true, "下载成功")
                } catch (e: Exception) {
                    Log.e(TAG, "下载异常 [镜像$mirrorIndex/重试$retry]: ${e.message}", e)
                    // 清理损坏的文件
                    if (destination.exists()) destination.delete()
                    if (retry < MAX_RETRIES - 1) {
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
        }

        // 所有镜像和重试都失败
        Log.e(TAG, "所有下载源均失败")
        DownloadResult(false, "所有下载源均不可达")
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
        val result = downloadApkWithProgress(downloadUrl, destination) { _, _ -> }
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