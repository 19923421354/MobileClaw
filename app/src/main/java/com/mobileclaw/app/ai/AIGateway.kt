package com.mobileclaw.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * AIGateway - AI 指令解析网关
 *
 * 通过 OkHttp 调用兼容 OpenAI Chat Completions 协议的大模型 API
 * （包括 DeepSeek、通义千问、智谱 GLM、Moonshot、OpenAI 等），将用户的
 * 自然语言指令 + 当前手机状态发送给 AI，并解析其返回的结构化 JSON 指令。
 *
 * 核心能力：
 * 1. 通过 [configure] 配置 apiKey / baseUrl / model，支持运行时切换模型供应商。
 * 2. 维护多轮对话上下文（[conversationHistory]），支持连续交互。
 * 3. 支持流式（SSE）与非流式两种响应模式。
 * 4. 利用 [ActionTranslator] 将 AI 输出解析为 [ClawCommandResult]。
 *
 * 使用示例：
 * ```
 * val gateway = AIGateway()
 * gateway.configure(apiKey = "sk-xxx", baseUrl = "https://api.deepseek.com", model = "deepseek-v4-flash")
 * val result = gateway.sendCommand("打开微信", phoneState)
 * ```
 *
 * @property client 共享的 OkHttp 客户端
 */
class AIGateway(
    private val client: OkHttpClient = defaultClient()
) {

    /** 当前 AI 配置（AtomicReference 保证多线程下配置切换的可见性）。 */
    private val configRef = AtomicReference(AIConfig())

    /** 多轮对话历史（不包含每次重新生成的 system message）。 */
    private val conversationHistory = mutableListOf<ChatMessage>()

    /** 对话历史的最大保留轮数（每轮含 user + assistant 两条），超出后裁剪旧消息。 */
    var maxHistoryTurns: Int = DEFAULT_MAX_HISTORY_TURNS

    /**
     * 智能 Token 模式：开启后根据任务复杂度动态调节提示词级别、maxTokens、历史轮数。
     *
     * 两阶段智能评估：
     * 1. 如果评估器（[taskEvaluator]）已配置，先用评估模型分析任务，获取精确参数
     * 2. 评估器未配置或降级时，使用 [TaskComplexityAnalyzer] 本地关键词分析
     *
     * 关闭后始终使用 FULL 提示词 + 不限 tokens + 6 轮历史（最大质量模式）。
     */
    @Volatile
    var intelligentMode: Boolean = true

    /**
     * 兼容旧接口：tokenSavingMode 的读写委托给 [intelligentMode]。
     * 保留以避免外部反射调用（如 MainActivity 旧代码）报错。
     */
    var tokenSavingMode: Boolean
        get() = intelligentMode
        set(value) { intelligentMode = value }

    /** 任务评估器（独立轻量模型，可配置不同 API）。 */
    val taskEvaluator: TaskEvaluator = TaskEvaluator(client)

    /** 当前任务的评估结果，由 [sendCommand] 分析后缓存，供 [continueCommand] 复用。 */
    @Volatile
    var currentEvaluation: EvaluationResult? = null

    /** 当前任务的复杂度等级（兼容旧接口），由 [sendCommand] 分析后缓存。 */
    @Volatile
    var currentComplexity: TaskComplexityAnalyzer.Complexity = TaskComplexityAnalyzer.Complexity.MEDIUM

    /** 思考模式：开启后提高温度并尝试启用推理参数（若模型支持）。默认关闭。 */
    @Volatile
    var thinkingMode: Boolean = false

    /** API 健康监控器：检测连续失败并自动切换到备用 API。 */
    val healthMonitor = ApiHealthMonitor()

    /** Token 用量跟踪器：统计每次 AI 请求的 Token 消耗。 */
    val tokenTracker = TokenTracker()

    /** 响应缓存：缓存近期相同查询的 AI 响应，避免重复 API 调用。 */
    val responseCache = ResponseCache()

    /** JSON 编解码器，复用 ActionTranslator 的实例以保持一致配置。 */
    private val json = ActionTranslator.json

    // =========================================================================
    //  配置
    // =========================================================================

    /**
     * 配置 AI 网关的连接参数。
     *
     * @param apiKey 模型供应商的 API Key（会以 `Bearer <apiKey>` 形式放入 Authorization 头）
     * @param baseUrl 基础地址，例如 `https://api.deepseek.com`（不带 `/v1/chat/completions` 后缀）
     * @param model 模型名称，例如 `glm-4.7-flash`、`deepseek-v4-flash`、`qwen-plus` 等
     */
    fun configure(apiKey: String, baseUrl: String, model: String) {
        configRef.set(
            AIConfig(
                apiKey = apiKey.trim(),
                baseUrl = baseUrl.trim().trimEnd('/'),
                model = model.trim()
            )
        )
        // 切换配置后清空历史对话，避免跨模型上下文错乱
        clearContext()
    }

    /** 当前是否已完成配置（apiKey、baseUrl、model 均非空）。 */
    fun isConfigured(): Boolean = configRef.get().let {
        it.apiKey.isNotBlank() && it.baseUrl.isNotBlank() && it.model.isNotBlank()
    }

    /**
     * 配置评估器（独立于主模型）。
     *
     * 评估器可以用完全不同的 API 和模型：
     * - 主模型用 DeepSeek，评估器可以用豆包 lite
     * - 主模型用 GLM-4.7，评估器可以用 GLM-4.7-Flash（同 API 不同模型）
     * - 也可以用 GPT、通义千问等任意 OpenAI 兼容接口
     *
     * 传入空字符串清除评估器配置，回退到本地关键词分析。
     */
    fun configureEvaluator(apiKey: String, baseUrl: String, model: String) {
        taskEvaluator.configure(apiKey, baseUrl, model)
    }

    /** 评估器是否已配置。 */
    fun isEvaluatorConfigured(): Boolean = taskEvaluator.isConfigured()

    /** 清空多轮对话上下文。 */
    fun clearContext() {
        synchronized(conversationHistory) {
            conversationHistory.clear()
        }
    }

    /** 获取当前配置的只读快照。 */
    fun currentConfig(): AIConfig = configRef.get()

    // =========================================================================
    //  核心方法：发送指令并解析为 ClawAction
    // =========================================================================

    /**
     * 将用户指令与当前手机状态发送给 AI，解析返回的结构化指令。
     *
     * 两阶段智能评估流程：
     * 1. **评估阶段**：如果评估器已配置，先用评估模型分析任务获取精确参数；
     *    评估器未配置或降级时，使用 [TaskComplexityAnalyzer] 本地关键词分析。
     * 2. **执行阶段**：主模型按评估结果配置的参数（maxTokens、提示词级别、历史轮数）执行。
     *
     * @param userInput 用户的自然语言指令
     * @param phoneState 当前手机状态（作为上下文注入 system prompt）
     * @param stream 是否使用流式响应（默认 false；流式时仍会聚合完整内容后再解析）
     * @return 解析后的指令集合
     * @throws IllegalStateException 未配置时抛出
     * @throws IOException 网络请求失败时抛出
     */
    suspend fun sendCommand(
        userInput: String,
        phoneState: PhoneState,
        stream: Boolean = false
    ): ClawCommandResult {
        requireConfigured()

        // 1. 两阶段智能评估：评估器优先，本地分析兜底
        val evaluation = evaluateTask(userInput)
        currentEvaluation = evaluation
        currentComplexity = mapToComplexity(evaluation)

        android.util.Log.i("AIGateway",
            "任务评估: ${evaluation.source.name} | 预估${evaluation.estimatedSteps}步 | " +
            "maxTokens=${evaluation.maxTokens} | 历史${evaluation.historyTurns}轮 | " +
            "提示=${evaluation.promptLevel} | ${evaluation.reason}")

        // 1.5 响应缓存检查：SIMPLE/MICRO 级别任务查缓存，命中则跳过 API 调用
        if (!stream) {
            val cached = responseCache.get(
                userInput, phoneState.currentAppPackage, currentComplexity
            )
            if (cached != null) {
                android.util.Log.i("AIGateway", "响应缓存命中，跳过 API 调用")
                return cached
            }
        }

        val systemPrompt = TaskComplexityAnalyzer.generatePromptFromEvaluation(evaluation, phoneState)
        val userMessage = ChatMessage(role = ROLE_USER, content = buildUserContent(userInput, phoneState, evaluation))

        val messages = buildMessages(systemPrompt, userMessage)

        // 2. 调用模型
        val assistantContent = if (stream) {
            requestStream(messages)
        } else {
            requestNonStream(messages)
        }

        // 3. 记录历史（SIMPLE 单步任务执行完后清空历史，不需要上下文）
        synchronized(conversationHistory) {
            if (evaluation.estimatedSteps <= 1) {
                // 单步任务不需要保留历史，直接清空
                conversationHistory.clear()
            } else {
                conversationHistory.add(userMessage)
                conversationHistory.add(ChatMessage(role = ROLE_ASSISTANT, content = assistantContent))
                trimHistory()
            }
        }

        // 4. 解析为动作
        val result = ActionTranslator.parse(assistantContent)

        // 4.5 存入响应缓存（仅 SIMPLE/MICRO 级别）
        responseCache.put(userInput, phoneState.currentAppPackage, currentComplexity, result)

        return result
    }

    /**
     * 在多步编排循环中追加执行反馈并请求下一步指令。
     *
     * 由 [ClawController] 在执行完一批动作后调用：将执行结果与新状态以 user 消息形式
     * 反馈给 AI，让其决定是否继续。
     *
     * 优化：
     * - 不重复发送屏幕文本（系统提示词中已包含最新屏幕文本）
     * - 精简状态信息，仅发送变化部分
     * - 提供更明确的决策引导
     *
     * @param feedback 执行反馈文本（每条动作的人类可读描述 + 结果）
     * @param phoneState 最新的手机状态
     * @return AI 给出的下一批指令
     */
    suspend fun continueCommand(
        feedback: String,
        phoneState: PhoneState
    ): ClawCommandResult {
        requireConfigured()

        // 复用首轮评估结果，保持多步编排中参数一致
        val evaluation = currentEvaluation ?: run {
            // 兜底：如果没有缓存的评估结果，使用本地分析
            val complexity = TaskComplexityAnalyzer.analyze(feedback)
            TaskComplexityAnalyzer.toEvaluationResult(complexity, feedback)
        }
        val systemPrompt = TaskComplexityAnalyzer.generatePromptFromEvaluation(evaluation, phoneState)
        val userMessage = ChatMessage(
            role = ROLE_USER,
            content = buildString {
                appendLine("==执行结果==")
                appendLine(feedback)
                appendLine("==当前状态==")
                append("前台:${phoneState.currentAppPackage ?: "未知"}")
                // 使用差异屏幕文本：仅发送变化部分，节省 60-80% Token
                if (phoneState.currentScreenText.isNotBlank()) {
                    val diffText = DifferentialScreenText.build(
                        phoneState.currentScreenText,
                        evaluation.screenTextLimit.coerceAtLeast(300)
                    )
                    appendLine()
                    append("屏幕变化:$diffText")
                }
                appendLine()
                appendLine("==下一步==")
                append("全部成功→ANSWER总结。有失败→换方法重试。禁止未确认就ANSWER。")
            }
        )
        val messages = buildMessages(systemPrompt, userMessage)

        val assistantContent = requestNonStream(messages)

        synchronized(conversationHistory) {
            conversationHistory.add(userMessage)
            conversationHistory.add(ChatMessage(role = ROLE_ASSISTANT, content = assistantContent))
            trimHistory()
        }

        return ActionTranslator.parse(assistantContent)
    }

    // =========================================================================
    //  流式响应：逐 token 回调
    // =========================================================================

    /**
     * 流式请求并以回调形式逐段返回文本内容（SSE）。
     *
     * 适用于 UI 实时展示 AI「思考过程」或 ANSWER 流式输出的场景。
     * 该方法会阻塞直到流结束，并返回拼接后的完整文本。
     *
     * @param onDelta 每收到一个内容片段时的回调（在 IO 线程触发）
     * @return 拼接后的完整 assistant 文本
     */
    suspend fun sendCommandStreaming(
        userInput: String,
        phoneState: PhoneState,
        onDelta: (String) -> Unit
    ): ClawCommandResult {
        requireConfigured()

        // 两阶段智能评估
        val evaluation = evaluateTask(userInput)
        currentEvaluation = evaluation
        currentComplexity = mapToComplexity(evaluation)

        val systemPrompt = TaskComplexityAnalyzer.generatePromptFromEvaluation(evaluation, phoneState)
        val userMessage = ChatMessage(role = ROLE_USER, content = buildUserContent(userInput, phoneState, evaluation))
        val messages = buildMessages(systemPrompt, userMessage)

        val assistantContent = requestStream(messages, onDelta)

        synchronized(conversationHistory) {
            conversationHistory.add(userMessage)
            conversationHistory.add(ChatMessage(role = ROLE_ASSISTANT, content = assistantContent))
            trimHistory()
        }

        return ActionTranslator.parse(assistantContent)
    }

    // =========================================================================
    //  便捷方法：发送简单提示词（供 CodeGenerator 等组件使用）
    // =========================================================================

    /**
     * 发送一个简单的提示词，返回 AI 的文本响应。
     *
     * 不维护对话历史，适合一次性代码生成等场景。
     *
     * @param prompt 提示词内容
     * @return AI 返回的文本，失败时返回 null
     */
    suspend fun sendSimplePrompt(prompt: String): String? {
        if (!isConfigured()) return null
        return try {
            val messages = listOf(
                ChatMessage(role = "user", content = prompt)
            )
            val result = requestNonStream(messages)
            result.ifBlank { null }
        } catch (e: Exception) {
            android.util.Log.e("AIGateway", "sendSimplePrompt failed", e)
            null
        }
    }

    // =========================================================================
    //  内部：消息构建与请求
    // =========================================================================

    /**
     * 构建发送给 API 的完整消息列表：system + 历史 + 本轮 user。
     */
    private fun buildMessages(systemPrompt: String, userMessage: ChatMessage): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(conversationHistory.size + 2)
        messages.add(ChatMessage(role = ROLE_SYSTEM, content = systemPrompt))
        synchronized(conversationHistory) {
            messages.addAll(conversationHistory)
        }
        messages.add(userMessage)
        return messages
    }

    /**
     * 拼装 user 消息内容：用户原始指令 + 紧凑的手机状态摘要。
     *
     * 智能策略：
     * - 单步任务（ULTRA）：仅用户指令（极简，状态已在提示词中）
     * - 多步任务（COMPACT/FULL）：附加一行简短状态提示，辅助 AI 决策
     */
    private fun buildUserContent(
        userInput: String,
        phoneState: PhoneState,
        evaluation: EvaluationResult
    ): String = buildString {
        appendLine(userInput)
        // 总是发送状态信息，即使是单步任务也需要上下文
        append("[状态] 前台:${phoneState.currentAppPackage ?: "未知"} 电量:${phoneState.batteryPercent}%")
        if (phoneState.isCharging) append("(充电中)")
        // 单步任务也发送屏幕文本，帮助 AI 理解当前界面
        if (evaluation.estimatedSteps <= 1 && phoneState.currentScreenText.isNotBlank()) {
            val limit = evaluation.screenTextLimit.coerceAtLeast(200)
            appendLine()
            append("屏幕文本:${phoneState.currentScreenText.take(limit)}")
        }
    }

    /** 当历史超过实际生效轮数时，裁剪最早的消息。 */
    private fun trimHistory() {
        val maxMessages = effectiveMaxHistoryTurns() * 2
        while (conversationHistory.size > maxMessages) {
            conversationHistory.removeAt(0)
        }
    }

    /**
     * 根据思考模式计算实际生效的采样温度。
     * - thinkingMode=true：0.5（允许一定发散以支持推理）
     * - thinkingMode=false：0.15（更确定性，适合结构化指令生成）
     */
    private fun effectiveTemperature(): Double = if (thinkingMode) 0.5 else 0.15

    /**
     * 根据当前评估结果计算实际生效的历史轮数。
     * 使用 [currentEvaluation] 的精确值，而非固定等级的预设值。
     */
    private fun effectiveMaxHistoryTurns(): Int = currentEvaluation?.historyTurns
        ?: currentComplexity.historyTurns

    /**
     * 根据当前评估结果计算最大输出 Token 数。
     * 使用 [currentEvaluation] 的精确值；maxTokens=0 时返回 null（不限制）。
     */
    private fun effectiveMaxTokens(): Int? = currentEvaluation?.let { eval ->
        eval.maxTokens.takeIf { it > 0 }
    } ?: currentComplexity.maxTokens.takeIf { it > 0 }

    /**
     * 两阶段任务评估：评估器优先，本地分析兜底。
     *
     * 流程：
     * 1. 智能模式关闭 → 返回 UNLIMITED 评估结果（最大质量）
     * 2. 评估器已配置 → 调用评估模型获取精确参数
     * 3. 评估器降级/未配置 → 使用 [TaskComplexityAnalyzer] 本地关键词分析
     *
     * @param userInput 用户指令
     * @return 评估结果（始终非空，最坏情况降级到本地分析）
     */
    private suspend fun evaluateTask(userInput: String): EvaluationResult {
        // 智能模式关闭：最大质量模式
        if (!intelligentMode) {
            return TaskComplexityAnalyzer.toEvaluationResult(
                TaskComplexityAnalyzer.Complexity.UNLIMITED, userInput
            )
        }

        // 阶段1：尝试用评估模型分析
        if (taskEvaluator.isConfigured()) {
            val modelResult = taskEvaluator.evaluate(userInput)
            if (modelResult != null) {
                android.util.Log.i("AIGateway", "评估器成功: ${modelResult.description()}")
                return modelResult
            }
            android.util.Log.w("AIGateway", "评估器降级 → 使用本地分析")
        }

        // 阶段2：本地关键词分析兜底
        val complexity = TaskComplexityAnalyzer.analyze(userInput)
        return TaskComplexityAnalyzer.toEvaluationResult(complexity, userInput)
    }

    /**
     * 将评估结果映射到最接近的 [Complexity] 等级（兼容旧接口）。
     */
    private fun mapToComplexity(evaluation: EvaluationResult): TaskComplexityAnalyzer.Complexity {
        return when {
            evaluation.maxTokens == 0 -> TaskComplexityAnalyzer.Complexity.UNLIMITED
            evaluation.maxTokens <= 256 -> TaskComplexityAnalyzer.Complexity.MICRO
            evaluation.maxTokens <= 512 -> TaskComplexityAnalyzer.Complexity.SIMPLE
            evaluation.maxTokens <= 1024 -> TaskComplexityAnalyzer.Complexity.MEDIUM
            else -> TaskComplexityAnalyzer.Complexity.COMPLEX
        }
    }

    /**
     * 构建思考模式下的 reasoning 参数（OpenAI o1 风格的 effort 字段）。
     * 不支持该参数的供应商通常会忽略未知字段，因此可安全下发。
     */
    private fun reasoningParam(): JsonElement =
        JsonObject(mapOf("effort" to JsonPrimitive("medium")))

    /**
     * 非流式请求：一次拿到完整响应。
     * 强制使用 response_format=json_object 确保 AI 返回 JSON。
     * 内置网络重试（最多2次重试，共3次请求）。
     *
     * 容错策略：
     * - 首次请求带 response_format=json_object
     * - 若返回 400 错误（可能是不支持 response_format），自动降级为不带该参数重试
     */
    private suspend fun requestNonStream(messages: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        val config = configRef.get()

        // 首次请求：带 response_format
        val result = tryRequest(messages, config, useResponseFormat = true)
        if (result != null) return@withContext result

        // 降级请求：不带 response_format（兼容不支持该参数的 API 供应商）
        android.util.Log.w("AIGateway", "带 response_format 请求失败，降级为不带该参数重试")
        val fallbackResult = tryRequest(messages, config, useResponseFormat = false)
        if (fallbackResult != null) return@withContext fallbackResult

        throw IOException("AI 请求失败（已尝试带/不带 response_format，各重试3次）")
    }

    /**
     * 执行带重试的请求。
     *
     * @param messages 消息列表
     * @param config AI 配置
     * @param useResponseFormat 是否携带 response_format 参数
     * @return 成功时返回 AI 内容，失败时返回 null
     */
    private suspend fun tryRequest(
        messages: List<ChatMessage>,
        config: AIConfig,
        useResponseFormat: Boolean
    ): String? = withContext(Dispatchers.IO) {
        // 健康监控：如果主 API 不健康且有备用 API，切换到备用
        val effectiveConfig = healthMonitor.getCurrentApi()?.let { backup ->
            android.util.Log.w("AIGateway", "API 不健康，切换到备用: ${backup.name}")
            AIConfig(apiKey = backup.apiKey, baseUrl = backup.baseUrl, model = backup.model)
        } ?: config

        val requestBody = ChatCompletionRequest(
            model = effectiveConfig.model,
            messages = messages,
            stream = false,
            temperature = effectiveTemperature(),
            maxTokens = effectiveMaxTokens(),
            reasoning = null,
            responseFormat = if (useResponseFormat) ResponseFormat("json_object") else null
        )
        val bodyStr = json.encodeToString(requestBody)
        val request = buildRequest(effectiveConfig, bodyStr)

        var lastError: Exception? = null
        var shouldFallback = false
        val requestStartTime = System.currentTimeMillis()

        // 只尝试一次（不重试超时/连接错误，仅 5xx 服务端错误重试）
        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                val response = client.newCall(request).await()
                val isSuccessful = response.isSuccessful
                val code = response.code
                val errorBody = if (!isSuccessful) response.body?.string()?.take(500) ?: "" else ""
                val latency = System.currentTimeMillis() - requestStartTime

                if (!isSuccessful) {
                    android.util.Log.e("AIGateway", "AI 请求失败(第${attempt}次): HTTP ${code} body=$errorBody")
                    healthMonitor.recordResult(false, latency)
                    response.close()
                    // 400 错误：可能是不支持 response_format，标记降级
                    if (code == 400 && useResponseFormat) {
                        shouldFallback = true
                        return@withContext null
                    }
                    // 其他 4xx 错误不重试
                    if (code in 400..499) {
                        throw IOException("AI 请求失败：HTTP ${code} ${response.message}，body=$errorBody")
                    }
                    // 5xx 服务端错误：重试（最多3次）
                    if (attempt >= 3) {
                        throw IOException("AI 请求失败：HTTP ${code} ${response.message}，body=$errorBody")
                    }
                    lastError = IOException("AI 请求失败：HTTP ${code} ${response.message}，body=$errorBody")
                    try { Thread.sleep(1000L) } catch (_: InterruptedException) {}
                    continue
                }

                val raw = response.body?.string().orEmpty()
                response.close()
                val content = parseCompletionContent(raw)
                android.util.Log.d("AIGateway", "AI 原始返回(前300字): ${content.take(300)} 耗时:${latency}ms")

                // 记录健康状态和 Token 用量
                healthMonitor.recordResult(true, latency)
                val inputText = messages.joinToString("") { it.content }
                tokenTracker.record(
                    taskDescription = messages.lastOrNull { it.role == "user" }?.content?.take(50) ?: "",
                    inputText = inputText,
                    outputText = content,
                    model = effectiveConfig.model,
                    complexity = currentComplexity.name,
                    promptLevel = currentEvaluation?.promptLevel?.name ?: "FULL",
                    success = true
                )
                return@withContext content
            } catch (e: java.net.SocketTimeoutException) {
                // 超时不重试，直接报错
                android.util.Log.w("AIGateway", "请求超时: ${e.message}")
                healthMonitor.recordResult(false, System.currentTimeMillis() - requestStartTime)
                throw e
            } catch (e: java.net.ConnectException) {
                // 连接失败不重试，直接报错
                android.util.Log.w("AIGateway", "连接失败: ${e.message}")
                healthMonitor.recordResult(false, System.currentTimeMillis() - requestStartTime)
                throw e
            } catch (e: IOException) {
                if (e.message?.contains("HTTP 4") == true) throw e
                // 5xx 重试循环，其他网络错误直接报错
                if (attempt >= 3) throw e
                android.util.Log.w("AIGateway", "网络错误(第${attempt}次): ${e.message}")
                healthMonitor.recordResult(false, System.currentTimeMillis() - requestStartTime)
                lastError = e
                try { Thread.sleep(500L) } catch (_: InterruptedException) {}
            }
        }

        if (shouldFallback) return@withContext null
        throw lastError ?: IOException("AI 请求失败")
    }

    /**
     * 流式请求：解析 SSE 数据行，逐段回调并最终返回完整文本。
     *
     * OpenAI 兼容的流式协议：每行形如 `data: {...}`，最后以 `data: [DONE]` 结束。
     */
    private suspend fun requestStream(
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val config = configRef.get()
        val requestBody = ChatCompletionRequest(
            model = config.model,
            messages = messages,
            stream = true,
            temperature = effectiveTemperature(),
            maxTokens = effectiveMaxTokens(),
            reasoning = null,
            responseFormat = ResponseFormat("json_object")
        )
        val bodyStr = json.encodeToString(requestBody)
        val request = buildRequest(config, bodyStr)

        val response = client.newCall(request).await()
        val sb = StringBuilder()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("AI 流式请求失败：HTTP ${resp.code} ${resp.message}")
            }
            val reader = BufferedReader(InputStreamReader(resp.body?.byteStream() ?: error("空响应体")))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val data = line ?: continue
                if (!data.startsWith("data:")) continue
                val payload = data.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                // 每条 payload 是一个 ChatCompletionResponse（含 delta）
                val deltaContent = runCatching {
                    val chunk = json.decodeFromString(ChatCompletionResponse.serializer(), payload)
                    chunk.choices.firstOrNull()?.delta?.content
                }.getOrNull()
                if (!deltaContent.isNullOrEmpty()) {
                    sb.append(deltaContent)
                    onDelta(deltaContent)
                }
            }
        }
        sb.toString()
    }

    /**
     * 解析非流式 Chat Completions 响应，提取 assistant 文本内容。
     */
    private fun parseCompletionContent(raw: String): String {
        val resp = json.decodeFromString(ChatCompletionResponse.serializer(), raw)
        val content = resp.choices.firstOrNull()?.message?.content
            ?: throw IOException("AI 响应中未找到 message.content：${raw.take(500)}")
        return content
    }

    /** 构建 OkHttp Request（含 Authorization 头与 JSON body）。 */
    private fun buildRequest(config: AIConfig, bodyStr: String): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body: RequestBody = bodyStr.toRequestBody(mediaType)
        // 智能构造 API URL：
        // - 已包含完整路径（/chat/completions）则直接使用
        // - 已包含版本号路径（如 /v1、/v3、/v4）则仅追加 /chat/completions
        // - 其他情况追加 /v1/chat/completions（兼容 DeepSeek、通义千问等）
        val base = config.baseUrl.trimEnd('/')
        val url = when {
            base.endsWith("/chat/completions") -> base
            Regex("""/v\d+$""").containsMatchIn(base) -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body)
            .build()
    }

    /** 校验是否已配置。 */
    private fun requireConfigured() {
        check(isConfigured()) {
            "AIGateway 尚未配置，请先调用 configure(apiKey, baseUrl, model)"
        }
    }

    // =========================================================================
    //  对外暴露的对话历史（只读快照，便于调试/UI 展示）
    // =========================================================================

    /** 获取当前对话历史的只读快照。 */
    fun snapshotHistory(): List<ChatMessage> = synchronized(conversationHistory) {
        conversationHistory.toList()
    }

    /** 获取当前任务评估的可读描述，供 UI 展示。 */
    fun complexityDescription(): String = currentEvaluation?.description() ?: when (currentComplexity) {
        TaskComplexityAnalyzer.Complexity.MICRO -> "微操作"
        TaskComplexityAnalyzer.Complexity.SIMPLE -> "简单"
        TaskComplexityAnalyzer.Complexity.MEDIUM -> "中等"
        TaskComplexityAnalyzer.Complexity.COMPLEX -> "复杂"
        TaskComplexityAnalyzer.Complexity.UNLIMITED -> "无限制"
    }

    /** 获取当前任务评估来源，供 UI 展示。 */
    fun evaluationSource(): String = currentEvaluation?.source?.name ?: "LOCAL"

    /** 获取当前任务复杂度等级数值（1=简单, 2=中等, 3=复杂），供 UI 展示。 */
    fun complexityLevel(): Int = currentEvaluation?.estimatedSteps ?: currentComplexity.level

    /** 获取 API 健康状态摘要，供 UI 展示。 */
    fun healthSummary(): String = healthMonitor.getHealthSummary()

    /** 获取响应缓存摘要，供 UI 展示。 */
    fun cacheSummary(): String = responseCache.getSummary()

    /** 重置差异屏幕文本状态（每个新任务开始时调用）。 */
    fun resetDifferentialScreenText() {
        DifferentialScreenText.reset()
    }

    /** 获取 Token 用量统计摘要，供 UI 展示。 */
    fun tokenSummary(): String {
        val summary = tokenTracker.getSessionSummary()
        return buildString {
            appendLine("总请求: ${summary.totalRequests}")
            appendLine("总Token: ${summary.totalTokens} (输入${summary.totalInputTokens}+输出${summary.totalOutputTokens})")
            appendLine("平均Token/请求: ${summary.averageTokensPerRequest}")
            appendLine("成功率: ${"%.1f".format(summary.successRate * 100)}%")
            if (summary.estimatedSavingPercent > 0) {
                appendLine("智能节省: ${"%.1f".format(summary.estimatedSavingPercent)}%")
            }
            if (summary.byComplexity.isNotEmpty()) {
                appendLine("按复杂度: ${summary.byComplexity.entries.joinToString { "${it.key}=${it.value}" }}")
            }
        }
    }

    /** 获取今日 Token 用量摘要。 */
    fun todayTokenSummary(): String {
        val summary = tokenTracker.getTodaySummary()
        return buildString {
            appendLine("今日请求: ${summary.totalRequests}")
            appendLine("今日Token: ${summary.totalTokens}")
            appendLine("平均: ${summary.averageTokensPerRequest}/请求")
            appendLine("成功率: ${"%.1f".format(summary.successRate * 100)}%")
        }
    }

    /** 添加备用 API（用于故障转移）。 */
    fun addBackupApi(name: String, apiKey: String, baseUrl: String, model: String, priority: Int = 0) {
        healthMonitor.addBackup(ApiHealthMonitor.ApiBackup(name, apiKey, baseUrl, model, priority))
    }

    /** 清除所有备用 API。 */
    fun clearBackupApis() {
        healthMonitor.clearBackups()
    }

    // =========================================================================
    //  OkHttp Call 协程化扩展
    // =========================================================================

    /**
     * 将 OkHttp 的异步 Call 转为挂起函数，支持协程取消时自动取消网络请求。
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    companion object {
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        /** 默认保留的对话历史轮数。 */
        private const val DEFAULT_MAX_HISTORY_TURNS = 4

        /**
         * 创建默认的 OkHttp 客户端：较长超时以适应大模型响应延迟。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}


// =============================================================================
//  API 数据模型（OpenAI 兼容协议）
// =============================================================================

/**
 * AI 网关配置。
 *
 * @param apiKey 供应商 API Key
 * @param baseUrl 基础地址（如 `https://api.deepseek.com`），会自动拼接 `/v1/chat/completions`
 * @param model 模型名称
 * @param temperature 采样温度，越低越确定（操控指令建议 0.0~0.3）
 * @param maxTokens 最大生成 token 数，null 表示由服务端决定
 */
data class AIConfig(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Double = 0.2,
    val maxTokens: Int? = null
)

/**
 * Chat 消息。
 *
 * @param role 角色：system / user / assistant
 * @param content 消息文本内容
 */
@Serializable
data class ChatMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

/**
 * Chat Completions 请求体（OpenAI 兼容）。
 */
@Serializable
data class ChatCompletionRequest(
    @SerialName("model") val model: String,
    @SerialName("messages") val messages: List<ChatMessage>,
    @SerialName("stream") val stream: Boolean = false,
    @SerialName("temperature") val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** 思考模式推理参数（如 OpenAI o1 风格的 effort），仅 thinkingMode=true 时下发；null 时不序列化。 */
    @SerialName("reasoning") val reasoning: JsonElement? = null,
    /** 强制 JSON 输出格式，提高 AI 返回结构化指令的可靠性。 */
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

/** OpenAI 兼容的 response_format 参数。 */
@Serializable
data class ResponseFormat(
    @SerialName("type") val type: String = "json_object"
)

/**
 * Chat Completions 响应体（OpenAI 兼容）。
 * 流式与非流式共用：非流式时读取 [ChatChoice.message]，流式时读取 [ChatChoice.delta]。
 */
@Serializable
data class ChatCompletionResponse(
    @SerialName("id") val id: String = "",
    @SerialName("object") val objectType: String = "",
    @SerialName("model") val model: String = "",
    @SerialName("choices") val choices: List<ChatChoice> = emptyList(),
    @SerialName("usage") val usage: JsonElement? = null
)

/**
 * 单个候选结果。
 */
@Serializable
data class ChatChoice(
    @SerialName("index") val index: Int = 0,
    @SerialName("message") val message: ChatMessage? = null,
    @SerialName("delta") val delta: ChatDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

/**
 * 流式响应中的增量片段。
 */
@Serializable
data class ChatDelta(
    @SerialName("role") val role: String? = null,
    @SerialName("content") val content: String? = null
)
