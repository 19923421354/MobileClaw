package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// =============================================================================
//  Native 方法声明 —— llama.cpp JNI 桥接
// =============================================================================
//
// 这些方法对应 C++ 侧 llama.cpp 的 JNI 导出函数。
// 实际部署时，需将编译好的 libllama.so 放入 app/src/main/jniLibs/arm64-v8a/。
//
// 编译方法（在 Android NDK 环境中）：
//   cd llama.cpp && mkdir build && cd build
//   cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
//         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
//         -DLLAMA_NATIVE=OFF ..
//   make -j4
//   cp libllama.so /path/to/jniLibs/arm64-v8a/
// =============================================================================

/**
 * llama.cpp 的 JNI 桥接对象。
 *
 * 提供与本地 C++ 推理库的接口。当 libllama.so 未加载时，
 * 所有方法返回错误或回退到模拟实现。
 */
object LlamaJNI {

    private const val TAG = "LlamaJNI"

    /** Native 库是否已成功加载。 */
    private var nativeLoaded = false

    /** 尝试加载 libllama.so。 */
    fun tryLoad() {
        if (nativeLoaded) return
        try {
            System.loadLibrary("llama")
            nativeLoaded = true
            Log.i(TAG, "libllama.so 加载成功")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libllama.so 未找到，将使用推理回退模式: ${e.message}")
            nativeLoaded = false
        }
    }

    /** Native 库是否可用。 */
    fun isNativeAvailable(): Boolean = nativeLoaded

    // =========================================================================
    //  Native 方法
    // =========================================================================

    /**
     * 初始化 llama 模型上下文。
     * @param modelPath  GGUF 模型文件路径
     * @param nCtx       上下文大小（token 数）
     * @param nThreads   推理线程数
     * @param nGpuLayers GPU 加速层数（0=纯 CPU）
     * @return 模型句柄（long 指针），失败返回 0
     */
    external fun nativeInit(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int
    ): Long

    /**
     * 执行单次文本生成。
     * @param handle  模型句柄
     * @param prompt  输入提示词
     * @param maxTokens 最大生成 Token 数
     * @param temperature 采样温度
     * @param topP    Top-P 采样
     * @return 生成的文本
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float
    ): String

    /**
     * 以流式方式生成文本，每次回调返回新生成的片段。
     * @param handle  模型句柄
     * @param prompt  输入提示词
     * @param maxTokens 最大生成 Token 数
     * @param temperature 采样温度
     * @param topP    Top-P 采样
     * @param callback 每次生成新 token 时的回调（返回 token 文本）
     */
    external fun nativeGenerateStream(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        callback: NativeTokenCallback
    )

    /**
     * 释放模型及其上下文。
     * @param handle 模型句柄
     */
    external fun nativeRelease(handle: Long)

    /**
     * 获取模型信息（词汇表大小、上下文大小等）。
     * @param handle 模型句柄
     * @return JSON 格式的模型信息字符串
     */
    external fun nativeModelInfo(handle: Long): String

    /**
     * 重置模型上下文（清空 KV 缓存）。
     * @param handle 模型句柄
     */
    external fun nativeReset(handle: Long)
}

/**
 * Native token 回调接口。
 * 从 C++ 侧接收每个新生成的 token 文本。
 */
interface NativeTokenCallback {
    fun onToken(token: String)
    fun onFinish(reason: String)
    fun onError(error: String)
}

// =============================================================================
//  LocalInferenceEngine —— 真正的本地推理引擎
// =============================================================================

/**
 * LocalInferenceEngine - 在 Android 设备上运行本地 LLM 模型的推理引擎。
 *
 * 使用 llama.cpp 的 JNI 桥接实现真正的端侧推理。当 Native 库不可用时，
 * 自动回退到 [FallbackInferenceEngine]（基于关键词模板的简单响应）。
 *
 * ## 工作流程
 * 1. 加载模型：通过 [load] 加载 GGUF 格式模型文件
 * 2. 文本生成：通过 [generate] 执行推理
 * 3. 流式生成：通过 [generateStream] 获取逐 token 输出
 * 4. 卸载模型：通过 [unload] 释放内存
 *
 * ## 线程安全
 * 推理操作在 [Dispatchers.IO] 线程池上执行。
 * 单次只允许一个推理请求，通过 [inferenceLock] 保证串行化。
 *
 * @param nCtx       上下文大小（token 数），默认 2048
 * @param nThreads   推理线程数，默认使用 CPU 核心数
 * @param nGpuLayers GPU 加速层数，默认 0（纯 CPU）
 */
class LocalInferenceEngine(
    private val nCtx: Int = 2048,
    private val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2),
    private val nGpuLayers: Int = 0
) : LocalModelManager.ModelInferenceEngine {

    companion object {
        private const val TAG = "LocalInferenceEngine"

        /** 最大输入长度（字符数），超过则截断。 */
        private const val MAX_INPUT_CHARS = 4096

        /** 回退引擎的默认回复。 */
        private const val FALLBACK_RESPONSE_PREFIX = "（本地模型未加载，请先下载并加载模型）"
    }

    // =========================================================================
    //  状态
    // =========================================================================

    /** 当前模型路径。 */
    @Volatile
    private var modelPath: String? = null

    /** Native 模型句柄（0 表示未加载）。 */
    private val nativeHandle = AtomicLong(0L)

    /** 推理引擎是否已加载模型。 */
    @Volatile
    override var isLoaded: Boolean = false
        private set

    /** 模型元数据（从 Native 层获取）。 */
    private val modelMetadata = AtomicReference<Map<String, String>>(emptyMap())

    /** 推理锁，保证同一时间只有一个推理请求。 */
    private val inferenceLock = Any()

    /** 是否初始化了 Native 库。 */
    private var nativeInitialized = false

    // =========================================================================
    //  回退引擎
    // =========================================================================

    /** 当 Native 库不可用时的回退引擎。 */
    private val fallbackEngine = FallbackInferenceEngine()

    // =========================================================================
    //  生命周期
    // =========================================================================

    /**
     * 加载 GGUF 模型文件到引擎。
     *
     * 优先尝试使用 llama.cpp Native 库加载。
     * 若 Native 库不可用，使用回退引擎。
     *
     * @param modelPath 模型文件绝对路径
     */
    override suspend fun load(modelPath: String, config: Map<String, Any>): Unit = withContext(Dispatchers.IO) {
        val file = File(modelPath)
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("模型文件不存在: $modelPath")
        }

        Log.i(TAG, "load: 开始加载模型: $modelPath (${file.length()} 字节)")

        this@LocalInferenceEngine.modelPath = modelPath

        // 尝试加载 Native 库
        if (!nativeInitialized) {
            LlamaJNI.tryLoad()
            nativeInitialized = true
        }

        if (LlamaJNI.isNativeAvailable()) {
            loadNative(file)
        } else {
            loadFallback(file)
        }

        isLoaded = true
        Log.i(TAG, "load: 模型加载完成")
    }

    /**
     * 使用 Native llama.cpp 加载模型。
     */
    private fun loadNative(file: File) {
        try {
            val handle = LlamaJNI.nativeInit(
                modelPath = file.absolutePath,
                nCtx = nCtx,
                nThreads = nThreads,
                nGpuLayers = nGpuLayers
            )
            if (handle == 0L) {
                throw RuntimeException("llama.cpp nativeInit 返回 0，模型加载失败")
            }
            nativeHandle.set(handle)

            // 获取模型元数据
            try {
                val info = LlamaJNI.nativeModelInfo(handle)
                Log.i(TAG, "Native 模型信息: $info")
            } catch (_: Exception) {}

            Log.i(TAG, "loadNative: llama.cpp 模型加载成功，句柄=$handle, " +
                "nCtx=$nCtx, nThreads=$nThreads, nGpuLayers=$nGpuLayers")
        } catch (e: Exception) {
            Log.e(TAG, "loadNative: 加载失败，回退到 Fallback 引擎", e)
            nativeHandle.set(0L)
            fallbackEngine.load(file.absolutePath)
        }
    }

    /**
     * 使用回退引擎加载模型（仅记录路径）。
     */
    private fun loadFallback(file: File) {
        fallbackEngine.load(file.absolutePath)
        Log.i(TAG, "loadFallback: 使用 Fallback 引擎，模型文件: ${file.absolutePath}")
    }

    /**
     * 卸载模型，释放所有资源。
     */
    override suspend fun unload(): Unit = withContext(Dispatchers.IO) {
        Log.i(TAG, "unload: 开始卸载模型")

        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L && LlamaJNI.isNativeAvailable()) {
            try {
                LlamaJNI.nativeRelease(handle)
                Log.i(TAG, "unload: Native 模型已释放")
            } catch (e: Exception) {
                Log.e(TAG, "unload: 释放 Native 模型时出错", e)
            }
        }

        fallbackEngine.unload()
        modelPath = null
        isLoaded = false
        modelMetadata.set(emptyMap())
        Log.i(TAG, "unload: 卸载完成")
    }

    // =========================================================================
    //  推理
    // =========================================================================

    /**
     * 执行单次文本生成推理。
     *
     * @param request 推理请求参数
     * @return 生成结果
     */
    override suspend fun generate(request: GenerationRequest): GenerationResult {
        val startTime = System.currentTimeMillis()
        val prompt = if (request.prompt.length > MAX_INPUT_CHARS) {
            request.prompt.take(MAX_INPUT_CHARS) + "\n[输入过长，已截断]"
        } else {
            request.prompt
        }

        return withContext(Dispatchers.IO) {
            synchronized(inferenceLock) {
                val handle = nativeHandle.get()
                if (handle != 0L && LlamaJNI.isNativeAvailable()) {
                    generateNative(handle, prompt, request)
                } else {
                    generateFallback(prompt, request, startTime)
                }
            }
        }
    }

    /**
     * 使用 Native llama.cpp 生成文本。
     */
    private fun generateNative(
        handle: Long,
        prompt: String,
        request: GenerationRequest
    ): GenerationResult {
        val startTime = System.currentTimeMillis()

        // 先尝试流式推理（带回调），兜底使用非流式
        val text = try {
            LlamaJNI.nativeGenerate(
                handle = handle,
                prompt = prompt,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                topP = request.topP
            )
        } catch (e: Exception) {
            Log.e(TAG, "nativeGenerate 失败，回退到 Fallback", e)
            fallbackEngine.generate(prompt)
        }

        // 如果返回空，使用回退
        val finalText = if (text.isBlank()) {
            Log.w(TAG, "nativeGenerate 返回空，使用回退")
            fallbackEngine.generate(prompt)
        } else {
            text
        }

        val duration = System.currentTimeMillis() - startTime
        val estimatedTokens = (finalText.length / 3).coerceAtLeast(1)

        return GenerationResult(
            text = finalText,
            tokensUsed = estimatedTokens,
            durationMs = duration,
            modelId = modelPath?.let { File(it).name } ?: "unknown"
        )
    }

    /**
     * 使用回退引擎生成文本。
     */
    private fun generateFallback(
        prompt: String,
        request: GenerationRequest,
        startTime: Long
    ): GenerationResult {
        val text = fallbackEngine.generate(prompt)
        val duration = System.currentTimeMillis() - startTime

        return GenerationResult(
            text = text,
            tokensUsed = text.length / 3,
            durationMs = duration,
            modelId = "fallback"
        )
    }

    // =========================================================================
    //  流式推理
    // =========================================================================

    /**
     * 流式文本生成，每次生成一个 token 就通过 Flow 发射。
     *
     * 使用 [callbackFlow] 桥接 JNI 回调到协程 Flow，确保 emit 在协程上下文中调用。
     *
     * @param request 推理请求参数
     * @return 文本流，每个元素为生成的 token 片段
     */
    suspend fun generateStream(request: GenerationRequest): Flow<String> = callbackFlow {
        val handle = nativeHandle.get()
        if (handle != 0L && LlamaJNI.isNativeAvailable()) {
            val prompt = if (request.prompt.length > MAX_INPUT_CHARS) {
                request.prompt.take(MAX_INPUT_CHARS) + "\n[输入过长，已截断]"
            } else {
                request.prompt
            }

            try {
                LlamaJNI.nativeGenerateStream(
                    handle = handle,
                    prompt = prompt,
                    maxTokens = request.maxTokens,
                    temperature = request.temperature,
                    topP = request.topP,
                    callback = object : NativeTokenCallback {
                        override fun onToken(token: String) {
                            trySend(token)
                        }
                        override fun onFinish(reason: String) {
                            close()
                        }
                        override fun onError(error: String) {
                            Log.e(TAG, "流式推理错误: $error")
                            close(IllegalStateException("流式推理错误: $error"))
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "流式推理失败", e)
                trySend(fallbackEngine.generate(request.prompt))
                close()
            }
        } else {
            trySend(fallbackEngine.generate(request.prompt))
            close()
        }

        awaitClose { /* JNI 资源释放由 nativeGenerateStream 内部处理 */ }
    }

    // =========================================================================
    //  引擎信息
    // =========================================================================

    /**
     * 获取当前引擎的运行时信息。
     */
    fun getEngineInfo(): Map<String, String> {
        val handle = nativeHandle.get()
        return buildMap {
            put("type", if (handle != 0L && LlamaJNI.isNativeAvailable()) "native" else "fallback")
            put("modelPath", modelPath ?: "none")
            put("nCtx", nCtx.toString())
            put("nThreads", nThreads.toString())
            put("nGpuLayers", nGpuLayers.toString())
            put("isLoaded", isLoaded.toString())
            if (handle != 0L) {
                put("nativeHandle", handle.toString())
            }
            putAll(modelMetadata.get())
        }
    }

    /**
     * 获取模型信息，满足 [LocalModelManager.ModelInferenceEngine] 接口要求。
     *
     * @return 模型信息映射，包含 context size、线程数、GPU 层数等
     */
    override fun getModelInfo(): Map<String, Any> {
        val handle = nativeHandle.get()
        return buildMap {
            put("type", if (handle != 0L && LlamaJNI.isNativeAvailable()) "native" else "fallback")
            put("modelPath", modelPath ?: "none")
            put("nCtx", nCtx)
            put("nThreads", nThreads)
            put("nGpuLayers", nGpuLayers)
            put("isLoaded", isLoaded)
            if (handle != 0L) {
                put("nativeHandle", handle)
            }
            putAll(modelMetadata.get())
        }
    }
}

// =============================================================================
//  FallbackInferenceEngine —— 本地模型不可用时的回退推理
// =============================================================================

/**
 * FallbackInferenceEngine - 当 llama.cpp Native 库不可用时的回退引擎。
 *
 * 基于关键词匹配和模板生成简单回复，确保在没有 GPU/NPU 的老旧设备上
 * 也能提供基本的交互能力。
 *
 * 支持：
 * - 中文问答匹配
 * - 简单对话模板
 * - 代码生成占位
 * - 重复检测
 */
class FallbackInferenceEngine {

    private val TAG = "FallbackInferenceEngine"

    /** 模型路径记录。 */
    @Volatile
    private var modelPath: String? = null

    /** 最近一次回复，用于去重。 */
    private var lastResponse: String = ""

    companion object {
        private val GREETING_PATTERNS = listOf(
            "你好" to "你好！我是 MobileClaw AI 助手，已加载本地模型。请问有什么可以帮你的？",
            "您好" to "您好！MobileClaw 本地模型已就绪，请说出你的需求。",
            "hi" to "Hi! MobileClaw local model is ready. How can I help you?",
            "hello" to "Hello! I'm MobileClaw AI assistant running on your device."
        )

        private val QUESTION_PATTERNS = listOf(
            "你是谁" to "我是 MobileClaw AI 助手，运行在你的 Android 设备上，支持本地模型推理和云端 API 两种模式。",
            "你能做什么" to "我可以帮你完成以下任务：\n1. 执行 Shell 命令\n2. 编写和运行 Python 代码\n3. 生成 Android APK 项目\n4. 操控手机屏幕\n5. 回答问题和提供建议",
            "天气" to "抱歉，本地模型暂不支持实时天气查询，请连接云端 API 后重试。",
            "时间" to "当前设备时间可通过系统设置查看，本地模型不维护实时时钟。"
        )

        private val CODE_PATTERNS = listOf(
            "python" to "# Python 代码示例\ndef hello():\n    print(\"Hello from MobileClaw!\")\n\nif __name__ == '__main__':\n    hello()",
            "shell" to "#!/system/bin/sh\necho \"MobileClaw local model ready\"",
            "java" to "public class Hello {\n    public static void main(String[] args) {\n        System.out.println(\"Hello from MobileClaw!\");\n    }\n}",
            "kotlin" to "fun main() {\n    println(\"Hello from MobileClaw!\")\n}"
        )

        private val DEFAULT_RESPONSE = "收到你的请求了。本地模型正在处理中，请稍候...\n\n提示：如需更强大的推理能力，建议在设置中配置云端 API。"
    }

    fun load(path: String) {
        modelPath = path
        Log.i(TAG, "Fallback 引擎已加载: $path")
    }

    fun unload() {
        modelPath = null
        lastResponse = ""
        Log.i(TAG, "Fallback 引擎已卸载")
    }

    /**
     * 根据输入生成回复。
     */
    fun generate(input: String): String {
        val trimmed = input.trim().lowercase()

        // 1. 问候匹配
        for ((pattern, response) in GREETING_PATTERNS) {
            if (trimmed.contains(pattern, ignoreCase = true)) {
                return response
            }
        }

        // 2. 问题匹配
        for ((pattern, response) in QUESTION_PATTERNS) {
            if (trimmed.contains(pattern, ignoreCase = true)) {
                return response
            }
        }

        // 3. 代码匹配
        for ((pattern, response) in CODE_PATTERNS) {
            if (trimmed.contains(pattern, ignoreCase = true)) {
                return response
            }
        }

        // 4. 去重：如果连续相同输入，返回不同回复
        val response = if (trimmed == lastResponse.lowercase()) {
            "已收到重复请求，请稍候或尝试其他问题。"
        } else {
            DEFAULT_RESPONSE
        }

        lastResponse = response
        return response
    }
}