package com.mobileclaw.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mobileclaw.app.util.UpdateChecker
import java.io.File

/**
 * 通知栏点击安装广播接收器。
 *
 * 当用户点击「下载完成」通知时，触发 APK 安装。
 * 从 Intent 中读取 APK 文件路径，调用 [UpdateChecker.installApk] 执行安装。
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "InstallReceiver"
        const val EXTRA_APK_PATH = "apk_path"
        const val ACTION_INSTALL_APK = "com.mobileclaw.app.action.INSTALL_APK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_APK) return

        val apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: run {
            Log.e(TAG, "未找到 APK 路径，无法安装")
            return
        }

        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Log.e(TAG, "APK 文件不存在: $apkPath")
            return
        }

        Log.d(TAG, "通知栏触发安装: $apkPath")
        UpdateChecker.installApk(context, apkFile)
    }
}