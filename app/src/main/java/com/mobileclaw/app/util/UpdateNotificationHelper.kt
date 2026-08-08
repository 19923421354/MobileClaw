package com.mobileclaw.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object UpdateNotificationHelper {
    private const val CHANNEL_ID = "update_download"
    private const val NOTIFICATION_ID = 1002
    private const val TAG = "UpdateNotification"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "更新下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示APK更新下载进度和实时网速"
                setShowBadge(false)
            }
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 显示下载进度（支持 KB/MB 进度 + 实时网速）。
     *
     * @param bytesRead 已下载字节数
     * @param totalBytes 总字节数（-1 表示未知）
     * @param fileName 文件名
     * @param speedBps 当前下载速度（字节/秒），0 或负值不显示
     */
    fun showDownloadProgress(
        context: Context,
        bytesRead: Long,
        totalBytes: Long,
        fileName: String,
        speedBps: Long = 0L
    ) {
        createChannel(context)

        val progressText = buildHumanReadableProgress(bytesRead, totalBytes)
        val speedText = if (speedBps > 0) {
            "\n网速: ${formatSpeed(speedBps)}"
        } else {
            ""
        }
        val percentage = if (totalBytes > 0) {
            (bytesRead * 100 / totalBytes).toInt()
        } else {
            0
        }
        val max = if (totalBytes > 0) totalBytes.toInt() else 0
        val progress = if (totalBytes > 0) bytesRead.toInt() else 0

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载更新")
            .setContentText("$fileName — $progressText")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$fileName\n$progressText$speedText"))
            .setProgress(max, progress, totalBytes <= 0)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * 构建人类可读的下载进度文本。
     * 例如：「3.2 MB / 15.8 MB (20%)」或「3.2 MB / 未知大小」
     */
    private fun buildHumanReadableProgress(bytesRead: Long, totalBytes: Long): String {
        val readStr = formatBytes(bytesRead)
        val totalStr = if (totalBytes > 0) {
            formatBytes(totalBytes)
        } else {
            "未知大小"
        }
        val percentage = if (totalBytes > 0) {
            " (${bytesRead * 100 / totalBytes}%)"
        } else {
            ""
        }
        return "$readStr / $totalStr$percentage"
    }

    /**
     * 将字节数格式化为人类可读的大小。
     */
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }

    /**
     * 格式化下载速度（自动选择 B/s / KB/s / MB/s）。
     */
    private fun formatSpeed(speedBps: Long): String = when {
        speedBps < 0 -> "未知"
        speedBps < 1024 -> "${speedBps} B/s"
        speedBps < 1024 * 1024 -> "${speedBps / 1024} KB/s"
        else -> "${"%.1f".format(speedBps.toDouble() / (1024 * 1024))} MB/s"
    }

    fun showDownloadComplete(context: Context, fileName: String) {
        createChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText("$fileName 已下载完成")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun showDownloadFailed(context: Context, errorMsg: String) {
        createChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText(errorMsg)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}