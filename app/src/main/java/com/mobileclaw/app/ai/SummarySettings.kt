package com.mobileclaw.app.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 总结设置管理器 —— 管理所有与总结相关的可配置参数。
 *
 * 核心功能：
 * 1. **总结模式**：无上限 / 智能节省 / 自定义
 * 2. **总结频率**：每 N 条消息自动总结（N=1/2/3/5/10 或手动）
 * 3. **总结字数**：目标字数（50/100/200/500/1000），字数越少 Token 越省
 * 4. **Token 输出上限**：自定义 AI 输出的最大 Token 数
 * 5. **记忆持久化**：对话记忆保存到 SharedPreferences，跨会话保留
 * 6. **自动总结开关**：自动总结/手动总结
 *
 * 所有设置通过 SharedPreferences 持久化，Key 统一以 `summary_` 前缀。
 */
object SummarySettings {

    private const val TAG = "SummarySettings"
    private const val PREFS_NAME = "mobileclaw"

    // =========================================================================
    //  常量定义
    // =========================================================================

    /** 总结模式 */
    enum class SummaryMode(val value: String, val displayName: String) {
        SMART_SAVING("smart_saving", "智能节省模式"),
        UNLIMITED("unlimited", "无上限模式"),
        CUSTOM("custom", "自定义模式");

        companion object {
            fun fromValue(value: String): SummaryMode =
                entries.find { it.value == value } ?: SMART_SAVING
        }
    }

    /** 总结频率选项 */
    enum class SummaryFrequency(val messageCount: Int, val displayName: String) {
        EVERY_1(1, "每条消息"),
        EVERY_2(2, "每2条消息"),
        EVERY_3(3, "每3条消息"),
        EVERY_5(5, "每5条消息"),
        EVERY_10(10, "每10条消息"),
        MANUAL(0, "仅手动总结");

        companion object {
            fun fromCount(count: Int): SummaryFrequency =
                entries.find { it.messageCount == count } ?: MANUAL
        }
    }

    /** 总结字数选项 */
    enum class SummaryWordCount(val targetWords: Int, val displayName: String) {
        MINI(50, "精简 (约50字)"),
        SHORT(100, "简短 (约100字)"),
        MEDIUM(200, "标准 (约200字)"),
        LONG(500, "详细 (约500字)"),
        EXTRA(1000, "完整 (约1000字)");

        companion object {
            fun fromCount(count: Int): SummaryWordCount =
                entries.find { it.targetWords == count } ?: MEDIUM
        }
    }

    /** Token 输出上限选项 */
    enum class TokenLimit(val maxTokens: Int, val displayName: String) {
        TINY(512, "512 Token"),
        SMALL(1024, "1K Token"),
        MEDIUM(2048, "2K Token"),
        LARGE(4096, "4K Token"),
        EXTRA(8192, "8K Token"),
        UNLIMITED(0, "无上限");

        companion object {
            fun fromTokens(tokens: Int): TokenLimit =
                entries.find { it.maxTokens == tokens } ?: MEDIUM
        }
    }

    // =========================================================================
    //  SharedPreferences 键名
    // =========================================================================

    private const val KEY_SUMMARY_MODE = "summary_mode"
    private const val KEY_SUMMARY_FREQUENCY = "summary_frequency"
    private const val KEY_SUMMARY_WORD_COUNT = "summary_word_count"
    private const val KEY_TOKEN_LIMIT = "summary_token_limit"
    private const val KEY_AUTO_SUMMARY_ENABLED = "summary_auto_enabled"
    private const val KEY_MEMORY_PERSISTENT = "summary_memory_persistent"
    private const val KEY_MEMORY_DATA = "summary_memory_data"

    // =========================================================================
    //  默认值
    // =========================================================================

    private const val DEFAULT_SUMMARY_MODE = "smart_saving"
    private const val DEFAULT_SUMMARY_FREQUENCY = 3
    private const val DEFAULT_SUMMARY_WORD_COUNT = 200
    private const val DEFAULT_TOKEN_LIMIT = 2048
    private const val DEFAULT_AUTO_SUMMARY = true
    private const val DEFAULT_MEMORY_PERSISTENT = true

    // =========================================================================
    //  getter / setter
    // =========================================================================

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- 总结模式 ----

    /** 获取当前总结模式。 */
    fun getSummaryMode(context: Context): SummaryMode {
        val value = prefs(context).getString(KEY_SUMMARY_MODE, DEFAULT_SUMMARY_MODE) ?: DEFAULT_SUMMARY_MODE
        return SummaryMode.fromValue(value)
    }

    /** 设置总结模式。 */
    fun setSummaryMode(context: Context, mode: SummaryMode) {
        prefs(context).edit().putString(KEY_SUMMARY_MODE, mode.value).apply()
        Log.d(TAG, "总结模式已设置为: ${mode.displayName}")
    }

    // ---- 总结频率 ----

    /** 获取当前总结频率（消息间隔数）。 */
    fun getSummaryFrequency(context: Context): SummaryFrequency {
        val count = prefs(context).getInt(KEY_SUMMARY_FREQUENCY, DEFAULT_SUMMARY_FREQUENCY)
        return SummaryFrequency.fromCount(count)
    }

    /** 设置总结频率。 */
    fun setSummaryFrequency(context: Context, frequency: SummaryFrequency) {
        prefs(context).edit().putInt(KEY_SUMMARY_FREQUENCY, frequency.messageCount).apply()
        Log.d(TAG, "总结频率已设置为: ${frequency.displayName}")
    }

    // ---- 总结字数 ----

    /** 获取目标总结字数。 */
    fun getSummaryWordCount(context: Context): SummaryWordCount {
        val count = prefs(context).getInt(KEY_SUMMARY_WORD_COUNT, DEFAULT_SUMMARY_WORD_COUNT)
        return SummaryWordCount.fromCount(count)
    }

    /** 设置目标总结字数。 */
    fun setSummaryWordCount(context: Context, wordCount: SummaryWordCount) {
        prefs(context).edit().putInt(KEY_SUMMARY_WORD_COUNT, wordCount.targetWords).apply()
        Log.d(TAG, "总结字数已设置为: ${wordCount.displayName}")
    }

    // ---- Token 输出上限 ----

    /** 获取 Token 输出上限。 */
    fun getTokenLimit(context: Context): TokenLimit {
        val tokens = prefs(context).getInt(KEY_TOKEN_LIMIT, DEFAULT_TOKEN_LIMIT)
        return TokenLimit.fromTokens(tokens)
    }

    /** 设置 Token 输出上限。 */
    fun setTokenLimit(context: Context, limit: TokenLimit) {
        prefs(context).edit().putInt(KEY_TOKEN_LIMIT, limit.maxTokens).apply()
        Log.d(TAG, "Token 上限已设置为: ${limit.displayName}")
    }

    /** 获取有效 Token 上限（无上限模式返回 Int.MAX_VALUE）。 */
    fun getEffectiveTokenLimit(context: Context): Int {
        val mode = getSummaryMode(context)
        return when (mode) {
            SummaryMode.UNLIMITED -> Int.MAX_VALUE
            SummaryMode.SMART_SAVING -> getTokenLimit(context).maxTokens / 2
            SummaryMode.CUSTOM -> getTokenLimit(context).maxTokens
        }
    }

    // ---- 自动总结开关 ----

    /** 是否启用自动总结。 */
    fun isAutoSummaryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SUMMARY_ENABLED, DEFAULT_AUTO_SUMMARY)

    /** 设置自动总结开关。 */
    fun setAutoSummaryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SUMMARY_ENABLED, enabled).apply()
        Log.d(TAG, "自动总结: ${if (enabled) "开启" else "关闭"}")
    }

    // ---- 记忆持久化 ----

    /** 是否启用记忆持久化（跨会话保留）。 */
    fun isMemoryPersistent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEMORY_PERSISTENT, DEFAULT_MEMORY_PERSISTENT)

    /** 设置记忆持久化开关。 */
    fun setMemoryPersistent(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEMORY_PERSISTENT, enabled).apply()
        Log.d(TAG, "记忆持久化: ${if (enabled) "开启" else "关闭"}")
    }

    // ---- 记忆数据持久化 ----

    /** 将记忆数据保存到 SharedPreferences。 */
    fun saveMemoryData(context: Context, jsonData: String) {
        prefs(context).edit().putString(KEY_MEMORY_DATA, jsonData).apply()
    }

    /** 从 SharedPreferences 读取记忆数据。 */
    fun loadMemoryData(context: Context): String? {
        return prefs(context).getString(KEY_MEMORY_DATA, null)
    }

    /** 清除持久化的记忆数据。 */
    fun clearMemoryData(context: Context) {
        prefs(context).edit().remove(KEY_MEMORY_DATA).apply()
    }

    // =========================================================================
    //  模式联动
    // =========================================================================

    /**
     * 设置无上限模式。
     * 自动关闭智能节省模式，强制设置总结模式为 UNLIMITED。
     */
    fun enableUnlimitedMode(context: Context) {
        setSummaryMode(context, SummaryMode.UNLIMITED)
        setAutoSummaryEnabled(context, false)
        Log.d(TAG, "无上限模式已开启，智能节省 Token 已自动关闭")
    }

    /**
     * 设置智能节省模式。
     * 自动关闭无上限模式，开启自动总结和省 Token 配置。
     */
    fun enableSmartSavingMode(context: Context) {
        setSummaryMode(context, SummaryMode.SMART_SAVING)
        setAutoSummaryEnabled(context, true)
        Log.d(TAG, "智能节省模式已开启")
    }

    /**
     * 获取当前模式对应的摘要 Token 预算。
     * 用于传递给 ConversationSummarizer.compressHistory()。
     */
    fun getSummaryTokenBudget(context: Context): Int {
        val mode = getSummaryMode(context)
        return when (mode) {
            SummaryMode.UNLIMITED -> 2000
            SummaryMode.SMART_SAVING -> {
                // 字数越少 Token 越少
                val wordCount = getSummaryWordCount(context)
                (wordCount.targetWords * 1.5).toInt() // 中文约 1.5 Token/字
            }
            SummaryMode.CUSTOM -> {
                val tokenLimit = getTokenLimit(context)
                if (tokenLimit.maxTokens == 0) 2000 else tokenLimit.maxTokens
            }
        }
    }
}