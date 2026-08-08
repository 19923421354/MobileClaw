package com.mobileclaw.app.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

// =============================================================================
//  内部数据模型
// =============================================================================

/**
 * 命令匹配结果。
 *
 * 由 [LocalCommandExecutor.match] 返回，包含匹配到的动作、置信度分数以及
 * 对人类可读的描述。高置信度（>= [LocalCommandExecutor.MIN_CONFIDENCE_AUTO]）
 * 的结果可直接自动执行，无需用户确认。
 *
 * @param actions      匹配到的动作列表（单步命令通常只有一个动作）
 * @param confidence   匹配置信度，取值范围 0.0（完全不匹配）~ 1.0（完全匹配）
 * @param description  该命令的人类可读描述（中文），用于日志与 UI 展示
 * @param rawInput     原始用户输入文本
 */
data class ExecuteResult(
    val actions: List<ClawAction>,
    val confidence: Double,
    val description: String,
    val rawInput: String
) {
    /** 是否达到可自动执行的高置信度阈值。 */
    val isAutoExecutable: Boolean
        get() = confidence >= LocalCommandExecutor.MIN_CONFIDENCE_AUTO

    /** 是否达到可建议执行的中等置信度阈值。 */
    val isSuggestable: Boolean
        get() = confidence >= LocalCommandExecutor.MIN_CONFIDENCE_SUGGEST

    /** 是否为空匹配（无对应动作）。 */
    val isEmpty: Boolean
        get() = actions.isEmpty()

    /** 是否为多步复合命令。 */
    val isComposite: Boolean
        get() = actions.size > 1
}

/**
 * 命令匹配统计信息。
 *
 * 记录 LocalCommandExecutor 从启动至今的匹配/执行统计，用于性能监控与调试。
 *
 * @param totalMatches      总匹配次数
 * @param autoExecutions    自动执行次数（高置信度）
 * @param manualExecutions  用户确认后执行次数
 * @param failedExecutions  执行失败次数
 * @param averageMatchTime  平均匹配耗时（毫秒）
 * @param topCommands       最常匹配的前 N 条命令统计
 * @param startTime         统计起始时间戳
 */
data class ExecutorStats(
    val totalMatches: Long = 0,
    val autoExecutions: Long = 0,
    val manualExecutions: Long = 0,
    val failedExecutions: Long = 0,
    val averageMatchTime: Double = 0.0,
    val topCommands: Map<String, Long> = emptyMap(),
    val startTime: Long = System.currentTimeMillis()
)

/**
 * 注册的自定义命令。
 *
 * 用户可通过 [LocalCommandExecutor.addCustomCommand] 注册自定义命令，
 * 实现个性化的命令响应。
 *
 * @param pattern    匹配模式（正则表达式字符串）
 * @param actionName 动作类型名称（需与 [ActionType] 的 name 一致）
 * @param params     动作参数（JsonObject）
 * @param description 命令描述
 * @param priority   优先级，数值越大越优先匹配（默认 0）
 */
data class CustomCommand(
    val pattern: String,
    val actionName: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val description: String = "",
    val priority: Int = 0
)

// =============================================================================
//  LocalCommandExecutor - 本地命令执行器
// =============================================================================

/**
 * LocalCommandExecutor - 本地命令执行器
 *
 * 【核心职责】
 * 将用户的自然语言指令（中文/英文）通过正则表达式匹配到预定义的 [ClawAction]，
 * 完全在本地执行，**无需任何 AI API 调用**。
 *
 * 适用场景：
 * - 打开应用（"打开微信"、"启动抖音"）
 * - 系统操作（"截屏"、"返回"、"回桌面"、"锁屏"）
 * - 媒体控制（"播放"、"下一首"、"增大音量"）
 * - 系统信息查询（"查看内存"、"电量"、"CPU使用"）
 * - 清理维护（"清理缓存"）
 * - 复合命令（"在微信搜索AAA"、"给张三发消息"）
 *
 * 【设计原则】
 * 1. 完全本地化：所有命令匹配均在本地完成，零网络依赖。
 * 2. 置信度评分：每条匹配结果附带 0.0~1.0 的置信度分数，便于上层决策。
 * 3. 线程安全：所有可变状态存储在 [ConcurrentHashMap] 中，支持多线程并发访问。
 * 4. 可扩展：支持 [addCustomCommand] 动态注册自定义命令。
 * 5. 中文优先：所有文档、注释和命令描述均以中文为主。
 *
 * 【使用示例】
 * ```
 * val executor = LocalCommandExecutor()
 * val result = executor.match("打开微信")
 * if (result.isAutoExecutable) {
 *     executor.execute(result)
 * }
 * ```
 *
 * @property customCommands 用户注册的自定义命令列表
 * @property matchHistory   匹配历史记录（用于统计）
 */
class LocalCommandExecutor {

    // =========================================================================
    //  属性
    // =========================================================================

    /** 用户自定义命令列表（按优先级排序）。 */
    private val customCommands = ConcurrentHashMap.newKeySet<CustomCommand>()

    /** 匹配历史记录（用于统计）。 */
    private val matchHistory = ConcurrentHashMap<String, AtomicLong>()

    /** 总匹配次数。 */
    private val totalMatches = AtomicLong(0)

    /** 自动执行次数。 */
    private val autoExecutions = AtomicLong(0)

    /** 用户确认后执行次数。 */
    private val manualExecutions = AtomicLong(0)

    /** 执行失败次数。 */
    private val failedExecutions = AtomicLong(0)

    /** 总匹配耗时（毫秒），用于计算平均值。 */
    private val totalMatchTime = AtomicLong(0)

    /** 统计起始时间戳。 */
    private val statsStartTime = System.currentTimeMillis()

    // =========================================================================
    //  上下文感知与学习系统
    // =========================================================================

    /** 上一次打开的应用名称（用于代词消解）。 */
    @Volatile
    var lastOpenedApp: String? = null

    /** 上一次打开的应用包名（用于后续操作）。 */
    @Volatile
    var lastOpenedPackage: String? = null

    /** 用户纠正学习映射表（用户说"不是A是B"时记录）。 */
    private val correctionMap = ConcurrentHashMap<String, String>()

    /** 用户自定义别名映射表。 */
    private val aliasMap = ConcurrentHashMap<String, String>()

    /** 应用使用频率统计（应用名 -> 打开次数）。 */
    private val appUsageFrequency = ConcurrentHashMap<String, AtomicLong>()

    /** 最近使用的应用列表（按时间倒序，最多20个）。 */
    private val recentApps = java.util.LinkedList<String>()

    /** 用户偏好评分（应用名 -> 偏好分数，基于使用频率+最近使用+场景匹配综合计算）。 */
    private val appPreferenceScore = ConcurrentHashMap<String, Double>()

    // =========================================================================
    //  上下文感知方法
    // =========================================================================

    /**
     * 解析代词（他/她/它/那个/这个）为上下文中的应用名。
     *
     * 例如：
     * - "打开豆包" -> lastOpenedApp = "豆包"
     * - "给他发一条你好" -> "给豆包发一条你好"
     *
     * @param input 用户原始输入
     * @return 解析代词后的输入
     */
    private fun resolvePronouns(input: String): String {
        // 如果当前命令包含代词且没有指定应用名，且有上次打开的应用
        if (lastOpenedApp != null) {
            val pronounPattern = Regex(
                """(?:给|向|对|在|用|帮|把|和|与|跟)\s*(?:他|她|它|祂|那个|这个|该|其)\s*""",
                RegexOption.IGNORE_CASE
            )
            if (pronounPattern.containsMatchIn(input) &&
                !input.contains("打开") && !APP_PACKAGE_MAP.keys.any { input.contains(it) }
            ) {
                // 替换代词为上次打开的应用名
                val resolved = input.replace(Regex("""(?:给|向|对|在|用|帮|把|和|与|跟)\s*(?:他|她|它|祂|那个|这个|该|其)""")) {
                    val prefix = it.value.substringBefore(Regex("""他|她|它|祂|那个|这个|该|其""").find(it.value)?.value ?: "他")
                    "$prefix${lastOpenedApp}"
                }
                return resolved
            }
        }
        return input
    }

    /**
     * 从用户纠正中学习。
     *
     * 支持格式：
     * - "不是A是B" / "不是A，是B"
     * - "不是打开A，是打开B"
     * - "我说的是A不是B"
     *
     * @param input 用户输入
     * @return 如果检测到纠正，返回纠正后的输入；否则返回原输入
     */
    private fun learnFromCorrection(input: String): String {
        // 模式1：不是A是B / 不是A，是B
        val correctionPattern = Regex(
            """(?:不(?:是|对|应该)\s*(?:打开|启动|开启|运行)?\s*(.+?)\s*[，,]\s*(?:是|应该)\s*(?:打开|启动|开启|运行)?\s*(.+))""",
            RegexOption.IGNORE_CASE
        )
        val match = correctionPattern.find(input)
        if (match != null) {
            val wrong = match.groupValues[1].trim()
            val correct = match.groupValues[2].trim()
            // 学习纠正映射
            if (wrong.length <= 10 && correct.length <= 10) {
                correctionMap[wrong] = correct
                // 也记录到别名映射
                if (APP_PACKAGE_MAP.containsKey(correct)) {
                    aliasMap[wrong] = APP_PACKAGE_MAP[correct]!!
                }
            }
            // 返回纠正后的命令
            return input.replace(wrong, correct)
        }

        // 模式2：我说的是A不是B
        val correctionPattern2 = Regex(
            """(?:我(?:说|指|要)\s*(?:的)?\s*(?:是)?\s*(.+?)\s*(?:不|不是)\s*(.+))""",
            RegexOption.IGNORE_CASE
        )
        val match2 = correctionPattern2.find(input)
        if (match2 != null) {
            val wrong = match2.groupValues[2].trim()
            val correct = match2.groupValues[1].trim()
            // 提取正确的应用名
            val correctApp = APP_PACKAGE_MAP.keys.firstOrNull { correct.contains(it) || it.contains(correct) }
            val wrongApp = APP_PACKAGE_MAP.keys.firstOrNull { wrong.contains(it) || it.contains(wrong) }
            if (correctApp != null && wrongApp != null) {
                correctionMap[wrongApp] = correctApp
                aliasMap[wrongApp] = APP_PACKAGE_MAP[correctApp]!!
            }
            return input.replace(wrong, correct)
        }

        // 模式3：A改为B / A改成B / 把A改成B
        val correctionPattern3 = Regex(
            """(?:把\s*)?(.+?)\s*(?:改(?:为|成|名叫?))\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val match3 = correctionPattern3.find(input)
        if (match3 != null) {
            val wrong = match3.groupValues[1].trim()
            val correct = match3.groupValues[2].trim()
            if (wrong.length <= 10 && correct.length <= 10) {
                correctionMap[wrong] = correct
                if (APP_PACKAGE_MAP.containsKey(correct)) {
                    aliasMap[wrong] = APP_PACKAGE_MAP[correct]!!
                }
            }
            return input.replace(wrong, correct)
        }

        // 模式4：记住A是B / 记住A叫B
        val correctionPattern4 = Regex(
            """(?:记住|记好|记下)\s*(.+?)\s*(?:是|叫|就是)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val match4 = correctionPattern4.find(input)
        if (match4 != null) {
            val alias = match4.groupValues[1].trim()
            val name = match4.groupValues[2].trim()
            if (alias.length <= 10 && name.length <= 10 && APP_PACKAGE_MAP.containsKey(name)) {
                aliasMap[alias] = APP_PACKAGE_MAP[name]!!
                correctionMap[alias] = name
            }
            return input.replace(alias, name)
        }

        return input
    }

    /**
     * 获取智能建议：当命令未匹配时，给出最相似的命令建议。
     * 增强版：融合使用频率、最近使用、时段场景进行智能排序。
     *
     * @param input 用户输入
     * @return 建议列表，最多3条
     */
    private fun getSuggestions(input: String): List<String> {
        val suggestions = mutableListOf<String>()
        val seenApps = mutableSetOf<String>()

        // 0. 基于时间的使用场景预测（优先推荐常用应用）
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val timeSuggestions = when (hour) {
            in 5..8 -> listOf(
                "天气" to "查看今日天气",
                "新闻" to "浏览早间新闻",
                "日历" to "查看今日日程"
            )
            in 9..11 -> listOf(
                "微信" to "查看微信消息",
                "钉钉" to "查看工作消息",
                "邮箱" to "查看邮件"
            )
            in 11..13 -> listOf(
                "美团外卖" to "点外卖",
                "饿了么" to "点外卖",
                "支付宝" to "打开支付宝"
            )
            in 14..17 -> listOf(
                "音乐" to "听音乐放松",
                "抖音" to "刷抖音",
                "小红书" to "浏览小红书"
            )
            in 18..20 -> listOf(
                "美团外卖" to "点晚餐",
                "饿了么" to "点晚餐",
                "腾讯视频" to "看视频"
            )
            in 21..23 -> listOf(
                "腾讯视频" to "看视频/电影",
                "哔哩哔哩" to "刷B站",
                "抖音" to "刷抖音"
            )
            else -> listOf(
                "微信" to "查看消息",
                "抖音" to "刷抖音",
                "音乐" to "听音乐"
            )
        }
        // 如果输入太短，直接给出时间建议（按使用频率排序）
        if (input.length <= 2 && suggestions.isEmpty()) {
            // 先按偏好评分排序
            val preferredApps = getPreferredApps()
            val sortedTimeSuggestions = timeSuggestions.sortedByDescending { (app, _) ->
                preferredApps.indexOfFirst { it.first == app }.let { if (it >= 0) preferredApps.size - it else 0 }
            }
            for ((app, desc) in sortedTimeSuggestions) {
                if (APP_PACKAGE_MAP.containsKey(app) && app !in seenApps) {
                    suggestions.add("现在是${hour}点，试试「打开$desc」")
                    seenApps.add(app)
                    if (suggestions.size >= 3) break
                }
            }
        }

        // 1. 检查应用名模糊匹配（使用加权编辑距离，按偏好评分排序）
        if (suggestions.size < 3) {
            // 收集所有匹配的应用及其评分
            val matchCandidates = mutableListOf<Pair<String, Double>>()
            for (name in APP_PACKAGE_MAP.keys) {
                // 加权编辑距离：对常见按键错误（如键盘相邻键）赋予较小权值
                val weightedDistance = weightedEditDistance(input, name)
                val distance = levenshteinDistance(input, name)
                val effectiveDistance = minOf(weightedDistance, distance)

                if (effectiveDistance <= 3 && effectiveDistance > 0) {
                    val confidence = (1.0 - effectiveDistance.toDouble() / maxOf(name.length, input.length).toDouble()).coerceIn(0.3, 0.95)
                    // 融合偏好评分
                    val preferenceBonus = appPreferenceScore[name] ?: 0.0
                    val finalScore = confidence * 0.7 + preferenceBonus * 0.3
                    matchCandidates.add(name to finalScore)
                }
                // 检查包含关系
                if (input.length >= 1 && name.length > 1 && input.contains(name.substring(0, 1))) {
                    val jaccard = jaccardSimilarity(input, name)
                    if (jaccard > 0.3) {
                        val confidence = jaccard.coerceIn(0.3, 0.95)
                        val preferenceBonus = appPreferenceScore[name] ?: 0.0
                        val finalScore = confidence * 0.7 + preferenceBonus * 0.3
                        if (matchCandidates.none { it.first == name }) {
                            matchCandidates.add(name to finalScore)
                        }
                    }
                }
            }
            // 按综合评分排序，取前3个
            for ((name, _) in matchCandidates.sortedByDescending { it.second }) {
                if (name !in seenApps) {
                    val preferenceBonus = appPreferenceScore[name] ?: 0.0
                    val freqHint = when {
                        preferenceBonus > 0.8 -> "（常用应用）"
                        preferenceBonus > 0.5 -> "（最近用过）"
                        preferenceBonus > 0.2 -> "（偶尔使用）"
                        else -> ""
                    }
                    suggestions.add("你是不是想打开「$name」？$freqHint")
                    seenApps.add(name)
                    if (suggestions.size >= 3) break
                }
            }
        }

        // 2. 拼音首字母模糊匹配
        if (suggestions.size < 3 && input.length >= 2 && input.all { it.isLetter() }) {
            val pinyinMatch = matchPinyinAbbreviation(input)
            if (pinyinMatch != null && pinyinMatch !in seenApps) {
                suggestions.add("你是不是想打开「$pinyinMatch」？（拼音匹配）")
                seenApps.add(pinyinMatch)
            }
        }

        // 3. 检查已知命令
        if (suggestions.isEmpty()) {
            val knownCommands = mapOf(
                "打开" to "打开应用，如「打开微信」「打开抖音」",
                "搜索" to "搜索，如「搜索猫咪」「在抖音搜索xxx」",
                "截屏" to "截屏",
                "返回" to "返回上一页",
                "发消息" to "发消息，如「给张三发微信说我晚点到」",
                "清理" to "清理缓存",
                "定时" to "定时打开，如「5分钟后打开支付宝」",
                "导航" to "导航，如「导航到天安门」「去火车站」",
                "播放" to "播放音乐/视频，如「播放周杰伦的歌」「听音乐」",
                "记录" to "记录提醒，如「提醒我明天早上8点开会」"
            )
            for ((keyword, desc) in knownCommands) {
                if (input.length >= 1 && keyword.length >= 1 &&
                    (input.contains(keyword.substring(0, 1)) || keyword.contains(input.substring(0, 1)))) {
                    suggestions.add("试试「$desc」")
                    if (suggestions.size >= 3) break
                }
            }
        }

        // 4. 如果仍未匹配到，尝试智能纠错（同音/近音字）
        if (suggestions.isEmpty()) {
            val homophoneMatch = smartHomophoneMatch(input)
            if (homophoneMatch != null && APP_PACKAGE_MAP.containsKey(homophoneMatch) && homophoneMatch !in seenApps) {
                suggestions.add("你是不是想打开「$homophoneMatch」？（同音匹配）")
                seenApps.add(homophoneMatch)
            }
        }

        // 5. 最后尝试从最近使用中推荐
        if (suggestions.isEmpty()) {
            val recent = getRecentApps(3)
            for (app in recent) {
                if (app !in seenApps) {
                    suggestions.add("试试打开最近用过的「$app」")
                    seenApps.add(app)
                    if (suggestions.size >= 3) break
                }
            }
        }

        return suggestions
    }

    /**
     * 加权编辑距离：对常见键盘按键错误赋予较小权重，
     * 使得"抖音"→"抖间"之类的错误具有更小的编辑距离。
     */
    private fun weightedEditDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }
        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else {
                    // 对键盘相邻键的替换赋予较小权重（0.5）
                    if (areAdjacentKeys(s1[i - 1], s2[j - 1])) 1 else 2
                }
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[len1][len2]
    }

    /**
     * 判断两个字符是否为键盘相邻键（简化版，覆盖常见拼音键盘布局）。
     */
    private fun areAdjacentKeys(c1: Char, c2: Char): Boolean {
        // 常见拼音键盘相邻映射
        val adjacentPairs = mapOf(
            'w' to "qes", 'e' to "wrs", 'r' to "etf", 't' to "ryg",
            'y' to "tuh", 'u' to "yij", 'i' to "uok", 'o' to "ipl",
            'p' to "oal", 'a' to "qws", 's' to "awd", 'd' to "sef",
            'f' to "drg", 'g' to "fth", 'h' to "gjy", 'j' to "hku",
            'k' to "jil", 'l' to "kop", 'z' to "ax", 'x' to "zc",
            'c' to "xv", 'v' to "cb", 'b' to "vn", 'n' to "bm",
            'm' to "n"
        )
        val lower1 = c1.lowercaseChar()
        val lower2 = c2.lowercaseChar()
        return adjacentPairs[lower1]?.contains(lower2) == true ||
               adjacentPairs[lower2]?.contains(lower1) == true
    }

    /**
     * 计算两个字符串的Jaccard相似度（基于字符集合）。
     */
    private fun jaccardSimilarity(s1: String, s2: String): Double {
        val set1 = s1.toSet()
        val set2 = s2.toSet()
        val intersection = set1.intersect(set2).size.toDouble()
        val union = set1.union(set2).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /**
     * 记录成功打开的应用，用于后续代词消解和使用频率统计。
     */
    fun recordAppOpen(appName: String, packageName: String) {
        lastOpenedApp = appName
        lastOpenedPackage = packageName

        // 更新使用频率统计
        appUsageFrequency.getOrPut(appName) { AtomicLong(0) }.incrementAndGet()

        // 更新最近使用列表
        synchronized(recentApps) {
            recentApps.remove(appName)
            recentApps.addFirst(appName)
            // 保持最多20个
            while (recentApps.size > 20) {
                recentApps.removeLast()
            }
        }

        // 更新综合偏好评分
        updatePreferenceScore(appName)
    }

    /**
     * 更新应用的偏好评分（基于使用频率、最近使用、时段场景综合计算）。
     */
    private fun updatePreferenceScore(appName: String) {
        val frequency = appUsageFrequency[appName]?.get() ?: 0L
        val recentIndex = synchronized(recentApps) { recentApps.indexOf(appName) }
        val recency = if (recentIndex >= 0) (1.0 - recentIndex.toDouble() / 20.0) else 0.0

        // 时段场景匹配
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val timeBonus = when {
            hour in 5..8 && appName in listOf("天气", "新闻", "日历") -> 0.3
            hour in 9..11 && appName in listOf("微信", "钉钉", "飞书", "邮箱", "企业微信") -> 0.3
            hour in 11..13 && appName in listOf("美团", "饿了么", "美团外卖", "百度外卖") -> 0.3
            hour in 14..17 && appName in listOf("音乐", "抖音", "小红书", "微博") -> 0.2
            hour in 18..20 && appName in listOf("美团", "饿了么", "腾讯视频", "哔哩哔哩") -> 0.3
            hour in 21..23 && appName in listOf("腾讯视频", "哔哩哔哩", "抖音", "音乐") -> 0.3
            else -> 0.0
        }

        // 综合评分 = 频率归一化(0-1) * 0.5 + 最近使用 * 0.3 + 时间场景 * 0.2
        val maxFrequency = appUsageFrequency.values.maxOfOrNull { it.get() }?.toDouble() ?: 1.0
        val normalizedFreq = if (maxFrequency > 0) frequency.toDouble() / maxFrequency else 0.0
        val score = normalizedFreq * 0.5 + recency * 0.3 + timeBonus * 0.2
        appPreferenceScore[appName] = score
    }

    /**
     * 获取按偏好评分排序的应用列表。
     */
    private fun getPreferredApps(): List<Pair<String, Double>> {
        return appPreferenceScore.entries
            .map { it.key to it.value }
            .sortedByDescending { it.second }
    }

    /**
     * 获取最常用的应用名列表（按使用频率降序）。
     */
    fun getMostUsedApps(limit: Int = 5): List<String> {
        return appUsageFrequency.entries
            .sortedByDescending { it.value.get() }
            .take(limit)
            .map { it.key }
    }

    /**
     * 获取最近使用的应用名列表。
     */
    fun getRecentApps(limit: Int = 5): List<String> {
        synchronized(recentApps) {
            return recentApps.take(limit)
        }
    }

    /**
     * 添加用户自定义别名。
     */
    fun addAlias(alias: String, packageName: String) {
        aliasMap[alias] = packageName
    }

    // =========================================================================
    //  核心方法：匹配
    // =========================================================================

    /**
     * 将用户输入的自然语言指令匹配为结构化的 [ExecuteResult]。
     *
     * 匹配流程：
     * 1. 优先匹配用户注册的自定义命令（[customCommands]）。
     * 2. 按优先级依次匹配预定义的正则表达式组。
     * 3. 若未匹配到任何命令，返回置信度为 0.0 的空结果。
     * 4. 记录匹配耗时并更新统计信息。
     *
     * 线程安全：该方法内部使用不可变状态，可在任意线程调用。
     *
     * @param userInput 用户的自然语言输入（中文/英文）
     * @return 匹配结果 [ExecuteResult]，包含动作列表、置信度与描述
     */
    fun match(userInput: String): ExecuteResult {
        val startTime = System.nanoTime()
        val trimmed = userInput.trim()

        // 空输入直接返回空结果
        if (trimmed.isBlank()) {
            return ExecuteResult(
                actions = emptyList(),
                confidence = 0.0,
                description = "输入为空，无法匹配任何命令",
                rawInput = userInput
            )
        }

        // 0. 智能预处理：代词消解 + 纠正学习
        val processed = learnFromCorrection(resolvePronouns(trimmed))

        // 1. 优先匹配自定义命令
        val customResult = matchCustomCommands(processed)
        if (customResult != null) {
            recordMatch(processed, startTime)
            return customResult
        }

        // 2. 匹配复合命令（多步操作）
        val compositeResult = tryMatchComposite(processed)
        if (compositeResult != null) {
            // 如果复合命令中包含了打开应用，记录上下文
            compositeResult.actions.forEach { action ->
                if (action.actionName == ActionType.APP_OPEN.name) {
                    val name = action.params["name"]?.jsonPrimitive?.content
                    val pkg = action.params["packageName"]?.jsonPrimitive?.content
                    if (name != null && pkg != null) {
                        recordAppOpen(name, pkg)
                    }
                }
            }
            recordMatch(processed, startTime)
            return compositeResult
        }

        // 3. 匹配单步命令
        val singleResult = tryMatchSingle(processed)
        if (singleResult != null) {
            // 如果单步命令中包含了打开应用，记录上下文
            singleResult.actions.forEach { action ->
                if (action.actionName == ActionType.APP_OPEN.name) {
                    val name = action.params["name"]?.jsonPrimitive?.content
                    val pkg = action.params["packageName"]?.jsonPrimitive?.content
                    if (name != null && pkg != null) {
                        recordAppOpen(name, pkg)
                    }
                }
            }
            recordMatch(processed, startTime)
            return singleResult
        }

        // 4. 无匹配：生成智能建议
        val suggestions = getSuggestions(processed)
        val suggestionText = if (suggestions.isNotEmpty()) {
            "\n\n💡 智能建议：\n" + suggestions.joinToString("\n")
        } else ""
        recordMatch(processed, startTime)
        return ExecuteResult(
            actions = emptyList(),
            confidence = 0.0,
            description = "未找到与「${trimmed}」匹配的本地命令${suggestionText}",
            rawInput = trimmed
        )
    }

    /**
     * 执行匹配到的命令列表。
     *
     * 该方法将 [ExecuteResult] 中的每个 [ClawAction] 转换为对应的
     * [ClawActionResult]，并收集所有执行结果。
     *
     * 注意：实际执行需要依赖 [ClawController] 或 [ScreenController] 等外部组件。
     * 该方法目前仅做动作转换与验证，不实际操控设备。
     *
     * @param result [match] 返回的匹配结果
     * @return 每个动作对应的执行结果列表
     */
    fun execute(result: ExecuteResult): List<ClawActionResult> {
        if (result.isEmpty) {
            return listOf(ClawActionResult.failure("没有可执行的动作"))
        }

        val results = mutableListOf<ClawActionResult>()
        for (action in result.actions) {
            val actionResult = when (action.type) {
                ActionType.APP_OPEN -> executeAppOpen(action)
                ActionType.SCREEN_SCREENSHOT -> executeScreenshot(action)
                ActionType.SCREEN_KEY -> executeKeyAction(action)
                ActionType.SCREEN_INPUT -> executeInput(action)
                ActionType.SYSTEM_GET_INFO -> executeSystemInfo(action)
                ActionType.SYSTEM_CLEAR_CACHE -> executeClearCache(action)
                ActionType.MEDIA_CONTROL -> executeMediaControl(action)
                ActionType.CLIPBOARD_COPY -> executeClipboardCopy(action)
                ActionType.SCREEN_CLICK_TEXT -> executeClickText(action)
                ActionType.SCREEN_FIND_AND_CLICK -> executeFindAndClick(action)
                ActionType.SCREEN_WAIT -> executeWait(action)
                ActionType.APP_CLOSE -> executeAppClose(action)
                ActionType.SCREEN_SWIPE -> executeSwipe(action)
                ActionType.SYSTEM_SET_VOLUME -> executeSetVolume(action)
                ActionType.SYSTEM_SET_BRIGHTNESS -> executeSetBrightness(action)
                ActionType.CLIPBOARD_PASTE -> executeClipboardPaste(action)
                else -> ClawActionResult.success(
                    message = "动作 ${action.actionName} 已识别，等待执行器处理",
                    data = action.description
                )
            }
            results.add(actionResult)
        }

        // 更新统计
        totalMatches.incrementAndGet()

        return results
    }

    /**
     * 根据动作类型名称获取对应的 [ActionType] 枚举值。
     *
     * 支持大小写归一化：
     * - "app_open" -> ActionType.APP_OPEN
     * - "screen_screenshot" -> ActionType.SCREEN_SCREENSHOT
     * - "SCREEN_KEY" -> ActionType.SCREEN_KEY
     *
     * @param actionName 动作名称字符串
     * @return 对应的 [ActionType] 枚举值，若无法识别则返回 null
     */
    fun getAction(actionName: String): ActionType? {
        val normalized = actionName.trim().uppercase()
            .replace("-", "_")
            .replace(" ", "_")
        return runCatching { ActionType.valueOf(normalized) }.getOrNull()
    }

    /**
     * 根据中文应用名称获取对应的 Android 包名。
     *
     * 查询顺序：
     * 1. 精确匹配中文应用名（如 "微信" -> "com.tencent.mm"）
     * 2. 模糊匹配包含关系（如 "微信" 包含 "微"）
     * 3. 直接返回输入（假设输入本身已是包名）
     *
     * @param appName 中文应用名称或包名
     * @return 对应的 Android 包名，若无法识别则返回原输入
     */
    fun getPackageName(appName: String): String {
        val trimmed = appName.trim()

        // 0. 数字和英文大小写归一化
        val normalized = trimmed.lowercase().replace(Regex("[._\\-\\s]+"), "")
            .replace("0", "零").replace("1", "一").replace("2", "二")
            .replace("3", "三").replace("4", "四").replace("5", "五")
            .replace("6", "六").replace("7", "七").replace("8", "八").replace("9", "九")

        // 0. 优先检查用户自定义别名
        aliasMap[trimmed]?.let { return it }
        aliasMap[normalized]?.let { return it }

        // 0b. 检查纠正学习映射
        correctionMap[trimmed]?.let { corrected ->
            APP_PACKAGE_MAP[corrected]?.let { return it }
        }
        correctionMap[normalized]?.let { corrected ->
            APP_PACKAGE_MAP[corrected]?.let { return it }
        }

        // 1. 精确匹配（原始输入 + 归一化后）
        APP_PACKAGE_MAP[trimmed]?.let { return it }
        APP_PACKAGE_MAP[normalized]?.let { return it }

        // 如果输入本身就是包名格式（包含点号），直接返回
        if (trimmed.contains(".") && trimmed.matches(Regex("^[a-zA-Z0-9._]+$"))) {
            return trimmed
        }

        // 2. 模糊匹配：在所有 key 中查找包含关系
        for ((name, pkg) in APP_PACKAGE_MAP) {
            if (trimmed.contains(name) || name.contains(trimmed) ||
                normalized.contains(name) || name.contains(normalized)) {
                return pkg
            }
        }

        // 3. 智能模糊匹配：编辑距离 <= 2，处理打字错误
        // 例如"抖音"打成"抖间"、"微信"打成"微愿"等
        val smartMatch = findSmartMatch(trimmed)
        if (smartMatch != null) return smartMatch

        // 3b. 部首/偏旁模糊匹配：处理形近字的错误
        // 例如"犭"（反犬旁）相关的字如"狗"、"猫"等
        val radicalMatch = matchRadicalSimilarity(trimmed)
        if (radicalMatch != null) return getPackageName(radicalMatch)

        // 4. 同音/近音字匹配：处理"为信"->"微信"、"抖印"->"抖音"等
        val homophoneMatch = smartHomophoneMatch(trimmed)
        if (homophoneMatch != null) return getPackageName(homophoneMatch)

        // 5. 拼音首字母匹配：处理"wx"->"微信"等
        val pinyinMatch = matchPinyinAbbreviation(trimmed)
        if (pinyinMatch != null) return getPackageName(pinyinMatch)

        // 5b. 更多拼音缩写匹配（扩展缩写表）
        val extendedAbbreviationMatch = matchExtendedAbbreviation(trimmed)
        if (extendedAbbreviationMatch != null) return getPackageName(extendedAbbreviationMatch)

        // 6. 无匹配，返回原输入
        return trimmed
    }

    /**
     * 部首/偏旁相似度匹配：处理形近字错误。
     * 例如"犭"（反犬旁）相关的字："狗"、"猫"、"狼"、"猪"等。
     */
    private fun matchRadicalSimilarity(input: String): String? {
        // 常见部首/偏旁映射表（形近字分组）
        val radicalGroups = listOf(
            // 反犬旁相关
            setOf("狗", "猫", "狼", "猪", "猴", "狮", "狸", "猎"),
            // 提手旁相关
            setOf("打", "找", "扫", "拍", "拉", "推", "摇", "摆", "拨", "搜"),
            // 口字旁相关
            setOf("吃", "喝", "唱", "叫", "喊", "听", "吸", "吹", "味", "呀"),
            // 贝字旁/财相关
            setOf("财", "购", "货", "资", "费", "赏", "贸"),
            // 火字旁相关
            setOf("炒", "烧", "炸", "烤", "灯", "炉", "烟"),
            // 三点水相关
            setOf("江", "河", "湖", "海", "洗", "游", "流", "清", "激", "浪"),
            // 木字旁相关
            setOf("林", "树", "桥", "板", "机", "材", "根", "校"),
            // 言字旁相关
            setOf("说", "话", "讲", "谈", "议", "论", "评", "证"),
            // 走之底相关
            setOf("这", "那", "过", "进", "道", "远", "近", "送"),
            // 日字旁相关
            setOf("晴", "时", "明", "星", "晨", "暖", "晒"),
            // 月字旁相关
            setOf("朋", "服", "期", "朗", "胜", "脸"),
            // 绞丝旁相关
            setOf("红", "绿", "线", "级", "细", "组", "经"),
            // 草字头相关
            setOf("花", "草", "茶", "药", "菜", "英", "苹"),
            // 足字旁相关
            setOf("跑", "跳", "路", "跟", "踢", "跨", "踪"),
            // 门字框相关
            setOf("门", "间", "开", "关", "闲", "阅"),
            // 宝盖头相关
            setOf("家", "安", "定", "宝", "实", "客", "室"),
            // 单人旁相关
            setOf("他", "你", "们", "住", "信", "做", "件"),
            // 双人旁相关
            setOf("很", "行", "得", "往", "微", "律"),
            // 工字旁相关
            setOf("工", "左", "右", "差", "巧"),
            // 耳朵旁相关
            setOf("阿", "队", "阳", "阴", "院", "除"),
            // 山字旁相关
            setOf("山", "峰", "岛", "岸", "峡", "岭"),
            // 车字旁相关
            setOf("车", "轮", "转", "轻", "较", "辆"),
            // 金字旁相关
            setOf("金", "银", "铜", "铁", "针", "钟", "钱"),
            // 食字旁相关
            setOf("饭", "饮", "饱", "饿", "餐", "馆"),
            // 马字旁相关
            setOf("马", "骑", "驱", "驶", "骄", "验"),
            // 鸟字旁相关
            setOf("鸟", "鸡", "鸭", "鹅", "鸽", "鹏"),
            // 鱼字旁相关
            setOf("鱼", "鲜", "鲤", "鲨", "鲸"),
            // 衣字旁相关
            setOf("衣", "衫", "衬", "被", "裙", "裤"),
            // 示字旁相关
            setOf("视", "社", "祖", "神", "福", "礼")
        )

        // 检查输入中的每个字是否在某个部首分组中
        for (char in input) {
            val charStr = char.toString()
            for (group in radicalGroups) {
                if (charStr in group) {
                    // 在APP_PACKAGE_MAP中查找包含同组部首字的应用名
                    for (name in APP_PACKAGE_MAP.keys) {
                        for (groupChar in group) {
                            if (name.contains(groupChar) && !name.contains(charStr)) {
                                // 将输入中的字替换为同组中匹配到的字
                                val corrected = input.replace(charStr, groupChar)
                                if (APP_PACKAGE_MAP.containsKey(corrected)) {
                                    return corrected
                                }
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 扩展拼音缩写匹配：处理更多常见英文缩写。
     */
    private fun matchExtendedAbbreviation(input: String): String? {
        val lowerInput = input.lowercase().trim()
        val extendedAbbreviationMap = mapOf(
            // 社交类
            "qq" to "QQ", "vx" to "微信", "wx" to "微信", "wx" to "微信",
            "wb" to "微博", "dy" to "抖音", "ks" to "快手",
            "xhs" to "小红书", "bili" to "哔哩哔哩", "bzz" to "哔哩哔哩",
            "zh" to "知乎", "tb" to "淘宝", "jd" to "京东",
            "pdd" to "拼多多", "mt" to "美团", "elm" to "饿了么",
            "dd" to "钉钉", "zfb" to "支付宝",
            // 视频/音乐
            "yy" to "网易云音乐", "kg" to "酷狗音乐", "kw" to "酷我音乐",
            "tx" to "腾讯视频", "iqy" to "爱奇艺", "yk" to "优酷",
            "mgtv" to "芒果TV",
            // 工具类
            "bd" to "百度", "gd" to "高德地图", "amap" to "高德地图",
            "didi" to "滴滴", "ctrip" to "携程", "qunar" to "去哪儿",
            "ds" to "DeepSeek", "kimi" to "Kimi",
            "chatgpt" to "ChatGPT", "gpt" to "ChatGPT",
            "copilot" to "Copilot", "gemini" to "Gemini",
            // 国际类
            "ig" to "Instagram", "ins" to "Instagram", "fb" to "Facebook",
            "tg" to "Telegram", "tw" to "Twitter", "x" to "Twitter",
            "yt" to "YouTube", "nf" to "Netflix",
            // 游戏类
            "lol" to "英雄联盟手游", "wz" to "王者荣耀",
            "cf" to "穿越火线", "mc" to "我的世界",
            "coc" to "部落冲突", "cr" to "皇室战争",
            "bs" to "荒野乱斗", "genshin" to "原神",
            // 其他
            "wp" to "WPS", "steam" to "Steam",
            "github" to "GitHub", "notion" to "Notion",
            "obsidian" to "Obsidian", "zoom" to "Zoom",
            "teams" to "Teams", "slack" to "Slack",
            "spotify" to "Spotify", "disney" to "Disney+",
            "hbo" to "HBO Max", "prime" to "Amazon Prime"
        )
        return extendedAbbreviationMap[lowerInput]
    }

    /**
     * 智能模糊匹配：使用编辑距离（Levenshtein Distance）算法，
     * 允许最多 2 个字符的差异，处理常见打字错误。
     *
     * @param input 用户输入的应用名
     * @return 匹配到的包名，无匹配返回 null
     */
    private fun findSmartMatch(input: String): String? {
        if (input.length < 2) return null

        var bestMatch: String? = null
        var bestDistance = Int.MAX_VALUE

        for (name in APP_PACKAGE_MAP.keys) {
            // 长度差超过 2 的直接跳过
            if (kotlin.math.abs(name.length - input.length) > 2) continue

            val distance = levenshteinDistance(input, name)
            // 编辑距离 <= 2，且长度较短时容差更小
            val maxAllowed = if (name.length <= 3) 1 else 2
            if (distance <= maxAllowed && distance < bestDistance) {
                bestDistance = distance
                bestMatch = name
            }
        }

        return bestMatch?.let { APP_PACKAGE_MAP[it] }
    }

    /**
     * 计算两个字符串之间的编辑距离（Levenshtein Distance）。
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    /**
     * 拼音首字母匹配：将用户输入的英文缩写与中文应用名的拼音首字母进行匹配。
     *
     * 例如：
     * - "wx" -> "微信" (w x)
     * - "zfb" -> "支付宝" (z f b)
     * - "dy" -> "抖音" (d y)
     * - "wy" -> "网易云音乐" (w y)
     * - "jd" -> "京东" (j d)
     * - "pdd" -> "拼多多" (p d d)
     * - "b站" -> "哔哩哔哩" (b z -> b站)
     * - "mt" -> "美团" (m t)
     * - "tb" -> "淘宝" (t b)
     * - "wb" -> "微博" (w b)
     * - "xhs" -> "小红书" (x h s)
     * - "bili" -> "哔哩哔哩" (bili)
     * - "ks" -> "快手" (k s)
     *
     * @param input 用户输入的英文缩写
     * @return 匹配到的应用名，无匹配返回 null
     */
    private fun matchPinyinAbbreviation(input: String): String? {
        val lowerInput = input.lowercase().trim()

        // 1. 精确匹配预定义的缩写映射表
        val abbreviationMap = mapOf(
            "wx" to "微信", "zfb" to "支付宝", "vx" to "微信", "wechat" to "微信",
            "dy" to "抖音", "douyin" to "抖音", "tiktok" to "抖音",
            "jd" to "京东", "jingdong" to "京东", "taobao" to "淘宝",
            "tb" to "淘宝", "tmall" to "天猫", "pdd" to "拼多多",
            "ks" to "快手", "kuaishou" to "快手",
            "wb" to "微博", "weibo" to "微博",
            "xhs" to "小红书", "bili" to "哔哩哔哩", "bilibili" to "哔哩哔哩",
            "mt" to "美团", "meituan" to "美团", "elm" to "饿了么",
            "dd" to "钉钉", "dingtalk" to "钉钉",
            "qq" to "QQ", "qq音乐" to "QQ音乐", "wy" to "网易云音乐",
            "netease" to "网易云音乐", "kg" to "酷狗音乐", "kw" to "酷我音乐",
            "bd" to "百度", "baidu" to "百度",
            "gaode" to "高德地图", "amap" to "高德地图",
            "didi" to "滴滴", "ctrip" to "携程", "qunar" to "去哪儿",
            "ds" to "DeepSeek", "deepseek" to "DeepSeek",
            "kimi" to "Kimi", "ty" to "通义千问",
            "chatgpt" to "ChatGPT", "gpt" to "ChatGPT",
            "copilot" to "Copilot", "gemini" to "Gemini",
            "ig" to "Instagram", "ins" to "Instagram",
            "fb" to "Facebook", "tg" to "Telegram",
            "wp" to "WPS", "steam" to "Steam",
            "lol" to "英雄联盟手游", "wz" to "王者荣耀",
            "cf" to "穿越火线", "mc" to "我的世界",
            "coc" to "部落冲突", "cr" to "皇室战争",
            "bs" to "荒野乱斗", "bzz" to "B站",
            "zh" to "知乎", "zhihu" to "知乎",
            "nga" to "NGA", "coolapk" to "酷安",
            "v2ex" to "V2EX", "github" to "GitHub",
            "gitee" to "Gitee", "notion" to "Notion",
            "obsidian" to "Obsidian", "zoom" to "Zoom",
            "teams" to "Teams", "slack" to "Slack",
            "spotify" to "Spotify", "netflix" to "Netflix",
            "disney" to "Disney+", "hbo" to "HBO Max"
        )

        abbreviationMap[lowerInput]?.let { return it }

        // 2. 对于长度 >= 2 的纯字母输入，尝试自动生成拼音首字母并匹配
        if (lowerInput.length >= 2 && lowerInput.all { it in 'a'..'z' }) {
            for ((name, _) in APP_PACKAGE_MAP) {
                if (name.length < 2) continue
                // 生成中文名的拼音首字母
                val initials = getPinyinInitials(name)
                if (initials == lowerInput) {
                    return name
                }
            }
        }

        return null
    }

    /**
     * 获取中文名的拼音首字母（简化版）。
     *
     * 使用一个简化的拼音首字母映射表，覆盖常见汉字。
     * 对于不在映射表中的汉字，使用其 Unicode 编码的首字母近似。
     *
     * 例如：
     * - "微信" -> "wx"
     * - "支付宝" -> "zfb"
     * - "抖音" -> "dy"
     * - "网易云音乐" -> "wyyy"
     * - "哔哩哔哩" -> "blll"
     *
     * @param name 中文名称
     * @return 拼音首字母字符串
     */
    private fun getPinyinInitials(name: String): String {
        // 常见汉字的拼音首字母映射（覆盖常用汉字）
        val pinyinMap = mapOf(
            '微' to "w", '信' to "x", '支' to "z", '付' to "f", '宝' to "b",
            '抖' to "d", '音' to "y", '淘' to "t", '宝' to "b", '天' to "t",
            '猫' to "m", '京' to "j", '东' to "d", '拼' to "p", '多' to "d",
            '快' to "k", '手' to "s", '微' to "w", '博' to "b",
            '小' to "x", '红' to "h", '书' to "s", '哔' to "b", '哩' to "l",
            '美' to "m", '团' to "t", '饿' to "e", '了' to "l", '么' to "m",
            '钉' to "d", '钉' to "d", '网' to "w", '易' to "y", '云' to "y",
            '音' to "y", '乐' to "l", '酷' to "k", '狗' to "g", '我' to "w",
            '百' to "b", '度' to "d", '高' to "g", '德' to "d", '地' to "d",
            '图' to "t", '滴' to "d", '滴' to "d", '出' to "c", '行' to "x",
            '携' to "x", '程' to "c", '去' to "q", '哪' to "n", '儿' to "e",
            '飞' to "f", '猪' to "z", '知' to "z", '乎' to "h",
            '贴' to "t", '吧' to "b", '酷' to "k", '安' to "a",
            '腾' to "t", '讯' to "x", '视' to "s", '频' to "p",
            '爱' to "a", '奇' to "q", '艺' to "y", '优' to "y",
            '芒' to "m", '果' to "g", '搜' to "s", '狐' to "h",
            '网' to "w", '易' to "y", '新' to "x", '闻' to "w",
            '今' to "j", '日' to "r", '头' to "t", '条' to "t",
            '澎' to "p", '湃' to "p", '辣' to "l", '椒' to "j",
            '豆' to "d", '包' to "b", '深' to "s", '度' to "d",
            '求' to "q", '索' to "s", '月' to "y", '之' to "z",
            '暗' to "a", '面' to "m", '文' to "w", '心' to "x",
            '一' to "y", '言' to "y", '通' to "t", '义' to "y",
            '千' to "q", '问' to "w", '元' to "y", '星' to "x",
            '火' to "h", '天' to "t", '工' to "g", '秘' to "m",
            '塔' to "t", '智' to "z", '谱' to "p", '百' to "b",
            '川' to "c", '原' to "y", '神' to "s", '王' to "w",
            '者' to "z", '荣' to "r", '耀' to "y", '和' to "h",
            '平' to "p", '英' to "y", '雄' to "x", '联' to "l",
            '盟' to "m", '手' to "s", '游' to "y", '星' to "x",
            '穹' to "q", '铁' to "t", '道' to "d", '绝' to "j",
            '区' to "q", '零' to "l", '明' to "m", '日' to "r",
            '方' to "f", '舟' to "z", '金' to "j", '铲' to "c",
            '蛋' to "d", '仔' to "z", '派' to "p", '对' to "d",
            '光' to "g", '遇' to "y", '我' to "w", '的' to "d",
            '世' to "s", '界' to "j", '部' to "b", '落' to "l",
            '冲' to "c", '突' to "t", '皇' to "h", '室' to "s",
            '战' to "z", '争' to "z", '荒' to "h", '野' to "y",
            '乱' to "l", '斗' to "d", '顺' to "s", '丰' to "f",
            '速' to "s", '运' to "y", '菜' to "c", '鸟' to "n",
            '裹' to "g", '裹' to "g", '快' to "k", '递' to "d",
            '中' to "z", '通' to "t", '圆' to "y", '韵' to "y",
            '达' to "d", '申' to "s", '邮' to "y", '政' to "z",
            '国' to "g", '航' to "h", '南' to "n", '东' to "d",
            '航' to "h", '旅' to "l", '纵' to "z", '横' to "h",
            '智' to "z", '行' to "x", '途' to "t", '牛' to "n",
            '马' to "m", '蜂' to "f", '窝' to "w", '爱' to "a",
            '彼' to "b", '迎' to "y", '天' to "t", '眼' to "y",
            '查' to "c", '企' to "q", '启' to "q", '信' to "x",
            '同' to "t", '花' to "h", '顺' to "s", '东' to "d",
            '方' to "f", '财' to "c", '富' to "f", '雪' to "x",
            '球' to "q", '涨' to "z", '乐' to "l", '华' to "h",
            '泰' to "t", '证' to "z", '券' to "q", '中' to "z",
            '信' to "x", '平' to "p", '安' to "a", '银' to "y",
            '联' to "l", '度' to "d", '小' to "x", '满' to "m",
            '借' to "j", '呗' to "b", '花' to "h", '还' to "h",
            '黄' to "h", '色' to "s", '软' to "r", '件' to "j",
            '蓝' to "l", '骑' to "q", '士' to "s", '蜂' to "f",
            '肯' to "k", '德' to "d", '基' to "j", '麦' to "m",
            '当' to "d", '劳' to "l", '星' to "x", '巴' to "b",
            '克' to "k", '瑞' to "r", '幸' to "x", '咖' to "k",
            '啡' to "f", '蜜' to "m", '雪' to "x", '冰' to "b",
            '城' to "c", '喜' to "x", '茶' to "c", '奈' to "n",
            '雪' to "x", '的' to "d", '茶' to "c", '花' to "h",
            '小' to "x", '猪' to "z", '曹' to "c", '操' to "c",
            '首' to "s", '汽' to "q", '哈' to "h", '啰' to "l",
            '青' to "q", '桔' to "j", '摩' to "m", '拜' to "b",
            '铁' to "t", '路' to "l", '携' to "x", '程' to "c",
            '去' to "q", '哪' to "n", '儿' to "e",
            '绿' to "l", '泡' to "p", '泡' to "p",
            '而' to "e", '美' to "m", '卫' to "w",
            '星' to "x", '阿' to "a", '支' to "z",
            '某' to "m", '狗' to "g", '东' to "d",
            '二' to "e", '手' to "s", '夕' to "x",
            '夕' to "x", '抖' to "d", '渣' to "z",
            '浪' to "l", '睿' to "r", '站' to "z",
            '小' to "x", '破' to "p", '站' to "z",
            '逼' to "b", '乎' to "h", '网' to "w",
            '抑' to "y", '云' to "y", '村' to "c",
            '黑' to "h", '胶' to "j", '度' to "d",
            '娘' to "n", '农' to "n", '药' to "y",
            '排' to "p", '位' to "w", '吃' to "c",
            '鸡' to "j", '刺' to "c", '激' to "j",
            '战' to "z", '场' to "c", '包' to "b",
            '子' to "z", '深' to "s", '度' to "d",
            '求' to "q", '索' to "s", '月' to "y",
            '之' to "z", '暗' to "a", '面' to "m",
            '文' to "w", '心' to "x", '一' to "y",
            '言' to "y", '通' to "t", '义' to "y",
            '千' to "q", '问' to "w", '讯' to "x",
            '飞' to "f", '星' to "x", '火' to "h",
            '元' to "y", '宝' to "b", '腾' to "t",
            '讯' to "x", '天' to "t", '工' to "g",
            '秘' to "m", '塔' to "t", '智' to "z",
            '谱' to "p", '百' to "b", '川' to "c",
            '豆' to "d", '包' to "b"
        )

        val sb = StringBuilder()
        for (char in name) {
            when {
                char in 'a'..'z' || char in 'A'..'Z' -> sb.append(char.lowercaseChar())
                pinyinMap.containsKey(char) -> sb.append(pinyinMap[char])
                else -> {
                    // 对于不在映射表中的汉字，使用拼音首字母的启发式方法
                    // 这里简单处理：跳过未知字符
                }
            }
        }
        return sb.toString()
    }

    /**
     * 智能近义词匹配：处理常见同音/近音字错误。
     *
     * 例如：
     * - "为信" -> "微信"（同音）
     * - "抖印" -> "抖音"（同音）
     * - "支负宝" -> "支付宝"（同音）
     * - "拼刀刀" -> "拼多多"（近音）
     * - "京冻" -> "京东"（同音）
     * - "美甜" -> "美团"（同音）
     * - "饿了吗" -> "饿了么"（近音）
     * - "bilibili" -> "哔哩哔哩"
     */
    private fun smartHomophoneMatch(input: String): String? {
        // 常见同音/近音词映射表
        val homophoneMap = mapOf(
            "为" to "微", "未" to "微", "位" to "微", "唯" to "微",
            "印" to "音", "因" to "音", "阴" to "音",
            "负" to "付", "父" to "付", "富" to "付",
            "刀" to "多", "哆" to "多", "躲" to "多",
            "冻" to "东", "冬" to "东", "懂" to "东",
            "甜" to "团", "端" to "团", "推" to "团",
            "了" to "了", "啦" to "了",
            "呼" to "乎", "胡" to "乎", "湖" to "乎",
            "博" to "博", "拨" to "博",
            "酷" to "酷", "库" to "酷",
            "狗" to "狗", "苟" to "狗",
            "抑" to "易", "义" to "易", "亿" to "易",
            "闻" to "文", "温" to "文", "蚊" to "文",
            "心" to "信", "辛" to "信", "新" to "信",
            "苛" to "可", "渴" to "可",
            "琳" to "林", "淋" to "林",
            "鱼" to "娱", "愉" to "娱", "余" to "娱",
            "游" to "游", "由" to "游",
            "戏" to "戏", "细" to "戏",
            "拼" to "拼", "频" to "拼",
            "夕" to "夕", "西" to "夕", "吸" to "夕",
            "夕" to "夕", "西" to "夕",
            "抖" to "抖", "斗" to "抖", "陡" to "抖",
            "快" to "快", "块" to "快", "筷" to "快",
            "手" to "手", "首" to "手", "守" to "手",
            "淘" to "淘", "逃" to "淘", "陶" to "淘",
            "京" to "京", "惊" to "京", "经" to "京",
            "东" to "东", "冬" to "东",
            "支" to "支", "知" to "支", "之" to "支",
            "付" to "付", "富" to "付", "复" to "付",
            "宝" to "宝", "保" to "宝", "饱" to "宝",
            "微" to "微", "危" to "微", "韦" to "微",
            "信" to "信", "新" to "信", "心" to "信"
        )

        // 对输入中的每个字尝试替换
        val corrected = input.map { char ->
            homophoneMap[char.toString()] ?: char.toString()
        }.joinToString("")

        // 如果纠正后与原始输入不同，尝试匹配
        if (corrected != input) {
            for ((name, _) in APP_PACKAGE_MAP) {
                if (corrected.contains(name) || name.contains(corrected)) {
                    return name
                }
            }
            // 也尝试使用纠正后的字符串进行精确匹配
            APP_PACKAGE_MAP[corrected]?.let { return corrected }
        }

        return null
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    /**
     * 获取所有支持的本地命令列表。
     *
     * 返回每个命令组中所有可匹配的示例命令及其对应的动作类型。
     * 适用于 UI 展示「可用命令列表」。
     *
     * @return 命令描述列表，每个条目包含 [commandName]、[actionType] 和 [examples]
     */
    fun getAllCommands(): List<CommandInfo> {
        val commands = mutableListOf<CommandInfo>()

        // 应用打开类
        commands.add(CommandInfo(
            commandName = "打开应用",
            actionType = ActionType.APP_OPEN,
            examples = listOf("打开微信", "启动抖音", "开启支付宝", "运行淘宝"),
            description = "打开指定的 Android 应用"
        ))

        // 截屏类
        commands.add(CommandInfo(
            commandName = "截屏",
            actionType = ActionType.SCREEN_SCREENSHOT,
            examples = listOf("截图", "截屏", "screenshot", "截个图", "截一下屏"),
            description = "对当前屏幕进行截图"
        ))

        // 按键类
        commands.add(CommandInfo(
            commandName = "返回",
            actionType = ActionType.SCREEN_KEY,
            examples = listOf("返回", "回去", "后退", "back"),
            description = "模拟返回键"
        ))
        commands.add(CommandInfo(
            commandName = "回桌面",
            actionType = ActionType.SCREEN_KEY,
            examples = listOf("回桌面", "回到桌面", "home", "主屏幕"),
            description = "模拟 Home 键回到桌面"
        ))
        commands.add(CommandInfo(
            commandName = "最近任务",
            actionType = ActionType.SCREEN_KEY,
            examples = listOf("最近任务", "多任务", "切换应用"),
            description = "打开最近任务列表"
        ))
        commands.add(CommandInfo(
            commandName = "锁屏",
            actionType = ActionType.SCREEN_KEY,
            examples = listOf("锁屏", "锁定"),
            description = "锁定屏幕"
        ))
        commands.add(CommandInfo(
            commandName = "通知栏",
            actionType = ActionType.SCREEN_KEY,
            examples = listOf("通知栏", "下拉通知"),
            description = "下拉通知栏"
        ))
        commands.add(CommandInfo(
            commandName = "音量加",
            actionType = ActionType.SCREEN_KEY,
            examples = listOf("音量加", "音量大", "大声点", "增大音量"),
            description = "增大媒体音量"
        ))
        commands.add(CommandInfo(
            commandName = "音量减",
            actionType = ActionType.SCREEN_KEY,
            examples = listOf("音量减", "音量小", "小声点", "减小音量"),
            description = "减小媒体音量"
        ))

        // 系统信息类
        commands.add(CommandInfo(
            commandName = "查看内存",
            actionType = ActionType.SYSTEM_GET_INFO,
            examples = listOf("查看内存", "内存使用", "内存情况"),
            description = "查看设备内存使用情况"
        ))
        commands.add(CommandInfo(
            commandName = "查看电池",
            actionType = ActionType.SYSTEM_GET_INFO,
            examples = listOf("查看电池", "电量", "电池状态"),
            description = "查看电池电量与状态"
        ))
        commands.add(CommandInfo(
            commandName = "查看CPU",
            actionType = ActionType.SYSTEM_GET_INFO,
            examples = listOf("查看CPU", "CPU使用", "CPU使用率"),
            description = "查看 CPU 使用情况"
        ))
        commands.add(CommandInfo(
            commandName = "查看存储",
            actionType = ActionType.SYSTEM_GET_INFO,
            examples = listOf("查看存储", "存储空间", "磁盘"),
            description = "查看存储空间使用情况"
        ))

        // 清理类
        commands.add(CommandInfo(
            commandName = "清理缓存",
            actionType = ActionType.SYSTEM_CLEAR_CACHE,
            examples = listOf("清理缓存", "清缓存", "清除缓存"),
            description = "清理系统与应用缓存"
        ))

        // 媒体控制类
        commands.add(CommandInfo(
            commandName = "播放/暂停",
            actionType = ActionType.MEDIA_CONTROL,
            examples = listOf("播放", "暂停", "播放音乐", "暂停音乐"),
            description = "播放或暂停当前媒体"
        ))
        commands.add(CommandInfo(
            commandName = "下一首",
            actionType = ActionType.MEDIA_CONTROL,
            examples = listOf("下一首", "切歌", "下一曲"),
            description = "切换到下一首曲目"
        ))
        commands.add(CommandInfo(
            commandName = "上一首",
            actionType = ActionType.MEDIA_CONTROL,
            examples = listOf("上一首", "上一曲"),
            description = "切换到上一首曲目"
        ))

        // 复合命令类
        commands.add(CommandInfo(
            commandName = "复合命令",
            actionType = ActionType.APP_OPEN,
            examples = listOf("在微信搜索xxx", "给张三发消息", "打开哔哩哔哩然后搜索"),
            description = "多步复合操作（打开应用 + 搜索/输入等）"
        ))
        commands.add(CommandInfo(
            commandName = "定时命令",
            actionType = ActionType.APP_OPEN,
            examples = listOf("5分钟后打开支付宝", "10秒后打开微信"),
            description = "延迟指定时间后打开应用"
        ))
        commands.add(CommandInfo(
            commandName = "序列命令",
            actionType = ActionType.APP_OPEN,
            examples = listOf("先打开微信再打开抖音", "依次打开京东和淘宝"),
            description = "按顺序依次打开多个应用"
        ))
        commands.add(CommandInfo(
            commandName = "循环命令",
            actionType = ActionType.APP_OPEN,
            examples = listOf("每隔5分钟打开微信", "每10分钟打开抖音"),
            description = "每隔指定时间重复打开应用"
        ))
        commands.add(CommandInfo(
            commandName = "应用别名",
            actionType = ActionType.APP_OPEN,
            examples = listOf("打开绿泡泡", "打开狗东", "打开拼夕夕"),
            description = "支持网络昵称和英文缩写识别"
        ))

        // 自定义命令
        for (cmd in customCommands) {
            commands.add(CommandInfo(
                commandName = cmd.description.ifBlank { cmd.pattern },
                actionType = getAction(cmd.actionName) ?: ActionType.CUSTOM,
                examples = listOf(cmd.pattern),
                description = cmd.description
            ))
        }

        return commands
    }

    /**
     * 获取运行统计信息。
     *
     * @return 当前 [ExecutorStats] 快照
     */
    fun getStats(): ExecutorStats {
        val total = totalMatches.get()
        val avgTime = if (total > 0) totalMatchTime.get().toDouble() / total / 1_000_000 else 0.0

        // 获取最常匹配的前 10 条命令
        val topEntries = matchHistory.entries
            .sortedByDescending { it.value.get() }
            .take(10)
            .associate { it.key to it.value.get() }

        return ExecutorStats(
            totalMatches = total,
            autoExecutions = autoExecutions.get(),
            manualExecutions = manualExecutions.get(),
            failedExecutions = failedExecutions.get(),
            averageMatchTime = avgTime,
            topCommands = topEntries,
            startTime = statsStartTime
        )
    }

    /**
     * 注册自定义命令。
     *
     * 用户可通过该方法添加自己定义的命令匹配规则，扩展 LocalCommandExecutor
     * 的识别能力。自定义命令的优先级高于内置命令。
     *
     * 示例：
     * ```
     * executor.addCustomCommand(
     *     pattern = "关机|关闭系统",
     *     actionName = "SHELL_EXEC",
     *     params = JsonObject(mapOf("command" to JsonPrimitive("reboot -p"))),
     *     description = "关机",
     *     priority = 10
     * )
     * ```
     *
     * @param pattern    匹配模式（正则表达式字符串）
     * @param actionName 动作类型名称（需与 [ActionType] 的 name 一致）
     * @param params     动作参数（JsonObject），默认为空
     * @param description 命令描述（中文），用于日志与 UI 展示
     * @param priority   优先级，数值越大越优先匹配（默认 0）
     */
    fun addCustomCommand(
        pattern: String,
        actionName: String,
        params: JsonObject = JsonObject(emptyMap()),
        description: String = "",
        priority: Int = 0
    ) {
        val cmd = CustomCommand(
            pattern = pattern,
            actionName = actionName,
            params = params,
            description = description,
            priority = priority
        )
        customCommands.add(cmd)
    }

    /**
     * 移除自定义命令。
     *
     * @param pattern 要移除的命令的正则表达式模式
     * @return 是否成功移除
     */
    fun removeCustomCommand(pattern: String): Boolean {
        return customCommands.removeIf { it.pattern == pattern }
    }

    /**
     * 清空所有自定义命令。
     */
    fun clearCustomCommands() {
        customCommands.clear()
    }

    // =========================================================================
    //  内部：匹配实现
    // =========================================================================

    /**
     * 匹配自定义命令。
     *
     * 遍历所有注册的自定义命令，按优先级降序检查是否匹配。
     * 优先返回优先级最高的匹配结果。
     */
    private fun matchCustomCommands(input: String): ExecuteResult? {
        val sorted = customCommands.sortedByDescending { it.priority }
        for (cmd in sorted) {
            val regex = runCatching { Regex(cmd.pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: continue
            if (regex.containsMatchIn(input)) {
                val action = ClawAction(
                    actionName = cmd.actionName,
                    params = cmd.params,
                    description = cmd.description
                )
                return ExecuteResult(
                    actions = listOf(action),
                    confidence = CONFIDENCE_CUSTOM_COMMAND,
                    description = cmd.description,
                    rawInput = input
                )
            }
        }
        return null
    }

    /**
     * 尝试匹配复合命令（多步操作）。
     *
     * 复合命令模式：
     * - "在XXX搜索YYY"：打开应用 + 等待 + 输入文本
     * - "给XXX发YYY"：打开应用 + 等待 + 点击 + 输入 + 点击
     * - "打开XXX然后YYY"：打开应用 + 后续操作
     * - "用XXX搜索YYY"：打开应用 + 输入搜索
     * - "去XXX看YYY"：打开应用 + 浏览内容
     */
    private fun tryMatchComposite(input: String): ExecuteResult? {
        // 模式1：打开XXX搜索YYY / 打开XXX并搜索YYY / 打开XXX然后搜索YYY
        // 注意：必须先于"在XXX搜索YYY"匹配，因为"打开"前缀可能后接"并"连接词
        // 支持逗号分隔：打开抖音，并搜索猫咪
        val openSearchPattern = Regex(
            """(?:打开|启动|开启|运行)\s*(.+?)\s*[,，]?\s*(?:并|然后|接着|再|并且|之后)?\s*(?:搜索|搜|查找|找|查询|查|寻找)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val openSearchMatch = openSearchPattern.find(input)
        if (openSearchMatch != null) {
            val appName = openSearchMatch.groupValues[1].trim()
            val query = openSearchMatch.groupValues[2].trim()
            // 如果appName包含"并"等连接词，说明不是真正的应用名，交给模式3处理
            if (appName.length <= 10 && !appName.contains("并") && !appName.contains("然后")) {
                val packageName = getPackageName(appName)
                if (packageName != null) {
                    val actions = listOf(
                        ClawAction(
                            actionName = ActionType.APP_OPEN.name,
                            params = JsonObject(mapOf(
                                "packageName" to JsonPrimitive(packageName),
                                "name" to JsonPrimitive(appName)
                            )),
                            description = "打开 $appName"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                            description = "等待应用加载"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_INPUT.name,
                            params = JsonObject(mapOf(
                                "text" to JsonPrimitive(query)
                            )),
                            description = "输入搜索内容「$query」"
                        )
                    )
                    return ExecuteResult(
                        actions = actions,
                        confidence = CONFIDENCE_COMPOSITE_SEARCH,
                        description = "在「$appName」中搜索「$query」",
                        rawInput = input
                    )
                }
            }
        }

        // 模式2：在XXX搜索YYY / 用XXX搜YYY / 去XXX搜YYY（不含"打开"，避免与模式1冲突）
        val searchPattern = Regex(
            """(?:在|用|去)\s*(.+?)\s*(?:搜索|搜|查找|找|查询|查|寻找)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val searchMatch = searchPattern.find(input)
        if (searchMatch != null) {
            val appName = searchMatch.groupValues[1].trim()
            val query = searchMatch.groupValues[2].trim()
            val packageName = getPackageName(appName)

            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive(packageName),
                        "name" to JsonPrimitive(appName)
                    )),
                    description = "打开 $appName"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                    description = "等待应用加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_INPUT.name,
                    params = JsonObject(mapOf(
                        "text" to JsonPrimitive(query)
                    )),
                    description = "输入搜索内容「$query」"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_SEARCH,
                description = "在「$appName」中搜索「$query」",
                rawInput = input
            )
        }

        // 模式3：给XXX发YYY / 给XXX发消息YYY / 告诉XXX YYY
        val sendPattern = Regex(
            """(?:给|向|对)\s*(.+?)\s*(?:发|发送|发消息|说|告诉|留言)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val sendMatch = sendPattern.find(input)
        if (sendMatch != null) {
            val target = sendMatch.groupValues[1].trim()
            val message = sendMatch.groupValues[2].trim()

            // 根据目标名称推断应用
            val appName = inferAppFromTarget(target)
            val packageName = getPackageName(appName)

            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive(packageName),
                        "name" to JsonPrimitive(appName)
                    )),
                    description = "打开 $appName"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                    description = "等待应用加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_INPUT.name,
                    params = JsonObject(mapOf(
                        "text" to JsonPrimitive(message)
                    )),
                    description = "输入消息内容「$message」"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf(
                        "text" to JsonPrimitive("发送")
                    )),
                    description = "点击发送按钮"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_SEND,
                description = "给「$target」发送消息「$message」",
                rawInput = input
            )
        }

        // 模式4：打开XXX然后YYY / 启动XXX并YYY（此处的"并"连接的是非搜索操作，如"打开抖音然后点赞"）
        // 支持逗号分隔：打开豆包，并给豆包发一条你好
        val openThenPattern = Regex(
            """(?:打开|启动|开启|运行)\s*(.+?)\s*[,，]?\s*(?:然后|接着|再|并|并且|之后)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val openThenMatch = openThenPattern.find(input)
        if (openThenMatch != null) {
            val appName = openThenMatch.groupValues[1].trim()
            val nextAction = openThenMatch.groupValues[2].trim()
            val packageName = getPackageName(appName)

            // 解析后续操作
            val nextActions = parseNextAction(nextAction)

            val actions = mutableListOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive(packageName),
                        "name" to JsonPrimitive(appName)
                    )),
                    description = "打开 $appName"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待应用加载"
                )
            )
            actions.addAll(nextActions)

            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                description = "打开「$appName」然后${nextAction}",
                rawInput = input
            )
        }

        // 模式4：去XXX看YYY / 打开XXX查看YYY
        val viewPattern = Regex(
            """(?:去|到|打开|启动)\s*(.+?)\s*(?:看|查看|浏览|打开)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val viewMatch = viewPattern.find(input)
        if (viewMatch != null) {
            val appName = viewMatch.groupValues[1].trim()
            val target = viewMatch.groupValues[2].trim()
            val packageName = getPackageName(appName)

            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive(packageName),
                        "name" to JsonPrimitive(appName)
                    )),
                    description = "打开 $appName"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                    description = "等待应用加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf(
                        "text" to JsonPrimitive(target)
                    )),
                    description = "点击「$target」"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_VIEW,
                description = "在「$appName」中查看「$target」",
                rawInput = input
            )
        }

        // 模式5：帮我打开XXX / 我要打开XXX / 我想打开XXX
        val helpOpenPattern = Regex(
            """(?:帮我|请帮我|我要|我想|给我)\s*(?:打开|启动|开启|运行)\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val helpOpenMatch = helpOpenPattern.find(input)
        if (helpOpenMatch != null) {
            val appName = helpOpenMatch.groupValues[1].trim()
            val packageName = getPackageName(appName)
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive(packageName),
                            "name" to JsonPrimitive(appName)
                        )),
                        description = "打开 $appName"
                    )
                ),
                confidence = CONFIDENCE_APP_OPEN_FUZZY,
                description = "打开「$appName」",
                rawInput = input
            )
        }

        // 模式6：帮我搜索XXX / 帮我查XXX / 搜索XXX
        val helpSearchPattern = Regex(
            """(?:帮我|请帮我|我要|我想)\s*(?:搜索|搜|查找|找|查询|查)\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val helpSearchMatch = helpSearchPattern.find(input)
        if (helpSearchMatch != null) {
            val query = helpSearchMatch.groupValues[1].trim()
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(query))),
                        description = "输入搜索内容「$query」"
                    )
                ),
                confidence = CONFIDENCE_COMPOSITE_SEARCH,
                description = "搜索「$query」",
                rawInput = input
            )
        }

        // 模式7：发消息给XXX说YYY / 发消息给XXX YYY
        val sendMsgPattern = Regex(
            """(?:发消息|发信息|发短信|发微信)\s*(?:给|向|到)\s*(.+?)\s*(?:说|告诉|发|发送|：|:)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val sendMsgMatch = sendMsgPattern.find(input)
        if (sendMsgMatch != null) {
            val target = sendMsgMatch.groupValues[1].trim()
            val message = sendMsgMatch.groupValues[2].trim()
            val appName = inferAppFromTarget(target)
            val packageName = getPackageName(appName)
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive(packageName),
                        "name" to JsonPrimitive(appName)
                    )),
                    description = "打开 $appName"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                    description = "等待应用加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_INPUT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                    description = "搜索联系人「$target」"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待搜索结果"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_INPUT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive(message))),
                    description = "输入消息「$message」"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("发送"))),
                    description = "点击发送"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_SEND,
                description = "给「$target」发送消息「$message」",
                rawInput = input
            )
        }

        // =============================================================
        // 模式8：定时打开 XXX / X分钟后打开XXX / 延迟打开XXX
        // =============================================================
        val timedPattern = Regex(
            """(\d+)\s*(?:分钟|分|秒|小时|分钟后|秒后|小时后)\s*(?:打开|启动|开启|运行)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val timedMatch = timedPattern.find(input)
        if (timedMatch != null) {
            val delayAmount = timedMatch.groupValues[1].trim().toIntOrNull() ?: 0
            val appName = timedMatch.groupValues[2].trim()
            val delayMs = when {
                input.contains("秒") || input.contains("秒后") -> delayAmount * 1000L
                input.contains("分") || input.contains("分钟后") -> delayAmount * 60L * 1000L
                input.contains("小时") || input.contains("小时后") -> delayAmount * 3600L * 1000L
                else -> delayAmount * 1000L
            }
            val timeUnit = when {
                input.contains("小时") || input.contains("小时后") -> "小时"
                input.contains("分") || input.contains("分钟后") -> "分钟"
                else -> "秒"
            }
            val packageName = getPackageName(appName)
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(delayMs))),
                    description = "等待 $delayAmount $timeUnit"
                ),
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive(packageName),
                        "name" to JsonPrimitive(appName)
                    )),
                    description = "定时打开 $appName"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                description = "${delayAmount}${timeUnit}后打开「$appName」",
                rawInput = input
            )
        }

        // =============================================================
        // 模式9：依次打开XXX和YYY / 先打开XXX再打开YYY / 顺序打开XXX和YYY
        // =============================================================
        val sequentialPattern = Regex(
            """(?:先|依次|顺序|分别)?\s*(?:打开|启动|开启|运行)\s*(.+?)\s*(?:和|与|跟|还有|再|然后接着|再然后)\s*(?:打开|启动|开启|运行)?\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val sequentialMatch = sequentialPattern.find(input)
        if (sequentialMatch != null) {
            val app1 = sequentialMatch.groupValues[1].trim()
            val app2 = sequentialMatch.groupValues[2].trim()
            val pkg1 = getPackageName(app1)
            val pkg2 = getPackageName(app2)

            // 检查是否是两个应用名（都不包含连接词）
            if (app1.length <= 12 && app2.length <= 12 && !app1.contains("和") && !app1.contains("再")) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf("packageName" to JsonPrimitive(pkg1), "name" to JsonPrimitive(app1))),
                        description = "打开 $app1"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1000))),
                        description = "等待"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = JsonObject(mapOf("key" to JsonPrimitive("RECENT_APPS"))),
                        description = "切换到最近任务"
                    ),
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf("packageName" to JsonPrimitive(pkg2), "name" to JsonPrimitive(app2))),
                        description = "打开 $app2"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1000))),
                        description = "等待应用加载"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "依次打开「$app1」和「$app2」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式10：重复/循环命令 - 每隔X分钟做YYY
        // =============================================================
        val repeatPattern = Regex(
            """(?:每隔|每|每间隔|循环)\s*(\d+)\s*(?:分钟|分|秒)\s*(?:打开|启动|开启|运行|执行|做)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val repeatMatch = repeatPattern.find(input)
        if (repeatMatch != null) {
            val interval = repeatMatch.groupValues[1].trim().toIntOrNull() ?: 5
            val action = repeatMatch.groupValues[2].trim()
            val intervalMs = if (input.contains("秒")) interval * 1000 else interval * 60 * 1000
            val packageName = getPackageName(action)

            // 循环3次
            val actions = mutableListOf<ClawAction>()
            for (i in 1..3) {
                actions.add(ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf("packageName" to JsonPrimitive(packageName), "name" to JsonPrimitive(action))),
                    description = "第${i}次打开 $action"
                ))
                if (i < 3) {
                    actions.add(ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(intervalMs))),
                        description = "等待 ${interval}分钟"
                    ))
                }
            }
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                description = "每${interval}分钟打开「$action」，共3次",
                rawInput = input
            )
        }

        // =============================================================
        // 模式11：多步序列命令 - 先XXX再YYY最后ZZZ
        // 使用"然后"、"接着"、"再"、"然后"分隔多步操作
        // =============================================================
        val multiStepPattern = Regex(
            """(?:打开|启动|开启|运行)\s*(.+?)\s*(?:然后|接着|再|然后接着)\s*(?:打开|启动|开启|运行)?\s*(.+?)\s*(?:然后|接着|再|最后)\s*(?:打开|启动|开启|运行)?\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val multiStepMatch = multiStepPattern.find(input)
        if (multiStepMatch != null) {
            val apps = listOf(
                multiStepMatch.groupValues[1].trim(),
                multiStepMatch.groupValues[2].trim(),
                multiStepMatch.groupValues[3].trim()
            ).filter { it.length <= 12 && !it.contains("然后") && !it.contains("再") }

            if (apps.size >= 2) {
                val actions = mutableListOf<ClawAction>()
                for ((i, app) in apps.withIndex()) {
                    val pkg = getPackageName(app)
                    actions.add(ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf("packageName" to JsonPrimitive(pkg), "name" to JsonPrimitive(app))),
                        description = "打开 $app"
                    ))
                    if (i < apps.size - 1) {
                        actions.add(ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                            description = "等待应用切换"
                        ))
                        actions.add(ClawAction(
                            actionName = ActionType.SCREEN_KEY.name,
                            params = JsonObject(mapOf("key" to JsonPrimitive("RECENT_APPS"))),
                            description = "切换到最近任务"
                        ))
                    }
                }
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "依次打开 ${apps.joinToString("、")}",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式12：打电话给XXX / 拨号给XXX / 呼叫XXX
        // =============================================================
        val callPattern = Regex(
            """(?:打电话|拨号|呼叫|打给|拨打)\s*(?:给|到)?\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val callMatch = callPattern.find(input)
        if (callMatch != null) {
            val target = callMatch.groupValues[1].trim()
            // 如果是数字，直接拨号
            val isPhoneNumber = target.matches(Regex("""^[\d\-+\s()]{5,20}$"""))
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.dialer"),
                        "name" to JsonPrimitive("电话")
                    )),
                    description = "打开电话应用"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待电话应用加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_INPUT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                    description = if (isPhoneNumber) "拨号「$target」" else "搜索联系人「$target」"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                description = "打电话给「$target」",
                rawInput = input
            )
        }

        // =============================================================
        // 模式13：发短信给XXX说YYY / 短信给XXX YYY
        // =============================================================
        val smsPattern = Regex(
            """(?:发短信|发信息|短信|发消息)\s*(?:给|到)?\s*(.+?)\s*(?:说|告诉|：|:)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val smsMatch = smsPattern.find(input)
        if (smsMatch != null) {
            val target = smsMatch.groupValues[1].trim()
            val message = smsMatch.groupValues[2].trim()
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.mms"),
                        "name" to JsonPrimitive("短信")
                    )),
                    description = "打开短信应用"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待短信应用加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_INPUT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                    description = "输入收件人「$target」"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1000))),
                    description = "等待"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_INPUT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive(message))),
                    description = "输入短信内容「$message」"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("发送"))),
                    description = "点击发送"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_SEND,
                description = "发短信给「$target」说「$message」",
                rawInput = input
            )
        }

        // =============================================================
        // 模式14：帮我查XXX / 帮我搜索一下XXX / 查一下XXX / 搜索XXX
        // 智能跳到浏览器搜索
        // =============================================================
        val searchQueryPattern = Regex(
            """(?:帮我|请帮我|帮我查|帮我搜|查一下|搜一下|查询|搜索一下|搜索)\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val searchQueryMatch = searchQueryPattern.find(input)
        if (searchQueryMatch != null) {
            val query = searchQueryMatch.groupValues[1].trim()
            // 排除已经匹配过的命令
            if (query.length > 1 && query.length < 50 && !query.contains("打开") && !query.contains("微信") && !query.contains("短信")) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.android.chrome"),
                            "name" to JsonPrimitive("浏览器")
                        )),
                        description = "打开浏览器"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待浏览器加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(query))),
                        description = "搜索「$query」"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_SEARCH,
                    description = "搜索「$query」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式15：导航到XXX / 去XXX / 我要去XXX
        // =============================================================
        val navigatePattern = Regex(
            """(?:导航|导航到|去|我要去|带我去)\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val navigateMatch = navigatePattern.find(input)
        if (navigateMatch != null) {
            val destination = navigateMatch.groupValues[1].trim()
            if (destination.length > 1 && destination.length < 30) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.autonavi.minimap"),
                            "name" to JsonPrimitive("高德地图")
                        )),
                        description = "打开高德地图"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待地图加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(destination))),
                        description = "搜索目的地「$destination」"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "导航到「$destination」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式16：自然时间解析 - "半小时后打开XXX"
        // =============================================================
        val naturalTimePattern = Regex(
            """(?:半|一|两|二|三|四|五|六|七|八|九|十)\s*(?:小时|分钟|秒|刻钟|个(?:小时|分钟|秒))\s*(?:后|之后)\s*(?:打开|启动|开启|运行)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val naturalTimeMatch = naturalTimePattern.find(input)
        if (naturalTimeMatch != null) {
            val appName = naturalTimeMatch.groupValues[1].trim()
            val timeText = input.substring(0, input.indexOf(appName)).trim()
            // 计算延迟时间
            var delayMinutes = 0
            when {
                timeText.contains("半") && timeText.contains("小时") -> delayMinutes = 30
                timeText.contains("半") && timeText.contains("分钟") -> delayMinutes = 0
                timeText.contains("一刻钟") -> delayMinutes = 15
                timeText.contains("小时") -> {
                    val hours = extractChineseNumber(timeText)
                    delayMinutes = hours * 60
                }
                timeText.contains("分钟") -> {
                    delayMinutes = extractChineseNumber(timeText)
                }
                timeText.contains("秒") -> {
                    delayMinutes = 0 // 秒级，忽略
                }
            }
            val delayMs = delayMinutes * 60 * 1000L
            if (delayMs > 0) {
                val packageName = getPackageName(appName)
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(delayMs))),
                        description = "等待 $delayMinutes 分钟"
                    ),
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive(packageName),
                            "name" to JsonPrimitive(appName)
                        )),
                        description = "定时打开 $appName"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "${timeText}打开「$appName」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式17：我想发消息 / 我要发消息 / 发消息（智能推断）
        // =============================================================
        val vagueMsgPattern = Regex(
            """(?:我(?:想|要|打算)\s*发(?:消息|信息|微信|短信)|发(?:个|一条)?消息|我要发)""",
            RegexOption.IGNORE_CASE
        )
        if (vagueMsgPattern.containsMatchIn(input)) {
            // 推断用户想发消息，默认打开微信
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.tencent.mm"),
                        "name" to JsonPrimitive("微信")
                    )),
                    description = "打开微信（准备发消息）"
                ),
                ClawAction(
                    actionName = ActionType.ANSWER.name,
                    params = JsonObject(mapOf(
                        "text" to JsonPrimitive("已打开微信，请告诉我你想给谁发消息以及发什么内容")
                    )),
                    description = "提示用户"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                description = "准备发消息（打开微信）",
                rawInput = input
            )
        }

        // =============================================================
        // 模式18：查看XXX / 看看XXX / 浏览XXX（智能推断要打开的应用）
        // 例如"查看微博热搜"、"看看微信朋友圈"、"浏览抖音"
        // =============================================================
        val browsePattern = Regex(
            """(?:查看|看看|浏览|看下|看一?下|瞅瞅|瞧瞧|刷刷)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val browseMatch = browsePattern.find(input)
        if (browseMatch != null) {
            val target = browseMatch.groupValues[1].trim()
            if (target.length > 1 && target.length < 30) {
                // 智能推断要打开的应用
                val appName = APP_PACKAGE_MAP.keys.firstOrNull { target.contains(it) || it.contains(target) }
                if (appName != null) {
                    val packageName = getPackageName(appName)
                    // 移除应用名，剩下的是具体内容
                    val content = target.replace(appName, "").trim()
                    val actions = mutableListOf(
                        ClawAction(
                            actionName = ActionType.APP_OPEN.name,
                            params = JsonObject(mapOf(
                                "packageName" to JsonPrimitive(packageName),
                                "name" to JsonPrimitive(appName)
                            )),
                            description = "打开 $appName"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                            description = "等待应用加载"
                        )
                    )
                    if (content.isNotEmpty()) {
                        actions.add(ClawAction(
                            actionName = ActionType.SCREEN_INPUT.name,
                            params = JsonObject(mapOf("text" to JsonPrimitive(content))),
                            description = "搜索「$content」"
                        ))
                    }
                    return ExecuteResult(
                        actions = actions,
                        confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                        description = "查看「$target」",
                        rawInput = input
                    )
                }
            }
        }

        // =============================================================
        // 模式19：帮我打开XXX的YYY / 打开XXX的YYY页面
        // 例如"帮我打开微信的支付页面"、"打开支付宝的扫一扫"
        // =============================================================
        val openPagePattern = Regex(
            """(?:帮我)?(?:打开|进入|去)\s*(.+?)\s*(?:的|里面|里)\s*(.+?)(?:页面|界面|功能|设置|页)?$""",
            RegexOption.IGNORE_CASE
        )
        val openPageMatch = openPagePattern.find(input)
        if (openPageMatch != null) {
            val appName = openPageMatch.groupValues[1].trim()
            val page = openPageMatch.groupValues[2].trim()
            val packageName = getPackageName(appName)
            if (packageName != null && appName.length <= 10 && page.length > 1) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive(packageName),
                            "name" to JsonPrimitive(appName)
                        )),
                        description = "打开 $appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(page))),
                        description = "点击「$page」"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "打开「$appName」的「$page」页面",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式20：播放XXX / 听XXX / 看XXX（智能推断媒体应用）
        // 例如"播放周杰伦的歌"、"听音乐"、"看视频"
        // =============================================================
        val playPattern = Regex(
            """(?:播放|听|看|放)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val playMatch = playPattern.find(input)
        if (playMatch != null) {
            val content = playMatch.groupValues[1].trim()
            if (content.length > 1 && content.length < 40) {
                // 智能推断媒体应用
                val musicKeywords = listOf("歌", "音乐", "歌曲", "周杰伦", "网易云", "QQ音乐")
                val videoKeywords = listOf("视频", "电影", "电视剧", "动漫", "综艺", "剧")
                val isMusic = musicKeywords.any { content.contains(it) }
                val isVideo = videoKeywords.any { content.contains(it) }
                val appName = when {
                    isMusic -> "网易云音乐"
                    isVideo -> "腾讯视频"
                    else -> "网易云音乐"
                }
                val packageName = getPackageName(appName)
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive(packageName),
                            "name" to JsonPrimitive(appName)
                        )),
                        description = "打开 $appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(content))),
                        description = "搜索「$content」"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "播放「$content」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式21：提醒我XXX / 设置提醒XXX / 定时提醒XXX
        // 例如"提醒我明天早上8点开会"、"设置提醒10分钟后关火"
        // =============================================================
        val reminderPattern = Regex(
            """(?:提醒我|设置提醒|定时提醒|设个提醒)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val reminderMatch = reminderPattern.find(input)
        if (reminderMatch != null) {
            val reminderContent = reminderMatch.groupValues[1].trim()
            if (reminderContent.length > 1) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.android.deskclock"),
                            "name" to JsonPrimitive("时钟")
                        )),
                        description = "打开时钟（设置提醒）"
                    ),
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf(
                            "text" to JsonPrimitive("已打开时钟应用，请在时钟中设置提醒：「$reminderContent」")
                        )),
                        description = "提示用户设置提醒"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "提醒「$reminderContent」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式22：智能预判 - 拼音/近义词匹配，处理"为"->"微"、"抖印"->"抖音"等
        // 当用户输入类似于"打开为信"、"打抖印"等情况时智能匹配
        // =============================================================
        val smartOpenPattern = Regex(
            """(?:打开|启动|开启|运行)\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val smartOpenMatch = smartOpenPattern.find(input)
        if (smartOpenMatch != null) {
            val appName = smartOpenMatch.groupValues[1].trim()
            // 如果之前没匹配到，尝试拼音纠错
            if (appName.length >= 2 && appName.all { it.isLowerCase() || it.isUpperCase() }) {
                // 尝试拼音首字母匹配
                val pinyinMatch = matchPinyinAbbreviation(appName)
                if (pinyinMatch != null) {
                    val packageName = getPackageName(pinyinMatch)
                    val actions = listOf(
                        ClawAction(
                            actionName = ActionType.APP_OPEN.name,
                            params = JsonObject(mapOf(
                                "packageName" to JsonPrimitive(packageName),
                                "name" to JsonPrimitive(pinyinMatch)
                            )),
                            description = "打开应用「$pinyinMatch」（拼音匹配）"
                        )
                    )
                    return ExecuteResult(
                        actions = actions,
                        confidence = CONFIDENCE_APP_OPEN_FUZZY,
                        description = "打开「$pinyinMatch」",
                        rawInput = input
                    )
                }
            }
        }

        // =============================================================
        // 模式23：打电话/发短信/发微信给XXX
        // 支持：打电话给XXX / 打给XXX / 给XXX打电话
        //       发短信给XXX说YYY / 给XXX发短信说YYY
        //       发微信给XXX说YYY / 给XXX发微信说YYY
        // =============================================================
        // 打电话给XXX / 打给XXX / 给XXX打电话
        val callContactPattern = Regex(
            """(?:(?:打电话|拨号|拨打|打给)\s*(?:给|到)?\s*(.+?)(?:\s*(?:说|告诉|：|:)\s*(.+))?$|(?:给)\s*(.+?)\s*(?:打电话|打给|拨号))""",
            RegexOption.IGNORE_CASE
        )
        val callContactMatch = callContactPattern.find(input)
        if (callContactMatch != null) {
            val target = callContactMatch.groupValues[1].ifBlank {
                callContactMatch.groupValues[3]
            }.trim()
            val message = callContactMatch.groupValues[2].trim()
            if (target.isNotBlank()) {
                val isPhoneNumber = target.matches(Regex("""^[\d\-+\s()]{5,20}$"""))
                val actions = mutableListOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.android.dialer"),
                            "name" to JsonPrimitive("电话")
                        )),
                        description = "打开电话应用"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                        description = "等待电话应用加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                        description = if (isPhoneNumber) "拨号「$target」" else "搜索联系人「$target」"
                    )
                )
                if (message.isNotBlank()) {
                    actions.add(ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1000))),
                        description = "等待"
                    ))
                    actions.add(ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(message))),
                        description = "输入通话中留言「$message」"
                    ))
                }
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "打电话给「$target」",
                    rawInput = input
                )
            }
        }

        // 发短信给XXX说YYY / 给XXX发短信说YYY
        val smsContactPattern = Regex(
            """(?:发短信|发信息|短信|发消息)\s*(?:给|到)?\s*(.+?)\s*(?:说|告诉|：|:)\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val smsContactMatch = smsContactPattern.find(input)
        if (smsContactMatch != null) {
            val target = smsContactMatch.groupValues[1].trim()
            val message = smsContactMatch.groupValues[2].trim()
            if (target.isNotBlank() && message.isNotBlank()) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.android.mms"),
                            "name" to JsonPrimitive("短信")
                        )),
                        description = "打开短信应用"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                        description = "等待短信应用加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                        description = "输入收件人「$target」"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1000))),
                        description = "等待"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(message))),
                        description = "输入短信内容「$message」"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("发送"))),
                        description = "点击发送"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_SEND,
                    description = "发短信给「$target」说「$message」",
                    rawInput = input
                )
            }
        }

        // 发微信给XXX说YYY / 给XXX发微信说YYY
        val wechatContactPattern = Regex(
            """(?:发微信|微信)\s*(?:给|到)?\s*(.+?)\s*(?:说|告诉|：|:)\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val wechatContactMatch = wechatContactPattern.find(input)
        if (wechatContactMatch != null) {
            val target = wechatContactMatch.groupValues[1].trim()
            val message = wechatContactMatch.groupValues[2].trim()
            if (target.isNotBlank() && message.isNotBlank()) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.tencent.mm"),
                            "name" to JsonPrimitive("微信")
                        )),
                        description = "打开微信"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待微信加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                        description = "搜索联系人「$target」"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                        description = "等待搜索结果"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(message))),
                        description = "输入消息「$message」"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("发送"))),
                        description = "点击发送"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_SEND,
                    description = "发微信给「$target」说「$message」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式24：定时执行任务（自然语言日期）
        // 支持：明天早上8点打开XXX / 明早8点打开XXX
        //       后天下午3点发送消息给XXX说YYY
        // =============================================================
        val naturalTimedPattern = Regex(
            """(?:(?:明天|明早|明儿|明天早上|明天上午|明天下午|明天晚上|后天|后天早上|后天上午|后天下午|后天晚上)|(?:大后天))\s*(?:早上|上午|中午|下午|晚上|凌晨)?\s*(\d{1,2})\s*(?:[：:]\s*(\d{2}))?\s*(?:点|时)\s*(?:打开|启动|开启|运行|发送|发消息|发)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val naturalTimedMatch = naturalTimedPattern.find(input)
        if (naturalTimedMatch != null) {
            val hour = naturalTimedMatch.groupValues[1].toIntOrNull() ?: 8
            val minute = naturalTimedMatch.groupValues[2].toIntOrNull() ?: 0
            val actionDesc = naturalTimedMatch.groupValues[3].trim()

            // 解析日期偏移
            val now = Calendar.getInstance()
            var dayOffset = 0
            when {
                input.contains("大后天") -> dayOffset = 3
                input.contains("后天") -> dayOffset = 2
                input.contains("明天") || input.contains("明早") || input.contains("明儿") -> dayOffset = 1
            }

            // 计算目标时间
            val targetCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                set(Calendar.MINUTE, minute.coerceIn(0, 59))
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val delayMs = targetCal.timeInMillis - now.timeInMillis
            if (delayMs > 0) {
                // 解析要执行的动作
                val appName = actionDesc
                val packageName = getPackageName(appName)
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(delayMs))),
                        description = "等待到指定时间"
                    ),
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive(packageName),
                            "name" to JsonPrimitive(appName)
                        )),
                        description = "定时打开 $appName"
                    )
                )
                val dateLabel = when (dayOffset) {
                    0 -> "今天"
                    1 -> "明天"
                    2 -> "后天"
                    3 -> "大后天"
                    else -> "今天"
                }
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "${dateLabel}${hour}:${"%02d".format(minute)}打开「$appName」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式25：批量操作 - 同时打开/依次打开多个应用
        // 支持：同时打开XXX和YYY / 依次打开XXX和YYY和ZZZ
        //       打开XXX、YYY和ZZZ
        // =============================================================
        // 同时打开/依次打开 XXX和YYY和ZZZ
        val batchOpenPattern = Regex(
            """(?:(?:同时|一起|一并)\s*)?(?:打开|启动|开启|运行)\s*(.+?)(?:\s*(?:和|与|跟|、|,|，)\s*(.+?)(?:\s*(?:和|与|跟|、|,|，)\s*(.+))?)?$""",
            RegexOption.IGNORE_CASE
        )
        val batchOpenMatch = batchOpenPattern.find(input)
        if (batchOpenMatch != null) {
            val appNames = mutableListOf<String>()
            for (i in 1..3) {
                val name = batchOpenMatch.groupValues[i].trim()
                if (name.isNotBlank() && !name.contains("和") && !name.contains("与") && !name.contains("跟") && name.length <= 15) {
                    appNames.add(name)
                }
            }
            // 也支持中文顿号分隔：打开XXX、YYY、ZZZ
            if (appNames.size <= 1 && input.contains("、")) {
                val parts = input.replace(Regex("""(?:同时|一起|一并|打开|启动|开启|运行)\s*"""), "").split("、", "和", "与", "跟")
                appNames.clear()
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isNotBlank() && trimmed.length <= 15) {
                        appNames.add(trimmed)
                    }
                }
            }
            if (appNames.size >= 2) {
                val isSequential = input.contains("依次") || input.contains("顺序")
                val waitMs = if (isSequential) 1500L else 500L
                val actions = mutableListOf<ClawAction>()
                for ((i, app) in appNames.withIndex()) {
                    val pkg = getPackageName(app)
                    actions.add(ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf("packageName" to JsonPrimitive(pkg), "name" to JsonPrimitive(app))),
                        description = "打开 $app"
                    ))
                    if (i < appNames.size - 1) {
                        actions.add(ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(waitMs))),
                            description = "等待应用切换"
                        ))
                    }
                }
                val mode = if (isSequential) "依次" else "同时"
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_COMPOSITE_OPEN_THEN,
                    description = "${mode}打开 ${appNames.joinToString("、")}",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 模式26：系统操作 - 连接WiFi/打开蓝牙/关闭手电筒等
        // 支持：连接WiFi / 打开蓝牙 / 关闭蓝牙 / 打开手电筒 / 关闭手电筒
        //       开启飞行模式 / 关闭飞行模式
        //       打开热点 / 关闭热点 / 打开静音模式 / 关闭静音模式
        //       打开VPN / 关闭VPN
        // =============================================================
        // WiFi连接
        if (Regex("""(?:连接|打开|开启|启动)\s*(?:WiFi|wifi|无线网络|Wi-Fi|无线)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:关闭|断开|停止|关掉|退出)\s*(?:WiFi|wifi|无线网络|Wi-Fi|无线)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.settings"),
                        "name" to JsonPrimitive("设置")
                    )),
                    description = "打开设置"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待设置加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("WiFi"))),
                    description = "点击WiFi设置"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "打开WiFi设置",
                rawInput = input
            )
        }
        // WiFi断开
        if (Regex("""(?:关闭|断开|停止|关掉|退出)\s*(?:WiFi|wifi|无线网络|Wi-Fi|无线)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.settings"),
                        "name" to JsonPrimitive("设置")
                    )),
                    description = "打开设置"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待设置加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("WiFi"))),
                    description = "点击WiFi设置"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "关闭WiFi（进入设置）",
                rawInput = input
            )
        }
        // 蓝牙
        if (Regex("""(?:打开|开启|启动)\s*(?:蓝牙|Bluetooth)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:关闭|断开|停止|关掉|退出)\s*(?:蓝牙|Bluetooth)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.settings"),
                        "name" to JsonPrimitive("设置")
                    )),
                    description = "打开设置"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待设置加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("蓝牙"))),
                    description = "点击蓝牙设置"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "打开蓝牙设置",
                rawInput = input
            )
        }
        if (Regex("""(?:关闭|断开|停止|关掉|退出)\s*(?:蓝牙|Bluetooth)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.settings"),
                        "name" to JsonPrimitive("设置")
                    )),
                    description = "打开设置"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待设置加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("蓝牙"))),
                    description = "点击蓝牙设置"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "关闭蓝牙（进入设置）",
                rawInput = input
            )
        }
        // 手电筒
        if (Regex("""(?:打开|开启|启动)\s*(?:手电筒|闪光灯|手电)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:关闭|关掉|停止)\s*(?:手电筒|闪光灯|手电)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请下拉通知栏，点击手电筒图标打开"))),
                        description = "提示打开手电筒"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "打开手电筒",
                rawInput = input
            )
        }
        if (Regex("""(?:关闭|关掉|停止)\s*(?:手电筒|闪光灯|手电)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请下拉通知栏，点击手电筒图标关闭"))),
                        description = "提示关闭手电筒"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "关闭手电筒",
                rawInput = input
            )
        }
        // 飞行模式
        if (Regex("""(?:打开|开启|启动)\s*(?:飞行模式|飞行)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:关闭|关掉|停止)\s*(?:飞行模式|飞行)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请下拉通知栏，点击飞行模式图标打开"))),
                        description = "提示打开飞行模式"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "打开飞行模式",
                rawInput = input
            )
        }
        if (Regex("""(?:关闭|关掉|停止)\s*(?:飞行模式|飞行)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请下拉通知栏，点击飞行模式图标关闭"))),
                        description = "提示关闭飞行模式"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "关闭飞行模式",
                rawInput = input
            )
        }
        // 热点
        if (Regex("""(?:打开|开启|启动)\s*(?:热点|个人热点|WiFi热点)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:关闭|关掉|停止)\s*(?:热点|个人热点|WiFi热点)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.settings"),
                        "name" to JsonPrimitive("设置")
                    )),
                    description = "打开设置"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待设置加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("热点"))),
                    description = "点击热点设置"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "打开热点设置",
                rawInput = input
            )
        }
        if (Regex("""(?:关闭|关掉|停止)\s*(?:热点|个人热点|WiFi热点)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请进入设置-个人热点，关闭热点开关"))),
                        description = "提示关闭热点"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "关闭热点",
                rawInput = input
            )
        }
        // 静音模式
        if (Regex("""(?:打开|开启|启动)\s*(?:静音模式|静音|勿扰模式|免打扰)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:关闭|关掉|停止)\s*(?:静音模式|静音|勿扰模式|免打扰)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = JsonObject(mapOf("key" to JsonPrimitive("VOLUME_DOWN"))),
                        description = "按音量减键"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "打开静音模式",
                rawInput = input
            )
        }
        if (Regex("""(?:关闭|关掉|停止)\s*(?:静音模式|静音|勿扰模式|免打扰)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = JsonObject(mapOf("key" to JsonPrimitive("VOLUME_UP"))),
                        description = "按音量加键"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "关闭静音模式",
                rawInput = input
            )
        }
        // VPN
        if (Regex("""(?:打开|开启|连接)\s*(?:VPN|虚拟专用网络)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:关闭|断开|停止)\s*(?:VPN|虚拟专用网络)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.settings"),
                        "name" to JsonPrimitive("设置")
                    )),
                    description = "打开设置"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待设置加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("VPN"))),
                    description = "点击VPN设置"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "打开VPN设置",
                rawInput = input
            )
        }
        if (Regex("""(?:关闭|断开|停止)\s*(?:VPN|虚拟专用网络)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val actions = listOf(
                ClawAction(
                    actionName = ActionType.APP_OPEN.name,
                    params = JsonObject(mapOf(
                        "packageName" to JsonPrimitive("com.android.settings"),
                        "name" to JsonPrimitive("设置")
                    )),
                    description = "打开设置"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_WAIT.name,
                    params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                    description = "等待设置加载"
                ),
                ClawAction(
                    actionName = ActionType.SCREEN_CLICK_TEXT.name,
                    params = JsonObject(mapOf("text" to JsonPrimitive("VPN"))),
                    description = "点击VPN设置"
                )
            )
            return ExecuteResult(
                actions = actions,
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "关闭VPN（进入设置）",
                rawInput = input
            )
        }

        // =============================================================
        // 模式27：截屏/截图/录屏/分屏/投屏等增强操作
        // =============================================================
        // 截屏/截图
        if (Regex("""(?:截屏|截图|屏幕截图|截个图|截一下|截一张)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:长截图|长截屏|滚动截屏|滚动截图|录屏|录制)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = JsonObject(mapOf("key" to JsonPrimitive("SCREENSHOT"))),
                        description = "截屏"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "截屏",
                rawInput = input
            )
        }
        // 长截图/滚动截屏
        if (Regex("""(?:长截图|长截屏|滚动截屏|滚动截图|截长图)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = JsonObject(mapOf("key" to JsonPrimitive("SCREENSHOT"))),
                        description = "截屏"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1000))),
                        description = "等待截屏完成"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("滚动截屏"))),
                        description = "点击滚动截屏"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "长截图",
                rawInput = input
            )
        }
        // 录屏/屏幕录制
        if (Regex("""(?:录屏|录制屏幕|屏幕录制|开始录屏|开始录制)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请下拉通知栏，点击屏幕录制图标开始录屏"))),
                        description = "提示开始录屏"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "录屏",
                rawInput = input
            )
        }
        // 停止录屏
        if (Regex("""(?:停止录屏|结束录制|结束录屏|停止录制)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请下拉通知栏，点击屏幕录制通知停止录屏"))),
                        description = "提示停止录屏"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "停止录屏",
                rawInput = input
            )
        }
        // 分屏模式
        if (Regex("""(?:分屏|分屏模式|打开分屏|开启分屏|进入分屏)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请进入最近任务列表，长按应用图标选择「分屏」"))),
                        description = "提示分屏操作"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "分屏",
                rawInput = input
            )
        }
        // 投屏/无线投屏
        if (Regex("""(?:投屏|无线投屏|屏幕投射|投屏到|镜像|屏幕镜像)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("请下拉通知栏，点击「投屏」或进入设置-连接与共享-投屏"))),
                        description = "提示投屏操作"
                    )
                ),
                confidence = CONFIDENCE_SYSTEM_OPERATION,
                description = "投屏",
                rawInput = input
            )
        }

        return null
    }

    /**
     * 提取中文数字（一、二、三... 十）。
     */
    private fun extractChineseNumber(text: String): Int {
        val chineseNumbers = mapOf(
            "零" to 0, "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
            "半" to 0
        )
        // 先尝试匹配阿拉伯数字
        val digitMatch = Regex("""(\d+)""").find(text)
        if (digitMatch != null) {
            return digitMatch.groupValues[1].toIntOrNull() ?: 1
        }
        // 匹配中文数字
        for ((chinese, value) in chineseNumbers) {
            if (text.contains(chinese)) {
                return if (chinese == "十" && value == 10) {
                    // 检查"十"前面是否有数字
                    val idx = text.indexOf("十")
                    if (idx > 0) {
                        val prev = text.substring(idx - 1, idx)
                        val prevNum = chineseNumbers[prev]
                        if (prevNum != null && prevNum > 0) prevNum * 10 else 10
                    } else 10
                } else value
            }
        }
        return 1
    }

    /**
     * 解析后续操作文本，生成对应的动作列表。
     *
     * 支持的操作：
     * - "搜索XXX" / "搜XXX" -> SCREEN_INPUT
     * - "截图" / "截屏" -> SCREEN_SCREENSHOT
     * - "返回" / "后退" -> SCREEN_KEY BACK
     * - "点击XXX" -> SCREEN_CLICK_TEXT
     * - "输入XXX" -> SCREEN_INPUT
     * - "滑动" -> SCREEN_SWIPE
     * - "等待X秒" -> SCREEN_WAIT
     */
    private fun parseNextAction(nextAction: String): List<ClawAction> {
        val actions = mutableListOf<ClawAction>()

        // 搜索
        val searchRegex = Regex("""(?:搜索|搜|查找|找)\s*(.+)""", RegexOption.IGNORE_CASE)
        val searchMatch = searchRegex.find(nextAction)
        if (searchMatch != null) {
            val query = searchMatch.groupValues[1].trim()
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_INPUT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(query))),
                description = "搜索「$query」"
            ))
            return actions
        }

        // 截图
        if (Regex("""截图|截屏|screenshot""", RegexOption.IGNORE_CASE).containsMatchIn(nextAction)) {
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_SCREENSHOT.name,
                params = JsonObject(emptyMap()),
                description = "截屏"
            ))
            return actions
        }

        // 返回
        if (Regex("""返回|后退|回去|back""", RegexOption.IGNORE_CASE).containsMatchIn(nextAction)) {
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("BACK"))),
                description = "返回"
            ))
            return actions
        }

        // 点击
        val clickRegex = Regex("""(?:点击|点|按|单击|双击)\s*(.+)""", RegexOption.IGNORE_CASE)
        val clickMatch = clickRegex.find(nextAction)
        if (clickMatch != null) {
            val target = clickMatch.groupValues[1].trim()
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_CLICK_TEXT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                description = "点击「$target」"
            ))
            return actions
        }

        // 输入
        val inputRegex = Regex("""(?:输入|键入|写入|填)\s*(.+)""", RegexOption.IGNORE_CASE)
        val inputMatch = inputRegex.find(nextAction)
        if (inputMatch != null) {
            val text = inputMatch.groupValues[1].trim()
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_INPUT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(text))),
                description = "输入「$text」"
            ))
            return actions
        }

        // 等待
        val waitRegex = Regex("""(?:等待|等|稍等)\s*(\d+)\s*(?:秒|s)""", RegexOption.IGNORE_CASE)
        val waitMatch = waitRegex.find(nextAction)
        if (waitMatch != null) {
            val seconds = waitMatch.groupValues[1].toIntOrNull() ?: 1
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_WAIT.name,
                params = JsonObject(mapOf("ms" to JsonPrimitive(seconds * 1000))),
                description = "等待 ${seconds} 秒"
            ))
            return actions
        }

        // 滑动
        if (Regex("""滑动|上滑|下滑|左滑|右滑|滚动""", RegexOption.IGNORE_CASE).containsMatchIn(nextAction)) {
            val direction = when {
                Regex("""上滑|向上|上滚动""", RegexOption.IGNORE_CASE).containsMatchIn(nextAction) -> "UP"
                Regex("""下滑|向下|下滚动""", RegexOption.IGNORE_CASE).containsMatchIn(nextAction) -> "DOWN"
                Regex("""左滑|向左|左滚动""", RegexOption.IGNORE_CASE).containsMatchIn(nextAction) -> "LEFT"
                Regex("""右滑|向右|右滚动""", RegexOption.IGNORE_CASE).containsMatchIn(nextAction) -> "RIGHT"
                else -> "UP"
            }
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_SWIPE.name,
                params = JsonObject(mapOf("direction" to JsonPrimitive(direction))),
                description = "向${direction}滑动"
            ))
            return actions
        }

        // 给XXX发YYY / 给XXX发送YYY / 告诉XXX YYY（后续操作中的发送消息）
        val sendToNextRegex = Regex("""(?:给|向|对)\s*(.+?)\s*(?:发|发送|发消息|说|告诉|留言)\s*(.+)""", RegexOption.IGNORE_CASE)
        val sendToNextMatch = sendToNextRegex.find(nextAction)
        if (sendToNextMatch != null) {
            val contact = sendToNextMatch.groupValues[1].trim()
            val message = sendToNextMatch.groupValues[2].trim()
            // 先尝试搜索联系人
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_INPUT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(contact))),
                description = "搜索联系人「$contact」"
            ))
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_WAIT.name,
                params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                description = "等待搜索结果"
            ))
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_INPUT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(message))),
                description = "输入消息内容「$message」"
            ))
            actions.add(ClawAction(
                actionName = ActionType.SCREEN_CLICK_TEXT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive("发送"))),
                description = "点击发送按钮"
            ))
            return actions
        }

        // 未识别的后续操作，作为一个整体返回
        actions.add(ClawAction(
            actionName = ActionType.ANSWER.name,
            params = JsonObject(mapOf("text" to JsonPrimitive(nextAction))),
            description = "后续操作：$nextAction"
        ))
        return actions
    }

    /**
     * 根据目标名称推断应使用的应用。
     *
     * 例如给"张三"发消息 -> 打开微信；
     * 给"李四"发消息 -> 打开微信。
     * 如果目标为联系人名称，默认使用微信。
     */
    private fun inferAppFromTarget(target: String): String {
        // 如果目标名称中包含应用名，直接使用对应应用
        for ((name, _) in APP_PACKAGE_MAP) {
            if (target.contains(name) || name.contains(target)) {
                return name
            }
        }
        // 默认使用微信
        return "微信"
    }

    /**
     * 尝试匹配单步命令。
     *
     * 按以下优先级依次匹配：
     * 1. APP_OPEN - 打开应用命令
     * 2. SCREEN_SCREENSHOT - 截屏命令
     * 3. SCREEN_KEY - 按键命令
     * 4. SYSTEM_GET_INFO - 系统信息查询
     * 5. SYSTEM_CLEAR_CACHE - 清理缓存
     * 6. MEDIA_CONTROL - 媒体控制
     * 7. CLIPBOARD_COPY - 剪贴板操作
     * 8. SCREEN_CLICK_TEXT - 点击文本
     * 9. SCREEN_FIND_AND_CLICK - 查找并点击
     * 10. SCREEN_WAIT - 等待
     * 11. APP_CLOSE - 关闭应用
     * 12. SCREEN_SWIPE - 滑动
     * 13. 其他动作
     */
    private fun tryMatchSingle(input: String): ExecuteResult? {
        // 1. APP_OPEN：打开/启动/开启/运行 + 应用名
        val openResult = matchAppOpen(input)
        if (openResult != null) return openResult

        // 2. SCREEN_SCREENSHOT：截屏相关
        val screenshotResult = matchScreenshot(input)
        if (screenshotResult != null) return screenshotResult

        // 3. SCREEN_KEY：按键操作
        val keyResult = matchKeyAction(input)
        if (keyResult != null) return keyResult

        // 4. SYSTEM_GET_INFO：系统信息查询
        val infoResult = matchSystemInfo(input)
        if (infoResult != null) return infoResult

        // 5. SYSTEM_CLEAR_CACHE：清理缓存
        val cacheResult = matchClearCache(input)
        if (cacheResult != null) return cacheResult

        // 6. MEDIA_CONTROL：媒体控制
        val mediaResult = matchMediaControl(input)
        if (mediaResult != null) return mediaResult

        // 7. CLIPBOARD_COPY：剪贴板
        val clipboardResult = matchClipboard(input)
        if (clipboardResult != null) return clipboardResult

        // 8. SCREEN_CLICK_TEXT：点击文本
        val clickTextResult = matchClickText(input)
        if (clickTextResult != null) return clickTextResult

        // 9. SCREEN_FIND_AND_CLICK：查找并点击
        val findClickResult = matchFindAndClick(input)
        if (findClickResult != null) return findClickResult

        // 10. SCREEN_WAIT：等待
        val waitResult = matchWait(input)
        if (waitResult != null) return waitResult

        // 11. APP_CLOSE：关闭应用
        val closeResult = matchAppClose(input)
        if (closeResult != null) return closeResult

        // 12. SCREEN_SWIPE：滑动
        val swipeResult = matchSwipe(input)
        if (swipeResult != null) return swipeResult

        // 13. 系统设置
        val settingsResult = matchSystemSettings(input)
        if (settingsResult != null) return settingsResult

        // =============================================================
        // 14. 导航/路线操作
        // 支持：到XXX怎么走 / 导航到XXX / 去XXX的路线
        // =============================================================
        val navigatePattern = Regex(
            """(?:到|去|导航到|前往)\s*(.+?)\s*(?:怎么走|路线|导航|路线规划|怎么去|如何走|的路)""",
            RegexOption.IGNORE_CASE
        )
        val navigateMatch = navigatePattern.find(input)
        if (navigateMatch != null) {
            val destination = navigateMatch.groupValues[1].trim()
            if (destination.length > 1 && destination.length < 30) {
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.autonavi.minimap"),
                            "name" to JsonPrimitive("高德地图")
                        )),
                        description = "打开高德地图"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待地图加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(destination))),
                        description = "搜索目的地「$destination」"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_NAVIGATE,
                    description = "导航到「$destination」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 15. 查看/看看操作
        // 支持：查看XXX / 看看XXX / 查看一下XXX
        // =============================================================
        val viewActionPattern = Regex(
            """(?:查看|看看|查看一下|瞅瞅|瞧瞧)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val viewActionMatch = viewActionPattern.find(input)
        if (viewActionMatch != null) {
            val target = viewActionMatch.groupValues[1].trim()
            if (target.length > 1 && target.length < 30) {
                // 智能推断要打开的应用
                val appName = APP_PACKAGE_MAP.keys.firstOrNull { target.contains(it) || it.contains(target) }
                if (appName != null) {
                    val packageName = getPackageName(appName)
                    val content = target.replace(appName, "").trim()
                    val actions = mutableListOf(
                        ClawAction(
                            actionName = ActionType.APP_OPEN.name,
                            params = JsonObject(mapOf(
                                "packageName" to JsonPrimitive(packageName),
                                "name" to JsonPrimitive(appName)
                            )),
                            description = "打开 $appName"
                        ),
                        ClawAction(
                            actionName = ActionType.SCREEN_WAIT.name,
                            params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                            description = "等待应用加载"
                        )
                    )
                    if (content.isNotEmpty()) {
                        actions.add(ClawAction(
                            actionName = ActionType.SCREEN_INPUT.name,
                            params = JsonObject(mapOf("text" to JsonPrimitive(content))),
                            description = "搜索「$content」"
                        ))
                    }
                    return ExecuteResult(
                        actions = actions,
                        confidence = CONFIDENCE_COMPOSITE_VIEW,
                        description = "查看「$target」",
                        rawInput = input
                    )
                }
            }
        }

        // =============================================================
        // 16. 播放/听操作
        // 支持：播放XXX / 放XXX / 听XXX
        // =============================================================
        val playActionPattern = Regex(
            """(?:播放|放|听|看)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val playActionMatch = playActionPattern.find(input)
        if (playActionMatch != null) {
            val content = playActionMatch.groupValues[1].trim()
            if (content.length > 1 && content.length < 40) {
                val musicKeywords = listOf("歌", "音乐", "歌曲", "周杰伦", "网易云", "QQ音乐", "乐")
                val videoKeywords = listOf("视频", "电影", "电视剧", "动漫", "综艺", "剧", "片")
                val isMusic = musicKeywords.any { content.contains(it) }
                val isVideo = videoKeywords.any { content.contains(it) }
                val appName = when {
                    isMusic -> "网易云音乐"
                    isVideo -> "腾讯视频"
                    else -> "网易云音乐"
                }
                val packageName = getPackageName(appName)
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive(packageName),
                            "name" to JsonPrimitive(appName)
                        )),
                        description = "打开 $appName"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(2000))),
                        description = "等待应用加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_INPUT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive(content))),
                        description = "搜索「$content」"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_MEDIA_PLAY,
                    description = "播放「$content」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 17. 蓝牙/连接操作
        // 支持：连接XXX / 配对XXX / 断开XXX
        // =============================================================
        val connectPattern = Regex(
            """(?:连接|配对|断开)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val connectMatch = connectPattern.find(input)
        if (connectMatch != null) {
            val device = connectMatch.groupValues[1].trim()
            if (device.length > 1 && device.length < 30) {
                val isConnect = input.contains("连接") || input.contains("配对")
                val actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_OPEN.name,
                        params = JsonObject(mapOf(
                            "packageName" to JsonPrimitive("com.android.settings"),
                            "name" to JsonPrimitive("设置")
                        )),
                        description = "打开设置"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_WAIT.name,
                        params = JsonObject(mapOf("ms" to JsonPrimitive(1500))),
                        description = "等待设置加载"
                    ),
                    ClawAction(
                        actionName = ActionType.SCREEN_CLICK_TEXT.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("蓝牙"))),
                        description = "点击蓝牙设置"
                    )
                )
                return ExecuteResult(
                    actions = actions,
                    confidence = CONFIDENCE_SYSTEM_OPERATION,
                    description = if (isConnect) "连接「$device」" else "断开「$device」",
                    rawInput = input
                )
            }
        }

        // =============================================================
        // 18. 刷新操作
        // 支持：刷新 / 刷新页面 / 重新加载
        // =============================================================
        if (Regex("""(?:刷新|刷新页面|重新加载|刷新一下|刷新当前|刷新当前页面)""", RegexOption.IGNORE_CASE).matches(input.trim()) ||
            Regex("""^(?:刷新|刷新页面|重新加载)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在执行刷新操作..."))),
                        description = "刷新当前页面"
                    )
                ),
                confidence = CONFIDENCE_REFRESH,
                description = "刷新页面",
                rawInput = input
            )
        }

        // =============================================================
        // 19. 最小化操作
        // 支持：最小化 / 最小化当前 / 收起
        // =============================================================
        if (Regex("""(?:最小化|最小化当前|收起|收起当前|最小化窗口|最小化应用)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.SCREEN_KEY.name,
                        params = JsonObject(mapOf("key" to JsonPrimitive("HOME"))),
                        description = "最小化当前应用"
                    )
                ),
                confidence = CONFIDENCE_MINIMIZE,
                description = "最小化当前应用",
                rawInput = input
            )
        }

        // =============================================================
        // 20. 分享操作
        // 支持：分享 / 分享当前 / 分享当前页面
        // =============================================================
        if (Regex("""(?:分享|分享当前|分享当前页面|分享这个|分享一下)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在准备分享..."))),
                        description = "分享当前内容"
                    )
                ),
                confidence = CONFIDENCE_SOCIAL_ACTION,
                description = "分享当前内容",
                rawInput = input
            )
        }

        // =============================================================
        // 21. 收藏操作
        // 支持：收藏 / 收藏当前 / 收藏这个
        // =============================================================
        if (Regex("""(?:收藏|收藏当前|收藏这个|收藏一下|收藏页面|收藏当前页面)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在收藏当前内容..."))),
                        description = "收藏当前内容"
                    )
                ),
                confidence = CONFIDENCE_SOCIAL_ACTION,
                description = "收藏当前内容",
                rawInput = input
            )
        }

        // =============================================================
        // 22. 点赞操作
        // 支持：点赞 / 点个赞 / 给个赞
        // =============================================================
        if (Regex("""(?:点赞|点个赞|给个赞|点个赞吧|点赞一下|点赞当前)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在点赞..."))),
                        description = "点赞当前内容"
                    )
                ),
                confidence = CONFIDENCE_SOCIAL_ACTION,
                description = "点赞",
                rawInput = input
            )
        }

        // =============================================================
        // 23. 评论操作
        // 支持：评论 / 写评论 / 发表评论
        // =============================================================
        if (Regex("""(?:评论|写评论|发表评论|写个评论|评论一下|添加评论)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在准备评论界面..."))),
                        description = "打开评论"
                    )
                ),
                confidence = CONFIDENCE_SOCIAL_ACTION,
                description = "评论",
                rawInput = input
            )
        }

        // =============================================================
        // 24. 转发操作
        // 支持：转发 / 转发当前
        // =============================================================
        if (Regex("""(?:转发|转发当前|转发一下|转发这个)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在准备转发..."))),
                        description = "转发当前内容"
                    )
                ),
                confidence = CONFIDENCE_SOCIAL_ACTION,
                description = "转发",
                rawInput = input
            )
        }

        // =============================================================
        // 25. 下载操作
        // 支持：下载 / 下载当前 / 下载这个
        // =============================================================
        if (Regex("""(?:下载|下载当前|下载这个|下载一下|下载页面|下载此内容)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在准备下载..."))),
                        description = "下载当前内容"
                    )
                ),
                confidence = CONFIDENCE_DOWNLOAD,
                description = "下载",
                rawInput = input
            )
        }

        // =============================================================
        // 26. 全屏操作
        // 支持：全屏 / 全屏模式 / 进入全屏 / 退出全屏
        // =============================================================
        if (Regex("""(?:全屏|全屏模式|进入全屏|全屏显示|全屏展示|全屏状态)""", RegexOption.IGNORE_CASE).containsMatchIn(input) &&
            !Regex("""(?:退出|关闭|取消)\s*全屏""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.ANSWER.name,
                        params = JsonObject(mapOf("text" to JsonPrimitive("正在进入全屏模式..."))),
                        description = "进入全屏模式"
                    )
                ),
                confidence = CONFIDENCE_FULLSCREEN,
                description = "进入全屏",
                rawInput = input
            )
        }

        // =============================================================
        // 27. 退出操作
        // 支持：退出 / 退出应用 / 关闭
        // =============================================================
        if (Regex("""(?:退出|退出应用|退出当前|关闭应用|关闭当前)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            return ExecuteResult(
                actions = listOf(
                    ClawAction(
                        actionName = ActionType.APP_CLOSE.name,
                        params = JsonObject(emptyMap()),
                        description = "退出当前应用"
                    )
                ),
                confidence = CONFIDENCE_EXIT,
                description = "退出应用",
                rawInput = input
            )
        }

        return null
    }

    // =========================================================================
    //  内部：各命令组的匹配实现
    // =========================================================================

    /**
     * 匹配打开应用命令。
     *
     * 支持的模式：
     * - "打开XXX" / "启动XXX" / "开启XXX" / "运行XXX"
     * - "进入XXX" / "到XXX" / "去XXX"
     * - "打开 com.xxx.xxx"（直接使用包名）
     */
    private fun matchAppOpen(input: String): ExecuteResult? {
        val pattern = Regex(
            """(?:打开|启动|开启|运行|进入|到|去|启动应用|打开应用)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(input)
        if (match != null) {
            val appName = match.groupValues[1].trim()
            if (appName.isBlank()) return null

            val packageName = getPackageName(appName)
            val confidence = if (APP_PACKAGE_MAP.containsKey(appName)) {
                CONFIDENCE_APP_OPEN_EXACT
            } else if (APP_PACKAGE_MAP.any { (name, _) -> appName.contains(name) || name.contains(appName) }) {
                CONFIDENCE_APP_OPEN_FUZZY
            } else {
                CONFIDENCE_APP_OPEN_GENERIC
            }

            val action = ClawAction(
                actionName = ActionType.APP_OPEN.name,
                params = JsonObject(mapOf(
                    "packageName" to JsonPrimitive(packageName),
                    "name" to JsonPrimitive(appName)
                )),
                description = "打开应用「$appName」"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = confidence,
                description = "打开「$appName」",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配截屏命令。
     *
     * 支持的模式：
     * - "截图" / "截屏" / "screenshot"
     * - "截个图" / "截一下屏" / "帮我截图"
     * - "屏幕截图" / "屏幕快照"
     */
    private fun matchScreenshot(input: String): ExecuteResult? {
        val pattern = Regex(
            """(?:截图|截屏|screenshot|截个图|截一下屏|帮我截图|帮我截屏|屏幕截图|屏幕快照|抓屏|抓图)""",
            RegexOption.IGNORE_CASE
        )
        if (pattern.containsMatchIn(input)) {
            // 排除包含"搜索"等干扰词的场景
            if (Regex("""搜索|搜|查找""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
                return null
            }
            val action = ClawAction(
                actionName = ActionType.SCREEN_SCREENSHOT.name,
                params = JsonObject(emptyMap()),
                description = "截取当前屏幕"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_SCREENSHOT,
                description = "截屏",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配按键操作命令。
     *
     * 支持的所有按键类型：
     * - BACK: 返回/回去/后退/back
     * - HOME: 回桌面/回到桌面/home/主屏幕/主屏
     * - RECENTS: 最近任务/多任务/切换应用
     * - LOCK_SCREEN: 锁屏/锁定
     * - NOTIFICATION_PANEL: 通知栏/下拉通知/通知中心
     * - VOLUME_UP: 音量加/音量大/大声点/增大音量
     * - VOLUME_DOWN: 音量减/音量小/小声点/减小音量
     * - POWER: 电源键/关机键/息屏
     * - SPLIT_SCREEN: 分屏/分屏模式
     * - QUICK_SETTINGS: 快速设置/快捷设置
     */
    private fun matchKeyAction(input: String): ExecuteResult? {
        // 检查是否包含"搜索"意图，避免误匹配
        val hasSearchIntent = Regex("""搜索|搜|查找|找""", RegexOption.IGNORE_CASE).containsMatchIn(input)

        // BACK
        if (Regex("""^(?:返回|回去|后退|back|回退|上一级)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("BACK"))),
                description = "返回上一级"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_BACK,
                description = "返回",
                rawInput = input
            )
        }

        // HOME
        if (Regex("""^(?:回桌面|回到桌面|home|主屏幕|主屏|返回桌面|显示桌面)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("HOME"))),
                description = "回到桌面"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_HOME,
                description = "回桌面",
                rawInput = input
            )
        }

        // RECENTS
        if (Regex("""^(?:最近任务|多任务|切换应用|任务列表|应用切换)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("RECENTS"))),
                description = "打开最近任务"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_RECENTS,
                description = "最近任务",
                rawInput = input
            )
        }

        // LOCK_SCREEN
        if (Regex("""^(?:锁屏|锁定|锁定屏幕|锁住屏幕)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("LOCK_SCREEN"))),
                description = "锁定屏幕"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_LOCK,
                description = "锁屏",
                rawInput = input
            )
        }

        // NOTIFICATION_PANEL
        if (Regex("""^(?:通知栏|下拉通知|通知中心|下拉状态栏|查看通知)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("NOTIFICATION_PANEL"))),
                description = "下拉通知栏"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_NOTIFICATION,
                description = "通知栏",
                rawInput = input
            )
        }

        // VOLUME_UP
        if (!hasSearchIntent && Regex(
            """(?:音量加|音量大|大声点|增大音量|调高音量|提高音量|加大音量|音量上升|大点声)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(input)) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("VOLUME_UP"))),
                description = "增大音量"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_VOLUME,
                description = "音量加",
                rawInput = input
            )
        }

        // VOLUME_DOWN
        if (!hasSearchIntent && Regex(
            """(?:音量减|音量小|小声点|减小音量|调低音量|降低音量|减少音量|小点声|音量下降)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(input)) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("VOLUME_DOWN"))),
                description = "减小音量"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_VOLUME,
                description = "音量减",
                rawInput = input
            )
        }

        // POWER
        if (Regex("""^(?:电源键|关机键|息屏|关闭屏幕|锁屏键)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("POWER"))),
                description = "按下电源键"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_POWER,
                description = "电源键",
                rawInput = input
            )
        }

        // SPLIT_SCREEN
        if (Regex("""^(?:分屏|分屏模式|开启分屏|分屏显示)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("SPLIT_SCREEN"))),
                description = "开启分屏"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_SPLIT,
                description = "分屏",
                rawInput = input
            )
        }

        // QUICK_SETTINGS
        if (Regex("""^(?:快速设置|快捷设置|快捷面板|快速面板)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_KEY.name,
                params = JsonObject(mapOf("key" to JsonPrimitive("QUICK_SETTINGS"))),
                description = "打开快速设置"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_KEY_QUICK_SETTINGS,
                description = "快速设置",
                rawInput = input
            )
        }

        return null
    }

    /**
     * 匹配系统信息查询命令。
     *
     * 支持的类型：
     * - MEMORY: 查看内存/内存使用/内存情况/内存占用
     * - BATTERY: 查看电池/电量/电池状态/剩余电量
     * - CPU: 查看CPU/CPU使用/CPU使用率/CPU情况
     * - STORAGE: 查看存储/存储空间/磁盘/磁盘空间
     */
    private fun matchSystemInfo(input: String): ExecuteResult? {
        // MEMORY
        if (Regex("""(?:查看|看看|检查|查询|显示)\s*(?:内存|运存|RAM|内存使用|内存情况|内存占用)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) ||
            Regex("""(?:内存|运存)\s*(?:使用|情况|占用|多大|多少)""", RegexOption.IGNORE_CASE).containsMatchIn(input)
        ) {
            val action = ClawAction(
                actionName = ActionType.SYSTEM_GET_INFO.name,
                params = JsonObject(mapOf("info" to JsonPrimitive("MEMORY"))),
                description = "查看内存使用情况"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_INFO_MEMORY,
                description = "查看内存",
                rawInput = input
            )
        }

        // BATTERY
        if (Regex("""(?:查看|看看|检查|查询|显示)\s*(?:电池|电量|电池状态|剩余电量|电池健康)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) ||
            Regex("""(?:电池|电量|电)\s*(?:状态|多少|剩多少|健康|情况)""", RegexOption.IGNORE_CASE).containsMatchIn(input) ||
            Regex("""^(?:电量|还有多少电|电池状态)$""", RegexOption.IGNORE_CASE).containsMatchIn(input.trim())
        ) {
            val action = ClawAction(
                actionName = ActionType.SYSTEM_GET_INFO.name,
                params = JsonObject(mapOf("info" to JsonPrimitive("BATTERY"))),
                description = "查看电池状态"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_INFO_BATTERY,
                description = "查看电池",
                rawInput = input
            )
        }

        // CPU
        if (Regex("""(?:查看|看看|检查|查询|显示)\s*(?:CPU|cpu|处理器|CPU使用|CPU使用率|CPU情况|处理器使用)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) ||
            Regex("""(?:CPU|cpu|处理器)\s*(?:使用|情况|占用|多大|负载|频率)""", RegexOption.IGNORE_CASE).containsMatchIn(input)
        ) {
            val action = ClawAction(
                actionName = ActionType.SYSTEM_GET_INFO.name,
                params = JsonObject(mapOf("info" to JsonPrimitive("CPU"))),
                description = "查看 CPU 使用情况"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_INFO_CPU,
                description = "查看CPU",
                rawInput = input
            )
        }

        // STORAGE
        if (Regex("""(?:查看|看看|检查|查询|显示)\s*(?:存储|存储空间|磁盘|硬盘|空间|储存)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) ||
            Regex("""(?:存储|磁盘|空间|储存)\s*(?:使用|情况|占用|多大|多少|剩余)""", RegexOption.IGNORE_CASE).containsMatchIn(input)
        ) {
            val action = ClawAction(
                actionName = ActionType.SYSTEM_GET_INFO.name,
                params = JsonObject(mapOf("info" to JsonPrimitive("STORAGE"))),
                description = "查看存储空间使用情况"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_INFO_STORAGE,
                description = "查看存储",
                rawInput = input
            )
        }

        return null
    }

    /**
     * 匹配清理缓存命令。
     *
     * 支持的模式：
     * - "清理缓存" / "清缓存" / "清除缓存"
     * - "清理垃圾" / "清垃圾" / "清除垃圾"
     * - "释放空间" / "清理空间"
     */
    private fun matchClearCache(input: String): ExecuteResult? {
        if (Regex("""(?:清理|清除|清|删除)\s*(?:缓存|垃圾|临时文件|临时数据)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) ||
            Regex("""(?:释放|清理|腾出)\s*(?:空间|存储空间)""", RegexOption.IGNORE_CASE).containsMatchIn(input)
        ) {
            val action = ClawAction(
                actionName = ActionType.SYSTEM_CLEAR_CACHE.name,
                params = JsonObject(emptyMap()),
                description = "清理系统缓存"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_CLEAR_CACHE,
                description = "清理缓存",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配媒体控制命令。
     *
     * 支持的操作：
     * - PLAY_PAUSE: 播放/暂停/播放音乐/暂停音乐/继续播放
     * - NEXT: 下一首/切歌/下一曲/下一首歌曲
     * - PREVIOUS: 上一首/上一曲/上一首歌曲/前一首
     */
    private fun matchMediaControl(input: String): ExecuteResult? {
        // PLAY_PAUSE
        if (Regex("""^(?:播放|暂停|继续播放|播放音乐|暂停音乐|播放暂停|开始播放|停止播放)$""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.MEDIA_CONTROL.name,
                params = JsonObject(mapOf("mediaAction" to JsonPrimitive("play_pause"))),
                description = "播放/暂停媒体"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_MEDIA_PLAY_PAUSE,
                description = "播放/暂停",
                rawInput = input
            )
        }

        // NEXT
        if (Regex("""^(?:下一首|切歌|下一曲|下一首歌曲|下首歌|下一首歌|换一首|下一首曲目)$""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.MEDIA_CONTROL.name,
                params = JsonObject(mapOf("mediaAction" to JsonPrimitive("next"))),
                description = "切换到下一首"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_MEDIA_NEXT,
                description = "下一首",
                rawInput = input
            )
        }

        // PREVIOUS
        if (Regex("""^(?:上一首|上一曲|上一首歌曲|上一首歌|前一首|上一首曲目|回上一首)$""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input.trim())) {
            val action = ClawAction(
                actionName = ActionType.MEDIA_CONTROL.name,
                params = JsonObject(mapOf("mediaAction" to JsonPrimitive("previous"))),
                description = "切换到上一首"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_MEDIA_PREVIOUS,
                description = "上一首",
                rawInput = input
            )
        }

        return null
    }

    /**
     * 匹配剪贴板操作命令。
     *
     * 支持的操作：
     * - CLIPBOARD_COPY: 复制/拷贝/复制到剪贴板
     * - CLIPBOARD_PASTE: 粘贴/粘贴剪贴板内容
     */
    private fun matchClipboard(input: String): ExecuteResult? {
        // COPY
        val copyPattern = Regex(
            """(?:复制|拷贝)\s*(?:到剪贴板)?\s*(.*)""",
            RegexOption.IGNORE_CASE
        )
        val copyMatch = copyPattern.find(input)
        if (copyMatch != null && !input.contains("粘贴")) {
            val textToCopy = copyMatch.groupValues[1].trim()
            val params = if (textToCopy.isNotBlank()) {
                JsonObject(mapOf("text" to JsonPrimitive(textToCopy)))
            } else {
                JsonObject(emptyMap())
            }
            val action = ClawAction(
                actionName = ActionType.CLIPBOARD_COPY.name,
                params = params,
                description = "复制到剪贴板"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_CLIPBOARD_COPY,
                description = if (textToCopy.isNotBlank()) "复制「$textToCopy」" else "复制",
                rawInput = input
            )
        }

        // PASTE
        if (Regex("""(?:粘贴|粘贴剪贴板|粘贴内容|贴上去)""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            val action = ClawAction(
                actionName = ActionType.CLIPBOARD_PASTE.name,
                params = JsonObject(emptyMap()),
                description = "粘贴剪贴板内容"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_CLIPBOARD_PASTE,
                description = "粘贴",
                rawInput = input
            )
        }

        return null
    }

    /**
     * 匹配点击文本命令。
     *
     * 支持的模式：
     * - "点击XXX" / "点XXX" / "按XXX"
     * - "单击XXX" / "双击XXX"
     * - "打开XXX"（当无法匹配到应用时，作为点击文本处理）
     */
    private fun matchClickText(input: String): ExecuteResult? {
        val pattern = Regex(
            """(?:点击|点|按|单击|双击|按下|轻触|触摸|触击)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(input)
        if (match != null) {
            val targetText = match.groupValues[1].trim()
            if (targetText.isBlank()) return null

            val action = ClawAction(
                actionName = ActionType.SCREEN_CLICK_TEXT.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(targetText))),
                description = "点击屏幕上的「$targetText」"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_CLICK_TEXT,
                description = "点击「$targetText」",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配查找并点击命令。
     *
     * 支持的模式：
     * - "查找XXX并点击" / "找到XXX并点"
     * - "搜索XXX并点击" / "定位XXX并点击"
     */
    private fun matchFindAndClick(input: String): ExecuteResult? {
        val pattern = Regex(
            """(?:查找|找到|搜索|定位|寻找)\s*(.+?)\s*(?:并|然后|再|接着)\s*(?:点击|点|按|打开)""",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(input)
        if (match != null) {
            val target = match.groupValues[1].trim()
            if (target.isBlank()) return null

            val action = ClawAction(
                actionName = ActionType.SCREEN_FIND_AND_CLICK.name,
                params = JsonObject(mapOf("text" to JsonPrimitive(target))),
                description = "查找并点击「$target」"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_FIND_AND_CLICK,
                description = "查找并点击「$target」",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配等待命令。
     *
     * 支持的模式：
     * - "等待X秒" / "等X秒" / "稍等X秒"
     * - "等待" / "稍等"（默认等待 3 秒）
     */
    private fun matchWait(input: String): ExecuteResult? {
        val pattern = Regex(
            """(?:等待|等|稍等|稍候)\s*(\d+)?\s*(?:秒|s|秒钟)?\s*(?:钟)?$""",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(input)
        if (match != null && input.trim().length < 15) {
            val seconds = match.groupValues[1].toIntOrNull() ?: 3
            val ms = seconds * 1000

            val action = ClawAction(
                actionName = ActionType.SCREEN_WAIT.name,
                params = JsonObject(mapOf("ms" to JsonPrimitive(ms))),
                description = "等待 ${seconds} 秒"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_WAIT,
                description = "等待 ${seconds} 秒",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配关闭应用命令。
     *
     * 支持的模式：
     * - "关闭XXX" / "退出XXX" / "结束XXX"
     * - "关掉XXX" / "关闭应用XXX"
     */
    private fun matchAppClose(input: String): ExecuteResult? {
        val pattern = Regex(
            """(?:关闭|退出|结束|关掉|退出应用|关闭应用|停止)\s*(.+)""",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(input)
        if (match != null) {
            val appName = match.groupValues[1].trim()
            if (appName.isBlank()) return null

            val packageName = getPackageName(appName)
            val action = ClawAction(
                actionName = ActionType.APP_CLOSE.name,
                params = JsonObject(mapOf(
                    "packageName" to JsonPrimitive(packageName),
                    "name" to JsonPrimitive(appName)
                )),
                description = "关闭应用「$appName」"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_APP_CLOSE,
                description = "关闭「$appName」",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配滑动命令。
     *
     * 支持的模式：
     * - "向上滑动" / "向下滑动" / "向左滑动" / "向右滑动"
     * - "上滑" / "下滑" / "左滑" / "右滑"
     * - "滑动到顶部" / "滑到底部"
     * - "翻页" / "下一页" / "上一页"
     */
    private fun matchSwipe(input: String): ExecuteResult? {
        val direction = when {
            Regex("""(?:向上滑动|上滑|向上滑|往上滑|滑动到顶部|滚到顶部|回到顶部)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) -> "UP"
            Regex("""(?:向下滑动|下滑|向下滑|往下滑|滑到底部|滚到底部|滑动到底部)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) -> "DOWN"
            Regex("""(?:向左滑动|左滑|向左滑|往左滑)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) -> "LEFT"
            Regex("""(?:向右滑动|右滑|向右滑|往右滑)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) -> "RIGHT"
            Regex("""(?:翻页|下一页|下翻|往下翻)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) -> "UP"
            Regex("""(?:上一页|上翻|往上翻)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(input) -> "DOWN"
            else -> null
        }

        if (direction != null) {
            val action = ClawAction(
                actionName = ActionType.SCREEN_SWIPE.name,
                params = JsonObject(mapOf("direction" to JsonPrimitive(direction))),
                description = "向${direction}滑动"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_SWIPE,
                description = "向${direction}滑动",
                rawInput = input
            )
        }
        return null
    }

    /**
     * 匹配系统设置命令。
     *
     * 支持的操作：
     * - 设置音量（"设置音量为50" / "音量调到70"）
     * - 设置亮度（"设置亮度50" / "亮度调到70"）
     */
    private fun matchSystemSettings(input: String): ExecuteResult? {
        // 设置音量
        val volumePattern = Regex(
            """(?:设置|调整|调节|把|将)\s*(?:音量|声音)\s*(?:设为|调到|调为|设置成|调整到|调到|改成|改为)?\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
        val volumeMatch = volumePattern.find(input)
        if (volumeMatch != null) {
            val level = volumeMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 50
            val action = ClawAction(
                actionName = ActionType.SYSTEM_SET_VOLUME.name,
                params = JsonObject(mapOf("volume" to JsonPrimitive(level))),
                description = "设置音量为 $level"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_SET_VOLUME,
                description = "设置音量 $level",
                rawInput = input
            )
        }

        // 设置亮度
        val brightnessPattern = Regex(
            """(?:设置|调整|调节|把|将)\s*(?:亮度|屏幕亮度|显示屏亮度)\s*(?:设为|调到|调为|设置成|调整到|调到|改成|改为)?\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
        val brightnessMatch = brightnessPattern.find(input)
        if (brightnessMatch != null) {
            val level = brightnessMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: 128
            val action = ClawAction(
                actionName = ActionType.SYSTEM_SET_BRIGHTNESS.name,
                params = JsonObject(mapOf("brightness" to JsonPrimitive(level))),
                description = "设置亮度为 $level"
            )
            return ExecuteResult(
                actions = listOf(action),
                confidence = CONFIDENCE_SET_BRIGHTNESS,
                description = "设置亮度 $level",
                rawInput = input
            )
        }

        return null
    }

    // =========================================================================
    //  内部：各命令组的执行实现
    // =========================================================================

    /**
     * 执行打开应用动作。
     *
     * 构造 [ClawActionResult] 表示需要打开指定的应用。
     * 实际打开操作由 [ClawController] 或 [ScreenController] 完成。
     *
     * @param action 包含包名和应用名的动作
     * @return 执行结果
     */
    private fun executeAppOpen(action: ClawAction): ClawActionResult {
        val packageName = action.packageName ?: action.params["name"]?.jsonPrimitive?.content
        val appName = action.name ?: packageName ?: "未知应用"
        if (packageName.isNullOrBlank()) {
            autoExecutions.incrementAndGet()
            return ClawActionResult.success(
                message = "准备打开应用「$appName」",
                data = appName
            )
        }
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "准备打开应用「$appName」（$packageName）",
            data = packageName
        )
    }

    /**
     * 执行截屏动作。
     *
     * @param action 截屏动作（参数为空）
     * @return 执行结果
     */
    private fun executeScreenshot(action: ClawAction): ClawActionResult {
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "正在截取当前屏幕",
            data = "screenshot_pending"
        )
    }

    /**
     * 执行按键动作。
     *
     * @param action 包含按键名称的动作
     * @return 执行结果
     */
    private fun executeKeyAction(action: ClawAction): ClawActionResult {
        val keyName = action.keyName ?: "UNKNOWN"
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "模拟按键：$keyName",
            data = keyName
        )
    }

    /**
     * 执行输入文本动作。
     *
     * @param action 包含输入文本的动作
     * @return 执行结果
     */
    private fun executeInput(action: ClawAction): ClawActionResult {
        val text = action.text ?: ""
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "准备输入文本：${text.take(50)}${if (text.length > 50) "..." else ""}",
            data = text
        )
    }

    /**
     * 执行查询系统信息动作。
     *
     * @param action 包含信息类型（MEMORY/BATTERY/CPU/STORAGE）的动作
     * @return 执行结果
     */
    private fun executeSystemInfo(action: ClawAction): ClawActionResult {
        val infoType = action.infoType ?: "UNKNOWN"
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "查询系统信息：$infoType",
            data = infoType
        )
    }

    /**
     * 执行清理缓存动作。
     *
     * @param action 清理缓存动作（参数为空）
     * @return 执行结果
     */
    private fun executeClearCache(action: ClawAction): ClawActionResult {
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "正在清理系统缓存",
            data = "cache_clear_pending"
        )
    }

    /**
     * 执行媒体控制动作。
     *
     * @param action 包含媒体操作（play_pause/next/previous）的动作
     * @return 执行结果
     */
    private fun executeMediaControl(action: ClawAction): ClawActionResult {
        val mediaAction = action.mediaAction ?: "play_pause"
        val actionDesc = when (mediaAction) {
            "play_pause" -> "播放/暂停"
            "next" -> "下一首"
            "previous" -> "上一首"
            else -> mediaAction
        }
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "媒体控制：$actionDesc",
            data = mediaAction
        )
    }

    /**
     * 执行复制到剪贴板动作。
     *
     * @param action 包含要复制文本的动作
     * @return 执行结果
     */
    private fun executeClipboardCopy(action: ClawAction): ClawActionResult {
        val text = action.text ?: ""
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "复制到剪贴板：${text.take(50)}${if (text.length > 50) "..." else ""}",
            data = text
        )
    }

    /**
     * 执行点击文本动作。
     *
     * @param action 包含要点击的文本的动作
     * @return 执行结果
     */
    private fun executeClickText(action: ClawAction): ClawActionResult {
        val text = action.text ?: ""
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "准备点击「$text」",
            data = text
        )
    }

    /**
     * 执行查找并点击动作。
     *
     * @param action 包含要查找的文本的动作
     * @return 执行结果
     */
    private fun executeFindAndClick(action: ClawAction): ClawActionResult {
        val text = action.text ?: ""
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "查找并点击「$text」",
            data = text
        )
    }

    /**
     * 执行等待动作。
     *
     * @param action 包含等待毫秒数的动作
     * @return 执行结果
     */
    private fun executeWait(action: ClawAction): ClawActionResult {
        val ms = action.ms ?: 3000L
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "等待 ${ms}ms",
            data = ms.toString()
        )
    }

    /**
     * 执行关闭应用动作。
     *
     * @param action 包含包名和应用名的动作
     * @return 执行结果
     */
    private fun executeAppClose(action: ClawAction): ClawActionResult {
        val packageName = action.packageName ?: "unknown"
        val appName = action.name ?: packageName
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "准备关闭应用「$appName」",
            data = packageName
        )
    }

    /**
     * 执行滑动动作。
     *
     * @param action 包含滑动方向的动作
     * @return 执行结果
     */
    private fun executeSwipe(action: ClawAction): ClawActionResult {
        val direction = action.swipeDirectionName ?: "UP"
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "向${direction}滑动",
            data = direction
        )
    }

    /**
     * 执行设置音量动作。
     *
     * @param action 包含音量级别的动作
     * @return 执行结果
     */
    private fun executeSetVolume(action: ClawAction): ClawActionResult {
        val volume = action.volume ?: 50
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "设置音量为 $volume",
            data = volume.toString()
        )
    }

    /**
     * 执行设置亮度动作。
     *
     * @param action 包含亮度级别的动作
     * @return 执行结果
     */
    private fun executeSetBrightness(action: ClawAction): ClawActionResult {
        val brightness = action.brightness ?: 128
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "设置亮度为 $brightness",
            data = brightness.toString()
        )
    }

    /**
     * 执行粘贴剪贴板动作。
     *
     * @param action 粘贴动作（参数为空）
     * @return 执行结果
     */
    private fun executeClipboardPaste(action: ClawAction): ClawActionResult {
        autoExecutions.incrementAndGet()
        return ClawActionResult.success(
            message = "准备粘贴剪贴板内容",
            data = "paste_pending"
        )
    }

    // =========================================================================
    //  内部：记录匹配
    // =========================================================================

    /**
     * 记录一次匹配（更新统计信息）。
     *
     * @param input     用户输入
     * @param startNano 匹配开始时的纳秒时间戳
     */
    private fun recordMatch(input: String, startNano: Long) {
        val elapsed = System.nanoTime() - startNano
        totalMatches.incrementAndGet()
        totalMatchTime.addAndGet(elapsed)
        matchHistory.computeIfAbsent(input) { AtomicLong(0) }.incrementAndGet()
    }

    // =========================================================================
    //  Companion 对象：静态常量与配置
    // =========================================================================

    companion object {
        // =====================================================================
        //  置信度阈值常量
        // =====================================================================

        /**
         * 自动执行的最低置信度阈值。
         * 匹配结果的置信度 >= 此值时，可无需用户确认直接执行。
         */
        const val MIN_CONFIDENCE_AUTO = 0.85

        /**
         * 建议执行的最低置信度阈值。
         * 匹配结果的置信度 >= 此值时，可向用户建议执行但需确认。
         * 置信度低于此值的匹配结果将被忽略或需要用户明确选择。
         */
        const val MIN_CONFIDENCE_SUGGEST = 0.60

        // =====================================================================
        //  各命令组的置信度分数
        // =====================================================================

        /** 自定义命令匹配置信度。 */
        const val CONFIDENCE_CUSTOM_COMMAND = 0.98

        /** 打开应用 - 精确匹配（应用名在映射表中）。 */
        const val CONFIDENCE_APP_OPEN_EXACT = 0.95

        /** 打开应用 - 模糊匹配（应用名部分匹配）。 */
        const val CONFIDENCE_APP_OPEN_FUZZY = 0.80

        /** 打开应用 - 通用匹配（未识别的应用名，直接使用包名）。 */
        const val CONFIDENCE_APP_OPEN_GENERIC = 0.70

        /** 截屏匹配置信度。 */
        const val CONFIDENCE_SCREENSHOT = 0.98

        /** 返回键匹配置信度。 */
        const val CONFIDENCE_KEY_BACK = 0.97

        /** Home 键匹配置信度。 */
        const val CONFIDENCE_KEY_HOME = 0.97

        /** 最近任务键匹配置信度。 */
        const val CONFIDENCE_KEY_RECENTS = 0.97

        /** 锁屏键匹配置信度。 */
        const val CONFIDENCE_KEY_LOCK = 0.97

        /** 通知栏匹配置信度。 */
        const val CONFIDENCE_KEY_NOTIFICATION = 0.96

        /** 音量键匹配置信度。 */
        const val CONFIDENCE_KEY_VOLUME = 0.95

        /** 电源键匹配置信度。 */
        const val CONFIDENCE_KEY_POWER = 0.95

        /** 分屏键匹配置信度。 */
        const val CONFIDENCE_KEY_SPLIT = 0.95

        /** 快速设置匹配置信度。 */
        const val CONFIDENCE_KEY_QUICK_SETTINGS = 0.95

        /** 查询内存信息置信度。 */
        const val CONFIDENCE_INFO_MEMORY = 0.95

        /** 查询电池信息置信度。 */
        const val CONFIDENCE_INFO_BATTERY = 0.95

        /** 查询 CPU 信息置信度。 */
        const val CONFIDENCE_INFO_CPU = 0.95

        /** 查询存储信息置信度。 */
        const val CONFIDENCE_INFO_STORAGE = 0.95

        /** 清理缓存置信度。 */
        const val CONFIDENCE_CLEAR_CACHE = 0.96

        /** 播放/暂停媒体置信度。 */
        const val CONFIDENCE_MEDIA_PLAY_PAUSE = 0.97

        /** 下一首置信度。 */
        const val CONFIDENCE_MEDIA_NEXT = 0.97

        /** 上一首置信度。 */
        const val CONFIDENCE_MEDIA_PREVIOUS = 0.97

        /** 复制到剪贴板置信度。 */
        const val CONFIDENCE_CLIPBOARD_COPY = 0.93

        /** 粘贴剪贴板置信度。 */
        const val CONFIDENCE_CLIPBOARD_PASTE = 0.93

        /** 点击文本置信度。 */
        const val CONFIDENCE_CLICK_TEXT = 0.85

        /** 查找并点击置信度。 */
        const val CONFIDENCE_FIND_AND_CLICK = 0.82

        /** 等待置信度。 */
        const val CONFIDENCE_WAIT = 0.90

        /** 关闭应用置信度。 */
        const val CONFIDENCE_APP_CLOSE = 0.92

        /** 滑动置信度。 */
        const val CONFIDENCE_SWIPE = 0.92

        /** 设置音量置信度。 */
        const val CONFIDENCE_SET_VOLUME = 0.88

        /** 设置亮度置信度。 */
        const val CONFIDENCE_SET_BRIGHTNESS = 0.88

        /** 复合命令 - 在XXX搜索YYY 置信度。 */
        const val CONFIDENCE_COMPOSITE_SEARCH = 0.85

        /** 复合命令 - 给XXX发YYY 置信度。 */
        const val CONFIDENCE_COMPOSITE_SEND = 0.78

        /** 复合命令 - 打开XXX然后YYY 置信度。 */
        const val CONFIDENCE_COMPOSITE_OPEN_THEN = 0.80

        /** 复合命令 - 去XXX看YYY 置信度。 */
        const val CONFIDENCE_COMPOSITE_VIEW = 0.75

        /** 系统操作 - WiFi/蓝牙/手电筒等 置信度。 */
        const val CONFIDENCE_SYSTEM_OPERATION = 0.92

        /** 导航操作 置信度。 */
        const val CONFIDENCE_NAVIGATE = 0.90

        /** 媒体播放操作 置信度。 */
        const val CONFIDENCE_MEDIA_PLAY = 0.90

        /** 社交操作（点赞/评论/收藏/分享等）置信度。 */
        const val CONFIDENCE_SOCIAL_ACTION = 0.85

        /** 最小化操作 置信度。 */
        const val CONFIDENCE_MINIMIZE = 0.90

        /** 刷新操作 置信度。 */
        const val CONFIDENCE_REFRESH = 0.92

        /** 下载操作 置信度。 */
        const val CONFIDENCE_DOWNLOAD = 0.85

        /** 全屏操作 置信度。 */
        const val CONFIDENCE_FULLSCREEN = 0.90

        /** 退出操作 置信度。 */
        const val CONFIDENCE_EXIT = 0.92

        // =====================================================================
        //  应用名到包名的映射表
        // =====================================================================

        /**
         * 中文应用名到 Android 包名的映射表。
         *
         * 涵盖中国大陆地区最常用的 50+ 款应用，支持用户通过中文名称
         * 直接打开应用。映射表使用 [ConcurrentHashMap] 以保证线程安全。
         *
         * 映射规则：
         * - key: 中文应用名称（如 "微信"、"抖音"）
         * - value: Android 包名（如 "com.tencent.mm"）
         *
         * 若需添加新应用，直接在此处添加条目即可。
         */
        val APP_PACKAGE_MAP: ConcurrentHashMap<String, String> = ConcurrentHashMap(
            // 社交/即时通讯
            mapOf(
                "微信" to "com.tencent.mm",
                "QQ" to "com.tencent.mobileqq",
                "TIM" to "com.tencent.tim",
                "微博" to "com.sina.weibo",
                "小红书" to "com.xingin.xhs",
                "知乎" to "com.zhihu.android",
                "钉钉" to "com.alibaba.android.rimet",
                "企业微信" to "com.tencent.wework",
                "飞书" to "com.ss.android.lark",
                "探探" to "com.p1.mobile.putong",
                "Soul" to "cn.soulapp.android",
                "陌陌" to "com.immomo.momo",

                // 短视频/娱乐
                "抖音" to "com.ss.android.ugc.aweme",
                "抖音极速版" to "com.ss.android.ugc.aweme.lite",
                "快手" to "com.kuaishou.nebula",
                "快手极速版" to "com.kuaishou.nebula.lite",
                "哔哩哔哩" to "tv.danmaku.bili",
                "B站" to "tv.danmaku.bili",
                "火山小视频" to "com.ss.android.ugc.live",
                "西瓜视频" to "com.ixigua",

                // 购物/电商
                "支付宝" to "com.eg.android.AlipayGphone",
                "淘宝" to "com.taobao.taobao",
                "天猫" to "com.tmall.wireless",
                "京东" to "com.jingdong.app.mall",
                "拼多多" to "com.xunmeng.pinduoduo",
                "美团" to "com.sankuai.meituan",
                "美团外卖" to "com.sankuai.meituan.takeoutnew",
                "饿了么" to "me.ele",
                "大众点评" to "com.dianping.v1",
                "闲鱼" to "com.taobao.idlefish",
                "唯品会" to "com.achievo.vipshop",
                "苏宁易购" to "com.suning.mobile.ebuy",
                "1688" to "com.alibaba.wireless",
                "什么值得买" to "com.smzdm.client.android",

                // 地图/出行
                "高德地图" to "com.autonavi.minimap",
                "百度地图" to "com.baidu.BaiduMap",
                "滴滴" to "com.sdu.didi.gps",
                "滴滴出行" to "com.sdu.didi.gps",
                "携程" to "ctrip.android.view",
                "去哪儿" to "com.Qunar",
                "飞猪" to "com.taobao.trip",
                "哈啰" to "com.jingyao.easybike",
                "美团单车" to "com.mobike.mobike",
                "铁路12306" to "com.MobileTicket",

                // 音乐/音频
                "网易云音乐" to "com.netease.cloudmusic",
                "QQ音乐" to "com.tencent.qqmusic",
                "酷狗音乐" to "com.kugou.android",
                "酷我音乐" to "cn.kuwo.player",
                "虾米音乐" to "com.xiami.music",
                "喜马拉雅" to "com.ximalaya.ting.android",
                "蜻蜓FM" to "cn.tingfm.android",

                // 视频/影视
                "腾讯视频" to "com.tencent.qqlive",
                "爱奇艺" to "com.qiyi.video",
                "优酷" to "com.youku.phone",
                "芒果TV" to "com.hunantv.imgo.activity",
                "搜狐视频" to "com.sohu.sohuvideo",
                "韩剧TV" to "com.hanjutv",

                // 系统/工具
                "设置" to "com.android.settings",
                "相机" to "com.android.camera",
                "相册" to "com.android.gallery3d",
                "图库" to "com.android.gallery3d",
                "电话" to "com.android.dialer",
                "短信" to "com.android.mms",
                "信息" to "com.android.mms",
                "日历" to "com.android.calendar",
                "时钟" to "com.android.deskclock",
                "计算器" to "com.android.calculator2",
                "文件管理" to "com.android.documentsui",
                "文件" to "com.android.documentsui",
                "应用商店" to "com.android.vending",
                "应用市场" to "com.android.vending",
                "浏览器" to "com.android.chrome",
                "录音机" to "com.android.soundrecorder",
                "便签" to "com.android.notes",
                "天气" to "com.android.weather",

                // Google 系
                "谷歌" to "com.google.android.googlequicksearchbox",
                "Chrome" to "com.android.chrome",
                "YouTube" to "com.google.android.youtube",
                "油管" to "com.google.android.youtube",
                "Gmail" to "com.google.android.gm",
                "Google地图" to "com.google.android.apps.maps",
                "Google相册" to "com.google.android.apps.photos",
                "Google日历" to "com.google.android.calendar",
                "Google云盘" to "com.google.android.apps.docs",

                // 办公/效率
                "WPS" to "cn.wps.moffice_eng",
                "腾讯文档" to "com.tencent.docs",
                "金山文档" to "cn.wps.yun",
                "百度网盘" to "com.baidu.netdisk",
                "阿里云盘" to "com.alicloud.databox",
                "新浪邮箱" to "com.sina.mail",
                "QQ邮箱" to "com.tencent.androidqqmail",
                "网易邮箱" to "com.netease.mail",
                "有道词典" to "com.youdao.dict",
                "百度翻译" to "com.baidu.translate",

                // 金融/理财
                "招商银行" to "com.cmbchina.ccd.pluto.cmbActivity",
                "工商银行" to "com.icbc",
                "建设银行" to "com.chinamworld.main",
                "农业银行" to "com.android.bankabc",
                "中国银行" to "com.chinamworld.bocmbci",
                "支付宝" to "com.eg.android.AlipayGphone",
                "微信支付" to "com.tencent.mm",
                "云闪付" to "com.unionpay",

                // AI 助手
                "豆包" to "com.larus.nova",

                // 新闻/资讯
                "今日头条" to "com.ss.android.article.news",
                "腾讯新闻" to "com.tencent.news",
                "网易新闻" to "com.netease.newsreader.activity",
                "新浪新闻" to "com.sina.news",
                "澎湃新闻" to "com.wondertek.paper",
                "搜狐新闻" to "com.sohu.newsclient",

                // 游戏/直播
                "斗鱼" to "air.tv.douyu.android",
                "虎牙" to "com.duowan.kiwi",
                "网易大神" to "com.netease.gl",
                "TapTap" to "com.taptap",
                "原神" to "com.miHoYo.Yuanshen",
                "王者荣耀" to "com.tencent.tmgp.sgame",
                "和平精英" to "com.tencent.tmgp.pubgmhd",
                "英雄联盟手游" to "com.tencent.lolm",

                // AI 助手
                "豆包" to "com.larus.nova",
                "豆包AI" to "com.larus.nova",
                "DeepSeek" to "com.deepseek.chat",
                "Kimi" to "com.moonshot.kimichat",
                "文心一言" to "com.baidu.wenxin",
                "通义千问" to "com.alibaba.aliwenxin",
                "通义" to "com.alibaba.aliwenxin",
                "星火" to "com.iflytek.inputmethod",
                "ChatGPT" to "com.openai.chatgpt",
                "Copilot" to "com.microsoft.copilot",
                "Gemini" to "com.google.android.apps.gemini",
                "Google助手" to "com.google.android.googlequicksearchbox",

                // 二次元/社区
                "贴吧" to "com.baidu.tieba",
                "百度贴吧" to "com.baidu.tieba",
                "NGA" to "com.ngabbs.nagent",
                "酷安" to "com.coolapk.market",
                "V2EX" to "com.v2ex",
                "GitHub" to "com.github.android",

                // 运动健康
                "Keep" to "com.gotokeep.keep",
                "悦跑圈" to "com.woya.run",
                "小米运动" to "com.xiaomi.hm.health",
                "华为健康" to "com.huawei.health",

                // 学习/教育
                "百度文库" to "com.baidu.wenku",
                "学习强国" to "cn.xuexi.android",
                "中国大学MOOC" to "com.icourse163",
                "知乎" to "com.zhihu.android",
                "得到" to "com.duoduo.deer",
                "微信读书" to "com.tencent.weread",
                "番茄小说" to "com.dragon.read",

                // 生活服务
                "交管12123" to "com.tmri.app.main",
                "国家反诈中心" to "com.hicorenational.antifraud",
                "个人所得税" to "com.renrenshishui",
                "掌上生活" to "com.cmbchina.ccd.pluto.cmbActivity",
                "小米商城" to "com.xiaomi.shop",
                "华为商城" to "com.vmall.client",
                "58同城" to "com.wuba",
                "安居客" to "com.anjuke.android.app",
                "贝壳找房" to "com.lianjia.beike",
                "小米有品" to "com.xiaomi.youpin",
                "华为视频" to "com.huawei.hwvplayer",
                "华为音乐" to "com.huawei.music",
                "OPPO商城" to "com.oppo.store",
                "vivo商城" to "com.vivo.upshore",

                // 影音娱乐
                "网易云" to "com.netease.cloudmusic",
                "QQ音乐" to "com.tencent.qqmusic",
                "酷狗" to "com.kugou.android",
                "网易云音乐" to "com.netease.cloudmusic",
                "腾讯视频" to "com.tencent.qqlive",
                "爱奇艺" to "com.qiyi.video",
                "优酷" to "com.youku.phone",
                "B站" to "tv.danmaku.bili",
                "bilibili" to "tv.danmaku.bili",
                "AcFun" to "tv.acfun.android",
                "A站" to "tv.acfun.android",
                "咪咕视频" to "com.migu.video",
                "央视频" to "com.cctv.yangshipin",
                "抖音火山版" to "com.ss.android.ugc.live",
                "微视" to "com.tencent.weishi",

                // ========== 应用别名/昵称（方便用户识别） ==========
                // 微信系
                "绿泡泡" to "com.tencent.mm",
                "小而美" to "com.tencent.mm",
                "卫星" to "com.tencent.mm",
                "VX" to "com.tencent.mm",
                "WeChat" to "com.tencent.mm",
                // 支付宝系
                "支" to "com.eg.android.AlipayGphone",
                "阿支" to "com.eg.android.AlipayGphone",
                // 淘宝系
                "某宝" to "com.taobao.taobao",
                "TB" to "com.taobao.taobao",
                "掏宝" to "com.taobao.taobao",
                // 京东
                "狗东" to "com.jingdong.app.mall",
                "JD" to "com.jingdong.app.mall",
                "二手东" to "com.jingdong.app.mall",
                // 拼多多
                "PDD" to "com.xunmeng.pinduoduo",
                "拼夕夕" to "com.xunmeng.pinduoduo",
                // 抖音
                "DY" to "com.ss.android.ugc.aweme",
                "抖" to "com.ss.android.ugc.aweme",
                "抖音短视频" to "com.ss.android.ugc.aweme",
                "TikTok" to "com.ss.android.ugc.aweme",
                // 快手
                "KS" to "com.kuaishou.nebula",
                // 微博
                "WB" to "com.sina.weibo",
                "渣浪" to "com.sina.weibo",
                // 小红书
                "XHS" to "com.xingin.xhs",
                "小书" to "com.xingin.xhs",
                "红书" to "com.xingin.xhs",
                // 哔哩哔哩
                "B站" to "tv.danmaku.bili",
                "bilibili" to "tv.danmaku.bili",
                "Bilibili" to "tv.danmaku.bili",
                "睿站" to "tv.danmaku.bili",
                "小破站" to "tv.danmaku.bili",
                // 知乎
                "逼乎" to "com.zhihu.android",
                "ZH" to "com.zhihu.android",
                // 钉钉
                "DD" to "com.alibaba.android.rimet",
                "DingTalk" to "com.alibaba.android.rimet",
                // 飞书
                "Feishu" to "com.ss.android.lark",
                "Lark" to "com.ss.android.lark",
                // 美团
                "MT" to "com.sankuai.meituan",
                "黄色软件" to "com.sankuai.meituan",
                // 饿了么
                "ELM" to "me.ele",
                "饿了吗" to "me.ele",
                "蓝骑士" to "me.ele",
                // 高德
                "高德" to "com.autonavi.minimap",
                "德哥" to "com.autonavi.minimap",
                // 滴滴
                "DiDi" to "com.sdu.didi.gps",
                "DD" to "com.sdu.didi.gps",
                // 网易云音乐
                "网抑云" to "com.netease.cloudmusic",
                "云村" to "com.netease.cloudmusic",
                "黑胶" to "com.netease.cloudmusic",
                // 酷狗
                "KG" to "com.kugou.android",
                // 百度
                "BD" to "com.baidu.searchbox",
                "度娘" to "com.baidu.searchbox",
                "百度一下" to "com.baidu.searchbox",
                // 抖音火山版
                "火山" to "com.ss.android.ugc.live",
                // 原神
                "Genshin" to "com.miHoYo.Yuanshen",
                "原" to "com.miHoYo.Yuanshen",
                // 王者荣耀
                "农药" to "com.tencent.tmgp.sgame",
                "WZ" to "com.tencent.tmgp.sgame",
                "排位" to "com.tencent.tmgp.sgame",
                // 和平精英
                "吃鸡" to "com.tencent.tmgp.pubgmhd",
                "刺激战场" to "com.tencent.tmgp.pubgmhd",
                // AI助手别名
                "豆包AI" to "com.larus.nova",
                "豆包" to "com.larus.nova",
                "包子" to "com.larus.nova",
                "DS" to "com.deepseek.chat",
                "深度求索" to "com.deepseek.chat",
                "Kimi" to "com.moonshot.kimichat",
                "KimiChat" to "com.moonshot.kimichat",
                "月之暗面" to "com.moonshot.kimichat",
                // 文心一言
                "一言" to "com.baidu.wenxin",
                "文心" to "com.baidu.wenxin",
                "ERNIE" to "com.baidu.wenxin",
                // 通义
                "千问" to "com.alibaba.aliwenxin",
                "TY" to "com.alibaba.aliwenxin",
                "通义千问" to "com.alibaba.aliwenxin",
                // 星火
                "讯飞星火" to "com.iflytek.inputmethod",
                "Spark" to "com.iflytek.inputmethod",
                // ========== 新增应用（v2.0.0 扩展） ==========
                // 更多 AI 助手
                "元宝" to "com.tencent.weiyun",
                "腾讯元宝" to "com.tencent.yuanbao",
                "天工AI" to "com.skywork.ai",
                "天工" to "com.skywork.ai",
                "百川" to "com.baichuan.chat",
                "百川智能" to "com.baichuan.chat",
                "智谱" to "com.zhipu.chat",
                "智谱AI" to "com.zhipu.chat",
                "秘塔AI" to "com.meta.chat",
                "秘塔" to "com.meta.chat",
                "Perplexity" to "com.perplexity.ai",
                "Claude" to "com.anthropic.claude",
                "Grok" to "com.x.grok",
                "豆包" to "com.larus.nova",
                // 社交媒体
                "Instagram" to "com.instagram.android",
                "Ins" to "com.instagram.android",
                "IG" to "com.instagram.android",
                "Facebook" to "com.facebook.katana",
                "脸书" to "com.facebook.katana",
                "Twitter" to "com.twitter.android",
                "推特" to "com.twitter.android",
                "X" to "com.twitter.android",
                "Telegram" to "org.telegram.messenger",
                "电报" to "org.telegram.messenger",
                "TG" to "org.telegram.messenger",
                "Signal" to "org.thoughtcrime.securesms",
                "WhatsApp" to "com.whatsapp",
                "Line" to "jp.naver.line.android",
                "Discord" to "com.discord",
                "Reddit" to "com.reddit.frontpage",
                "Pinterest" to "com.pinterest",
                "LinkedIn" to "com.linkedin.android",
                "领英" to "com.linkedin.android",
                "Snapchat" to "com.snapchat.android",
                "TikTok国际版" to "com.zhiliaoapp.musically",
                // 视频/影视新增
                "Netflix" to "com.netflix.mediaclient",
                "奈飞" to "com.netflix.mediaclient",
                "Disney+" to "com.disney.disneyplus",
                "迪士尼" to "com.disney.disneyplus",
                "HBO" to "com.hbo.hbonow",
                "HBO Max" to "com.hbo.max",
                "Amazon Prime" to "com.amazon.avod.thirdpartyclient",
                "Prime Video" to "com.amazon.avod.thirdpartyclient",
                "Spotify" to "com.spotify.music",
                "Apple Music" to "com.apple.android.music",
                "Podcast" to "com.google.android.apps.podcasts",
                "小宇宙" to "com.xiaoyuzhou.podcast",
                "豆瓣" to "com.douban.frodo",
                "即刻" to "com.ruguoapp.jike",
                // 工具新增
                "ES文件浏览器" to "com.estrongs.android.pop",
                "Solid Explorer" to "pl.solidexplorer2",
                "MX播放器" to "com.mxtech.videoplayer.ad",
                "VLC" to "org.videolan.vlc",
                "迅雷" to "com.xunlei.downloadprovider",
                "百度云" to "com.baidu.netdisk",
                "OneDrive" to "com.microsoft.skydrive",
                "Google Drive" to "com.google.android.apps.docs",
                "Dropbox" to "com.dropbox.android",
                "Notion" to "notion.notion",
                "Obsidian" to "md.obsidian",
                "印象笔记" to "com.evernote",
                "为知笔记" to "cn.wiz.note",
                "幕布" to "com.mubu",
                "XMind" to "com.xmind.xmind",
                "ProcessOn" to "com.processon",
                "思维导图" to "com.xmind.xmind",
                "腾讯会议" to "com.tencent.wemeet",
                "Zoom" to "us.zoom.videomeetings",
                "瞩目" to "us.zoom.videomeetings",
                "Teams" to "com.microsoft.teams",
                "Slack" to "com.slack",
                "Notability" to "com.notability",
                "GoodNotes" to "com.goodnotes",
                "扫描全能王" to "com.intsig.camscanner",
                "CS扫描" to "com.intsig.camscanner",
                "百度输入法" to "com.baidu.input",
                "搜狗输入法" to "com.sohu.inputmethod.sogou",
                "讯飞输入法" to "com.iflytek.inputmethod",
                "Gboard" to "com.google.android.inputmethod.latin",
                // 游戏新增
                "LOL" to "com.tencent.lolm",
                "CF手游" to "com.tencent.tmgp.cf",
                "穿越火线" to "com.tencent.tmgp.cf",
                "第五人格" to "com.netease.idv",
                "阴阳师" to "com.netease.onmyoji",
                "崩坏3" to "com.miHoYo.enterprise.bh3",
                "崩坏" to "com.miHoYo.enterprise.bh3",
                "星穹铁道" to "com.miHoYo.starrail",
                "崩坏星穹铁道" to "com.miHoYo.starrail",
                "绝区零" to "com.miHoYo.zzz",
                "明日方舟" to "com.hypergryph.arknights",
                "方舟" to "com.hypergryph.arknights",
                "金铲铲之战" to "com.tencent.tmgp.jcc",
                "金铲铲" to "com.tencent.tmgp.jcc",
                "蛋仔派对" to "com.netease.egg",
                "光遇" to "com.netease.sky",
                "我的世界" to "com.mojang.minecraftpe",
                "Minecraft" to "com.mojang.minecraftpe",
                "MC" to "com.mojang.minecraftpe",
                "部落冲突" to "com.supercell.clashofclans",
                "COC" to "com.supercell.clashofclans",
                "皇室战争" to "com.supercell.clashroyale",
                "CR" to "com.supercell.clashroyale",
                "荒野乱斗" to "com.supercell.brawlstars",
                "BS" to "com.supercell.brawlstars",
                "Steam" to "com.valvesoftware.steamlink",
                "Steam Link" to "com.valvesoftware.steamlink",
                // 生活服务新增
                "顺丰" to "com.sf.activity",
                "顺丰速运" to "com.sf.activity",
                "菜鸟" to "com.cainiao.wireless",
                "菜鸟裹裹" to "com.cainiao.wireless",
                "快递100" to "com.kuaidi100",
                "中通" to "com.zto.zto",
                "圆通" to "com.yto.zhanghu",
                "韵达" to "com.yundaex",
                "申通" to "com.sto.shop",
                "邮政" to "com.ems.mobile",
                "EMS" to "com.ems.mobile",
                "国航" to "com.airchina",
                "南航" to "com.csair.mbp",
                "东航" to "com.ceair",
                "航旅纵横" to "com.umetrip.android.msky",
                "智行" to "com.zhixing",
                "途牛" to "com.tuniu.app",
                "马蜂窝" to "com.magicears.mfh",
                "爱彼迎" to "com.airbnb.android",
                "Airbnb" to "com.airbnb.android",
                "Klook" to "com.klook",
                "天眼查" to "com.tianyancha.skyeye",
                "企查查" to "com.qichacha",
                "启信宝" to "com.qixin.Network",
                // 金融新增
                "同花顺" to "com.hexin.plat.android",
                "东方财富" to "com.eastmoney.android.berlin",
                "雪球" to "com.xueqiu.android",
                "涨乐财富通" to "com.lphtsccft",
                "华泰证券" to "com.lphtsccft",
                "中信证券" to "com.cs.ecitic",
                "平安证券" to "com.pingan.stock",
                "支付宝" to "com.eg.android.AlipayGphone",
                "微信支付" to "com.tencent.mm",
                "银联" to "com.unionpay",
                "京东金融" to "com.jd.jrapp",
                "度小满" to "com.baidu.ibm",
                "360借条" to "com.qihoo.loan",
                "借呗" to "com.eg.android.AlipayGphone",
                "花呗" to "com.eg.android.AlipayGphone",
                "还呗" to "com.huanbei",
                // 外卖新增别名
                "黄色软件" to "com.sankuai.meituan",
                "蓝骑士" to "me.ele",
                "蜂鸟" to "me.ele",
                "美团外卖" to "com.sankuai.meituan.takeoutnew",
                "饿了么" to "me.ele",
                "饿了吗" to "me.ele",
                "大众点评" to "com.dianping.v1",
                "点评" to "com.dianping.v1",
                "肯德基" to "com.yumchina.kfc",
                "KFC" to "com.yumchina.kfc",
                "麦当劳" to "com.mcdonalds.app",
                "McDonald's" to "com.mcdonalds.app",
                "星巴克" to "com.starbucks.cn",
                "瑞幸咖啡" to "com.luckincoffee",
                "瑞幸" to "com.luckincoffee",
                "luckin" to "com.luckincoffee",
                "蜜雪冰城" to "com.mxbc.mxbc",
                "喜茶" to "com.heytea",
                "奈雪的茶" to "com.naixue",
                // 地图/出行新增
                "百度地图" to "com.baidu.BaiduMap",
                "高德地图" to "com.autonavi.minimap",
                "腾讯地图" to "com.tencent.map",
                "谷歌地图" to "com.google.android.apps.maps",
                "Google Maps" to "com.google.android.apps.maps",
                "滴滴出行" to "com.sdu.didi.gps",
                "滴滴" to "com.sdu.didi.gps",
                "花小猪" to "com.huaxiaozhu",
                "T3出行" to "cn.t3出行",
                "曹操出行" to "com.incar.demo",
                "首汽约车" to "com.shouqievip",
                "哈啰出行" to "com.jingyao.easybike",
                "哈啰" to "com.jingyao.easybike",
                "青桔" to "com.didi.soda",
                "美团单车" to "com.mobike.mobike",
                "摩拜" to "com.mobike.mobike",
                "12306" to "com.MobileTicket",
                "铁路12306" to "com.MobileTicket",
                "携程" to "ctrip.android.view",
                "去哪儿" to "com.Qunar",
                "飞猪" to "com.taobao.trip",
                // 更多别名
                "绿泡泡" to "com.tencent.mm",
                "小而美" to "com.tencent.mm",
                "卫星" to "com.tencent.mm",
                "VX" to "com.tencent.mm",
                "WeChat" to "com.tencent.mm",
                "Weixin" to "com.tencent.mm",
                "阿支" to "com.eg.android.AlipayGphone",
                "支" to "com.eg.android.AlipayGphone",
                "支付宝" to "com.eg.android.AlipayGphone",
                "某宝" to "com.taobao.taobao",
                "TB" to "com.taobao.taobao",
                "掏宝" to "com.taobao.taobao",
                "掏" to "com.taobao.taobao",
                "狗东" to "com.jingdong.app.mall",
                "JD" to "com.jingdong.app.mall",
                "二手东" to "com.jingdong.app.mall",
                "京东" to "com.jingdong.app.mall",
                "PDD" to "com.xunmeng.pinduoduo",
                "拼夕夕" to "com.xunmeng.pinduoduo",
                "拼多多" to "com.xunmeng.pinduoduo",
                "DY" to "com.ss.android.ugc.aweme",
                "抖" to "com.ss.android.ugc.aweme",
                "抖音短视频" to "com.ss.android.ugc.aweme",
                "TikTok" to "com.ss.android.ugc.aweme",
                "KS" to "com.kuaishou.nebula",
                "WB" to "com.sina.weibo",
                "渣浪" to "com.sina.weibo",
                "XHS" to "com.xingin.xhs",
                "小书" to "com.xingin.xhs",
                "红书" to "com.xingin.xhs",
                "bilibili" to "tv.danmaku.bili",
                "Bilibili" to "tv.danmaku.bili",
                "睿站" to "tv.danmaku.bili",
                "小破站" to "tv.danmaku.bili",
                "逼乎" to "com.zhihu.android",
                "ZH" to "com.zhihu.android",
                "DD" to "com.alibaba.android.rimet",
                "DingTalk" to "com.alibaba.android.rimet",
                "Feishu" to "com.ss.android.lark",
                "Lark" to "com.ss.android.lark",
                "MT" to "com.sankuai.meituan",
                "ELM" to "me.ele",
                "饿了吗" to "me.ele",
                "德哥" to "com.autonavi.minimap",
                "高德" to "com.autonavi.minimap",
                "DiDi" to "com.sdu.didi.gps",
                "网抑云" to "com.netease.cloudmusic",
                "云村" to "com.netease.cloudmusic",
                "黑胶" to "com.netease.cloudmusic",
                "网易云" to "com.netease.cloudmusic",
                "KG" to "com.kugou.android",
                "BD" to "com.baidu.searchbox",
                "度娘" to "com.baidu.searchbox",
                "百度一下" to "com.baidu.searchbox",
                "Genshin" to "com.miHoYo.Yuanshen",
                "原" to "com.miHoYo.Yuanshen",
                "农药" to "com.tencent.tmgp.sgame",
                "WZ" to "com.tencent.tmgp.sgame",
                "排位" to "com.tencent.tmgp.sgame",
                "吃鸡" to "com.tencent.tmgp.pubgmhd",
                "刺激战场" to "com.tencent.tmgp.pubgmhd",
                "豆包AI" to "com.larus.nova",
                "豆包" to "com.larus.nova",
                "包子" to "com.larus.nova",
                "DS" to "com.deepseek.chat",
                "深度求索" to "com.deepseek.chat",
                "Kimi" to "com.moonshot.kimichat",
                "KimiChat" to "com.moonshot.kimichat",
                "月之暗面" to "com.moonshot.kimichat",
                "一言" to "com.baidu.wenxin",
                "文心" to "com.baidu.wenxin",
                "ERNIE" to "com.baidu.wenxin",
                "千问" to "com.alibaba.aliwenxin",
                "TY" to "com.alibaba.aliwenxin",
                "通义千问" to "com.alibaba.aliwenxin",
                "通义" to "com.alibaba.aliwenxin",
                "讯飞星火" to "com.iflytek.inputmethod",
                "Spark" to "com.iflytek.inputmethod",
                // 新应用缩写别名
                "VX" to "com.tencent.mm",
                "ZFB" to "com.eg.android.AlipayGphone",
                "TB" to "com.taobao.taobao",
                "JD" to "com.jingdong.app.mall",
                "PDD" to "com.xunmeng.pinduoduo",
                "MT" to "com.sankuai.meituan",
                "DY" to "com.ss.android.ugc.aweme",
                "KS" to "com.kuaishou.nebula",
                "WB" to "com.sina.weibo",
                "B站" to "tv.danmaku.bili",
                "ZH" to "com.zhihu.android",
                "XHS" to "com.xingin.xhs",
                "DD" to "com.alibaba.android.rimet",
                "QQ" to "com.tencent.mobileqq",
                "WX" to "com.tencent.mm",
                "BD" to "com.baidu.searchbox",
                "DS" to "com.deepseek.chat",
                "TY" to "com.alibaba.aliwenxin",
                "KG" to "com.kugou.android",
                "ELM" to "me.ele",
                "WZ" to "com.tencent.tmgp.sgame",
                // 常用社交
                "绿信" to "com.tencent.mm",
                "微" to "com.tencent.mm",
                "微信" to "com.tencent.mm",
                "QQ" to "com.tencent.mobileqq",
                "企鹅" to "com.tencent.mobileqq",
                "小企鹅" to "com.tencent.mobileqq",
                "微博" to "com.sina.weibo",
                "小红书" to "com.xingin.xhs",
                "知乎" to "com.zhihu.android",
                "贴吧" to "com.baidu.tieba",
                "百度贴吧" to "com.baidu.tieba",
                "NGA" to "com.ngabbs.nagent",
                "酷安" to "com.coolapk.market",
                "V2EX" to "com.v2ex",
                "GitHub" to "com.github.android",
                "码云" to "com.github.android",
                "Gitee" to "com.gitee.android",

                // ========== 新增应用 v2.0.1 ==========
                // 更多游戏
                "LOL手游" to "com.tencent.lolm",
                "金铲铲" to "com.tencent.tmgp.jcc",
                "金铲铲之战" to "com.tencent.tmgp.jcc",
                // 更多工具
                "李跳跳" to "com.ltt.ltt",
                "自动跳过" to "com.autojump",
                "轻启动" to "com.lite.startup",
                "存储空间清理" to "com.cleanmaster.master",
                "安卓清理" to "com.cleanmaster.master",
                "黑阈" to "me.piebridge.brevent",
                "Shizuku" to "moe.shizuku.manager",
                "ShizukuManager" to "moe.shizuku.manager",
                "冰箱" to "com.catchingnow.icebox",
                "绿色守护" to "com.oasisfeng.greenify",
                "应用管理" to "com.android.documentsui",
                "权限管理" to "com.android.permissioncontroller",
                "应用信息" to "com.android.settings",
                // 更多社交
                "Soul" to "cn.soulapp.android",
                "积目" to "com.blue.eye",
                "探探" to "com.p1.mobile.putong",
                "微博国际版" to "com.sina.weibointl",
                "微博轻享版" to "com.sina.weibolight",
                "QQ轻聊版" to "com.tencent.qqlite",
                // 更多购物
                "考拉" to "com.kaola",
                "网易考拉" to "com.kaola",
                "得物" to "com.shizhuang.duapp",
                "识货" to "com.hupu.shihuo",
                "转转" to "com.zhuanzhuan",
                "贝壳" to "com.lianjia.beike",
                // 更多视频
                "人人视频" to "com.qiyi.video",
                "人人影视" to "com.hackor.renren",
                "影视大全" to "com.tencent.qqlive",
                "埋堆堆" to "com.mdd",
                "柠檬视频" to "com.lemon.video",
                "91看" to "com.91kan",
                // 更多音乐
                "汽水音乐" to "com.netease.qishui",
                "咪咕音乐" to "com.migu.music",
                "千千音乐" to "com.baidu.baidumusic",
                // 更多AI助手
                "豆包" to "com.larus.nova",
                "豆包AI" to "com.larus.nova",
                "扣子" to "com.coze.cn",
                "Coze" to "com.coze.cn",
                "智谱清言" to "com.zhipu.chat",
                "海螺AI" to "com.hailuo.ai",
                "MiniMax" to "com.minimax.chat"
            )
        )
    }
}

// =============================================================================
//  命令信息数据类
// =============================================================================

/**
 * 命令信息。
 *
 * 用于 [LocalCommandExecutor.getAllCommands] 返回，描述一条可用的本地命令。
 *
 * @param commandName 命令名称（中文，如 "打开应用"、"截屏"）
 * @param actionType  对应的动作类型
 * @param examples    示例命令列表
 * @param description 命令的详细说明
 */
data class CommandInfo(
    val commandName: String,
    val actionType: ActionType,
    val examples: List<String>,
    val description: String
)