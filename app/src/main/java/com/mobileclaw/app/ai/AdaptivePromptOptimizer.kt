package com.mobileclaw.app.ai

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * AdaptivePromptOptimizer - 自适应提示词优化器
 *
 * 核心理念：固定的系统提示词无法适应所有任务场景。本优化器持续追踪 AI 响应的
 * 质量指标（解析成功率、动作相关性、冗余度、幻觉率），并据此动态增删提示词中
 * 的引导片段，让提示词随使用不断进化，从而在 Token 消耗与执行质量之间取得平衡。
 *
 * 五大能力：
 * 1. 响应质量分析 —— 解析 AI 返回，量化解析成功率、动作数量、相关性、幻觉
 * 2. 动态冗余调节 —— 动作持续偏多则追加「精简」指令，偏少则追加「分步」指令
 * 3. 特性关联追踪 —— 记录每个提示词特性（示例/包名/记忆等）与成功率的关联
 * 4. A/B 测试 —— 并行尝试多个提示词变体，累计足够样本后选出最优
 * 5. 自动熔断 —— 成功率持续低于阈值的特性自动禁用，避免拖累整体表现
 *
 * 工作流程：
 * ```
 * val optimizer = AdaptivePromptOptimizer()
 * // 1. 优化提示词（内部自动选取 A/B 变体）
 * val result = optimizer.optimizePrompt(basePrompt, Complexity.MEDIUM)
 * // 2. 将 result.prompt 发送给 AI，拿到响应后分析质量
 * val quality = optimizer.analyzeResponseQuality(aiResponse, "打开微信发消息")
 * // 3. 任务执行完毕后记录结果，反馈给优化器
 * optimizer.recordResult(optimizer.currentVariantId()!!, execSuccess, quality)
 * // 4. 查询当前最优变体 / 统计摘要
 * val best = optimizer.getBestVariant()
 * val stats = optimizer.getOptimizationStats()
 * ```
 *
 * 线程安全：所有可变状态通过 [ConcurrentHashMap] 与 `synchronized` 块保护，
 * 可在多协程环境下安全调用。
 */
class AdaptivePromptOptimizer {

    // =========================================================================
    //  枚举定义
    // =========================================================================

    /**
     * 冗余度等级 —— 描述 AI 返回动作数量相对任务需求的偏向。
     *
     * - [TOO_FEW]：动作过少，可能遗漏必要步骤
     * - [OPTIMAL]：动作数量适中
     * - [TOO_MANY]：动作过多，存在冗余或重复步骤
     */
    enum class VerbosityLevel {
        TOO_FEW,
        OPTIMAL,
        TOO_MANY
    }

    /**
     * 可动态启停的提示词特性。
     *
     * 每个特性对应一段追加到基础提示词的条件片段，可独立追踪成功率并自动熔断。
     *
     * - [EXAMPLE_GUIDANCE]：JSON 格式示例引导
     * - [SCREEN_TEXT_HINT]：屏幕文本匹配提示
     * - [APP_PACKAGES]：常用应用包名参考
     * - [MEMORY_CONTEXT]：对话记忆上下文
     * - [EXPERIENCE_HINT]：历史经验提示
     * - [COMPLEXITY_HINT]：复杂度分步提示
     */
    enum class PromptFeature {
        EXAMPLE_GUIDANCE,
        SCREEN_TEXT_HINT,
        APP_PACKAGES,
        MEMORY_CONTEXT,
        EXPERIENCE_HINT,
        COMPLEXITY_HINT
    }

    // =========================================================================
    //  数据模型
    // =========================================================================

    /**
     * 提示词变体 —— A/B 测试的最小单元。
     *
     * 一个变体是若干提示词附加片段的组合，拥有独立的成功率与样本统计。
     * 当样本数达到 [MIN_SAMPLES_FOR_WINNER] 后，方可参与「最优变体」判定。
     *
     * @param id          变体唯一标识
     * @param additions   该变体追加到基础提示词的条件片段列表
     * @param description 人类可读的变体描述
     * @param successRate 历史成功率（0~1）
     * @param sampleCount 已累计的样本数
     * @param enabled     是否启用（被熔断或手动停用时为 false）
     */
    data class PromptVariant(
        val id: String,
        val additions: List<String>,
        val description: String,
        val successRate: Float = 0f,
        val sampleCount: Int = 0,
        val enabled: Boolean = true
    )

    /**
     * 响应质量评估结果。
     *
     * 由 [analyzeResponseQuality] 产出，量化单次 AI 响应的各项质量指标。
     *
     * @param parsed          是否成功解析为结构化动作（含可识别的 JSON 与有效动作）
     * @param relevantActions 与预期结果相关的动作数量
     * @param actionCount     解析得到的有效动作总数
     * @param hallucinated    是否出现幻觉（声称完成但无真实操作动作）
     * @param parseErrors     解析失败的动作条目数（0 表示全部解析成功）
     * @param verbosity       冗余度等级
     */
    data class ResponseQuality(
        val parsed: Boolean,
        val relevantActions: Int,
        val actionCount: Int,
        val hallucinated: Boolean,
        val parseErrors: Int,
        val verbosity: VerbosityLevel
    ) {
        /** 动作相关性比例（0~1），动作数为 0 时记为 0。 */
        val relevanceRatio: Float
            get() = if (actionCount > 0) relevantActions.toFloat() / actionCount else 0f

        /** 是否为高质量响应：已解析、无幻觉、无解析错误且冗余度适中。 */
        val isHighQuality: Boolean
            get() = parsed && !hallucinated && parseErrors == 0 && verbosity == VerbosityLevel.OPTIMAL
    }

    /**
     * 质量趋势 —— 基于近期成功率窗口对比得出的走向判断。
     *
     * @param improving 近期成功率是否显著上升
     * @param stable    近期成功率是否保持平稳
     * @param samples   参与趋势计算的样本数
     */
    data class QualityTrend(
        val improving: Boolean,
        val stable: Boolean,
        val samples: Int
    )

    /**
     * 提示词优化结果。
     *
     * 由 [optimizePrompt] 产出，包含最终发送给 AI 的完整提示词、追加的片段列表
     * 以及本次优化的决策理由。
     *
     * @param prompt    优化后的完整提示词（基础提示词 + 追加片段）
     * @param additions 实际追加的条件片段列表（已过滤被熔断的特性）
     * @param reason    决策理由，用于日志与 UI 展示
     */
    data class PromptOptimizationResult(
        val prompt: String,
        val additions: List<String>,
        val reason: String
    )

    // =========================================================================
    //  内部统计模型（不对外暴露）
    // =========================================================================

    /** 变体静态定义（不含运行时统计）。 */
    private data class VariantDefinition(
        val id: String,
        val additions: List<String>,
        val description: String,
        val features: Set<PromptFeature>
    )

    /** 变体运行时统计（不可变，通过 [ConcurrentHashMap.compute] 原子更新）。 */
    private data class VariantStats(
        val successCount: Int = 0,
        val totalCount: Int = 0,
        val enabled: Boolean = true
    ) {
        val successRate: Float
            get() = if (totalCount > 0) successCount.toFloat() / totalCount else 0f
    }

    /** 特性运行时统计。 */
    private data class FeatureStats(
        val successCount: Int = 0,
        val totalCount: Int = 0,
        val enabled: Boolean = true,
        val manuallyDisabled: Boolean = false
    ) {
        val successRate: Float
            get() = if (totalCount > 0) successCount.toFloat() / totalCount else 0f
    }

    private companion object {
        const val TAG = "AdaptivePromptOptimizer"

        /** 基线变体标识（无任何附加片段）。 */
        const val BASELINE_ID = "baseline"

        /** A/B 测试中每个变体的最小样本数，达到后才参与优胜判定。 */
        const val MIN_SAMPLES_FOR_WINNER = 10

        /** 特性自动熔断的最小样本数。 */
        const val MIN_SAMPLES_FOR_DISABLE = 20

        /** 特性自动熔断的成功率阈值（低于此值则禁用）。 */
        const val DISABLE_SUCCESS_RATE_THRESHOLD = 0.5f

        /** 冗余度滑动窗口大小。 */
        const val VERBOSITY_WINDOW_SIZE = 15

        /** 质量趋势对比的样本窗口大小。 */
        const val TREND_WINDOW_SIZE = 20

        /** 触发「精简」指令的 TOO_MANY 占比阈值。 */
        const val TOO_MANY_RATIO_THRESHOLD = 0.4f

        /** 触发「分步」指令的 TOO_FEW 占比阈值。 */
        const val TOO_FEW_RATIO_THRESHOLD = 0.4f

        /** 质量趋势判定的显著变化区间。 */
        const val TREND_EPSILON = 0.05f

        /** 触发动态冗余调节所需的最小冗余度样本数。 */
        const val MIN_VERBOSITY_SAMPLES = 5
    }

    // =========================================================================
    //  特性 -> 追加片段文案
    // =========================================================================

    /** 各特性对应的追加提示词片段。 */
    private val featureAdditions: Map<PromptFeature, String> = mapOf(
        PromptFeature.EXAMPLE_GUIDANCE to
            "请严格按以下JSON格式返回：{\"actions\":[{\"action\":\"动作名\",\"params\":{...},\"description\":\"说明\"}]}，不要添加额外解释文本。",
        PromptFeature.SCREEN_TEXT_HINT to
            "请优先根据屏幕可见文本选择点击目标，确保参数中的文本与屏幕文本精确匹配。",
        PromptFeature.APP_PACKAGES to
            "常用应用包名参考：微信=com.tencent.mm，抖音=com.ss.android.ugc.aweme，支付宝=com.eg.android.AlipayGphone，QQ=com.tencent.mobileqq，淘宝=com.taobao.taobao。",
        PromptFeature.MEMORY_CONTEXT to
            "请结合最近的对话与操作历史保持连贯，避免重复已完成的步骤。",
        PromptFeature.EXPERIENCE_HINT to
            "根据历史经验，类似任务通常需要先打开目标应用，再执行搜索或输入等后续操作。",
        PromptFeature.COMPLEXITY_HINT to
            "请根据任务复杂度合理规划步骤：简单任务1步完成，复杂任务按逻辑顺序分解为多个动作。"
    )

    /** 动态冗余调节：动作过多时追加的精简指令。 */
    private val conciseAddition =
        "注意：请精简动作，只返回完成任务的必要操作，避免冗余或重复步骤。"

    /** 动态冗余调节：动作过少时追加的分步指令。 */
    private val breakDownAddition =
        "注意：请将任务分解为清晰的操作步骤，每一步对应一个独立动作，确保流程完整。"

    /** 幻觉检测：声称任务完成的关键词。 */
    private val completionPhrases = listOf(
        "已完成", "已经完成", "任务完成", "操作完成", "已执行", "已成功", "完成了",
        "已搞定", "搞定了", "done", "completed", "success"
    )

    // =========================================================================
    //  运行时状态
    // =========================================================================

    /** 变体定义表（id -> 定义）。 */
    private val definitions = ConcurrentHashMap<String, VariantDefinition>()

    /** 变体统计表（id -> 统计）。 */
    private val stats = ConcurrentHashMap<String, VariantStats>()

    /** 特性统计表（feature -> 统计）。 */
    private val featureStats = ConcurrentHashMap<PromptFeature, FeatureStats>()

    /** 近期冗余度滑动窗口。 */
    private val recentVerbosity = ArrayDeque<VerbosityLevel>()

    /** 近期任务成功与否滑动窗口（用于质量趋势）。 */
    private val recentResults = ArrayDeque<Boolean>()

    /** 最近一次 [optimizePrompt] 选中的变体标识（供 [recordResult] 使用）。 */
    @Volatile
    private var lastVariantId: String? = null

    init {
        initDefaultVariants()
    }

    /** 初始化默认的 A/B 测试变体集合。 */
    private fun initDefaultVariants() {
        registerVariant(BASELINE_ID, emptySet(), "基线（无附加片段）")
        registerVariant("examples", setOf(PromptFeature.EXAMPLE_GUIDANCE), "示例引导")
        registerVariant(
            "context",
            setOf(PromptFeature.SCREEN_TEXT_HINT, PromptFeature.APP_PACKAGES),
            "屏幕文本+包名"
        )
        registerVariant(
            "memory",
            setOf(PromptFeature.MEMORY_CONTEXT, PromptFeature.EXPERIENCE_HINT),
            "记忆+经验"
        )
        registerVariant("full", PromptFeature.entries.toSet(), "全特性")
    }

    /**
     * 注册一个自定义提示词变体。
     *
     * @param id          变体唯一标识（已存在则注册失败）
     * @param features    该变体包含的特性集合
     * @param description 变体描述
     * @return 是否注册成功（id 重复时返回 false）
     */
    fun registerVariant(id: String, features: Set<PromptFeature>, description: String): Boolean {
        if (definitions.containsKey(id)) {
            Log.w(TAG, "变体 $id 已存在，注册失败")
            return false
        }
        val additions = features.mapNotNull { featureAdditions[it] }
        definitions[id] = VariantDefinition(id, additions, description, features)
        stats[id] = VariantStats()
        Log.i(TAG, "注册变体 $id（特性=${features.joinToString(",")}}）")
        return true
    }

    // =========================================================================
    //  核心方法：响应质量分析
    // =========================================================================

    /**
     * 分析单次 AI 响应的质量。
     *
     * 解析流程：
     * 1. 从原始响应中提取 JSON 块，判定是否可解析
     * 2. 委托 [ActionTranslator.parse] 解析为结构化动作，统计有效动作数
     * 3. 比对原始动作条目数与有效动作数，得出解析错误数
     * 4. 检测幻觉：响应声称完成但无真实操作动作
     * 5. 评估相关性：动作描述/参数是否涉及预期结果的关键词
     * 6. 判定冗余度：动作数量相对预期步数区间的偏向
     *
     * 该方法为纯查询，不修改优化器内部状态；状态更新由 [recordResult] 完成。
     *
     * @param aiResponse    AI 返回的原始文本
     * @param expectedResult 预期结果的自然语言描述（用于评估相关性与预期步数）
     * @return 响应质量评估结果
     */
    fun analyzeResponseQuality(aiResponse: String, expectedResult: String): ResponseQuality {
        val raw = aiResponse.trim()

        // 1. 提取 JSON 块并解析
        val jsonText = extractJsonBlock(raw)
        val jsonExtracted = jsonText.isNotBlank()
        val commandResult = runCatching { ActionTranslator.parse(raw) }.getOrNull()
        val actions = commandResult?.actions ?: emptyList()
        val validCount = actions.size
        val parsed = jsonExtracted && validCount > 0

        // 2. 解析错误数：原始条目数 - 有效动作数
        val rawEntryCount = countRawActionEntries(jsonText)
        val parseErrors = when {
            !jsonExtracted && raw.isNotEmpty() -> 1
            rawEntryCount > validCount -> rawEntryCount - validCount
            else -> 0
        }

        // 3. 幻觉检测：声称完成但无真实操作动作（ANSWER 不算真实操作）
        val hallucinated = detectHallucination(raw, actions)

        // 4. 相关性评估
        val relevantActions = countRelevantActions(actions, expectedResult)

        // 5. 冗余度判定
        val expectedRange = estimateExpectedActionRange(expectedResult)
        val verbosity = when {
            validCount == 0 -> VerbosityLevel.TOO_FEW
            validCount < expectedRange.first -> VerbosityLevel.TOO_FEW
            validCount > expectedRange.last -> VerbosityLevel.TOO_MANY
            else -> VerbosityLevel.OPTIMAL
        }

        return ResponseQuality(
            parsed = parsed,
            relevantActions = relevantActions,
            actionCount = validCount,
            hallucinated = hallucinated,
            parseErrors = parseErrors,
            verbosity = verbosity
        )
    }

    /**
     * 检测幻觉：响应中出现完成类措辞，但没有任何真实操作动作。
     *
     * @param raw     AI 原始响应
     * @param actions 已解析的有效动作
     * @return 是否判定为幻觉
     */
    private fun detectHallucination(raw: String, actions: List<ClawAction>): Boolean {
        val lower = raw.lowercase()
        val claimsCompletion = completionPhrases.any { lower.contains(it.lowercase()) }
        val hasRealAction = actions.any { it.type != null && it.type != ActionType.ANSWER }
        return claimsCompletion && !hasRealAction
    }

    /**
     * 统计与预期结果相关的动作数量。
     *
     * 从预期结果中提取关键词（含中文二元组），检查每个动作的描述、参数文本、
     * 包名与动作名是否命中关键词。预期结果为空时无法判定，假定全部相关。
     */
    private fun countRelevantActions(actions: List<ClawAction>, expectedResult: String): Int {
        if (expectedResult.isBlank()) return actions.size
        val keywords = extractKeywords(expectedResult)
        if (keywords.isEmpty()) return actions.size
        return actions.count { action ->
            val text = buildString {
                append(action.description).append(' ')
                append(action.text ?: "").append(' ')
                append(action.packageName ?: "").append(' ')
                append(action.name ?: "").append(' ')
                append(action.actionName)
            }.lowercase()
            keywords.any { kw -> text.contains(kw) }
        }
    }

    /**
     * 从预期结果文本中提取关键词。
     *
     * 策略：按标点/空白分词后保留长度≥2的词，并补充中文二元组，
     * 过滤常见停用词，最终去重。
     */
    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "和", "与", "或",
            "把", "被", "给", "向", "到", "从", "对", "于", "为", "请", "帮", "一", "下",
            "个", "这", "那", "要", "会", "能", "就", "都", "也", "还", "又", "才", "再",
            "然后", "之后", "一下", "帮我", "麻烦", "可以", "需要"
        )
        val cleaned = text.replace(Regex("[\\s，。！？,.!?;:\"'、（）()【】\\[\\]]+"), " ")
            .trim().lowercase()
        if (cleaned.isEmpty()) return emptyList()
        val tokens = cleaned.split(" ").filter { it.length >= 2 }
        val keywords = mutableListOf<String>()
        for (tok in tokens) {
            if (tok in stopWords) continue
            keywords.add(tok)
            // 中文二元组补充，提升短词命中率
            if (tok.length > 2) {
                for (i in 0..tok.length - 2) {
                    val gram = tok.substring(i, i + 2)
                    if (gram !in stopWords) keywords.add(gram)
                }
            }
        }
        return keywords.distinct()
    }

    /**
     * 根据预期结果文本估算合理的动作数量区间。
     *
     * - 含多动词/复合操作 → 复杂任务（6~15 步）
     * - 含搜索/输入/滑动等 → 中等任务（2~5 步）
     * - 其余 → 简单/微操作（1~2 步）
     */
    private fun estimateExpectedActionRange(expected: String): IntRange {
        val s = expected.trim().lowercase()
        if (s.isEmpty()) return 1..8 // 未知时取宽容区间
        // 复杂任务判定：出现多个操作动词或典型复合模式
        val complexMarkers = listOf("发", "消息", "搜索", "然后", "再", "并", "之后",
            "复制", "分享", "转发", "添加", "删除", "给")
        val mediumMarkers = listOf("搜索", "输入", "滑动", "滚动", "安装", "卸载",
            "下载", "播放", "保存", "截图")
        val complexHits = complexMarkers.count { s.contains(it) }
        if (complexHits >= 2 || (s.contains("发") && s.contains("消息"))) return 6..15
        if (mediumMarkers.any { s.contains(it) }) return 2..5
        return 1..2
    }

    /** 从可能包含代码块或多余文本的响应中提取 JSON 块。 */
    private fun extractJsonBlock(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fence.find(text)?.let { match ->
            val inner = match.groupValues[1].trim()
            if (inner.startsWith("{")) return inner
        }
        if (text.startsWith("{")) return text
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else ""
    }

    /** 统计 JSON 块中 "action" 字段出现的次数（用于推算原始动作条目数）。 */
    private fun countRawActionEntries(jsonText: String): Int {
        if (jsonText.isBlank()) return 0
        return Regex("\"action\"\\s*:").findAll(jsonText).count()
    }

    // =========================================================================
    //  核心方法：提示词优化
    // =========================================================================

    /**
     * 根据任务复杂度优化基础提示词。
     *
     * 决策流程：
     * 1. 选取 A/B 变体（探索采样不足的变体，否则按复杂度加权选取最优）
     * 2. 追加该变体包含且未被熔断的特性片段
     * 3. 基于近期冗余度窗口动态追加「精简」或「分步」指令
     * 4. 拼接生成最终提示词，并记录选中变体供 [recordResult] 使用
     *
     * @param basePrompt 基础系统提示词
     * @param complexity 任务复杂度等级（影响变体加权与决策理由）
     * @return 优化结果（含完整提示词、追加片段、决策理由）
     */
    fun optimizePrompt(
        basePrompt: String,
        complexity: TaskComplexityAnalyzer.Complexity
    ): PromptOptimizationResult {
        val variant = selectVariant(complexity)
        lastVariantId = variant.id

        val features = definitions[variant.id]?.features ?: emptySet()
        val additions = mutableListOf<String>()

        // 追加启用的特性片段
        for (feature in features) {
            if (shouldFeatureBeEnabled(feature)) {
                featureAdditions[feature]?.let { additions.add(it) }
            }
        }

        // 动态冗余调节
        val verbosityAddition = buildVerbosityAddition()
        if (verbosityAddition != null) {
            additions.add(verbosityAddition)
        }

        val prompt = if (additions.isEmpty()) {
            basePrompt
        } else {
            val separator = if (basePrompt.endsWith("\n")) "" else "\n"
            basePrompt + separator + "\n" + additions.joinToString("\n")
        }

        val reason = buildOptimizationReason(variant, complexity, verbosityAddition)
        Log.d(TAG, "optimizePrompt: 变体=${variant.id} 追加片段=${additions.size} 复杂度=${complexity.name}")

        return PromptOptimizationResult(
            prompt = prompt,
            additions = additions,
            reason = reason
        )
    }

    /**
     * 选取下一个用于 A/B 测试的变体。
     *
     * 策略：
     * - 若存在样本数不足 [MIN_SAMPLES_FOR_WINNER] 的启用变体，优先采样样本最少者（探索）
     * - 否则按复杂度加权得分选取最高者（利用）
     * - 全部禁用时回退到基线变体
     */
    private fun selectVariant(complexity: TaskComplexityAnalyzer.Complexity): PromptVariant {
        val enabled = definitions.keys.mapNotNull { toVariantSnapshot(it) }.filter { it.enabled }
        if (enabled.isEmpty()) {
            return toVariantSnapshot(BASELINE_ID) ?: PromptVariant(
                id = BASELINE_ID, additions = emptyList(), description = "基线"
            )
        }
        // 探索：优先采样不足的变体
        val underSampled = enabled.filter { it.sampleCount < MIN_SAMPLES_FOR_WINNER }
        if (underSampled.isNotEmpty()) {
            return underSampled.minByOrNull { it.sampleCount }!!
        }
        // 利用：按复杂度加权得分选取最优
        return enabled.maxByOrNull { scoreVariant(it, complexity) }!!
    }

    /**
     * 计算变体在指定复杂度下的综合得分。
     *
     * 基础分为历史成功率，叠加复杂度相关的特性偏好加权，
     * 使简单任务倾向轻量变体、复杂任务倾向经验与示例引导。
     */
    private fun scoreVariant(
        variant: PromptVariant,
        complexity: TaskComplexityAnalyzer.Complexity
    ): Float {
        var score = variant.successRate
        val features = definitions[variant.id]?.features ?: emptySet()
        when (complexity.level) {
            // 微操作/简单：偏好少量附加片段以节省 Token
            TaskComplexityAnalyzer.Complexity.MICRO.level,
            TaskComplexityAnalyzer.Complexity.SIMPLE.level -> {
                score += (1f - features.size.toFloat() / PromptFeature.entries.size) * 0.1f
            }
            // 中等：偏好示例引导与应用包名
            TaskComplexityAnalyzer.Complexity.MEDIUM.level -> {
                if (PromptFeature.EXAMPLE_GUIDANCE in features) score += 0.05f
                if (PromptFeature.APP_PACKAGES in features) score += 0.05f
            }
            // 复杂/无限制：偏好经验提示与复杂度提示
            else -> {
                if (PromptFeature.EXPERIENCE_HINT in features) score += 0.05f
                if (PromptFeature.COMPLEXITY_HINT in features) score += 0.05f
                if (PromptFeature.EXAMPLE_GUIDANCE in features) score += 0.03f
            }
        }
        return score
    }

    /**
     * 基于近期冗余度窗口生成动态调节片段。
     *
     * - TOO_MANY 占比超过阈值 → 追加精简指令
     * - TOO_FEW 占比超过阈值 → 追加分步指令
     * - 样本不足或冗余度健康 → 返回 null（不追加）
     */
    private fun buildVerbosityAddition(): String? {
        val window = synchronized(recentVerbosity) { recentVerbosity.toList() }
        if (window.size < MIN_VERBOSITY_SAMPLES) return null
        val tooMany = window.count { it == VerbosityLevel.TOO_MANY }
        val tooFew = window.count { it == VerbosityLevel.TOO_FEW }
        val tooManyRatio = tooMany.toFloat() / window.size
        val tooFewRatio = tooFew.toFloat() / window.size
        return when {
            tooManyRatio >= TOO_MANY_RATIO_THRESHOLD -> conciseAddition
            tooFewRatio >= TOO_FEW_RATIO_THRESHOLD -> breakDownAddition
            else -> null
        }
    }

    /** 构建本次优化的决策理由（用于日志与 UI 展示）。 */
    private fun buildOptimizationReason(
        variant: PromptVariant,
        complexity: TaskComplexityAnalyzer.Complexity,
        verbosityAddition: String?
    ): String {
        val parts = mutableListOf<String>()
        parts.add("变体[${variant.id}](${variant.description})")
        parts.add("复杂度=${complexity.name}")
        if (variant.sampleCount >= MIN_SAMPLES_FOR_WINNER) {
            parts.add("成功率=%.1f%%(n=%d)".format(variant.successRate * 100, variant.sampleCount))
        } else {
            parts.add("探索采样中(n=%d/<%d)".format(variant.sampleCount, MIN_SAMPLES_FOR_WINNER))
        }
        if (verbosityAddition != null) {
            val tag = when (verbosityAddition) {
                conciseAddition -> "精简"
                breakDownAddition -> "分步"
                else -> "调节"
            }
            parts.add("冗余度调节:$tag")
        }
        val trend = getQualityTrend()
        val trendDesc = when {
            trend.improving -> "趋势:上升"
            trend.stable -> "趋势:平稳"
            else -> "趋势:下降"
        }
        parts.add(trendDesc)
        return parts.joinToString(" | ")
    }

    // =========================================================================
    //  核心方法：结果记录与统计
    // =========================================================================

    /**
     * 记录一次任务执行结果，更新变体与特性统计。
     *
     * 更新内容：
     * 1. 变体成功率与样本数（累加更新）
     * 2. 变体所含特性的成功率统计，并尝试自动熔断
     * 3. 冗余度滑动窗口
     * 4. 质量趋势滑动窗口
     *
     * @param variantId 本次使用的变体标识（通常由 [currentVariantId] 取得）
     * @param success   本次任务是否成功
     * @param quality   对应的响应质量评估
     */
    fun recordResult(variantId: String, success: Boolean, quality: ResponseQuality) {
        // 1. 更新变体统计
        stats.compute(variantId) { _, s ->
            val cur = s ?: VariantStats()
            cur.copy(
                successCount = cur.successCount + if (success) 1 else 0,
                totalCount = cur.totalCount + 1
            )
        }

        // 2. 更新特性统计并尝试熔断
        val features = definitions[variantId]?.features ?: emptySet()
        for (feature in features) {
            featureStats.compute(feature) { _, fs ->
                val cur = fs ?: FeatureStats()
                cur.copy(
                    successCount = cur.successCount + if (success) 1 else 0,
                    totalCount = cur.totalCount + 1
                )
            }
            maybeAutoDisableFeature(feature)
        }

        // 3. 更新冗余度窗口
        synchronized(recentVerbosity) {
            if (recentVerbosity.size >= VERBOSITY_WINDOW_SIZE) recentVerbosity.removeFirst()
            recentVerbosity.addLast(quality.verbosity)
        }

        // 4. 更新质量趋势窗口
        synchronized(recentResults) {
            if (recentResults.size >= TREND_WINDOW_SIZE) recentResults.removeFirst()
            recentResults.addLast(success)
        }

        Log.d(TAG, "recordResult: variant=$variantId success=$success " +
            "verbosity=${quality.verbosity} hallucinated=${quality.hallucinated} " +
            "parsed=${quality.parsed} errors=${quality.parseErrors}")
    }

    /**
     * 尝试自动熔断特性：样本数达标且成功率低于阈值时禁用。
     *
     * 已手动禁用的特性不会被此机制重新启用。
     */
    private fun maybeAutoDisableFeature(feature: PromptFeature) {
        featureStats.compute(feature) { _, fs ->
            val cur = fs ?: return@compute null
            if (!cur.manuallyDisabled && cur.enabled &&
                cur.totalCount >= MIN_SAMPLES_FOR_DISABLE &&
                cur.successRate < DISABLE_SUCCESS_RATE_THRESHOLD
            ) {
                Log.w(TAG, "特性 ${feature.name} 自动禁用" +
                    "（成功率=%.1f%%, 样本=%d）".format(cur.successRate * 100, cur.totalCount))
                cur.copy(enabled = false)
            } else {
                cur
            }
        }
    }

    /**
     * 获取当前最优变体。
     *
     * 仅在样本数达到 [MIN_SAMPLES_FOR_WINNER] 的启用变体中选取成功率最高者；
     * 若无变体达到样本阈值，返回 null。
     *
     * @return 最优变体，或 null
     */
    fun getBestVariant(): PromptVariant? {
        return definitions.keys.mapNotNull { toVariantSnapshot(it) }
            .filter { it.enabled && it.sampleCount >= MIN_SAMPLES_FOR_WINNER }
            .maxByOrNull { it.successRate }
    }

    /**
     * 判断指定特性当前是否应启用。
     *
     * 判定规则：
     * - 手动禁用 → false
     * - 已被自动熔断 → false
     * - 样本数达标且成功率低于阈值 → false
     * - 其余 → true
     *
     * @param feature 待判定的特性
     * @return 是否应启用
     */
    fun shouldFeatureBeEnabled(feature: PromptFeature): Boolean {
        val fs = featureStats[feature] ?: return true
        if (fs.manuallyDisabled) return false
        if (!fs.enabled) return false
        if (fs.totalCount >= MIN_SAMPLES_FOR_DISABLE &&
            fs.successRate < DISABLE_SUCCESS_RATE_THRESHOLD
        ) {
            return false
        }
        return true
    }

    /**
     * 获取质量趋势。
     *
     * 将近期成功率窗口对半分为「较早」与「较新」两段，对比成功率变化：
     * - 较新显著高于较早 → improving
     * - 变化在 [TREND_EPSILON] 内 → stable
     * - 否则 → 既不 improving 也不 stable（下降）
     *
     * @return 质量趋势
     */
    fun getQualityTrend(): QualityTrend {
        val window = synchronized(recentResults) { recentResults.toList() }
        if (window.size < 4) {
            return QualityTrend(improving = false, stable = true, samples = window.size)
        }
        val mid = window.size / 2
        val older = window.subList(0, mid)
        val newer = window.subList(mid, window.size)
        val olderRate = if (older.isNotEmpty()) older.count { it }.toFloat() / older.size else 0f
        val newerRate = if (newer.isNotEmpty()) newer.count { it }.toFloat() / newer.size else 0f
        val delta = newerRate - olderRate
        val improving = delta > TREND_EPSILON
        val stable = abs(delta) <= TREND_EPSILON
        return QualityTrend(improving = improving, stable = stable, samples = window.size)
    }

    /**
     * 获取优化统计摘要（用于 UI 展示与调试）。
     *
     * 包含：变体 A/B 测试概况、特性熔断状态、质量趋势、冗余度分布、当前变体。
     *
     * @return 多行统计文本
     */
    fun getOptimizationStats(): String = buildString {
        appendLine("===== 自适应提示词优化统计 =====")

        // 变体 A/B 测试
        val snapshots = definitions.keys.mapNotNull { toVariantSnapshot(it) }
            .sortedByDescending { it.successRate }
        appendLine("【变体 A/B 测试】(生效阈值: 每变体>=$MIN_SAMPLES_FOR_WINNER 样本)")
        if (snapshots.isEmpty()) {
            appendLine("  无变体")
        } else {
            snapshots.forEach { v ->
                val tag = when {
                    !v.enabled -> "[停用]"
                    v.sampleCount >= MIN_SAMPLES_FOR_WINNER -> "[就绪]"
                    else -> "[采样]"
                }
                appendLine("  $tag ${v.id}: ${v.description} | " +
                    "成功率=%.1f%% n=%d".format(v.successRate * 100, v.sampleCount))
            }
        }
        getBestVariant()?.let { best ->
            appendLine("  >> 当前最优: ${best.id} " +
                "(成功率=%.1f%% n=%d)".format(best.successRate * 100, best.sampleCount))
        } ?: run {
            appendLine("  >> 暂无变体达到样本阈值，尚未判定最优")
        }

        // 特性熔断
        appendLine("【特性熔断】(阈值: >=$MIN_SAMPLES_FOR_DISABLE 样本且成功率<${(DISABLE_SUCCESS_RATE_THRESHOLD * 100).toInt()}%)")
        PromptFeature.entries.forEach { f ->
            val fs = featureStats[f]
            val enabled = shouldFeatureBeEnabled(f)
            val rate = fs?.successRate ?: 0f
            val n = fs?.totalCount ?: 0
            appendLine("  ${if (enabled) "[启用]" else "[禁用]"} ${f.name}: " +
                "成功率=%.1f%% n=%d".format(rate * 100, n))
        }

        // 质量趋势
        val trend = getQualityTrend()
        val trendDesc = when {
            trend.improving -> "上升"
            trend.stable -> "平稳"
            else -> "下降"
        }
        appendLine("【质量趋势】$trendDesc (样本=${trend.samples})")

        // 冗余度分布
        val vw = synchronized(recentVerbosity) { recentVerbosity.toList() }
        if (vw.isNotEmpty()) {
            val tooMany = vw.count { it == VerbosityLevel.TOO_MANY }
            val tooFew = vw.count { it == VerbosityLevel.TOO_FEW }
            val optimal = vw.count { it == VerbosityLevel.OPTIMAL }
            appendLine("【冗余度分布(近${vw.size}次)】过多=$tooMany 过少=$tooFew 适中=$optimal")
        }

        appendLine("【当前变体】${lastVariantId ?: "无"}")
        appendLine("================================")
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    /** 获取最近一次 [optimizePrompt] 选中的变体标识。 */
    fun currentVariantId(): String? = lastVariantId

    /** 将内部定义与统计合并为对外可见的 [PromptVariant] 快照。 */
    private fun toVariantSnapshot(id: String): PromptVariant? {
        val def = definitions[id] ?: return null
        val st = stats[id] ?: VariantStats()
        return PromptVariant(
            id = def.id,
            additions = def.additions,
            description = def.description,
            successRate = st.successRate,
            sampleCount = st.totalCount,
            enabled = st.enabled
        )
    }

    /** 手动启用某特性（清除手动禁用标记）。 */
    fun enableFeature(feature: PromptFeature) {
        featureStats.compute(feature) { _, fs ->
            (fs ?: FeatureStats()).copy(enabled = true, manuallyDisabled = false)
        }
        Log.i(TAG, "特性 ${feature.name} 已手动启用")
    }

    /** 手动禁用某特性（标记为手动禁用，不会被自动熔断机制重新启用）。 */
    fun disableFeature(feature: PromptFeature) {
        featureStats.compute(feature) { _, fs ->
            (fs ?: FeatureStats()).copy(enabled = false, manuallyDisabled = true)
        }
        Log.i(TAG, "特性 ${feature.name} 已手动禁用")
    }

    /** 手动停用某变体。 */
    fun disableVariant(id: String) {
        stats.compute(id) { _, s -> (s ?: VariantStats()).copy(enabled = false) }
    }

    /** 手动启用某变体。 */
    fun enableVariant(id: String) {
        stats.compute(id) { _, s -> (s ?: VariantStats()).copy(enabled = true) }
    }

    /**
     * 重置优化器：清空所有统计与变体，重新初始化默认变体。
     *
     * 适用于切换用户或调试场景。
     */
    fun reset() {
        definitions.clear()
        stats.clear()
        featureStats.clear()
        synchronized(recentVerbosity) { recentVerbosity.clear() }
        synchronized(recentResults) { recentResults.clear() }
        lastVariantId = null
        initDefaultVariants()
        Log.i(TAG, "优化器已重置")
    }
}
