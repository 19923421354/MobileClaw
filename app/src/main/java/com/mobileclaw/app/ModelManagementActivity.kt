package com.mobileclaw.app

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.mobileclaw.app.ai.ClawController
import com.mobileclaw.app.ai.DownloadProgress
import com.mobileclaw.app.ai.DownloadStatus
import com.mobileclaw.app.ai.DownloadedModel
import com.mobileclaw.app.ai.LocalModelManager
import com.mobileclaw.app.ai.ModelInfo
import com.mobileclaw.app.ai.ModelState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地模型管理 Activity (Material Design 3 风格)。
 *
 * 提供完整的模型管理界面，包括：
 * - 状态摘要展示（已下载数量、已加载模型、可下载数量）
 * - 搜索与分类筛选（按名称/描述搜索，按推荐/代码/轻量分类）
 * - 可下载模型列表（支持下载/加载/删除操作）
 * - 本地模型文件导入
 * - 下拉刷新
 * - 实时进度条动画（LinearProgressIndicator）
 * - 实时搜索（TextWatcher 动态过滤）
 */
class ModelManagementActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModelManagement"
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val LIGHTWEIGHT_THRESHOLD = 500L * 1024 * 1024 // 500 MB
    }

    // 视图绑定（手写，以保持兼容性）
    private lateinit var btnBack: ImageView
    private lateinit var btnRefresh: ImageView
    private lateinit var txtModelStatus: TextView
    private lateinit var txtDownloadCount: TextView
    private lateinit var txtLoadedName: TextView
    private lateinit var txtAvailableCount: TextView
    private lateinit var layoutImportModel: LinearLayout
    private lateinit var layoutModelList: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var editSearch: EditText
    private lateinit var chipGroupCategories: ChipGroup

    private val handler = Handler(Looper.getMainLooper())
    private var controller: ClawController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_management)

        initViews()
        controller = MobileClawApp.clawController

        // 设置按钮点击事件
        btnBack.setOnClickListener { finish() }
        btnRefresh.setOnClickListener {
            animateRefreshButton()
            refreshModelList()
        }
        layoutImportModel.setOnClickListener { showImportDialog() }

        // 下拉刷新
        swipeRefresh.setOnRefreshListener {
            refreshModelList()
            handler.postDelayed({
                swipeRefresh.isRefreshing = false
            }, 1000)
        }

        // 实时搜索监听
        editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                refreshModelList()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // 不需要处理
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 不需要处理
            }
        })

        // 分类筛选监听
        chipGroupCategories.setOnCheckedStateChangeListener { _, _ ->
            refreshModelList()
        }

        // 加载模型列表
        refreshModelList()
    }

    /**
     * 初始化视图引用。
     */
    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        btnRefresh = findViewById(R.id.btnRefresh)
        txtModelStatus = findViewById(R.id.txtModelStatus)
        txtDownloadCount = findViewById(R.id.txtDownloadCount)
        txtLoadedName = findViewById(R.id.txtLoadedName)
        txtAvailableCount = findViewById(R.id.txtAvailableCount)
        layoutImportModel = findViewById(R.id.layoutImportModel)
        layoutModelList = findViewById(R.id.layoutModelList)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        editSearch = findViewById(R.id.editSearch)
        chipGroupCategories = findViewById(R.id.chipGroupCategories)
    }

    /**
     * 刷新按钮旋转动画。
     */
    private fun animateRefreshButton() {
        val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                btnRefresh.rotation = animation.animatedValue as Float
            }
        }
        animator.start()
    }

    /**
     * 刷新模型列表和状态摘要，包含搜索和分类筛选。
     */
    private fun refreshModelList() {
        val ctrl = controller
        if (ctrl == null) {
            txtModelStatus.text = "请先开启无障碍服务"
            txtDownloadCount.text = "0"
            txtLoadedName.text = "无"
            txtAvailableCount.text = "0"
            return
        }

        // 更新状态摘要
        updateStatusSummary(ctrl)

        // 获取搜索关键词（去掉首尾空格，转小写用于匹配）
        val keyword = editSearch.text.toString().trim().lowercase()

        // 获取当前选中的分类标签
        val selectedCategory = getSelectedCategory()

        // 刷新模型列表
        layoutModelList.removeAllViews()
        val modelSources = ctrl.getModelSources()
            .filter { matchesSearch(it, keyword) }
            .filter { matchesCategory(it, selectedCategory) }
        val downloadedModels = ctrl.getDownloadedModels()
        val loadedModel = getLoadedModel(ctrl)

        if (modelSources.isEmpty()) {
            val hint = if (keyword.isNotEmpty() || selectedCategory != "全部") {
                "没有匹配的模型"
            } else {
                "暂无可用模型"
            }
            layoutModelList.addView(createEmptyState(hint, "尝试修改搜索关键词或切换分类"))
            return
        }

        for (modelInfo in modelSources) {
            val isDownloaded = downloadedModels.any { it.modelInfo.id == modelInfo.id }
            val isLoaded = loadedModel?.modelInfo?.id == modelInfo.id
            val card = createModelCard(modelInfo, isDownloaded, isLoaded, ctrl)
            layoutModelList.addView(card)
        }
    }

    /**
     * 获取当前选中的分类文本。
     */
    private fun getSelectedCategory(): String {
        val checkedChipId = chipGroupCategories.checkedChipId
        return if (checkedChipId != ChipGroup.NO_ID) {
            findViewById<Chip>(checkedChipId)?.text?.toString() ?: "全部"
        } else {
            "全部"
        }
    }

    /**
     * 按搜索关键词过滤模型（匹配名称或描述）。
     */
    private fun matchesSearch(model: ModelInfo, keyword: String): Boolean {
        if (keyword.isEmpty()) return true
        return model.name.lowercase().contains(keyword) ||
                model.description.lowercase().contains(keyword)
    }

    /**
     * 按分类标签过滤模型。
     * - "全部"：不过滤
     * - "推荐"：isDefault == true
     * - "代码"：名称或描述包含 "code" / "coder"
     * - "轻量"：模型文件大小 < 500 MB
     */
    private fun matchesCategory(model: ModelInfo, category: String): Boolean {
        return when (category) {
            "推荐" -> model.isDefault
            "代码" -> model.name.contains("code", ignoreCase = true) ||
                    model.name.contains("coder", ignoreCase = true) ||
                    model.description.contains("code", ignoreCase = true) ||
                    model.description.contains("coder", ignoreCase = true) ||
                    model.name.contains("代码", ignoreCase = true) ||
                    model.description.contains("代码", ignoreCase = true)
            "轻量" -> model.size < LIGHTWEIGHT_THRESHOLD
            else -> true // "全部" 或未知标签
        }
    }

    /**
     * 更新状态摘要区域。
     */
    private fun updateStatusSummary(ctrl: ClawController) {
        val state = ctrl.getLocalModelStatus()
        txtModelStatus.text = state

        val downloadedModels = ctrl.getDownloadedModels()
        txtDownloadCount.text = downloadedModels.size.toString()

        val loadedModel = getLoadedModel(ctrl)
        txtLoadedName.text = loadedModel?.modelInfo?.name ?: "无"

        val availableCount = ctrl.getModelSources().size
        txtAvailableCount.text = availableCount.toString()
    }

    /**
     * 通过反射获取当前加载的模型。
     */
    private fun getLoadedModel(ctrl: ClawController): DownloadedModel? {
        return try {
            val lmField = ctrl.javaClass.getDeclaredField("localModelManager")
            lmField.isAccessible = true
            val lm = lmField.get(ctrl) as LocalModelManager
            lm.getLoadedModel()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 创建单个模型卡片视图（Material Design 3 风格，带进度条和美化）。
     */
    private fun createModelCard(
        modelInfo: ModelInfo,
        isDownloaded: Boolean,
        isLoaded: Boolean,
        ctrl: ClawController
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(12))
            }
            // Material3 风格：大圆角、柔和阴影
            radius = dpToPx(16).toFloat()
            cardElevation = dpToPx(3).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.surface_white))
            strokeWidth = dpToPx(1).toInt()
            strokeColor = ContextCompat.getColor(this@ModelManagementActivity, R.color.card_stroke)
            // 点击涟漪效果
            isClickable = true
            isFocusable = true
            setRippleColor(ContextCompat.getColorStateList(this@ModelManagementActivity, R.color.card_shadow_light))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            background = ContextCompat.getDrawable(this@ModelManagementActivity, R.drawable.bg_gradient_model_card)
        }

        // ========== 第一行：模型名称 + 状态标签（带图标） ==========
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val nameText = TextView(this).apply {
            text = modelInfo.name
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameRow.addView(nameText)

        // 状态标签（带图标 + 圆角背景）
        val statusLabel = createStatusBadge(isLoaded, isDownloaded)
        nameRow.addView(statusLabel)
        container.addView(nameRow)

        // ========== 第二行：模型格式 + 大小 + 内存 ==========
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dpToPx(6), 0, 0)
        }

        val formatText = TextView(this).apply {
            text = modelInfo.format.extension.uppercase()
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_tertiary))
        }
        infoRow.addView(formatText)

        val sizeStr = if (modelInfo.size > 1024 * 1024 * 1024) {
            String.format(" | %.1f GB", modelInfo.size / (1024.0 * 1024 * 1024))
        } else {
            String.format(" | %.0f MB", modelInfo.size / (1024.0 * 1024))
        }
        val sizeText = TextView(this).apply {
            text = sizeStr
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_tertiary))
        }
        infoRow.addView(sizeText)

        val ramStr = String.format(" | 内存: %dMB", modelInfo.requiredRam / (1024 * 1024))
        val ramText = TextView(this).apply {
            text = ramStr
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_tertiary))
        }
        infoRow.addView(ramText)
        container.addView(infoRow)

        // ========== 第三行：描述 ==========
        val descText = TextView(this).apply {
            text = modelInfo.description
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_secondary))
            setPadding(0, dpToPx(6), 0, 0)
            maxLines = 2
        }
        container.addView(descText)

        // ========== 第四行：进度条区域（默认隐藏，下载时显示） ==========
        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dpToPx(8), 0, 0)
            visibility = View.GONE
        }

        val progressIndicator = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(4)
            )
            setIndicatorColor(
                ContextCompat.getColor(this@ModelManagementActivity, R.color.brand_primary)
            )
            trackColor = ContextCompat.getColor(this@ModelManagementActivity, R.color.brand_primary_light)
            isIndeterminate = true
            progress = 0
        }
        progressContainer.addView(progressIndicator)

        val progressText = TextView(this).apply {
            text = "连接中..."
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_tertiary))
            setPadding(0, dpToPx(4), 0, 0)
        }
        progressContainer.addView(progressText)

        // 将进度条容器添加到 actionRow 之前
        container.addView(progressContainer)

        // ========== 第五行：操作按钮行 ==========
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.END
            setPadding(0, dpToPx(10), 0, 0)
        }

        when {
            isLoaded -> {
                actionRow.addView(createMaterialButton("卸载", R.color.error_red) {
                    ctrl.unloadModel(modelInfo.id)
                    showToast("正在卸载 ${modelInfo.name}...")
                    handler.postDelayed({ refreshModelList() }, 1500)
                })
            }
            isDownloaded -> {
                actionRow.addView(createMaterialButton("加载", R.color.brand_primary) {
                    ctrl.loadModel(modelInfo.id)
                    showToast("正在加载 ${modelInfo.name}...")
                    handler.postDelayed({ refreshModelList() }, 2000)
                })
                actionRow.addView(createMaterialButton("删除", R.color.error_red) {
                    confirmDeleteModel(modelInfo, ctrl)
                })
            }
            else -> {
                actionRow.addView(createMaterialButton("下载", R.color.brand_primary) {
                    ctrl.downloadModel(modelInfo.id)
                    showToast("开始下载 ${modelInfo.name}...")
                    // 立即显示进度条，无需等待
                    progressContainer.visibility = View.VISIBLE
                    progressIndicator.isIndeterminate = true
                    progressText.text = "连接中..."
                    // 启动进度跟踪
                    trackDownloadProgress(modelInfo.id, progressIndicator, progressText, ctrl)
                })
            }
        }

        container.addView(actionRow)

        // 检查是否已有进行中的下载（例如从其他页面启动的）
        val existingProgress = ctrl.getModelDownloadProgress(modelInfo.id)
        if (existingProgress != null &&
            (existingProgress.status == DownloadStatus.DOWNLOADING ||
                    existingProgress.status == DownloadStatus.PENDING)
        ) {
            progressContainer.visibility = View.VISIBLE
            updateProgressDisplay(existingProgress, progressIndicator, progressText)
            // 如果正在下载中，继续跟踪进度
            if (existingProgress.status == DownloadStatus.DOWNLOADING) {
                trackDownloadProgress(modelInfo.id, progressIndicator, progressText, ctrl)
            }
        }

        card.addView(container)
        return card
    }

    /**
     * 跟踪指定模型的下载进度，实时更新进度条。
     *
     * 使用 [handler] 轮询 [ClawController.getModelDownloadProgress]，
     * 每次轮询间隔约 500ms，立即启动（无初始延迟）。
     */
    private fun trackDownloadProgress(
        modelId: String,
        progressIndicator: LinearProgressIndicator,
        progressText: TextView,
        ctrl: ClawController
    ) {
        val progressRunnable = object : Runnable {
            var checkCount = 0
            override fun run() {
                if (checkCount > 120) return // 最多跟踪约 60 秒
                checkCount++
                val progress = ctrl.getModelDownloadProgress(modelId)
                if (progress != null) {
                    when (progress.status) {
                        DownloadStatus.DOWNLOADING -> {
                            updateProgressDisplay(progress, progressIndicator, progressText)
                            handler.postDelayed(this, 500)
                        }
                        DownloadStatus.PENDING -> {
                            progressText.text = "等待中..."
                            handler.postDelayed(this, 500)
                        }
                        DownloadStatus.COMPLETED, DownloadStatus.VERIFIED -> {
                            // 下载完成，进度条填满后刷新列表
                            progressIndicator.isIndeterminate = false
                            progressIndicator.progress = 100
                            progressText.text = "下载完成！"
                            handler.postDelayed({
                                refreshModelList()
                            }, 800)
                        }
                        DownloadStatus.FAILED -> {
                            progressText.text = "下载失败"
                            progressIndicator.isIndeterminate = false
                            progressIndicator.progress = 0
                            showToast("模型下载失败")
                            handler.postDelayed({ refreshModelList() }, 1500)
                        }
                        else -> {
                            handler.postDelayed(this, 500)
                        }
                    }
                } else {
                    // progress 为 null 表示下载已结束或不存在，刷新列表
                    refreshModelList()
                }
            }
        }
        // 立即启动，无延迟
        progressRunnable.run()
    }

    /**
     * 更新进度条的显示状态。
     *
     * - 当 [DownloadProgress.percentage] == -1.0 或 [DownloadProgress.totalBytes] == -1L 时，
     *   显示"连接中..."并使进度条保持不定模式。
     * - 否则显示百分比和速度，进度条切换到定值模式。
     */
    private fun updateProgressDisplay(
        progress: DownloadProgress,
        progressIndicator: LinearProgressIndicator,
        progressText: TextView
    ) {
        if (progress.percentage < 0 || progress.totalBytes < 0) {
            // 未知总大小或百分比 — 连接中或正在获取元数据
            progressIndicator.isIndeterminate = true
            val speed = formatSpeed(progress.speedBytesPerSec)
            progressText.text = if (speed.isNotEmpty()) "连接中... ($speed)" else "连接中..."
        } else {
            progressIndicator.isIndeterminate = false
            // 进度值范围 0..100，带平滑动画
            val pct = progress.percentage.toInt().coerceIn(0, 100)
            // 使用 ValueAnimator 实现平滑进度条动画
            val animator = ValueAnimator.ofInt(progressIndicator.progress, pct).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    progressIndicator.progress = anim.animatedValue as Int
                }
            }
            animator.start()

            val pctText = String.format("%.1f%%", progress.percentage)
            val speed = formatSpeed(progress.speedBytesPerSec)
            val downloaded = formatBytes(progress.bytesDownloaded)
            val total = if (progress.totalBytes > 0) formatBytes(progress.totalBytes) else "?"
            progressText.text = "$pctText ($downloaded / $total, $speed)"
        }
    }

    /**
     * 格式化字节数为人类可读的字符串。
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.0f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /**
     * 格式化下载速度为人类可读的字符串。
     */
    private fun formatSpeed(speedBytesPerSec: Long): String {
        if (speedBytesPerSec <= 0) return ""
        return if (speedBytesPerSec >= 1024 * 1024) {
            String.format("%.1f MB/s", speedBytesPerSec / (1024.0 * 1024))
        } else {
            String.format("%.0f KB/s", speedBytesPerSec / 1024.0)
        }
    }

    /**
     * 创建状态标签（带图标 + 圆角背景）。
     */
    private fun createStatusBadge(isLoaded: Boolean, isDownloaded: Boolean): LinearLayout {
        val text: String
        val bgRes: Int
        val iconRes: Int
        val textColor: Int
        when {
            isLoaded -> {
                text = "已加载"
                bgRes = R.drawable.bg_status_loading
                iconRes = R.drawable.ic_action_verified
                textColor = R.color.success_green
            }
            isDownloaded -> {
                text = "已下载"
                bgRes = R.drawable.bg_status_downloaded
                iconRes = R.drawable.ic_action_verified
                textColor = R.color.brand_primary
            }
            else -> {
                text = "可下载"
                bgRes = R.drawable.bg_status_available
                iconRes = R.drawable.ic_action_memory
                textColor = R.color.warning_orange
            }
        }

        val badge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(4), dpToPx(10), dpToPx(4))
            background = ContextCompat.getDrawable(this@ModelManagementActivity, bgRes)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14))
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(this@ModelManagementActivity, textColor))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        badge.addView(icon)

        val label = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, textColor))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(4)
            }
        }
        badge.addView(label)

        return badge
    }

    /**
     * 创建 MaterialButton 风格的操作按钮。
     */
    private fun createMaterialButton(label: String, colorRes: Int, onClick: () -> Unit): MaterialButton {
        val btn = MaterialButton(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_on_primary))
            isAllCaps = false
            cornerRadius = dpToPx(8)
            setBackgroundColor(ContextCompat.getColor(this@ModelManagementActivity, colorRes))
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(6), 0, 0, 0)
            }
            setOnClickListener { onClick() }
        }
        return btn
    }

    /**
     * 创建空状态视图（带图标和提示文字）。
     */
    private fun createEmptyState(title: String, subtitle: String): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(48), 0, dpToPx(48))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56))
            setImageResource(R.drawable.ic_action_chip)
            setColorFilter(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_tertiary))
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0.5f
        }
        container.addView(icon)

        val titleText = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(12), 0, 0)
        }
        container.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_tertiary))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(4), 0, 0)
        }
        container.addView(subtitleText)

        return container
    }

    /**
     * 确认删除模型对话框。
     */
    private fun confirmDeleteModel(modelInfo: ModelInfo, ctrl: ClawController) {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除模型「${modelInfo.name}」吗？删除后需要重新下载。")
            .setPositiveButton("删除") { _, _ ->
                ctrl.deleteModel(modelInfo.id)
                showToast("已删除 ${modelInfo.name}")
                handler.postDelayed({ refreshModelList() }, 500)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 显示导入模型对话框。
     */
    private fun showImportDialog() {
        val ctrl = controller
        if (ctrl == null) {
            showToast("请先开启无障碍服务")
            return
        }

        val importableFiles = ctrl.scanImportableModels()
        if (importableFiles.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("导入模型")
                .setMessage("在 Download 目录中未找到可导入的模型文件。\n\n支持的格式：.gguf、.onnx、.tflite\n\n请将模型文件放入 Download 目录后重试。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val fileNames = importableFiles.map { File(it).name }
        MaterialAlertDialogBuilder(this)
            .setTitle("选择要导入的模型文件")
            .setItems(fileNames.toTypedArray()) { _, which ->
                val selectedPath = importableFiles[which]
                showToast("正在导入模型...")
                lifecycleScope.launch {
                    val modelId = ctrl.importModel(selectedPath)
                    if (modelId != null) {
                        showToast("模型导入成功！")
                    } else {
                        showToast("模型导入失败，文件可能已存在或格式不支持")
                    }
                    refreshModelList()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * dp 转 px。
     */
    private fun dpToPx(dp: Int): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}