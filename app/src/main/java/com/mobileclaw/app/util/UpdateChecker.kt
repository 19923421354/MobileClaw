package com.mobileclaw.app.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
 * 通过 GitHub Releases API 获取最新版本信息，支持版本对比、更新日志展示、
 * APK 下载与安装。使用 GitHub 作为更新源，仓库地址从常量统一配置。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    /** GitHub 仓库所有者 */
    private const val GITHUB_OWNER = "19923421354"

    /** GitHub 仓库名 */
    private const val GITHUB_REPO = "MobileClaw"

    /** GitHub Releases API 地址 */
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /** 下载超时（毫秒） */
    private const val DOWNLOAD_TIMEOUT_MS = 120_000L

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

    /**
     * 检查是否有新版本（带 Fallback 机制）。
     *
     * 优先通过 GitHub Releases API 获取最新版本信息。
     * 如果 API 调用失败（如网络问题、API 限流），
     * 自动回退到通过直接构造 URL 的方式下载 APK。
     *
     * @param currentVersion 当前版本号（如 "2.0.4"）
     * @return [UpdateInfo] 有新版本时返回；无更新或失败时返回 null
     */
    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}, trying fallback...")
                // API 失败时尝试直接构造下载链接
                return@withContext tryFallbackCheck(currentVersion)
            }

            val body = response.body?.string() ?: return@withContext tryFallbackCheck(currentVersion)
            val json = Json { ignoreUnknownKeys = true }
            val release = json.decodeFromString<GitHubRelease>(body)

            val tagVersion = release.tagName.removePrefix("v").removePrefix("V")
            if (compareVersions(tagVersion, currentVersion) <= 0) {
                Log.d(TAG, "Current version $currentVersion is up to date (latest: $tagVersion)")
                return@withContext null
            }

            // 找第一个 APK 附件
            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }

            // 构建直接下载链接：如果有 APK 附件用附件链接，否则构造标准下载链接
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
            Log.e(TAG, "checkForUpdate failed, trying fallback...", e)
            // 异常时尝试 fallback 检查
            tryFallbackCheck(currentVersion)
        }
    }

    /**
     * 回退检查：在不依赖 GitHub API 的情况下，
     * 直接尝试访问预期的最新版本 APK 下载链接。
     *
     * 此方法通过构造标准 URL 模式来工作，
     * 如果服务器返回 200 则说明该版本存在。
     * 注意：此方法无法获取更新日志，仅能判断版本是否存在。
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
                Log.d(TAG, "Fallback: found v$nextPatch via direct URL")
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
                Log.d(TAG, "Fallback: no newer version found (v$nextPatch not available)")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback check failed", e)
            null
        }
    }

    /**
     * 下载 APK 文件到本地。
     *
     * @param downloadUrl 下载地址
     * @param destination 保存路径
     * @return 下载成功返回 true
     */
    suspend fun downloadApk(downloadUrl: String, destination: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()

            val request = Request.Builder()
                .url(downloadUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                return@withContext false
            }

            response.body?.let { body ->
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            Log.d(TAG, "APK downloaded: ${destination.absolutePath} (${destination.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk failed", e)
            false
        }
    }

    /**
     * 触发 APK 安装（通过系统安装 Intent）。
     *
     * @param apkFile 已下载的 APK 文件
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
            Log.e(TAG, "installApk failed", e)
        }
    }
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