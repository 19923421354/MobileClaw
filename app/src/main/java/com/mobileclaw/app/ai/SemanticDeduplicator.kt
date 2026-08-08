package com.mobileclaw.app.ai

import com.mobileclaw.app.ai.ActionType
import com.mobileclaw.app.ai.ClawAction
import kotlin.math.hypot

/**
 * 语义去重器 —— 检测并消除动作序列中的语义重复与冗余动作。
 *
 * 核心问题：AI 生成的动作序列常包含重复或相互抵消的动作，例如连续点击同一元素、
 * 点击相近坐标、用不同动作类型完成同一目标，或先执行某动作再执行其逆动作。
 * 这些冗余动作浪费执行时间、增加失败概率，并干扰后续决策。
 *
 * 本去重器提供五类检测能力：
 * 1. 完全重复：动作类型与参数完全一致（如连续两次 SCREEN_CLICK_TEXT{text:"搜索"}）
 * 2. 近似重复：类型相同但参数相近（如点击坐标 (100,200) 与 (105,203)）
 * 3. 语义重复：类型不同但目标一致（如 SCREEN_CLICK_TEXT{text:"搜索"} 与 SCREEN_FIND_AND_CLICK{text:"搜索"}）
 * 4. 冗余序列：连续多次操作同一元素（保留首次，移除后续）
 * 5. 互逆动作：动作后紧跟其逆动作（如 VOLUME_UP 后 VOLUME_DOWN、SWIPE UP 后 DOWN）
 *
 * 去重策略（[deduplicate]）：仅对相邻动作进行冗余判定，避免误删「先操作再回头操作」的合法序列；
 * 其中滑动、按键、等待的连续重复可能是有意为之（连续上滑翻页、连按音量键等），不自动移除，
 * 但若相邻动作构成互逆对（上滑后下滑、音量加后音量减、打开后关闭同一应用）则同时移除二者。
 * 分析接口 [findDuplicates] 扫描全部动作对，用于调试与统计。
 *
 * 使用示例：
 * ```
 * val result = SemanticDeduplicator.deduplicate(actions)
 * if (result.removedCount > 0) {
 *     Log.d(TAG, result.reason)
 * }
 * val pairs = SemanticDeduplicator.findDuplicates(actions)
 * println(SemanticDeduplicator.getDedupStats())
 * ```
 */
object SemanticDeduplicator {

    // =========================================================================
    //  常量与配置
    // =========================================================================

    /** 坐标邻近阈值（像素）：两坐标欧氏距离不超过该值视为近似重复。 */
    const val COORD_PROXIMITY_THRESHOLD = 30

    /** 完全重复的相似度评分。 */
    private const val EXACT_SIMILARITY = 1.0f

    /** 语义重复的相似度评分。 */
    private const val SEMANTIC_SIMILARITY = 0.9f

    /** 互逆动作的相似度评分（互为相反操作，相似度为 0）。 */
    private const val INVERSE_SIMILARITY = 0.0f

    /** 归一化文本/按键/包名相等时的近似相似度评分。 */
    private const val NEAR_NORMALIZED_SIMILARITY = 0.95f

    /**
     * 语义等价动作组：组内不同动作类型可达成相同目标，视为语义重复。
     * - 点击文本组：SCREEN_CLICK_TEXT 与 SCREEN_FIND_AND_CLICK 均为点击指定文本
     * - 打开应用组：APP_OPEN（按包名/名称）与 APP_SEARCH（按名称搜索并打开）均为打开指定应用
     */
    private val SEMANTIC_EQUIVALENCE_GROUPS: List<Set<ActionType>> = listOf(
        setOf(ActionType.SCREEN_CLICK_TEXT, ActionType.SCREEN_FIND_AND_CLICK),
        setOf(ActionType.APP_OPEN, ActionType.APP_SEARCH)
    )

    /** 互逆按键对（SCREEN_KEY 的 key 参数）。 */
    private val INVERSE_KEY_PAIRS: List<Pair<String, String>> = listOf(
        "VOLUME_UP" to "VOLUME_DOWN"
    )

    /** 互逆滑动方向对（SCREEN_SWIPE 的 direction 参数）。 */
    private val INVERSE_SWIPE_DIRECTIONS: List<Pair<String, String>> = listOf(
        "UP" to "DOWN",
        "LEFT" to "RIGHT"
    )

    /**
     * 连续重复可安全移除的动作类型集合。
     *
     * 仅包含点击类与幂等查询/操作类动作——这些动作连续重复执行通常无意义。
     * 滑动（SCREEN_SWIPE）、按键（SCREEN_KEY）与等待（SCREEN_WAIT）的连续重复
     * 可能是有意为之（如连续上滑翻页、连按音量键、分段等待），故不自动移除；
     * 但它们若构成互逆对（如上滑后下滑、音量加后音量减）仍会被 [isInversePair] 抵消。
     */
    private val REMOVABLE_DUPLICATE_TYPES: Set<ActionType> = setOf(
        ActionType.SCREEN_CLICK,
        ActionType.SCREEN_CLICK_TEXT,
        ActionType.SCREEN_LONG_CLICK,
        ActionType.SCREEN_DOUBLE_CLICK,
        ActionType.SCREEN_FIND_AND_CLICK,
        ActionType.SCREEN_SCROLL_TO_TEXT,
        ActionType.SCREEN_INPUT,
        ActionType.SCREEN_TEXT_EXISTS,
        ActionType.SCREEN_SCREENSHOT,
        ActionType.SCREEN_GET_TEXT,
        ActionType.APP_OPEN,
        ActionType.APP_CLOSE,
        ActionType.APP_SEARCH,
        ActionType.APP_UNINSTALL
    )

    // =========================================================================
    //  统计
    // =========================================================================

    /** 累计处理的动作总数。 */
    var totalProcessed: Int = 0
        private set

    /** 累计移除的冗余动作总数。 */
    var totalRemoved: Int = 0
        private set

    /** 去重率（已移除动作占已处理动作的比例，0-1）。 */
    val dedupRate: Float
        get() = if (totalProcessed > 0) totalRemoved.toFloat() / totalProcessed else 0f

    // =========================================================================
    //  数据模型
    // =========================================================================

    /**
     * 去重结果。
     *
     * @param original        原始动作列表
     * @param deduplicated    去重后的动作列表
     * @param removedCount    移除的动作数量
     * @param removedIndices  被移除动作在原始列表中的下标（升序）
     * @param reason          去重原因说明
     */
    data class DedupResult(
        val original: List<ClawAction>,
        val deduplicated: List<ClawAction>,
        val removedCount: Int,
        val removedIndices: List<Int>,
        val reason: String
    )

    /**
     * 重复动作对。
     *
     * @param index1     第一个动作在列表中的下标
     * @param index2     第二个动作在列表中的下标
     * @param similarity 相似度（0-1，1 表示完全重复；互逆动作为 0）
     * @param reason     判定为重复的原因
     */
    data class DuplicatePair(
        val index1: Int,
        val index2: Int,
        val similarity: Float,
        val reason: String
    )

    // =========================================================================
    //  核心接口：去重与分析
    // =========================================================================

    /**
     * 对动作序列执行去重，移除相邻的冗余动作与互逆动作。
     *
     * 处理规则（顺序扫描，仅与上一个保留动作比较）：
     * - 互逆对：当前动作与上一个保留动作互逆 → 同时移除二者（相互抵消）。
     * - 完全/近似/语义重复：当前动作与上一个保留动作重复，且二者均属于
     *   [REMOVABLE_DUPLICATE_TYPES] → 移除当前动作（保留首次出现）。
     * - 其他情况：保留当前动作。
     *
     * 注意：仅对相邻动作判定冗余，非相邻的「回头操作」不会被误删；
     * 滑动/按键/等待的连续重复不自动移除（可能是有意操作）。
     *
     * @param actions 原始动作列表
     * @return 去重结果 [DedupResult]
     */
    fun deduplicate(actions: List<ClawAction>): DedupResult {
        totalProcessed += actions.size

        if (actions.size <= 1) {
            return DedupResult(
                original = actions,
                deduplicated = actions,
                removedCount = 0,
                removedIndices = emptyList(),
                reason = "动作数 ≤ 1，无需去重"
            )
        }

        val kept = mutableListOf<ClawAction>()
        // kept 中每个元素对应的原始下标，用于生成移除原因与 removedIndices
        val keptOrigIndex = mutableListOf<Int>()
        val removedIndices = mutableListOf<Int>()
        val removeReasons = mutableListOf<String>()

        for (i in actions.indices) {
            val current = actions[i]

            // 首个动作直接保留
            if (kept.isEmpty()) {
                kept.add(current)
                keptOrigIndex.add(i)
                continue
            }

            val lastPos = kept.lastIndex
            val lastKept = kept[lastPos]
            val lastOrigIdx = keptOrigIndex[lastPos]

            when {
                // 1. 互逆动作：移除上一个保留项 + 跳过当前项（相互抵消）
                isInversePair(lastKept, current) -> {
                    kept.removeAt(lastPos)
                    keptOrigIndex.removeAt(lastPos)
                    removedIndices.add(lastOrigIdx)
                    removedIndices.add(i)
                    removeReasons.add("动作[$lastOrigIdx]与动作[$i]互逆，已相互抵消")
                }

                // 2. 冗余重复：仅对可安全移除的类型判定完全/近似/语义重复
                lastKept.type in REMOVABLE_DUPLICATE_TYPES &&
                    current.type in REMOVABLE_DUPLICATE_TYPES -> {
                    when {
                        isExactDuplicate(lastKept, current) -> {
                            removedIndices.add(i)
                            removeReasons.add("动作[$i]与动作[$lastOrigIdx]完全重复，已移除")
                        }
                        isNearDuplicate(lastKept, current, COORD_PROXIMITY_THRESHOLD) -> {
                            removedIndices.add(i)
                            removeReasons.add("动作[$i]与动作[$lastOrigIdx]近似重复，已移除")
                        }
                        isSemanticDuplicate(lastKept, current) -> {
                            removedIndices.add(i)
                            removeReasons.add("动作[$i]与动作[$lastOrigIdx]语义重复，已移除")
                        }
                        else -> {
                            kept.add(current)
                            keptOrigIndex.add(i)
                        }
                    }
                }

                // 3. 其他情况：保留当前动作
                else -> {
                    kept.add(current)
                    keptOrigIndex.add(i)
                }
            }
        }

        val removedCount = removedIndices.size
        totalRemoved += removedCount

        val reason = if (removedCount == 0) {
            "未检测到冗余动作"
        } else {
            "共移除 $removedCount 个冗余动作：" + removeReasons.joinToString("；")
        }

        return DedupResult(
            original = actions,
            deduplicated = kept.toList(),
            removedCount = removedCount,
            removedIndices = removedIndices.sorted(),
            reason = reason
        )
    }

    /**
     * 扫描动作序列中所有重复对（不限于相邻），用于分析与调试。
     *
     * 对每一对 (i, j)（i < j）按优先级判定：完全重复 > 近似重复 > 语义重复 > 互逆，
     * 取首个命中的关系生成 [DuplicatePair]。复杂度为 O(n²)，适用于常规动作序列规模。
     *
     * @param actions 动作列表
     * @return 重复动作对列表
     */
    fun findDuplicates(actions: List<ClawAction>): List<DuplicatePair> {
        val pairs = mutableListOf<DuplicatePair>()
        for (i in actions.indices) {
            for (j in i + 1 until actions.size) {
                val a1 = actions[i]
                val a2 = actions[j]
                when {
                    isExactDuplicate(a1, a2) -> pairs.add(
                        DuplicatePair(i, j, EXACT_SIMILARITY, "完全重复：相同类型与参数")
                    )
                    isNearDuplicate(a1, a2, COORD_PROXIMITY_THRESHOLD) -> pairs.add(
                        DuplicatePair(i, j, nearSimilarity(a1, a2), "近似重复：参数相近（坐标/文本归一化后一致）")
                    )
                    isSemanticDuplicate(a1, a2) -> pairs.add(
                        DuplicatePair(i, j, SEMANTIC_SIMILARITY, "语义重复：不同动作达成相同目标")
                    )
                    isInversePair(a1, a2) -> pairs.add(
                        DuplicatePair(i, j, INVERSE_SIMILARITY, "互逆动作：相邻执行会相互抵消")
                    )
                }
            }
        }
        return pairs
    }

    // =========================================================================
    //  判定接口
    // =========================================================================

    /**
     * 判断两个动作是否完全重复：动作类型相同且参数完全一致（忽略描述字段）。
     *
     * @param a1 第一个动作
     * @param a2 第二个动作
     * @return 完全重复返回 true
     */
    fun isExactDuplicate(a1: ClawAction, a2: ClawAction): Boolean {
        val t1 = a1.type
        val t2 = a2.type
        // 类型不同（含一方无法识别）则不可能是完全重复
        if (t1 != t2) return false
        // 类型均无法识别时，退化为动作名 + 参数比较
        if (t1 == null) {
            return a1.actionName.equals(a2.actionName, ignoreCase = true) && a1.params == a2.params
        }
        // 类型相同且可识别：比较参数是否完全一致（JsonObject 内容相等）
        return a1.params == a2.params
    }

    /**
     * 判断两个动作是否近似重复：类型相同、不完全相同，但参数相近。
     *
     * - 坐标类动作（SCREEN_CLICK 等）：坐标欧氏距离 ≤ [threshold]
     * - 文本类动作：文本归一化（去空白、转小写）后一致但原文不同
     * - 滑动（SCREEN_SWIPE）：方向相同，或起止点分别相近
     * - 按键/包名：归一化相等但原文不同
     *
     * @param a1        第一个动作
     * @param a2        第二个动作
     * @param threshold 坐标邻近阈值（像素），默认 [COORD_PROXIMITY_THRESHOLD]
     * @return 近似重复返回 true
     */
    fun isNearDuplicate(
        a1: ClawAction,
        a2: ClawAction,
        threshold: Int = COORD_PROXIMITY_THRESHOLD
    ): Boolean {
        val t1 = a1.type ?: return false
        val t2 = a2.type ?: return false
        // 近似重复要求类型相同
        if (t1 != t2) return false
        // 完全重复不属于近似重复（近似强调「不完全相同但相近」）
        if (isExactDuplicate(a1, a2)) return false

        return when (t1) {
            ActionType.SCREEN_CLICK ->
                coordNear(a1.x, a1.y, a2.x, a2.y, threshold)

            ActionType.SCREEN_LONG_CLICK, ActionType.SCREEN_DOUBLE_CLICK ->
                coordNear(a1.x, a1.y, a2.x, a2.y, threshold) || textNear(a1.text, a2.text)

            ActionType.SCREEN_SWIPE -> {
                val d1 = a1.swipeDirectionName
                val d2 = a2.swipeDirectionName
                if (d1 != null && d2 != null) {
                    // 方向模式：方向相同即视为近似（参数如坐标略有不同）
                    d1.equals(d2, ignoreCase = true)
                } else {
                    // 坐标模式：起点与终点分别相近
                    coordNear(a1.x1, a1.y1, a2.x1, a2.y1, threshold) &&
                        coordNear(a1.x2, a1.y2, a2.x2, a2.y2, threshold)
                }
            }

            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK,
            ActionType.SCREEN_SCROLL_TO_TEXT,
            ActionType.SCREEN_INPUT,
            ActionType.SCREEN_TEXT_EXISTS ->
                textNear(a1.text, a2.text)

            ActionType.SCREEN_KEY ->
                stringNear(a1.keyName, a2.keyName)

            ActionType.APP_OPEN, ActionType.APP_CLOSE, ActionType.APP_UNINSTALL ->
                stringNear(a1.packageName, a2.packageName)

            else -> false
        }
    }

    /**
     * 判断两个动作是否语义重复：类型不同但属于同一语义等价组且目标一致。
     *
     * 等价组：
     * - {SCREEN_CLICK_TEXT, SCREEN_FIND_AND_CLICK}：文本目标一致
     * - {APP_OPEN, APP_SEARCH}：应用目标一致（包名或名称）
     *
     * @param a1 第一个动作
     * @param a2 第二个动作
     * @return 语义重复返回 true
     */
    fun isSemanticDuplicate(a1: ClawAction, a2: ClawAction): Boolean {
        val t1 = a1.type ?: return false
        val t2 = a2.type ?: return false
        // 语义重复要求类型不同（同类型归精确/近似重复）
        if (t1 == t2) return false
        // 必须属于同一语义等价组
        val sameGroup = SEMANTIC_EQUIVALENCE_GROUPS.any { t1 in it && t2 in it }
        if (!sameGroup) return false

        return when {
            // 点击文本组：文本目标一致（归一化比较）
            t1 in setOf(ActionType.SCREEN_CLICK_TEXT, ActionType.SCREEN_FIND_AND_CLICK) &&
                t2 in setOf(ActionType.SCREEN_CLICK_TEXT, ActionType.SCREEN_FIND_AND_CLICK) ->
                textEqualsNormalized(a1.text, a2.text)

            // 打开应用组：应用目标一致（优先按名称，其次按包名）
            t1 in setOf(ActionType.APP_OPEN, ActionType.APP_SEARCH) &&
                t2 in setOf(ActionType.APP_OPEN, ActionType.APP_SEARCH) ->
                appTargetEquals(a1, a2)

            else -> false
        }
    }

    /**
     * 判断两个动作是否互逆（对称判定）。
     *
     * 互逆对：
     * - SCREEN_KEY：VOLUME_UP ↔ VOLUME_DOWN
     * - SCREEN_SWIPE：UP ↔ DOWN、LEFT ↔ RIGHT；坐标模式下往返滑动（a 终点≈b 起点且 a 起点≈b 终点）
     * - APP_OPEN ↔ APP_CLOSE：同一包名
     *
     * @param a1 第一个动作
     * @param a2 第二个动作
     * @return 互逆返回 true
     */
    fun isInversePair(a1: ClawAction, a2: ClawAction): Boolean {
        val t1 = a1.type ?: return false
        val t2 = a2.type ?: return false

        // 1. 音量键互逆：SCREEN_KEY VOLUME_UP <-> VOLUME_DOWN
        if (t1 == ActionType.SCREEN_KEY && t2 == ActionType.SCREEN_KEY) {
            return isInverseKeys(a1.keyName, a2.keyName)
        }

        // 2. 滑动方向互逆：SCREEN_SWIPE UP<->DOWN, LEFT<->RIGHT
        if (t1 == ActionType.SCREEN_SWIPE && t2 == ActionType.SCREEN_SWIPE) {
            val d1 = a1.swipeDirectionName
            val d2 = a2.swipeDirectionName
            if (d1 != null && d2 != null) {
                return isInverseDirections(d1, d2)
            }
            // 坐标模式：a1 终点接近 a2 起点，且 a1 起点接近 a2 终点（往返滑动）
            if (a1.x1 != null && a1.y1 != null && a1.x2 != null && a1.y2 != null &&
                a2.x1 != null && a2.y1 != null && a2.x2 != null && a2.y2 != null
            ) {
                val forward = coordNear(a1.x2, a1.y2, a2.x1, a2.y1, COORD_PROXIMITY_THRESHOLD)
                val backward = coordNear(a1.x1, a1.y1, a2.x2, a2.y2, COORD_PROXIMITY_THRESHOLD)
                return forward && backward
            }
            return false
        }

        // 3. 应用打开/关闭互逆：APP_OPEN <-> APP_CLOSE（同一包名）
        if ((t1 == ActionType.APP_OPEN && t2 == ActionType.APP_CLOSE) ||
            (t1 == ActionType.APP_CLOSE && t2 == ActionType.APP_OPEN)
        ) {
            val p1 = a1.packageName
            val p2 = a2.packageName
            // APP_OPEN 可能仅按 name 打开，此时无法判定与 APP_CLOSE 是否针对同一应用，保守不判
            return !p1.isNullOrEmpty() && !p2.isNullOrEmpty() &&
                p1.trim().equals(p2.trim(), ignoreCase = true)
        }

        return false
    }

    // =========================================================================
    //  统计接口
    // =========================================================================

    /**
     * 获取累计去重统计的文本描述。
     *
     * @return 形如「语义去重统计：已处理 N 个动作，移除 M 个冗余动作，去重率 X.X%」
     */
    fun getDedupStats(): String {
        val ratePercent = dedupRate * 100f
        return "语义去重统计：已处理 $totalProcessed 个动作，" +
            "移除 $totalRemoved 个冗余动作，" +
            "去重率 ${"%.1f".format(ratePercent)}%"
    }

    /** 重置累计统计（已处理数与已移除数归零）。 */
    fun resetStats() {
        totalProcessed = 0
        totalRemoved = 0
    }

    // =========================================================================
    //  内部工具：坐标 / 文本 / 包名比较
    // =========================================================================

    /** 判断两组坐标的欧氏距离是否不超过阈值（任一坐标缺失返回 false）。 */
    private fun coordNear(x1: Int?, y1: Int?, x2: Int?, y2: Int?, threshold: Int): Boolean {
        if (x1 == null || y1 == null || x2 == null || y2 == null) return false
        val dist = hypot((x1 - x2).toDouble(), (y1 - y2).toDouble())
        return dist <= threshold
    }

    /** 判断两段文本是否「归一化相等但原文不同」（去首尾空白、转小写、去除所有内部空白）。 */
    private fun textNear(t1: String?, t2: String?): Boolean {
        if (t1 == null || t2 == null) return false
        if (t1 == t2) return false // 原文完全相同归精确重复
        val n1 = normalizeText(t1)
        val n2 = normalizeText(t2)
        return n1.isNotEmpty() && n1 == n2
    }

    /** 判断两段文本归一化后是否相等（用于语义重复判定，允许原文不同）。 */
    private fun textEqualsNormalized(t1: String?, t2: String?): Boolean {
        if (t1 == null || t2 == null) return false
        val n1 = normalizeText(t1)
        val n2 = normalizeText(t2)
        return n1.isNotEmpty() && n1 == n2
    }

    /** 文本归一化：去首尾空白、转小写、去除所有内部空白字符。 */
    private fun normalizeText(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), "")

    /** 判断两个字符串是否「归一化相等但原文不同」（去空白、忽略大小写），用于按键名/包名的近似比较。 */
    private fun stringNear(s1: String?, s2: String?): Boolean {
        if (s1 == null || s2 == null) return false
        if (s1 == s2) return false // 原文完全相同归精确重复
        return s1.trim().equals(s2.trim(), ignoreCase = true)
    }

    /** 判断两个「打开应用」动作的目标是否一致（优先按 name，其次按 packageName）。 */
    private fun appTargetEquals(a1: ClawAction, a2: ClawAction): Boolean {
        val n1 = a1.name
        val n2 = a2.name
        if (!n1.isNullOrEmpty() && !n2.isNullOrEmpty()) {
            return n1.trim().equals(n2.trim(), ignoreCase = true)
        }
        val p1 = a1.packageName
        val p2 = a2.packageName
        if (!p1.isNullOrEmpty() && !p2.isNullOrEmpty()) {
            return p1.trim().equals(p2.trim(), ignoreCase = true)
        }
        return false
    }

    // =========================================================================
    //  内部工具：互逆判定
    // =========================================================================

    /** 判断两个按键名是否构成互逆对（对称）。 */
    private fun isInverseKeys(k1: String?, k2: String?): Boolean {
        if (k1 == null || k2 == null) return false
        val u1 = k1.uppercase()
        val u2 = k2.uppercase()
        return INVERSE_KEY_PAIRS.any { (x, y) ->
            (u1 == x && u2 == y) || (u1 == y && u2 == x)
        }
    }

    /** 判断两个滑动方向是否构成互逆对（对称）。 */
    private fun isInverseDirections(d1: String, d2: String): Boolean {
        val u1 = d1.uppercase()
        val u2 = d2.uppercase()
        return INVERSE_SWIPE_DIRECTIONS.any { (x, y) ->
            (u1 == x && u2 == y) || (u1 == y && u2 == x)
        }
    }

    // =========================================================================
    //  内部工具：相似度计算
    // =========================================================================

    /** 计算近似重复对的相似度评分（0-1）。 */
    private fun nearSimilarity(a1: ClawAction, a2: ClawAction): Float {
        val t = a1.type ?: return NEAR_NORMALIZED_SIMILARITY
        return when (t) {
            ActionType.SCREEN_CLICK -> coordSimilarity(a1.x, a1.y, a2.x, a2.y)
            ActionType.SCREEN_LONG_CLICK, ActionType.SCREEN_DOUBLE_CLICK -> {
                val byCoord = coordSimilarity(a1.x, a1.y, a2.x, a2.y)
                if (byCoord > 0f) byCoord else NEAR_NORMALIZED_SIMILARITY
            }
            ActionType.SCREEN_SWIPE -> {
                if (a1.swipeDirectionName != null && a2.swipeDirectionName != null) {
                    NEAR_NORMALIZED_SIMILARITY
                } else {
                    val start = coordSimilarity(a1.x1, a1.y1, a2.x1, a2.y1)
                    val end = coordSimilarity(a1.x2, a1.y2, a2.x2, a2.y2)
                    (start + end) / 2f
                }
            }
            else -> NEAR_NORMALIZED_SIMILARITY
        }
    }

    /** 计算两组坐标的相似度：距离越近相似度越高，范围 (0, 1)。 */
    private fun coordSimilarity(x1: Int?, y1: Int?, x2: Int?, y2: Int?): Float {
        if (x1 == null || y1 == null || x2 == null || y2 == null) return 0f
        val dist = hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
        // 距离为 0 时已属精确重复（不应进入此分支），此处保证结果落在 (0, 1)
        val denom = COORD_PROXIMITY_THRESHOLD.toFloat() * 2f
        return (1f - (dist / denom)).coerceIn(0.01f, 0.99f)
    }
}
