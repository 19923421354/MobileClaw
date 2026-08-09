package com.mobileclaw.app

import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
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
 * - 可下载模型列表（支持下载/加载/删除操作）
 * - 本地模型文件导入
 * - 下拉刷新
 * - 进度条动画
 * - 实时刷新
 */
class ModelManagementActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ModelManagement"
        private const val REFRESH_INTERVAL_MS = 2000L
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
     * 刷新模型列表和状态摘要。
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

        // 刷新模型列表
        layoutModelList.removeAllViews()
        val modelSources = ctrl.getModelSources()
        val downloadedModels = ctrl.getDownloadedModels()
        val loadedModel = getLoadedModel(ctrl)

        if (modelSources.isEmpty()) {
            layoutModelList.addView(createEmptyState("暂无可用模型", "连接到网络后可获取可用模型列表"))
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
     * 创建单个模型卡片视图（Material Design 3 风格）。
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

        // 第一行：模型名称 + 状态标签（带图标）
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

        // 第二行：模型格式 + 大小 + 内存
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

        // 第三行：描述
        val descText = TextView(this).apply {
            text = modelInfo.description
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ModelManagementActivity, R.color.text_secondary))
            setPadding(0, dpToPx(6), 0, 0)
            maxLines = 2
        }
        container.addView(descText)

        // 第四行：进度条 + 操作按钮
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
                    // 显示下载进度条
                    showDownloadProgress(modelInfo.id, ctrl)
                })
            }
        }

        container.addView(actionRow)
        card.addView(container)
        return card
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
     * 显示下载进度条。
     */
    private fun showDownloadProgress(modelId: String, ctrl: ClawController) {
        val progressRunnable = object : Runnable {
            var checkCount = 0
            override fun run() {
                if (checkCount > 30) return // 最多跟踪 60 秒
                checkCount++
                val progress = ctrl.getModelDownloadProgress(modelId)
                if (progress != null) {
                    when (progress.status) {
                        DownloadStatus.DOWNLOADING -> {
                            val pct = if (progress.percentage >= 0) {
                                String.format("%.1f%%", progress.percentage)
                            } else {
                                "未知"
                            }
                            val speed = String.format("%.1f MB/s", progress.speedBytesPerSec / (1024.0 * 1024))
                            showToast("下载中: $pct ($speed)")
                            handler.postDelayed(this, 2000)
                        }
                        DownloadStatus.COMPLETED, DownloadStatus.VERIFIED -> {
                            showToast("下载完成！")
                            refreshModelList()
                        }
                        DownloadStatus.FAILED -> {
                            showToast("下载失败")
                            refreshModelList()
                        }
                        else -> {
                            handler.postDelayed(this, 2000)
                        }
                    }
                } else {
                    refreshModelList()
                }
            }
        }
        handler.postDelayed(progressRunnable, 1500)
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