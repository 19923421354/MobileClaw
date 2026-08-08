package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * TaskEvaluator - 任务评估器
 *
 * 核心理念：用一个独立的轻量模型先评估用户指令，再给出精确的 Token 预算建议。
 * 评估器自身消耗极少（输入约 50 token，输出约 30 token），但能为主模型节省数百 token。
 *
 * 两阶段流程：
 * 1. 评估阶段：将用户指令发送给评估模型，返回 JSON 格式的参数建议
 * 2. 执行阶段：主模型按评估结果配置参数执行
 *
 * 评估器配置独立于主模型：
 * - 可以用同一个 API 但不同模型（如主模型用 GLM-4.7，评估器用 GLM-4.7-Flash）
 * - 也可以用完全不同的 API（如主模型用 DeepSeek，评估器用豆包 lite）
 * - 支持任意 OpenAI 兼容接口：DeepSeek、豆包、通义千问、智谱GLM、GPT 等
 *
 * 容错策略：
 * - 评估器未配置 → 降级到 [TaskComplexityAnalyzer] 本地分析
 * - 评估器请求失败/超时 → 降级到本地分析
 * - 评估器返回格式异常 → 降级到本地分析
 *
 * @param client 共享的 OkHttp 客户端
 */
class TaskEvaluator(
    private val client: OkHttpClient = AIGateway.defaultClient()
) {

    private companion object {
        const val TAG = "TaskEvaluator"

        /** 评估器请求超时（毫秒），超时后降级到本地分析。 */
        const val EVALUATOR_TIMEOUT_MS = 3_000L

        /** 评估器自身的 maxTokens 限制（输出极少，仅需一个 JSON 对象）。 */
        const val EVALUATOR_MAX_TOKENS = 150

        /** 评估结果缓存最大条数。 */
        const val CACHE_MAX_SIZE = 20

        /** 评估结果缓存有效期（毫秒，5 分钟）。 */
        const val CACHE_TTL_MS = 5 * 60 * 1000L

        /** 评估器的系统提示词（极致精简，约 50 token）。 */
        const val EVALUATOR_PROMPT = """分析手机操控任务，返回JSON:{"steps":预估步数,"max_tokens":建议输出上限,"prompt_level":"ultra|compact|full","screen_text_limit":屏幕文本截断长度,"multi_step":是否多步}
规则:
- 单步操作(截图/按键/查看信息/打开单个应用)→steps:1,max_tokens:384,prompt_level:"ultra",screen_text_limit:100
- 2-5步(打开应用并搜索/清理缓存/查看应用信息)→steps:3,max_tokens:1024,prompt_level:"compact",screen_text_limit:400
- 6+步(发消息/多应用协作/文件操作/复杂设置)→steps:8,max_tokens:2048,prompt_level:"full",screen_text_limit:800
关键:打开应用+后续操作(搜索/输入/发送/点击)至少2步。发消息类至少4步。
限制:简单max_tokens<=512,中等<=1280,复杂<=2560。只返回JSON。"""

        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
    }

    /** 评估器配置（独立于主模型）。 */
    private val evaluatorConfigRef = AtomicReference<EvaluatorConfig?>(null)

    /** 评估结果缓存：指令 -> (评估结果, 时间戳)。避免短时间内重复评估相同指令。 */
    private val evalCache = java.util.LinkedHashMap<String, Pair<EvaluationResult, Long>>()

    /** 评估器是否已配置（apiKey、baseUrl、model 均非空）。 */
    fun isConfigured(): Boolean = evaluatorConfigRef.get()?.let { cfg ->
        cfg.apiKey.isNotBlank() && cfg.baseUrl.isNotBlank() && cfg.model.isNotBlank()
    } ?: false

    /**
     * 配置评估器。
     *
     * @param apiKey 评估模型的 API Key
     * @param baseUrl 评估模型的 Base URL
     * @param model 评估模型名称（如 deepseek-v4-flash、doubao-lite-32k、glm-4.7-flash 等）
     */
    fun configure(apiKey: String, baseUrl: String, model: String) {
        if (apiKey.isBlank() && baseUrl.isBlank() && model.isBlank()) {
            evaluatorConfigRef.set(null)
            Log.i(TAG, "评估器配置已清除")
            return
        }
        evaluatorConfigRef.set(
            EvaluatorConfig(
                apiKey = apiKey.trim(),
                baseUrl = baseUrl.trim().trimEnd('/'),
                model = model.trim()
            )
        )
        Log.i(TAG, "评估器已配置: $model @ $baseUrl")
    }

    /** 获取当前评估器配置快照。 */
    fun currentConfig(): EvaluatorConfig? = evaluatorConfigRef.get()

    /** 清除评估器配置。 */
    fun clearConfig() {
        evaluatorConfigRef.set(null)
    }

    /**
     * 评估用户指令，返回精确的 Token 预算建议。
     *
     * 流程：
     * 1. 检查评估器是否已配置，未配置则降级
     * 2. 检查缓存，命中则直接返回（避免短时间内重复评估）
     * 3. 构造极简评估请求（system ~40 token + user 指令）
     * 4. 调用评估模型，设置 3 秒超时
     * 5. 解析返回的 JSON 建议，钳位不合理参数
     * 6. 任何异常都降级到 [TaskComplexityAnalyzer]
     *
     * @param userInput 用户的自然语言指令
     * @return 评估结果（含建议参数），或 null 表示降级到本地分析
     */
    suspend fun evaluate(userInput: String): EvaluationResult? {
        val config = evaluatorConfigRef.get() ?: return null
        if (!isConfigured()) return null

        // 1. 检查缓存
        synchronized(evalCache) {
            val cached = evalCache[userInput]
            if (cached != null) {
                val (result, timestamp) = cached
                if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                    Log.d(TAG, "评估结果命中缓存: ${result.description()}")
                    return result
                }
                // 过期，移除
                evalCache.remove(userInput)
            }
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val messages = listOf(
                    ChatMessage(ROLE_SYSTEM, EVALUATOR_PROMPT),
                    ChatMessage(ROLE_USER, userInput)
                )
                val requestBody = ChatCompletionRequest(
                    model = config.model,
                    messages = messages,
                    stream = false,
                    temperature = 0.0,
                    maxTokens = EVALUATOR_MAX_TOKENS,
                    responseFormat = ResponseFormat("json_object")
                )
                val bodyStr = ActionTranslator.json.encodeToString(requestBody)
                val request = buildRequest(config, bodyStr)

                val response = withTimeoutOrNull(EVALUATOR_TIMEOUT_MS) {
                    executeCall(request)
                } ?: run {
                    Log.w(TAG, "评估器超时（${EVALUATOR_TIMEOUT_MS}ms）→ 降级")
                    return@withContext null
                }

                val result = response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "评估器请求失败: HTTP ${resp.code} → 降级")
                        return@use null
                    }
                    val raw = resp.body?.string().orEmpty()
                    val content = parseContent(raw)
                    parseEvaluation(content, userInput)
                }

                // 2. 缓存成功的评估结果
                if (result != null) {
                    synchronized(evalCache) {
                        evalCache[userInput] = Pair(result, System.currentTimeMillis())
                        // LRU 淘汰：超出容量时移除最旧的条目
                        while (evalCache.size > CACHE_MAX_SIZE) {
                            val oldestKey = evalCache.keys.iterator().next()
                            evalCache.remove(oldestKey)
                        }
                    }
                }

                result
            }.getOrElse { e ->
                Log.w(TAG, "评估器降级（${e.javaClass.simpleName}: ${e.message}）→ 使用本地分析")
                null
            }
        }
    }

    /** 执行 OkHttp 调用并返回 Response。 */
    private suspend fun executeCall(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    cont.resume(response)
                }
            })
            cont.invokeOnCancellation { runCatching { call.cancel() } }
        }

    /** 解析非流式响应内容。 */
    private fun parseContent(raw: String): String {
        val resp = ActionTranslator.json.decodeFromString(
            ChatCompletionResponse.serializer(), raw
        )
        return resp.choices.firstOrNull()?.message?.content
            ?: throw IOException("评估器响应无 content")
    }

    /**
     * 将评估模型返回的 JSON 解析为 [EvaluationResult]。
     *
     * 预期格式：
     * ```json
     * {"steps": 3, "max_tokens": 1024, "prompt_level": "compact", "screen_text_limit": 400, "multi_step": true}
     * ```
     *
     * 解析容错：
     * - prompt_level 不识别时根据 steps 推断
     * - max_tokens 超出合理范围时钳位
     * - screen_text_limit 缺失时根据 prompt_level 设置默认值
     */
    private fun parseEvaluation(content: String, userInput: String): EvaluationResult? {
        return runCatching {
            val jsonText = extractJson(content) ?: return null
            val obj = ActionTranslator.json.parseToJsonElement(jsonText).jsonObject

            val steps = obj["steps"]?.jsonPrimitive?.intOrNull ?: 1
            val maxTokens = obj["max_tokens"]?.jsonPrimitive?.intOrNull ?: clampMaxTokens(steps)
            val promptLevelStr = obj["prompt_level"]?.jsonPrimitive?.contentOrNull
                ?: inferPromptLevel(steps).name
            val screenTextLimit = obj["screen_text_limit"]?.jsonPrimitive?.intOrNull
                ?: inferScreenTextLimit(promptLevelStr)
            val multiStep = obj["multi_step"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                ?: (steps > 1)
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: ""

            val promptLevel = runCatching {
                TaskComplexityAnalyzer.PromptLevel.valueOf(promptLevelStr.uppercase())
            }.getOrElse { inferPromptLevel(steps) }

            // 置信度钳位：根据步数限制 maxTokens 的合理范围，防止评估器给出明显不合理的值
            val clampedMaxTokens = when {
                steps <= 1 -> maxTokens.coerceIn(128, 512)        // 单步任务最多 512
                steps <= 5 -> maxTokens.coerceIn(256, 1280)       // 中等任务最多 1280
                else -> maxTokens.coerceIn(512, 2560)             // 复杂任务最多 2560
            }

            EvaluationResult(
                estimatedSteps = steps.coerceIn(1, 20),
                maxTokens = clampedMaxTokens,
                promptLevel = promptLevel,
                screenTextLimit = screenTextLimit.coerceIn(0, 2000),
                isMultiStep = multiStep,
                reason = reason,
                source = EvaluationSource.MODEL,
                originalInput = userInput
            )
        }.getOrElse { e ->
            Log.w(TAG, "评估结果解析失败: ${e.message}")
            null
        }
    }

    /** 从可能包含多余文本的响应中提取 JSON。 */
    private fun extractJson(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val fencePattern = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fencePattern.find(text)?.let { match ->
            val inner = match.groupValues[1].trim()
            if (inner.startsWith("{")) return inner
        }

        if (text.startsWith("{")) return text

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    // ---- 参数推断兜底 ----

    private fun clampMaxTokens(steps: Int): Int = when {
        steps <= 1 -> 256
        steps <= 5 -> 1024
        else -> 2048
    }

    private fun inferPromptLevel(steps: Int): TaskComplexityAnalyzer.PromptLevel = when {
        steps <= 1 -> TaskComplexityAnalyzer.PromptLevel.ULTRA
        steps <= 5 -> TaskComplexityAnalyzer.PromptLevel.COMPACT
        else -> TaskComplexityAnalyzer.PromptLevel.FULL
    }

    private fun inferScreenTextLimit(level: String): Int = when (level.uppercase()) {
        "ULTRA" -> 100
        "COMPACT" -> 400
        "FULL" -> 800
        else -> 400
    }

    /** 构建 OkHttp Request。 */
    private fun buildRequest(config: EvaluatorConfig, bodyStr: String): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = bodyStr.toRequestBody(mediaType)
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
            .post(body)
            .build()
    }
}

// =============================================================================
//  数据模型
// =============================================================================

/**
 * 评估器配置（独立于主模型配置）。
 *
 * @param apiKey 评估模型的 API Key
 * @param baseUrl 评估模型的 Base URL
 * @param model 评估模型名称
 */
data class EvaluatorConfig(
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = ""
)

/**
 * 任务评估结果 —— 评估模型给出的精确 Token 预算建议。
 *
 * 与 [TaskComplexityAnalyzer.Complexity] 的固定参数不同，
 * EvaluationResult 携带评估模型给出的精确数值，不限于 3 个固定等级。
 *
 * @param estimatedSteps 预估操作步数
 * @param maxTokens 建议的最大输出 Token 数（精确值，如 256/768/1500 等）
 * @param promptLevel 建议的提示词级别
 * @param screenTextLimit 建议的屏幕文本截断长度（精确值）
 * @param isMultiStep 是否为多步任务
 * @param reason 评估理由（可选，用于日志/UI 展示）
 * @param source 评估来源（模型评估 / 本地分析）
 * @param originalInput 原始用户指令
 */
data class EvaluationResult(
    val estimatedSteps: Int,
    val maxTokens: Int,
    val promptLevel: TaskComplexityAnalyzer.PromptLevel,
    val screenTextLimit: Int,
    val isMultiStep: Boolean,
    val reason: String = "",
    val source: EvaluationSource = EvaluationSource.MODEL,
    val originalInput: String = ""
) {
    /**
     * 根据评估结果推导出历史轮数。
     * - 1步：0轮（无需历史）
     * - 2-5步：1轮
     * - 6+步：3轮
     */
    val historyTurns: Int
        get() = when {
            estimatedSteps <= 1 -> 0
            estimatedSteps <= 5 -> 1
            else -> 3
        }

    /** 可读描述，用于 UI 展示。 */
    fun description(): String = buildString {
        append("预估${estimatedSteps}步 | ")
        append("Token上限${maxTokens} | ")
        append(promptLevel.name)
        if (reason.isNotBlank()) append(" | $reason")
    }

    /** 简短标签，用于 UI 消息前缀。 */
    fun shortTag(): String = when (source) {
        EvaluationSource.MODEL -> "[AI评估:${estimatedSteps}步] "
        EvaluationSource.LOCAL -> "[${promptLevel.name}] "
    }
}

/**
 * 评估来源。
 * - MODEL：由评估模型分析得出（精确参数）
 * - LOCAL：由本地 [TaskComplexityAnalyzer] 分析得出（降级场景）
 */
enum class EvaluationSource {
    MODEL,
    LOCAL
}
