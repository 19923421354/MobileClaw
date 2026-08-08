package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

// =============================================================================
//  LocalInferenceProvider —— 本地推理提供者
// =============================================================================

/**
 * LocalInferenceProvider - 将本地模型推理包装为与 AIGateway 兼容的接口。
 *
 * 当用户切换推理后端为本地模型时，AIGateway 通过此 Provider 调用本地推理引擎，
 * 对外暴露与 OpenAI Chat Completions API 一致的接口。
 *
 * ## 工作流程
 * 1. 接收 [sendCommand] 调用（与 AIGateway.sendCommand 签名一致）
 * 2. 将 system prompt + user message 拼接为本地模型的输入
 * 3. 调用 [LocalInferenceEngine] 执行推理
 * 4. 将结果解析为 [ClawCommandResult] 格式
 *
 * @param modelManager 已初始化的 LocalModelManager 实例
 * @param actionTranslator 指令翻译器，用于将 AI 输出解析为 ClawAction
 */
class LocalInferenceProvider(
    private val modelManager: LocalModelManager,
    private val actionTranslator: ActionTranslator = ActionTranslator
) {

    companion object {
        private const val TAG = "LocalInferenceProvider"
    }

    /**
     * 当前是否可用（本地模型已加载且就绪）。
     */
    val isAvailable: Boolean
        get() = modelManager.isModelLoaded()

    /**
     * 发送指令到本地模型并解析返回结果。
     *
     * @param userInput 用户自然语言指令
     * @param phoneState 当前手机状态
     * @return 解析后的指令集合
     */
    suspend fun sendCommand(
        userInput: String,
        phoneState: PhoneState
    ): ClawCommandResult {
        try {
            if (!isAvailable) {
                Log.w(TAG, "本地模型未加载")
                return ClawCommandResult(
                    actions = emptyList(),
                    description = "本地模型未加载或正在下载中，请先下载并加载模型"
                )
            }

            // 构建提示词
            val prompt = buildPrompt(userInput, phoneState)

            Log.i(TAG, "sendCommand: 发送到本地模型, prompt=${prompt.take(300)}...")

            // 执行推理
            val result = withContext(Dispatchers.IO) {
                modelManager.generate(
                    GenerationRequest(
                        prompt = prompt,
                        maxTokens = 512,
                        temperature = 0.3f,
                        topP = 0.9f
                    )
                )
            }

            Log.i(TAG, "sendCommand: 本地模型返回: ${result.text.take(200)}...")

            // 解析为 ClawCommandResult
            return parseResult(result.text, userInput)

        } catch (e: Exception) {
            Log.e(TAG, "sendCommand: 本地推理失败", e)
            return ClawCommandResult(
                actions = emptyList(),
                description = "本地推理失败: ${e.message}"
            )
        }
    }

    /**
     * 流式发送指令（通过回调逐步输出）。
     */
    suspend fun sendCommandStreaming(
        userInput: String,
        phoneState: PhoneState,
        onToken: (String) -> Unit,
        onComplete: (ClawCommandResult) -> Unit
    ) {
        try {
            if (!isAvailable) {
                onComplete(ClawCommandResult(
                    actions = emptyList(),
                    description = "本地模型未加载"
                ))
                return
            }

            val prompt = buildPrompt(userInput, phoneState)
            val fullText = StringBuilder()

            modelManager.generateStream(
                GenerationRequest(
                    prompt = prompt,
                    maxTokens = 512,
                    temperature = 0.3f,
                    topP = 0.9f
                )
            ).collect { token ->
                fullText.append(token)
                onToken(token)
            }

            val result = parseResult(fullText.toString(), userInput)
            onComplete(result)

        } catch (e: Exception) {
            Log.e(TAG, "流式推理失败", e)
            onComplete(ClawCommandResult(
                actions = emptyList(),
                description = "推理失败: ${e.message}"
            ))
        }
    }

    /**
     * 发送简单聊天请求（非指令模式）。
     */
    suspend fun chat(prompt: String): String {
        try {
            if (!isAvailable) {
                return "（本地模型未加载，请先下载并加载模型）"
            }

            val result = withContext(Dispatchers.IO) {
                modelManager.generate(
                    GenerationRequest(
                        prompt = prompt,
                        maxTokens = 1024,
                        temperature = 0.7f,
                        topP = 0.9f
                    )
                )
            }

            return result.text.ifBlank { "（本地模型未返回有效内容）" }

        } catch (e: Exception) {
            Log.e(TAG, "chat 失败", e)
            return "（推理失败: ${e.message}）"
        }
    }

    // =========================================================================
    //  内部方法
    // =========================================================================

    /**
     * 构建本地模型的输入提示词。
     */
    private fun buildPrompt(userInput: String, phoneState: PhoneState): String {
        val systemPrompt = buildString {
            appendLine("你是一个运行在 Android 手机上的 AI 助手 MobileClaw。")
            appendLine("请根据用户的指令，返回 JSON 格式的结构化指令。")
            appendLine()
            appendLine("当前手机状态：")
            appendLine("- 前台应用: ${phoneState.currentAppPackage ?: "未知"}")
            appendLine("- 屏幕状态: ${if (phoneState.currentScreenText.isNotBlank()) "有可见内容" else "未知"}")
            appendLine()
            appendLine("可用指令类型：")
            appendLine("1. APP_OPEN: 打开应用")
            appendLine("2. SCREEN_CLICK: 屏幕点击")
            appendLine("3. SCREEN_INPUT: 文本输入")
            appendLine("4. SHELL_EXEC: 执行 Shell 命令")
            appendLine("5. WAIT: 等待")
            appendLine()
            appendLine("请以 JSON 格式返回，格式：")
            appendLine("{\"actions\": [{\"type\": \"指令类型\", \"params\": {...}}], \"description\": \"任务描述\"}")
            appendLine()
            appendLine("只输出 JSON，不要包含其他文字。")
        }

        return "$systemPrompt\n\n用户指令: $userInput"
    }

    /**
     * 解析本地模型的输出为 ClawCommandResult。
     */
    private fun parseResult(text: String, userInput: String): ClawCommandResult {
        // 尝试直接解析 JSON
        val trimmed = text.trim()
        val jsonStr = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            trimmed
        } else {
            // 尝试从文本中提取 JSON
            val jsonStart = trimmed.indexOf('{')
            val jsonEnd = trimmed.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                trimmed.substring(jsonStart, jsonEnd + 1)
            } else {
                null
            }
        }

        if (jsonStr != null) {
            try {
                val json = Json { ignoreUnknownKeys = true }
                val parsed = json.decodeFromString<JsonObject>(jsonStr)
                val actions = mutableListOf<ClawAction>()

                val actionsArray = parsed["actions"]?.jsonArray
                if (actionsArray != null) {
                    for (actionJson in actionsArray) {
                        val action = actionJson.jsonObject
                        val type = action["type"]?.jsonPrimitive?.contentOrNull ?: continue
                        val params = action["params"]?.jsonObject

                        val clawAction = when (type.uppercase()) {
                            "APP_OPEN" -> ClawAction(
                                actionName = ActionType.APP_OPEN.name,
                                params = params?.let { p ->
                                    buildJsonObject {
                                        p["appName"]?.jsonPrimitive?.contentOrNull?.let { put("appName", JsonPrimitive(it)) }
                                        p["packageName"]?.jsonPrimitive?.contentOrNull?.let { put("packageName", JsonPrimitive(it)) }
                                    }
                                } ?: JsonObject(emptyMap()),
                                description = "打开应用"
                            )
                            "SCREEN_CLICK" -> ClawAction(
                                actionName = ActionType.SCREEN_CLICK.name,
                                params = params?.let { p ->
                                    buildJsonObject {
                                        p["x"]?.jsonPrimitive?.let { x -> put("x", x) }
                                        p["y"]?.jsonPrimitive?.let { y -> put("y", y) }
                                        p["text"]?.jsonPrimitive?.contentOrNull?.let { put("text", JsonPrimitive(it)) }
                                    }
                                } ?: JsonObject(emptyMap()),
                                description = "屏幕点击"
                            )
                            "SCREEN_INPUT" -> ClawAction(
                                actionName = ActionType.SCREEN_INPUT.name,
                                params = params?.let { p ->
                                    buildJsonObject {
                                        p["text"]?.jsonPrimitive?.contentOrNull?.let { put("text", JsonPrimitive(it)) }
                                    }
                                } ?: JsonObject(emptyMap()),
                                description = "文本输入"
                            )
                            "SHELL_EXEC" -> ClawAction(
                                actionName = ActionType.SHELL_EXEC.name,
                                params = params?.let { p ->
                                    buildJsonObject {
                                        p["command"]?.jsonPrimitive?.contentOrNull?.let { put("command", JsonPrimitive(it)) }
                                    }
                                } ?: JsonObject(emptyMap()),
                                description = "执行Shell命令"
                            )
                            else -> null
                        }
                        if (clawAction != null) {
                            actions.add(clawAction)
                        }
                    }
                }

                val description = parsed["description"]?.jsonPrimitive?.contentOrNull
                    ?: "已处理: $userInput"

                return ClawCommandResult(
                    actions = actions,
                    description = description
                )
            } catch (e: Exception) {
                Log.w(TAG, "JSON 解析失败，使用文本回退: ${e.message}")
            }
        }

        // 回退：将文本作为描述返回
        return ClawCommandResult(
            actions = emptyList(),
            description = text.take(500)
        )
    }
}

// =============================================================================
//  AIGateway 扩展 —— 本地推理集成
// =============================================================================

/**
 * AIGateway 的本地推理扩展。
 *
 * 当用户配置了本地模型时，通过此扩展将 AIGateway 的请求路由到本地推理引擎。
 * 支持四种后端模式：
 * - CLOUD_ONLY: 仅使用云端 API（默认）
 * - LOCAL_ONLY: 仅使用本地模型
 * - HYBRID: 本地优先，回退到云端
 * - AUTO: 自动选择可用后端
 */
enum class InferenceMode {
    CLOUD_ONLY, LOCAL_ONLY, HYBRID, AUTO
}

/**
 * 为 AIGateway 添加本地推理支持。
 */
class AIGatewayLocalExtension(
    private val gateway: AIGateway,
    private val provider: LocalInferenceProvider
) {

    companion object {
        private const val TAG = "AIGatewayLocalExtension"
    }

    /** 当前推理模式。 */
    @Volatile
    var mode: InferenceMode = InferenceMode.AUTO

    /**
     * 发送指令，根据当前模式选择推理后端。
     */
    suspend fun sendCommand(
        userInput: String,
        phoneState: PhoneState,
        stream: Boolean = false
    ): ClawCommandResult {
        return when (mode) {
            InferenceMode.LOCAL_ONLY -> {
                Log.i(TAG, "LOCAL_ONLY 模式: 使用本地模型")
                provider.sendCommand(userInput, phoneState)
            }
            InferenceMode.CLOUD_ONLY -> {
                Log.i(TAG, "CLOUD_ONLY 模式: 使用云端 API")
                gateway.sendCommand(userInput, phoneState, stream)
            }
            InferenceMode.HYBRID -> {
                Log.i(TAG, "HYBRID 模式: 本地优先，云端回退")
                if (provider.isAvailable) {
                    val localResult = provider.sendCommand(userInput, phoneState)
                    if (localResult.actions.isNotEmpty() || localResult.description.isNotBlank()) {
                        return localResult
                    }
                }
                Log.i(TAG, "HYBRID 模式: 本地无结果，回退到云端")
                gateway.sendCommand(userInput, phoneState, stream)
            }
            InferenceMode.AUTO -> {
                if (provider.isAvailable) {
                    Log.i(TAG, "AUTO 模式: 使用本地模型")
                    provider.sendCommand(userInput, phoneState)
                } else {
                    Log.i(TAG, "AUTO 模式: 本地不可用，使用云端 API")
                    gateway.sendCommand(userInput, phoneState, stream)
                }
            }
        }
    }

    /**
     * 流式发送指令。
     */
    suspend fun sendCommandStreaming(
        userInput: String,
        phoneState: PhoneState,
        onToken: (String) -> Unit,
        onComplete: (ClawCommandResult) -> Unit
    ) {
        when (mode) {
            InferenceMode.LOCAL_ONLY -> {
                provider.sendCommandStreaming(userInput, phoneState, onToken, onComplete)
            }
            InferenceMode.CLOUD_ONLY -> {
                val result = gateway.sendCommand(userInput, phoneState, stream = true)
                onComplete(result)
            }
            InferenceMode.HYBRID -> {
                if (provider.isAvailable) {
                    provider.sendCommandStreaming(userInput, phoneState, onToken, onComplete)
                } else {
                    val result = gateway.sendCommand(userInput, phoneState, stream = true)
                    onComplete(result)
                }
            }
            InferenceMode.AUTO -> {
                if (provider.isAvailable) {
                    provider.sendCommandStreaming(userInput, phoneState, onToken, onComplete)
                } else {
                    val result = gateway.sendCommand(userInput, phoneState, stream = true)
                    onComplete(result)
                }
            }
        }
    }

    /**
     * 在多步编排循环中追加执行反馈并请求下一步指令。
     *
     * 将执行结果与新状态反馈给 AI，让其决定是否继续。
     * 本地推理时使用 sendCommand（本地模型不支持对话历史），
     * 云端推理时使用 gateway.continueCommand（保留对话上下文）。
     *
     * @param feedback 执行反馈文本
     * @param phoneState 最新的手机状态
     * @return AI 给出的下一批指令
     */
    suspend fun continueCommand(
        feedback: String,
        phoneState: PhoneState
    ): ClawCommandResult {
        return when (mode) {
            InferenceMode.LOCAL_ONLY -> {
                Log.i(TAG, "LOCAL_ONLY 模式 (continue): 使用本地模型")
                provider.sendCommand(feedback, phoneState)
            }
            InferenceMode.CLOUD_ONLY -> {
                Log.i(TAG, "CLOUD_ONLY 模式 (continue): 使用云端 API")
                gateway.continueCommand(feedback, phoneState)
            }
            InferenceMode.HYBRID -> {
                Log.i(TAG, "HYBRID 模式 (continue): 本地优先，云端回退")
                if (provider.isAvailable) {
                    val localResult = provider.sendCommand(feedback, phoneState)
                    if (localResult.actions.isNotEmpty() || localResult.description.isNotBlank()) {
                        return localResult
                    }
                }
                Log.i(TAG, "HYBRID 模式 (continue): 本地无结果，回退到云端")
                gateway.continueCommand(feedback, phoneState)
            }
            InferenceMode.AUTO -> {
                if (provider.isAvailable) {
                    Log.i(TAG, "AUTO 模式 (continue): 使用本地模型")
                    provider.sendCommand(feedback, phoneState)
                } else {
                    Log.i(TAG, "AUTO 模式 (continue): 本地不可用，使用云端 API")
                    gateway.continueCommand(feedback, phoneState)
                }
            }
        }
    }
}