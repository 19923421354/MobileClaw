package com.mobileclaw.app.ai

import android.util.Log
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

// =============================================================================
//  NaturalLanguageParser - 自然语言解析器
// =============================================================================

/**
 * 自然语言解析器 —— 在不依赖 AI API 调用的前提下，对用户指令进行高级解析。
 *
 * 核心理念：大量用户指令具有高度结构化的表达模式（「打开微信」「截图」
 * 「10分钟后提醒我」）。通过规则引擎 + 模板匹配 + 同义词归一化，可以在
 * 本地瞬间完成意图理解与实体抽取，无需消耗网络往返与 Token，从而：
 * 1. 将简单指令的响应延迟从「秒级（AI）」降到「毫秒级（本地）」。
 * 2. 在离线或 AI 不可用时仍能处理常见指令。
 * 3. 为 AI 解析提供高质量前置过滤，仅将复杂/歧义指令上送给 AI。
 *
 * 解析流水线（[parse] 方法内部执行顺序）：
 * 1. 命令归一化（[normalize]）：去除口语填充词、全半角统一、多余空白压缩。
 * 2. 同义词扩展（[expandSynonyms]）：将「截屏」「屏幕截图」统一为「截图」等规范形式。
 * 3. 多意图检测（[detectMultiIntent]）：按「然后/接着/之后」等连接词拆分子指令。
 * 4. 模板匹配（[matchTemplate]）：与预定义模板正则比对，命中则获得高置信度。
 * 5. 意图提取（[extractIntent]）：基于关键词与模式判定 [IntentType]。
 * 6. 实体识别（[extractEntities]）：抽取应用名、联系人、电话号码、时间等。
 * 7. 时间解析（[parseTimeExpression]）：解析中文时间表达式为结构化 [TimeExpression]。
 * 8. 置信度综合：融合模板命中、意图匹配、实体完备度，输出 [ParseResult]。
 *
 * 线程安全：
 * - 所有可变共享状态使用 [ConcurrentHashMap]，可被多线程并发调用。
 * - 统计计数使用 @Volatile + private set，保证可见性的同时禁止外部修改。
 * - 预定义的同义词表、应用名映射、模板列表在 companion object 中初始化一次，
 *   初始化后只读，天然线程安全。
 * - 典型场景：UI 线程调用 parse()，后台线程调用 addCustomTemplate()。
 *
 * 使用方式：
 * ```
 * val parser = NaturalLanguageParser()
 * val result = parser.parse("打开微信然后给张三发消息你好")
 * if (result.confidence > 0.8f) {
 *     // 高置信度，可直接执行，无需调用 AI
 *     executeDirectly(result)
 * } else {
 *     // 低置信度，降级到 AI 解析
 *     parseWithAI(result.originalCommand)
 * }
 * ```
 */
class NaturalLanguageParser {

    private val tag = "NaturalLanguageParser"

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 意图类型枚举 —— 描述用户指令的高层语义意图。
     *
     * 每个意图类型对应一组关键词模式与一个首选 [ActionType]，
     * 用于在 [extractIntent] 中进行分类。
     */
    enum class IntentType(val description: String, val primaryAction: ActionType?) {
        /** 打开应用（「打开微信」「启动抖音」）。 */
        OPEN_APP("打开应用", ActionType.APP_OPEN),

        /** 发送消息（「给张三发消息」「发微信给李四」）。 */
        SEND_MESSAGE("发送消息", ActionType.SCREEN_INPUT),

        /** 搜索内容（「搜索猫咪」「在淘宝查找手机壳」）。 */
        SEARCH("搜索内容", ActionType.SCREEN_INPUT),

        /** 导航/路线（「导航到公司」「去机场」）。 */
        NAVIGATE("导航路线", ActionType.APP_OPEN),

        /** 系统控制（「音量调到50」「亮度调到128」「查看电量」）。 */
        SYSTEM_CONTROL("系统控制", ActionType.SYSTEM_GET_INFO),

        /** 截屏（「截图」「截屏」「屏幕截图」）。 */
        SCREENSHOT("截屏", ActionType.SCREEN_SCREENSHOT),

        /** 剪贴板操作（「复制你好」「粘贴」）。 */
        CLIPBOARD("剪贴板操作", ActionType.CLIPBOARD_COPY),

        /** 定时/提醒（「10分钟后提醒我」「明天下午3点」）。 */
        TIMER("定时提醒", ActionType.TIMER_SET),

        /** 清理/加速（「清理缓存」「加速手机」）。 */
        CLEANUP("清理加速", ActionType.SYSTEM_CLEAR_CACHE),

        /** 拨打电话（「打电话给张三」「呼叫13800138000」）。 */
        PHONE_CALL("拨打电话", ActionType.APP_OPEN),

        /** 无法识别的意图，降级到 AI 处理。 */
        UNKNOWN("未知意图", ActionType.ANSWER)
    }

    /**
     * 实体类型枚举 —— 描述从指令中抽取的命名实体类别。
     *
     * 用于 [extractEntities] 的分类标注，每个实体携带类型、值、原始文本与位置。
     */
    enum class EntityType(val description: String) {
        /** 应用名称（「微信」「抖音」「淘宝」）。 */
        APP_NAME("应用名称"),

        /** 联系人姓名（「张三」「李四」「王经理」）。 */
        CONTACT_NAME("联系人姓名"),

        /** 电话号码（「13800138000」「+8613800138000」）。 */
        PHONE_NUMBER("电话号码"),

        /** 文本内容（输入框文字、消息正文、搜索关键词）。 */
        TEXT_CONTENT("文本内容"),

        /** 时间表达式（「10分钟后」「明天下午3点」）。 */
        TIME("时间表达式"),

        /** 纯数字（音量值、亮度值、次数等）。 */
        NUMBER("数字"),

        /** 方向（「上」「下」「左」「右」）。 */
        DIRECTION("方向"),

        /** 网址（「https://example.com」「www.example.com」）。 */
        URL("网址")
    }

    /**
     * 时间表达式类型 —— 区分相对时间、绝对时间与循环时间。
     */
    enum class TimeType(val description: String) {
        /** 相对时间：从当前时刻起经过若干单位后（「10分钟后」「3小时后」）。 */
        RELATIVE("相对时间"),

        /** 绝对时间：指向某个具体的时刻（「明天下午3点」「今天上午9点30分」）。 */
        ABSOLUTE("绝对时间"),

        /** 循环时间：按周期重复（「每天早上8点」「每周一9点」）。 */
        RECURRING("循环时间")
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 解析结果 —— 一次 [parse] 调用的完整输出。
     *
     * 包含归一化后的指令、主意图、实体列表、置信度、匹配的模板、
     * 时间表达式以及（若检测到多意图）拆分后的子指令结果。
     *
     * @param originalCommand 用户原始输入指令
     * @param normalizedCommand 归一化后的指令（去填充词、统一标点）
     * @param expandedCommand 同义词扩展后的指令（规范形式）
     * @param intent 主意图
     * @param entities 识别到的实体列表
     * @param confidence 综合置信度（0.0-1.0），越高越可信
     * @param matchedTemplate 命中的模板，未命中为 null
     * @param timeExpression 解析到的时间表达式，无时间信息为 null
     * @param isMultiIntent 是否为多意图指令
     * @param subIntents 多意图拆分后的子指令结果（单意图时为空列表）
     */
    data class ParseResult(
        val originalCommand: String,
        val normalizedCommand: String,
        val expandedCommand: String,
        val intent: Intent,
        val entities: List<Entity>,
        val confidence: Float,
        val matchedTemplate: TemplateMatch?,
        val timeExpression: TimeExpression?,
        val isMultiIntent: Boolean,
        val subIntents: List<ParseResult>
    ) {
        /** 便捷判断：置信度是否达到可直接执行（无需 AI）的阈值。 */
        val isHighConfidence: Boolean
            get() = confidence >= CONFIDENCE_THRESHOLD_DIRECT

        /** 便捷判断：是否解析失败（意图未知且置信度极低）。 */
        val isUnrecognized: Boolean
            get() = intent.type == IntentType.UNKNOWN && confidence < CONFIDENCE_THRESHOLD_LOW
    }

    /**
     * 意图 —— 从指令中提取的语义意图。
     *
     * @param type 意图类型
     * @param confidence 意图置信度（0.0-1.0）
     * @param actionType 对应的首选动作类型，可能为 null（如 UNKNOWN）
     * @param rawText 触发该意图的原始文本片段
     * @param matchedKeywords 命中的关键词列表（用于调试与说明）
     */
    data class Intent(
        val type: IntentType,
        val confidence: Float,
        val actionType: ActionType?,
        val rawText: String,
        val matchedKeywords: List<String> = emptyList()
    )

    /**
     * 实体 —— 从指令中识别的命名实体。
     *
     * @param type 实体类型
     * @param value 规范化后的实体值（如应用包名、纯数字等）
     * @param rawText 实体在指令中的原始文本
     * @param startIndex 在归一化指令中的起始字符索引
     * @param endIndex 在归一化指令中的结束字符索引（exclusive）
     * @param confidence 实体识别置信度（0.0-1.0）
     */
    data class Entity(
        val type: EntityType,
        val value: String,
        val rawText: String,
        val startIndex: Int,
        val endIndex: Int,
        val confidence: Float
    )

    /**
     * 模板 —— 预定义的指令匹配模板。
     *
     * 每个模板描述一类常见指令的匹配规则，包含正则模式、关键词、
     * 对应的意图类型与动作类型。模板命中时可获得高置信度，从而跳过 AI。
     *
     * @param name 模板唯一名称（如「OPEN_APP」）
     * @param intentType 该模板对应的意图类型
     * @param actionType 该模板对应的首选动作类型
     * @param pattern 匹配正则表达式
     * @param keywords 关键词列表（用于关键词级回退匹配）
     * @param paramGroupNames 正则捕获组名称列表，按顺序对应实体抽取
     * @param description 模板的人类可读描述
     * @param example 示例指令
     * @param baseConfidence 模板命中时的基础置信度
     */
    data class Template(
        val name: String,
        val intentType: IntentType,
        val actionType: ActionType,
        val pattern: Regex,
        val keywords: List<String>,
        val paramGroupNames: List<String>,
        val description: String,
        val example: String,
        val baseConfidence: Float
    )

    /**
     * 模板匹配结果 —— [matchTemplate] 的返回值。
     *
     * @param template 命中的模板定义
     * @param groups 正则捕获组值列表（第 0 项为整体匹配）
     * @param confidence 匹配置信度（正则命中高于关键词命中）
     */
    data class TemplateMatch(
        val template: Template,
        val groups: List<String>,
        val confidence: Float
    )

    /**
     * 时间表达式 —— 解析后的结构化时间信息。
     *
     * 支持三种时间类型：
     * - [TimeType.RELATIVE]：使用 [amount] + [unit] 描述，如「10分钟后」。
     * - [TimeType.ABSOLUTE]：使用 [dayOffset] + [hour] + [minute] 描述，如「明天下午3点」。
     * - [TimeType.RECURRING]：使用 [recurringPeriod] + [hour] + [minute] 描述，如「每天早上8点」。
     *
     * [delaySeconds] 为统一换算后的延迟秒数，可直接用于 [ActionType.TIMER_SET]。
     * 对于循环时间，delaySeconds 表示距离下一次触发的秒数。
     *
     * @param rawText 原始时间文本
     * @param type 时间类型
     * @param amount 相对时间的数值（RELATIVE 有效）
     * @param unit 相对时间的单位（「秒」「分」「时」「天」，RELATIVE 有效）
     * @param hour 小时（0-23，ABSOLUTE/RECURRING 有效，-1 表示未指定）
     * @param minute 分钟（0-59，ABSOLUTE/RECURRING 有效，-1 表示未指定）
     * @param dayOffset 相对今天的天数偏移（0=今天，1=明天，2=后天，ABSOLUTE 有效）
     * @param recurringPeriod 循环周期描述（「每天」「每周X」「每月X日」，RECURRING 有效）
     * @param delaySeconds 统一换算的延迟秒数（用于 TIMER_SET）
     */
    data class TimeExpression(
        val rawText: String,
        val type: TimeType,
        val amount: Long = 0L,
        val unit: String = "",
        val hour: Int = -1,
        val minute: Int = -1,
        val dayOffset: Int = 0,
        val recurringPeriod: String = "",
        val delaySeconds: Long = 0L
    ) {
        /** 便捷判断：是否为循环时间。 */
        val isRecurring: Boolean
            get() = type == TimeType.RECURRING

        /** 便捷判断：延迟是否有效（大于 0）。 */
        val isValidDelay: Boolean
            get() = delaySeconds > 0L
    }

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** 自定义模板存储，键 = 模板名称。可通过 [addCustomTemplate] 动态添加。 */
    private val customTemplates = ConcurrentHashMap<String, Template>()

    /** 最近解析缓存，键 = 原始指令，值 = 解析结果（避免重复解析相同指令）。 */
    private val parseCache = ConcurrentHashMap<String, ParseResult>()

    // ============================================================
    // 统计计数
    // ============================================================

    /** 累计解析次数。 */
    @Volatile
    var totalParsed: Int = 0
        private set

    /** 模板命中次数（高置信度直接执行，无需 AI）。 */
    @Volatile
    var templateMatchCount: Int = 0
        private set

    /** 多意图检测次数。 */
    @Volatile
    var multiIntentCount: Int = 0
        private set

    /** 意图为 UNKNOWN 的次数（需降级到 AI）。 */
    @Volatile
    var unknownIntentCount: Int = 0
        private set

    // ============================================================
    // 主入口：parse
    // ============================================================

    /**
     * 解析用户指令，返回完整的 [ParseResult]。
     *
     * 这是解析器的唯一主入口，内部依次执行归一化、同义词扩展、
     * 多意图检测、模板匹配、意图提取、实体识别与时间解析，
     * 最终融合出综合置信度。
     *
     * 解析结果会被缓存（键为原始指令），相同指令的重复调用直接返回缓存结果。
     *
     * @param command 用户原始指令
     * @return 解析结果
     */
    fun parse(command: String): ParseResult {
        val original = command.trim()
        if (original.isEmpty()) {
            return emptyResult("")
        }

        // 缓存命中
        parseCache[original]?.let { return it }

        totalParsed++

        // 1. 归一化
        val normalized = normalize(original)

        // 2. 同义词扩展
        val expanded = expandSynonyms(normalized)

        // 3. 多意图检测（内部不计数，由本方法统一管理统计）
        val subResults = detectMultiIntent(expanded)
        val isMulti = subResults.size > 1

        if (isMulti) {
            // —— 多意图分支：聚合子结果 ——
            multiIntentCount++

            val firstSub = subResults.first()
            val allEntities = subResults.flatMap { it.entities }.distinctBy { it.type to it.rawText }
            val anyTemplateMatch = subResults.firstOrNull { it.matchedTemplate != null }?.matchedTemplate
            val anyTimeExpr = subResults.firstOrNull { it.timeExpression != null }?.timeExpression
            if (anyTemplateMatch != null) templateMatchCount++

            val intent = firstSub.intent
            if (intent.type == IntentType.UNKNOWN) unknownIntentCount++

            // 多意图置信度取子意图最小值 × 0.9（体现拆分不确定性）
            val confidence = (subResults.minOfOrNull { it.confidence } ?: 0f) * 0.9f

            val result = ParseResult(
                originalCommand = original,
                normalizedCommand = normalized,
                expandedCommand = expanded,
                intent = intent,
                entities = allEntities,
                confidence = confidence.coerceIn(0f, 1f),
                matchedTemplate = anyTemplateMatch,
                timeExpression = anyTimeExpr,
                isMultiIntent = true,
                subIntents = subResults
            )

            if (parseCache.size < MAX_CACHE_SIZE) {
                parseCache[original] = result
            }
            Log.d(tag, "解析: \"$original\" -> ${intent.type} (${(confidence * 100).toInt()}%)" +
                    " [多意图×${subResults.size}]")
            return result
        }

        // —— 单意图分支 ——
        // 4. 模板匹配
        val templateMatch = matchTemplate(expanded)
        if (templateMatch != null) templateMatchCount++

        // 5. 意图提取
        val intent = if (templateMatch != null) {
            Intent(
                type = templateMatch.template.intentType,
                confidence = templateMatch.confidence,
                actionType = templateMatch.template.actionType,
                rawText = expanded,
                matchedKeywords = templateMatch.template.keywords
            )
        } else {
            extractIntent(expanded)
        }

        if (intent.type == IntentType.UNKNOWN) unknownIntentCount++

        // 6. 实体识别
        val entities = extractEntities(expanded)

        // 7. 时间解析
        val timeExpr = parseTimeExpression(expanded)

        // 8. 置信度综合
        val confidence = computeConfidence(intent, templateMatch, entities, timeExpr, false)

        val result = ParseResult(
            originalCommand = original,
            normalizedCommand = normalized,
            expandedCommand = expanded,
            intent = intent,
            entities = entities,
            confidence = confidence,
            matchedTemplate = templateMatch,
            timeExpression = timeExpr,
            isMultiIntent = false,
            subIntents = emptyList()
        )

        // 写入缓存（控制缓存大小）
        if (parseCache.size < MAX_CACHE_SIZE) {
            parseCache[original] = result
        }

        Log.d(tag, "解析: \"$original\" -> ${intent.type} (${(confidence * 100).toInt()}%)")
        return result
    }

    // ============================================================
    // 意图提取
    // ============================================================

    /**
     * 从指令中提取主意图。
     *
     * 基于预定义的意图关键词表进行匹配：遍历每个意图类型的关键词，
     * 统计命中数，命中越多置信度越高。当多个意图有命中时，取命中数最多者。
     *
     * @param command 归一化（建议已同义词扩展）后的指令
     * @return 提取到的意图，无法识别时返回 UNKNOWN
     */
    fun extractIntent(command: String): Intent {
        val text = command.trim()
        if (text.isEmpty()) return unknownIntent(text)

        var bestType = IntentType.UNKNOWN
        var bestScore = 0
        var bestKeywords = emptyList<String>()

        for ((intentType, keywords) in INTENT_KEYWORDS) {
            val matched = keywords.filter { text.contains(it) }
            if (matched.size > bestScore) {
                bestScore = matched.size
                bestType = intentType
                bestKeywords = matched
            }
        }

        if (bestType == IntentType.UNKNOWN) return unknownIntent(text)

        // 置信度：基于命中关键词数量，归一化到 [0.5, 0.85] 区间
        // （未命中模板的意图提取置信度上限为 0.85，留出空间给模板命中加成）
        val totalKeywords = INTENT_KEYWORDS[bestType]?.size ?: 1
        val ratio = bestScore.toFloat() / totalKeywords.coerceAtLeast(1)
        val confidence = (0.5f + ratio * 0.35f).coerceIn(0.5f, 0.85f)

        return Intent(
            type = bestType,
            confidence = confidence,
            actionType = bestType.primaryAction,
            rawText = text,
            matchedKeywords = bestKeywords
        )
    }

    /** 构造 UNKNOWN 意图。 */
    private fun unknownIntent(text: String): Intent = Intent(
        type = IntentType.UNKNOWN,
        confidence = 0.2f,
        actionType = IntentType.UNKNOWN.primaryAction,
        rawText = text
    )

    // ============================================================
    // 实体识别
    // ============================================================

    /**
     * 从指令中识别所有命名实体。
     *
     * 依次执行以下抽取器，结果合并为去重后的实体列表：
     * 1. 电话号码（正则匹配中国大陆手机号）
     * 2. 网址（正则匹配 http/https/www 开头的 URL）
     * 3. 应用名称（基于预定义应用名映射 + 「打开/关闭X」模式）
     * 4. 联系人姓名（基于「给X发消息/打电话」模式）
     * 5. 时间表达式（委托 [parseTimeExpression]）
     * 6. 方向（上/下/左/右）
     * 7. 纯数字（音量、亮度等数值）
     * 8. 文本内容（「输入X」「搜索X」「发消息X」模式）
     *
     * @param command 归一化后的指令
     * @return 识别到的实体列表（按出现位置排序）
     */
    fun extractEntities(command: String): List<Entity> {
        val text = command.trim()
        if (text.isEmpty()) return emptyList()

        val entities = ArrayList<Entity>()

        // 1. 电话号码
        extractPhoneNumbers(text, entities)

        // 2. 网址
        extractUrls(text, entities)

        // 3. 应用名称
        extractAppNames(text, entities)

        // 4. 联系人姓名
        extractContactNames(text, entities)

        // 5. 时间表达式
        parseTimeExpression(text)?.let { timeExpr ->
            val index = text.indexOf(timeExpr.rawText)
            if (index >= 0) {
                entities.add(
                    Entity(
                        type = EntityType.TIME,
                        value = timeExpr.delaySeconds.toString(),
                        rawText = timeExpr.rawText,
                        startIndex = index,
                        endIndex = index + timeExpr.rawText.length,
                        confidence = 0.9f
                    )
                )
            }
        }

        // 6. 方向
        extractDirections(text, entities)

        // 7. 纯数字
        extractNumbers(text, entities)

        // 8. 文本内容
        extractTextContent(text, entities)

        // 按出现位置排序，位置相同按置信度降序
        return entities
            .distinctBy { it.type to it.rawText }
            .sortedWith(compareBy({ it.startIndex }, { -it.confidence }))
    }

    /** 抽取电话号码实体。 */
    private fun extractPhoneNumbers(text: String, out: MutableList<Entity>) {
        for (match in PHONE_REGEX.findAll(text)) {
            out.add(
                Entity(
                    type = EntityType.PHONE_NUMBER,
                    value = match.value.replace("\\s".toRegex(), ""),
                    rawText = match.value,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    confidence = 0.95f
                )
            )
        }
    }

    /** 抽取网址实体。 */
    private fun extractUrls(text: String, out: MutableList<Entity>) {
        for (match in URL_REGEX.findAll(text)) {
            out.add(
                Entity(
                    type = EntityType.URL,
                    value = match.value,
                    rawText = match.value,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    confidence = 0.9f
                )
            )
        }
    }

    /** 抽取应用名称实体。 */
    private fun extractAppNames(text: String, out: MutableList<Entity>) {
        // 策略一：直接匹配预定义应用名映射中的已知应用名
        for ((appName, _) in APP_PACKAGES) {
            val index = text.indexOf(appName)
            if (index >= 0) {
                out.add(
                    Entity(
                        type = EntityType.APP_NAME,
                        value = appName,
                        rawText = appName,
                        startIndex = index,
                        endIndex = index + appName.length,
                        confidence = 0.95f
                    )
                )
            }
        }

        // 策略二：通过「打开/启动/关闭X」模式抽取未知应用名
        val appPattern = Regex("(?:打开|启动|开启|运行|关闭|退出)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?(?:然后|接着|之后|$)")
        for (match in appPattern.findAll(text)) {
            val name = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // 跳过已识别的（避免重复）
            if (out.any { it.type == EntityType.APP_NAME && it.rawText == name }) continue
            val groupRange = match.groups[1]?.range ?: continue
            out.add(
                Entity(
                    type = EntityType.APP_NAME,
                    value = name,
                    rawText = name,
                    startIndex = groupRange.first,
                    endIndex = groupRange.last + 1,
                    confidence = 0.7f
                )
            )
        }
    }

    /** 抽取联系人姓名实体。 */
    private fun extractContactNames(text: String, out: MutableList<Entity>) {
        // 模式一：「给X发消息/打电话」
        val pattern1 = Regex("给\\s*[「「【]?(.+?)[」」】]?\\s*(?:发|发送|打|呼叫)")
        for (match in pattern1.findAll(text)) {
            val name = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (isLikelyAppName(name)) continue
            val groupRange = match.groups[1]?.range ?: continue
            out.add(
                Entity(
                    type = EntityType.CONTACT_NAME,
                    value = name,
                    rawText = name,
                    startIndex = groupRange.first,
                    endIndex = groupRange.last + 1,
                    confidence = 0.85f
                )
            )
        }

        // 模式二：「发消息给X」「打电话给X」
        val pattern2 = Regex("(?:发消息|发信息|打电话|呼叫)给\\s*[「「【]?(.+?)[」」】]?(?:发|说|，|,|然后|接着|$)")
        for (match in pattern2.findAll(text)) {
            val name = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (isLikelyAppName(name)) continue
            if (out.any { it.type == EntityType.CONTACT_NAME && it.rawText == name }) continue
            val groupRange = match.groups[1]?.range ?: continue
            out.add(
                Entity(
                    type = EntityType.CONTACT_NAME,
                    value = name,
                    rawText = name,
                    startIndex = groupRange.first,
                    endIndex = groupRange.last + 1,
                    confidence = 0.8f
                )
            )
        }
    }

    /** 抽取方向实体。 */
    private fun extractDirections(text: String, out: MutableList<Entity>) {
        for (match in DIRECTION_REGEX.findAll(text)) {
            out.add(
                Entity(
                    type = EntityType.DIRECTION,
                    value = match.value,
                    rawText = match.value,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    confidence = 0.85f
                )
            )
        }
    }

    /** 抽取纯数字实体。 */
    private fun extractNumbers(text: String, out: MutableList<Entity>) {
        for (match in NUMBER_REGEX.findAll(text)) {
            val numStr = match.value
            // 跳过已作为电话号码识别的
            if (out.any { it.type == EntityType.PHONE_NUMBER && it.rawText.contains(numStr) }) continue
            out.add(
                Entity(
                    type = EntityType.NUMBER,
                    value = numStr,
                    rawText = numStr,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                    confidence = 0.75f
                )
            )
        }
    }

    /** 抽取文本内容实体（输入/搜索/消息正文）。 */
    private fun extractTextContent(text: String, out: MutableList<Entity>) {
        // 模式：「输入X」「搜索X」「查找X」
        val inputPattern = Regex("(?:输入|搜索|查找|搜)\\s*[「「【\"']?(.+?)[」」】\"']?(?:然后|接着|，|,|$)")
        for (match in inputPattern.findAll(text)) {
            val content = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val groupRange = match.groups[1]?.range ?: continue
            out.add(
                Entity(
                    type = EntityType.TEXT_CONTENT,
                    value = content,
                    rawText = content,
                    startIndex = groupRange.first,
                    endIndex = groupRange.last + 1,
                    confidence = 0.8f
                )
            )
        }

        // 模式：「发消息X」「给X发消息Y」中的消息正文
        val msgPattern = Regex("(?:发消息|发信息|发送)\\s*[「「【\"']?(.+?)[」」】\"']?(?:然后|接着|，|,|$)")
        for (match in msgPattern.findAll(text)) {
            val content = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (out.any { it.type == EntityType.TEXT_CONTENT && it.rawText == content }) continue
            val groupRange = match.groups[1]?.range ?: continue
            out.add(
                Entity(
                    type = EntityType.TEXT_CONTENT,
                    value = content,
                    rawText = content,
                    startIndex = groupRange.first,
                    endIndex = groupRange.last + 1,
                    confidence = 0.75f
                )
            )
        }
    }

    /** 判断给定名称是否可能是应用名（已在应用名映射中）。 */
    private fun isLikelyAppName(name: String): Boolean = APP_PACKAGES.containsKey(name)

    // ============================================================
    // 命令归一化
    // ============================================================

    /**
     * 命令归一化 —— 将各种口语化表达统一为规范形式。
     *
     * 归一化步骤：
     * 1. 去除首尾空白。
     * 2. 全角字符转半角（数字、字母、标点）。
     * 3. 去除开头的口语填充词（「请」「帮我」「麻烦」「我要」「我想」等）。
     * 4. 去除结尾的语气词（「一下」「吧」「呢」「啊」等）。
     * 5. 压缩连续空白为单个空格。
     *
     * 例如：「请帮我打开一下微信吧」→「打开微信」
     *
     * @param command 用户原始指令
     * @return 归一化后的指令
     */
    fun normalize(command: String): String {
        var text = command.trim()
        if (text.isEmpty()) return text

        // 1. 全角转半角
        text = toHalfWidth(text)

        // 2. 去除开头填充词（循环去除，处理「请帮我」这种叠加）
        var changed = true
        while (changed) {
            changed = false
            for (prefix in FILLER_PREFIXES) {
                if (text.startsWith(prefix)) {
                    text = text.substring(prefix.length).trimStart()
                    changed = true
                }
            }
        }

        // 3. 去除结尾语气词
        for (suffix in FILLER_SUFFIXES) {
            if (text.endsWith(suffix) && text.length > suffix.length) {
                text = text.substring(0, text.length - suffix.length).trimEnd()
            }
        }

        // 4. 压缩连续空白
        text = text.replace(Regex("\\s+"), " ").trim()

        return text
    }

    /**
     * 全角字符转半角。
     * 处理全角数字（０-９）、全角字母（Ａ-Ｚ, ａ-ｚ）和常见全角标点。
     */
    private fun toHalfWidth(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val converted = when {
                ch in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                ch == '\u3000' -> ' '
                else -> ch
            }
            sb.append(converted)
        }
        return sb.toString()
    }

    // ============================================================
    // 同义词扩展
    // ============================================================

    /**
     * 同义词扩展 —— 将各种同义表达替换为规范形式。
     *
     * 遍历预定义同义词映射表，将同义词替换为对应的规范形式。
     * 替换按同义词长度降序进行（长的优先），避免短词错误覆盖长词。
     *
     * 例如：
     * - 「截屏」→「截图」
     * - 「屏幕截图」→「截图」
     * - 「开微信」→「打开微信」
     * - 「发信息」→「发消息」
     *
     * @param command 归一化后的指令
     * @return 同义词扩展后的指令
     */
    fun expandSynonyms(command: String): String {
        var text = command
        if (text.isEmpty()) return text

        // 按同义词长度降序排列，长的优先替换
        val sortedSynonyms = SYNONYM_ENTRIES
        for ((synonym, canonical) in sortedSynonyms) {
            if (synonym.isEmpty()) continue
            if (text.contains(synonym)) {
                text = text.replace(synonym, canonical)
            }
        }

        return text.trim()
    }

    // ============================================================
    // 模板匹配
    // ============================================================

    /**
     * 将指令与预定义模板进行匹配。
     *
     * 匹配优先级：
     * 1. 正则精确匹配（置信度 = 模板基础置信度）
     * 2. 关键词包含匹配（置信度 = 模板基础置信度 × 0.8）
     *
     * 遍历所有预定义模板与自定义模板，返回第一个正则命中的结果；
     * 若无正则命中，返回关键词匹配最佳的模板。
     *
     * @param command 归一化（建议已同义词扩展）后的指令
     * @return 模板匹配结果，未匹配返回 null
     */
    fun matchTemplate(command: String): TemplateMatch? {
        val text = command.trim()
        if (text.isEmpty()) return null

        var keywordFallback: TemplateMatch? = null

        // 合并预定义模板与自定义模板
        val allTemplates = TEMPLATES + customTemplates.values

        for (template in allTemplates) {
            // 1. 正则匹配（高优先级）
            val match = template.pattern.find(text)
            if (match != null) {
                val groups = match.groupValues.map { it }
                return TemplateMatch(
                    template = template,
                    groups = groups,
                    confidence = template.baseConfidence
                )
            }

            // 2. 关键词匹配（低优先级回退）
            if (keywordFallback == null) {
                val matchedKeywords = template.keywords.filter { text.contains(it) }
                if (matchedKeywords.isNotEmpty()) {
                    keywordFallback = TemplateMatch(
                        template = template,
                        groups = emptyList(),
                        confidence = template.baseConfidence * KEYWORD_MATCH_FACTOR
                    )
                }
            }
        }

        return keywordFallback
    }

    // ============================================================
    // 时间表达式解析
    // ============================================================

    /**
     * 解析中文时间表达式，返回结构化的 [TimeExpression]。
     *
     * 支持的时间表达式类型：
     *
     * **相对时间**（[TimeType.RELATIVE]）：
     * - 「10分钟后」「30秒钟后」「3小时后」「2天后」
     *
     * **绝对时间**（[TimeType.ABSOLUTE]）：
     * - 「明天下午3点」「后天上午9点30分」「今天晚上8点」
     * - 「下午3点」「上午9点」（默认今天）
     * - 「3点15分」（默认今天下午）
     *
     * **循环时间**（[TimeType.RECURRING]）：
     * - 「每天早上8点」「每天下午6点30分」
     * - 「每周一9点」「工作日早上9点」
     *
     * [TimeExpression.delaySeconds] 统一换算为距离当前时刻的延迟秒数，
     * 可直接用于 [ActionType.TIMER_SET]。对于循环时间，返回距离下一次触发的秒数。
     *
     * @param command 包含时间表达的指令
     * @return 解析到的时间表达式，无时间信息返回 null
     */
    fun parseTimeExpression(command: String): TimeExpression? {
        val text = command.trim()
        if (text.isEmpty()) return null

        // 1. 尝试循环时间
        parseRecurringTime(text)?.let { return it }

        // 2. 尝试相对时间
        parseRelativeTime(text)?.let { return it }

        // 3. 尝试绝对时间
        parseAbsoluteTime(text)?.let { return it }

        return null
    }

    /**
     * 解析相对时间表达式（「X分钟/秒钟/小时/天后」）。
     */
    private fun parseRelativeTime(text: String): TimeExpression? {
        val match = RELATIVE_TIME_REGEX.find(text) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unitText = match.groupValues[2]

        val (canonicalUnit, seconds) = when {
            unitText.startsWith("秒") -> "秒" to amount
            unitText.startsWith("分") -> "分" to amount * 60
            unitText.startsWith("时") || unitText.startsWith("小") -> "时" to amount * 3600
            unitText.startsWith("天") -> "天" to amount * 86400
            else -> return null
        }

        return TimeExpression(
            rawText = match.value,
            type = TimeType.RELATIVE,
            amount = amount,
            unit = canonicalUnit,
            delaySeconds = seconds
        )
    }

    /**
     * 解析绝对时间表达式（「明天下午3点」「今天上午9点30分」等）。
     */
    private fun parseAbsoluteTime(text: String): TimeExpression? {
        val match = ABSOLUTE_TIME_REGEX.find(text) ?: return null

        val dayText = match.groupValues[1]
        val periodText = match.groupValues[2]
        val hourText = match.groupValues[3]
        val minuteText = match.groupValues[4]

        var hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: 0

        // 处理上下午
        when {
            periodText.contains("下午") || periodText.contains("晚") || periodText.contains("傍晚") -> {
                if (hour < 12) hour += 12
            }
            periodText.contains("中午") -> {
                if (hour < 12) hour += 12
            }
            periodText.contains("上午") || periodText.contains("早") -> {
                // 上午不调整
            }
        }

        // 处理日期偏移
        val dayOffset = when {
            dayText.contains("今天") -> 0
            dayText.contains("明天") -> 1
            dayText.contains("后天") -> 2
            dayText.contains("大后天") -> 3
            else -> 0
        }

        // 计算延迟秒数
        val delaySeconds = computeAbsoluteDelay(dayOffset, hour, minute)

        return TimeExpression(
            rawText = match.value,
            type = TimeType.ABSOLUTE,
            hour = hour,
            minute = minute,
            dayOffset = dayOffset,
            delaySeconds = delaySeconds
        )
    }

    /**
     * 解析循环时间表达式（「每天早上8点」「每周一9点」等）。
     */
    private fun parseRecurringTime(text: String): TimeExpression? {
        val match = RECURRING_TIME_REGEX.find(text) ?: return null

        val periodText = match.groupValues[1]    // 每天/每周/工作日/每月
        val dayOfWeek = match.groupValues[2]     // 一/二/三/.../天（周几详情）
        val ampmText = match.groupValues[3]      // 上午/下午/晚上/早上/中午/傍晚
        val hourText = match.groupValues[4]      // 小时
        val minuteText = match.groupValues[5]    // 分钟

        var hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: 0

        // 处理上下午（仅使用时段信息，不使用周几详情）
        when {
            ampmText.contains("下午") || ampmText.contains("晚") || ampmText.contains("傍晚") -> {
                if (hour < 12) hour += 12
            }
            ampmText.contains("中午") -> {
                if (hour < 12) hour += 12
            }
        }

        // 构建循环周期描述（如「每周一」「每天」「工作日」）
        val recurringPeriod = buildString {
            append(periodText)
            if (dayOfWeek.isNotEmpty()) append(dayOfWeek)
        }

        // 循环时间取距离下一次触发的秒数
        val delaySeconds = computeRecurringDelay(hour, minute, recurringPeriod)

        return TimeExpression(
            rawText = match.value,
            type = TimeType.RECURRING,
            hour = hour,
            minute = minute,
            recurringPeriod = recurringPeriod,
            delaySeconds = delaySeconds
        )
    }

    /**
     * 计算绝对时间距离当前时刻的延迟秒数。
     *
     * @param dayOffset 天数偏移（0=今天，1=明天...）
     * @param hour 目标小时（0-23）
     * @param minute 目标分钟（0-59）
     * @return 延迟秒数，若目标时刻已过且为今天则顺延到第二天
     */
    private fun computeAbsoluteDelay(dayOffset: Int, hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var diffMillis = target.timeInMillis - now.timeInMillis
        // 如果目标时间已过（且是今天），顺延到第二天
        if (diffMillis < 0 && dayOffset == 0) {
            diffMillis += 86400000L
        }
        return (diffMillis / 1000).coerceAtLeast(0L)
    }

    /**
     * 计算循环时间距离下一次触发的延迟秒数。
     *
     * @param hour 目标小时
     * @param minute 目标分钟
     * @param periodText 周期文本（「每天」「每周」「工作日」「每月」）
     * @return 距离下一次触发的秒数
     */
    private fun computeRecurringDelay(hour: Int, minute: Int, periodText: String): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        when {
            periodText.contains("每天") -> {
                // 每天：若今天的时间已过，顺延到明天
                if (target.timeInMillis <= now.timeInMillis) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            periodText.contains("每周") -> {
                // 每周X：找到下一个匹配的星期几
                val targetDayOfWeek = extractDayOfWeek(periodText)
                if (targetDayOfWeek > 0) {
                    var currentDay = now.get(Calendar.DAY_OF_WEEK)
                    var daysToAdd = (targetDayOfWeek - currentDay + 7) % 7
                    if (daysToAdd == 0 && target.timeInMillis <= now.timeInMillis) {
                        daysToAdd = 7
                    }
                    target.add(Calendar.DAY_OF_YEAR, daysToAdd)
                }
            }
            periodText.contains("工作日") -> {
                // 工作日（周一到周五）：找到下一个工作日
                while (target.timeInMillis <= now.timeInMillis ||
                    target.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                    target.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                ) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            else -> {
                if (target.timeInMillis <= now.timeInMillis) {
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }

        return ((target.timeInMillis - now.timeInMillis) / 1000).coerceAtLeast(0L)
    }

    /** 从「每周一」「每周三」等文本中提取星期几（1=周日，2=周一...7=周六，遵循 Calendar 常量）。 */
    private fun extractDayOfWeek(text: String): Int {
        val dayMap = mapOf(
            "周日" to 1, "星期日" to 1, "礼拜日" to 1,
            "周一" to 2, "星期一" to 2, "礼拜一" to 2,
            "周二" to 3, "星期二" to 3, "礼拜二" to 3,
            "周三" to 4, "星期三" to 4, "礼拜三" to 4,
            "周四" to 5, "星期四" to 5, "礼拜四" to 5,
            "周五" to 6, "星期五" to 6, "礼拜五" to 6,
            "周六" to 7, "星期六" to 7, "礼拜六" to 7
        )
        for ((keyword, value) in dayMap) {
            if (text.contains(keyword)) return value
        }
        return -1
    }

    // ============================================================
    // 多意图检测
    // ============================================================

    /**
     * 检测指令中是否包含多个意图，并拆分为子指令。
     *
     * 按意图连接词（「然后」「接着」「之后」「再」「并且」「最后」等）
     * 将复合指令拆分为多个子指令，每个子指令独立解析。
     *
     * 例如：「打开微信然后给张三发消息你好」拆分为：
     * 1. 「打开微信」→ OPEN_APP
     * 2. 「给张三发消息你好」→ SEND_MESSAGE
     *
     * 注意：当指令中无连接词时，返回仅包含原始指令（单元素）的列表，
     * 此时调用方可判断 [List.size] <= 1 来识别单意图。
     *
     * @param command 归一化后的指令
     * @return 拆分后的子指令解析结果列表（单意图时仅含一个元素）
     */
    fun detectMultiIntent(command: String): List<ParseResult> {
        val text = command.trim()
        if (text.isEmpty()) return listOf(emptyResult(""))

        // 按连接词拆分
        val parts = splitByConnectors(text)
        if (parts.size <= 1) {
            // 单意图：直接解析（不递归调用 parse 以避免缓存干扰）
            return listOf(parseSingle(text))
        }

        // 多意图：对每个子指令递归解析
        return parts.map { part -> parseSingle(part) }
    }

    /**
     * 按意图连接词拆分指令。
     * 返回拆分后的子指令列表（已去除连接词本身）。
     */
    private fun splitByConnectors(text: String): List<String> {
        // 构建连接词正则（按长度降序，优先匹配长连接词）
        val connectorPattern = Regex(
            INTENT_CONNECTORS
                .sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }
        )

        val parts = connectorPattern.split(text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return parts
    }

    /**
     * 解析单个子指令（不进行多意图拆分，避免递归）。
     * 内部使用，供 [detectMultiIntent] 调用。不修改统计计数（由 [parse] 统一管理）。
     */
    private fun parseSingle(command: String): ParseResult {
        val text = command.trim()
        if (text.isEmpty()) return emptyResult("")

        val templateMatch = matchTemplate(text)

        val intent = if (templateMatch != null) {
            Intent(
                type = templateMatch.template.intentType,
                confidence = templateMatch.confidence,
                actionType = templateMatch.template.actionType,
                rawText = text,
                matchedKeywords = templateMatch.template.keywords
            )
        } else {
            extractIntent(text)
        }

        val entities = extractEntities(text)
        val timeExpr = parseTimeExpression(text)
        val confidence = computeConfidence(intent, templateMatch, entities, timeExpr, false)

        return ParseResult(
            originalCommand = text,
            normalizedCommand = text,
            expandedCommand = text,
            intent = intent,
            entities = entities,
            confidence = confidence,
            matchedTemplate = templateMatch,
            timeExpression = timeExpr,
            isMultiIntent = false,
            subIntents = emptyList()
        )
    }

    // ============================================================
    // 置信度综合计算
    // ============================================================

    /**
     * 综合计算解析结果的置信度。
     *
     * 融合因子：
     * - 意图置信度（权重 0.4）：来自 [extractIntent] 或模板命中。
     * - 模板命中加成（权重 0.3）：模板正则命中为高，关键词命中为中。
     * - 实体完备度（权重 0.2）：识别到的实体越多越完备。
     * - 时间解析加成（权重 0.1）：成功解析时间表达式时加成。
     *
     * 多意图指令的总置信度略降（× 0.9），体现拆分带来的不确定性。
     *
     * @return 综合置信度（0.0-1.0）
     */
    private fun computeConfidence(
        intent: Intent,
        templateMatch: TemplateMatch?,
        entities: List<Entity>,
        timeExpr: TimeExpression?,
        isMultiIntent: Boolean
    ): Float {
        // 意图置信度
        val intentScore = intent.confidence

        // 模板命中加成
        val templateScore = templateMatch?.confidence ?: 0f

        // 实体完备度：每识别到一个实体加 0.1，上限 0.4
        val entityScore = (entities.size * 0.1f).coerceAtMost(0.4f)

        // 时间解析加成
        val timeScore = if (timeExpr != null) 0.1f else 0f

        var confidence = intentScore * 0.4f +
                templateScore * 0.3f +
                entityScore * 0.2f +
                timeScore * 0.1f

        // 模板正则命中时直接取模板置信度与综合值的较大者
        if (templateMatch != null && templateMatch.confidence >= 0.9f) {
            confidence = maxOf(confidence, templateMatch.confidence)
        }

        // 多意图略降
        if (isMultiIntent) confidence *= 0.9f

        return confidence.coerceIn(0f, 1f)
    }

    // ============================================================
    // 自定义模板管理
    // ============================================================

    /**
     * 添加自定义模板。
     *
     * 自定义模板与预定义模板一同参与 [matchTemplate] 匹配。
     * 若同名模板已存在则覆盖。
     *
     * @param template 模板定义
     */
    fun addCustomTemplate(template: Template) {
        customTemplates[template.name] = template
        Log.d(tag, "添加自定义模板: ${template.name}")
    }

    /**
     * 移除自定义模板。
     *
     * @param name 模板名称
     * @return 是否移除成功
     */
    fun removeCustomTemplate(name: String): Boolean {
        val removed = customTemplates.remove(name) != null
        if (removed) Log.d(tag, "移除自定义模板: $name")
        return removed
    }

    /** 获取所有自定义模板名称列表。 */
    fun getCustomTemplateNames(): List<String> = customTemplates.keys.toList()

    // ============================================================
    // 统计与查询
    // ============================================================

    /**
     * 获取解析器统计摘要（用于 UI 展示与调试）。
     *
     * 包含累计解析次数、模板命中率、多意图次数、未知意图次数。
     */
    fun getSummary(): String {
        val templateRate = if (totalParsed > 0) {
            "%.1f%%".format(templateMatchCount.toFloat() / totalParsed * 100)
        } else {
            "N/A"
        }
        val unknownRate = if (totalParsed > 0) {
            "%.1f%%".format(unknownIntentCount.toFloat() / totalParsed * 100)
        } else {
            "N/A"
        }
        return "解析器: 解析${totalParsed}次 | 模板命中${templateMatchCount}($templateRate) " +
                "| 多意图${multiIntentCount} | 未知${unknownIntentCount}($unknownRate) " +
                "| 自定义模板${customTemplates.size} | 缓存${parseCache.size}"
    }

    /** 获取模板命中率（0.0-1.0）。 */
    fun templateHitRate(): Float =
        if (totalParsed > 0) templateMatchCount.toFloat() / totalParsed else 0f

    // ============================================================
    // 缓存管理
    // ============================================================

    /** 清空解析缓存。 */
    fun clearCache() {
        parseCache.clear()
        Log.d(tag, "已清空解析缓存")
    }

    /** 清空所有统计计数（不影响模板与同义词配置）。 */
    fun resetStats() {
        totalParsed = 0
        templateMatchCount = 0
        multiIntentCount = 0
        unknownIntentCount = 0
        Log.d(tag, "已重置统计计数")
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /** 构造空指令的解析结果。 */
    private fun emptyResult(command: String): ParseResult = ParseResult(
        originalCommand = command,
        normalizedCommand = command,
        expandedCommand = command,
        intent = unknownIntent(command),
        entities = emptyList(),
        confidence = 0f,
        matchedTemplate = null,
        timeExpression = null,
        isMultiIntent = false,
        subIntents = emptyList()
    )

    // ============================================================
    // 伴生对象：配置常量与静态映射
    // ============================================================

    companion object {

        // ---- 置信度阈值 ----

        /** 高置信度阈值：达到此值可直接执行，无需 AI。 */
        const val CONFIDENCE_THRESHOLD_DIRECT = 0.8f

        /** 低置信度阈值：低于此值需降级到 AI 解析。 */
        const val CONFIDENCE_THRESHOLD_LOW = 0.3f

        /** 关键词匹配的置信度折扣因子。 */
        const val KEYWORD_MATCH_FACTOR = 0.8f

        /** 解析缓存最大条目数。 */
        const val MAX_CACHE_SIZE = 200

        // ---- 时间正则表达式 ----

        /** 相对时间正则：「10分钟后」「30秒钟后」「3小时后」「2天后」。 */
        val RELATIVE_TIME_REGEX: Regex = Regex(
            "(\\d+)\\s*(秒钟|秒|分钟|分|小时|时|天)\\s*后"
        )

        /** 绝对时间正则：「明天下午3点」「后天上午9点30分」「今天晚上8点」。 */
        val ABSOLUTE_TIME_REGEX: Regex = Regex(
            "(今天|明天|后天|大后天)?\\s*(上午|下午|晚上|早上|中午|傍晚)?\\s*(\\d{1,2})\\s*[点时]\\s*(\\d{1,2})?\\s*分?"
        )

        /** 循环时间正则：「每天早上8点」「每周一9点」「工作日早上9点30分」。 */
        val RECURRING_TIME_REGEX: Regex = Regex(
            "(每天|每周|工作日|每月)(一|二|三|四|五|六|日|天)?\\s*(上午|下午|晚上|早上|中午|傍晚)?\\s*(\\d{1,2})\\s*[点时]\\s*(\\d{1,2})?\\s*分?"
        )

        // ---- 实体识别正则表达式 ----

        /** 电话号码正则（中国大陆手机号，支持 +86 前缀）。 */
        val PHONE_REGEX: Regex = Regex("(?:\\+86)?1[3-9]\\d{9}")

        /** 网址正则（http/https/www 开头）。 */
        val URL_REGEX: Regex = Regex("(?:https?://|www\\.)[\\w\\-]+(\\.[\\w\\-]+)+[/\\w\\-./?%&=#]*")

        /** 方向正则（向上/向下/向左/向右/上滑/下滑等）。 */
        val DIRECTION_REGEX: Regex = Regex("向上|向下|向左|向右|上滑|下滑|左滑|右滑|往上|往下|往左|往右")

        /** 纯数字正则（1-4位数字，避免误匹配电话号码）。 */
        val NUMBER_REGEX: Regex = Regex("\\b\\d{1,4}\\b")

        // ---- 口语填充词 ----

        /** 开头填充词列表（循环去除以处理叠加情况）。 */
        val FILLER_PREFIXES: List<String> = listOf(
            "请", "帮我", "帮", "麻烦", "麻烦帮我", "可以帮我", "能不能帮我",
            "能不能", "我要", "我想", "我需要", "需要", "给我", "来",
            "小助手", "助手", "嘿", "哎", "那个"
        )

        /** 结尾语气词列表。 */
        val FILLER_SUFFIXES: List<String> = listOf(
            "一下", "吧", "呢", "啊", "呀", "哦", "哈", "嘛",
            "可以吗", "行吗", "好吗", "呗"
        )

        // ---- 意图连接词（用于多意图拆分） ----

        /** 意图连接词列表（用于 [detectMultiIntent] 拆分复合指令）。 */
        val INTENT_CONNECTORS: List<String> = listOf(
            "然后", "接着", "之后", "再", "并且", "最后", "然后再",
            "随后", "紧接着", "其次", "然后呢", "然后再来"
        )

        // ---- 意图关键词映射 ----

        /**
         * 意图关键词映射表 —— 每个意图类型对应一组触发关键词。
         * 用于 [extractIntent] 的意图分类。关键词已包含常见同义表达。
         */
        val INTENT_KEYWORDS: Map<IntentType, List<String>> = mapOf(
            IntentType.OPEN_APP to listOf(
                "打开", "启动", "开启", "运行", "开", "进入", "launch", "open"
            ),
            IntentType.SEND_MESSAGE to listOf(
                "发消息", "发送消息", "发信息", "发微信", "发个消息", "发条消息", "send message"
            ),
            IntentType.SEARCH to listOf(
                "搜索", "查找", "查一下", "搜一下", "搜搜", "找一下", "search"
            ),
            IntentType.NAVIGATE to listOf(
                "导航", "去", "到", "路线", "怎么走", "开车去", "navigate"
            ),
            IntentType.SYSTEM_CONTROL to listOf(
                "音量", "亮度", "蓝牙", "wifi", "WiFi", "飞行模式", "热点",
                "电量", "内存", "存储", "电池", "设置", "打开开关", "关闭开关"
            ),
            IntentType.SCREENSHOT to listOf(
                "截图", "截屏", "屏幕截图", "屏幕抓图", "screenshot"
            ),
            IntentType.CLIPBOARD to listOf(
                "复制", "粘贴", "剪贴板", "拷贝", "copy", "paste"
            ),
            IntentType.TIMER to listOf(
                "分钟后", "秒钟后", "小时后", "天后", "提醒", "定时", "闹钟",
                "明天", "后天", "每天", "每周", "定时任务"
            ),
            IntentType.CLEANUP to listOf(
                "清理", "加速", "清缓存", "清理垃圾", "清理手机", "优化", "cleanup", "optimize"
            ),
            IntentType.PHONE_CALL to listOf(
                "打电话", "呼叫", "拨号", "拨打电话", "致电", "call"
            )
        )

        // ---- 同义词映射表 ----

        /**
         * 同义词映射表 —— 键为同义表达，值为规范形式。
         * 用于 [expandSynonyms] 将各种表达统一为规范形式。
         *
         * 替换按键长度降序进行（长词优先），避免短词错误覆盖长词。
         * 例如「屏幕截图」优先于「截图」被替换。
         */
        val SYNONYM_MAP: ConcurrentHashMap<String, String> = ConcurrentHashMap<String, String>().apply {
            // 截图相关
            put("屏幕截图", "截图")
            put("屏幕抓图", "截图")
            put("截屏", "截图")

            // 打开相关
            put("启动", "打开")
            put("开启", "打开")
            put("运行", "打开")
            put("进入", "打开")

            // 关闭相关
            put("退出", "关闭")
            put("杀掉", "关闭")
            put("结束", "关闭")

            // 消息相关
            put("发信息", "发消息")
            put("发送消息", "发消息")
            put("发个消息", "发消息")
            put("发条消息", "发消息")
            put("发微信", "发消息")

            // 搜索相关
            put("查找", "搜索")
            put("查一下", "搜索")
            put("搜一下", "搜索")
            put("搜搜", "搜索")
            put("找一下", "搜索")

            // 电话相关
            put("呼叫", "打电话")
            put("拨号", "打电话")
            put("拨打电话", "打电话")
            put("致电", "打电话")
            put("打电话给", "打电话")

            // 清理相关
            put("加速", "清理")
            put("清缓存", "清理")
            put("清理垃圾", "清理")
            put("清理手机", "清理")
            put("优化", "清理")

            // 剪贴板相关
            put("拷贝", "复制")

            // 导航相关
            put("怎么走", "导航")
            put("开车去", "导航")

            // 提醒相关
            put("闹钟", "提醒")
            put("定时任务", "提醒")

            // 系统控制相关
            put("声音", "音量")
            put("调音量", "音量调")
            put("调亮度", "亮度调")
        }

        /** 同义词条目按键长度降序排列的列表（供 [expandSynonyms] 高效遍历）。 */
        val SYNONYM_ENTRIES: List<Pair<String, String>> =
            SYNONYM_MAP.entries
                .sortedByDescending { it.key.length }
                .map { it.key to it.value }

        // ---- 应用名映射表 ----

        /**
         * 常用应用名到包名的映射表。
         * 用于 [extractAppNames] 实体识别与 [Template] 中的应用名解析。
         * 覆盖国内主流应用，键为应用中文名（小写英文别名也支持）。
         */
        val APP_PACKAGES: ConcurrentHashMap<String, String> = ConcurrentHashMap<String, String>().apply {
            put("微信", "com.tencent.mm")
            put("抖音", "com.ss.android.ugc.aweme")
            put("qq", "com.tencent.mobileqq")
            put("QQ", "com.tencent.mobileqq")
            put("支付宝", "com.eg.android.AlipayGphone")
            put("淘宝", "com.taobao.taobao")
            put("快手", "com.smile.gifmaker")
            put("b站", "tv.danmaku.bili")
            put("B站", "tv.danmaku.bili")
            put("哔哩哔哩", "tv.danmaku.bili")
            put("小红书", "com.xingin.xhs")
            put("美团", "com.sankuai.meituan")
            put("京东", "com.jingdong.app.mall")
            put("拼多多", "com.xunmeng.pinduoduo")
            put("知乎", "com.zhihu.android")
            put("微博", "com.sina.weibo")
            put("钉钉", "com.alibaba.android.rimet")
            put("网易云音乐", "com.netease.cloudmusic")
            put("QQ音乐", "com.tencent.qqmusic")
            put("qq音乐", "com.tencent.qqmusic")
            put("高德地图", "com.autonavi.minimap")
            put("百度地图", "com.baidu.BaiduMap")
            put("今日头条", "com.ss.android.article.news")
            put("腾讯视频", "com.tencent.qqlive")
            put("爱奇艺", "com.qiyi.video")
            put("优酷", "com.youku.phone")
            put("设置", "com.android.settings")
            put("飞书", "com.ss.android.lark")
            put("企业微信", "com.tencent.wework")
            put("百度", "com.baidu.searchbox")
            put("夸克", "com.quark.browser")
            put("豆包", "com.larus.nova")
            put("滴滴", "com.sdu.didi.psnger")
            put("携程", "ctrip.android.view")
            put("12306", "com.MobileTicket")
            put("天猫", "com.tmall.wireless")
            put("饿了么", "me.ele")
            put("大众点评", "com.dianping.v1")
            put("百度网盘", "com.baidu.netdisk")
            put("keep", "com.gotokeep.keep")
            put("Keep", "com.gotokeep.keep")
            put("喜马拉雅", "com.ximalaya.ting.android")
            put("wps", "cn.wps.moffice_eng")
            put("WPS", "cn.wps.moffice_eng")
            put("酷狗音乐", "com.kugou.android")
            put("酷我音乐", "cn.kuwo.player")
            put("芒果TV", "com.hunantv.imgo.activity")
            put("番茄小说", "com.dragon.read")
            put("七猫小说", "com.kmxs.reader")
            put("UC浏览器", "com.UCMobile")
            put("墨迹天气", "com.moji.mojiweather")
            put("美图秀秀", "com.mt.mtxx.mtxx")
            put("浏览器", "com.android.chrome")
            put("相册", "com.android.gallery3d")
            put("相机", "com.android.camera")
            put("计算器", "com.android.calculator2")
            put("日历", "com.android.calendar")
            put("时钟", "com.android.deskclock")
            put("电话", "com.android.dialer")
            put("短信", "com.android.mms")
            put("通讯录", "com.android.contacts")
            put("文件管理", "com.android.filemanager")
        }

        // ---- 预定义模板 ----

        /**
         * 预定义模板列表 —— 覆盖常见指令模式。
         *
         * 每个模板包含正则模式与关键词，命中时获得高置信度。
         * 模板按常见度排序，常用模板靠前以提高匹配效率。
         */
        val TEMPLATES: List<Template> = listOf(

            // —— 打开应用 ——
            Template(
                name = "OPEN_APP",
                intentType = IntentType.OPEN_APP,
                actionType = ActionType.APP_OPEN,
                pattern = Regex("(?:打开|启动|开启|运行|进入)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?(?:$|然后|接着|，|,)"),
                keywords = listOf("打开", "启动", "开启", "运行", "进入"),
                paramGroupNames = listOf("appName"),
                description = "打开指定应用",
                example = "打开微信",
                baseConfidence = 0.95f
            ),

            // —— 关闭应用 ——
            Template(
                name = "CLOSE_APP",
                intentType = IntentType.SYSTEM_CONTROL,
                actionType = ActionType.APP_CLOSE,
                pattern = Regex("(?:关闭|退出|杀掉)\\s*[「「【]?(.+?)[」」】]?(?:应用|app)?(?:$|然后|接着|，|,)"),
                keywords = listOf("关闭", "退出", "杀掉"),
                paramGroupNames = listOf("appName"),
                description = "关闭指定应用",
                example = "关闭微信",
                baseConfidence = 0.95f
            ),

            // —— 截图 ——
            Template(
                name = "SCREENSHOT",
                intentType = IntentType.SCREENSHOT,
                actionType = ActionType.SCREEN_SCREENSHOT,
                pattern = Regex("(?:截图|截屏|屏幕截图|屏幕抓图)"),
                keywords = listOf("截图", "截屏", "屏幕截图"),
                paramGroupNames = emptyList(),
                description = "截取当前屏幕",
                example = "截图",
                baseConfidence = 0.98f
            ),

            // —— 发送消息 ——
            Template(
                name = "SEND_MESSAGE",
                intentType = IntentType.SEND_MESSAGE,
                actionType = ActionType.SCREEN_INPUT,
                pattern = Regex("(?:用|通过|使用)?\\s*[「「【]?(.+?)[」」】]?\\s*给\\s*[「「【]?(.+?)[」」】]?\\s*(?:发|发送)?\\s*(?:消息|信息)?\\s*[「「【\"']?(.+?)[」」】\"']?(?:$|然后|接着|，|,)"),
                keywords = listOf("发消息", "发送消息", "发信息"),
                paramGroupNames = listOf("appName", "contactName", "messageContent"),
                description = "通过应用给联系人发送消息",
                example = "用微信给张三发消息你好",
                baseConfidence = 0.9f
            ),

            // —— 搜索内容 ——
            Template(
                name = "SEARCH",
                intentType = IntentType.SEARCH,
                actionType = ActionType.SCREEN_INPUT,
                pattern = Regex("(?:在|用|使用)?\\s*[「「【]?(.+?)[」」】]?\\s*(?:里|中|里面|上)?\\s*搜索\\s*[「「【\"']?(.+?)[」」】\"']?(?:$|然后|接着|，|,)"),
                keywords = listOf("搜索", "查找", "搜一下"),
                paramGroupNames = listOf("appName", "searchContent"),
                description = "在指定应用中搜索内容",
                example = "在抖音搜索猫咪",
                baseConfidence = 0.9f
            ),

            // —— 通用搜索 ——
            Template(
                name = "SEARCH_GENERIC",
                intentType = IntentType.SEARCH,
                actionType = ActionType.SCREEN_INPUT,
                pattern = Regex("(?:搜索|查找|搜一下)\\s*[「「【\"']?(.+?)[」」】\"']?(?:$|然后|接着|，|,)"),
                keywords = listOf("搜索", "查找", "搜一下"),
                paramGroupNames = listOf("searchContent"),
                description = "搜索指定内容",
                example = "搜索猫咪",
                baseConfidence = 0.85f
            ),

            // —— 拨打电话 ——
            Template(
                name = "PHONE_CALL",
                intentType = IntentType.PHONE_CALL,
                actionType = ActionType.APP_OPEN,
                pattern = Regex("打电话给\\s*[「「【]?(.+?)[」」】]?(?:$|然后|接着|，|,)"),
                keywords = listOf("打电话", "呼叫", "拨号"),
                paramGroupNames = listOf("contactName"),
                description = "拨打电话给联系人",
                example = "打电话给张三",
                baseConfidence = 0.92f
            ),

            // —— 拨打号码 ——
            Template(
                name = "PHONE_CALL_NUMBER",
                intentType = IntentType.PHONE_CALL,
                actionType = ActionType.APP_OPEN,
                pattern = Regex("(?:打电话给|拨打|呼叫)\\s*((?:\\+86)?1[3-9]\\d{9})"),
                keywords = listOf("拨打", "呼叫"),
                paramGroupNames = listOf("phoneNumber"),
                description = "拨打指定电话号码",
                example = "拨打13800138000",
                baseConfidence = 0.95f
            ),

            // —— 设置音量 ——
            Template(
                name = "SET_VOLUME",
                intentType = IntentType.SYSTEM_CONTROL,
                actionType = ActionType.SYSTEM_SET_VOLUME,
                pattern = Regex("(?:音量|声音)\\s*(?:调|设|设置为?|调到|调成)\\s*(\\d{1,3})"),
                keywords = listOf("音量", "声音"),
                paramGroupNames = listOf("volume"),
                description = "设置音量到指定值",
                example = "音量调到50",
                baseConfidence = 0.95f
            ),

            // —— 设置亮度 ——
            Template(
                name = "SET_BRIGHTNESS",
                intentType = IntentType.SYSTEM_CONTROL,
                actionType = ActionType.SYSTEM_SET_BRIGHTNESS,
                pattern = Regex("(?:亮度)\\s*(?:调|设|设置为?|调到|调成)\\s*(\\d{1,3})"),
                keywords = listOf("亮度"),
                paramGroupNames = listOf("brightness"),
                description = "设置屏幕亮度到指定值",
                example = "亮度调到128",
                baseConfidence = 0.95f
            ),

            // —— 复制到剪贴板 ——
            Template(
                name = "CLIPBOARD_COPY",
                intentType = IntentType.CLIPBOARD,
                actionType = ActionType.CLIPBOARD_COPY,
                pattern = Regex("复制\\s*[「「【\"']?(.+?)[」」】\"']?(?:$|然后|接着|，|,)"),
                keywords = listOf("复制", "拷贝"),
                paramGroupNames = listOf("text"),
                description = "复制文本到剪贴板",
                example = "复制你好",
                baseConfidence = 0.92f
            ),

            // —— 粘贴剪贴板 ——
            Template(
                name = "CLIPBOARD_PASTE",
                intentType = IntentType.CLIPBOARD,
                actionType = ActionType.CLIPBOARD_PASTE,
                pattern = Regex("粘贴"),
                keywords = listOf("粘贴"),
                paramGroupNames = emptyList(),
                description = "粘贴剪贴板内容",
                example = "粘贴",
                baseConfidence = 0.95f
            ),

            // —— 定时/延迟任务 ——
            Template(
                name = "TIMER_SET",
                intentType = IntentType.TIMER,
                actionType = ActionType.TIMER_SET,
                pattern = Regex("(\\d+)\\s*(秒钟|秒|分钟|分|小时|时|天)\\s*后\\s*(?:提醒我|提醒|之后)?\\s*(.+)?"),
                keywords = listOf("分钟后", "秒钟后", "小时后", "天后", "提醒"),
                paramGroupNames = listOf("amount", "unit", "task"),
                description = "设置定时提醒",
                example = "10分钟后提醒我喝水",
                baseConfidence = 0.9f
            ),

            // —— 清理缓存 ——
            Template(
                name = "CLEANUP",
                intentType = IntentType.CLEANUP,
                actionType = ActionType.SYSTEM_CLEAR_CACHE,
                pattern = Regex("(?:清理|加速|清缓存|清理垃圾|清理手机|优化)(?:手机|缓存|垃圾)?"),
                keywords = listOf("清理", "加速", "清缓存", "清理垃圾", "优化"),
                paramGroupNames = emptyList(),
                description = "清理缓存并优化系统",
                example = "清理缓存",
                baseConfidence = 0.95f
            ),

            // —— 导航 ——
            Template(
                name = "NAVIGATE",
                intentType = IntentType.NAVIGATE,
                actionType = ActionType.APP_OPEN,
                pattern = Regex("(?:导航到|导航|开车去|去|到)\\s*[「「【]?(.+?)[」」】]?(?:$|然后|接着|，|,)"),
                keywords = listOf("导航", "开车去", "怎么走"),
                paramGroupNames = listOf("destination"),
                description = "导航到指定地点",
                example = "导航到公司",
                baseConfidence = 0.88f
            ),

            // —— 获取系统信息 ——
            Template(
                name = "GET_SYSTEM_INFO",
                intentType = IntentType.SYSTEM_CONTROL,
                actionType = ActionType.SYSTEM_GET_INFO,
                pattern = Regex("(?:查看|查询|看一下|显示)\\s*(内存|电量|电池|存储|CPU|手机信息|系统信息)"),
                keywords = listOf("查看内存", "查看电量", "查看电池", "查看存储", "查看CPU", "手机信息"),
                paramGroupNames = listOf("infoType"),
                description = "获取系统信息",
                example = "查看电量",
                baseConfidence = 0.92f
            ),

            // —— 按键操作 ——
            Template(
                name = "KEY_PRESS",
                intentType = IntentType.SYSTEM_CONTROL,
                actionType = ActionType.SCREEN_KEY,
                pattern = Regex("(?:按|点击)\\s*(返回键|返回|Home键|home键|主键|菜单键|最近任务|电源键|锁屏|home)"),
                keywords = listOf("按返回", "按Home", "按电源", "锁屏", "返回键"),
                paramGroupNames = listOf("key"),
                description = "执行按键操作",
                example = "按返回键",
                baseConfidence = 0.9f
            ),

            // —— 滑动操作 ——
            Template(
                name = "SWIPE",
                intentType = IntentType.SYSTEM_CONTROL,
                actionType = ActionType.SCREEN_SWIPE,
                pattern = Regex("(?:向上|向下|向左|向右|上滑|下滑|左滑|右滑)\\s*(?:滑动|滑)?"),
                keywords = listOf("向上", "向下", "向左", "向右", "上滑", "下滑", "左滑", "右滑"),
                paramGroupNames = listOf("direction"),
                description = "执行滑动操作",
                example = "向上滑动",
                baseConfidence = 0.9f
            ),

            // —— 输入文本 ——
            Template(
                name = "INPUT_TEXT",
                intentType = IntentType.SEARCH,
                actionType = ActionType.SCREEN_INPUT,
                pattern = Regex("输入\\s*[「「【\"']?(.+?)[」」】\"']?(?:$|然后|接着|，|,)"),
                keywords = listOf("输入"),
                paramGroupNames = listOf("text"),
                description = "输入指定文本",
                example = "输入你好世界",
                baseConfidence = 0.88f
            )
        )
    }
}
