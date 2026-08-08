package com.mobileclaw.app.ai

import android.util.Log

/**
 * 任务复杂度分析器 —— 智能评估用户指令的复杂度等级。
 *
 * 核心理念：不同复杂度的任务需要不同的 Token 预算。
 * - 简单任务（"截个屏"）→ 极少 Token 即可完成
 * - 中等任务（"打开抖音搜索猫咪"）→ 需要适量 Token
 * - 复杂任务（"打开微信给XXX发消息说YYY"）→ 需要充足 Token 才能分解为多步动作
 *
 * 分析维度：
 * 1. 关键词模式匹配（"打开+搜索"、"发消息"、"设置"等组合）
 * 2. 动作数量预估（一个指令需要几步完成）
 * 3. 是否涉及多应用切换
 * 4. 是否涉及文本输入
 * 5. 是否涉及联系人/文件等需要查找的操作
 *
 * 智能调节策略：根据复杂度等级动态设置以下参数：
 * - maxTokens：简单 512 / 中等 1024 / 复杂 2048
 * - 历史轮数：简单 0 / 中等 1 / 复杂 3
 * - 提示词级别：简单 ULTRA / 中等 COMPACT / 复杂 FULL
 * - 屏幕文本截断：简单 200 / 中等 400 / 复杂 800 字符
 */
object TaskComplexityAnalyzer {

    private const val TAG = "ComplexityAnalyzer"

    /**
     * 任务复杂度等级。
     *
     * @param level 等级数值（1=简单, 2=中等, 3=复杂）
     * @param maxTokens 该等级下的最大输出 Token 数
     * @param historyTurns 该等级下保留的对话历史轮数
     * @param screenTextLimit 该等级下屏幕文本的截断长度
     * @param promptLevel 该等级下使用的系统提示词级别
     */
    enum class Complexity(
        val level: Int,
        val maxTokens: Int,
        val historyTurns: Int,
        val screenTextLimit: Int,
        val promptLevel: PromptLevel
    ) {
        /** 微操作：极简单步操作，如截图、按返回键。maxTokens 256 极致节省。 */
        MICRO(0, maxTokens = 256, historyTurns = 0, screenTextLimit = 0, PromptLevel.MICRO),

        /** 简单：单步操作，如打开应用、查看内存。maxTokens 512 确保JSON完整。 */
        SIMPLE(1, maxTokens = 512, historyTurns = 0, screenTextLimit = 200, PromptLevel.ULTRA),

        /** 中等：2-5 步操作，如打开应用搜索、清理缓存。 */
        MEDIUM(2, maxTokens = 1024, historyTurns = 1, screenTextLimit = 400, PromptLevel.COMPACT),

        /** 复杂：6+ 步操作，如发消息、多应用协作、文件操作。 */
        COMPLEX(3, maxTokens = 2048, historyTurns = 3, screenTextLimit = 800, PromptLevel.FULL),

        /** 无限制：智能模式关闭时使用，最大质量配置（不限制 Token、保留更多历史）。 */
        UNLIMITED(4, maxTokens = 0, historyTurns = 6, screenTextLimit = 1200, PromptLevel.FULL)
    }

    /**
     * 系统提示词级别。
     *
     * - MICRO：超极简，仅格式和动作名（用于微操作，不发送屏幕文本）
     * - ULTRA：极简，仅动作列表和基本格式（用于简单任务）
     * - COMPACT：压缩版，含示例和包名（用于中等任务）
     * - FULL：完整版，含多步教学、详细规则（用于复杂任务）
     */
    enum class PromptLevel {
        MICRO,
        ULTRA,
        COMPACT,
        FULL
    }

    // ---- 复杂度判定关键词 ----

    /** 标志着复杂任务的高权重关键词组合。 */
    private val COMPLEX_PATTERNS = listOf(
        // 发消息类（需要打开应用→搜索联系人→输入→发送，至少6步）
        Regex("(?i)(发|发送|发条|发个).*(消息|微信|短信|信息|内容)"),
        Regex("(?i)(给|跟).*(发|说|告诉|回复)"),
        // 搜索并操作类
        Regex("(?i)(搜索|查找|找).*(然后|再|并).*(点击|打开|播放|下载)"),
        // 多步骤操作
        Regex("(?i)(打开).*(并|然后|再|之后).*(搜索|输入|发送|点击|设置|添加|删除)"),
        // 打开应用+直接跟操作（无连接词，如"打开微信发消息"、"打开抖音搜索XX"）
        Regex("(?i)(打开|启动).{1,15}(发|发送|搜索|输入|回复|分享|转发|添加|删除|设置|调节)"),
        // 文件操作
        Regex("(?i)(复制|移动|删除|创建|重命名).*(文件|文件夹|目录)"),
        // 系统设置修改
        Regex("(?i)(修改|更改|设置|调节|调整).*(亮度|音量|壁纸|蓝牙|WiFi|热点|飞行模式)"),
        // 多应用协作
        Regex("(?i)(从|在).*(复制|分享|转发|发送).*(到|给|至)"),
        // 联系人操作
        Regex("(?i)(添加|删除|编辑|修改).*(联系人|号码|电话)")
    )

    /** 标志着中等复杂度的关键词。 */
    private val MEDIUM_PATTERNS = listOf(
        // 搜索类（打开应用→搜索，3-4步）
        Regex("(?i)(搜索|查找|搜一下|查一下)"),
        // 打开并操作
        Regex("(?i)(打开|启动).*(搜索|播放|查看|浏览)"),
        // 输入类
        Regex("(?i)(输入|填写|编辑)"),
        // 滑动/滚动类
        Regex("(?i)(滑动|滚动|翻到|滑到)"),
        // 安装/卸载
        Regex("(?i)(安装|卸载|下载)"),
        // 截图后操作
        Regex("(?i)(截图|截屏).*(然后|再|并|发送|分享|保存)")
    )

    /** 微操作关键词：极简单步操作，无需屏幕文本上下文。 */
    private val MICRO_KEYWORDS = listOf(
        "截图", "截屏", "截个屏",
        "返回", "后退", "按返回",
        "home", "主屏", "桌面", "按home",
        "锁屏",
        "音量加", "音量减", "静音"
    )

    /** 标志着简单任务的关键词（单步操作）。 */
    private val SIMPLE_KEYWORDS = listOf(
        "截图", "截屏", "截个屏",
        "返回", "后退", "按返回",
        "home", "主屏", "桌面", "按home",
        "最近任务", "多任务",
        "内存", "电量", "电池", "cpu", "存储", "系统信息",
        "清理缓存", "清缓存",
        "锁屏", "亮屏",
        "音量加", "音量减", "静音",
        // 系统设置类单步操作
        "打开wifi", "关闭wifi", "开关wifi", "打开蓝牙", "关闭蓝牙", "开关蓝牙",
        "打开飞行模式", "关闭飞行模式", "打开热点", "关闭热点",
        "打开手电筒", "关闭手电筒",
        "调大音量", "调小音量", "音量最大", "音量最小",
        "调亮", "调暗", "亮度最大", "亮度最小",
        "打开数据", "关闭数据", "开关数据"
    )

    /** 简单打开应用（不涉及后续操作）。 */
    private val SIMPLE_OPEN_PATTERN = Regex("^(?i)(打开|启动|launch|开启)\\s*[\\u4e00-\\u9fa5a-zA-Z0-9_\\s]{1,10}$")

    /**
     * 分析用户指令的复杂度。
     *
     * 分析流程：
     * 1. 先检查是否匹配简单关键词（快速路径，命中率最高）
     * 2. 检查复杂模式（高权重组合）
     * 3. 检查中等模式
     * 4. 根据指令长度和动作动词数量做兜底推断
     *
     * @param userInput 用户的自然语言指令
     * @return 复杂度等级
     */
    fun analyze(userInput: String): Complexity {
        val input = userInput.trim().lowercase()
        val originalInput = userInput.trim()

        // 0. 超快速路径：微操作关键词（截图、返回等极简单步操作）
        // 仅当输入非常短且精确匹配微操作关键词时才判定为 MICRO
        if (originalInput.length <= 6) {
            val microMatch = MICRO_KEYWORDS.any { keyword ->
                input == keyword.lowercase() || input == "按$keyword" ||
                input == "帮我$keyword" || input == "一下$keyword"
            }
            if (microMatch) {
                Log.d(TAG, "微操作（关键词精确匹配）: $originalInput")
                return Complexity.MICRO
            }
        }

        // 1. 快速路径：匹配简单关键词
        if (SIMPLE_KEYWORDS.any { input.contains(it.lowercase()) }) {
            // 但需要排除"截图后发送"这种中等复杂度
            val isSimpleKeyword = SIMPLE_KEYWORDS.any { keyword ->
                input.contains(keyword.lowercase()) &&
                !input.contains("然后") && !input.contains("再") &&
                !input.contains("并") && !input.contains("之后") &&
                !input.contains("发送") && !input.contains("分享") &&
                !input.contains("转发")
            }
            if (isSimpleKeyword) {
                Log.d(TAG, "简单任务（关键词匹配）: $originalInput")
                return Complexity.SIMPLE
            }
        }

        // 简单打开应用："打开微信"（无后续操作）
        if (SIMPLE_OPEN_PATTERN.matches(originalInput) && input.length <= 12) {
            Log.d(TAG, "简单任务（打开应用）: $originalInput")
            return Complexity.SIMPLE
        }

        // 2. 检查复杂模式
        if (COMPLEX_PATTERNS.any { it.containsMatchIn(originalInput) }) {
            Log.d(TAG, "复杂任务（模式匹配）: $originalInput")
            return Complexity.COMPLEX
        }

        // 3. 检查中等模式
        if (MEDIUM_PATTERNS.any { it.containsMatchIn(originalInput) }) {
            Log.d(TAG, "中等任务（模式匹配）: $originalInput")
            return Complexity.MEDIUM
        }

        // 4. 兜底：根据指令长度和动词数量推断
        val verbCount = countActionVerbs(originalInput)
        val complexity = when {
            originalInput.length <= 8 && verbCount <= 1 -> Complexity.SIMPLE
            originalInput.length <= 25 && verbCount <= 2 -> Complexity.MEDIUM
            verbCount >= 3 || originalInput.length > 40 -> Complexity.COMPLEX
            else -> Complexity.MEDIUM
        }

        Log.d(TAG, "兜底判定（长度=${originalInput.length}, 动词数=$verbCount）→ ${complexity.name}: $originalInput")
        return complexity
    }

    /**
     * 统计指令中包含的动作动词数量。
     *
     * 动词越多，任务越可能需要多步完成。
     */
    private fun countActionVerbs(input: String): Int {
        val verbs = listOf(
            "打开", "关闭", "点击", "输入", "搜索", "发送",
            "滑动", "滚动", "截图", "等待", "返回", "按",
            "复制", "粘贴", "删除", "安装", "卸载", "下载",
            "播放", "暂停", "停止", "保存", "分享", "转发",
            "设置", "修改", "查看", "清", "添加", "创建"
        )
        return verbs.count { input.contains(it) }
    }

    /**
     * 根据复杂度生成对应的系统提示词。
     *
     * 委托给 [ActionTranslator] 的三个级别方法。
     */
    fun generatePrompt(complexity: Complexity, phoneState: PhoneState): String {
        return when (complexity.promptLevel) {
            PromptLevel.MICRO -> ActionTranslator.generateSystemPromptMicro(phoneState)
            PromptLevel.ULTRA -> ActionTranslator.generateSystemPromptUltra(phoneState, complexity.screenTextLimit)
            PromptLevel.COMPACT -> ActionTranslator.generateSystemPromptCompact(phoneState)
            PromptLevel.FULL -> ActionTranslator.generateSystemPrompt(phoneState)
        }
    }

    /**
     * 根据 [EvaluationResult] 的精确参数生成系统提示词。
     *
     * 与 [generatePrompt] 不同，此方法使用评估结果中的精确 screenTextLimit，
     * 而非固定等级的预设值。这样评估模型可以给出如 350 字符这种非标准截断长度。
     */
    fun generatePromptFromEvaluation(result: EvaluationResult, phoneState: PhoneState): String {
        return when (result.promptLevel) {
            PromptLevel.MICRO -> ActionTranslator.generateSystemPromptMicro(phoneState)
            PromptLevel.ULTRA -> ActionTranslator.generateSystemPromptUltra(phoneState, result.screenTextLimit)
            PromptLevel.COMPACT -> ActionTranslator.generateSystemPromptCompact(phoneState, result.screenTextLimit)
            PromptLevel.FULL -> ActionTranslator.generateSystemPrompt(phoneState)
        }
    }

    /**
     * 将本地分析的 [Complexity] 转换为 [EvaluationResult]，
     * 用于评估器降级时保持统一的参数访问接口。
     */
    fun toEvaluationResult(complexity: Complexity, userInput: String): EvaluationResult {
        return EvaluationResult(
            estimatedSteps = when (complexity) {
                Complexity.MICRO -> 1
                Complexity.SIMPLE -> 1
                Complexity.MEDIUM -> 3
                Complexity.COMPLEX -> 8
                Complexity.UNLIMITED -> 10
            },
            maxTokens = complexity.maxTokens,
            promptLevel = complexity.promptLevel,
            screenTextLimit = complexity.screenTextLimit,
            isMultiStep = complexity.level >= Complexity.MEDIUM.level,
            reason = "本地分析（${complexity.name}）",
            source = EvaluationSource.LOCAL,
            originalInput = userInput
        )
    }
}
