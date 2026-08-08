package com.mobileclaw.app.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ClawController - 小龙虾主控制器（核心编排器）
 *
 * 作为整个 AI 操控手机的「大脑」，协调以下组件：
 * - [AIGateway]：将自然语言指令解析为结构化 [ClawAction]
 * - [ScreenController]：执行屏幕交互（点击/滑动/输入/按键/截屏等）
 * - [SystemInfoCollector]：采集手机状态与系统信息
 * - [ShellExecutor]：执行 shell 命令、文件读写、通知等特权操作
 *
 * 完整处理流程：
 * 1. 采集当前手机状态（前台应用、屏幕信息、系统信息摘要）
 * 2. 调用 AI 解析用户指令为 [ClawCommandResult]
 * 3. 依次将每个 [ClawAction] 分发给对应执行器
 * 4. 收集执行结果并反馈给 AI
 * 5. 若任务未完成，循环请求下一批指令（agentic loop，带最大迭代限制）
 * 6. 将最终结果通过 [ClawCallback] 回调通知 UI
 *
 * 设计要点：
 * - 使用 [Mutex] 实现任务队列串行化，避免并发操控手机导致冲突。
 * - 使用 [SupervisorJob] + 协程管理异步流程，[cancel] 可随时中断当前任务。
 * - 通过 [ClawCallback] 与 [statusFlow] 双通道通知 UI 更新。
 * - 全程记录操作日志（[OperationLog]）。
 *
 * @param gateway AI 网关实例
 * @param screen 屏幕控制器
 * @param systemInfo 系统信息采集器
 * @param shell Shell 执行器
 * @param scope 外部传入的协程作用域（可选），默认自带一个 SupervisorJob 作用域
 */
class ClawController(
    private val context: Context,
    private val gateway: AIGateway,
    private val screen: ScreenController,
    private val systemInfo: SystemInfoCollector,
    private val shell: ShellExecutor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    /** 任务串行化锁：同一时刻只允许一个用户指令在执行。 */
    private val taskMutex = Mutex()

    /** 当前正在执行的任务 Job，用于 [cancel]。 */
    @Volatile
    private var currentJob: Job? = null

    /** 是否有任务正在执行。 */
    @Volatile
    var isRunning: Boolean = false
        private set

    /** Agentic loop 最大迭代轮数，防止无限循环。 */
    var maxIterations: Int = DEFAULT_MAX_ITERATIONS

    /** 操作日志（按时间顺序记录）。 */
    private val _logList = mutableListOf<OperationLog>()
    val logs: List<OperationLog> get() = synchronized(_logList) { _logList.toList() }

    /** 状态更新的热流，UI 可订阅以实时展示。 */
    private val _statusFlow = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 16)
    val statusFlow: SharedFlow<String> = _statusFlow.asSharedFlow()

    /** 时间戳格式化器（用于日志）。 */
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 对话上下文记忆：记录最近几轮交互，让 AI 有连续的上下文。 */
    val memory = ConversationMemory(maxEntries = 5)

    /** 是否启用快捷指令（跳过AI解析直接执行常见操作）。 */
    var enableQuickCommands: Boolean = true

    /** 是否启用智能错误恢复（动作失败时自动尝试替代方案）。 */
    var enableSmartRecovery: Boolean = true

    /** 是否启用任务模板（常见任务跳过 AI 解析直接执行）。 */
    var enableTaskTemplates: Boolean = true

    /** 是否启用动作并行执行（独立动作同时执行，加速任务完成）。 */
    var enableParallelExecution: Boolean = true

    /** 定时任务调度器：支持延迟执行和周期执行用户指令。 */
    val taskScheduler = TaskScheduler(scope)

    /** 任务执行指标收集器：记录耗时、成功率等性能数据。 */
    val metrics = TaskExecutionMetrics()

    /** 屏幕状态缓存：减少无障碍服务冗余调用，提升响应速度。 */
    val screenCache = ScreenStateCache()

    /** 智能上下文构建器：为 AI 提供精准、精简的上下文信息。 */
    val contextBuilder = IntelligentContextBuilder(screenCache)

    /** 是否启用动作验证（执行后验证动作是否真正生效）。 */
    var enableActionVerification: Boolean = true

    /** 是否启用智能上下文构建（根据任务类型过滤无关信息，节省 Token）。 */
    var enableIntelligentContext: Boolean = true

    /** 自适应时序系统：学习应用加载时间，动态调整等待时长。 */
    val adaptiveTiming = AdaptiveTiming()

    /** 是否启用主动屏幕分析（自动检测弹窗、加载状态并处理）。 */
    var enableProactiveAnalysis: Boolean = true

    /** 执行轨迹记录器：记录完整执行链路，支持回放和问题诊断。 */
    val tracer = ExecutionTracer()

    /** 是否启用执行管道优化（参数预检+去重检测）。 */
    var enablePipelineOptimization: Boolean = true

    /** 是否启用增强反馈压缩（极致压缩反馈文本，降低 Token 消耗）。 */
    var enableEnhancedFeedback: Boolean = true

    /** 是否启用智能动作排序（自动插入WAIT依赖，合并冗余动作）。 */
    var enableSmartSequencer: Boolean = true

    /** 响应缓存：缓存近期相同查询的 AI 响应，避免重复 API 调用。 */
    val responseCache = ResponseCache()

    /** 跨任务经验记忆器：从成功任务中提取模式，加速相似任务执行。 */
    val experienceMemory = ExperienceMemory()

    /** 动作执行预热器：在 AI 思考期间并行预采集屏幕状态，减少感知延迟。 */
    val actionPreheater = ActionPreheater(scope)

    /** 智能超时管理器：根据任务复杂度和历史数据动态调整超时阈值。 */
    val smartTimeout = SmartTimeoutManager()

    /** 是否启用跨任务经验记忆（复用成功任务模式加速执行）。 */
    var enableExperienceMemory: Boolean = true

    /** 是否启用动作执行预热（AI 思考期间并行预采集屏幕状态）。 */
    var enableActionPreheater: Boolean = true

    /** 是否启用智能超时管理（动态调整超时阈值）。 */
    var enableSmartTimeout: Boolean = true

    /** 意图预测器：基于时段、应用上下文和频率预测用户下一步操作。 */
    val intentPredictor = IntentPredictor()

    /** 用户习惯画像：学习常用应用、操作时段、语言偏好等个性化特征。 */
    val userProfile = UserProfile()

    /** 智能重试策略：根据错误类型采用差异化重试策略（指数退避、线性退避等）。 */
    val smartRetry = SmartRetryStrategy()

    /** 操作录制与回放：录制成功操作序列，支持一键回放。 */
    val actionRecorder = ActionRecorder()

    /** 智能上下文裁剪器：精准裁剪屏幕文本和上下文，节省 Token。 */
    val contextPruner = ContextPruner()

    /** 性能基线监控：持续监控性能指标，检测退化趋势。 */
    val performanceBaseline = PerformanceBaseline()

    /** 语义去重器：检测并移除语义重复的动作。 */
    val semanticDeduplicator = SemanticDeduplicator

    /** 智能动作批处理器：将独立动作分组并行执行，加速任务完成。 */
    val smartActionBatcher = SmartActionBatcher()

    /** 对话摘要压缩器：将对话历史压缩为简洁摘要，减少 Token 占用。 */
    val conversationSummarizer = ConversationSummarizer()

    /** 自适应提示词优化器：根据 AI 响应质量动态调整提示词。 */
    val promptOptimizer = AdaptivePromptOptimizer()

    /** 是否启用意图预测。 */
    var enableIntentPrediction: Boolean = true

    /** 是否启用用户习惯画像。 */
    var enableUserProfile: Boolean = true

    /** 是否启用智能重试策略。 */
    var enableSmartRetry: Boolean = true

    /** 是否启用操作录制。 */
    var enableActionRecorder: Boolean = true

    /** 是否启用上下文裁剪。 */
    var enableContextPruner: Boolean = true

    /** 是否启用性能基线监控。 */
    var enablePerformanceBaseline: Boolean = true

    /** 是否启用语义去重。 */
    var enableSemanticDedup: Boolean = true

    /** 是否启用智能动作批处理。 */
    var enableSmartBatcher: Boolean = true

    /** 是否启用对话摘要压缩。 */
    var enableConversationSummarizer: Boolean = true

    /** 是否启用自适应提示词优化。 */
    var enablePromptOptimizer: Boolean = true

    // =========================================================================
    //  本地化执行引擎（新增：v1.3.0）
    // =========================================================================

    /** 本地命令执行器：无需 AI API，直接在本地识别并执行常见指令。
     *
     * 覆盖场景：打开应用、截图、返回、桌面、锁屏、音量控制、系统信息、
     * 清理缓存、媒体控制、剪贴板操作、简单点击等 30+ 类常见操作。
     * 匹配置信度 >= 0.85 时自动执行，完全绕过 AI 调用。
     */
    val localCommandExecutor = LocalCommandExecutor()

    /** 本地模型管理器：下载、管理、加载本地 AI 模型。
     *
     * 支持从软件内下载 GGUF/ONNX/TFLite 模型，自动加载和部署。
     * 预置 7 个模型源（Qwen2.5、Gemma、Phi-3、TinyLlama、DeepSeek-Coder 等）。
     * 当本地模型已加载时，可替代云端 AI API 进行推理。
     *
     * 初始化时自动加载模型源和扫描已下载模型。
     */
    val localModelManager = LocalModelManager(context).also { mgr ->
        scope.launch {
            try {
                mgr.initialize()
            } catch (e: Exception) {
                Log.e(TAG, "LocalModelManager 初始化失败", e)
            }
        }
    }

    /** 自然语言解析器：本地意图理解，无需 AI 即可解析常见指令语义。 */
    val naturalLanguageParser = NaturalLanguageParser()

    /** 是否启用本地命令执行器（优先于 AI 解析）。 */
    var enableLocalExecutor: Boolean = true

    /** 本地命令自动执行的最低置信度阈值。 */
    var localExecutorMinConfidence: Double = LocalCommandExecutor.MIN_CONFIDENCE_AUTO

    /** 是否启用本地模型推理（替代云端 AI API）。 */
    var enableLocalInference: Boolean = false

    /** 本地推理提供者，包装 LocalModelManager 为 AIGateway 兼容接口。 */
    val localInferenceProvider = LocalInferenceProvider(localModelManager)

    /** 本地推理扩展，根据推理模式路由到本地或云端。 */
    val localInferenceExtension = AIGatewayLocalExtension(gateway, localInferenceProvider)

    /**
     * 获取当前推理模式。
     * @return 当前推理模式枚举
     */
    fun getInferenceMode(): InferenceMode = localInferenceExtension.mode

    /**
     * 设置推理模式。
     * @param mode CLOUD_ONLY: 仅云端, LOCAL_ONLY: 仅本地, HYBRID: 本地优先回退云端, AUTO: 自动选择
     */
    fun setInferenceMode(mode: InferenceMode) {
        localInferenceExtension.mode = mode
        enableLocalInference = mode != InferenceMode.CLOUD_ONLY
        Log.i(TAG, "推理模式已切换为: $mode, enableLocalInference=$enableLocalInference")
    }

    /**
     * 检查本地模型是否已加载可用。
     * @return true 表示本地模型已加载并可用于推理
     */
    fun isLocalModelAvailable(): Boolean = localInferenceProvider.isAvailable

    // =========================================================================
    //  AI Agent 组件（v2.0.0 新增）
    // =========================================================================

    /** Termux 桥接器：提供 Shell 命令执行、Python 执行、文件操作等能力。 */
    val termuxBridge: TermuxBridge = TermuxBridge(context)

    /** 工具注册表：管理 AI Agent 可调用的所有工具。 */
    val toolRegistry: ToolRegistry = ToolRegistry(termuxBridge)

    /** AI Agent 引擎：实现 ReAct 循环，支持复杂任务自主执行。 */
    val agentEngine: AgentEngine = AgentEngine(context, gateway, toolRegistry, termuxBridge, scope = scope)

    /** 代码生成器：在设备上生成并执行 Python/Shell/Kotlin/Java/APK 代码。 */
    val codeGenerator: CodeGenerator = CodeGenerator(context, termuxBridge, gateway)

    /** 是否启用 AI Agent 模式（复杂任务自动路由到 AgentEngine）。 */
    var enableAgentMode: Boolean = true

    /** 是否启用代码生成能力（通过 Agent 创建和执行代码文件）。 */
    var enableCodeGeneration: Boolean = true

    /** Agent 执行模式：AUTO 自动执行，ASK 询问用户，MANUAL 仅建议。 */
    var agentMode: AgentEngine.AgentMode = AgentEngine.AgentMode.AUTO
        set(value) {
            field = value
            agentEngine.mode = value
        }

    // =========================================================================
    //  对外入口
    // =========================================================================

    /**
     * 处理用户输入的完整流程（异步）。
     *
     * 在协程中执行：抢锁 -> 采集状态 -> 调用 AI -> 分发执行 -> 循环 -> 回调结果。
     * 通过 [callback] 通知 UI 各阶段进展；返回的 [Job] 可用于等待或取消。
     *
     * @param userInput 用户的自然语言指令
     * @param callback UI 回调接口
     * @return 代表该任务的 [Job]
     */
    fun execute(userInput: String, callback: ClawCallback): Job {
        // 检查协程作用域是否活跃
        if (!scope.isActive) {
            log("协程作用域已关闭，无法执行任务")
            callback.onError(IllegalStateException("应用状态异常，请重启应用"))
            return Job().also { it.complete() }
        }
        val job = scope.launch {
            if (isRunning) {
                emitStatus("有任务正在执行，已加入队列等待…")
                callback.onStatusUpdate("排队中")
            }
            try {
                taskMutex.withLock {
                    runTask(userInput, callback)
                }
            } catch (e: CancellationException) {
                log("任务被取消：${e.message}")
                callback.onError(e)
                throw e
            } catch (e: Throwable) {
                log("任务异常终止：${e.javaClass.simpleName}: ${e.message}")
                callback.onError(e)
            }
        }
        currentJob = job
        job.invokeOnCompletion { throwable ->
            isRunning = false
            if (currentJob === job) currentJob = null
            // 兜底：如果 job 因任何原因完成但 callback 未被调用，
            // 确保 UI 不会永远卡在 "处理中" 状态
            if (throwable != null && throwable !is CancellationException) {
                log("任务异常完成：${throwable.message}")
            }
        }
        return job
    }

    /**
     * 取消正在执行的任务。
     * 会触发协程的 [CancellationException]，已派发的执行器需自行响应取消。
     */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        emitStatus("任务已取消")
    }

    /** 释放资源（取消作用域内所有协程）。 */
    fun shutdown() {
        cancel()
        actionPreheater.cancelAll()
        taskScheduler.shutdown()
        scope.cancel()
    }

    /** 清空操作日志。 */
    fun clearLogs() {
        synchronized(_logList) { _logList.clear() }
    }

    /** 清空对话记忆。 */
    fun clearMemory() {
        memory.clear()
        screenCache.invalidate()
        actionPreheater.cancelAll()
        log("对话记忆、屏幕缓存和预热器已清空")
    }

    /** 获取所有可用快捷指令描述（用于 UI 展示）。 */
    fun getQuickCommands(): List<String> = QuickCommands.getAllDescriptions()

    /** 获取所有任务模板描述（用于 UI 展示）。 */
    fun getTaskTemplates(): List<String> = TaskTemplates.getAllTemplateDescriptions()

    /** 获取性能指标摘要（用于 UI 展示）。 */
    fun getPerformanceSummary(): String = metrics.getPerformanceSummary()

    /** 获取 Token 用量摘要（用于 UI 展示）。 */
    fun getTokenStats(): String = gateway.tokenSummary()

    /** 获取今日 Token 用量摘要。 */
    fun getTodayTokenStats(): String = gateway.todayTokenSummary()

    /** 获取 API 健康状态摘要（用于 UI 展示）。 */
    fun getHealthStats(): String = gateway.healthSummary()

    /** 获取所有定时任务列表。 */
    fun getScheduledTasks(): List<TaskScheduler.ScheduledTask> = taskScheduler.tasks

    /** 获取定时任务执行历史。 */
    fun getScheduledTaskHistory(): List<TaskScheduler.TaskExecution> = taskScheduler.executions

    /** 取消定时任务。 */
    fun cancelScheduledTask(taskId: String) {
        taskScheduler.cancelTask(taskId)
        log("已取消定时任务: $taskId")
    }

    /** 清空所有统计数据（Token 记录、执行指标、时序数据、缓存、经验记忆、超时数据、性能基线等）。 */
    fun clearStats() {
        gateway.tokenTracker.clear()
        gateway.responseCache.clear()
        gateway.resetDifferentialScreenText()
        metrics.clear()
        adaptiveTiming.clear()
        experienceMemory.clear()
        smartTimeout.clear()
        actionPreheater.clearStats()
        performanceBaseline.clear()
        semanticDeduplicator.resetStats()
        promptOptimizer.reset()
        userProfile.cleanup(0)
        intentPredictor.cleanup()
        log("统计数据已清空")
    }

    /** 获取自适应时序摘要（用于 UI 展示）。 */
    fun getTimingStats(): String = adaptiveTiming.getSummary()

    /** 获取响应缓存摘要（用于 UI 展示）。 */
    fun getCacheStats(): String = gateway.cacheSummary()

    /** 获取跨任务经验记忆摘要（用于 UI 展示）。 */
    fun getExperienceStats(): String = experienceMemory.getSummary()

    /** 获取所有经验记录列表（用于 UI 展示）。 */
    fun getExperiences(): List<ExperienceMemory.Experience> = experienceMemory.getAllExperiences()

    /** 获取动作预热器统计摘要（用于 UI 展示）。 */
    fun getPreheaterStats(): String = actionPreheater.getSummary()

    /** 获取智能超时管理摘要（用于 UI 展示）。 */
    fun getTimeoutStats(): String = smartTimeout.getSummary()

    /** 获取当前任务的超时配置预估（用于 UI 展示）。 */
    fun getEstimatedTaskDuration(): String {
        val complexity = gateway.currentComplexity
        val estimated = smartTimeout.getEstimatedDuration(complexity)
        val config = smartTimeout.getTimeout(complexity)
        return "${complexity.name} 预估${estimated / 1000}秒 超时${config.totalTaskTimeoutMs / 1000}秒"
    }

    /** 获取意图预测器摘要（用于 UI 展示）。 */
    fun getIntentPredictionStats(): String = intentPredictor.getPredictionSummary()

    /** 获取用户画像摘要（用于 UI 展示）。 */
    fun getUserProfileStats(): String = userProfile.getProfileSummary()

    /** 获取本地命令执行器统计（用于 UI 展示）。 */
    fun getLocalExecutorStats(): String {
        val stats = localCommandExecutor.getStats()
        return "本地命令: 匹配${stats.totalMatches}次 成功${stats.autoExecutions}次 失败${stats.failedExecutions}次"
    }

    /** 获取本地命令执行器支持的所有命令列表（用于 UI 展示）。 */
    fun getLocalCommands(): List<CommandInfo> = localCommandExecutor.getAllCommands()

    /** 获取 AI Agent 引擎状态（用于 UI 展示）。 */
    fun getAgentState(): AgentEngine.AgentState = agentEngine.stateFlow.value

    /** 获取 AI Agent 是否正在执行任务。 */
    fun isAgentRunning(): Boolean = agentEngine.stateFlow.value.running

    /** 获取 AI Agent 当前模式的可读描述。 */
    fun getAgentModeDescription(): String = when (agentEngine.mode) {
        AgentEngine.AgentMode.AUTO -> "自动模式"
        AgentEngine.AgentMode.ASK -> "询问模式"
        AgentEngine.AgentMode.MANUAL -> "手动模式"
    }

    /** 获取工具注册表统计信息。 */
    fun getToolRegistryStats(): String {
        val tools = toolRegistry.getAllTools()
        return "已注册 ${tools.size} 个工具"
    }

    /** 获取代码生成器统计信息（用于 UI 展示）。 */
    fun getCodeGeneratorStats(): String {
        val stats = codeGenerator.getCodeStatistics()
        if (stats.isEmpty()) return "暂无生成代码"
        return stats.entries.joinToString(" | ") { (type, pair) ->
            "$type: ${pair.first}文件 ${pair.second / 1024}KB"
        }
    }

    /** 获取本地模型管理器状态（用于 UI 展示）。 */
    fun getLocalModelStatus(): String {
        val state = localModelManager.getState()
        val loaded = localModelManager.getLoadedModel()
        val available = localModelManager.getAvailableModels().size
        val total = localModelManager.getAllModelSources().size
        return "本地模型: ${state["state"]} 已加载:${loaded?.modelInfo?.name ?: "无"} 可用:${available}/$total"
    }

    /** 获取所有可下载的模型源列表。 */
    fun getModelSources(): List<ModelInfo> = localModelManager.getAllModelSources()

    /** 获取已下载的模型列表。 */
    fun getDownloadedModels(): List<DownloadedModel> = localModelManager.getAvailableModels()

    /** 开始下载模型。 */
    fun downloadModel(modelId: String) = localModelManager.downloadModel(modelId)

    /** 加载已下载的模型。 */
    fun loadModel(modelId: String) = localModelManager.loadModel(modelId)

    /** 卸载已加载的模型以释放内存。 */
    fun unloadModel(modelId: String) = localModelManager.unloadModel(modelId)

    /** 删除已下载的模型文件。 */
    fun deleteModel(modelId: String): Boolean = localModelManager.deleteModel(modelId)

    /** 从本地文件系统导入已有模型文件（如 .gguf）。 */
    suspend fun importModel(filePath: String, customName: String? = null): String? =
        localModelManager.importModel(filePath, customName)

    /** 扫描指定目录，查找可导入的模型文件。 */
    fun scanImportableModels(directory: String? = null): List<String> =
        localModelManager.scanImportableModels(directory)

    /** 获取模型下载进度。 */
    fun getModelDownloadProgress(modelId: String): DownloadProgress? =
        localModelManager.getDownloadProgress(modelId)

    /** 获取智能重试策略统计摘要（用于 UI 展示）。 */
    fun getRetryStats(): String = smartRetry.getRetryStats()

    /** 获取操作录制列表（用于 UI 展示）。 */
    fun getRecordings(): List<Recording> = actionRecorder.listRecordings()

    /** 获取性能基线监控摘要（用于 UI 展示）。 */
    fun getPerformanceBaselineStats(): String = performanceBaseline.getSummary()

    /** 获取性能退化告警列表。 */
    fun getPerformanceAlerts(): List<String> {
        val alerts = mutableListOf<String>()
        MetricType.values().forEach { type ->
            performanceBaseline.detectDegradation(type)?.let { alerts.add(it.message) }
        }
        return alerts
    }

    /** 获取语义去重统计摘要（用于 UI 展示）。 */
    fun getDedupStats(): String = semanticDeduplicator.getDedupStats()

    /** 获取智能动作批处理统计摘要（用于 UI 展示）。 */
    fun getBatcherStats(): String = smartActionBatcher.getBatchStats()

    /** 获取对话摘要（用于 UI 展示）。 */
    fun getConversationDigest(): String = conversationSummarizer.getSummary()

    // =========================================================================
    //  总结与记忆增强功能（v2.0.7+）
    // =========================================================================

    /** 总结打包器 */
    val summaryPackage = SummaryPackage()

    /**
     * 获取当前对话的上下文包（供新 AI 接续）。
     * 将对话历史打包为结构化文本，新 AI 收到后能完美接续上下文。
     */
    suspend fun getContextPackage(
        context: android.content.Context,
        sessionInfo: SummaryPackage.SessionInfo = SummaryPackage.SessionInfo()
    ): SummaryPackage.ContextPackage {
        val mode = SummarySettings.getSummaryMode(context)
        val wordCount = SummarySettings.getSummaryWordCount(context)
        val digest = conversationSummarizer.summarize(memory.entries)
        return summaryPackage.packageContext(
            entries = memory.entries,
            digest = digest,
            mode = mode,
            wordCount = wordCount,
            sessionInfo = sessionInfo
        )
    }

    /**
     * 将上下文包保存为文件（供分享/发送给新 AI）。
     * @return 保存的文件路径，失败返回 null
     */
    fun saveContextPackageToFile(
        context: android.content.Context,
        pkg: SummaryPackage.ContextPackage
    ): java.io.File? = summaryPackage.saveToFile(context, pkg)

    /**
     * 获取当前对话的摘要统计信息。
     */
    fun getSummaryStats(context: android.content.Context): String {
        val mode = SummarySettings.getSummaryMode(context)
        val freq = SummarySettings.getSummaryFrequency(context)
        val wordCount = SummarySettings.getSummaryWordCount(context)
        val tokenLimit = SummarySettings.getTokenLimit(context)
        val autoEnabled = SummarySettings.isAutoSummaryEnabled(context)
        val memoryPersistent = SummarySettings.isMemoryPersistent(context)
        val msgCount = memory.getMessageCount()

        return buildString {
            appendLine("═══ 总结与记忆设置 ═══")
            appendLine()
            appendLine("总结模式: ${mode.displayName}")
            appendLine("总结频率: ${freq.displayName}")
            appendLine("目标字数: ${wordCount.displayName}")
            appendLine("Token上限: ${tokenLimit.displayName}")
            appendLine("自动总结: ${if (autoEnabled) "开启" else "关闭"}")
            appendLine("记忆持久化: ${if (memoryPersistent) "开启" else "关闭"}")
            appendLine("当前消息计数: ${msgCount}条")
            appendLine()
            appendLine("记忆条目: ${memory.entries.size}条")
            appendLine("经验记忆: ${experienceMemory.getSummary()}")
        }
    }

    /** 获取自适应提示词优化统计摘要（用于 UI 展示）。 */
    fun getPromptOptimizationStats(): String = promptOptimizer.getOptimizationStats()

    /** 回放指定录制（通过录制 ID）。 */
    suspend fun replayRecording(recordingId: String): ReplayResult {
        val recording = actionRecorder.getRecording(recordingId)
            ?: return ReplayResult(false, 0, -1, "录制不存在")
        return actionRecorder.replay(recording) { action ->
            kotlinx.coroutines.runBlocking { dispatch(action) }
        }
    }

    /** 获取最近任务执行轨迹报告（用于调试和问题诊断）。 */
    fun getLastTraceReport(): String = tracer.getLastReport()

    /** 获取最近失败任务的轨迹报告。 */
    fun getLastFailedTraceReport(): String = tracer.getLastFailedReport()

    /** 获取检测到的问题列表。 */
    fun getDetectedProblems(): List<String> = tracer.detectProblems()

    /** 清空执行轨迹。 */
    fun clearTraces() {
        tracer.clear()
        ExecutionPipelineOptimizer.clear()
        recentActionResults.clear()
        gateway.resetDifferentialScreenText()
        log("执行轨迹已清空")
    }

    // =========================================================================
    //  核心编排逻辑
    // =========================================================================

    /**
     * 单次任务的完整执行体。
     *
     * 关键改进：
     * - 检测虚假完成：AI 首轮返回 ANSWER 声称"已打开/已发送"等但未执行任何动作时，自动重试
     * - 剥离过早 ANSWER：首轮多步动作中包含 ANSWER 时，剥离 ANSWER 只执行操作动作
     * - 多次重试：最多 3 次重试，每次递增警告强度
     * - 诚实兜底：重试耗尽后返回诚实错误，绝不展示虚假完成消息
     */
    private suspend fun runTask(userInput: String, callback: ClawCallback) {
        isRunning = true
        log("收到用户指令：$userInput")
        emitStatus("开始处理：$userInput")
        callback.onStatusUpdate("处理中")

        // 重置差异屏幕文本状态（每个新任务的首轮发送完整文本）
        gateway.resetDifferentialScreenText()

        // 开始轨迹记录
        tracer.startTask(userInput)
        tracer.recordStep(
            ExecutionTracer.Phase.USER_INPUT, userInput, "用户输入指令", true
        )

        try {
            // 0. 定时任务检测：解析"X分钟后/X小时后/每天X点"等模式
            val scheduledTaskId = tryParseScheduledTask(userInput, callback)
            if (scheduledTaskId != null) {
                return // 定时任务已设置，不需要立即执行
            }

            // 1. 采集当前手机状态（使用缓存减少延迟）
            emitStatus("正在采集手机状态…")
            val phoneState = withContext(Dispatchers.IO) { systemInfo.getCurrentState() }
            log("手机状态采集完成：前台=${phoneState.currentAppPackage ?: "未知"}, 电量=${phoneState.batteryPercent}%")

            // 1.0a 用户画像记录：记录应用使用和指令
            if (enableUserProfile) {
                userProfile.recordCommand(userInput, phoneState.currentAppPackage)
                log("用户画像已更新")
            }

            // 1.0b 意图预测：预测用户下一步可能操作
            if (enableIntentPrediction) {
                val prediction = intentPredictor.predictNext(
                    phoneState.currentAppPackage,
                    java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                )
                if (prediction != null && prediction.confidence > 0.5f) {
                    log("意图预测：${prediction.predictedCommand}（置信度${"%.0f".format(prediction.confidence * 100)}%）- ${prediction.reason}")
                }
                // 记录当前指令用于预测
                intentPredictor.recordCommand(userInput, System.currentTimeMillis(), phoneState.currentAppPackage)
            }

            // 1.1 智能上下文构建：根据任务类型生成精简上下文
            val taskType = if (enableIntelligentContext) {
                contextBuilder.identifyTaskType(userInput)
            } else {
                IntelligentContextBuilder.TaskType.UNKNOWN
            }
            log("任务类型识别：$taskType")

            // 记录任务开始时间（用于指标统计）
            val taskStartTime = System.currentTimeMillis()
            var aiCallCount = 0
            var smartRecoveryUsed = false

            // 预声明循环变量（快捷指令、任务模板和 Agentic loop 共用）
            var finalAnswer: String? = null
            val executedActions = mutableListOf<ClawAction>()

            // 1.4 本地命令执行器：优先尝试本地匹配，完全无需 AI（新增：v1.3.0）
            // 覆盖 "打开微信"、"截图"、"返回" 等 30+ 类常见操作
            if (enableLocalExecutor) {
                val localMatch = localCommandExecutor.match(userInput)
                if (!localMatch.isEmpty && localMatch.confidence >= localExecutorMinConfidence) {
                    log("本地命令匹配成功：${localMatch.description}（置信度${"%.0f".format(localMatch.confidence * 100)}%），跳过 AI 解析")
                    emitStatus("本地命令匹配：${localMatch.description}")
                    callback.onStatusUpdate("本地执行")
                    var localAllSuccess = true
                    for (action in localMatch.actions) {
                        if (!scope.isActive) throw CancellationException("用户取消")
                        val readable = ActionTranslator.describeAction(action)
                        emitStatus("执行：$readable")
                        callback.onActionExecuting(action)
                        executedActions.add(action)
                        val actionResult = dispatch(action)
                        callback.onActionComplete(action, actionResult)
                        log("本地命令结果：${if (actionResult.success) "成功" else "失败"} - ${actionResult.message}")
                        if (!actionResult.success) {
                            localAllSuccess = false
                            break
                        }
                        delay(300)
                    }
                    if (localAllSuccess) {
                        finalAnswer = generateHumanReadableSummary(executedActions)
                        memory.add(ConversationMemory.MemoryEntry(
                            userCommand = userInput,
                            actions = executedActions.map { ActionTranslator.describeAction(it) },
                            success = true,
                            summary = finalAnswer,
                            phoneStateSummary = "前台:${phoneState.currentAppPackage ?: "?"}"
                        ))
                        if (enableExperienceMemory) {
                            experienceMemory.record(userInput, executedActions, true,
                                System.currentTimeMillis() - taskStartTime, phoneState.currentAppPackage)
                        }
                        val answer = sanitizeFinalAnswer(finalAnswer, executedActions)
                        recordMetrics(userInput, taskStartTime, 0, executedActions.size,
                            executedActions.count { true }, 0, true, true, false)
                        emitStatus(answer)
                        callback.onStatusUpdate("完成")
                        callback.onFinalResult(answer)
                        return
                    }
                    // 本地执行失败，降级到 AI 流程
                    log("本地命令执行失败，切换到 AI 模式")
                    executedActions.clear()
                } else if (!localMatch.isEmpty) {
                    log("本地命令匹配但置信度不足：${localMatch.description}（${"%.0f".format(localMatch.confidence * 100)}% < ${"%.0f".format(localExecutorMinConfidence * 100)}%），走 AI 流程")
                } else {
                    log("未匹配到本地命令，走 AI 流程")
                }
            }

            // 1.5 快捷指令检查：跳过 AI 解析，直接执行常见操作（节省 Token 和时间）
            if (enableQuickCommands) {
                val quickActions = QuickCommands.match(userInput)
                if (quickActions != null) {
                    log("匹配到快捷指令，跳过 AI 解析")
                    emitStatus("快捷指令匹配成功，直接执行")
                    var quickSuccess = true
                    for (action in quickActions) {
                        if (!scope.isActive) throw CancellationException("用户取消")
                        val readable = ActionTranslator.describeAction(action)
                        emitStatus("执行：$readable")
                        callback.onActionExecuting(action)
                        executedActions.add(action)
                        val actionResult = dispatch(action)
                        callback.onActionComplete(action, actionResult)
                        log("快捷指令结果：${if (actionResult.success) "成功" else "失败"} - ${actionResult.message}")
                        if (!actionResult.success) {
                            quickSuccess = false
                            break
                        }
                        delay(300)
                    }
                    if (quickSuccess) {
                        finalAnswer = generateHumanReadableSummary(executedActions)
                        // 快捷指令成功，保存记忆并返回
                        memory.add(ConversationMemory.MemoryEntry(
                            userCommand = userInput,
                            actions = executedActions.map { ActionTranslator.describeAction(it) },
                            success = true,
                            summary = finalAnswer,
                            phoneStateSummary = "前台:${phoneState.currentAppPackage ?: "?"}"
                        ))
                        // 记录跨任务经验
                        if (enableExperienceMemory) {
                            experienceMemory.record(userInput, executedActions, true,
                                System.currentTimeMillis() - taskStartTime, phoneState.currentAppPackage)
                        }
                        val answer = sanitizeFinalAnswer(finalAnswer, executedActions)
                        // 记录指标
                        recordMetrics(userInput, taskStartTime, 0, executedActions.size,
                            executedActions.count { true }, 0, true, true, false)
                        emitStatus(answer)
                        callback.onStatusUpdate("完成")
                        callback.onFinalResult(answer)
                        return
                    }
                    // 快捷指令失败，继续走 AI 流程
                    log("快捷指令失败，切换到 AI 模式")
                    executedActions.clear() // 清空快捷指令的动作，重新开始 AI 流程
                }
            }

            // 1.6 任务模板检查：匹配常见任务模式，直接生成首轮动作
            if (enableTaskTemplates && executedActions.isEmpty()) {
                val templateMatch = TaskTemplates.match(userInput)
                if (templateMatch != null && templateMatch.confidence >= 0.9f && templateMatch.firstActions.isNotEmpty()) {
                    log("匹配到任务模板：${templateMatch.templateName}（置信度${templateMatch.confidence}）")
                    emitStatus("任务模板匹配：${templateMatch.templateName}，直接执行")
                    
                    // 执行模板的首轮动作
                    var templateAllSuccess = true
                    for (action in templateMatch.firstActions) {
                        if (!scope.isActive) throw CancellationException("用户取消")
                        val readable = ActionTranslator.describeAction(action)
                        emitStatus("执行：$readable")
                        callback.onActionExecuting(action)
                        executedActions.add(action)
                        val actionResult = dispatch(action)
                        callback.onActionComplete(action, actionResult)
                        log("模板动作结果：${if (actionResult.success) "成功" else "失败"} - ${actionResult.message}")
                        if (!actionResult.success) {
                            templateAllSuccess = false
                            break
                        }
                        delay(300)
                    }
                    
                    if (templateAllSuccess && templateMatch.estimatedSteps <= 1) {
                        // 单步模板任务完成
                        finalAnswer = generateHumanReadableSummary(executedActions)
                        memory.add(ConversationMemory.MemoryEntry(
                            userCommand = userInput,
                            actions = executedActions.map { ActionTranslator.describeAction(it) },
                            success = true,
                            summary = finalAnswer,
                            phoneStateSummary = "前台:${phoneState.currentAppPackage ?: "?"}"
                        ))
                        // 记录跨任务经验
                        if (enableExperienceMemory) {
                            experienceMemory.record(userInput, executedActions, true,
                                System.currentTimeMillis() - taskStartTime, phoneState.currentAppPackage)
                        }
                        val answer = sanitizeFinalAnswer(finalAnswer, executedActions)
                        recordMetrics(userInput, taskStartTime, 0, executedActions.size,
                            executedActions.count { true }, 0, true, false, false)
                        emitStatus(answer)
                        callback.onStatusUpdate("完成")
                        callback.onFinalResult(answer)
                        return
                    }
                    
                    // 多步模板任务：首轮动作已完成，继续用 AI 执行后续步骤
                    log("模板首轮动作完成，继续 AI 编排后续步骤：${templateMatch.guidance}")
                    // 将模板的引导信息作为首轮反馈
                    val templateFeedback = buildString {
                        appendLine("模板${templateMatch.templateName}首轮动作已执行：")
                        executedActions.forEach { action ->
                            appendLine("- ${ActionTranslator.describeAction(action)}")
                        }
                        appendLine("引导：${templateMatch.guidance}")
                    }
                    // 跳过首轮 AI 调用，直接进入第二轮
                    var iteration = 0
                    var lastInput = templateFeedback
                    var consecutiveNoProgress = 0
                    val maxNoProgress = 3
                    
                    while (iteration < maxIterations && scope.isActive) {
                        iteration++
                        aiCallCount++
                        log("—— 模板后续第 $iteration 轮：调用 AI ——")
                        val newState = withContext(Dispatchers.IO) { systemInfo.getCurrentState() }
                        val result = if (enableLocalInference) {
                            localInferenceExtension.continueCommand(lastInput, newState)
                        } else {
                            gateway.continueCommand(lastInput, newState)
                        }
                        log("AI 返回 ${result.actions.size} 条指令：${result.description}")
                        
                        if (result.actions.isEmpty()) {
                            finalAnswer = result.description.ifBlank { "任务已完成" }
                            break
                        }
                        
                        val (roundExecuted, roundAnswer, recoveryUsed) = executeActionsWithParallel(
                            result.actions, executedActions, callback
                        )
                        if (recoveryUsed) smartRecoveryUsed = true
                        
                        if (roundAnswer != null) {
                            finalAnswer = roundAnswer
                            break
                        }
                        
                        if (roundExecuted == 0) {
                            consecutiveNoProgress++
                            if (consecutiveNoProgress >= maxNoProgress) {
                                finalAnswer = generateHumanReadableSummary(executedActions)
                                break
                            }
                        } else {
                            consecutiveNoProgress = 0
                        }
                        
                        lastInput = buildFeedback(result.actions, executedActions)
                    }
                    
                    if (finalAnswer == null) {
                        finalAnswer = generateHumanReadableSummary(executedActions)
                    }
                    val answer = sanitizeFinalAnswer(finalAnswer, executedActions)
                    memory.add(ConversationMemory.MemoryEntry(
                        userCommand = userInput,
                        actions = executedActions.map { ActionTranslator.describeAction(it) },
                        success = executedActions.isNotEmpty() && consecutiveNoProgress < maxNoProgress,
                        summary = answer,
                        phoneStateSummary = "前台:${phoneState.currentAppPackage ?: "?"} 电量:${phoneState.batteryPercent}%"
                    ))
                    // 记录跨任务经验和智能超时数据
                    if (enableExperienceMemory && executedActions.isNotEmpty()) {
                        experienceMemory.record(userInput, executedActions,
                            consecutiveNoProgress < maxNoProgress,
                            System.currentTimeMillis() - taskStartTime, phoneState.currentAppPackage)
                    }
                    if (enableSmartTimeout) {
                        smartTimeout.recordDuration(gateway.currentComplexity,
                            System.currentTimeMillis() - taskStartTime)
                    }
                    recordMetrics(userInput, taskStartTime, aiCallCount, executedActions.size,
                        executedActions.count { true }, 0, true, false, smartRecoveryUsed)
                    emitStatus(answer)
                    callback.onStatusUpdate("完成")
                    callback.onFinalResult(answer)
                    return
                }
            }

            // 2. Agentic loop：可能多轮
            var iteration = 0
            var lastInput = userInput
            // 连续无进展轮数：如果连续3轮没有成功执行任何动作，提前终止
            var consecutiveNoProgress = 0
            val maxNoProgress = 3

            // 2.0a 跨任务经验记忆：查找相似任务历史，生成经验摘要供 AI 参考
            var experienceHint = ""
            if (enableExperienceMemory) {
                val exp = experienceMemory.find(userInput, phoneState.currentAppPackage)
                if (exp != null && exp.successCount > 0) {
                    experienceHint = experienceMemory.buildExperienceSummary(userInput)
                    if (experienceHint.isNotBlank()) {
                        log("经验记忆：$experienceHint")
                    }
                }
            }

            // 2.0b 智能超时：获取当前任务复杂度的超时配置
            val timeoutConfig = if (enableSmartTimeout) {
                smartTimeout.getTimeout(gateway.currentComplexity)
            } else null
            val taskDeadline = timeoutConfig?.let { taskStartTime + it.totalTaskTimeoutMs }

            while (iteration < maxIterations && scope.isActive) {
                iteration++
                aiCallCount++

                // 智能超时检查：如果即将超时，提前终止
                if (taskDeadline != null && smartTimeout.isLikelyTimeout(
                        gateway.currentComplexity,
                        System.currentTimeMillis() - taskStartTime
                    )) {
                    log("智能超时：任务可能即将超时，提前总结")
                    if (executedActions.isNotEmpty()) {
                        finalAnswer = generateHumanReadableSummary(executedActions) + "（任务耗时较长，已提前结束）"
                        break
                    }
                }

                log("—— 第 $iteration 轮：调用 AI 解析指令 ——")

                // 2.1a 动作预热：在 AI 调用期间并行预采集屏幕状态
                if (enableActionPreheater) {
                    actionPreheater.preheatDuringAICall(systemInfo, delayMs = 500)
                }

                // 2.1 调用 AI
                val result = if (iteration == 1) {
                    // 首轮：使用智能上下文构建器生成精简上下文
                    if (enableIntelligentContext) {
                        val contextResult = contextBuilder.buildContext(
                            userInput, phoneState, memory,
                            gateway.currentComplexity
                        )
                        log("智能上下文：类型=$taskType 估算Token=${contextResult.estimatedTokens}")
                        // 尝试获取建议的首轮动作（跳过 AI 解析）
                        val suggested = contextBuilder.getSuggestedFirstActions(taskType, userInput)
                        if (suggested != null && suggested.isNotEmpty()) {
                            log("上下文构建器建议首轮动作，跳过 AI 调用")
                            ClawCommandResult(actions = suggested, description = "上下文建议动作")
                        } else {
                            // 构建带上下文+经验记忆的输入并调用 AI
                            val contextText = contextResult.contextText
                            var inputForAI = buildString {
                                if (contextText.isNotBlank()) appendLine(contextText)
                                if (experienceHint.isNotBlank()) appendLine(experienceHint)
                                append("用户指令：$lastInput")
                            }
                            // 上下文裁剪：如果输入过长，智能裁剪
                            if (enableContextPruner) {
                                val pruned = contextPruner.pruneScreenText(inputForAI, 1500)
                                if (pruned.savingsPercent > 20f) {
                                    log("上下文裁剪：节省${"%.0f".format(pruned.savingsPercent)}% Token")
                                    inputForAI = pruned.text
                                }
                            }
                            // 自适应提示词优化
                            if (enablePromptOptimizer) {
                                val optResult = promptOptimizer.optimizePrompt(inputForAI, gateway.currentComplexity)
                                if (optResult.additions.isNotEmpty()) {
                                    inputForAI = optResult.prompt
                                    log("提示词优化：${optResult.reason}")
                                }
                            }
                            // 用户画像个性化
                            if (enableUserProfile) {
                                inputForAI = userProfile.personalizePrompt(inputForAI, phoneState.currentAppPackage)
                            }
                            // 根据推理模式选择本地或云端
                            if (enableLocalInference) {
                                localInferenceExtension.sendCommand(inputForAI, phoneState)
                            } else {
                                gateway.sendCommand(inputForAI, phoneState)
                            }
                        }
                    } else {
                        // 回退：使用原始记忆上下文 + 经验记忆
                        val memoryContext = memory.buildContextSummary()
                        var inputWithMemory = buildString {
                            if (memoryContext.isNotBlank()) appendLine(memoryContext)
                            if (experienceHint.isNotBlank()) appendLine(experienceHint)
                            append("用户指令：$lastInput")
                        }
                        // 上下文裁剪
                        if (enableContextPruner) {
                            val pruned = contextPruner.pruneScreenText(inputWithMemory, 1500)
                            if (pruned.savingsPercent > 20f) {
                                inputWithMemory = pruned.text
                            }
                        }
                        // 用户画像个性化
                        if (enableUserProfile) {
                            inputWithMemory = userProfile.personalizePrompt(inputWithMemory, phoneState.currentAppPackage)
                        }
                        // 根据推理模式选择本地或云端
                        if (enableLocalInference) {
                            localInferenceExtension.sendCommand(inputWithMemory, phoneState)
                        } else {
                            gateway.sendCommand(inputWithMemory, phoneState)
                        }
                    }
                } else {
                    // 后续轮次：基于最新状态 + 执行反馈继续
                    // 优先使用预热器缓存的屏幕状态，避免重复采集
                    val preheated = if (enableActionPreheater) actionPreheater.getCachedState() else null
                    val newState = if (preheated != null && !preheated.isStale()) {
                        log("使用预采集的屏幕状态（命中缓存）")
                        phoneState.copy(
                            currentScreenText = preheated.screenText,
                            currentAppPackage = preheated.currentApp ?: phoneState.currentAppPackage
                        )
                    } else {
                        withContext(Dispatchers.IO) { systemInfo.getCurrentState() }
                    }
                    if (enableLocalInference) {
                        localInferenceExtension.continueCommand(lastInput, newState)
                    } else {
                        gateway.continueCommand(lastInput, newState)
                    }
                }

                log("AI 返回 ${result.actions.size} 条指令：${result.description}")

                // 2.1b 首轮防虚假完成检测
                var effectiveResult = result
                if (iteration == 1 && executedActions.isEmpty()) {
                    effectiveResult = handleFirstRoundFakeAnswer(result, lastInput, phoneState)
                }

                // 2.2 无指令则结束
                if (effectiveResult.actions.isEmpty()) {
                    finalAnswer = effectiveResult.description.ifBlank { "任务已完成（无更多指令）" }
                    break
                }

                // 2.1c 智能动作排序：自动插入必要的 WAIT，合并冗余动作
                if (enableSmartSequencer && effectiveResult.actions.size > 1) {
                    val originalCount = effectiveResult.actions.size
                    effectiveResult = effectiveResult.copy(
                        actions = SmartSequencer.optimize(effectiveResult.actions)
                    )
                    if (effectiveResult.actions.size > originalCount) {
                        val waitTime = SmartSequencer.estimateWaitTime(effectiveResult.actions)
                        log("智能排序：插入等待，总等待${waitTime}ms，动作数${originalCount}→${effectiveResult.actions.size}")
                    }
                }

                // 2.1d 语义去重：检测并移除语义重复的动作
                if (enableSemanticDedup && effectiveResult.actions.size > 1) {
                    val dedupResult = semanticDeduplicator.deduplicate(effectiveResult.actions)
                    if (dedupResult.removedCount > 0) {
                        log("语义去重：移除${dedupResult.removedCount}个重复动作 - ${dedupResult.reason}")
                        effectiveResult = effectiveResult.copy(actions = dedupResult.deduplicated)
                    }
                }

                // 2.1e 智能动作批处理：规划执行批次
                if (enableSmartBatcher && effectiveResult.actions.size > 1) {
                    val batchPlan = smartActionBatcher.planBatches(effectiveResult.actions)
                    if (batchPlan.parallelizableCount > 0) {
                        log("动作批处理：${batchPlan.batches.size}批 并行${batchPlan.parallelizableCount} 预估${batchPlan.totalEstimatedMs}ms")
                    }
                }

                // 2.2b 主动屏幕分析：检测并自动处理弹窗、广告等阻塞元素
                if (enableProactiveAnalysis && iteration > 1) {
                    val screenText = screenCache.getScreenText()
                    val uiElements = screenCache.getUiElements()
                    val analysis = ProactiveScreenAnalyzer.analyze(screenText, uiElements)
                    if (analysis.hasDialog && analysis.suggestedAction != null) {
                        log("主动分析：${analysis.suggestedAction}")
                        emitStatus("自动处理：${analysis.suggestedAction}")
                        val autoActions = ProactiveScreenAnalyzer.generateAutoActions(analysis, uiElements)
                        for (autoAction in autoActions) {
                            if (!scope.isActive) throw CancellationException("用户取消")
                            callback.onActionExecuting(autoAction)
                            executedActions.add(autoAction)
                            val autoResult = dispatch(autoAction)
                            callback.onActionComplete(autoAction, autoResult)
                            if (autoResult.success) {
                                log("自动处理成功：${autoResult.message}")
                                delay(500)
                            }
                        }
                    }
                }

                // 2.3 依次执行每条动作
                val feedbackBuilder = StringBuilder()
                var encounteredAnswer = false
                var actionsExecutedThisRound = 0

                for ((index, action) in effectiveResult.actions.withIndex()) {
                    // 协程取消检查
                    if (!scope.isActive) {
                        log("任务被取消，停止执行剩余动作")
                        throw CancellationException("用户取消")
                    }

                    val readable = ActionTranslator.describeAction(action)
                    emitStatus("执行第 ${index + 1}/${effectiveResult.actions.size} 步：$readable")
                    callback.onActionExecuting(action)
                    log("执行动作：$readable")

                    // ANSWER 动作：直接作为最终回答，不调用执行器
                    if (action.type == ActionType.ANSWER) {
                        val answer = action.text ?: effectiveResult.description
                        // 检测虚假完成：未执行任何操作就声称完成
                        if (executedActions.isEmpty() && isFakeCompletion(answer)) {
                            log("检测到 ANSWER 中的虚假完成声明，忽略并继续循环")
                            encounteredAnswer = false
                            break
                        }
                        // 检测非首轮的虚假完成：执行了动作但关键操作可能未成功
                        if (executedActions.isNotEmpty() && isFakeCompletion(answer)) {
                            // 检查是否至少有一个关键操作成功（APP_OPEN、SCREEN_CLICK_TEXT等）
                            val hasCriticalSuccess = executedActions.any {
                                it.type == ActionType.APP_OPEN || it.type == ActionType.SCREEN_CLICK_TEXT ||
                                it.type == ActionType.SCREEN_INPUT || it.type == ActionType.SCREEN_FIND_AND_CLICK
                            }
                            if (!hasCriticalSuccess) {
                                log("ANSWER 声称完成但关键操作未确认成功，忽略")
                                encounteredAnswer = false
                                break
                            }
                        }
                        finalAnswer = answer
                        callback.onActionComplete(action, ClawActionResult.success(answer))
                        feedbackBuilder.appendLine("- ANSWER: $answer")
                        encounteredAnswer = true
                        break
                    }

                    // 管道预检：验证参数完整性，跳过重复动作
                    if (enablePipelineOptimization) {
                        val preCheck = ExecutionPipelineOptimizer.preCheck(action)
                        when (preCheck) {
                            is ExecutionPipelineOptimizer.PreCheckResult.Skip -> {
                                log("管道优化：跳过动作 - ${preCheck.reason}")
                                tracer.recordStep(
                                    ExecutionTracer.Phase.ACTION_EXECUTE,
                                    ActionTranslator.describeAction(action),
                                    preCheck.reason, false, 0
                                )
                                continue
                            }
                            is ExecutionPipelineOptimizer.PreCheckResult.Fail -> {
                                log("管道优化：参数校验失败 - ${preCheck.reason}")
                                val failResult = ClawActionResult.failure(preCheck.reason)
                                callback.onActionComplete(action, failResult)
                                feedbackBuilder.appendLine("- ${ActionTranslator.describeAction(action)} -> 失败: ${preCheck.reason}")
                                if (preCheck.suggestion != null) {
                                    feedbackBuilder.appendLine("  建议: ${preCheck.suggestion}")
                                }
                                continue
                            }
                            is ExecutionPipelineOptimizer.PreCheckResult.Pass -> {
                                // 通过预检，继续执行
                            }
                        }
                    }

                    // 分发执行
                    executedActions.add(action)
                    val dispatchStart = System.currentTimeMillis()
                    val actionResult = dispatch(action)
                    val dispatchDuration = System.currentTimeMillis() - dispatchStart
                    callback.onActionComplete(action, actionResult)
                    log("动作结果：${if (actionResult.success) "成功" else "失败"} - ${actionResult.message}")

                    // 记录管道结果（用于去重检测）
                    ExecutionPipelineOptimizer.recordResult(action, actionResult.success)

                    // 记录轨迹
                    tracer.recordStep(
                        ExecutionTracer.Phase.ACTION_EXECUTE,
                        ActionTranslator.describeAction(action),
                        actionResult.message,
                        actionResult.success,
                        dispatchDuration
                    )

                    // 缓存结果（供增强反馈压缩器使用）
                    recentActionResults.add(action to actionResult)
                    if (recentActionResults.size > 20) recentActionResults.removeAt(0)

                    if (actionResult.success) {
                        actionsExecutedThisRound++
                    }

                    feedbackBuilder.appendLine("- ${ActionTranslator.describeAction(action)} -> ${if (actionResult.success) "成功" else "失败"}: ${actionResult.message}")
                    if (actionResult.data != null && actionResult.data.length <= 200) {
                        feedbackBuilder.appendLine("  数据: ${actionResult.data.take(200)}")
                    }

                    // 执行失败：尝试智能重试策略，然后尝试智能恢复
                    if (!actionResult.success) {
                        log("动作执行失败，尝试智能恢复")

                        // 智能重试策略：根据错误类型采用差异化重试
                        if (enableSmartRetry) {
                            val errorType = smartRetry.classifyError(actionResult.message, action.type)
                            val retryDecision = smartRetry.shouldRetry(errorType, 0)
                            if (retryDecision.shouldRetry) {
                                log("智能重试：${errorType.name} 等待${retryDecision.delayMs}ms后重试")
                                delay(retryDecision.delayMs)
                                val modifiedAction = smartRetry.applyRetryModification(action, errorType)
                                if (modifiedAction !== action) {
                                    log("智能重试：动作参数已调整")
                                }
                                callback.onActionExecuting(modifiedAction)
                                val retryResult = dispatch(modifiedAction)
                                callback.onActionComplete(modifiedAction, retryResult)
                                smartRetry.recordRetryResult(errorType, retryResult.success)
                                if (retryResult.success) {
                                    log("智能重试成功")
                                    actionsExecutedThisRound++
                                    feedbackBuilder.appendLine("- 重试: ${ActionTranslator.describeAction(modifiedAction)} -> 成功")
                                    if (index < effectiveResult.actions.size - 1) {
                                        delay(300)
                                    }
                                    continue
                                }
                            }
                        }

                        // 智能恢复：尝试自动执行替代方案
                        if (enableSmartRecovery && SmartRecovery.isRecoverable(actionResult)) {
                            smartRecoveryUsed = true
                            val recoveryActions = SmartRecovery.suggestRecovery(
                                action, actionResult, phoneState.currentScreenText
                            )
                            if (recoveryActions.isNotEmpty()) {
                                log("智能恢复：生成 ${recoveryActions.size} 个替代动作")
                                for (recoveryAction in recoveryActions) {
                                    if (!scope.isActive) throw CancellationException("用户取消")
                                    val recoveryReadable = ActionTranslator.describeAction(recoveryAction)
                                    emitStatus("智能恢复：$recoveryReadable")
                                    callback.onActionExecuting(recoveryAction)
                                    executedActions.add(recoveryAction)
                                    val recoveryResult = dispatch(recoveryAction)
                                    callback.onActionComplete(recoveryAction, recoveryResult)
                                    log("恢复动作结果：${if (recoveryResult.success) "成功" else "失败"} - ${recoveryResult.message}")
                                    feedbackBuilder.appendLine("- 恢复: $recoveryReadable -> ${if (recoveryResult.success) "成功" else "失败"}")
                                    if (recoveryResult.success) {
                                        actionsExecutedThisRound++
                                        // 恢复成功，继续执行后续动作而非中断
                                        delay(300)
                                    } else {
                                        break
                                    }
                                }
                                // 如果恢复成功，不中断当前批次
                                if (actionsExecutedThisRound > 0) {
                                    if (index < effectiveResult.actions.size - 1) {
                                        delay(300)
                                    }
                                    continue
                                }
                            }
                        }
                        
                        // 恢复失败或不可恢复：提供恢复建议并中断
                        log("动作执行失败，中断当前批次")
                        val recoveryHint = buildRecoveryHint(action, actionResult)
                        feedbackBuilder.appendLine(recoveryHint)
                        break
                    }

                    // 动作间短暂等待，让 UI 有时间响应
                    if (index < effectiveResult.actions.size - 1) {
                        delay(300)
                    }
                }

                // 2.4 若已得到 ANSWER，结束循环
                if (encounteredAnswer) break

                // 2.5 连续无进展检测：本轮没有成功执行任何动作
                if (actionsExecutedThisRound == 0) {
                    consecutiveNoProgress++
                    log("本轮无成功执行的动作（连续 $consecutiveNoProgress/$maxNoProgress 次）")
                    if (consecutiveNoProgress >= maxNoProgress) {
                        log("连续 $maxNoProgress 轮无进展，提前终止")
                        finalAnswer = if (executedActions.isEmpty()) {
                            "抱歉，我暂时无法完成这个操作。请尝试更具体的指令，例如「打开微信」或「打开抖音搜索猫咪」。"
                        } else {
                            generateHumanReadableSummary(executedActions)
                        }
                        break
                    }
                } else {
                    consecutiveNoProgress = 0
                }

                // 2.6 将执行反馈作为下一轮输入，继续循环
                lastInput = feedbackBuilder.toString()
            }

            // 3. 迭代次数耗尽兜底：永不使用原始 JSON，改用执行动作的人类可读总结
            if (finalAnswer == null) {
                val summary = generateHumanReadableSummary(executedActions)
                finalAnswer = if (iteration >= maxIterations) {
                    "$summary（已达最大执行轮数 $maxIterations，任务暂停）"
                } else {
                    summary
                }
                log(finalAnswer)
            }

            // 3.5 AI Agent 模式：当标准屏幕操控流程失败或无结果时，启用 AgentEngine 处理
            // 适用于代码生成、文件操作、命令执行、APK 构建等非屏幕操控任务
            if (enableAgentMode && (executedActions.isEmpty() || executedActions.none { it.type == ActionType.APP_OPEN || it.type == ActionType.SCREEN_CLICK || it.type == ActionType.SCREEN_INPUT })) {
                // 检测是否需要 Agent 处理：用户请求涉及代码生成、文件操作、命令执行等
                val agentKeywords = listOf(
                    "写代码", "编写", "生成", "创建", "写一个", "写个",
                    "python", "shell", "脚本", "代码", "py", "apk", "apk文件",
                    "编译", "运行", "执行", "命令", "安装", "下载",
                    "termux", "终端", "查", "搜索", "分析"
                )
                val needsAgent = agentKeywords.any { userInput.contains(it, ignoreCase = true) }

                if (needsAgent) {
                    log("标准屏幕操控流程未产生结果，启动 AI Agent 处理复杂任务")
                    emitStatus("启动智能 Agent 处理复杂任务...")
                    callback.onStatusUpdate("Agent 处理中")

                    try {
                        var agentResult: AgentResult? = null
                        agentEngine.execute(userInput) { result ->
                            agentResult = result
                        }
                        // execute 是挂起函数，执行完毕后 agentResult 已设置
                        val result = agentResult
                        if (result != null && result.success) {
                            log("Agent 执行完成: steps=${result.steps.size}")
                            val agentSummary = result.summary
                            val filesInfo = if (result.filesCreated.isNotEmpty()) {
                                "\n\n创建的文件:\n" + result.filesCreated.joinToString("\n")
                            } else ""
                            finalAnswer = agentSummary + filesInfo
                            val finalAns = finalAnswer!!

                            // 记录到记忆
                            memory.add(ConversationMemory.MemoryEntry(
                                userCommand = userInput,
                                actions = result.steps.map { it.summary ?: "步骤 ${it.stepNumber}" },
                                success = true,
                                summary = finalAns,
                                phoneStateSummary = "Agent 处理"
                            ))
                            // 记录指标
                            recordMetrics(userInput, taskStartTime, aiCallCount + 1,
                                result.steps.size, result.steps.size, 0,
                                true, false, true)
                            emitStatus(finalAns)
                            callback.onStatusUpdate("完成")
                            callback.onFinalResult(finalAns)
                            return@runTask  // Agent 已完成，直接返回
                        } else {
                            val errMsg = result?.error ?: "Agent 返回空结果"
                            log("Agent 处理失败: $errMsg")
                        }
                    } catch (e: Exception) {
                        log("Agent 引擎异常: ${e.message}")
                    }
                }
            }

            // 4. 通知最终结果（此时 finalAnswer 必非空：循环内赋值或上方兜底已保证）
            //    永不将原始 JSON 展示给用户：若 finalAnswer 疑似 JSON，则改用执行动作的人类可读总结
            val answer = sanitizeFinalAnswer(finalAnswer, executedActions)
            
            // 保存对话记忆，供后续多轮对话引用
            memory.add(ConversationMemory.MemoryEntry(
                userCommand = userInput,
                actions = executedActions.map { ActionTranslator.describeAction(it) },
                success = executedActions.isNotEmpty() && consecutiveNoProgress < maxNoProgress,
                summary = answer,
                phoneStateSummary = "前台:${phoneState.currentAppPackage ?: "?"} 电量:${phoneState.batteryPercent}%"
            ))
            
            // 记录执行指标
            recordMetrics(userInput, taskStartTime, aiCallCount, executedActions.size,
                executedActions.count { /* all counted as executed */ true }, 0,
                consecutiveNoProgress < maxNoProgress, false, smartRecoveryUsed)

            // 跨任务经验记忆：记录本次任务结果，积累成功模式
            if (enableExperienceMemory && executedActions.isNotEmpty()) {
                val taskSuccess2 = consecutiveNoProgress < maxNoProgress
                experienceMemory.record(
                    userInput = userInput,
                    actions = executedActions,
                    success = taskSuccess2,
                    durationMs = System.currentTimeMillis() - taskStartTime,
                    appContext = phoneState.currentAppPackage
                )
            }

            // 智能超时：记录本次任务耗时，优化未来超时阈值
            if (enableSmartTimeout) {
                smartTimeout.recordDuration(
                    gateway.currentComplexity,
                    System.currentTimeMillis() - taskStartTime
                )
            }

            // 取消预热器的残留协程
            if (enableActionPreheater) actionPreheater.cancelAll()

            // 任务成功判定（提前定义供后续使用）
            val taskSuccess = executedActions.isNotEmpty() && consecutiveNoProgress < maxNoProgress

            // 性能基线监控：记录关键性能指标
            if (enablePerformanceBaseline) {
                val totalDuration = System.currentTimeMillis() - taskStartTime
                performanceBaseline.recordMetric(MetricType.TASK_DURATION, totalDuration.toDouble())
                performanceBaseline.recordMetric(MetricType.AI_LATENCY, (if (aiCallCount > 0) totalDuration / aiCallCount else 0L).toDouble())
                performanceBaseline.recordMetric(MetricType.SUCCESS_RATE, if (taskSuccess) 1.0 else 0.0)
                // 检测性能退化
                val degradation = performanceBaseline.detectDegradation(MetricType.TASK_DURATION)
                if (degradation != null) {
                    log("性能告警：${degradation.message}")
                }
            }

            // 操作录制：录制成功的任务执行序列
            if (enableActionRecorder && taskSuccess && executedActions.isNotEmpty()) {
                actionRecorder.startRecording(
                    name = userInput.take(20),
                    userCommand = userInput,
                    appContext = phoneState.currentAppPackage
                )
                executedActions.forEach { action ->
                    actionRecorder.recordAction(action, ClawActionResult.success("已执行"))
                }
                actionRecorder.stopRecording(true)
            }

            // 对话摘要压缩：如果对话历史过长，压缩为摘要
            if (enableConversationSummarizer && memory.entries.size >= 4) {
                val digest = conversationSummarizer.summarize(memory.entries)
                log("对话摘要：${digest.totalTasks}个任务 ${digest.successCount}成功 ${digest.failedCount}失败 ${digest.keyFacts.size}个关键事实")
            }

            // 自适应提示词优化：记录本次 AI 响应质量
            if (enablePromptOptimizer) {
                val quality = promptOptimizer.analyzeResponseQuality(
                    answer,
                    answer
                )
                promptOptimizer.recordResult("default", taskSuccess, quality)
            }

            // 结束轨迹记录
            tracer.endTask(taskSuccess, answer)
            tracer.recordStep(
                ExecutionTracer.Phase.FINAL_RESULT, "任务完成", answer, taskSuccess,
                System.currentTimeMillis() - taskStartTime
            )
            
            // 检测问题并记录
            val problems = tracer.detectProblems()
            if (problems.isNotEmpty()) {
                problems.forEach { log("问题检测: $it") }
            }
        
            emitStatus(answer)
            callback.onStatusUpdate("完成")
            callback.onFinalResult(answer)

        } catch (e: CancellationException) {
            log("任务被取消")
            tracer.endTask(false, "任务被取消")
            callback.onError(e)
            throw e
        } catch (e: Throwable) {
            log("任务执行出错：${e.message}")
            tracer.endTask(false, "出错: ${e.message}")
            emitStatus("出错：${e.message}")
            callback.onError(e)
        } finally {
            isRunning = false
        }
    }

    /**
     * 处理首轮 AI 返回中的虚假完成问题。
     *
     * 检测并处理三种异常情况：
     * 1. 首轮纯 ANSWER 且声称已完成操作 → 多次重试，递增警告
     * 2. 首轮纯 ANSWER 但非虚假完成（纯聊天）→ 正常返回
     * 3. 首轮多步动作中混入 ANSWER → 剥离 ANSWER，只执行操作动作
     *
     * 关键修复：
     * - 重试后若返回混合结果（动作+ANSWER），正确剥离 ANSWER 后接受
     * - 重试后若返回纯动作，直接接受
     * - 重试后若返回空列表，视为失败继续重试
     *
     * @param result AI 首轮返回的原始结果
     * @param originalInput 用户原始指令
     * @param phoneState 当前手机状态
     * @return 处理后的有效结果（可能经过重试或剥离）
     */
    private suspend fun handleFirstRoundFakeAnswer(
        result: ClawCommandResult,
        originalInput: String,
        phoneState: PhoneState
    ): ClawCommandResult {
        var effective = result

        // 情况1：首轮纯 ANSWER → 仅检测虚假完成（声称"已打开"等但未执行）
        if (effective.isAnswerOnly) {
            val answerText = effective.actions.firstOrNull()?.text ?: ""
            // 关键：仅对虚假完成重试，不对"未执行操作"重试
            // 因为AI可能合理地返回ANSWER（如纯聊天、不支持的操作等）
            if (isFakeCompletion(answerText)) {
                log("AI 首轮返回虚假完成回答：$answerText")
                val maxRetries = 3
                var retryCount = 0
                while (retryCount < maxRetries) {
                    retryCount++
                    log("虚假完成重试（第 $retryCount/$maxRetries 次）")
                    emitStatus("AI 返回了虚假完成，正在重试（第 $retryCount 次）…")
                    val retryPrompt = buildRetryPrompt(originalInput, retryCount)
                    effective = gateway.sendCommand(retryPrompt, phoneState)
                    log("重试后 AI 返回 ${effective.actions.size} 条指令：${effective.description}")

                    // 重试结果分析：
                    val hasNonAnswerActions = effective.actions.any { it.type != ActionType.ANSWER }
                    if (hasNonAnswerActions) {
                        // 重试返回了操作动作（可能混有ANSWER）→ 剥离ANSWER后接受
                        if (effective.actions.any { it.type == ActionType.ANSWER }) {
                            log("重试返回混合结果，剥离 ANSWER")
                            effective = effective.copy(
                                actions = effective.actions.filter { it.type != ActionType.ANSWER }
                            )
                        }
                        log("重试成功，获得 ${effective.actions.size} 条操作动作")
                        return effective
                    }

                    // 重试结果仍是纯 ANSWER 或空
                    if (effective.isAnswerOnly) {
                        val retryAnswer = effective.actions.firstOrNull()?.text ?: ""
                        if (isFakeCompletion(retryAnswer)) {
                            log("重试后仍然返回虚假完成：$retryAnswer")
                            if (retryCount >= maxRetries) {
                                log("重试耗尽，返回诚实错误")
                                return ClawCommandResult(
                                    actions = listOf(
                                        ClawAction(
                                            actionName = ActionType.ANSWER.name,
                                            params = JsonObject(mapOf("text" to JsonPrimitive("抱歉，我暂时无法执行这个操作。请尝试更具体地描述，例如「打开微信」或「打开抖音搜索猫咪」。"))),
                                            description = "重试耗尽的诚实兜底"
                                        )
                                    ),
                                    description = "重试耗尽"
                                )
                            }
                            // 继续重试
                        } else {
                            // 重试结果不是虚假完成（可能是纯聊天或正常回答），接受
                            break
                        }
                    } else {
                        // 空动作列表，继续重试
                        log("重试返回空动作列表，继续重试")
                    }
                }
            }
            // 非虚假完成（纯聊天或正常回答）→ 正常返回 effective
        }

        // 情况2：首轮多步动作中混入 ANSWER → 剥离 ANSWER
        if (effective.actions.any { it.type == ActionType.ANSWER } &&
            effective.actions.any { it.type != ActionType.ANSWER }
        ) {
            log("首轮多步动作中包含 ANSWER，剥离 ANSWER 只执行操作动作")
            effective = effective.copy(
                actions = effective.actions.filter { it.type != ActionType.ANSWER }
            )
        }

        return effective
    }

    /**
     * 检测回答文本是否包含虚假完成声明。
     *
     * 当 AI 返回的 ANSWER 文本中包含"已打开""已发送"等声称已完成操作的措辞，
     * 但实际上没有执行任何动作时，判定为虚假完成。
     *
     * 优化：使用正则匹配更灵活，覆盖更多变体（如"已经帮你打开了"等），
     * 同时排除合理回答（如"可以打开微信"是建议而非完成声明）。
     *
     * @param text ANSWER 的文本内容
     * @return true 表示检测到虚假完成声明
     */
    private fun isFakeCompletion(text: String): Boolean {
        // 精确匹配模式：明确声称已完成的操作
        val fakePatterns = listOf(
            // 声称已完成操作（精确匹配，避免误判"可以打开"等建议语）
            "已打开", "已发送", "已完成", "已为你", "已经打开", "已经发送",
            "成功打开", "成功发送", "已给", "已创建", "已删除", "已设置",
            "已关闭", "已清理", "已搜索", "已输入", "已点击", "已安装",
            "帮您打开", "帮您发送", "为你打开", "为你发送", "为你搜索",
            "已启动", "已退出", "已截图", "已复制", "已粘贴", "已保存",
            "已拨号", "已拨打", "已挂断", "已连接", "已断开",
            "已添加", "已移除", "已切换", "已开启",
            "帮你打开", "帮你发送", "帮你搜索", "帮你完成",
            "已经帮你", "已经为你", "已帮您",
            // 英文完成声明
            "has been opened", "has been sent", "successfully opened",
            "has been completed", "already opened", "already sent",
            "I have opened", "I have sent", "I've opened", "I've sent"
        )
        // 快速检测：如果文本很短且不含完成关键词，直接返回false
        if (text.length < 3) return false
        return fakePatterns.any { text.contains(it) }
    }

    /**
     * 构建重试提示词，随重试次数递增警告强度。
     *
     * 优化：每次重试都给出具体的JSON示例，降低AI再次返回ANSWER的概率。
     *
     * @param originalInput 用户原始指令
     * @param retryCount 当前重试次数（1/2/3）
     * @return 递增强度的重试提示词
     */
    private fun buildRetryPrompt(originalInput: String, retryCount: Int): String = when (retryCount) {
        1 -> "注意：你刚才返回了ANSWER声称已完成，但你没有执行任何动作。你必须返回操作动作JSON。" +
             "格式：{\"actions\":[{\"action\":\"动作类型\",\"params\":{...},\"description\":\"说明\"}],\"description\":\"目标\"}" +
             "常用动作：APP_OPEN{packageName或name} SCREEN_CLICK_TEXT{text} SCREEN_INPUT{text} SCREEN_WAIT{ms} SCREEN_KEY{BACK/HOME/RECENTS} SCREEN_FIND_AND_CLICK{text}" +
             "不确定包名用APP_OPEN{name:\"应用名\"}。请返回操作动作：$originalInput"
        2 -> "严重警告：你再次返回了ANSWER，这是严格禁止的！你无法预知操作是否成功。" +
             "正确做法：返回操作动作→系统执行→告诉你结果→你再决定下一步。" +
             "示例(打开微信)：{\"actions\":[{\"action\":\"APP_OPEN\",\"params\":{\"packageName\":\"com.tencent.mm\"},\"description\":\"打开微信\"},{\"action\":\"SCREEN_WAIT\",\"params\":{\"ms\":2000},\"description\":\"等待启动\"}]}" +
             "必须返回操作动作，禁止ANSWER。指令：$originalInput"
        else -> "最后警告：你已经3次返回ANSWER。你必须只返回操作动作JSON。" +
                "格式：{\"actions\":[{\"action\":\"APP_OPEN\",\"params\":{\"packageName\":\"com.tencent.mm\"}},{\"action\":\"SCREEN_WAIT\",\"params\":{\"ms\":2000}}],\"description\":\"打开微信\"}" +
                "不确定包名用APP_OPEN{name:\"应用名\"}。禁止ANSWER。指令：$originalInput"
    }

    /**
     * 根据动作类型和失败信息构建具体的恢复建议。
     *
     * 为 AI 提供可操作的下一步建议，而非笼统的"请调整"，
     * 显著提高多步任务中的错误恢复成功率。
     *
     * @param action 失败的动作
     * @param result 失败结果
     * @return 包含具体建议的反馈文本
     */
    private fun buildRecoveryHint(action: ClawAction, result: ClawActionResult): String {
        val baseHint = "动作失败：${result.message}"
        val suggestion = when (action.type) {
            ActionType.APP_OPEN -> {
                when {
                    result.message.contains("未安装") ->
                        "建议：应用可能未安装，改用APP_SEARCH{name:\"${action.name ?: action.packageName ?: ""}\"}搜索，或用APP_LIST查看已安装应用。"
                    result.message.contains("悬浮窗") || result.message.contains("权限") ->
                        "建议：缺少权限导致无法启动，改用APP_SEARCH{name:\"${action.name ?: action.packageName ?: ""}\"}尝试。"
                    action.packageName.isNullOrEmpty() && !action.name.isNullOrEmpty() ->
                        "建议：应用名「${action.name}」未匹配到包名，改用APP_SEARCH{name:\"${action.name}\"}搜索打开。"
                    else ->
                        "建议：打开应用失败，改用APP_SEARCH{name:\"${action.name ?: action.packageName ?: ""}\"}或换用name参数。"
                }
            }
            ActionType.SCREEN_CLICK_TEXT, ActionType.SCREEN_FIND_AND_CLICK -> {
                "建议：文本「${action.text ?: ""}」未找到，可能：1)页面未加载完，先SCREEN_WAIT{ms:2000} 2)文本在下方，先SCREEN_SWIPE{direction:UP} 3)换用SCREEN_FIND_AND_CLICK自动滚动查找。"
            }
            ActionType.SCREEN_INPUT -> {
                "建议：输入失败，可能输入框未获得焦点。先SCREEN_CLICK_TEXT点击输入框，再SCREEN_INPUT输入。"
            }
            ActionType.APP_SEARCH -> {
                "建议：搜索应用失败，可能应用名不匹配。改用APP_LIST列出已安装应用，或用更准确的应用名。"
            }
            ActionType.SCREEN_SWIPE -> {
                "建议：滑动失败，检查方向参数(UP/DOWN/LEFT/RIGHT)是否正确。"
            }
            ActionType.SCREEN_KEY -> {
                "建议：按键失败，检查key参数(BACK/HOME/RECENTS)是否正确。"
            }
            else -> "建议：请换一种方法重试该操作。"
        }
        return "$baseHint $suggestion"
    }

    // =========================================================================
    //  定时任务解析与并行执行辅助方法
    // =========================================================================

    /**
     * 尝试解析定时任务指令。
     *
     * 支持的模式：
     * - "X分钟后打开微信" → 延迟 X 分钟执行
     * - "X小时后打开支付宝" → 延迟 X 小时执行
     * - "X秒钟后截图" → 延迟 X 秒执行
     *
     * @return 任务 ID（已设置定时任务），null 表示不是定时任务指令
     */
    private fun tryParseScheduledTask(userInput: String, callback: ClawCallback): String? {
        // 匹配 "X分钟后/秒钟后/小时后 + 指令"
        val delayRegex = Regex("(\\d+)\\s*(分钟|秒钟|小时|min|sec|hour)\\s*后\\s*(.+)", RegexOption.IGNORE_CASE)
        val match = delayRegex.find(userInput.trim()) ?: return null

        val num = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val command = match.groupValues[3].trim()

        if (command.isBlank()) return null

        val delayMs = when {
            unit.contains("分") || unit.contains("min") -> num * 60_000L
            unit.contains("秒") || unit.contains("sec") -> num * 1_000L
            unit.contains("小") || unit.contains("hour") -> num * 3_600_000L
            else -> return null
        }

        val taskName = "延迟${num}${unit}后$command"
        log("解析到定时任务：$taskName（延迟${delayMs}ms）")

        // 设置任务执行回调
        taskScheduler.onExecute = { cmd ->
            log("定时任务触发：$cmd")
            scope.launch {
                execute(cmd, object : ClawCallback {
                    override fun onStatusUpdate(status: String) {
                        log("定时任务状态：$status")
                    }
                    override fun onFinalResult(result: String) {
                        log("定时任务完成：$result")
                    }
                    override fun onError(error: Throwable) {
                        log("定时任务出错：${error.message}")
                    }
                })
            }
        }

        val taskId = taskScheduler.scheduleDelayed(taskName, command, delayMs)
        emitStatus("已设置定时任务：$taskName")
        callback.onStatusUpdate("定时任务已设置")
        callback.onFinalResult("已设置定时任务：$num$unit 后执行「$command」\n任务ID: $taskId\n\n你可以继续输入其他指令。")
        return taskId
    }

    /**
     * 使用并行分组执行动作。
     *
     * 利用 [ActionDependencyAnalyzer] 将动作分组，非阻塞动作可以并行执行。
     *
     * @param actions 待执行的动作列表
     * @param executedActions 已执行动作的累积列表（新执行的动作会追加到此列表）
     * @param callback UI 回调
     * @return Triple(成功执行数, ANSWER文本(可为null), 是否使用了智能恢复)
     */
    private suspend fun executeActionsWithParallel(
        actions: List<ClawAction>,
        executedActions: MutableList<ClawAction>,
        callback: ClawCallback
    ): Triple<Int, String?, Boolean> {
        var answer: String? = null
        var successCount = 0
        var recoveryUsed = false

        // 使用依赖分析器分组
        val groups = if (enableParallelExecution) {
            ActionDependencyAnalyzer.analyzeParallelGroups(actions)
        } else {
            // 禁用并行时，每个动作单独一组
            actions.map { listOf(it) }
        }

        for (group in groups) {
            if (!scope.isActive) throw CancellationException("用户取消")

            // 检查 ANSWER
            val answerAction = group.firstOrNull { it.type == ActionType.ANSWER }
            if (answerAction != null) {
                val answerText = answerAction.text ?: ""
                if (executedActions.isNotEmpty() || !isFakeCompletion(answerText)) {
                    answer = answerText
                    callback.onActionComplete(answerAction, ClawActionResult.success(answerText))
                }
                break
            }

            // 单个动作：直接执行
            if (group.size == 1) {
                val action = group.first()
                val readable = ActionTranslator.describeAction(action)
                emitStatus("执行：$readable")
                callback.onActionExecuting(action)
                executedActions.add(action)
                val result = dispatch(action)
                callback.onActionComplete(action, result)
                if (result.success) successCount++

                if (!result.success && enableSmartRecovery && SmartRecovery.isRecoverable(result)) {
                    recoveryUsed = true
                    val recoveryActions = SmartRecovery.suggestRecovery(action, result, "")
                    for (recoveryAction in recoveryActions) {
                        if (!scope.isActive) throw CancellationException("用户取消")
                        emitStatus("智能恢复：${ActionTranslator.describeAction(recoveryAction)}")
                        callback.onActionExecuting(recoveryAction)
                        executedActions.add(recoveryAction)
                        val recoveryResult = dispatch(recoveryAction)
                        callback.onActionComplete(recoveryAction, recoveryResult)
                        if (recoveryResult.success) successCount++
                        delay(300)
                    }
                }
                delay(300)
                continue
            }

            // 多个非阻塞动作：并行执行
            emitStatus("并行执行 ${group.size} 个独立动作…")
            val deferredResults = group.map { action ->
                scope.async {
                    val readable = ActionTranslator.describeAction(action)
                    log("并行执行：$readable")
                    callback.onActionExecuting(action)
                    executedActions.add(action)
                    val result = dispatch(action)
                    callback.onActionComplete(action, result)
                    Triple(action, result, readable)
                }
            }
            val results = deferredResults.awaitAll()
            successCount += results.count { it.second.success }
            delay(300)
        }

        return Triple(successCount, answer, recoveryUsed)
    }

    /**
     * 构建执行反馈文本。
     * 使用增强反馈压缩器，将反馈压缩到 50-100 Token。
     */
    private fun buildFeedback(actions: List<ClawAction>, executedActions: List<ClawAction>): String {
        if (enableEnhancedFeedback && executedActions.isNotEmpty()) {
            // 使用增强反馈压缩器
            val results = executedActions.mapNotNull { action ->
                // 从日志中找到该动作的执行结果
                val result = findActionResult(action) ?: ClawActionResult.success("已执行")
                action to result
            }
            if (results.isNotEmpty()) {
                val currentApp = executedActions.lastOrNull { it.type == ActionType.APP_OPEN }
                    ?.packageName?.substringAfterLast(".")
                return EnhancedFeedbackCompressor.compress(
                    actions, results, currentApp = currentApp
                )
            }
        }

        // 回退：原始反馈格式
        val sb = StringBuilder()
        actions.forEach { action ->
            if (action.type == ActionType.ANSWER) {
                sb.appendLine("- ANSWER: ${action.text ?: ""}")
            } else {
                val readable = ActionTranslator.describeAction(action)
                sb.appendLine("- $readable -> 已执行")
            }
        }
        return sb.toString()
    }

    /** 缓存最近动作结果，供 buildFeedback 查找。 */
    private val recentActionResults = mutableListOf<Pair<ClawAction, ClawActionResult>>()

    /** 查找动作的执行结果。 */
    private fun findActionResult(action: ClawAction): ClawActionResult? {
        return recentActionResults.lastOrNull { it.first === action }?.second
    }

    /**
     * 记录任务执行指标。
     */
    private fun recordMetrics(
        userInput: String,
        startTime: Long,
        aiCalls: Int,
        actionsExecuted: Int,
        actionsSucceeded: Int,
        actionsFailed: Int,
        success: Boolean,
        quickCommandUsed: Boolean,
        smartRecoveryUsed: Boolean
    ) {
        val totalTimeMs = System.currentTimeMillis() - startTime
        val iteration = (aiCalls + 1).coerceAtLeast(1)
        metrics.record(TaskExecutionMetrics.TaskMetric(
            userCommand = userInput.take(50),
            totalTimeMs = totalTimeMs,
            aiCalls = aiCalls,
            actionsExecuted = actionsExecuted,
            actionsSucceeded = actionsSucceeded,
            actionsFailed = actionsFailed,
            iterations = iteration,
            quickCommandUsed = quickCommandUsed,
            smartRecoveryUsed = smartRecoveryUsed,
            success = success
        ))
        log("任务指标：耗时${totalTimeMs}ms AI调用${aiCalls}次 动作${actionsExecuted}个 成功${actionsSucceeded} ${if (success) "成功" else "失败"}")
    }

    // =========================================================================
    //  动作分发
    // =========================================================================

    /**
     * 根据 [ActionType] 将 [ClawAction] 分发给对应的执行器并返回结果。
     *
     * 这是一个「分发器（dispatcher）」，不包含具体执行逻辑，仅做路由。
     * 集成 [ActionVerifier] 进行执行后验证，确保动作真正生效。
     */
    private suspend fun dispatch(action: ClawAction): ClawActionResult {
        // 执行前失效屏幕缓存（动作会改变屏幕状态）
        val isScreenAction = action.type == ActionType.SCREEN_CLICK ||
            action.type == ActionType.SCREEN_CLICK_TEXT || action.type == ActionType.SCREEN_SWIPE ||
            action.type == ActionType.SCREEN_INPUT || action.type == ActionType.SCREEN_KEY ||
            action.type == ActionType.SCREEN_FIND_AND_CLICK || action.type == ActionType.SCREEN_SCROLL_TO_TEXT ||
            action.type == ActionType.SCREEN_LONG_CLICK || action.type == ActionType.SCREEN_DOUBLE_CLICK ||
            action.type == ActionType.APP_OPEN || action.type == ActionType.APP_CLOSE
        if (isScreenAction) {
            screenCache.invalidate()
            // 同步失效预热器缓存（屏幕状态即将变化）
            if (enableActionPreheater) actionPreheater.invalidate()
        }

        return try {
            val rawResult = dispatchRaw(action)

            // APP_OPEN 成功后启动加载检测，预采集完成后的屏幕状态
            if (enableActionPreheater && rawResult.success && action.type == ActionType.APP_OPEN) {
                val targetPkg = action.packageName
                if (!targetPkg.isNullOrEmpty()) {
                    actionPreheater.watchAppLoading(systemInfo, targetPkg)
                }
            }

            // 动作验证：对关键动作类型执行后验证
            if (enableActionVerification && rawResult.success) {
                val verifiedResult = ActionVerifier.verify(action, rawResult, screen, systemInfo)
                if (!verifiedResult.success) {
                    log("动作验证失败：${ActionTranslator.describeAction(action)} -> ${verifiedResult.message}")
                    // 验证失败时尝试重试
                    val retriedResult = ActionVerifier.executeWithVerification(
                        action, { dispatchRaw(it) }, screen, systemInfo, maxRetries = 1
                    )
                    // 失效缓存（重试可能改变了屏幕状态）
                    screenCache.invalidate()
                    if (enableActionPreheater) actionPreheater.invalidate()
                    return retriedResult
                }
                return verifiedResult
            }

            rawResult
        } catch (e: Throwable) {
            ClawActionResult.failure("执行异常：${e.message}")
        }
    }

    /**
     * 原始动作分发（不含验证逻辑）。
     */
    private suspend fun dispatchRaw(action: ClawAction): ClawActionResult {
        return try {
            when (action.type) {
                // —— 屏幕交互 ——
                ActionType.SCREEN_CLICK -> {
                    val x = action.x
                    val y = action.y
                    if (x != null && y != null) screen.click(x, y)
                    else ClawActionResult.failure("SCREEN_CLICK 缺少 x/y 参数")
                }
                ActionType.SCREEN_CLICK_TEXT -> {
                    val text = action.text
                    if (!text.isNullOrEmpty()) screen.clickText(text)
                    else ClawActionResult.failure("SCREEN_CLICK_TEXT 缺少 text 参数")
                }
                ActionType.SCREEN_SWIPE -> {
                    val dir = action.swipeDirection
                    // 注意：x1/y1/x2/y2 是带自定义 getter 的属性，Kotlin 无法对其 smart cast，
                    // 必须先赋值给局部 val 才能在非空分支中作为非空 Int 使用。
                    val x1 = action.x1
                    val y1 = action.y1
                    val x2 = action.x2
                    val y2 = action.y2
                    when {
                        dir != null -> screen.swipeDirection(dir)
                        x1 != null && y1 != null && x2 != null && y2 != null ->
                            screen.swipe(x1, y1, x2, y2)
                        else -> ClawActionResult.failure("SCREEN_SWIPE 缺少 direction 或 x1/y1/x2/y2 参数")
                    }
                }
                ActionType.SCREEN_INPUT -> {
                    val text = action.text
                    if (text != null) screen.inputText(text)
                    else ClawActionResult.failure("SCREEN_INPUT 缺少 text 参数")
                }
                ActionType.SCREEN_KEY -> {
                    val key = action.key
                    if (key != null) screen.pressKey(key)
                    else ClawActionResult.failure("SCREEN_KEY 缺少有效 key 参数（BACK/HOME/RECENTS）")
                }
                ActionType.SCREEN_SCREENSHOT -> screen.screenshot()
                ActionType.SCREEN_WAIT -> {
                    val ms = action.ms
                    if (ms != null && ms > 0) screen.wait(ms)
                    else ClawActionResult.failure("SCREEN_WAIT 缺少有效 ms 参数")
                }

                // —— 新增屏幕交互 ——
                ActionType.SCREEN_LONG_CLICK -> {
                    val x = action.x
                    val y = action.y
                    val text = action.text
                    if (x != null && y != null) screen.longClick(x, y, text)
                    else if (!text.isNullOrEmpty()) screen.longClick(null, null, text)
                    else ClawActionResult.failure("SCREEN_LONG_CLICK 缺少 x/y 或 text 参数")
                }
                ActionType.SCREEN_FIND_AND_CLICK -> {
                    val text = action.text
                    if (!text.isNullOrEmpty()) screen.findAndClick(text)
                    else ClawActionResult.failure("SCREEN_FIND_AND_CLICK 缺少 text 参数")
                }
                ActionType.SCREEN_SCROLL_TO_TEXT -> {
                    val text = action.text
                    if (!text.isNullOrEmpty()) screen.scrollToText(text)
                    else ClawActionResult.failure("SCREEN_SCROLL_TO_TEXT 缺少 text 参数")
                }

                // —— 应用管理 ——
                ActionType.APP_OPEN -> {
                    val pkg = action.packageName
                    val name = action.name
                    val openResult = when {
                        // 有包名 -> 直接打开
                        !pkg.isNullOrEmpty() -> shell.openApp(pkg)
                        // 无包名但有名称 -> 按名称搜索打开
                        !name.isNullOrEmpty() -> {
                            log("APP_OPEN 无 packageName，使用 name「$name」搜索打开")
                            shell.searchApp(name)
                        }
                        // 都没有 -> 错误
                        else -> ClawActionResult.failure("APP_OPEN 缺少 packageName 或 name 参数")
                    }
                    // 成功打开后使用自适应时序等待加载
                    if (openResult.success && !pkg.isNullOrEmpty()) {
                        val waitTime = adaptiveTiming.smartWait(pkg, systemInfo)
                        log("应用加载等待：${waitTime}ms（自适应）")
                    }
                    openResult
                }
                ActionType.APP_CLOSE -> {
                    val pkg = action.packageName
                    if (!pkg.isNullOrEmpty()) shell.closeApp(pkg)
                    else ClawActionResult.failure("APP_CLOSE 缺少 packageName 参数")
                }
                ActionType.APP_INSTALL -> {
                    val path = action.apkPath
                    if (!path.isNullOrEmpty()) shell.installApp(path)
                    else ClawActionResult.failure("APP_INSTALL 缺少 apkPath 参数")
                }
                ActionType.APP_UNINSTALL -> {
                    val pkg = action.packageName
                    if (!pkg.isNullOrEmpty()) shell.uninstallApp(pkg)
                    else ClawActionResult.failure("APP_UNINSTALL 缺少 packageName 参数")
                }

                // —— 应用管理（搜索与列表） ——
                ActionType.APP_LIST -> shell.listApps()
                ActionType.APP_SEARCH -> {
                    val name = action.name
                    if (!name.isNullOrEmpty()) shell.searchApp(name)
                    else ClawActionResult.failure("APP_SEARCH 缺少 name 参数")
                }

                // —— 系统信息 ——
                ActionType.SYSTEM_GET_INFO -> systemInfo.getSystemInfo(action.systemInfoType)
                ActionType.SYSTEM_KILL_PROCESS -> {
                    val pid = action.pid
                    if (pid != null) systemInfo.killProcess(pid)
                    else ClawActionResult.failure("SYSTEM_KILL_PROCESS 缺少 pid 参数")
                }
                ActionType.SYSTEM_CLEAR_CACHE -> systemInfo.clearCache()

                // —— Shell / 文件 / 通知 ——
                ActionType.SHELL_EXEC -> {
                    val cmd = action.command
                    if (!cmd.isNullOrEmpty()) shell.exec(cmd)
                    else ClawActionResult.failure("SHELL_EXEC 缺少 command 参数")
                }
                ActionType.FILE_READ -> {
                    val path = action.path
                    if (!path.isNullOrEmpty()) shell.readFile(path)
                    else ClawActionResult.failure("FILE_READ 缺少 path 参数")
                }
                ActionType.FILE_WRITE -> {
                    val path = action.path
                    val content = action.content
                    if (!path.isNullOrEmpty() && content != null) shell.writeFile(path, content)
                    else ClawActionResult.failure("FILE_WRITE 缺少 path/content 参数")
                }
                ActionType.NOTIFY_READ -> shell.readNotifications()
                ActionType.NOTIFY_SEND -> {
                    val title = action.notifyTitle ?: "MobileClaw"
                    val content = action.notifyContent
                    if (!content.isNullOrEmpty()) shell.sendNotification(title, content)
                    else ClawActionResult.failure("NOTIFY_SEND 缺少 content 参数")
                }

                // —— 新增屏幕交互 ——
                ActionType.SCREEN_DOUBLE_CLICK -> {
                    val x = action.x
                    val y = action.y
                    val text = action.text
                    when {
                        x != null && y != null -> screen.doubleClick(x, y, text)
                        !text.isNullOrEmpty() -> screen.doubleClick(null, null, text)
                        else -> ClawActionResult.failure("SCREEN_DOUBLE_CLICK 缺少 x/y 或 text 参数")
                    }
                }
                ActionType.SCREEN_GET_TEXT -> screen.getScreenText()
                ActionType.SCREEN_TEXT_EXISTS -> {
                    val text = action.text
                    if (!text.isNullOrEmpty()) screen.textExists(text)
                    else ClawActionResult.failure("SCREEN_TEXT_EXISTS 缺少 text 参数")
                }

                // —— 系统控制 ——
                ActionType.SYSTEM_SET_VOLUME -> {
                    val vol = action.volume
                    if (vol != null) shell.setVolume(vol)
                    else ClawActionResult.failure("SYSTEM_SET_VOLUME 缺少 volume 参数")
                }
                ActionType.SYSTEM_SET_BRIGHTNESS -> {
                    val bright = action.brightness
                    if (bright != null) shell.setBrightness(bright)
                    else ClawActionResult.failure("SYSTEM_SET_BRIGHTNESS 缺少 brightness 参数")
                }

                // —— 剪贴板 ——
                ActionType.CLIPBOARD_COPY -> {
                    val text = action.text
                    if (!text.isNullOrEmpty()) shell.clipboardCopy(text)
                    else ClawActionResult.failure("CLIPBOARD_COPY 缺少 text 参数")
                }
                ActionType.CLIPBOARD_PASTE -> shell.clipboardPaste()

                // —— 媒体控制 ——
                ActionType.MEDIA_CONTROL -> {
                    val mediaAction = action.mediaAction
                    if (!mediaAction.isNullOrEmpty()) shell.mediaControl(mediaAction)
                    else ClawActionResult.failure("MEDIA_CONTROL 缺少 mediaAction 参数")
                }

                // —— 定时器 ——
                ActionType.TIMER_SET -> {
                    val duration = action.duration
                    if (duration != null && duration > 0) shell.setTimer(duration)
                    else ClawActionResult.failure("TIMER_SET 缺少有效 duration 参数")
                }

                // —— 直接回答 ——
                ActionType.ANSWER -> {
                    // 理论上 ANSWER 在 runTask 中已被拦截，这里兜底
                    ClawActionResult.success(action.text ?: "")
                }

                // —— 自定义动作 ——
                ActionType.CUSTOM -> {
                    ClawActionResult.failure("自定义动作不支持直接分发：${action.actionName}")
                }

                null -> ClawActionResult.failure("无法识别的动作类型：${action.actionName}")
            }
        } catch (e: Throwable) {
            ClawActionResult.failure("执行异常：${e.message}")
        }
    }

    // =========================================================================
    //  人类可读总结（防止 JSON 泄漏）
    // =========================================================================

    /**
     * 将 agentic loop 期间执行过的动作列表转换为人类可读的自然语言总结。
     *
     * 用于在 AI 未给出 ANSWER、或其输出疑似原始 JSON 时，作为兜底的用户反馈，
     * 避免把结构化指令（JSON）泄露给用户。
     *
     * @param actions 已执行的动作列表（不含 ANSWER）
     * @return 自然语言总结文本
     */
    private fun generateHumanReadableSummary(actions: List<ClawAction>): String {
        if (actions.isEmpty()) return "任务执行完毕。"

        val parts = mutableListOf<String>()
        
        // Group by action type for natural language summary
        val openedApps = actions.filter { it.type == ActionType.APP_OPEN }
            .mapNotNull { it.packageName ?: it.name }
        val clickedTexts = actions.filter { 
            it.type == ActionType.SCREEN_CLICK_TEXT || it.type == ActionType.SCREEN_FIND_AND_CLICK 
        }.mapNotNull { it.text }
        val inputTexts = actions.filter { it.type == ActionType.SCREEN_INPUT }
            .mapNotNull { it.text }
        val pressedKeys = actions.filter { it.type == ActionType.SCREEN_KEY }
            .mapNotNull { it.keyName }
        val screenshots = actions.count { it.type == ActionType.SCREEN_SCREENSHOT }
        val swipes = actions.count { 
            it.type == ActionType.SCREEN_SWIPE || it.type == ActionType.SCREEN_SCROLL_TO_TEXT 
        }
        val closedApps = actions.filter { it.type == ActionType.APP_CLOSE }
            .mapNotNull { it.packageName }
        val systemOps = actions.filter {
            it.type == ActionType.SYSTEM_GET_INFO || it.type == ActionType.SYSTEM_CLEAR_CACHE ||
            it.type == ActionType.SYSTEM_SET_VOLUME || it.type == ActionType.SYSTEM_SET_BRIGHTNESS
        }
        val clipboardOps = actions.filter {
            it.type == ActionType.CLIPBOARD_COPY || it.type == ActionType.CLIPBOARD_PASTE
        }
        val mediaOps = actions.filter { it.type == ActionType.MEDIA_CONTROL }

        if (openedApps.isNotEmpty()) parts.add("打开了${openedApps.joinToString("、")}")
        if (closedApps.isNotEmpty()) parts.add("关闭了${closedApps.joinToString("、")}")
        if (clickedTexts.isNotEmpty()) parts.add("点击了${clickedTexts.joinToString("、")}")
        if (inputTexts.isNotEmpty()) parts.add("输入了${inputTexts.joinToString("、")}")
        if (pressedKeys.isNotEmpty()) parts.add("按了${pressedKeys.joinToString("、")}键")
        if (screenshots > 0) parts.add("截了${screenshots}张图")
        if (swipes > 0) parts.add("滑动了${swipes}次")
        if (systemOps.isNotEmpty()) {
            systemOps.forEach { parts.add(ActionTranslator.describeAction(it)) }
        }
        if (clipboardOps.isNotEmpty()) {
            clipboardOps.forEach { parts.add(ActionTranslator.describeAction(it)) }
        }
        if (mediaOps.isNotEmpty()) {
            mediaOps.forEach { parts.add(ActionTranslator.describeAction(it)) }
        }

        // Other actions
        val otherActions = actions.filter {
            it.type != ActionType.APP_OPEN && it.type != ActionType.APP_CLOSE &&
            it.type != ActionType.SCREEN_CLICK_TEXT && it.type != ActionType.SCREEN_FIND_AND_CLICK &&
            it.type != ActionType.SCREEN_INPUT && it.type != ActionType.SCREEN_KEY &&
            it.type != ActionType.SCREEN_SCREENSHOT && it.type != ActionType.SCREEN_SWIPE &&
            it.type != ActionType.SCREEN_SCROLL_TO_TEXT &&
            it.type != ActionType.SYSTEM_GET_INFO && it.type != ActionType.SYSTEM_CLEAR_CACHE &&
            it.type != ActionType.SYSTEM_SET_VOLUME && it.type != ActionType.SYSTEM_SET_BRIGHTNESS &&
            it.type != ActionType.CLIPBOARD_COPY && it.type != ActionType.CLIPBOARD_PASTE &&
            it.type != ActionType.MEDIA_CONTROL && it.type != ActionType.ANSWER
        }
        if (otherActions.isNotEmpty()) {
            parts.add(otherActions.joinToString("；") { ActionTranslator.describeAction(it) })
        }

        return if (parts.isNotEmpty()) "已为你${parts.joinToString("，")}。" else "任务执行完毕。"
    }

    /**
     * 防止原始 JSON 泄漏给用户：若 [raw] 疑似 JSON 文本，则改用执行动作的总结替代。
     *
     * 检测模式：
     * - 以 { 或 [ 开头的纯 JSON
     * - 包裹在 ```json ... ``` 代码块中的 JSON
     * - 包含 "action":" 或 "actions":[ 的 JSON 片段
     * - 包含 "params":{ 的 JSON 片段
     *
     * @param raw 待校验的最终回答文本
     * @param actions 已执行的动作列表，用于生成兜底总结
     * @return 安全的、人类可读的最终回答
     */
    private fun sanitizeFinalAnswer(raw: String?, actions: List<ClawAction>): String {
        if (raw == null) return generateHumanReadableSummary(actions)
        val trimmed = raw.trim()

        // 检测1：以 { 或 [ 开头
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return generateHumanReadableSummary(actions)
        }

        // 检测2：```json 代码块包裹
        if (trimmed.startsWith("```")) {
            return generateHumanReadableSummary(actions)
        }

        // 检测3：包含 JSON 动作关键字（说明 AI 返回了原始指令而非人类可读回答）
        val jsonIndicators = listOf(
            "\"action\":", "\"actions\":", "\"params\":",
            "\"action_name\":", "\"packageName\":",
            "\"description\":\"打开", "\"description\":\"点击"
        )
        if (jsonIndicators.any { trimmed.contains(it, ignoreCase = true) }) {
            return generateHumanReadableSummary(actions)
        }

        // 检测4：过长的 JSON 片段（超过50字符且包含多个引号和冒号）
        val quoteCount = trimmed.count { it == '"' }
        val colonCount = trimmed.count { it == ':' }
        if (quoteCount >= 6 && colonCount >= 3 && trimmed.length > 50) {
            return generateHumanReadableSummary(actions)
        }

        return raw
    }

    // =========================================================================
    //  日志与状态
    // =========================================================================

    /** 记录一条操作日志。 */
    private fun log(message: String) {
        val entry = OperationLog(
            timestamp = System.currentTimeMillis(),
            timeText = timeFormatter.format(Date()),
            message = message
        )
        synchronized(_logList) {
            _logList.add(entry)
            // 防止日志无限增长
            if (_logList.size > MAX_LOG_SIZE) {
                _logList.removeAt(0)
            }
        }
    }

    /** 发射状态更新（同时写入日志与 SharedFlow）。 */
    private fun emitStatus(status: String) {
        log(status)
        scope.launch { _statusFlow.emit(status) }
    }

    companion object {
        private const val TAG = "ClawController"

        /** Agentic loop 默认最大迭代轮数。 */
        private const val DEFAULT_MAX_ITERATIONS = 10

        /** 日志最大条数。 */
        private const val MAX_LOG_SIZE = 500
    }
}


// =============================================================================
//  回调接口
// =============================================================================

/**
 * UI 回调接口，用于通知界面各阶段进展。
 *
 * 所有方法默认空实现，UI 可按需覆写感兴趣的回调。
 * 注意：这些回调在控制器协程线程触发，UI 更新需自行切回主线程。
 */
interface ClawCallback {
    /** 状态更新（如「采集中」「执行第 N 步」「完成」）。 */
    fun onStatusUpdate(status: String) {}

    /** 某个动作即将执行。 */
    fun onActionExecuting(action: ClawAction) {}

    /** 某个动作执行完成。 */
    fun onActionComplete(action: ClawAction, result: ClawActionResult) {}

    /** 发生错误。 */
    fun onError(error: Throwable) {}

    /** 任务完成，返回最终结果文本。 */
    fun onFinalResult(result: String) {}
}

/**
 * 操作日志条目。
 *
 * @param timestamp 毫秒时间戳
 * @param timeText 格式化后的时间字符串
 * @param message 日志内容
 */
data class OperationLog(
    val timestamp: Long,
    val timeText: String,
    val message: String
)


// =============================================================================
//  执行器接口（由具体实现注入，控制器只面向接口编程）
// =============================================================================

/**
 * 屏幕控制器：负责所有屏幕级交互。
 * 通常基于无障碍服务（AccessibilityService）或 Shizuku 注入手势实现。
 */
interface ScreenController {
    /** 点击屏幕坐标 (x, y)。 */
    suspend fun click(x: Int, y: Int): ClawActionResult

    /** 点击包含指定文本的元素。 */
    suspend fun clickText(text: String): ClawActionResult

    /** 长按屏幕坐标或文本元素。 */
    suspend fun longClick(x: Int?, y: Int?, text: String?): ClawActionResult

    /** 双击屏幕坐标或文本元素。 */
    suspend fun doubleClick(x: Int?, y: Int?, text: String?): ClawActionResult

    /** 查找并点击：自动滚动查找指定文本并点击。 */
    suspend fun findAndClick(text: String): ClawActionResult

    /** 滚动到指定文本可见。 */
    suspend fun scrollToText(text: String): ClawActionResult

    /** 按坐标滑动：(x1,y1) -> (x2,y2)。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int): ClawActionResult

    /** 按方向滑动。 */
    suspend fun swipeDirection(direction: SwipeDirection): ClawActionResult

    /** 在当前焦点输入框输入文本。 */
    suspend fun inputText(text: String): ClawActionResult

    /** 按下系统按键。 */
    suspend fun pressKey(key: KeyType): ClawActionResult

    /** 截屏，返回截图保存路径。 */
    suspend fun screenshot(): ClawActionResult

    /** 等待指定毫秒。 */
    suspend fun wait(ms: Long): ClawActionResult

    /** 获取当前屏幕上的所有文本。 */
    suspend fun getScreenText(): ClawActionResult

    /** 判断指定文本是否存在于当前屏幕。 */
    suspend fun textExists(text: String): ClawActionResult
}

/**
 * 系统信息采集器：负责手机状态快照与系统信息查询。
 */
interface SystemInfoCollector {
    /** 采集当前手机状态快照（前台应用、屏幕、电量、内存、通知等）。 */
    suspend fun getCurrentState(): PhoneState

    /** 查询指定类型的系统信息。 */
    suspend fun getSystemInfo(type: SystemInfoType?): ClawActionResult

    /** 结束指定 pid 的进程。 */
    suspend fun killProcess(pid: Int): ClawActionResult

    /** 清理应用缓存。 */
    suspend fun clearCache(): ClawActionResult
}

/**
 * Shell 执行器：负责特权操作（应用管理、shell 命令、文件、通知）。
 * 通常基于 Shizuku 提权的 shell 会话实现。
 */
interface ShellExecutor {
    /** 执行 shell 命令，返回输出。 */
    suspend fun exec(command: String): ClawActionResult

    /** 打开指定包名的应用。 */
    suspend fun openApp(packageName: String): ClawActionResult

    /** 强制停止指定包名的应用。 */
    suspend fun closeApp(packageName: String): ClawActionResult

    /** 按名称搜索应用并打开（不需要知道包名）。 */
    suspend fun searchApp(name: String): ClawActionResult

    /** 列出已安装的应用。 */
    suspend fun listApps(): ClawActionResult

    /** 安装 APK（需给出文件路径）。 */
    suspend fun installApp(apkPath: String): ClawActionResult

    /** 卸载应用。 */
    suspend fun uninstallApp(packageName: String): ClawActionResult

    /** 读取文件内容。 */
    suspend fun readFile(path: String): ClawActionResult

    /** 写入文件内容。 */
    suspend fun writeFile(path: String, content: String): ClawActionResult

    /** 读取当前通知列表。 */
    suspend fun readNotifications(): ClawActionResult

    /** 发送一条本地通知。 */
    suspend fun sendNotification(title: String, content: String): ClawActionResult

    /** 复制文本到剪贴板。 */
    suspend fun clipboardCopy(text: String): ClawActionResult

    /** 粘贴剪贴板内容。 */
    suspend fun clipboardPaste(): ClawActionResult

    /** 媒体控制（播放/暂停/上一首/下一首等）。 */
    suspend fun mediaControl(action: String): ClawActionResult

    /** 设置系统音量。 */
    suspend fun setVolume(volume: Int): ClawActionResult

    /** 设置屏幕亮度。 */
    suspend fun setBrightness(brightness: Int): ClawActionResult

    /** 设置定时器。 */
    suspend fun setTimer(durationSec: Int): ClawActionResult
}
