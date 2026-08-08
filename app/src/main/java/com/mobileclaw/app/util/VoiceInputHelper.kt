package com.mobileclaw.app.util

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast

/**
 * 语音输入助手。
 *
 * 封装 Android SpeechRecognizer API，提供简洁的语音转文字功能：
 * - 支持中文和英文识别
 * - 自动处理权限检查
 * - 提供回调接口通知识别结果
 * - 支持连续识别模式
 *
 * 使用方式：
 * ```
 * val voiceHelper = VoiceInputHelper(activity)
 * voiceHelper.startListening { text ->
 *     // 使用识别到的文本
 * }
 * ```
 *
 * 需要 RECORD_AUDIO 权限。
 */
class VoiceInputHelper(private val activity: Activity) {

    companion object {
        private const val TAG = "VoiceInputHelper"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    /** 语音识别是否可用。 */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    /**
     * 开始语音识别。
     *
     * @param onResult 识别成功回调，返回识别到的文本
     * @param onError 识别失败回调，返回错误信息
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (isListening) {
            Log.w(TAG, "已在监听中，忽略重复调用")
            return
        }

        if (!isAvailable()) {
            onError("设备不支持语音识别")
            return
        }

        onResultCallback = onResult
        onErrorCallback = onError

        // 检查权限
        if (!PermissionManager.hasMicrophonePermission(activity)) {
            PermissionManager.requestPermission(activity, PermissionManager.PermissionType.MICROPHONE)
            onError("缺少录音权限，正在请求...")
            return
        }

        // 创建或复用 SpeechRecognizer
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            speechRecognizer?.setRecognitionListener(voiceListener)
        }

        // 构建识别意图
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN") // 中文识别
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1) // 只返回最佳结果
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) // 启用部分结果
        }

        isListening = true
        speechRecognizer?.startListening(intent)
        Log.d(TAG, "开始语音识别")
    }

    /** 停止语音识别。 */
    fun stopListening() {
        if (!isListening) return
        speechRecognizer?.stopListening()
        isListening = false
        Log.d(TAG, "停止语音识别")
    }

    /** 释放资源。 */
    fun destroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        onResultCallback = null
        onErrorCallback = null
    }

    /** 语音识别监听器。 */
    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "准备就绪，请说话...")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "开始说话")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // 音量变化，可用于 UI 动画
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // 录音缓冲
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "说话结束")
        }

        override fun onError(error: Int) {
            isListening = false
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音内容"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入超时"
                else -> "未知错误($error)"
            }
            Log.w(TAG, "语音识别错误: $errorMsg")
            onErrorCallback?.invoke(errorMsg)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0] // 取最佳结果
                Log.d(TAG, "识别结果: $text")
                onResultCallback?.invoke(text)
            } else {
                onErrorCallback?.invoke("未识别到内容")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // 部分结果，可用于实时显示
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrEmpty()) {
                Log.d(TAG, "部分结果: $partial")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // 扩展事件
        }
    }
}
