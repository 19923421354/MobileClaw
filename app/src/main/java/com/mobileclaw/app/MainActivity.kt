package com.mobileclaw.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import com.mobileclaw.app.ai.Recording
import androidx.recyclerview.widget.LinearLayoutManager
import com.mobileclaw.app.adapter.recyclerview.ChatMessage
import com.mobileclaw.app.adapter.recyclerview.ChatMessageAdapter
import com.mobileclaw.app.databinding.ActivityMainBinding
import com.mobileclaw.app.service.ClawAgentService
import com.mobileclaw.app.shizuku.ShizukuManager
import com.mobileclaw.app.util.PermissionManager
import com.mobileclaw.app.util.VoiceInputHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 灵爪 主界面 —— AI 对话操控界面。
 *
 * 界面结构：
 * - 顶部状态栏：品牌名、AI 模型、权限状态、智能模式开关、思考模式开关
 * - 中间消息列表：用户指令与 AI 回复
 * - 底部输入栏：文本输入 + 语音输入 + 发送按钮 + 快捷操作
 *
 * 权限分级策略：
 * - 必需：无障碍服务（核心功能，没有它无法操控手机）
 * - 可选：Shizuku/STELLAR（用于高级操作，没有时自动降级）
 * - 可选：存储、悬浮窗、电池优化、自启动等增强权限
 *
 * 核心交互流程：
 * 1. 引导用户开启无障碍服务（必需）
 * 2. 无障碍激活后自动引导申请其他权限
 * 3. 配置 AI 模型 API Key（默认使用最便宜的 GLM-4.7-Flash，免费）
 * 4. 用户输入指令 -> ClawController.execute()
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatMessageAdapter()
    private var isProcessing = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 标记是否已经自动引导过权限申请，避免重复弹窗 */
    private var hasAutoRequestedPermissions = false

    /** Shizuku 状态监听协程 */
    private var shizukuStateJob: Job? = null

    /** 语音输入助手 */
    private var voiceInputHelper: VoiceInputHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 加载保存的配置
        MobileClawApp.instance.loadSavedConfig()

        // 主动初始化控制器（如果无障碍服务已连接）
        // 解决：无障碍服务已连接但未触发 onServiceConnected 回调时控制器为 null 的问题
        if (MobileClawApp.clawController == null) {
            MobileClawApp.instance.initClawController()
        }

        setupRecyclerView()
        setupClickListeners()
        setupModeSwitches()

        // 初始化语音输入助手
        voiceInputHelper = VoiceInputHelper(this)

        // 启动前台服务
        ClawAgentService.start(this)

        // 申请基本运行时权限
        PermissionManager.requestBasicPermissions(this)

        // 监听 Shizuku 状态变化，实时更新 UI
        shizukuStateJob = lifecycleScope.launch {
            ShizukuManager.state.collect { state ->
                // 状态变化时刷新 UI
                updateServiceStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 强制重新检测 Shizuku/STELLAR 连接状态
        // 解决：用户从 STELLAR 返回后，Binder 可能已推送但未检测到的问题
        ShizukuManager.forceRebind(this)
        updateServiceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        shizukuStateJob?.cancel()
        voiceInputHelper?.destroy()
        voiceInputHelper = null
    }

    private fun setupRecyclerView() {
        binding.recyclerChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerChat.adapter = chatAdapter

        // 欢迎消息
        chatAdapter.addMessage(ChatMessage(
            type = ChatMessage.TYPE_AI,
            content = "你好！我是灵爪，你的 AI 手机操控助手。\n\n" +
                    "只需开启【无障碍服务】即可使用核心功能（点击/滑动/输入/截屏等）。\n\n" +
                    "⚡ 已支持一键配置！点击右下角「⚡」按钮，有 Shizuku/STELLAR 即可一键开启无障碍，无需手动设置。\n\n" +
                    "默认使用智谱 GLM-4.7-Flash（免费模型），在设置中可切换其他模型。\n\n" +
                    "v2.0.2 升级 — 一键配置 + 执行优化：\n" +
                    "🔥 全新密钥：彻底解决覆盖安装冲突，后续版本均可覆盖安装\n" +
                    "🔥 AI Agent 引擎：ReAct 推理循环，自动调用工具完成任务\n" +
                    "🔥 15+ 内置工具：Shell命令/Python执行/文件操作/应用管理/屏幕操控\n" +
                    "🔥 Termux 集成：执行 Shell 脚本、Python 代码、Shell 命令\n" +
                    "🔥 Python 代码生成：说「写个Python爬虫」「写个计算器程序」直接生成并执行\n" +
                    "🔥 APK 项目生成：说「创建一个计算器APP」自动生成完整 Android 项目\n" +
                    "🔥 代码文件创建：生成 .py/.sh/.kt/.java 文件并保存到设备\n" +
                    "🔥 Shell 脚本执行：创建并运行 Shell 脚本，自动化设备操作\n" +
                    "🔥 智能意图推断：理解「帮我查天气」「打电话给张三」「导航到天安门」\n" +
                    "🔥 自然时间解析：支持「半小时后」「一刻钟后」「两小时后」等中文时间\n" +
                    "🔥 上下文感知：打开豆包后说「给他发你好」自动识别\n" +
                    "🔥 智能纠错学习：你说「不是A是B」，下次自动纠正\n" +
                    "🔥 智能建议：融合使用频率排序，输错命令时智能推荐\n" +
                    "🔥 300+ 应用别名：支持绿泡泡/小而美/狗东/拼夕夕/Insta/TG/奈飞等\n" +
                    "🔥 拼音首字母匹配：说「wx」→微信、「zfb」→支付宝、「dy」→抖音\n" +
                    "🔥 同音纠错：「为信」→微信、「抖印」→抖音、「支负宝」→支付宝\n" +
                    "🔥 智能搜索：直接说「查一下xxx」自动打开浏览器搜索\n" +
                    "🔥 定时命令：支持「5分钟后打开支付宝」「半小时后打开微信」\n" +
                    "🔥 查看模式：「看看微博热搜」「刷刷抖音」「浏览小红书」自动识别\n" +
                    "🔥 播放模式：「播放周杰伦的歌」「听音乐」「看视频」智能推断\n" +
                    "🔥 页面直达：「打开微信的支付页面」「打开支付宝的扫一扫」\n" +
                    "🔥 提醒模式：「提醒我明天早上8点开会」「设置提醒10分钟后关火」\n" +
                    "🔥 使用频率学习：越用越懂你，自动推荐常用应用\n" +
                    "🔥 增强操作：截屏/长截图/录屏/分屏/投屏一句话搞定\n" +
                    "• 序列命令：支持「先打开微信再打开抖音」「依次打开A和B和C」\n" +
                    "• 多步命令：支持「打开微信，然后打开抖音，最后打开支付宝」\n" +
                    "• 网络昵称：支持 VX/PDD/JD/TB/DS/WB/ZFB/IG/TG 等英文缩写\n" +
                    "• 500+ 应用识别：覆盖国内外主流应用，支持DeepSeek/Kimi等AI助手\n" +
                    "• 全新的指令模式：帮我打开XXX、发消息给XXX说YYY\n" +
                    "• 打电话模式：打电话给XXX、发短信给XXX说YYY\n" +
                    "• 权限向导：一键申请，逐个引导，缺失哪个就跳转哪个\n" +
                    "• 本地部署：内置 Qwen/Gemma/Phi/TinyLlama 等模型下载\n" +
                    "• 自适应学习引擎：从用户纠正中学习，越用越准\n" +
                    "• 任务分解器：复杂任务自动拆分为子任务\n" +
                    "• 自然语言解析器：本地意图理解，无需AI\n" +
                    "• 手势优化器：生成拟人化手势路径\n" +
                    "• 智能恢复系统：失败后自动选择最佳恢复策略\n" +
                    "• 有状态会话：跨任务记忆对话上下文\n" +
                    "• 错误关联引擎：跨任务分析错误根本原因\n\n" +
                    "你可以用自然语言指挥我，例如：\n" +
                    "• 打开微信\n" +
                    "• 打开抖音并搜索猫咪\n" +
                    "• 打开豆包，并给豆包发一条你好\n" +
                    "• 帮我打开小红书\n" +
                    "• 5分钟后打开支付宝\n" +
                    "• 半小时后打开微信\n" +
                    "• 先打开微信再打开抖音\n" +
                    "• 发消息给张三说我晚点到\n" +
                    "• 打电话给张三\n" +
                    "• 帮我查一下今天的天气\n" +
                    "• 导航到天安门\n" +
                    "• 打开绿泡泡\n" +
                    "• 截个屏\n" +
                    "• 清理缓存\n" +
                    "• 写一个 Python 爬虫\n" +
                    "• 创建一个计算器 APP\n" +
                    "• 写一个 Python 脚本排序文件\n" +
                    "• 生成一个简单的 Shell 脚本\n" +
                    "• 执行 ls 命令查看目录\n" +
                    "• 安装这个 APK 文件"
        ))
    }

    private fun setupClickListeners() {
        // 发送按钮
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        // 设置按钮
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // 快捷设置按钮
        binding.btnQuickSettings.setOnClickListener {
            showPermissionDialog()
        }

        // 本地模型管理按钮 - 打开专用模型管理界面
        binding.btnLocalModel.setOnClickListener {
            val intent = Intent(this, ModelManagementActivity::class.java)
            startActivity(intent)
        }

        // 权限管理按钮（点击状态栏区域）
        binding.layoutShizukuStatus.setOnClickListener {
            showPermissionDialog()
        }

        binding.layoutAccessibilityStatus.setOnClickListener {
            showPermissionDialog()
        }

        // 快捷按钮
        binding.btnScreenshot.setOnClickListener {
            sendMessage("截个屏")
        }
        binding.btnMemory.setOnClickListener {
            sendMessage("查看内存使用情况")
        }
        binding.btnBack.setOnClickListener {
            sendMessage("按返回键")
        }
        binding.btnHome.setOnClickListener {
            sendMessage("按Home键")
        }
        binding.btnClean.setOnClickListener {
            sendMessage("清理缓存")
        }

        // 语音输入按钮
        binding.btnVoice.setOnClickListener {
            startVoiceInput()
        }
    }

    /**
     * 初始化智能 Token 模式和思考模式开关。
     */
    private fun setupModeSwitches() {
        val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)
        // 智能模式开关（向后兼容旧的 token_saving 键）
        val intelligentEnabled = if (prefs.contains("intelligent_mode")) {
            prefs.getBoolean("intelligent_mode", true)
        } else {
            prefs.getBoolean("token_saving", true)
        }
        binding.switchTokenSaving.isChecked = intelligentEnabled
        binding.switchTokenSaving.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit()
                .putBoolean("intelligent_mode", isChecked)
                .putBoolean("token_saving", isChecked) // 同步旧键，避免冲突
                .apply()
            MobileClawApp.clawController?.let { controller ->
                try {
                    val gatewayField = controller.javaClass.getDeclaredField("gateway")
                    gatewayField.isAccessible = true
                    val gateway = gatewayField.get(controller) as com.mobileclaw.app.ai.AIGateway
                    gateway.intelligentMode = isChecked
                } catch (e: Exception) {
                    // 忽略
                }
            }
            showToast(if (isChecked) "智能模式已开启：按任务复杂度动态调节Token" else "智能模式已关闭：使用最大质量模式")
        }

        // 思考模式开关
        binding.switchThinking.isChecked = getSharedPreferences("mobileclaw", MODE_PRIVATE)
            .getBoolean("thinking_mode", false)
        binding.switchThinking.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("mobileclaw", MODE_PRIVATE).edit()
                .putBoolean("thinking_mode", isChecked).apply()
            MobileClawApp.clawController?.let { controller ->
                try {
                    val gatewayField = controller.javaClass.getDeclaredField("gateway")
                    gatewayField.isAccessible = true
                    val gateway = gatewayField.get(controller) as com.mobileclaw.app.ai.AIGateway
                    gateway.thinkingMode = isChecked
                } catch (e: Exception) {
                    // 忽略
                }
            }
            showToast(if (isChecked) "思考模式已开启" else "思考模式已关闭")
        }

        // 本地模型模式开关
        binding.switchLocalModel.isChecked = getSharedPreferences("mobileclaw", MODE_PRIVATE)
            .getBoolean("local_inference", false)
        binding.switchLocalModel.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("mobileclaw", MODE_PRIVATE).edit()
                .putBoolean("local_inference", isChecked).apply()
            MobileClawApp.clawController?.let { controller ->
                if (isChecked) {
                    val available = controller.isLocalModelAvailable()
                    if (!available) {
                        showToast("本地模型未加载，请先在模型管理中下载并加载模型")
                        // 但仍然设为本地模式，加载模型后会自动生效
                    }
                    controller.setInferenceMode(com.mobileclaw.app.ai.InferenceMode.LOCAL_ONLY)
                    showToast("已切换到本地模型模式")
                } else {
                    controller.setInferenceMode(com.mobileclaw.app.ai.InferenceMode.CLOUD_ONLY)
                    showToast("已切换到云端 API 模式")
                }
            }
        }
    }

    private fun sendMessage(text: String? = null) {
        val message = text ?: binding.editInput.text.toString().trim()
        if (message.isEmpty() || isProcessing) return

        // 检查前置条件：无障碍服务 + API 配置
        val controller = MobileClawApp.clawController
        if (controller == null) {
            // 无障碍服务未连接，直接引导快捷配置
            PermissionManager.quickSetup(this)
            return
        }

        // 检查 API Key 是否已配置（通过反射读取 gateway 的配置状态）
        val apiKeyConfigured = try {
            val gatewayField = controller.javaClass.getDeclaredField("gateway")
            gatewayField.isAccessible = true
            val gateway = gatewayField.get(controller)
            val isConfiguredMethod = gateway.javaClass.getMethod("isConfigured")
            isConfiguredMethod.invoke(gateway) as Boolean
        } catch (e: Exception) { false }
        if (!apiKeyConfigured) {
            showToast("请先在设置中配置 API Key")
            showSettingsDialog()
            return
        }

        isProcessing = true
        binding.btnSend.isEnabled = false
        binding.btnSend.text = "执行中..."
        binding.editInput.text.clear()

        // 隐藏键盘
        currentFocus?.let {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }

        // 显示用户消息
        chatAdapter.addMessage(ChatMessage(
            type = ChatMessage.TYPE_USER,
            content = message
        ))
        scrollToBottom()

        // 添加 AI "思考中" 消息（含评估预览）
        val complexityPreview = com.mobileclaw.app.ai.TaskComplexityAnalyzer.analyze(message)
        val complexityTag = when (complexityPreview) {
            com.mobileclaw.app.ai.TaskComplexityAnalyzer.Complexity.MICRO -> "[微操作] "
            com.mobileclaw.app.ai.TaskComplexityAnalyzer.Complexity.SIMPLE -> "[简单] "
            com.mobileclaw.app.ai.TaskComplexityAnalyzer.Complexity.MEDIUM -> "[中等] "
            com.mobileclaw.app.ai.TaskComplexityAnalyzer.Complexity.COMPLEX -> "[复杂] "
            com.mobileclaw.app.ai.TaskComplexityAnalyzer.Complexity.UNLIMITED -> ""
        }
        val thinkingMsg = ChatMessage(
            type = ChatMessage.TYPE_AI,
            content = "${complexityTag}正在处理...",
            isThinking = true
        )
        // 注：如果配置了评估器，实际标签会在 AI 响应后通过 onStatusUpdate 更新
        chatAdapter.addMessage(thinkingMsg)
        scrollToBottom()

        // 执行
        controller.execute(message, object : com.mobileclaw.app.ai.ClawCallback {
            override fun onStatusUpdate(status: String) {
                runOnUiThread {
                    thinkingMsg.content = status
                    chatAdapter.notifyItemChanged(chatAdapter.itemCount - 1)
                }
            }

            override fun onActionExecuting(action: com.mobileclaw.app.ai.ClawAction) {
                runOnUiThread {
                    val desc = com.mobileclaw.app.ai.ActionTranslator.describeAction(action)
                    thinkingMsg.content = "正在执行: $desc"
                    chatAdapter.notifyItemChanged(chatAdapter.itemCount - 1)
                }
            }

            override fun onActionComplete(action: com.mobileclaw.app.ai.ClawAction, result: com.mobileclaw.app.ai.ClawActionResult) {
                runOnUiThread {
                    if (!result.success) {
                        thinkingMsg.content = "操作失败: ${result.message}"
                    }
                    chatAdapter.notifyItemChanged(chatAdapter.itemCount - 1)
                }
            }

            override fun onError(error: Throwable) {
                runOnUiThread {
                    val errorMsg = error.message ?: error.javaClass.simpleName
                    // 给出更友好的错误提示
                    val friendlyMsg = when {
                        errorMsg.contains("HTTP 401") -> "API Key 无效或已过期，请在设置中检查配置"
                        errorMsg.contains("HTTP 404") -> "API 地址或模型名称错误，请在设置中检查"
                        errorMsg.contains("HTTP 429") -> "请求过于频繁，请稍后再试"
                        errorMsg.contains("HTTP 5") -> "AI 服务端暂时不可用，请稍后再试"
                        errorMsg.contains("Unable to resolve host") || errorMsg.contains("timeout") ->
                            "网络连接失败，请检查网络后重试"
                        errorMsg.contains("尚未配置") -> "请先在设置中配置 AI 模型的 API Key"
                        else -> "出错: $errorMsg"
                    }
                    thinkingMsg.content = friendlyMsg
                    thinkingMsg.isThinking = false
                    chatAdapter.notifyItemChanged(chatAdapter.itemCount - 1)
                    resetInputState()
                }
            }

            override fun onFinalResult(result: String) {
                runOnUiThread {
                    // 移除 "思考中" 消息
                    chatAdapter.removeLast()
                    // 添加最终结果
                    chatAdapter.addMessage(ChatMessage(
                        type = ChatMessage.TYPE_AI,
                        content = result
                    ))
                    scrollToBottom()
                    resetInputState()
                }
            }
        })

        // 安全超时：如果 120 秒后仍未收到任何回调，强制重置状态
        handler.postDelayed({
            if (isProcessing) {
                android.util.Log.e("MainActivity", "任务超时120秒未响应，强制重置")
                runOnUiThread {
                    thinkingMsg.content = "请求超时，请检查网络和 API 配置后重试"
                    thinkingMsg.isThinking = false
                    chatAdapter.notifyItemChanged(chatAdapter.itemCount - 1)
                    resetInputState()
                }
            }
        }, 120_000L)
    }

    private fun resetInputState() {
        isProcessing = false
        binding.btnSend.isEnabled = true
        binding.btnSend.text = "发送"
    }

    private fun scrollToBottom() {
        binding.recyclerChat.scrollToPosition(chatAdapter.itemCount - 1)
    }

    /**
     * 更新顶部状态栏显示。
     * 统一展示权限状态，不再区分 Shizuku 和无障碍的独立状态行。
     */
    private fun updateServiceStatus() {
        val permissions = PermissionManager.getAllPermissions(this)
        val grantedCount = permissions.count { it.granted }
        val totalCount = permissions.size

        // 权限总览状态
        val accessibilityReady = permissions.first { it.type == PermissionManager.PermissionType.ACCESSIBILITY }.granted
        val aiReady = MobileClawApp.aiConfig.apiKey.isNotEmpty()

        // 无障碍状态（必需）
        binding.txtAccessibilityStatus.text = if (accessibilityReady) "✓ 无障碍已开启" else "✗ 无障碍未开启"
        binding.txtAccessibilityStatus.setTextColor(
            getColor(if (accessibilityReady) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        // 权限总览
        binding.txtShizukuStatus.text = "权限 $grantedCount/$totalCount"
        binding.txtShizukuStatus.setTextColor(
            getColor(if (grantedCount >= 4) android.R.color.holo_green_dark else android.R.color.holo_orange_dark)
        )

        // AI 配置状态
        binding.txtAiStatus.text = if (aiReady) "AI: ${MobileClawApp.aiConfig.model}" else "AI: 未配置"
        binding.txtAiStatus.setTextColor(
            getColor(if (aiReady) android.R.color.holo_green_dark else android.R.color.holo_red_dark)
        )

        // 只要无障碍服务就绪，就可以初始化控制器（不需要 Shizuku）
        if (accessibilityReady && MobileClawApp.clawController == null) {
            MobileClawApp.instance.onAccessibilityConnected()
            // 无障碍刚激活时，自动引导用户申请其他权限（仅一次）
            if (!hasAutoRequestedPermissions) {
                hasAutoRequestedPermissions = true
                PermissionManager.autoRequestPermissionsAfterAccessibility(this)
            }
        }

        // 同步开关状态到 gateway
        MobileClawApp.clawController?.let { controller ->
            try {
                val gatewayField = controller.javaClass.getDeclaredField("gateway")
                gatewayField.isAccessible = true
                val gateway = gatewayField.get(controller) as com.mobileclaw.app.ai.AIGateway
                gateway.intelligentMode = binding.switchTokenSaving.isChecked
                gateway.thinkingMode = binding.switchThinking.isChecked
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    /**
     * 显示权限管理对话框。
     * 列出所有权限状态，点击可跳转到对应设置页面。
     */
    private fun showPermissionDialog() {
        val permissions = PermissionManager.getAllPermissions(this)

        val sb = StringBuilder()
        sb.appendLine("权限状态总览：")
        sb.appendLine()

        permissions.forEachIndexed { index, perm ->
            val icon = if (perm.granted) "✅" else if (perm.required) "❌" else "⚠️"
            sb.appendLine("${index + 1}. $icon ${perm.name}${if (perm.required) " (必需)" else ""}")
            sb.appendLine("   ${perm.description}")
            if (!perm.granted) {
                sb.appendLine("   >>> 点击「去设置」开启此权限")
            }
            sb.appendLine()
        }

        val missingRequired = permissions.any { it.required && !it.granted }
        val missingOptional = permissions.any { !it.required && !it.granted }

        sb.appendLine("---")
        if (missingRequired) {
            sb.appendLine("⚠️ 必需权限未开启，核心功能无法使用！")
        } else if (missingOptional) {
            sb.appendLine("可选权限未全部开启，部分高级功能可能受限。")
            sb.appendLine("当前无需 STELLAR 也可使用基本功能。")
        } else {
            sb.appendLine("✅ 所有权限已就绪！")
        }

        AlertDialog.Builder(this)
            .setTitle("权限管理")
            .setMessage(sb.toString())
            .setPositiveButton("⚡ 一键快捷配置") { _, _ ->
                PermissionManager.quickSetup(this)
            }
            .setNeutralButton("完整引导") { _, _ ->
                PermissionManager.requestAllPermissions(this)
            }
            .setNegativeButton("逐个设置") { _, _ ->
                showIndividualPermissionDialog(permissions)
            }
            .show()
    }

    /**
     * 逐个权限设置对话框。
     */
    private fun showIndividualPermissionDialog(permissions: List<PermissionManager.PermissionStatus>) {
        val items = permissions.map { perm ->
            val icon = if (perm.granted) "✅" else if (perm.required) "❌" else "⚠️"
            "$icon ${perm.name}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要设置的权限")
            .setItems(items) { _, which ->
                val perm = permissions[which]
                PermissionManager.requestPermission(this, perm.type)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 显示安装调试软件引导对话框。
     */
    private fun showInstallDebugAppDialog() {
        val items = arrayOf(
            "下载 STELLAR（推荐，Shizuku 兼容）",
            "下载 Shizuku（官方版）",
            "查看使用教程"
        )

        AlertDialog.Builder(this)
            .setTitle("安装调试软件")
            .setMessage("调试软件（STELLAR/Shizuku）可以提供高级系统操作权限，如执行 Shell 命令、安装应用等。\n\n" +
                    "STELLAR 是基于 Shizuku 的增强版，完全兼容 Shizuku 协议，推荐使用。")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> PermissionManager.downloadAndInstallStellar(this)
                    1 -> PermissionManager.downloadAndInstallShizuku(this)
                    2 -> PermissionManager.openShizukuInstallGuide(this)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val editApiKey = dialogView.findViewById<android.widget.EditText>(R.id.editApiKey)
        val editBaseUrl = dialogView.findViewById<android.widget.EditText>(R.id.editBaseUrl)
        val editModel = dialogView.findViewById<android.widget.EditText>(R.id.editModel)
        val spinnerPreset = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerPreset)
        val switchTokenSaving = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchTokenSaving)
        val switchThinking = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchThinking)
        val switchActionVerification = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchActionVerification)
        val switchLocalModel = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchLocalModel)
        val switchIntelligentContext = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchIntelligentContext)
        val switchProactiveAnalysis = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchProactiveAnalysis)
        val switchPipelineOptimization = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchPipelineOptimization)
        val switchEnhancedFeedback = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchEnhancedFeedback)

        // 评估器配置控件
        val editEvaluatorApiKey = dialogView.findViewById<android.widget.EditText>(R.id.editEvaluatorApiKey)
        val editEvaluatorBaseUrl = dialogView.findViewById<android.widget.EditText>(R.id.editEvaluatorBaseUrl)
        val editEvaluatorModel = dialogView.findViewById<android.widget.EditText>(R.id.editEvaluatorModel)
        val spinnerEvaluatorPreset = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerEvaluatorPreset)

        // 预设选项（含官网链接，方便用户获取 API Key）
        data class ModelPreset(
            val name: String, val baseUrl: String, val model: String,
            val isThinking: Boolean, val website: String, val apiKeyHint: String
        )
        val presets = listOf(
            ModelPreset("智谱 GLM-4.7-Flash（免费，200K）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash", false,
                "open.bigmodel.cn", "在 open.bigmodel.cn 注册免费获取"),
            ModelPreset("DeepSeek V4-Flash（极便宜）", "https://api.deepseek.com", "deepseek-v4-flash", false,
                "platform.deepseek.com", "在 platform.deepseek.com 获取"),
            ModelPreset("通义千问 qwen-turbo（便宜）", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-turbo", false,
                "dashscope.aliyun.com", "在 dashscope.aliyun.com 获取"),
            ModelPreset("豆包 doubao-lite-32k（便宜）", "https://ark.cn-beijing.volces.com/api/v3", "doubao-lite-32k", false,
                "volcengine.com/product/doubao", "在 volcengine.com 获取"),
            ModelPreset("通义千问 qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus", false,
                "dashscope.aliyun.com", "在 dashscope.aliyun.com 获取"),
            ModelPreset("DeepSeek V4-Pro（思考模型）", "https://api.deepseek.com", "deepseek-v4-pro", true,
                "platform.deepseek.com", "在 platform.deepseek.com 获取"),
            ModelPreset("智谱 GLM-4.7（旗舰）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7", false,
                "open.bigmodel.cn", "在 open.bigmodel.cn 获取"),
            ModelPreset("智谱 GLM-5.2（最新/Coding）", "https://open.bigmodel.cn/api/paas/v4", "glm-5.2", false,
                "open.bigmodel.cn", "在 open.bigmodel.cn 获取")
        )

        // 标记：区分初始化触发 vs 用户手动选择
        var isUserPresetSelection = false
        var isUserEvaluatorSelection = false

        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, presets.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPreset.adapter = adapter

        spinnerPreset.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUserPresetSelection) return
                val preset = presets[position]
                // 用户手动切换预设：自动填入 URL 和模型名，清空 API Key 让用户重新填写
                editBaseUrl.setText(preset.baseUrl)
                editModel.setText(preset.model)
                editApiKey.setText("")
                editApiKey.hint = preset.apiKeyHint
                switchThinking.isChecked = preset.isThinking
                showToast("已切换到${preset.name}\nAPI Key 需重新填写，官网: ${preset.website}")
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 评估器预设（推荐用最便宜的轻量模型）
        data class EvalPreset(val name: String, val baseUrl: String, val model: String)
        val evalPresets = listOf(
            EvalPreset("不使用评估器", "", ""),
            EvalPreset("智谱 GLM-4.7-Flash（免费，推荐）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.7-flash"),
            EvalPreset("DeepSeek V4-Flash（极便宜）", "https://api.deepseek.com", "deepseek-v4-flash"),
            EvalPreset("豆包 doubao-lite-32k（便宜）", "https://ark.cn-beijing.volces.com/api/v3", "doubao-lite-32k"),
            EvalPreset("通义千问 qwen-turbo（便宜）", "https://dashscope.aliyuncs.com/compatible-mode", "qwen-turbo"),
            EvalPreset("与主模型相同", "", "")
        )
        val evaluatorAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, evalPresets.map { it.name })
        evaluatorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerEvaluatorPreset.adapter = evaluatorAdapter

        spinnerEvaluatorPreset.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUserEvaluatorSelection) return
                val ep = evalPresets[position]
                if (position == 0) {
                    editEvaluatorApiKey.setText("")
                    editEvaluatorBaseUrl.setText("")
                    editEvaluatorModel.setText("")
                } else if (position == evalPresets.size - 1) {
                    // 与主模型相同
                    editEvaluatorBaseUrl.setText(editBaseUrl.text.toString())
                    editEvaluatorModel.setText(editModel.text.toString())
                    editEvaluatorApiKey.setText(editApiKey.text.toString())
                } else {
                    editEvaluatorBaseUrl.setText(ep.baseUrl)
                    editEvaluatorModel.setText(ep.model)
                    editEvaluatorApiKey.setText("")
                    editEvaluatorApiKey.hint = "评估器 API Key"
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 填入当前主模型配置
        val config = MobileClawApp.aiConfig
        editApiKey.setText(config.apiKey)
        editBaseUrl.setText(config.baseUrl)
        editModel.setText(config.model)

        // 填入当前评估器配置
        val (evalApiKey, evalBaseUrl, evalModel) = MobileClawApp.instance.getEvaluatorConfig()
        editEvaluatorApiKey.setText(evalApiKey)
        editEvaluatorBaseUrl.setText(evalBaseUrl)
        editEvaluatorModel.setText(evalModel)

        // 初始化完成，启用用户手动选择监听
        isUserPresetSelection = true
        isUserEvaluatorSelection = true

        // 填入当前模式开关状态
        val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)
        // 智能模式（向后兼容旧的 token_saving 键）
        switchTokenSaving.isChecked = if (prefs.contains("intelligent_mode")) {
            prefs.getBoolean("intelligent_mode", true)
        } else {
            prefs.getBoolean("token_saving", true)
        }
        switchThinking.isChecked = prefs.getBoolean("thinking_mode", false)
        switchActionVerification.isChecked = prefs.getBoolean("action_verification", true)
        switchLocalModel.isChecked = prefs.getBoolean("local_inference", false)
        switchIntelligentContext.isChecked = prefs.getBoolean("intelligent_context", true)
        switchProactiveAnalysis.isChecked = prefs.getBoolean("proactive_analysis", true)
        switchPipelineOptimization.isChecked = prefs.getBoolean("pipeline_optimization", true)
        switchEnhancedFeedback.isChecked = prefs.getBoolean("enhanced_feedback", true)

        AlertDialog.Builder(this)
            .setTitle("AI 模型配置")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val apiKey = editApiKey.text.toString().trim()
                val baseUrl = editBaseUrl.text.toString().trim()
                val model = editModel.text.toString().trim()

                val evalApiKeySave = editEvaluatorApiKey.text.toString().trim()
                val evalBaseUrlSave = editEvaluatorBaseUrl.text.toString().trim()
                val evalModelSave = editEvaluatorModel.text.toString().trim()

                // 保存模式开关
                prefs.edit()
                    .putBoolean("intelligent_mode", switchTokenSaving.isChecked)
                    .putBoolean("token_saving", switchTokenSaving.isChecked)
                    .putBoolean("thinking_mode", switchThinking.isChecked)
                    .putBoolean("local_inference", switchLocalModel.isChecked)
                    .putBoolean("action_verification", switchActionVerification.isChecked)
                    .putBoolean("intelligent_context", switchIntelligentContext.isChecked)
                    .putBoolean("proactive_analysis", switchProactiveAnalysis.isChecked)
                    .putBoolean("pipeline_optimization", switchPipelineOptimization.isChecked)
                    .putBoolean("enhanced_feedback", switchEnhancedFeedback.isChecked)
                    .apply()

                // 同步到主界面的开关
                binding.switchTokenSaving.isChecked = switchTokenSaving.isChecked
                binding.switchThinking.isChecked = switchThinking.isChecked
                binding.switchLocalModel.isChecked = switchLocalModel.isChecked

                // 应用新功能开关到控制器
                MobileClawApp.clawController?.let { ctrl ->
                    ctrl.enableActionVerification = switchActionVerification.isChecked
                    ctrl.enableIntelligentContext = switchIntelligentContext.isChecked
                    ctrl.enableProactiveAnalysis = switchProactiveAnalysis.isChecked
                    ctrl.enablePipelineOptimization = switchPipelineOptimization.isChecked
                    ctrl.enableEnhancedFeedback = switchEnhancedFeedback.isChecked

                    // 应用本地模型模式
                    if (switchLocalModel.isChecked) {
                        ctrl.setInferenceMode(com.mobileclaw.app.ai.InferenceMode.LOCAL_ONLY)
                    } else {
                        ctrl.setInferenceMode(com.mobileclaw.app.ai.InferenceMode.CLOUD_ONLY)
                    }
                }

                // 保存评估器配置
                MobileClawApp.instance.updateEvaluatorConfig(evalApiKeySave, evalBaseUrlSave, evalModelSave)

                if (apiKey.isNotEmpty()) {
                    MobileClawApp.instance.updateAIConfig(apiKey, baseUrl, model)
                    val evalStatus = if (evalApiKeySave.isNotEmpty()) "，评估器: $evalModelSave" else ""
                    showToast("配置已保存$evalStatus")
                    updateServiceStatus()
                } else {
                    showToast("API Key 不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("统计面板") { _, _ ->
                showStatsDialog()
            }
            .show()
    }

    /**
     * 显示统计面板对话框。
     * 展示 Token 用量、API 健康状态、任务执行指标和定时任务。
     */
    private fun showStatsDialog() {
        val controller = MobileClawApp.clawController
        if (controller == null) {
            showToast("请先开启无障碍服务")
            return
        }

        val sb = StringBuilder()
        sb.appendLine("═══ Token 用量统计 ═══")
        sb.appendLine(controller.getTokenStats())
        sb.appendLine()
        sb.appendLine("═══ 今日用量 ═══")
        sb.appendLine(controller.getTodayTokenStats())
        sb.appendLine()
        sb.appendLine("═══ API 健康状态 ═══")
        sb.appendLine(controller.getHealthStats())
        sb.appendLine()
        sb.appendLine("═══ 任务执行指标 ═══")
        sb.appendLine(controller.getPerformanceSummary())
        sb.appendLine()
        sb.appendLine("═══ 自适应时序 ═══")
        sb.appendLine(controller.getTimingStats())
        sb.appendLine()
        sb.appendLine("═══ 响应缓存 ═══")
        sb.appendLine(controller.getCacheStats())
        sb.appendLine()
        sb.appendLine("═══ 跨任务经验记忆 ═══")
        sb.appendLine(controller.getExperienceStats())
        sb.appendLine()
        sb.appendLine("═══ 动作预热器 ═══")
        sb.appendLine(controller.getPreheaterStats())
        sb.appendLine()
        sb.appendLine("═══ 智能超时管理 ═══")
        sb.appendLine(controller.getTimeoutStats())
        sb.appendLine("预估: ${controller.getEstimatedTaskDuration()}")
        sb.appendLine()
        sb.appendLine("═══ 意图预测 ═══")
        sb.appendLine(controller.getIntentPredictionStats())
        sb.appendLine()
        sb.appendLine("═══ 用户画像 ═══")
        sb.appendLine(controller.getUserProfileStats())
        sb.appendLine()
        sb.appendLine("═══ 智能重试策略 ═══")
        sb.appendLine(controller.getRetryStats())
        sb.appendLine()
        sb.appendLine("═══ 性能基线监控 ═══")
        sb.appendLine(controller.getPerformanceBaselineStats())
        sb.appendLine()
        sb.appendLine("═══ 语义去重 ═══")
        sb.appendLine(controller.getDedupStats())
        sb.appendLine()
        sb.appendLine("═══ 动作批处理 ═══")
        sb.appendLine(controller.getBatcherStats())
        sb.appendLine()
        sb.appendLine("═══ 对话摘要 ═══")
        sb.appendLine(controller.getConversationDigest())
        sb.appendLine()
        sb.appendLine("═══ 提示词优化 ═══")
        sb.appendLine(controller.getPromptOptimizationStats())
        sb.appendLine()
        sb.appendLine("═══ 操作录制 (${controller.getRecordings().size}) ═══")
        controller.getRecordings().take(5).forEach { rec ->
            sb.appendLine("  ${rec.name} [${rec.category}] 回放${rec.replayCount}次")
        }
        sb.appendLine()
        sb.appendLine("═══ 最近执行轨迹 ═══")
        sb.appendLine(controller.getLastTraceReport())
        sb.appendLine()
        // 检测到的问题
        val problems = controller.getDetectedProblems()
        if (problems.isNotEmpty()) {
            sb.appendLine("═══ 检测到的问题 ═══")
            problems.forEach { sb.appendLine("• $it") }
            sb.appendLine()
        }
        sb.appendLine("═══ 定时任务 (${controller.getScheduledTasks().size}) ═══")
        val tasks = controller.getScheduledTasks()
        if (tasks.isEmpty()) {
            sb.appendLine("暂无定时任务")
            sb.appendLine("提示：输入「10分钟后打开微信」可设置定时任务")
        } else {
            tasks.forEach { task ->
                val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date(task.executeAt))
                val typeStr = if (task.isRepeating) "周期" else "一次"
                sb.appendLine("• [$typeStr] ${task.name}")
                sb.appendLine("  执行时间: $timeStr | 指令: ${task.command.take(30)}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("统计与监控面板")
            .setMessage(sb.toString())
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空统计") { _, _ ->
                controller.clearStats()
                controller.clearTraces()
                showToast("统计和轨迹已清空")
            }
            .show()
    }

    /**
     * 显示本地模型管理对话框。
     * 展示可下载的模型列表、已下载模型、当前加载状态，支持下载/加载/删除操作。
     */
    private fun showLocalModelDialog() {
        val controller = MobileClawApp.clawController
        if (controller == null) {
            showToast("请先开启无障碍服务")
            return
        }

        val sb = StringBuilder()
        val modelSources = controller.getModelSources()
        val downloadedModels = controller.getDownloadedModels()
        val loadedModel = try {
            val lmField = controller.javaClass.getDeclaredField("localModelManager")
            lmField.isAccessible = true
            val lm = lmField.get(controller) as com.mobileclaw.app.ai.LocalModelManager
            lm.getLoadedModel()
        } catch (e: Exception) { null }

        // 当前加载状态
        sb.appendLine("═══ 本地模型状态 ═══")
        sb.appendLine(controller.getLocalModelStatus())
        sb.appendLine()

        // 已下载模型
        if (downloadedModels.isNotEmpty()) {
            sb.appendLine("═══ 已下载的模型 ═══")
            downloadedModels.forEach { dm ->
                val sizeStr = if (dm.fileSize > 1024 * 1024 * 1024) {
                    String.format("%.1f GB", dm.fileSize / (1024.0 * 1024 * 1024))
                } else {
                    String.format("%.0f MB", dm.fileSize / (1024.0 * 1024))
                }
                val loadedMark = if (loadedModel?.modelInfo?.id == dm.modelInfo.id) " [已加载 ✓]" else ""
                val validMark = if (dm.checksumValid) "" else " [校验失败]"
                sb.appendLine("• ${dm.modelInfo.name}$loadedMark$validMark")
                sb.appendLine("  大小: $sizeStr | ID: ${dm.modelInfo.id}")
            }
            sb.appendLine()
        }

        // 可下载的模型
        sb.appendLine("═══ 可下载的模型 ═══")
        modelSources.forEach { mi ->
            val alreadyDownloaded = downloadedModels.any { it.modelInfo.id == mi.id }
            val sizeStr = if (mi.size > 1024 * 1024 * 1024) {
                String.format("%.1f GB", mi.size / (1024.0 * 1024 * 1024))
            } else {
                String.format("%.0f MB", mi.size / (1024.0 * 1024))
            }
            val status = if (alreadyDownloaded) "✅ 已下载" else "⬇️ 未下载"
            val defaultMark = if (mi.isDefault) " [默认]" else ""
            sb.appendLine("$status ${mi.name}$defaultMark")
            sb.appendLine("  大小: $sizeStr | 内存: ${mi.requiredRam / (1024*1024)}MB")
            sb.appendLine("  ${mi.description.take(60)}...")
        }
        sb.appendLine()
        sb.appendLine("提示：输入「下载模型」「加载模型」等指令可操作")

        val menuItems = mutableListOf<String>()
        val menuActions = mutableListOf<() -> Unit>()

        // 根据当前状态添加操作按钮
        if (loadedModel != null) {
            menuItems.add("卸载模型：${loadedModel.modelInfo.name}")
            menuActions.add {
                controller.unloadModel(loadedModel.modelInfo.id)
                showToast("正在卸载模型...")
                handler.postDelayed({
                    showLocalModelDialog()
                }, 1000)
            }
        }

        // 为每个未下载的模型添加下载按钮
        modelSources.forEach { mi ->
            val alreadyDownloaded = downloadedModels.any { it.modelInfo.id == mi.id }
            if (!alreadyDownloaded) {
                menuItems.add("下载：${mi.name}")
                menuActions.add {
                    controller.downloadModel(mi.id)
                    showToast("开始下载 ${mi.name}...")
                    handler.postDelayed({
                        showLocalModelDialog()
                    }, 2000)
                }
            } else {
                val isLoaded = loadedModel?.modelInfo?.id == mi.id
                if (!isLoaded) {
                    menuItems.add("加载：${mi.name}")
                    menuActions.add {
                        controller.loadModel(mi.id)
                        showToast("正在加载 ${mi.name}...")
                        handler.postDelayed({
                            showLocalModelDialog()
                        }, 2000)
                    }
                }
                menuItems.add("删除：${mi.name}")
                menuActions.add {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("确认删除")
                        .setMessage("确定要删除模型「${mi.name}」吗？删除后需要重新下载。")
                        .setPositiveButton("删除") { _, _ ->
                            controller.deleteModel(mi.id)
                            showToast("已删除 ${mi.name}")
                            handler.postDelayed({
                                showLocalModelDialog()
                            }, 500)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }

        if (menuItems.isEmpty()) {
            menuItems.add("刷新列表")
            menuActions.add { showLocalModelDialog() }
        }

        // 添加导入模型按钮
        menuItems.add("📥 导入本地模型文件")
        menuActions.add { showImportModelDialog(controller) }

        AlertDialog.Builder(this)
            .setTitle("本地模型管理 v2.0.1")
            .setMessage(sb.toString())
            .setPositiveButton("刷新") { _, _ -> showLocalModelDialog() }
            .setNegativeButton("关闭", null)
            .setNeutralButton("操作...") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("选择操作")
                    .setItems(menuItems.toTypedArray()) { _, which ->
                        menuActions[which].invoke()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .show()
    }

    /**
     * 显示导入模型对话框。
     * 扫描本地 Download 目录中的 .gguf/.onnx/.tflite 文件，让用户选择导入。
     */
    private fun showImportModelDialog(controller: com.mobileclaw.app.ai.ClawController) {
        // 扫描可导入的模型文件
        val importableFiles = controller.scanImportableModels()
        if (importableFiles.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("导入模型")
                .setMessage("在 Download 目录中未找到可导入的模型文件。\n\n支持的格式：.gguf、.onnx、.tflite\n\n请将模型文件放入 Download 目录后重试。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val fileNames = importableFiles.map { File(it).name }
        AlertDialog.Builder(this)
            .setTitle("选择要导入的模型文件")
            .setItems(fileNames.toTypedArray()) { _, which ->
                val selectedPath = importableFiles[which]
                showToast("正在导入模型...")
                // 在后台协程中导入
                lifecycleScope.launch {
                    val modelId = controller.importModel(selectedPath)
                    if (modelId != null) {
                        showToast("模型导入成功！")
                    } else {
                        showToast("模型导入失败，文件可能已存在或格式不支持")
                    }
                    handler.postDelayed({
                        showLocalModelDialog()
                    }, 500)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 启动语音输入。
     * 使用 Android SpeechRecognizer 将语音转为文字，自动填入输入框。
     */
    private fun startVoiceInput() {
        val helper = voiceInputHelper ?: run {
            showToast("语音输入未初始化")
            return
        }

        if (!helper.isAvailable()) {
            showToast("设备不支持语音识别")
            return
        }

        showToast("请说话...")
        binding.btnVoice.contentDescription = "听写中..."

        helper.startListening(
            onResult = { text ->
                runOnUiThread {
                    binding.btnVoice.contentDescription = "语音输入"
                    // 将识别结果填入输入框
                    val currentText = binding.editInput.text.toString().trim()
                    binding.editInput.setText(if (currentText.isNotEmpty()) "$currentText $text" else text)
                    binding.editInput.setSelection(binding.editInput.text.length)
                    binding.editInput.requestFocus()
                    showToast("识别成功：$text")
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.btnVoice.contentDescription = "语音输入"
                    showToast("语音识别失败：$error")
                }
            }
        )
    }

    /**
     * 切换悬浮快捷面板显示状态。
     */
    private fun toggleFloatingPanel() {
        if (com.mobileclaw.app.service.FloatingPanelService.isRunning) {
            com.mobileclaw.app.service.FloatingPanelService.stop(this)
            showToast("悬浮面板已关闭")
        } else {
            if (PermissionManager.hasOverlayPermission(this)) {
                com.mobileclaw.app.service.FloatingPanelService.start(this)
                showToast("悬浮面板已开启")
            } else {
                showToast("请先授予悬浮窗权限")
                PermissionManager.requestOverlayPermission(this)
            }
        }
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
