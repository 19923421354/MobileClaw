package com.mobileclaw.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.mobileclaw.app.adapter.ScreenControllerAdapter
import com.mobileclaw.app.adapter.ShellExecutorAdapter
import com.mobileclaw.app.adapter.SystemInfoCollectorAdapter
import com.mobileclaw.app.ai.AIGateway
import com.mobileclaw.app.ai.ClawController
import com.mobileclaw.app.accessibility.ScreenAccessibilityService
import com.mobileclaw.app.accessibility.ScreenController
import com.mobileclaw.app.debug.ShellExecutor
import com.mobileclaw.app.system.SystemInfoCollector
import com.mobileclaw.app.shizuku.ShizukuManager
import com.mobileclaw.app.shizuku.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MobileClaw 应用入口。
 *
 * 负责全局初始化：
 * - 创建并持有 [ClawController] 单例（核心控制器）
 * - 创建各执行器实例与适配器
 * - 监听 Shizuku 状态变化
 * - 提供 AI 配置管理（API Key 等）
 */
class MobileClawApp : Application() {

    companion object {
        private const val TAG = "MobileClawApp"

        lateinit var instance: MobileClawApp
            private set

        /** 全局控制器实例，由 UI 层调用。 */
        var clawController: ClawController? = null
            private set

        /** AI 配置管理。 */
        var aiConfig: AIConfig = AIConfig()
            private set

        /** Shizuku 是否已授权。 */
        var shizukuReady: Boolean = false
            private set
    }

    /** 应用级协程作用域，用于监听 Shizuku 状态等。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "MobileClaw 启动中...")

        // 初始化 Shizuku 状态监听
        ShizukuManager.init()

        // 加载已保存的 AI 配置
        loadSavedConfig()

        // 监听 Shizuku 状态变化
        appScope.launch {
            ShizukuManager.state.collect { state ->
                val ready = state is ShizukuState.Authorized
                onShizukuStateChanged(ready)
            }
        }
    }

    /**
     * 初始化或重建 ClawController。
     *
     * 权限分级策略：
     * - 必需：无障碍服务（用于屏幕操控）
     * - 可选：Shizuku（用于 Shell 执行、应用安装等高级操作）
     *
     * 只有无障碍服务连接就绪即可初始化控制器；
     * Shizuku 不可用时，Shell 相关操作会自动降级为本地执行。
     */
    fun initClawController() {
        val service = ScreenAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "无障碍服务未连接，无法初始化控制器")
            return
        }

        val screenImpl = ScreenController(service)
        val shellImpl = ShellExecutor(this)
        val systemImpl = SystemInfoCollector(this)

        val screenAdapter = ScreenControllerAdapter(screenImpl)
        val shellAdapter = ShellExecutorAdapter(shellImpl, this)
        val systemAdapter = SystemInfoCollectorAdapter(systemImpl, shellImpl)

        val gateway = AIGateway().apply {
            if (aiConfig.apiKey.isNotEmpty()) {
                configure(aiConfig.apiKey, aiConfig.baseUrl, aiConfig.model)
            }
            // 加载评估器配置
            loadEvaluatorConfig(this)
            // 思考模式仅由用户手动控制，不自动根据模型名开启
            // 默认关闭，用户可在设置中手动开启
            thinkingMode = false
        }

        clawController = ClawController(this, gateway, screenAdapter, systemAdapter, shellAdapter)

        // 从 SharedPreferences 加载功能开关
        val prefs = getSharedPreferences("mobileclaw", Context.MODE_PRIVATE)
        clawController?.apply {
            enableActionVerification = prefs.getBoolean("action_verification", true)
            enableIntelligentContext = prefs.getBoolean("intelligent_context", true)
            enableProactiveAnalysis = prefs.getBoolean("proactive_analysis", true)
            enablePipelineOptimization = prefs.getBoolean("pipeline_optimization", true)
            enableEnhancedFeedback = prefs.getBoolean("enhanced_feedback", true)
        }

        Log.i(TAG, "ClawController 初始化完成")
    }

    /**
     * 更新 AI 配置并重新初始化控制器。
     */
    fun updateAIConfig(apiKey: String, baseUrl: String, model: String) {
        aiConfig = AIConfig(apiKey, baseUrl, model)
        // 保存到 SharedPreferences
        getSharedPreferences("mobileclaw", Context.MODE_PRIVATE).edit().apply {
            putString("api_key", apiKey)
            putString("base_url", baseUrl)
            putString("model", model)
            apply()
        }
        // 如果控制器已存在，重建控制器以使用新配置
        // initClawController 会从 aiConfig 创建新网关，并自动加载评估器配置
        if (clawController != null) {
            initClawController()
        }
    }

    /**
     * 更新评估器配置并持久化。
     *
     * 评估器独立于主模型，可以使用不同的 API 和模型。
     * 传入空字符串清除评估器，回退到本地关键词分析。
     */
    fun updateEvaluatorConfig(apiKey: String, baseUrl: String, model: String) {
        getSharedPreferences("mobileclaw", Context.MODE_PRIVATE).edit().apply {
            putString("evaluator_api_key", apiKey)
            putString("evaluator_base_url", baseUrl)
            putString("evaluator_model", model)
            apply()
        }
        // 更新当前网关的评估器
        clawController?.let { controller ->
            try {
                val gatewayField = controller.javaClass.getDeclaredField("gateway")
                gatewayField.isAccessible = true
                val gateway = gatewayField.get(controller) as AIGateway
                gateway.configureEvaluator(apiKey, baseUrl, model)
            } catch (e: Exception) {
                Log.w(TAG, "更新评估器配置失败: ${e.message}")
            }
        }
    }

    /**
     * 从 SharedPreferences 加载评估器配置到指定网关。
     */
    private fun loadEvaluatorConfig(gateway: AIGateway) {
        val prefs = getSharedPreferences("mobileclaw", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("evaluator_api_key", "") ?: ""
        val baseUrl = prefs.getString("evaluator_base_url", "") ?: ""
        val model = prefs.getString("evaluator_model", "") ?: ""
        if (apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()) {
            gateway.configureEvaluator(apiKey, baseUrl, model)
            Log.i(TAG, "评估器配置已加载: $model")
        }
    }

    /**
     * 获取当前评估器配置快照。
     */
    fun getEvaluatorConfig(): Triple<String, String, String> {
        val prefs = getSharedPreferences("mobileclaw", Context.MODE_PRIVATE)
        return Triple(
            prefs.getString("evaluator_api_key", "") ?: "",
            prefs.getString("evaluator_base_url", "") ?: "",
            prefs.getString("evaluator_model", "") ?: ""
        )
    }

    /**
     * 从 SharedPreferences 加载已保存的 AI 配置。
     * 自动迁移已弃用的模型名称到当前可用版本。
     */
    fun loadSavedConfig() {
        val prefs = getSharedPreferences("mobileclaw", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        var baseUrl = prefs.getString("base_url", "") ?: ""
        var model = prefs.getString("model", "") ?: ""

        // 模型名称迁移：将已弃用的模型名称自动替换为当前可用版本
        val migrated = migrateModelName(model)
        if (migrated != model) {
            model = migrated
            // 修正对应的 baseUrl（如旧 deepseek 配置的 URL 可能需要更新）
            baseUrl = migrateBaseUrl(baseUrl, model)
            prefs.edit()
                .putString("model", model)
                .putString("base_url", baseUrl)
                .apply()
            Log.i(TAG, "模型已迁移: $migrated")
        }

        if (apiKey.isNotEmpty()) {
            aiConfig = AIConfig(apiKey, baseUrl, model)
        }
    }

    /**
     * 模型名称迁移映射表。
     * deepseek-chat 和 deepseek-reasoner 已于 2026-07-24 弃用。
     * glm-4-flash 已被 glm-4.7-flash 取代（最新免费模型）。
     * glm-4 已被 glm-4.7 取代。
     */
    private fun migrateModelName(oldModel: String): String = when (oldModel) {
        "deepseek-chat" -> "deepseek-v4-flash"
        "deepseek-reasoner" -> "deepseek-v4-pro"
        "glm-4-flash" -> "glm-4.7-flash"
        "glm-4" -> "glm-4.7"
        else -> oldModel
    }

    /**
     * 根据 migrated 后的模型名修正 baseUrl。
     */
    private fun migrateBaseUrl(oldUrl: String, newModel: String): String {
        // 豆包 API 需要包含 /v3 路径
        if (newModel.startsWith("doubao") && !oldUrl.contains("/v3")) {
            return oldUrl.trimEnd('/') + "/v3"
        }
        return oldUrl
    }

    /**
     * 标记 Shizuku 状态变更。
     * 即使 Shizuku 未就绪，只要无障碍服务已连接，也会初始化控制器。
     */
    fun onShizukuStateChanged(ready: Boolean) {
        shizukuReady = ready
        Log.i(TAG, "Shizuku 状态: ${if (ready) "就绪" else "未就绪"}")
        // 只要无障碍服务已连接，就初始化控制器（Shizuku 可选）
        if (ScreenAccessibilityService.isConnected()) {
            initClawController()
        }
    }

    /**
     * 通知无障碍服务已连接。
     * 这是初始化控制器的关键触发点——无障碍服务是唯一必需的前置条件。
     */
    fun onAccessibilityConnected() {
        Log.i(TAG, "无障碍服务已连接，初始化控制器")
        // 无障碍服务是必需条件，只要它就绪就可以初始化控制器
        initClawController()
    }
}

/**
 * AI 大模型配置。
 *
 * @param apiKey 供应商 API Key
 * @param baseUrl 基础地址（默认智谱 GLM 开放平台，免费模型）
 * @param model 模型名称（默认 glm-4.7-flash，智谱最新免费模型，200K 上下文）
 * @param isThinkingModel 是否为思考模型（如 deepseek-v4-pro），用于联动 AIGateway 的思考模式
 */
data class AIConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val model: String = "glm-4.7-flash",
    val isThinkingModel: Boolean = false
) {
    companion object {
        /**
         * 预设的 AI 供应商配置（按价格从低到高排序）：
         * 1. 智谱 GLM-4.7-Flash（免费，200K 上下文）
         * 2. DeepSeek V4-Flash（极便宜，$0.14/M input）
         * 3. 通义千问 qwen-turbo（便宜）
         * 4. 豆包 doubao-lite-32k（便宜）
         * 5. 通义千问 qwen-plus
         * 6. DeepSeek V4-Pro（思考模型，$0.435/M input）
         * 7. 智谱 GLM-4.7（旗舰，200K 上下文）
         * 8. 智谱 GLM-5.2（最新，Coding 与长程任务专长）
         *
         * 注意：deepseek-chat 和 deepseek-reasoner 已于 2026-07-24 弃用，
         * 分别替换为 deepseek-v4-flash 和 deepseek-v4-pro。
         */
        val PRESETS = listOf(
            AIConfig("", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash"),
            AIConfig("", "https://api.deepseek.com", "deepseek-v4-flash"),
            AIConfig("", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-turbo"),
            AIConfig("", "https://ark.cn-beijing.volces.com/api/v3", "doubao-lite-32k"),
            AIConfig("", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus"),
            AIConfig("", "https://api.deepseek.com", "deepseek-v4-pro", isThinkingModel = true),
            AIConfig("", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7"),
            AIConfig("", "https://open.bigmodel.cn/api/paas/v4", "glm-5.2")
        )
    }
}
