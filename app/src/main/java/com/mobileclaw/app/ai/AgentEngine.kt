package com.mobileclaw.app.ai

import android.content.Context
import android.util.Log
import com.mobileclaw.app.shizuku.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// =============================================================================
// AgentEngine - AI Agent with ReAct loop and tool calling
// =============================================================================

/**
 * AgentEngine - AI Agent 引擎，实现 ReAct（Reasoning + Acting）循环。
 *
 * 通过 LLM（经由 AIGateway 的配置）进行任务推理，生成工具调用，
 * 执行工具并将结果反馈给 LLM，循环直到任务完成或达到最大迭代次数。
 *
 * 工作流程：
 * 1. 构建包含工具定义的系统提示词
 * 2. 将用户任务发送给 LLM（带 function calling 参数）
 * 3. 解析 LLM 响应，提取工具调用
 * 4. 通过 ToolRegistry 执行工具
 * 5. 将工具执行结果反馈给 LLM
 * 6. 重复直到 LLM 返回非工具调用的最终回答
 * 7. 汇总执行步骤，返回 AgentResult
 *
 * @param context       Android 应用上下文
 * @param gateway       AIGateway 实例，用于获取 API 配置（baseUrl / apiKey / model）
 * @param toolRegistry  工具注册表，管理所有 AI 可调用的工具
 * @param termuxBridge  Termux 桥接器，提供底层 Shell 命令执行能力
 * @param scope         协程作用域，默认使用 SupervisorJob + Dispatchers.Default
 */
class AgentEngine(
    private val context: Context,
    private val gateway: AIGateway,
    private val toolRegistry: ToolRegistry,
    private val termuxBridge: TermuxBridge,
    private val localProvider: LocalInferenceProvider? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val TAG = "AgentEngine"

        /** ReAct 循环默认最大迭代次数。 */
        private const val DEFAULT_MAX_ITERATIONS = 20

        /** 单次 API 请求超时（毫秒）。 */
        private const val API_TIMEOUT_MS = 120_000L

        /** 工具执行结果长度限制（字符数），超出截断。 */
        private const val TOOL_RESULT_MAX_LENGTH = 4000
    }

    // =========================================================================
    // AgentMode - 代理模式
    // =========================================================================

    /**
     * Agent 运行模式。
     *
     * - AUTO:  自动模式，Agent 自主决策执行所有工具调用，无需用户确认
     * - ASK:   询问模式，每次工具调用前向用户请求确认
     * - MANUAL: 手动模式，Agent 仅给出建议，由用户手动执行
     */
    enum class AgentMode { AUTO, ASK, MANUAL }

    /** 当前 Agent 运行模式，默认为 AUTO。 */
    @Volatile
    var mode: AgentMode = AgentMode.AUTO

    // =========================================================================
    // AgentState - 代理状态
    // =========================================================================

    /**
     * Agent 运行状态快照。
     *
     * @property running        Agent 是否正在执行
     * @property currentStep    当前步骤描述
     * @property stepsCompleted 已完成步骤数
     * @property totalSteps     总步骤数（预估）
     * @property lastError      最近一次错误信息
     */
    data class AgentState(
        val running: Boolean = false,
        val currentStep: String = "",
        val stepsCompleted: Int = 0,
        val totalSteps: Int = 0,
        val lastError: String? = null
    )

    private val _stateFlow = MutableStateFlow(AgentState())
    val stateFlow: StateFlow<AgentState> = _stateFlow.asStateFlow()

    /** 取消标记，用于中止正在执行的 ReAct 循环。 */
    private val cancelled = AtomicBoolean(false)

    // =========================================================================
    // 内部状态
    // =========================================================================

    /** 当前执行的任务描述。 */
    @Volatile
    private var currentTask: String = ""

    /** 执行的步骤记录列表。 */
    private val steps = mutableListOf<AgentStep>()

    /** 创建的文件列表。 */
    private val createdFiles = mutableListOf<String>()

    /** 共享的 OkHttp 客户端。 */
    private val client: OkHttpClient = AIGateway.defaultClient()

    /** JSON 编解码器。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = true
    }

    // =========================================================================
    // 核心方法：execute
    // =========================================================================

    /**
     * 执行一个 AI Agent 任务。
     *
     * 启动 ReAct 循环，将用户任务交给 LLM 处理，LLM 通过思考-工具调用-观察
     * 循环逐步完成任务。任务完成后返回执行结果摘要。
     *
     * 调用方通过 [callback] 接收最终结果。
     * 可通过 [stateFlow] 观察执行过程中的状态变化。
     *
     * @param task     用户的任务描述
     * @param callback 执行完成后的回调，接收 [AgentResult]
     */
    suspend fun execute(task: String, callback: suspend (AgentResult) -> Unit) {
        // 重置状态
        cancelled.set(false)
        steps.clear()
        createdFiles.clear()
        currentTask = task

        updateState {
            copy(
                running = true,
                currentStep = "初始化任务...",
                stepsCompleted = 0,
                totalSteps = 0,
                lastError = null
            )
        }

        // 检查推理后端可用性
        val cloudAvailable = gateway.isConfigured()
        val localAvailable = localProvider?.isAvailable == true

        if (!cloudAvailable && !localAvailable) {
            Log.w(TAG, "execute: 无可用推理后端（云端未配置，本地模型未加载）")
            val errorResult = AgentResult(
                success = false,
                summary = "无法执行任务：AI 模型未配置。请先在设置中配置 API 密钥，或下载并加载本地模型。",
                steps = emptyList(),
                error = "No inference backend available"
            )
            updateState { copy(running = false, currentStep = "无可用模型", lastError = "云端和本地模型均不可用") }
            callback(errorResult)
            return
        }

        // 如果云端不可用但本地模型可用，使用本地推理
        val useLocalOnly = !cloudAvailable && localAvailable

        Log.i(TAG, "=== Agent ${if (useLocalOnly) "本地推理" else "ReAct"} 循环开始 ===")
        Log.i(TAG, "Task: $task")

        try {
            val result = if (useLocalOnly) {
                runLocalTask(task)
            } else {
                runReActLoop(task)
            }
            Log.i(TAG, "=== Agent ReAct 循环结束 ===")
            Log.i(TAG, "Success: ${result.success}, Steps: ${result.steps.size}")

            updateState {
                copy(
                    running = false,
                    currentStep = if (result.success) "任务完成" else "任务失败",
                    stepsCompleted = result.steps.size,
                    lastError = result.error
                )
            }

            callback(result)
        } catch (e: CancellationException) {
            Log.w(TAG, "Agent execution cancelled", e)
            val errorResult = AgentResult(
                success = false,
                summary = "任务已被取消",
                steps = steps.toList(),
                error = "Cancelled: ${e.message}"
            )
            updateState {
                copy(
                    running = false,
                    currentStep = "已取消",
                    lastError = "任务被取消"
                )
            }
            callback(errorResult)
        } catch (e: Exception) {
            Log.e(TAG, "Agent execution failed", e)
            val errorResult = AgentResult(
                success = false,
                summary = "任务执行异常",
                steps = steps.toList(),
                error = "Fatal error: ${e.message ?: "Unknown error"}"
            )
            updateState {
                copy(
                    running = false,
                    currentStep = "异常终止",
                    lastError = e.message
                )
            }
            callback(errorResult)
        }
    }

    /**
     * 取消当前正在执行的 Agent 任务。
     *
     * 设置取消标记，ReAct 循环会在下一次工具调用前检查并退出。
     */
    fun cancel() {
        Log.i(TAG, "cancel() called")
        cancelled.set(true)
    }

    // =========================================================================
    // ReAct 循环
    // =========================================================================

    /**
     * 运行 ReAct（Reasoning + Acting）主循环。
     *
     * 循环流程：
     * 1. 构建消息列表（系统提示词 + 对话历史）
     * 2. 调用 LLM（带工具定义）
     * 3. 解析响应：
     *    a. 如果 LLM 返回 tool_calls -> 执行工具，将结果作为 tool 消息加入历史，继续循环
     *    b. 如果 LLM 返回普通文本内容（final answer）-> 退出循环
     * 4. 达到最大迭代次数 -> 强制退出
     * 5. 汇总执行步骤，构造 AgentResult
     *
     * @param task 用户任务描述
     * @return Agent 执行结果
     */
    private suspend fun runReActLoop(task: String): AgentResult {
        val systemPrompt = buildSystemPrompt()
        val messages = mutableListOf<AgentChatMessage>()
        messages.add(AgentChatMessage(role = "system", content = systemPrompt))
        messages.add(AgentChatMessage(role = "user", content = task))

        val functions = toolRegistry.getOpenAIFunctions()
        var iteration = 0
        val maxIterations = DEFAULT_MAX_ITERATIONS
        var finalAnswer = ""

        updateState { copy(currentStep = "开始推理...", totalSteps = maxIterations) }

        while (iteration < maxIterations && !cancelled.get()) {
            iteration++
            val stepNumber = iteration

            Log.d(TAG, "ReAct iteration $iteration/$maxIterations")

            updateState {
                copy(
                    currentStep = "推理步骤 $iteration/$maxIterations...",
                    stepsCompleted = stepNumber - 1
                )
            }

            // 1. 调用 LLM
            val llmResponse = try {
                callLLM(messages, functions)
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed at iteration $iteration", e)
                // 记录错误步骤
                steps.add(
                    AgentStep(
                        stepNumber = stepNumber,
                        thought = "LLM 调用失败",
                        error = "API call failed: ${e.message ?: "Unknown error"}"
                    )
                )
                return AgentResult(
                    success = false,
                    summary = "AI 模型调用失败，已执行 ${steps.size} 步",
                    steps = steps.toList(),
                    filesCreated = createdFiles.toList(),
                    error = "LLM API error at iteration $iteration: ${e.message}"
                )
            }

            // 2. 解析 LLM 响应
            val assistantMessage = llmResponse["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject

            if (assistantMessage == null) {
                Log.e(TAG, "Invalid LLM response structure: ${llmResponse.toString().take(500)}")
                steps.add(
                    AgentStep(
                        stepNumber = stepNumber,
                        error = "Invalid LLM response format"
                    )
                )
                return AgentResult(
                    success = false,
                    summary = "AI 返回了无效的响应格式",
                    steps = steps.toList(),
                    filesCreated = createdFiles.toList(),
                    error = "Invalid response from LLM"
                )
            }

            val content = assistantMessage["content"]?.jsonPrimitive?.contentOrNull
            val toolCalls = assistantMessage["tool_calls"]?.jsonArray

            // 记录 AI 的思考过程
            val thought = if (!content.isNullOrBlank()) content else null

            // 3. 判断是否包含工具调用
            if (toolCalls != null && toolCalls.isNotEmpty()) {
                // 处理工具调用
                for (toolCall in toolCalls) {
                    if (cancelled.get()) break

                    val toolCallObj = toolCall.jsonObject
                    val toolCallId = toolCallObj["id"]?.jsonPrimitive?.contentOrNull
                        ?: "call_${stepNumber}_${System.currentTimeMillis()}"
                    val functionObj = toolCallObj["function"]?.jsonObject
                    val functionName = functionObj?.get("name")?.jsonPrimitive?.contentOrNull
                    val rawArguments = functionObj?.get("arguments")?.jsonPrimitive?.contentOrNull

                    if (functionName == null || rawArguments == null) {
                        Log.w(TAG, "Incomplete tool call at step $stepNumber")
                        steps.add(
                            AgentStep(
                                stepNumber = stepNumber,
                                thought = thought,
                                toolName = functionName,
                                toolArgs = rawArguments,
                                error = "Incomplete tool call: missing name or arguments"
                            )
                        )
                        continue
                    }

                    Log.i(TAG, "Tool call: $functionName($rawArguments)")

                    // 解析参数
                    val argsJson = try {
                        json.parseToJsonElement(rawArguments).jsonObject
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse tool arguments: $rawArguments", e)
                        val errorResult = "参数解析失败: ${e.message}"
                        // 将错误结果返回给 LLM
                        messages.add(
                            AgentChatMessage(
                                role = "assistant",
                                content = content,
                                toolCalls = listOf(
                                    AgentToolCallData(
                                        id = toolCallId,
                                        type = "function",
                                        function = AgentToolCallFunction(
                                            name = functionName,
                                            arguments = rawArguments
                                        )
                                    )
                                )
                            )
                        )
                        messages.add(
                            AgentChatMessage(
                                role = "tool",
                                toolCallId = toolCallId,
                                content = "Error: $errorResult"
                            )
                        )
                        steps.add(
                            AgentStep(
                                stepNumber = stepNumber,
                                thought = thought,
                                toolName = functionName,
                                toolArgs = rawArguments,
                                toolResult = null,
                                error = errorResult
                            )
                        )
                        continue
                    }

                    // 检查模式：ASK 模式下需要用户确认
                    if (mode == AgentMode.ASK) {
                        // 在 ASK 模式下，我们记录工具调用但不自动执行
                        // 调用方需要先确认，这里我们继续执行但记录日志
                        Log.i(TAG, "ASK mode: tool call pending user confirmation: $functionName")
                        // 实际项目中，这里应通过回调通知 UI 层请求用户确认
                        // 当前实现中，ASK 模式仍然自动执行，仅记录日志
                    }

                    // 更新状态
                    updateState {
                        copy(currentStep = "执行: $functionName")
                    }

                    // 执行工具
                    val toolResult = toolRegistry.executeTool(functionName, argsJson)

                    // 记录工具执行结果（截断过长内容）
                    val truncatedResult = if (toolResult.data.length > TOOL_RESULT_MAX_LENGTH) {
                        toolResult.data.take(TOOL_RESULT_MAX_LENGTH) + "\n... (${toolResult.data.length - TOOL_RESULT_MAX_LENGTH} more characters truncated)"
                    } else {
                        toolResult.data
                    }

                    val resultText = buildString {
                        if (toolResult.success) {
                            append("Success: ")
                            append(truncatedResult)
                        } else {
                            append("Error: ")
                            append(toolResult.error ?: truncatedResult)
                        }
                    }

                    Log.d(TAG, "Tool result for $functionName: ${resultText.take(300)}")

                    // 检查是否创建了文件（从工具名称或参数中检测）
                    detectCreatedFiles(functionName, argsJson, toolResult)

                    // 将 assistant 消息和 tool 结果加入对话历史
                    // 注意：对于多个 tool_calls，assistant 消息只需要添加一次
                    // 但这里我们每个 tool_call 单独处理
                    if (toolCalls.size == 1) {
                        messages.add(
                            AgentChatMessage(
                                role = "assistant",
                                content = content,
                                toolCalls = listOf(
                                    AgentToolCallData(
                                        id = toolCallId,
                                        type = "function",
                                        function = AgentToolCallFunction(
                                            name = functionName,
                                            arguments = rawArguments
                                        )
                                    )
                                )
                            )
                        )
                    }

                    messages.add(
                        AgentChatMessage(
                            role = "tool",
                            toolCallId = toolCallId,
                            content = resultText
                        )
                    )

                    // 记录步骤
                    steps.add(
                        AgentStep(
                            stepNumber = stepNumber,
                            thought = thought,
                            toolName = functionName,
                            toolArgs = rawArguments,
                            toolResult = truncatedResult,
                            summary = if (toolResult.success) "工具 $functionName 执行成功" else "工具 $functionName 执行失败",
                            error = if (!toolResult.success) toolResult.error else null
                        )
                    )
                }

                // 如果取消，退出循环
                if (cancelled.get()) break
            } else {
                // 没有工具调用，LLM 返回了最终答案
                finalAnswer = content ?: "(AI 没有提供文本回答)"
                Log.i(TAG, "LLM returned final answer at iteration $iteration")

                // 记录最终步骤
                steps.add(
                    AgentStep(
                        stepNumber = stepNumber,
                        thought = finalAnswer,
                        summary = "AI 完成了任务"
                    )
                )

                // 将 assistant 的最终回答加入历史
                messages.add(
                    AgentChatMessage(
                        role = "assistant",
                        content = content
                    )
                )

                break
            }
        }

        // 检查是否因达到最大迭代次数而退出
        val wasForcedExit = iteration >= maxIterations && !cancelled.get()

        if (wasForcedExit) {
            Log.w(TAG, "ReAct loop reached max iterations ($maxIterations)")
            updateState {
                copy(currentStep = "达到最大迭代次数")
            }
        }

        // 构建最终结果
        val success = !wasForcedExit && !cancelled.get()
        val summary = if (success) {
            if (finalAnswer.isNotBlank()) {
                // 尝试从 finalAnswer 中提取摘要，取前 500 字符
                finalAnswer.take(500)
            } else {
                "任务完成，共执行 ${steps.size} 步"
            }
        } else if (wasForcedExit) {
            "任务未完成：达到最大迭代次数（$maxIterations），已执行 ${steps.size} 步"
        } else {
            "任务已被取消"
        }

        return AgentResult(
            success = success,
            summary = summary,
            steps = steps.toList(),
            output = finalAnswer.ifBlank { null },
            filesCreated = createdFiles.toList(),
            error = if (wasForcedExit) "Reached max iterations ($maxIterations)" else null
        )
    }

    // =========================================================================
    // LLM API 调用（带 function calling）
    // =========================================================================

    /**
     * 调用 LLM 的 Chat Completions API，支持 function calling（tools）。
     *
     * 使用与 AIGateway 相同的 API 配置（baseUrl、apiKey、model），
     * 但请求体中包含 tools 参数以实现工具调用。
     *
     * @param messages 对话消息列表（包含 system / user / assistant / tool 消息）
     * @param functions OpenAI 兼容的 tools 数组（由 ToolRegistry 生成）
     * @return API 返回的完整 JSON 响应对象
     */
    private suspend fun callLLM(
        messages: List<AgentChatMessage>,
        functions: JsonArray
    ): JsonObject = withContext(Dispatchers.IO) {
        val config = gateway.currentConfig()

        // 构建请求体
        val requestBodyJson = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            putJsonArray("messages") {
                for (msg in messages) {
                    add(msg.toJsonObject(json))
                }
            }
            put("temperature", JsonPrimitive(0.3))
            put("tools", functions)
            put("tool_choice", JsonPrimitive("auto"))
            put("stream", JsonPrimitive(false))
            // 如果配置了 maxTokens，添加到请求中
            config.maxTokens?.let { put("max_tokens", JsonPrimitive(it)) }
        }

        val bodyStr = json.encodeToString(requestBodyJson)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyStr.toRequestBody(mediaType)

        // 构造 URL
        val base = config.baseUrl.trimEnd('/')
        val url = when {
            base.endsWith("/chat/completions") -> base
            Regex("""/v\d+$""").containsMatchIn(base) -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body)
            .build()

        // 执行请求（带重试）
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val response = client.newCall(request).await()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errorBody = resp.body?.string()?.take(500) ?: ""
                        Log.e(TAG, "LLM API error (attempt ${attempt + 1}): HTTP ${resp.code} $errorBody")
                        if (resp.code in 400..499) {
                            throw IOException("LLM API returned HTTP ${resp.code}: $errorBody")
                        }
                        lastError = IOException("HTTP ${resp.code}: $errorBody")
                        return@use
                    }
                    val raw = resp.body?.string().orEmpty()
                    val parsed = json.parseToJsonElement(raw).jsonObject
                    Log.d(TAG, "LLM response received (${raw.length} chars)")

                    // 检查是否有错误信息
                    val error = parsed["error"]?.jsonObject
                    if (error != null) {
                        val errMsg = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown API error"
                        val errType = error["type"]?.jsonPrimitive?.contentOrNull ?: ""
                        throw IOException("API error: $errMsg (type: $errType)")
                    }

                    return@withContext parsed
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "LLM API timeout (attempt ${attempt + 1})", e)
                lastError = e
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "LLM API connection failed (attempt ${attempt + 1})", e)
                lastError = e
            } catch (e: IOException) {
                if (e.message?.contains("HTTP 4") == true) throw e
                Log.w(TAG, "LLM API IO error (attempt ${attempt + 1})", e)
                lastError = e
            }
            if (attempt < 2) {
                Thread.sleep((500L * (attempt + 1)))
            }
        }

        throw lastError ?: IOException("LLM API call failed after 3 attempts")
    }

    // =========================================================================
    // 本地推理任务
    // =========================================================================

    /**
     * 使用本地模型执行任务（当云端 API 不可用时）。
     *
     * 本地模型可能不支持功能调用（function calling），因此使用简化的
     * 单轮推理模式，将任务描述直接发送给本地模型，返回其生成的文本。
     *
     * @param task 用户任务描述
     * @return Agent 执行结果
     */
    private suspend fun runLocalTask(task: String): AgentResult {
        Log.i(TAG, "runLocalTask: 使用本地模型执行任务: ${task.take(200)}")

        updateState { copy(currentStep = "本地模型推理中...") }

        try {
            val provider = localProvider ?: return AgentResult(
                success = false,
                summary = "本地模型不可用",
                steps = emptyList(),
                error = "Local provider not available"
            )
            if (!provider.isAvailable) {
                return AgentResult(
                    success = false,
                    summary = "本地模型未加载",
                    steps = emptyList(),
                    error = "Local model not loaded"
                )
            }

            // 使用本地模型进行对话
            val response = provider.chat(task)

            steps.add(
                AgentStep(
                    stepNumber = 1,
                    thought = "使用本地模型处理任务",
                    summary = "本地模型推理完成"
                )
            )

            return AgentResult(
                success = true,
                summary = response.take(500),
                steps = steps.toList(),
                output = response
            )

        } catch (e: Exception) {
            Log.e(TAG, "runLocalTask: 本地模型推理失败", e)
            steps.add(
                AgentStep(
                    stepNumber = 1,
                    error = "本地推理失败: ${e.message}"
                )
            )
            return AgentResult(
                success = false,
                summary = "本地模型推理失败",
                steps = steps.toList(),
                error = "Local inference error: ${e.message ?: "Unknown"}"
            )
        }
    }

    // =========================================================================
    // 系统提示词构建
    // =========================================================================

    /**
     * 构建 ReAct Agent 的系统提示词。
     *
     * 提示词内容涵盖：
     * - Agent 的身份定位（运行在 Android 手机上的 AI 助手）
     * - 可用工具的详细说明
     * - ReAct 工作模式说明（思考 -> 工具调用 -> 观察 -> 循环 -> 最终回答）
     * - 任务执行规范和最佳实践
     * - 输出格式要求
     */
    private fun buildSystemPrompt(): String {
        val tools = toolRegistry.getAllTools()

        return buildString {
            appendLine("你是一个运行在 Android 手机上的 AI 智能助手（Agent），名为 MobileClaw。")
            appendLine()
            appendLine("## 你的身份")
            appendLine("- 你运行在用户的 Android 设备上，拥有通过 Shell 命令和系统工具直接操作设备的能力。")
            appendLine("- 你可以执行 Shell 命令、运行 Python 代码、读写文件、安装应用、模拟屏幕点击、输入文本等。")
            appendLine("- 你是一个有创造力和主动性的 AI，能够自主推理并完成任务。")
            appendLine()
            appendLine("## 工作模式：ReAct（Reasoning + Acting）")
            appendLine("你遵循「思考 -> 行动 -> 观察」的循环模式来完成任务：")
            appendLine()
            appendLine("1. **思考（Thought）**：分析当前任务状态，确定下一步需要做什么。")
            appendLine("2. **行动（Action）**：调用一个工具来执行具体操作。")
            appendLine("3. **观察（Observation）**：接收工具执行的结果，分析是否达到了预期。")
            appendLine("4. 重复上述步骤，直到任务完成。")
            appendLine("5. 任务完成后，给出最终回答（Final Answer），总结你做了什么、结果如何。")
            appendLine()
            appendLine("## 可用工具")
            appendLine("以下是你可以调用的工具列表。每个工具都有特定的用途和参数。")
            appendLine("选择合适的工具来完成当前步骤。如果某个工具的结果不理想，可以换一种方法重试。")
            appendLine()

            // 列出所有工具
            for ((index, tool) in tools.withIndex()) {
                appendLine("### ${index + 1}. ${tool.name}")
                appendLine("   ${tool.description}")
                appendLine("   参数: ${tool.parameters.toString().take(300)}")
                appendLine()
            }

            appendLine("## 工具调用格式")
            appendLine("当你需要调用工具时，请在思考之后使用 JSON 格式的工具调用。")
            appendLine("每次调用一个工具，等待工具执行结果后再决定下一步。")
            appendLine()
            appendLine("## 任务执行规范")
            appendLine("1. **逐步推理**：复杂任务要拆解为多个步骤，每步只调用一个工具。")
            appendLine("2. **错误处理**：如果工具执行失败，分析错误原因，尝试替代方案。")
            appendLine("3. **文件操作**：创建文件后，告知用户文件路径。记录所有创建的文件。")
            appendLine("4. **效率优先**：优先使用已有的工具，不要重复造轮子。")
            appendLine("5. **安全第一**：不要执行可能损坏系统的操作（如 rm -rf /、格式化分区等）。")
            appendLine("6. **信息收集**：在操作前，先收集必要的信息（如当前目录、系统状态等）。")
            appendLine("7. **超时控制**：对于可能长时间运行的操作（如安装 APK），设置合理的超时时间。")
            appendLine()
            appendLine("## 任务完成")
            appendLine("当任务完成时，请给出一个完整的总结，包含：")
            appendLine("- 你完成了什么任务")
            appendLine("- 执行了哪些步骤")
            appendLine("- 创建了哪些文件（如果有）")
            appendLine("- 任何需要注意的事项或建议")
            appendLine()
            appendLine("## 重要规则")
            appendLine("- 在最终回答前，确保所有必要步骤都已执行完毕。")
            appendLine("- 如果任务无法完成，诚实地说明原因和当前进展。")
            appendLine("- 保持回答简洁、专业、有用。")
            appendLine("- 使用中文与用户交流。")
            appendLine("- 严格遵守以上所有规则。")
        }
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    /**
     * 检测工具执行是否创建了文件，并记录到 createdFiles 列表。
     */
    private fun detectCreatedFiles(
        toolName: String,
        args: JsonObject,
        result: ToolResult
    ) {
        if (!result.success) return

        // 从工具名称判断
        val fileCreatingTools = setOf(
            "write_file", "create_python_file", "create_shell_script", "generate_code"
        )
        if (toolName in fileCreatingTools) {
            val path = args["path"]?.jsonPrimitive?.contentOrNull
                ?: args["file_path"]?.jsonPrimitive?.contentOrNull
            if (path != null) {
                synchronized(createdFiles) {
                    if (path !in createdFiles) {
                        createdFiles.add(path)
                    }
                }
            }
        }
    }

    /**
     * 安全地更新 AgentState。
     */
    private fun updateState(transform: AgentState.() -> AgentState) {
        _stateFlow.value = _stateFlow.value.transform()
    }

    // =========================================================================
    // OkHttp Call 协程化扩展
    // =========================================================================

    /**
     * 将 OkHttp 的异步 Call 转为挂起函数，支持协程取消时自动取消网络请求。
     */
    private suspend fun okhttp3.Call.await(): okhttp3.Response =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    cont.resumeWith(kotlin.Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    cont.resumeWith(kotlin.Result.success(response))
                }
            })
        }
}

// =============================================================================
// 数据模型
// =============================================================================

/**
 * Agent 执行结果。
 *
 * @property success     是否成功完成
 * @property summary     执行摘要
 * @property steps       执行步骤列表
 * @property output      AI 最终回答文本（如果有）
 * @property filesCreated 创建的文件路径列表
 * @property error       错误信息（失败时）
 */
data class AgentResult(
    val success: Boolean,
    val summary: String,
    val steps: List<AgentStep>,
    val output: String? = null,
    val filesCreated: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Agent 单步执行记录。
 *
 * @property stepNumber 步骤编号
 * @property thought     AI 在这一步的思考/推理内容
 * @property toolName    调用的工具名称（如果有）
 * @property toolArgs    工具调用参数（JSON 字符串）
 * @property toolResult  工具执行结果
 * @property summary     步骤摘要
 * @property error       步骤执行中的错误信息
 */
data class AgentStep(
    val stepNumber: Int,
    val thought: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolResult: String? = null,
    val summary: String? = null,
    val error: String? = null
)

// =============================================================================
// 内部 API 数据模型（OpenAI Function Calling 协议）
// =============================================================================

/**
 * 内部使用的 Agent 消息，支持 OpenAI 的 function calling 协议。
 *
 * 注意：此类型与 AIGateway 中的 ChatMessage 同名但不同结构，因此使用 internal 限定。
 * 为避免与 AIGateway 中的 ChatMessage 冲突，在引用时以全限定名区分。
 *
 * @property role       角色：system / user / assistant / tool
 * @property content    消息内容文本
 * @property toolCallId 工具调用 ID（仅 role=tool 时使用）
 * @property toolCalls  工具调用列表（仅 role=assistant 时使用）
 */
internal data class AgentChatMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AgentToolCallData>? = null
) {
    /**
     * 将 AgentChatMessage 序列化为 OpenAI 兼容的 JSON 对象。
     */
    fun toJsonObject(json: Json): JsonElement {
        return buildJsonObject {
            put("role", JsonPrimitive(role))
            if (content != null) {
                put("content", JsonPrimitive(content))
            }
            if (toolCallId != null) {
                put("tool_call_id", JsonPrimitive(toolCallId))
            }
            if (toolCalls != null && toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    for (tc in toolCalls) {
                        add(buildJsonObject {
                            put("id", JsonPrimitive(tc.id))
                            put("type", JsonPrimitive(tc.type))
                            putJsonObject("function") {
                                put("name", JsonPrimitive(tc.function.name))
                                put("arguments", JsonPrimitive(tc.function.arguments))
                            }
                        })
                    }
                }
            }
        }
    }
}

/**
 * 工具调用数据（OpenAI function calling 格式）。
 */
internal data class AgentToolCallData(
    val id: String,
    val type: String = "function",
    val function: AgentToolCallFunction
)

/**
 * 工具调用中的 function 对象。
 */
internal data class AgentToolCallFunction(
    val name: String,
    val arguments: String
)