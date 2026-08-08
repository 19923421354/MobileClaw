package com.mobileclaw.app.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 预测式缓存 —— 提前计算并缓存「即将需要」的信息，将感知延迟压缩到极致。
 *
 * 核心理念：用户的手机操作存在强烈的规律性——打开微信后常搜索联系人、
 * 截图后常分享、下班时段常打开导航。如果能基于上下文提前预测用户接下来
 * 需要什么，并预先计算/缓存对应的数据（AI 响应、屏幕状态等），就能在用户
 * 真正发起请求时直接命中缓存，实现「零延迟」响应。
 *
 * 六大核心能力：
 * 1. AI 响应预缓存：根据预测规则，在特定动作后预生成可能需要的 AI 响应。
 *    例如用户在微信中常问「搜索联系人」，可在打开微信时预生成该响应。
 * 2. 屏幕状态预缓存：预测即将打开的应用，提前采集其屏幕状态。
 *    例如预测用户将打开抖音，预先采集首页 UI 元素供 AI 上下文使用。
 * 3. 预测置信度追踪：记录每条预测规则的命中/未命中次数，动态调整置信度，
 *    使预测越来越精准（高频命中规则优先级提升，频繁落空规则被淡化）。
 * 4. 多级缓存（L1/L2）：
 *    - L1（内存级）：[ConcurrentHashMap]，纳秒级访问，容量受限（[maxEntries]）。
 *    - L2（持久级）：模拟磁盘持久化，L1 未命中时回源 L2，访问较慢。
 *      生产环境可替换为 SharedPreferences 或 Room，实现跨进程/跨会话持久化。
 *    - L2 命中后自动提升至 L1，后续访问即可走快速通道。
 * 5. 缓存预热：定时刷新高频访问的热点条目，避免过期后冷启动延迟。
 *    由后台协程周期性执行 [warmup]，对热点键「续命」保持常驻。
 * 6. 自适应 TTL：访问频次达到阈值（[hotKeyThreshold] 次）的热点条目自动获得
 *    2 倍 TTL（[hotKeyTtlMultiplier]），冷门条目按基础 TTL 过期，
 *    实现「越常用越持久」的弹性生命周期。
 *
 * 线程安全：
 * - L1/L2 存储均使用 [ConcurrentHashMap]，可被多线程并发调用。
 * - 统计计数使用 @Volatile + private set，保证可见性的同时禁止外部修改。
 * - 典型场景：UI 线程读写缓存、后台协程执行定时预热与过期清理。
 *
 * 使用方式：
 * ```
 * val cache = PredictiveCache()
 * // 手动存入缓存
 * cache.put("wechat_search_response", "搜索结果...", ttl = 300_000L)
 * // 添加预测规则：在微信中、晚间时段，预测需要搜索响应
 * cache.addPredictionRule(PredictionRule(
 *     condition = "app=com.tencent.mm;segment=evening",
 *     predictedKey = "wechat_search_response",
 *     confidence = 0.6f
 * ))
 * // 根据上下文预测可能需要的缓存键
 * val predicted = cache.predict("after_open", "com.tencent.mm", 21)
 * // 预缓存预测的条目（标记预测来源）
 * cache.putPredicted("wechat_search_response", "预生成响应...", predictedFrom = "app=com.tencent.mm;segment=evening")
 * // 获取缓存（命中则返回，未命中返回 null）
 * val value = cache.get("wechat_search_response")
 * // 反馈预测结果，持续校准置信度
 * cache.recordPredictionHit("wechat_search_response")
 * ```
 */
class PredictiveCache(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val tag = "PredictiveCache"

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 缓存条目。
     *
     * @property key 缓存键
     * @property value 缓存值
     * @property createdAt 创建时间戳（毫秒）
     * @property lastAccessed 最后访问时间戳（毫秒）
     * @property accessCount 累计访问次数
     * @property ttl 生存时间（毫秒），超时后条目失效
     * @property predictedFrom 预测来源（触发预缓存的规则条件），手动存入时为 null
     * @property hitCount 缓存命中次数（被 get 命中的次数）
     */
    data class CacheEntry(
        val key: String,
        val value: String,
        val createdAt: Long,
        val lastAccessed: Long,
        val accessCount: Int,
        val ttl: Long,
        val predictedFrom: String?,
        val hitCount: Int
    ) {
        /** 是否已过期。 */
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
            return now - createdAt > ttl
        }

        /** 是否为热点键（访问次数达到阈值）。 */
        fun isHot(threshold: Int): Boolean = accessCount >= threshold
    }

    /**
     * 预测规则 —— 描述「在何种条件下，预测需要哪个缓存键」。
     *
     * 条件格式为分号分隔的 key=value 对，例如：
     * "app=com.tencent.mm;hour=21;segment=evening;context=after_open"
     * 支持的键：app（应用包名）、hour（小时 0-23）、segment（时段）、context（自定义上下文）。
     * 空条件视为通配，始终匹配。
     *
     * @property condition 触发条件（分号分隔的 key=value 对）
     * @property predictedKey 预测需要的缓存键
     * @property confidence 初始置信度（0.0-1.0）
     * @property hitCount 预测命中次数
     * @property missCount 预测未命中次数
     */
    data class PredictionRule(
        val condition: String,
        val predictedKey: String,
        val confidence: Float,
        var hitCount: Int = 0,
        var missCount: Int = 0
    ) {
        /**
         * 当前置信度：融合初始置信度与历史命中率动态计算。
         * 初始置信度占 40%，历史命中率占 60%，数据越多越倾向于实际表现。
         */
        val currentConfidence: Float
            get() {
                val total = hitCount + missCount
                return if (total > 0) {
                    confidence * 0.4f + (hitCount.toFloat() / total) * 0.6f
                } else {
                    confidence
                }
            }

        /** 预测摘要（用于 UI 展示）。 */
        fun summary(): String =
            "[$condition] -> $predictedKey (" +
                    "置信度${"%.0f".format(currentConfidence * 100)}%, " +
                    "命中$hitCount/未中$missCount)"
    }

    /**
     * 缓存统计快照。
     *
     * @property totalEntries 当前缓存条目总数（L1 与 L2 去重）
     * @property hits 缓存命中次数
     * @property misses 缓存未命中次数
     * @property hitRate 缓存命中率（0.0-1.0）
     * @property predictionHits 预测命中次数
     * @property predictionMisses 预测未命中次数
     */
    data class CacheStats(
        val totalEntries: Int,
        val hits: Int,
        val misses: Int,
        val hitRate: Float,
        val predictionHits: Int,
        val predictionMisses: Int
    )

    // ============================================================
    // 配置常量
    // ============================================================

    /** 默认 TTL（毫秒）：5 分钟。 */
    private val defaultTtlMs: Long = 5 * 60 * 1000L

    /** 最大缓存条目数（L1）。 */
    private val maxEntries: Int = 200

    /** 热点键访问次数阈值：达到 5 次即为热点。 */
    private val hotKeyThreshold: Int = 5

    /** 热点键 TTL 倍数：热点条目获得 2 倍 TTL。 */
    private val hotKeyTtlMultiplier: Int = 2

    /** 预热间隔（毫秒）：默认 10 分钟。 */
    private val warmupIntervalMs: Long = 10 * 60 * 1000L

    /** 过期清理间隔（毫秒）：默认 5 分钟。 */
    private val cleanupIntervalMs: Long = 5 * 60 * 1000L

    /** 最低有效置信度阈值，低于此值的预测规则不参与预测。 */
    private val minConfidence: Float = 0.1f

    // ============================================================
    // 存储结构（全部线程安全）
    // ============================================================

    /** L1 缓存（内存级，快速访问）。 */
    private val l1Cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * L2 缓存（持久级，较慢）。
     *
     * 此处用内存 Map 模拟持久化存储；生产环境应替换为
     * SharedPreferences 或 Room，实现跨进程/跨会话的真正持久化。
     */
    private val l2Cache = ConcurrentHashMap<String, CacheEntry>()

    /** 预测规则存储，键 = "condition|predictedKey"。 */
    private val predictionRules = ConcurrentHashMap<String, PredictionRule>()

    /**
     * 待验证的预测记录：predictedKey -> 触发该预测的规则条件。
     * 用于在 [recordPredictionHit] / [recordPredictionMiss] 时回溯关联的规则。
     */
    private val pendingPredictions = ConcurrentHashMap<String, String>()

    // ============================================================
    // 统计计数
    // ============================================================

    /** 缓存命中次数。 */
    @Volatile
    var hits: Int = 0
        private set

    /** 缓存未命中次数。 */
    @Volatile
    var misses: Int = 0
        private set

    /** 预测命中次数。 */
    @Volatile
    var predictionHits: Int = 0
        private set

    /** 预测未命中次数。 */
    @Volatile
    var predictionMisses: Int = 0
        private set

    /** 定时预热协程 Job。 */
    private var warmupJob: Job? = null

    /** 定时清理协程 Job。 */
    private var cleanupJob: Job? = null

    // ============================================================
    // 初始化与生命周期
    // ============================================================

    init {
        startPeriodicWarmup()
        startPeriodicCleanup()
    }

    /** 启动定时预热协程，周期性刷新热点缓存条目。 */
    private fun startPeriodicWarmup() {
        warmupJob?.cancel()
        warmupJob = scope.launch {
            while (true) {
                delay(warmupIntervalMs)
                try {
                    warmup()
                } catch (e: Exception) {
                    Log.w(tag, "定时预热异常: ${e.message}")
                }
            }
        }
    }

    /** 启动定时清理协程，周期性移除过期条目。 */
    private fun startPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (true) {
                delay(cleanupIntervalMs)
                try {
                    cleanupExpired()
                } catch (e: Exception) {
                    Log.w(tag, "定时清理异常: ${e.message}")
                }
            }
        }
    }

    /**
     * 取消所有定时协程（通常在组件销毁时调用）。
     * 注意：此方法不会清空已缓存的数据。
     */
    fun dispose() {
        warmupJob?.cancel()
        cleanupJob?.cancel()
        warmupJob = null
        cleanupJob = null
    }

    // ============================================================
    // 核心缓存操作
    // ============================================================

    /**
     * 存入缓存。
     *
     * 同时写入 L1 和 L2。如果该键已是热点（访问次数 >= [hotKeyThreshold]），
     * 则自动应用 2 倍 TTL（自适应 TTL）。保留已有访问统计与命中次数。
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param ttl 生存时间（毫秒），默认为 5 分钟
     */
    fun put(key: String, value: String, ttl: Long = defaultTtlMs) {
        val now = System.currentTimeMillis()
        // 检查是否已是热点，自适应延长 TTL
        val existing = l1Cache[key] ?: l2Cache[key]
        val effectiveTtl = if (existing != null && existing.accessCount >= hotKeyThreshold) {
            ttl * hotKeyTtlMultiplier
        } else {
            ttl
        }

        val entry = CacheEntry(
            key = key,
            value = value,
            createdAt = now,
            lastAccessed = now,
            accessCount = existing?.accessCount ?: 0,
            ttl = effectiveTtl,
            predictedFrom = existing?.predictedFrom,
            hitCount = existing?.hitCount ?: 0
        )

        l1Cache[key] = entry
        l2Cache[key] = entry

        // LRU 淘汰：超过最大条目数时移除最久未访问的
        if (l1Cache.size > maxEntries) {
            val oldest = l1Cache.entries.minByOrNull { it.value.lastAccessed }
            oldest?.let { l1Cache.remove(it.key) }
        }

        Log.d(tag, "存入缓存: $key (TTL=${effectiveTtl}ms, L1=${l1Cache.size}/$maxEntries)")
    }

    /**
     * 存入预测式预缓存的条目（标记预测来源）。
     *
     * 与 [put] 的区别在于会记录 [predictedFrom]，标识该条目由哪条预测规则触发，
     * 便于后续追踪预测命中率与调优规则置信度。
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param ttl 生存时间（毫秒），默认为 5 分钟
     * @param predictedFrom 触发预缓存的预测规则条件
     */
    fun putPredicted(key: String, value: String, ttl: Long = defaultTtlMs, predictedFrom: String) {
        val now = System.currentTimeMillis()
        val existing = l1Cache[key] ?: l2Cache[key]
        val effectiveTtl = if (existing != null && existing.accessCount >= hotKeyThreshold) {
            ttl * hotKeyTtlMultiplier
        } else {
            ttl
        }

        val entry = CacheEntry(
            key = key,
            value = value,
            createdAt = now,
            lastAccessed = now,
            accessCount = existing?.accessCount ?: 0,
            ttl = effectiveTtl,
            predictedFrom = predictedFrom,
            hitCount = existing?.hitCount ?: 0
        )

        l1Cache[key] = entry
        l2Cache[key] = entry

        if (l1Cache.size > maxEntries) {
            val oldest = l1Cache.entries.minByOrNull { it.value.lastAccessed }
            oldest?.let { l1Cache.remove(it.key) }
        }

        Log.d(tag, "预缓存: $key <- [$predictedFrom] (TTL=${effectiveTtl}ms)")
    }

    /**
     * 获取缓存值。
     *
     * 查询顺序：L1 -> L2 -> 返回 null。
     * 命中时更新访问次数与最后访问时间；当访问次数跨过热点阈值时，
     * 自动将 TTL 扩展为 2 倍（自适应 TTL）。L2 命中后自动提升至 L1。
     *
     * @param key 缓存键
     * @return 缓存值，未命中或已过期返回 null
     */
    fun get(key: String): String? {
        val now = System.currentTimeMillis()

        // 1. 查询 L1 缓存
        val l1Entry = l1Cache[key]
        if (l1Entry != null) {
            if (l1Entry.isExpired(now)) {
                l1Cache.remove(key)
                l2Cache.remove(key)
                misses++
                Log.d(tag, "L1 过期: $key")
                return null
            }
            // 命中 L1，更新访问统计
            val updated = touchEntry(l1Entry, now)
            l1Cache[key] = updated
            l2Cache[key] = updated
            hits++
            Log.d(tag, "L1 命中: $key (访问${updated.accessCount}次)")
            return updated.value
        }

        // 2. 查询 L2 缓存（回源）
        val l2Entry = l2Cache[key]
        if (l2Entry != null) {
            if (l2Entry.isExpired(now)) {
                l2Cache.remove(key)
                misses++
                Log.d(tag, "L2 过期: $key")
                return null
            }
            // L2 命中，提升到 L1 并更新访问统计
            val updated = touchEntry(l2Entry, now)
            l1Cache[key] = updated
            l2Cache[key] = updated
            hits++
            Log.d(tag, "L2 命中并提升至 L1: $key")
            return updated.value
        }

        // 3. 未命中
        misses++
        Log.d(tag, "缓存未命中: $key")
        return null
    }

    /**
     * 更新条目的访问统计（访问次数 +1、更新最后访问时间、命中次数 +1）。
     * 当访问次数跨过热点阈值时，自动扩展 TTL（自适应 TTL）。
     *
     * @return 更新后的条目
     */
    private fun touchEntry(entry: CacheEntry, now: Long): CacheEntry {
        val newAccessCount = entry.accessCount + 1
        // 自适应 TTL：跨过热点阈值时翻倍 TTL
        val newTtl = if (newAccessCount == hotKeyThreshold) {
            Log.d(tag, "热点升级: ${entry.key} (访问${newAccessCount}次, TTL x$hotKeyTtlMultiplier)")
            entry.ttl * hotKeyTtlMultiplier
        } else {
            entry.ttl
        }
        return entry.copy(
            lastAccessed = now,
            accessCount = newAccessCount,
            ttl = newTtl,
            hitCount = entry.hitCount + 1
        )
    }

    // ============================================================
    // 预测操作
    // ============================================================

    /**
     * 根据上下文预测可能需要的缓存键列表。
     *
     * 预测流程：
     * 1. 将当前上下文解析为条件键值对（app、hour、segment、context）。
     * 2. 遍历所有预测规则，筛选条件满足且置信度达标的规则。
     * 3. 按当前置信度降序排列，取预测键列表。
     * 4. 将命中的预测键记入待验证集合，供后续 [recordPredictionHit] /
     *    [recordPredictionMiss] 回溯关联规则。
     *
     * 条件匹配规则（所有键值对均需满足才算匹配）：
     * - app：与当前前台应用包名精确匹配
     * - hour：与当前小时精确匹配
     * - segment：与当前时段（morning/work/evening/night）匹配
     * - context：与自定义上下文字符串精确匹配
     *
     * @param context 自定义上下文标识（如 "after_screenshot"、"chat_ended"）
     * @param currentApp 当前前台应用包名，可为 null
     * @param timeOfDay 当前小时（0-23，超出会自动取模）
     * @return 预测的缓存键列表（按置信度降序），无匹配时返回空列表
     */
    fun predict(context: String, currentApp: String?, timeOfDay: Int): List<String> {
        val hour = ((timeOfDay % 24) + 24) % 24
        val segment = segmentOf(hour)

        // 构建当前上下文条件映射
        val contextMap = HashMap<String, String>()
        contextMap["hour"] = hour.toString()
        contextMap["segment"] = segment
        if (currentApp != null) contextMap["app"] = currentApp
        if (context.isNotEmpty()) contextMap["context"] = context

        // 筛选条件满足且置信度达标的规则，按置信度降序排列
        val matched = predictionRules.values
            .filter { rule ->
                rule.currentConfidence >= minConfidence && matchesCondition(rule.condition, contextMap)
            }
            .sortedByDescending { it.currentConfidence }

        // 记录待验证预测，并收集预测键
        val predictedKeys = ArrayList<String>(matched.size)
        for (rule in matched) {
            pendingPredictions[rule.predictedKey] = rule.condition
            predictedKeys.add(rule.predictedKey)
        }

        if (predictedKeys.isNotEmpty()) {
            Log.d(tag, "预测命中 ${predictedKeys.size} 条规则: $predictedKeys")
        }
        return predictedKeys
    }

    /**
     * 判断规则条件是否与当前上下文匹配。
     *
     * 条件格式为分号分隔的 key=value 对，所有键值对均需满足才算匹配。
     * 空条件视为始终匹配（通配）。
     */
    private fun matchesCondition(condition: String, contextMap: Map<String, String>): Boolean {
        if (condition.isBlank()) return true
        val pairs = condition.split(";")
        for (pair in pairs) {
            val kv = pair.split("=", limit = 2)
            if (kv.size != 2) continue
            val condKey = kv[0].trim()
            val condValue = kv[1].trim()
            val actual = contextMap[condKey] ?: return false
            if (actual != condValue) return false
        }
        return true
    }

    /**
     * 记录预测命中（当预测的缓存键被实际访问时调用）。
     *
     * 更新全局预测命中计数，并关联更新对应规则的 hitCount，
     * 使该规则的 [PredictionRule.currentConfidence] 在后续预测中提升。
     *
     * @param key 被实际访问的预测缓存键
     */
    fun recordPredictionHit(key: String) {
        predictionHits++
        val condition = pendingPredictions.remove(key)
        if (condition != null) {
            val ruleKey = "$condition|$key"
            predictionRules[ruleKey]?.let { it.hitCount++ }
        }
        Log.d(tag, "预测命中反馈: $key (累计命中 $predictionHits)")
    }

    /**
     * 记录预测未命中（当预测的缓存键未被使用或预测错误时调用）。
     *
     * 更新全局预测未命中计数，并关联更新对应规则的 missCount，
     * 使该规则的 [PredictionRule.currentConfidence] 在后续预测中下降。
     *
     * @param key 未被使用的预测缓存键
     */
    fun recordPredictionMiss(key: String) {
        predictionMisses++
        val condition = pendingPredictions.remove(key)
        if (condition != null) {
            val ruleKey = "$condition|$key"
            predictionRules[ruleKey]?.let { it.missCount++ }
        }
        Log.d(tag, "预测未命中反馈: $key (累计未中 $predictionMisses)")
    }

    /**
     * 添加预测规则。
     *
     * 若已存在相同 condition + predictedKey 的规则，则保留已有规则（含历史命中/未命中记录），
     * 不覆盖统计数据。如需更新置信度，请先移除旧规则再添加。
     *
     * @param rule 预测规则
     */
    fun addPredictionRule(rule: PredictionRule) {
        val ruleKey = "${rule.condition}|${rule.predictedKey}"
        predictionRules.compute(ruleKey) { _, existing ->
            if (existing == null) {
                rule
            } else {
                // 已存在相同条件的规则：保留历史命中/未命中记录
                existing
            }
        }
        Log.d(tag, "添加预测规则: ${rule.condition} -> ${rule.predictedKey} (置信度${rule.confidence})")
    }

    /** 获取所有预测规则（按当前置信度降序，用于 UI 展示）。 */
    fun getPredictionRules(): List<PredictionRule> =
        predictionRules.values.sortedByDescending { it.currentConfidence }

    // ============================================================
    // 缓存管理与维护
    // ============================================================

    /**
     * 缓存预热 —— 刷新热点条目，避免过期后冷启动延迟。
     *
     * 遍历所有热点键（访问次数 >= [hotKeyThreshold]），重置其创建时间，
     * 相当于「续命」，保持高频数据常驻缓存。
     *
     * 生产环境中，预热应从数据源重新拉取最新值；此处为通用缓存，
     * 采用续命策略模拟刷新。由定时协程周期性调用，也可手动触发。
     */
    fun warmup() {
        val now = System.currentTimeMillis()
        var refreshed = 0

        // 收集所有热点键（L1 和 L2 合并去重）
        val allKeys = l1Cache.keys.union(l2Cache.keys)
        for (key in allKeys) {
            val entry = l1Cache[key] ?: l2Cache[key] ?: continue
            if (entry.accessCount >= hotKeyThreshold) {
                // 续命：重置创建时间，保持热点数据常驻
                val refreshedEntry = entry.copy(createdAt = now, lastAccessed = now)
                l1Cache[key] = refreshedEntry
                l2Cache[key] = refreshedEntry
                refreshed++
            }
        }

        if (refreshed > 0) {
            Log.d(tag, "缓存预热: 刷新 $refreshed 条热点条目")
        }
    }

    /**
     * 失效指定缓存键（从 L1 和 L2 中移除）。
     *
     * @param key 要失效的缓存键
     */
    fun invalidate(key: String) {
        l1Cache.remove(key)
        l2Cache.remove(key)
        pendingPredictions.remove(key)
        Log.d(tag, "失效缓存: $key")
    }

    /**
     * 失效所有缓存（清空 L1、L2 及待验证预测记录）。
     * 统计计数不会被重置（保留历史命中率数据）。
     */
    fun invalidateAll() {
        l1Cache.clear()
        l2Cache.clear()
        pendingPredictions.clear()
        Log.d(tag, "已清空所有缓存")
    }

    /**
     * 获取热点键列表（访问次数 >= [hotKeyThreshold]）。
     *
     * @param limit 返回的最大条数，默认 10
     * @return 按访问次数降序排列的热点键列表
     */
    fun getHotKeys(limit: Int = 10): List<String> {
        return l1Cache.values
            .filter { it.accessCount >= hotKeyThreshold }
            .sortedByDescending { it.accessCount }
            .take(limit)
            .map { it.key }
    }

    /**
     * 清理已过期的缓存条目（L1 和 L2 同步清理）。
     * 由定时协程周期性调用，也可手动触发。
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        var removed = 0

        // 清理 L1 过期条目
        val l1Iterator = l1Cache.entries.iterator()
        while (l1Iterator.hasNext()) {
            val entry = l1Iterator.next().value
            if (entry.isExpired(now)) {
                l1Iterator.remove()
                removed++
            }
        }

        // 清理 L2 过期条目
        val l2Iterator = l2Cache.entries.iterator()
        while (l2Iterator.hasNext()) {
            val entry = l2Iterator.next().value
            if (entry.isExpired(now)) {
                l2Iterator.remove()
            }
        }

        // 清理待验证预测记录中已不存在的键
        val pendingIterator = pendingPredictions.entries.iterator()
        while (pendingIterator.hasNext()) {
            val entry = pendingIterator.next()
            if (!l1Cache.containsKey(entry.key) && !l2Cache.containsKey(entry.key)) {
                pendingIterator.remove()
            }
        }

        if (removed > 0) {
            Log.d(tag, "清理过期条目: $removed 条")
        }
    }

    // ============================================================
    // 统计与查询
    // ============================================================

    /**
     * 获取缓存统计快照。
     *
     * @return 包含条目数、命中率、预测命中/未命中的统计快照
     */
    fun getStats(): CacheStats {
        val total = hits + misses
        val hitRate = if (total > 0) hits.toFloat() / total else 0f
        // L1 与 L2 去重统计条目数
        val totalEntries = l1Cache.keys.union(l2Cache.keys).size
        return CacheStats(
            totalEntries = totalEntries,
            hits = hits,
            misses = misses,
            hitRate = hitRate,
            predictionHits = predictionHits,
            predictionMisses = predictionMisses
        )
    }

    /**
     * 获取统计摘要字符串（用于 UI 展示与调试）。
     */
    fun getSummary(): String {
        val stats = getStats()
        val hotCount = l1Cache.values.count { it.accessCount >= hotKeyThreshold }
        val rate = if (stats.hits + stats.misses > 0) {
            "%.1f%%".format(stats.hitRate * 100)
        } else {
            "N/A"
        }
        val predTotal = stats.predictionHits + stats.predictionMisses
        val predRate = if (predTotal > 0) {
            "%.1f%%".format(stats.predictionHits.toFloat() / predTotal * 100)
        } else {
            "N/A"
        }
        return "预测缓存: ${stats.totalEntries}/$maxEntries 条 | " +
                "L1=${l1Cache.size} L2=${l2Cache.size} 热点=$hotCount | " +
                "命中率: $rate | " +
                "预测命中: ${stats.predictionHits}/$predTotal ($predRate) | " +
                "规则: ${predictionRules.size}"
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 根据小时（0-23）获取时段标识。
     *
     * - morning：早晨日常（6-8 点）
     * - work：工作时段（9-17 点）
     * - evening：晚间休闲（18-21 点）
     * - night：夜间模式（22-5 点）
     */
    private fun segmentOf(hour: Int): String = when (hour) {
        in 6..8 -> "morning"
        in 9..17 -> "work"
        in 18..21 -> "evening"
        else -> "night"
    }

    // ============================================================
    // 重置
    // ============================================================

    /**
     * 清空所有缓存数据、预测规则与统计计数（定时协程不会被取消）。
     */
    fun clear() {
        l1Cache.clear()
        l2Cache.clear()
        predictionRules.clear()
        pendingPredictions.clear()
        hits = 0
        misses = 0
        predictionHits = 0
        predictionMisses = 0
        Log.d(tag, "已清空所有缓存数据与统计")
    }
}
