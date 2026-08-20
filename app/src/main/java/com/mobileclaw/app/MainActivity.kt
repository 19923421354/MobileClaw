package com.mobileclaw.app

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import com.mobileclaw.app.util.UpdateChecker
import com.mobileclaw.app.util.UpdateInfo
import com.mobileclaw.app.util.UpdateNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        try {
            Log.d("MainActivity", "onCreate: step 1 - setTheme")
            AppCompatDelegate.setDefaultNightMode(getCurrentThemeMode())

            Log.d("MainActivity", "onCreate: step 2 - inflate layout")
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // 加载保存的配置
            Log.d("MainActivity", "onCreate: step 3 - loadConfig")
            MobileClawApp.instance.loadSavedConfig()

            // 主动初始化控制器（如果无障碍服务已连接）
            // 解决：无障碍服务已连接但未触发 onServiceConnected 回调时控制器为 null 的问题
            if (MobileClawApp.clawController == null) {
                MobileClawApp.instance.initClawController()
            }

            Log.d("MainActivity", "onCreate: step 4 - setupViews")
            setupRecyclerView()
            setupClickListeners()
            setupModeSwitches()

            // 初始化语音输入助手
            Log.d("MainActivity", "onCreate: step 5 - voiceInput")
            voiceInputHelper = VoiceInputHelper(this)

            // 启动前台服务
            Log.d("MainActivity", "onCreate: step 6 - startService")
            ClawAgentService.start(this)

            // 申请基本运行时权限
            Log.d("MainActivity", "onCreate: step 7 - permissions")
            PermissionManager.requestBasicPermissions(this)

            // 监听 Shizuku 状态变化，实时更新 UI
            Log.d("MainActivity", "onCreate: step 8 - observeShizuku")
            shizukuStateJob = lifecycleScope.launch {
                ShizukuManager.state.collect { state ->
                    updateServiceStatus()
                }
            }

            // 启动后自动检查更新（延迟2秒，让界面先加载完成）
            lifecycleScope.launch {
                delay(2000)
                checkForUpdatesAuto()
            }

            Log.d("MainActivity", "onCreate: complete")
        } catch (e: Throwable) {
            Log.e("MainActivity", "onCreate: CRASH at step: ${e.message}", e)
            android.util.Log.e("MainActivity", "onCreate failed", e)
            // 如果 onCreate 崩溃，尝试显示错误 Toast
            try {
                android.widget.Toast.makeText(this,
                    "启动失败: ${e.javaClass.simpleName}: ${e.message}",
                    android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Throwable) {}
            throw e // 重新抛出，让系统处理
        }
    }

    override fun onResume() {
        super.onResume()
        // 强制重新检测 Shizuku/STELLAR 连接状态
        // 解决：用户从 STELLAR 返回后，Binder 可能已推送但未检测到的问题
        ShizukuManager.forceRebind(this)
        // 刷新权限缓存（用户可能刚从设置页返回，权限状态已变）
        PermissionManager.refreshPermissions()
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
                    "v2.0.6 更新 — 省Token/无上限模式 + 智能总结 + 增强更新检查：\n" +
                    "🔥 全新「我的」个人中心：赞助开发、统计卡片、功能入口一站式集成\n" +
                    "🔥 应用内更新优化：API 失败自动回退直链下载，确保找到新版本\n" +
                    "🔥 修复检查更新问题：配合 GitHub Release 发布，现在检查更新真正可用\n" +
                    "🔥 全新「关于」对话框：版本信息、功能一览、更新日志、GitHub链接\n" +
                    "🔥 命令历史记录：自动保存最近50条指令，点击即可快速复用\n" +
                    "🔥 通知栏下载进度：大文件更新时显示通知栏实时进度\n" +
                    "🔥 设置页新增入口：底部集成「检查更新」和「赞助开发者」按钮\n" +
                    "🔥 现代化进度控件：替换废弃的 ProgressDialog，更流畅的体验\n" +
                    "🔥 微信支付 & 支付宝：赞助对话框支持双支付方式切换\n" +
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

        // 空状态快捷建议
        setupEmptyStateSuggestions()

        // 监听消息列表变化，自动切换空状态显示
        chatAdapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() = toggleEmptyState()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = toggleEmptyState()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = toggleEmptyState()
        })
    }

    /**
     * 设置空状态快捷建议芯片。
     * 消息列表为空时显示可点击的指令芯片，点击后自动发送对应命令。
     */
    private fun setupEmptyStateSuggestions() {
        val suggestions = listOf(
            "打开微信" to "打开微信",
            "打开设置" to "打开设置",
            "截屏" to "截个屏",
            "清理内存" to "清理内存",
            "查看天气" to "查看天气"
        )

        suggestions.forEach { (label, command) ->
            val chip = createSuggestionChip(label, command)
            binding.layoutSuggestionChips.addView(chip)
        }

        // 初始检查空状态
        toggleEmptyState()
    }

    /**
     * 创建一个快捷建议芯片（MaterialCardView 样式）。
     */
    private fun createSuggestionChip(label: String, command: String): android.view.View {
        val cardView = com.google.android.material.card.MaterialCardView(this).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val marginLp = layoutParams as ViewGroup.MarginLayoutParams
            marginLp.setMargins(
                resources.getDimensionPixelSize(R.dimen.chip_margin_start),
                0,
                resources.getDimensionPixelSize(R.dimen.chip_margin_end),
                resources.getDimensionPixelSize(R.dimen.chip_margin_bottom)
            )
            radius = resources.getDimensionPixelSize(R.dimen.chip_corner_radius).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(resources.getColor(R.color.chip_bg, theme))
            strokeWidth = 0
            isClickable = true
            isFocusable = true
            setPadding(
                resources.getDimensionPixelSize(R.dimen.chip_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.chip_padding_vertical),
                resources.getDimensionPixelSize(R.dimen.chip_padding_horizontal),
                resources.getDimensionPixelSize(R.dimen.chip_padding_vertical)
            )
            setOnClickListener { sendMessage(command) }
        }

        val textView = android.widget.TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(resources.getColor(R.color.chip_text_color, theme))
            gravity = android.view.Gravity.CENTER
        }
        cardView.addView(textView)

        return cardView
    }

    /**
     * 根据消息列表是否为空切换空状态建议的可见性。
     */
    private fun toggleEmptyState() {
        binding.layoutEmptyState.visibility =
            if (chatAdapter.itemCount == 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setupClickListeners() {
        // 发送按钮
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        // 个人中心按钮
        binding.btnProfile.setOnClickListener {
            showProfileDialog()
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

        // 检查更新按钮
        binding.btnUpdate.setOnClickListener {
            checkForUpdatesManual()
        }

        // 赞助开发者按钮
        binding.btnSponsor.setOnClickListener {
            showSponsorDialog()
        }

        // 关于按钮
        binding.btnAbout.setOnClickListener {
            showAboutDialog()
        }

        // 命令历史按钮
        binding.btnHistory.setOnClickListener {
            showCommandHistoryDialog()
        }

        // 清空对话按钮
        binding.btnClearChat.setOnClickListener {
            showClearChatDialog()
        }

        // 导出对话按钮
        binding.btnExportChat.setOnClickListener {
            exportChat()
        }
    }

    /**
     * 初始化智能 Token 模式和思考模式开关。
     */
    private fun setupModeSwitches() {
        val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)

        // 省Token模式开关（向后兼容旧的 token_saving 键）
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
            // 如果开启省Token模式，自动关闭无上限模式
            if (isChecked) {
                binding.switchUnlimitedMode.isChecked = false
                prefs.edit().putBoolean("unlimited_mode", false).apply()
            }
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
            showToast(if (isChecked) "省Token模式已开启：按任务复杂度动态调节Token" else "省Token模式已关闭")
        }

        // 无上限模式开关
        binding.switchUnlimitedMode.isChecked = prefs.getBoolean("unlimited_mode", false)
        binding.switchUnlimitedMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("unlimited_mode", isChecked).apply()
            // 如果开启无上限模式，自动关闭省Token模式
            if (isChecked) {
                binding.switchTokenSaving.isChecked = false
                prefs.edit().putBoolean("intelligent_mode", false)
                    .putBoolean("token_saving", false).apply()
            }
            MobileClawApp.clawController?.let { controller ->
                try {
                    val gatewayField = controller.javaClass.getDeclaredField("gateway")
                    gatewayField.isAccessible = true
                    val gateway = gatewayField.get(controller) as com.mobileclaw.app.ai.AIGateway
                    gateway.unlimitedMode = isChecked
                    gateway.intelligentMode = !isChecked
                } catch (e: Exception) {
                    // 忽略
                }
            }
            showToast(if (isChecked) "无上限模式已开启：不限制Token，使用最大质量配置" else "无上限模式已关闭")
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

        // 记录到命令历史
        addToCommandHistory(message)

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
     *
     * 提供两种模式（分开展示，避免混淆）：
     * 1. 【一键快捷配置】— 自动尝试通过 Shizuku 一键开启无障碍，无需手动操作
     * 2. 【逐个设置】— 列出所有权限，用户选择后跳转到对应系统设置页手动开关
     *
     * 两种模式各自独立，用户可自由选择。
     */
    private fun showPermissionDialog() {
        val permissions = PermissionManager.getAllPermissions(this)

        val grantedCount = permissions.count { it.granted }
        val totalCount = permissions.size
        val missingRequired = permissions.any { it.required && !it.granted }

        val sb = StringBuilder()
        sb.appendLine("权限状态：$grantedCount/$totalCount")
        sb.appendLine()

        permissions.forEachIndexed { index, perm ->
            val icon = if (perm.granted) "✓" else if (perm.required) "✗" else "-"
            sb.appendLine("${icon} ${perm.name}${if (perm.required) " (必需)" else ""}")
        }

        sb.appendLine()
        if (missingRequired) {
            sb.appendLine("必需权限未开启，核心功能无法使用。")
        } else {
            sb.appendLine("所有必需权限已就绪。")
        }

        sb.appendLine()
        sb.appendLine("━━━ 选择配置方式 ━━━")
        sb.appendLine()
        sb.appendLine("【一键快捷配置】")
        sb.appendLine("有 Shizuku/STELLAR 时自动一键开启无障碍，全程无需跳转设置页。")
        sb.appendLine()
        sb.appendLine("【逐个设置】")
        sb.appendLine("手动选择要设置的权限，跳转到系统设置页自行开关。")

        AlertDialog.Builder(this)
            .setTitle("权限管理")
            .setMessage(sb.toString())
            .setPositiveButton("一键快捷配置") { _, _ ->
                PermissionManager.quickSetup(this)
            }
            .setNegativeButton("逐个设置") { _, _ ->
                showIndividualPermissionDialog(permissions)
            }
            .show()
    }

    /**
     * 逐个权限设置对话框。
     *
     * 列出所有权限，用户点击某个权限后跳转到对应的系统设置页。
     * 已授权的权限标注 ✓，未授权的标注 ✗（必需）或 -（可选）。
     * 每次只处理一个权限，用户从设置页返回后可继续选择其他权限。
     */
    private fun showIndividualPermissionDialog(permissions: List<PermissionManager.PermissionStatus>) {
        // 构建带状态的列表项
        val items = permissions.map { perm ->
            val icon = if (perm.granted) "✓" else if (perm.required) "✗" else "-"
            val required = if (perm.required) " [必需]" else ""
            "$icon ${perm.name}$required"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择要设置的权限")
            .setItems(items) { _, which ->
                val perm = permissions[which]
                // 已授权则提示
                if (perm.granted) {
                    AlertDialog.Builder(this)
                        .setTitle(perm.name)
                        .setMessage("${perm.name} 已授权，无需重复设置。\n\n${perm.description}")
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("重新设置") { _, _ ->
                            PermissionManager.requestPermission(this, perm.type)
                        }
                        .show()
                } else {
                    // 未授权 → 直接跳转到系统设置页，让用户手动开关
                    PermissionManager.openSettingsForType(this, perm.type)
                    showToast("正在打开「${perm.name}」设置…")
                }
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
        // 省Token模式（向后兼容旧的 token_saving 键）
        switchTokenSaving.isChecked = if (prefs.contains("intelligent_mode")) {
            prefs.getBoolean("intelligent_mode", true)
        } else {
            prefs.getBoolean("token_saving", true)
        }
        val switchUnlimitedMode = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchUnlimitedMode)
        switchUnlimitedMode.isChecked = prefs.getBoolean("unlimited_mode", false)
        switchThinking.isChecked = prefs.getBoolean("thinking_mode", false)
        switchActionVerification.isChecked = prefs.getBoolean("action_verification", true)
        switchLocalModel.isChecked = prefs.getBoolean("local_inference", false)
        switchIntelligentContext.isChecked = prefs.getBoolean("intelligent_context", true)
        switchProactiveAnalysis.isChecked = prefs.getBoolean("proactive_analysis", true)
        switchPipelineOptimization.isChecked = prefs.getBoolean("pipeline_optimization", true)
        switchEnhancedFeedback.isChecked = prefs.getBoolean("enhanced_feedback", true)

        // 主题选择
        val radioGroupTheme = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioGroupTheme)
        val currentTheme = getCurrentThemeMode()
        when (currentTheme) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> radioGroupTheme.check(R.id.radioThemeFollowSystem)
            AppCompatDelegate.MODE_NIGHT_NO -> radioGroupTheme.check(R.id.radioThemeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> radioGroupTheme.check(R.id.radioThemeDark)
        }
        radioGroupTheme.setOnCheckedChangeListener { _, checkedId ->
            val themeMode = when (checkedId) {
                R.id.radioThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            applyTheme(themeMode)
        }

        // 设置页底部按钮：检查更新
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCheckUpdate)?.setOnClickListener {
            checkForUpdatesManual()
        }

        // 设置页底部按钮：赞助开发者
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSponsorDev)?.setOnClickListener {
            showSponsorDialog()
        }

        // 设置页底部按钮：总结与记忆设置
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSummarySettings)?.setOnClickListener {
            showSummarySettingsDialog()
        }

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
                    .putBoolean("unlimited_mode", switchUnlimitedMode.isChecked)
                    .putBoolean("thinking_mode", switchThinking.isChecked)
                    .putBoolean("local_inference", switchLocalModel.isChecked)
                    .putBoolean("action_verification", switchActionVerification.isChecked)
                    .putBoolean("intelligent_context", switchIntelligentContext.isChecked)
                    .putBoolean("proactive_analysis", switchProactiveAnalysis.isChecked)
                    .putBoolean("pipeline_optimization", switchPipelineOptimization.isChecked)
                    .putBoolean("enhanced_feedback", switchEnhancedFeedback.isChecked)
                    .putInt("theme_mode", getCurrentThemeMode())
                    .apply()

                // 同步到主界面的开关
                binding.switchTokenSaving.isChecked = switchTokenSaving.isChecked
                binding.switchUnlimitedMode.isChecked = switchUnlimitedMode.isChecked
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

    private fun applyTheme(themeMode: Int) {
        AppCompatDelegate.setDefaultNightMode(themeMode)
        getSharedPreferences("mobileclaw", MODE_PRIVATE).edit()
            .putInt("theme_mode", themeMode)
            .apply()
    }

    private fun getCurrentThemeMode(): Int {
        return getSharedPreferences("mobileclaw", MODE_PRIVATE)
            .getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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
     * 显示智能总结对话框。
     *
     * 汇总对话历史，生成结构化摘要，包括：
     * - 关键事实提取（做了什么、打开了什么应用）
     * - 待办任务追踪（失败的操作、需要重试的任务）
     * - 时间范围摘要（最近5分钟、30分钟、全部）
     *
     * 生成的摘要可直接复制或用于后续任务的上下文提示。
     */
    private fun showSummarizeDialog() {
        val controller = MobileClawApp.clawController
        if (controller == null) {
            showToast("请先开启无障碍服务")
            return
        }

        val options = arrayOf("最近5分钟", "最近30分钟", "全部对话", "查看统计摘要", "打包为上下文（供新AI接续）")
        AlertDialog.Builder(this)
            .setTitle("智能总结")
            .setItems(options) { _, which ->
                if (which == 4) {
                    // 打包为上下文
                    showContextPackageDialog(controller)
                    return@setItems
                }
                val mins = when (which) {
                    0 -> 5L
                    1 -> 30L
                    else -> 0L
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val summaryText = try {
                        when (which) {
                            0, 1 -> {
                                 // 使用 ConversationSummarizer 的时间范围摘要
                                 val durationMs = mins * 60 * 1000
                                 val summarizer = com.mobileclaw.app.ai.ConversationSummarizer()
                                 val memory = controller.memory
                                 val entries = memory.entries
                                 if (entries.isEmpty()) {
                                     "暂无对话记录。\n\n发送指令后，这里会显示对话总结。"
                                 } else {
                                     val rangeSummary = summarizer.summarizeRecent(entries, durationMs)
                                     buildString {
                                         appendLine("═══ 对话摘要（最近${options[which]}）═══")
                                         appendLine()
                                         if (rangeSummary.summaries.isNotEmpty()) {
                                             appendLine("【已完成任务】")
                                             rangeSummary.summaries.forEach { s ->
                                                 val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.timestamp))
                                                 appendLine("• [$timeStr] ${s.command.take(50)} → ${s.result}")
                                             }
                                         }
                                         val stats = rangeSummary.stats
                                         appendLine()
                                         appendLine("【统计】共 ${stats.totalTasks} 条 | 成功 ${stats.successCount} | 失败 ${stats.failedCount}")
                                         if (rangeSummary.summaries.isEmpty()) {
                                             appendLine()
                                             appendLine("所选时间范围内暂无对话记录。")
                                         }
                                     }
                                 }
                             }
                            2 -> {
                                // 全部对话
                                val digest = controller.getConversationDigest()
                                if (digest.isBlank()) {
                                    "暂无对话记录。\n\n发送指令后，这里会显示对话总结。"
                                } else {
                                    "═══ 全部对话摘要 ═══\n\n$digest"
                                }
                            }
                            3 -> {
                                // 统计摘要
                                "═══ 统计摘要 ═══\n\n" +
                                controller.getSummaryStats(this@MainActivity) + "\n\n" +
                                controller.getTokenStats() + "\n\n" +
                                controller.getPerformanceSummary()
                            }
                            else -> "暂无数据"
                        }
                    } catch (e: Exception) {
                        "获取对话摘要失败：${e.message?.take(100) ?: "未知错误"}"
                    }

                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("智能总结")
                            .setMessage(summaryText)
                            .setPositiveButton("关闭", null)
                            .setNeutralButton("复制摘要") { _, _ ->
                                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("对话摘要", summaryText)
                                clipboard.setPrimaryClip(clip)
                                showToast("摘要已复制到剪贴板")
                            }
                            .setNegativeButton("作为上下文发送") { _, _ ->
                                // 将摘要作为上下文发送给 AI
                                val contextMsg = "以下是近期对话摘要，请参考以保持上下文连贯性：\n\n$summaryText"
                                binding.editInput.setText(contextMsg)
                                showToast("摘要已填入输入框，点击发送即可")
                            }
                            .show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 显示上下文打包对话框。
     * 将对话历史打包为结构化文本，新 AI 收到后能完美接续上下文。
     */
    private fun showContextPackageDialog(controller: com.mobileclaw.app.ai.ClawController) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 获取打包后的上下文
                val pkg = controller.getContextPackage(this@MainActivity)

                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("上下文包已生成")
                        .setMessage(
                            "打包完成！\n\n" +
                            "格式: 纯文本\n" +
                            "字符数: ${pkg.charCount}\n" +
                            "估算 Token: ${pkg.estimatedTokens}\n\n" +
                            "你可以：\n" +
                            "1. 复制到剪贴板，粘贴到新 AI 的输入框\n" +
                            "2. 保存为文件，发送给新 AI\n" +
                            "3. 填入输入框，继续当前会话"
                        )
                        .setPositiveButton("复制上下文包") { _, _ ->
                            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("上下文包", pkg.text)
                            clipboard.setPrimaryClip(clip)
                            showToast("上下文包已复制（${pkg.estimatedTokens} Token），粘贴到新 AI 即可接续")
                        }
                        .setNeutralButton("保存为文件") { _, _ ->
                            val file = controller.saveContextPackageToFile(this@MainActivity, pkg)
                            if (file != null) {
                                showToast("已保存到: ${file.name}")
                                // 提供分享
                                try {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        this@MainActivity,
                                        "${packageName}.fileprovider",
                                        file
                                    )
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    startActivity(android.content.Intent.createChooser(shareIntent, "分享上下文包"))
                                } catch (_: Exception) {
                                    showToast("文件已保存到缓存目录")
                                }
                            } else {
                                showToast("保存失败")
                            }
                        }
                        .setNegativeButton("填入输入框") { _, _ ->
                            binding.editInput.setText(pkg.text)
                            showToast("上下文包已填入输入框（${pkg.estimatedTokens} Token），发送即可")
                        }
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("打包失败")
                        .setMessage("生成上下文包失败：${e.message?.take(100) ?: "未知错误"}")
                        .setPositiveButton("好的", null)
                        .show()
                }
            }
        }
    }

    /**
     * 显示总结与记忆设置对话框。
     * 包含：总结模式、频率、字数、Token 上限、自动总结开关、记忆持久化。
     */
    private fun showSummarySettingsDialog() {
        val controller = MobileClawApp.clawController ?: run {
            showToast("请先开启无障碍服务")
            return
        }

        // 当前设置
        val currentMode = com.mobileclaw.app.ai.SummarySettings.getSummaryMode(this)
        val currentFreq = com.mobileclaw.app.ai.SummarySettings.getSummaryFrequency(this)
        val currentWordCount = com.mobileclaw.app.ai.SummarySettings.getSummaryWordCount(this)
        val currentTokenLimit = com.mobileclaw.app.ai.SummarySettings.getTokenLimit(this)
        val autoSummary = com.mobileclaw.app.ai.SummarySettings.isAutoSummaryEnabled(this)
        val memoryPersistent = com.mobileclaw.app.ai.SummarySettings.isMemoryPersistent(this)
        val msgCount = controller.memory.getMessageCount()

        // 模式选项
        val modeNames = com.mobileclaw.app.ai.SummarySettings.SummaryMode.entries.map { it.displayName }.toTypedArray()
        val modeIndex = com.mobileclaw.app.ai.SummarySettings.SummaryMode.entries.indexOf(currentMode).coerceAtLeast(0)

        // 频率选项
        val freqNames = com.mobileclaw.app.ai.SummarySettings.SummaryFrequency.entries.map { it.displayName }.toTypedArray()
        val freqIndex = com.mobileclaw.app.ai.SummarySettings.SummaryFrequency.entries.indexOf(currentFreq).coerceAtLeast(0)

        // 字数选项
        val wordCountNames = com.mobileclaw.app.ai.SummarySettings.SummaryWordCount.entries.map { it.displayName }.toTypedArray()
        val wordCountIndex = com.mobileclaw.app.ai.SummarySettings.SummaryWordCount.entries.indexOf(currentWordCount).coerceAtLeast(0)

        // Token 上限选项
        val tokenLimitNames = com.mobileclaw.app.ai.SummarySettings.TokenLimit.entries.map { it.displayName }.toTypedArray()
        val tokenLimitIndex = com.mobileclaw.app.ai.SummarySettings.TokenLimit.entries.indexOf(currentTokenLimit).coerceAtLeast(0)

        // 构建设置界面
        val scrollView = android.widget.ScrollView(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // 标题：总结模式
        container.addView(android.widget.TextView(this).apply {
            text = "总结模式"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
        })
        container.addView(android.widget.TextView(this).apply {
            text = "无上限模式自动关闭智能节省 Token"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 12)
        })
        val modeSpinner = android.widget.Spinner(this)
        android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, modeNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modeSpinner.adapter = it
        }
        modeSpinner.setSelection(modeIndex)
        container.addView(modeSpinner)

        // 分隔线
        container.addView(createDivider())

        // 标题：总结频率
        container.addView(android.widget.TextView(this).apply {
            text = "自动总结频率"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
        })
        container.addView(android.widget.TextView(this).apply {
            text = "每 N 条消息自动生成一次总结，仅手动则不自动总结"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 12)
        })
        val freqSpinner = android.widget.Spinner(this)
        android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, freqNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            freqSpinner.adapter = it
        }
        freqSpinner.setSelection(freqIndex)
        container.addView(freqSpinner)

        // 分隔线
        container.addView(createDivider())

        // 标题：总结字数
        container.addView(android.widget.TextView(this).apply {
            text = "总结字数"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
        })
        container.addView(android.widget.TextView(this).apply {
            text = "字数越少 Token 越省，但效果可能较差"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 12)
        })
        val wordCountSpinner = android.widget.Spinner(this)
        android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, wordCountNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            wordCountSpinner.adapter = it
        }
        wordCountSpinner.setSelection(wordCountIndex)
        container.addView(wordCountSpinner)

        // 分隔线
        container.addView(createDivider())

        // 标题：Token 输出上限
        container.addView(android.widget.TextView(this).apply {
            text = "Token 输出上限"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
        })
        container.addView(android.widget.TextView(this).apply {
            text = "自定义 AI 输出的最大 Token 数"
            textSize = 12f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, 12)
        })
        val tokenLimitSpinner = android.widget.Spinner(this)
        android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, tokenLimitNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            tokenLimitSpinner.adapter = it
        }
        tokenLimitSpinner.setSelection(tokenLimitIndex)
        container.addView(tokenLimitSpinner)

        // 分隔线
        container.addView(createDivider())

        // 开关：自动总结
        val switchAutoSummary = androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "自动总结"
            isChecked = autoSummary
            setPadding(0, 8, 0, 8)
            setTextSize(16f)
        }
        container.addView(switchAutoSummary)

        // 开关：记忆持久化
        val switchMemoryPersist = androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "记忆持久化（跨会话保留）"
            isChecked = memoryPersistent
            setPadding(0, 8, 0, 8)
            setTextSize(16f)
        }
        container.addView(switchMemoryPersist)

        // 状态信息
        container.addView(createDivider())
        container.addView(android.widget.TextView(this).apply {
            text = "当前状态"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
        })
        container.addView(android.widget.TextView(this).apply {
            text = "消息计数: ${msgCount}条 | 记忆条目: ${controller.memory.entries.size}条"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 4, 0, 12)
        })

        scrollView.addView(container)

        AlertDialog.Builder(this)
            .setTitle("总结与记忆设置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ ->
                val selectedMode = com.mobileclaw.app.ai.SummarySettings.SummaryMode.entries[modeSpinner.selectedItemPosition]
                val selectedFreq = com.mobileclaw.app.ai.SummarySettings.SummaryFrequency.entries[freqSpinner.selectedItemPosition]
                val selectedWordCount = com.mobileclaw.app.ai.SummarySettings.SummaryWordCount.entries[wordCountSpinner.selectedItemPosition]
                val selectedTokenLimit = com.mobileclaw.app.ai.SummarySettings.TokenLimit.entries[tokenLimitSpinner.selectedItemPosition]

                // 保存设置
                com.mobileclaw.app.ai.SummarySettings.setSummaryMode(this, selectedMode)
                com.mobileclaw.app.ai.SummarySettings.setSummaryFrequency(this, selectedFreq)
                com.mobileclaw.app.ai.SummarySettings.setSummaryWordCount(this, selectedWordCount)
                com.mobileclaw.app.ai.SummarySettings.setTokenLimit(this, selectedTokenLimit)
                com.mobileclaw.app.ai.SummarySettings.setAutoSummaryEnabled(this, switchAutoSummary.isChecked)
                com.mobileclaw.app.ai.SummarySettings.setMemoryPersistent(this, switchMemoryPersist.isChecked)

                // 模式联动：无上限模式自动关闭智能节省
                when (selectedMode) {
                    com.mobileclaw.app.ai.SummarySettings.SummaryMode.UNLIMITED -> {
                        com.mobileclaw.app.ai.SummarySettings.enableUnlimitedMode(this)
                        showToast("无上限模式已开启，智能节省 Token 已自动关闭")
                    }
                    com.mobileclaw.app.ai.SummarySettings.SummaryMode.SMART_SAVING -> {
                        com.mobileclaw.app.ai.SummarySettings.enableSmartSavingMode(this)
                        showToast("智能节省模式已开启")
                    }
                    else -> showToast("设置已保存")
                }

                // 同步记忆持久化
                if (switchMemoryPersist.isChecked) {
                    controller.memory.persist(this)
                } else {
                    controller.memory.clearPersisted(this)
                }
            }
            .setNeutralButton("重置为默认") { _, _ ->
                com.mobileclaw.app.ai.SummarySettings.setSummaryMode(this, com.mobileclaw.app.ai.SummarySettings.SummaryMode.SMART_SAVING)
                com.mobileclaw.app.ai.SummarySettings.setSummaryFrequency(this, com.mobileclaw.app.ai.SummarySettings.SummaryFrequency.EVERY_3)
                com.mobileclaw.app.ai.SummarySettings.setSummaryWordCount(this, com.mobileclaw.app.ai.SummarySettings.SummaryWordCount.MEDIUM)
                com.mobileclaw.app.ai.SummarySettings.setTokenLimit(this, com.mobileclaw.app.ai.SummarySettings.TokenLimit.MEDIUM)
                com.mobileclaw.app.ai.SummarySettings.setAutoSummaryEnabled(this, true)
                com.mobileclaw.app.ai.SummarySettings.setMemoryPersistent(this, true)
                showToast("已重置为默认设置")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 创建一个分割线 View。 */
    private fun createDivider(): android.view.View {
        return android.widget.LinearLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                1
            ).apply { setMargins(0, 16, 0, 16) }
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
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

    // =========================================================================
    //  关于对话框
    // =========================================================================

    /**
     * 显示「关于」对话框。
     * 展示版本信息、功能亮点、开源协议、GitHub 链接等。
     */
    private fun showAboutDialog() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE

        val content = StringBuilder()
        content.appendLine("灵爪 MobileClaw v$versionName (build $versionCode)")
        content.appendLine()
        content.appendLine("━━━ 核心功能 ━━━")
        content.appendLine("• 自然语言操控手机 — 说「打开微信」就打开微信")
        content.appendLine("• AI Agent 引擎 — ReAct 推理循环，自动调用工具完成任务")
        content.appendLine("• 15+ 内置工具 — Shell命令/Python执行/文件操作/屏幕操控")
        content.appendLine("• 代码生成 — 说「写个Python爬虫」直接生成并执行")
        content.appendLine("• APK 项目生成 — 说「创建一个计算器APP」自动生成完整项目")
        content.appendLine("• 300+ 应用别名 — 绿泡泡/小而美/狗东/拼夕夕/Insta/TG/奈飞")
        content.appendLine("• 智能纠错学习 — 你说「不是A是B」，下次自动纠正")
        content.appendLine("• 定时命令 — 「5分钟后打开支付宝」「半小时后打开微信」")
        content.appendLine("• 本地模型 — 内置 Qwen/Gemma/Phi 等模型离线推理")
        content.appendLine("• 检查更新 — 自动检测新版本，应用内下载安装")
        content.appendLine()
        content.appendLine("━━━ 技术栈 ━━━")
        content.appendLine("• 语言: Kotlin + Jetpack")
        content.appendLine("• AI: OpenAI 兼容接口 + 本地推理引擎")
        content.appendLine("• 特权: Shizuku/STELLAR 提供系统级 API")
        content.appendLine("• 协议: Apache License 2.0")
        content.appendLine()
        content.appendLine("开源地址：")
        content.appendLine("github.com/19923421354/MobileClaw")

        AlertDialog.Builder(this)
            .setTitle("关于灵爪")
            .setMessage(content.toString())
            .setPositiveButton("打开 GitHub") { _, _ ->
                try {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://github.com/19923421354/MobileClaw")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    showToast("无法打开浏览器")
                }
            }
            .setNegativeButton("关闭", null)
            .setNeutralButton("查看更新日志") { _, _ ->
                showChangelogDialog()
            }
            .show()
    }

    /**
     * 显示更新日志对话框。
     * 展示当前版本及历史版本的主要变更。
     */
    private fun showChangelogDialog() {
        val changelog = """
            |━━━ v2.0.5 ━━━
            |• 新增「我的」个人中心页面，集成赞助开发、统计卡片、功能入口
            |• 顶部状态栏新增「我的」入口按钮（👤 图标）
            |• 优化应用内更新：GitHub API 失败时自动回退直链下载
            |• 更新对话框明确标注「应用内更新」，无需跳转网页
            |• 修复检查更新不生效的问题（需配合 GitHub Release 发布）
            |
            |━━━ v2.0.4 ━━━
            |• 新增「关于」对话框，集成版本信息与功能导航
            |• 新增命令历史记录，快速重复常用指令
            |• 通知栏下载进度显示，大文件更新更直观
            |• 设置页新增「检查更新」和「赞助开发者」入口
            |• 替换废弃的 ProgressDialog，使用现代化进度控件
            |• 优化赞助对话框，支持微信支付和支付宝切换
            |• 新增赞助入口到设置页底部
            |
            |━━━ v2.0.3 ━━━
            |• 全新密钥，彻底解决覆盖安装冲突
            |• AI Agent 引擎：ReAct 推理循环
            |• 15+ 内置工具，Termux 集成
            |• Python 代码生成与 APK 项目生成
            |• 智能意图推断与自然时间解析
            |• 300+ 应用别名与拼音首字母匹配
            |
            |━━━ v2.0.2 ━━━
            |• 权限系统全面优化，一键快捷配置 + 逐个设置
            |• Shizuku/STELLAR 一键开启无障碍服务
            |• 权限缓存优化，避免频繁检测导致卡顿
            |• 评估器模型配置，支持独立于主模型的评估器
            |
            |━━━ v2.0.1 ━━━
            |• 本地模型管理，支持下载/加载/删除
            |• 统计面板，展示 Token 用量和执行指标
            |• 智能 Token 模式，按任务复杂度动态调节
            |• 思考模式，提升复杂任务质量
        """.trimMargin()

        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this)
        textView.text = changelog
        textView.textSize = 13f
        textView.setTextColor(0xFF333333.toInt())
        textView.setPadding(24, 16, 24, 16)
        textView.typeface = android.graphics.Typeface.MONOSPACE
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("更新日志")
            .setView(scrollView)
            .setPositiveButton("关闭", null)
            .show()
    }

    // =========================================================================
    //  命令历史
    // =========================================================================

    /** 命令历史最大保存条数 */
    private companion object {
        private const val MAX_COMMAND_HISTORY = 50
    }

    /**
     * 添加命令到历史记录。
     */
    private fun addToCommandHistory(command: String) {
        val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)
        val history = prefs.getString("command_history", "") ?: ""
        val commands = history.split("\n").filter { it.isNotBlank() && it != command }.toMutableList()
        commands.add(0, command)
        if (commands.size > MAX_COMMAND_HISTORY) {
            commands.removeAt(commands.size - 1)
        }
        prefs.edit().putString("command_history", commands.joinToString("\n")).apply()
    }

    /**
     * 获取命令历史列表。
     */
    private fun getCommandHistory(): List<String> {
        val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)
        val history = prefs.getString("command_history", "") ?: ""
        return history.split("\n").filter { it.isNotBlank() }
    }

    /**
     * 显示命令历史对话框。
     * 用户可点击历史命令快速填入输入框。
     */
    private fun showCommandHistoryDialog() {
        val history = getCommandHistory()
        if (history.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("命令历史")
                .setMessage("暂无命令历史。\n\n发送指令后，会自动记录到历史中。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("命令历史（点击选择）")
            .setItems(history.toTypedArray()) { _, which ->
                val command = history[which]
                binding.editInput.setText(command)
                binding.editInput.setSelection(command.length)
                binding.editInput.requestFocus()
            }
            .setNeutralButton("清空历史") { _, _ ->
                getSharedPreferences("mobileclaw", MODE_PRIVATE)
                    .edit().putString("command_history", "").apply()
                showToast("命令历史已清空")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // =========================================================================
    //  更新检查
    // =========================================================================

    /** 当前版本号，从 BuildConfig 读取 */
    private val appVersion: String by lazy {
        BuildConfig.VERSION_NAME
    }

    /** 是否已缓存更新信息（避免重复弹窗） */
    private var updateInfoCached: UpdateInfo? = null

    /**
     * 自动检查更新（静默模式）。
     * 仅在检测到新版本时弹窗提示，无更新时不打扰用户。
     */
    private fun checkForUpdatesAuto() {
        lifecycleScope.launch {
            val info = UpdateChecker.checkForUpdate(appVersion)
            if (info != null) {
                updateInfoCached = info
                // 仅在首次发现新版本时弹窗
                val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)
                val lastSkipped = prefs.getString("skipped_version", "")
                if (lastSkipped != info.latestVersion) {
                    showUpdateDialog(info, autoDetected = true)
                }
            }
        }
    }

    /**
     * 手动检查更新。
     * 显示加载状态，无论有无更新都给出明确反馈。
     * 更新直接通过应用内下载安装，无需跳转网页。
     */
    private fun checkForUpdatesManual() {
        // 使用现代化进度对话框
        val progressView = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(40, 0, 40, 0) }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("检查更新")
            .setMessage("正在检查新版本…\n（通过国内镜像加速，几秒内返回结果）")
            .setView(progressView)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val info = UpdateChecker.checkForUpdate(appVersion)
                dialog.dismiss()
                if (info != null) {
                    updateInfoCached = info
                    showUpdateDialog(info, autoDetected = false)
                } else {
                    // 使用 UpdateChecker 的 isNetworkAvailable 检测网络连通性
                    val networkOk = UpdateChecker.isNetworkAvailable()

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("检查更新")
                        .setMessage(if (networkOk) {
                            "当前已是最新版本（v$appVersion），无需更新。\n\n" +
                            "已通过 gh-proxy.com 镜像 + jsDelivr CDN 等多源并行检查，确认暂无新版本。\n\n" +
                            "如有新版本发布，会自动在应用内检测到并下载安装。"
                        } else {
                            "检查更新失败：无法连接到更新服务器。\n\n" +
                            "检测了 gh-proxy.com、ghproxy.net、jsDelivr CDN、Gitee、GitHub API 等多个源均不可达。\n\n" +
                            "请检查网络连接后重试。\n\n" +
                            "你也可以前往 GitHub 查看最新版本：\n" +
                            "github.com/19923421354/MobileClaw/releases"
                        })
                        .setPositiveButton(if (networkOk) "好的" else "打开浏览器") { _, _ ->
                            if (!networkOk) {
                                try {
                                    startActivity(android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://github.com/19923421354/MobileClaw/releases")
                                    ))
                                } catch (_: Exception) { showToast("无法打开浏览器") }
                            }
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("检查更新失败")
                    .setMessage("发生错误：${e.message?.take(100) ?: "未知错误"}\n\n" +
                            "请检查网络连接后重试。")
                    .setPositiveButton("好的", null)
                    .show()
            }
        }
    }

    /**
     * 显示更新对话框。
     *
     * @param info 更新信息
     * @param autoDetected 是否为自动检测触发（影响按钮文案）
     */
    private fun showUpdateDialog(info: UpdateInfo, autoDetected: Boolean) {
        val changelog = if (info.changelog.isBlank()) "暂无更新日志" else info.changelog
        val sizeInfo = if (info.apkSize > 0) "（${info.formattedSize()}）" else ""
        val isDirectApk = info.downloadUrl.endsWith(".apk")
        val downloadLabel = if (isDirectApk) "📥 应用内更新 $sizeInfo" else "📥 下载更新 $sizeInfo"

        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${info.latestVersion}")
            .setMessage(
                "当前版本：v$appVersion\n" +
                "最新版本：v${info.latestVersion}\n" +
                "发布时间：${info.publishedAt.take(10)}\n\n" +
                "━━━ 更新日志 ━━━\n$changelog"
            )
            .setPositiveButton(downloadLabel) { _, _ ->
                downloadAndInstallUpdate(info)
            }
            .setNeutralButton("以后再说") { _, _ ->
                // 自动检测时提示可跳过此版本
                if (autoDetected) {
                    showSkipVersionPrompt(info)
                }
            }
            .setNegativeButton("忽略此版本") { _, _ ->
                val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)
                prefs.edit().putString("skipped_version", info.latestVersion).apply()
                showToast("已忽略 v${info.latestVersion}")
            }
            .show()
    }

    /**
     * 自动检测更新时，提供「跳过此版本」选项。
     */
    private fun showSkipVersionPrompt(info: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("跳过此版本？")
            .setMessage("v${info.latestVersion} 的更新提醒将被忽略，下次检查更新时不会再提示。\n\n" +
                    "你可以随时点击工具栏的「检查更新」按钮手动检查。")
            .setPositiveButton("跳过此版本") { _, _ ->
                val prefs = getSharedPreferences("mobileclaw", MODE_PRIVATE)
                prefs.edit().putString("skipped_version", info.latestVersion).apply()
                showToast("已跳过 v${info.latestVersion}")
            }
            .setNegativeButton("保留提醒", null)
            .show()
    }

    /**
     * 下载并安装更新。
     * 使用真实进度回调，实时显示 KB/MB 下载进度。
     * 先下载到缓存目录，然后调用系统安装器。
     * 使用通知栏 + 对话框双重显示下载进度。
     */
    private fun downloadAndInstallUpdate(info: UpdateInfo) {
        // 创建通知渠道并显示初始进度
        UpdateNotificationHelper.createChannel(this)
        UpdateNotificationHelper.showDownloadProgress(this, 0, 0, info.apkName)

        // 显示进度对话框（显示实时 KB/MB 进度）
        val progressView = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(40, 0, 40, 0) }
        }
        val progressText = android.widget.TextView(this).apply {
            text = "准备下载…"
            gravity = android.view.Gravity.CENTER
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
        }
        val progressLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(progressView)
            addView(progressText)
        }
        val downloadDialog = AlertDialog.Builder(this)
            .setTitle("下载更新")
            .setMessage("正在下载 v${info.latestVersion}…")
            .setView(progressLayout)
            .setCancelable(false)
            .show()

        lifecycleScope.launch(Dispatchers.IO) {
            // 使用持久化目录（getExternalFilesDir），避免 APK 被系统清理
            val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: filesDir // 如果外部存储不可用，回退到内部 filesDir
            val apkFile = File(downloadDir, info.apkName)
            // 如果已下载过且文件存在，直接安装
            if (apkFile.exists() && apkFile.length() > 1_000_000) {
                runOnUiThread { downloadDialog.dismiss() }
                UpdateNotificationHelper.cancelNotification(this@MainActivity)
                UpdateChecker.installApk(this@MainActivity, apkFile)
                return@launch
            }

            // 真正的进度回调（含实时网速）
            val result = UpdateChecker.downloadApkWithProgress(
                downloadUrl = info.downloadUrl,
                destination = apkFile,
                progressCallback = { bytesRead, totalBytes, speedBps ->
                    runOnUiThread {
                        progressView.isIndeterminate = totalBytes <= 0
                        if (totalBytes > 0) {
                            val pct = (bytesRead * 100 / totalBytes).toInt()
                            progressView.progress = pct
                        }
                        // 显示 KB/MB 进度和实时网速
                        val readStr = UpdateChecker.formatFileSize(bytesRead)
                        val totalStr = if (totalBytes > 0) {
                            UpdateChecker.formatFileSize(totalBytes)
                        } else {
                            "?"
                        }
                        val pctStr = if (totalBytes > 0) {
                            " (${bytesRead * 100 / totalBytes}%)"
                        } else {
                            ""
                        }
                        val speedStr = UpdateChecker.formatSpeed(speedBps)
                        progressText.text = "$readStr / $totalStr$pctStr\n网速: $speedStr"
                    }
                    // 同时更新通知栏（含网速）
                    UpdateNotificationHelper.showDownloadProgress(
                        this@MainActivity, bytesRead, totalBytes, info.apkName, speedBps
                    )
                }
            )

            runOnUiThread { downloadDialog.dismiss() }

            if (result.success) {
                UpdateNotificationHelper.showDownloadComplete(
                    this@MainActivity, info.apkName, apkFile.absolutePath
                )
                runOnUiThread {
                    showToast("下载完成（${UpdateChecker.formatFileSize(apkFile.length())}），正在启动安装…")
                    UpdateChecker.installApk(this@MainActivity, apkFile)
                }
            } else {
                val errorMsg = result.message
                UpdateNotificationHelper.showDownloadFailed(this@MainActivity, errorMsg)
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("下载失败")
                        .setMessage("下载更新文件失败。\n原因：$errorMsg\n\n" +
                                "已自动尝试多个镜像源，建议检查网络后重试。")
                        .setPositiveButton("重试") { _, _ ->
                            downloadAndInstallUpdate(info)
                        }
                        .setNeutralButton("打开浏览器") { _, _ ->
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(info.releaseUrl)
                            )
                            startActivity(intent)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }
    }

    // =========================================================================
    //  个人中心
    // =========================================================================

    /**
     * 显示「我的」个人中心对话框。
     *
     * 集成个人资料、赞助开发、检查更新、更新日志、开源仓库、统计面板等入口。
     * 使用 [dialog_profile] 布局，动态加载二维码图片。
     */
    private fun showProfileDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_profile, null)
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE

        // 设置版本信息
        dialogView.findViewById<android.widget.TextView>(R.id.profileVersion).text = "v$versionName (build $versionCode)"
        dialogView.findViewById<android.widget.TextView>(R.id.profileStatsVersion).text = "v$versionName"

        // 设置权限统计
        val permissions = PermissionManager.getAllPermissions(this)
        val grantedCount = permissions.count { it.granted }
        dialogView.findViewById<android.widget.TextView>(R.id.profileStatsPermissions).text = "$grantedCount/${permissions.size}"

        // 设置指令统计
        val historyCount = getCommandHistory().size
        dialogView.findViewById<android.widget.TextView>(R.id.profileStatsCommands).text = "$historyCount"

        // ========== 加载二维码 ==========
        val displayMetrics = resources.displayMetrics
        val qrSize = (displayMetrics.widthPixels * 0.45).toInt()
        val qrImage = dialogView.findViewById<android.widget.ImageView>(R.id.profileQrImage)
        qrImage.layoutParams = android.widget.FrameLayout.LayoutParams(qrSize, qrSize)

        val wechatBitmap = try {
            val `is` = assets.open("wechat_qr.png")
            val bm = android.graphics.BitmapFactory.decodeStream(`is`)
            `is`.close(); bm
        } catch (_: Exception) { null }

        val alipayBitmap = try {
            val `is` = assets.open("alipay_qr.jpg")
            val bm = android.graphics.BitmapFactory.decodeStream(`is`)
            `is`.close(); bm
        } catch (_: Exception) { null }

        val hasWechat = wechatBitmap != null
        val hasAlipay = alipayBitmap != null

        // 默认显示微信二维码
        if (hasWechat) qrImage.setImageBitmap(wechatBitmap)
        else if (hasAlipay) qrImage.setImageBitmap(alipayBitmap)
        else qrImage.visibility = android.view.View.GONE

        // 支付方式切换按钮
        val btnWechat = dialogView.findViewById<android.widget.Button>(R.id.profileBtnWechat)
        val btnAlipay = dialogView.findViewById<android.widget.Button>(R.id.profileBtnAlipay)
        if (!hasWechat) btnWechat.visibility = android.view.View.GONE
        if (!hasAlipay) btnAlipay.visibility = android.view.View.GONE

        btnWechat.setOnClickListener {
            if (hasWechat) {
                qrImage.setImageBitmap(wechatBitmap)
                btnWechat.alpha = 1f
                btnAlipay.alpha = 0.6f
            }
        }
        btnAlipay.setOnClickListener {
            if (hasAlipay) {
                qrImage.setImageBitmap(alipayBitmap)
                btnAlipay.alpha = 1f
                btnWechat.alpha = 0.6f
            }
        }
        // 默认微信高亮
        if (hasWechat) { btnWechat.alpha = 1f; btnAlipay.alpha = 0.6f }

        // 点击赞助卡片也可以打开赞助对话框
        dialogView.findViewById<android.view.View>(R.id.cardSponsor).setOnClickListener {
            showSponsorDialog()
        }

        // ========== 功能按钮 ==========
        // 检查更新
        dialogView.findViewById<android.view.View>(R.id.profileBtnCheckUpdate).setOnClickListener {
            val statusText = dialogView.findViewById<android.widget.TextView>(R.id.profileUpdateStatus)
            statusText.text = "检查中…"
            checkForUpdatesManual()
            handler.postDelayed({ statusText.text = "点击检查" }, 5000)
        }

        // 更新日志
        dialogView.findViewById<android.view.View>(R.id.profileBtnChangelog).setOnClickListener {
            showChangelogDialog()
        }

        // 开源仓库
        dialogView.findViewById<android.view.View>(R.id.profileBtnGitHub).setOnClickListener {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/19923421354/MobileClaw")
                )
                startActivity(intent)
            } catch (_: Exception) {
                showToast("无法打开浏览器")
            }
        }

        // 智能总结
        dialogView.findViewById<android.view.View>(R.id.profileBtnSummary).setOnClickListener {
            showSummarizeDialog()
        }

        // 统计面板
        dialogView.findViewById<android.view.View>(R.id.profileBtnStats).setOnClickListener {
            showStatsDialog()
        }

        // 设置
        dialogView.findViewById<android.view.View>(R.id.profileBtnSettings).setOnClickListener {
            showSettingsDialog()
        }

        // 显示对话框
        AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .show()
    }

    // =========================================================================
    //  赞助开发者
    // =========================================================================

    /**
     * 显示赞助对话框。
     *
     * 支持微信支付和支付宝两种方式，用户可通过顶部切换按钮选择。
     * 二维码图片从 assets 目录加载（wechat_qr.png / alipay_qr.jpg）。
     */
    private fun showSponsorDialog() {
        val displayMetrics = resources.displayMetrics
        val qrWidth = (displayMetrics.widthPixels * 0.65).toInt()
        val qrHeight = (qrWidth * 1.05).toInt()

        // 预加载两张二维码 bitmap
        val wechatBitmap = try {
            val `is` = assets.open("wechat_qr.png")
            val bm = android.graphics.BitmapFactory.decodeStream(`is`)
            `is`.close()
            bm
        } catch (_: Exception) { null }

        val alipayBitmap = try {
            val `is` = assets.open("alipay_qr.jpg")
            val bm = android.graphics.BitmapFactory.decodeStream(`is`)
            `is`.close()
            bm
        } catch (_: Exception) { null }

        val hasWechat = wechatBitmap != null
        val hasAlipay = alipayBitmap != null

        // 如果两张二维码都没有，显示纯文字提示
        if (!hasWechat && !hasAlipay) {
            AlertDialog.Builder(this)
                .setTitle("赞助开发者")
                .setMessage("如果灵爪对你有所帮助，欢迎赞助开发者一杯咖啡！\n\n你的支持是持续改进的动力！\n\n（二维码即将在后续版本中更新）")
                .setPositiveButton("好的", null)
                .show()
            return
        }

        // 构建自定义视图
        val rootLayout = android.widget.LinearLayout(this)
        rootLayout.orientation = android.widget.LinearLayout.VERTICAL
        rootLayout.setPadding(40, 20, 40, 20)

        // 顶部文字
        val titleText = android.widget.TextView(this)
        titleText.text = "如果灵爪对你有所帮助，欢迎赞助开发者一杯咖啡！\n你的支持是持续改进的动力！"
        titleText.textSize = 14f
        titleText.gravity = android.view.Gravity.CENTER
        titleText.setPadding(0, 0, 0, 16)
        rootLayout.addView(titleText)

        // 切换按钮行
        val tabLayout = android.widget.LinearLayout(this)
        tabLayout.orientation = android.widget.LinearLayout.HORIZONTAL
        tabLayout.gravity = android.view.Gravity.CENTER
        tabLayout.setPadding(0, 0, 0, 12)

        val btnWechat = android.widget.Button(this)
        btnWechat.text = "微信支付"
        btnWechat.tag = "wechat"
        btnWechat.setTextSize(13f)
        btnWechat.setPadding(24, 8, 24, 8)
        val tabParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tabParams.setMargins(0, 0, 8, 0)
        btnWechat.layoutParams = tabParams

        val btnAlipay = android.widget.Button(this)
        btnAlipay.text = "支付宝"
        btnAlipay.tag = "alipay"
        btnAlipay.setTextSize(13f)
        btnAlipay.setPadding(24, 8, 24, 8)

        tabLayout.addView(btnWechat)
        tabLayout.addView(btnAlipay)
        rootLayout.addView(tabLayout)

        // 二维码图片容器
        val frameLayout = android.widget.FrameLayout(this)
        val frameParams = android.widget.LinearLayout.LayoutParams(qrWidth, qrHeight)
        frameParams.gravity = android.view.Gravity.CENTER
        frameLayout.layoutParams = frameParams

        val imageView = android.widget.ImageView(this)
        imageView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        imageView.adjustViewBounds = true
        imageView.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        // 默认显示微信
        if (hasWechat) imageView.setImageBitmap(wechatBitmap)
        else if (hasAlipay) imageView.setImageBitmap(alipayBitmap)

        frameLayout.addView(imageView)
        rootLayout.addView(frameLayout)

        // 底部提示文字
        val hintText = android.widget.TextView(this)
        hintText.text = "长按或截图保存二维码，打开相应 App 扫码"
        hintText.textSize = 12f
        hintText.gravity = android.view.Gravity.CENTER
        hintText.setTextColor(0xFF888888.toInt())
        hintText.setPadding(0, 12, 0, 0)
        rootLayout.addView(hintText)

        // 切换按钮样式更新
        fun updateTabStyle(selected: String) {
            val wechatBg = if (selected == "wechat") {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF07C160.toInt())
                    setCornerRadius(20f)
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFE0E0E0.toInt())
                    setCornerRadius(20f)
                }
            }
            val alipayBg = if (selected == "alipay") {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1677FF.toInt())
                    setCornerRadius(20f)
                }
            } else {
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFE0E0E0.toInt())
                    setCornerRadius(20f)
                }
            }
            btnWechat.background = wechatBg
            btnWechat.setTextColor(if (selected == "wechat") -0x1 else 0xFF333333.toInt())
            btnAlipay.background = alipayBg
            btnAlipay.setTextColor(if (selected == "alipay") -0x1 else 0xFF333333.toInt())
        }
        updateTabStyle("wechat")

        btnWechat.setOnClickListener {
            if (hasWechat) {
                imageView.setImageBitmap(wechatBitmap)
                updateTabStyle("wechat")
            }
        }
        btnAlipay.setOnClickListener {
            if (hasAlipay) {
                imageView.setImageBitmap(alipayBitmap)
                updateTabStyle("alipay")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("赞助开发者")
            .setView(rootLayout)
            .setPositiveButton("好的", null)
            .show()
    }

    // =========================================================================
    //  对话管理
    // =========================================================================

    /**
     * 显示清空对话确认对话框。
     * 确认后清空所有聊天消息（包括欢迎消息）。
     */
    private fun showClearChatDialog() {
        AlertDialog.Builder(this)
            .setTitle("清空对话")
            .setMessage("确定要清空所有对话记录吗？\n\n此操作不可撤销，所有消息将被永久删除。")
            .setPositiveButton("清空") { _, _ ->
                chatAdapter.clear()
                // 重新显示欢迎消息
                chatAdapter.addMessage(ChatMessage(
                    type = ChatMessage.TYPE_AI,
                    content = "对话已清空，有什么可以帮你的？"
                ))
                showToast("对话已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 导出对话记录到文本文件。
     * 保存到下载目录，文件名格式：MobileClaw_对话记录_2024-01-01_14-30.txt
     */
    private fun exportChat() {
        val messages = chatAdapter.getAllMessages()
        if (messages.isEmpty()) {
            showToast("暂无对话记录可导出")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val fileName = "MobileClaw_对话记录_$dateStr.txt"

                // 构建导出内容
                val sb = StringBuilder()
                sb.appendLine("灵爪 MobileClaw 对话记录")
                sb.appendLine("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                sb.appendLine("版本: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
                sb.appendLine("=" .repeat(50))
                sb.appendLine()

                for (msg in messages) {
                    val time = com.mobileclaw.app.adapter.recyclerview.ChatMessage.formatTimestamp(msg.timestamp)
                    when (msg.type) {
                        com.mobileclaw.app.adapter.recyclerview.ChatMessage.TYPE_USER -> {
                            sb.appendLine("👤 [你] $time")
                        }
                        com.mobileclaw.app.adapter.recyclerview.ChatMessage.TYPE_AI -> {
                            sb.appendLine("🤖 [灵爪] $time")
                        }
                    }
                    sb.appendLine(msg.content)
                    sb.appendLine()
                }

                sb.appendLine("=" .repeat(50))
                sb.appendLine("导出完毕")

                // 保存到下载目录
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val exportFile = File(downloadsDir, fileName)
                exportFile.writeText(sb.toString(), java.nio.charset.StandardCharsets.UTF_8)

                runOnUiThread {
                    showToast("对话已导出到: $fileName")
                    // 弹出分享/打开选择
                    try {
                        val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            androidx.core.content.FileProvider.getUriForFile(
                                this@MainActivity,
                                "$packageName.fileprovider",
                                exportFile
                            )
                        } else {
                            android.net.Uri.fromFile(exportFile)
                        }
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(android.content.Intent.createChooser(shareIntent, "分享对话记录"))
                    } catch (e: Exception) {
                        // 分享失败也不要紧，文件已保存
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showToast("导出失败: ${e.message?.take(50)}")
                }
            }
        }
    }
}
