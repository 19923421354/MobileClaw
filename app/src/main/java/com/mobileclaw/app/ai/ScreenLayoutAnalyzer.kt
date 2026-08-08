package com.mobileclaw.app.ai

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 屏幕布局分析器 —— 分析屏幕布局结构，提升 AI 动作定位的精准度。
 *
 * 核心理念：AI 在执行点击/输入等操作时，常因缺乏对屏幕布局的理解而定位错误。
 * 例如把「搜索」按钮误认为搜索输入框、把列表项误认为标题。通过对屏幕进行
 * 区域划分、元素分类与模式识别，可以为 AI 提供结构化的布局上下文，从而：
 * - 缩小目标搜索范围（先定位区域，再定位元素）
 * - 识别常见布局模式（列表页、对话框、搜索栏等），匹配对应操作策略
 * - 对用户意图进行目标元素推荐，减少 AI 的决策空间
 *
 * 分析能力：
 * 1. 布局区域检测：将屏幕划分为 HEADER（顶部 15%）、CONTENT（中部 75%）、
 *    FOOTER（底部 10%）、SIDEBAR（侧边栏）、MODAL/OVERLAY（居中弹层）。
 * 2. 元素分类：根据控件类名、可点击性、文本内容等，将元素分类为按钮、输入框、
 *    列表项、图片、文本块、标签页、图标、复选框等。
 * 3. 交互元素排序：根据元素大小、位置、文本相关性计算相关度分数，排序后供目标推荐使用。
 * 4. 布局模式检测：识别列表视图、标签栏、对话框、搜索栏、导航抽屉等常见模式。
 * 5. 屏幕变化检测：对比两次屏幕捕获，找出新增、消失、移动的元素及变化区域。
 * 6. 目标推荐：根据用户意图关键词，推荐最可能的目标元素。
 *
 * 使用方式：
 * ```
 * val analyzer = ScreenLayoutAnalyzer()
 * // 基于屏幕文本快速划分区域
 * val zones = analyzer.analyzeLayout(screenText, screenWidth, screenHeight)
 * // 基于无障碍 UI 元素详细分类
 * val elements = analyzer.classifyElements(screenText, uiElements)
 * // 检测布局模式
 * val pattern = analyzer.detectPattern(zones, elements)
 * // 根据意图推荐目标
 * val suggestion = analyzer.suggestTarget("搜索猫咪", elements)
 * // 获取分析摘要
 * val summary = analyzer.getAnalysisSummary()
 * ```
 */
class ScreenLayoutAnalyzer {

    private val tag = "ScreenLayoutAnalyzer"

    // ============================================================
    // 枚举定义
    // ============================================================

    /**
     * 屏幕区域类型。
     *
     * 表示屏幕上划分的逻辑区域，用于缩小元素搜索范围。
     */
    enum class ZoneType {
        /** 顶部区域（标题栏/工具栏，约占屏幕顶部 15%）。 */
        HEADER,

        /** 主内容区域（屏幕中部 75%）。 */
        CONTENT,

        /** 底部区域（底部导航栏/操作栏，约占屏幕底部 10%）。 */
        FOOTER,

        /** 侧边栏区域（导航抽屉/侧滑菜单）。 */
        SIDEBAR,

        /** 模态弹窗区域（居中对话框，遮罩背景）。 */
        MODAL,

        /** 浮层区域（非居中的悬浮层，如 Snackbar、底部弹窗）。 */
        OVERLAY,

        /** 未知区域（无法判定时使用）。 */
        UNKNOWN
    }

    /**
     * 屏幕元素类型。
     *
     * 表示 UI 元素的功能类别，用于辅助 AI 选择正确的操作方式。
     */
    enum class ElementType {
        /** 按钮（可点击的动作触发控件）。 */
        BUTTON,

        /** 输入框（可编辑文本的控件）。 */
        INPUT,

        /** 列表项（列表/网格中的单项）。 */
        LIST_ITEM,

        /** 图片（纯展示性图片控件）。 */
        IMAGE,

        /** 文本块（纯展示性文本控件）。 */
        TEXT,

        /** 标签页（Tab 栏中的单个标签）。 */
        TAB,

        /** 图标（带 contentDescription 的可点击小图标）。 */
        ICON,

        /** 复选框/开关（可切换状态的控件）。 */
        CHECKBOX,

        /** 未知类型（无法判定时使用）。 */
        UNKNOWN
    }

    /**
     * 布局模式类型。
     *
     * 表示屏幕整体布局的结构模式，用于匹配对应的操作策略。
     */
    enum class LayoutPatternType {
        /** 列表视图（垂直滚动的列表页面）。 */
        LIST_VIEW,

        /** 标签栏（顶部或底部的多标签切换栏）。 */
        TAB_BAR,

        /** 对话框（居中弹出的模态对话框）。 */
        DIALOG,

        /** 搜索栏（顶部含搜索输入框的布局）。 */
        SEARCH_BAR,

        /** 导航抽屉（侧滑出现的导航菜单）。 */
        NAV_DRAWER,

        /** 分栏视图（左右双栏布局，常见于平板）。 */
        SPLIT_VIEW,

        /** 网格视图（多行多列的网格布局）。 */
        GRID,

        /** 轮播视图（横向滑动的卡片轮播）。 */
        CAROUSEL,

        /** 未知模式（无法判定时使用）。 */
        UNKNOWN
    }

    // ============================================================
    // 数据类定义
    // ============================================================

    /**
     * 边界矩形（屏幕坐标系，单位为像素）。
     *
     * @property left   左边界
     * @property top    上边界
     * @property right  右边界
     * @property bottom 下边界
     */
    data class Bounds(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0
    ) {
        /** 中心点 X 坐标。 */
        val centerX: Int
            get() = (left + right) / 2

        /** 中心点 Y 坐标。 */
        val centerY: Int
            get() = (top + bottom) / 2

        /** 宽度。 */
        val width: Int
            get() = (right - left).coerceAtLeast(0)

        /** 高度。 */
        val height: Int
            get() = (bottom - top).coerceAtLeast(0)

        /** 面积（宽 × 高）。 */
        val area: Int
            get() = width * height

        /** 判断边界是否有效（宽高均大于 0）。 */
        val isValid: Boolean
            get() = width > 0 && height > 0
    }

    /**
     * 屏幕元素 —— 单个 UI 控件的结构化描述。
     *
     * @property text           元素显示的文本内容
     * @property bounds         元素在屏幕中的边界矩形
     * @property type           元素类型
     * @property isClickable    元素是否可点击
     * @property isInteractive  元素是否可交互（点击/编辑/滚动/聚焦等）
     * @property relevanceScore 相关度分数（0.0-1.0），越高越可能成为操作目标
     */
    data class ScreenElement(
        val text: String = "",
        val bounds: Bounds = Bounds(),
        val type: ElementType = ElementType.UNKNOWN,
        val isClickable: Boolean = false,
        val isInteractive: Boolean = false,
        val relevanceScore: Float = 0f
    )

    /**
     * 屏幕区域 —— 屏幕上一个逻辑区域及其包含的元素。
     *
     * @property type     区域类型
     * @property bounds   区域边界矩形
     * @property elements 该区域内的屏幕元素列表
     */
    data class ScreenZone(
        val type: ZoneType = ZoneType.UNKNOWN,
        val bounds: Bounds = Bounds(),
        val elements: List<ScreenElement> = emptyList()
    )

    /**
     * 布局模式 —— 检测到的屏幕整体布局结构。
     *
     * @property type             模式类型
     * @property confidence       置信度（0.0-1.0），越高越可信
     * @property detectedElements 参与检测的关键元素列表
     */
    data class LayoutPattern(
        val type: LayoutPatternType = LayoutPatternType.UNKNOWN,
        val confidence: Float = 0f,
        val detectedElements: List<ScreenElement> = emptyList()
    )

    /**
     * 屏幕差异 —— 两次屏幕捕获之间的变化。
     *
     * @property addedElements  新出现的元素列表
     * @property removedElements 消失的元素列表
     * @property movedElements  位置发生变化的元素列表
     * @property zoneChanged    发生变化的区域类型列表
     */
    data class ScreenDiff(
        val addedElements: List<ScreenElement> = emptyList(),
        val removedElements: List<ScreenElement> = emptyList(),
        val movedElements: List<ScreenElement> = emptyList(),
        val zoneChanged: List<ZoneType> = emptyList()
    )

    /**
     * 目标推荐 —— 根据用户意图推荐的最可能目标元素。
     *
     * @property element    推荐的目标元素
     * @property confidence 置信度（0.0-1.0），越高越可信
     * @property reason     推荐依据的人类可读说明
     */
    data class TargetSuggestion(
        val element: ScreenElement,
        val confidence: Float,
        val reason: String
    )

    companion object {

        /** 顶部区域占屏幕高度的比例（15%）。 */
        private const val HEADER_RATIO = 0.15f

        /** 底部区域占屏幕高度的比例（10%）。 */
        private const val FOOTER_RATIO = 0.10f

        /** 模态弹窗检测的最大元素数（超过则不视为对话框）。 */
        private const val MODAL_MAX_ELEMENTS = 8

        /** 列表视图检测的最小列表项数量。 */
        private const val LIST_VIEW_MIN_ITEMS = 3

        /** 网格视图检测的最小图片/卡片数量。 */
        private const val GRID_MIN_ITEMS = 4

        /** 标签栏检测的最小标签数量。 */
        private const val TAB_BAR_MIN_ITEMS = 2

        /** 目标推荐的最低置信度阈值，低于此值不返回推荐。 */
        private const val SUGGESTION_MIN_CONFIDENCE = 0.15f

        /** 文本分析的每行最大保留字符数。 */
        private const val LINE_TEXT_MAX_CHARS = 80

        // ---- 评分权重（目标推荐） ----

        /** 文本关键词匹配权重。 */
        private const val WEIGHT_KEYWORD = 0.50f

        /** 可点击性权重。 */
        private const val WEIGHT_CLICKABLE = 0.20f

        /** 元素大小权重。 */
        private const val WEIGHT_SIZE = 0.15f

        /** 位置居中度权重。 */
        private const val WEIGHT_POSITION = 0.10f

        /** 可交互性权重。 */
        private const val WEIGHT_INTERACTIVE = 0.05f

        // ---- 模态/对话框关键词 ----

        /** 标志着模态对话框的文本关键词。 */
        private val MODAL_KEYWORDS = listOf(
            "确定", "取消", "关闭", "确认", "提示", "警告", "对话框",
            "同意", "拒绝", "知道了", "稍后", "不再提示", "选择操作"
        )

        // ---- 搜索相关关键词 ----

        /** 标志着搜索栏的文本关键词。 */
        private val SEARCH_KEYWORDS = listOf(
            "搜索", "查找", "搜一下", "搜索内容", "输入关键词", "search", "find"
        )

        // ---- 导航抽屉关键词 ----

        /** 标志着导航抽屉/侧滑菜单的文本关键词。 */
        private val NAV_DRAWER_KEYWORDS = listOf(
            "首页", "菜单", "设置", "我的", "收藏", "历史", "消息",
            "通知", "朋友圈", "钱包", "个人中心", "导航", "扫一扫"
        )

        // ---- 标签页关键词 ----

        /** 标志着标签页的文本关键词。 */
        private val TAB_KEYWORDS = listOf(
            "推荐", "关注", "热门", "附近", "最新", "同城", "视频",
            "图片", "动态", "精选", "排行", "分类"
        )

        // ---- 动作动词（用于从文本识别可点击元素） ----

        /** 标志着可点击按钮的文本关键词。 */
        private val ACTION_VERBS = listOf(
            "点击", "搜索", "发送", "确定", "取消", "登录", "退出", "注销",
            "删除", "添加", "新增", "保存", "提交", "编辑", "修改", "分享",
            "下载", "上传", "安装", "打开", "关闭", "下一步", "上一步",
            "完成", "继续", "扫码", "扫描", "收藏", "点赞", "评论", "购买",
            "支付", "刷新", "复制", "粘贴", "选择", "切换", "开启", "停止",
            "播放", "暂停", "录制", "拍照", "拍摄", "发布", "发表", "回复",
            "转发", "关注", "举报", "反馈", "联系", "拨打", "接听", "挂断",
            "导航", "定位", "查找", "申请", "预约", "订阅", "确认", "返回"
        )

        // ---- 意图关键词提取时需排除的停用词 ----

        /** 意图分析时需排除的常见动作动词与无意义词。 */
        private val INTENT_STOP_WORDS = listOf(
            "打开", "点击", "搜索", "输入", "发送", "找到", "选择",
            "帮我", "请", "一下", "然后", "并", "和", "把", "给",
            "在", "去", "来", "要", "想", "需要", "进行", "操作"
        )

        // ---- 控件类名模式（用于元素类型分类） ----

        /** 按钮类名模式。 */
        private val BUTTON_CLASS_PATTERNS = listOf("Button", "ImageButton")

        /** 输入框类名模式。 */
        private val INPUT_CLASS_PATTERNS = listOf(
            "EditText", "AutoCompleteTextView", "SearchView", "MultiAutoCompleteTextView"
        )

        /** 复选框/开关类名模式。 */
        private val CHECKBOX_CLASS_PATTERNS = listOf(
            "CheckBox", "CheckedTextView", "Switch", "ToggleButton", "RadioButton"
        )

        /** 图片类名模式。 */
        private val IMAGE_CLASS_PATTERNS = listOf("ImageView")

        /** 标签页类名模式。 */
        private val TAB_CLASS_PATTERNS = listOf("TabLayout", "TabItem", "TabRow")

        /** 文本类名模式。 */
        private val TEXT_CLASS_PATTERNS = listOf("TextView", "AppCompatTextView")
    }

    // ============================================================
    // 分析状态（用于 getAnalysisSummary）
    // ============================================================

    /** 最近一次分析的区域列表。 */
    @Volatile
    private var lastZones: List<ScreenZone> = emptyList()

    /** 最近一次分析的元素列表。 */
    @Volatile
    private var lastElements: List<ScreenElement> = emptyList()

    /** 最近一次检测的布局模式。 */
    @Volatile
    private var lastPattern: LayoutPattern? = null

    /** 累计分析次数。 */
    @Volatile
    var analyzeCount: Int = 0
        private set

    // ============================================================
    // 布局区域检测
    // ============================================================

    /**
     * 分析屏幕布局，将屏幕划分为多个区域。
     *
     * 区域划分策略：
     * - HEADER：屏幕顶部 15% 的区域（标题栏/工具栏）
     * - FOOTER：屏幕底部 10% 的区域（底部导航/操作栏）
     * - CONTENT：中间 75% 的区域（主内容区）
     * - MODAL：当屏幕文本较少（< [MODAL_MAX_ELEMENTS] 行）且包含对话框关键词时，
     *   将居中元素识别为模态弹窗区域
     * - SIDEBAR：当文本开头包含导航抽屉关键词时，识别为侧边栏区域
     *
     * 由于此方法仅基于屏幕文本分析（无实际坐标），各行的 Y 坐标按行序号
     * 等比例估算。如需精确坐标，请配合 [classifyElements] 使用。
     *
     * @param screenText    屏幕文本（多行，每行代表一个文本元素）
     * @param screenWidth   屏幕宽度（像素）
     * @param screenHeight  屏幕高度（像素）
     * @return 划分后的区域列表（仅包含有元素的区域）
     */
    fun analyzeLayout(
        screenText: String,
        screenWidth: Int,
        screenHeight: Int
    ): List<ScreenZone> {
        val lines = screenText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(50)  // 限制最大行数，避免过多文本影响性能

        if (lines.isEmpty() || screenHeight <= 0 || screenWidth <= 0) {
            Log.d(tag, "analyzeLayout: 屏幕文本为空或尺寸无效")
            lastZones = emptyList()
            analyzeCount++
            return emptyList()
        }

        // 估算每行高度，将行序号映射为 Y 坐标
        val lineHeight = screenHeight.toFloat() / lines.size
        val headerBoundary = screenHeight * HEADER_RATIO
        val footerBoundary = screenHeight * (1f - FOOTER_RATIO)

        // 为每行创建屏幕元素（含估算边界）
        val allElements = lines.mapIndexed { index, line ->
            val top = (index * lineHeight).toInt()
            val bottom = ((index + 1) * lineHeight).toInt()
            val isAction = containsActionVerb(line)
            ScreenElement(
                text = line.take(LINE_TEXT_MAX_CHARS),
                bounds = Bounds(0, top, screenWidth, bottom),
                type = if (isAction) ElementType.BUTTON else ElementType.TEXT,
                isClickable = isAction,
                isInteractive = isAction,
                relevanceScore = computeTextRelevanceScore(line, index, lines.size)
            )
        }

        val zones = mutableListOf<ScreenZone>()

        // 1. 检测模态弹窗
        val modalZone = detectModalZone(allElements, screenWidth, screenHeight)
        if (modalZone != null) {
            zones.add(modalZone)
            // 模态弹窗存在时，其余元素归入 OVERLAY 背景
            val bgElements = allElements.filter { el ->
                modalZone.elements.none { it.text == el.text }
            }
            if (bgElements.isNotEmpty()) {
                zones.add(ScreenZone(
                    type = ZoneType.OVERLAY,
                    bounds = Bounds(0, 0, screenWidth, screenHeight),
                    elements = bgElements
                ))
            }
            lastZones = zones
            analyzeCount++
            Log.d(tag, "analyzeLayout: 检测到模态弹窗，区域数=${zones.size}")
            return zones
        }

        // 2. 检测侧边栏
        val sidebarZone = detectSidebarZone(allElements, screenWidth, screenHeight)
        if (sidebarZone != null) {
            zones.add(sidebarZone)
        }

        // 3. 按 Y 坐标划分 HEADER / CONTENT / FOOTER
        val headerElements = mutableListOf<ScreenElement>()
        val contentElements = mutableListOf<ScreenElement>()
        val footerElements = mutableListOf<ScreenElement>()

        for (element in allElements) {
            // 排除已归入侧边栏的元素
            if (sidebarZone != null && sidebarZone.elements.any { it.text == element.text }) {
                continue
            }
            val centerY = element.bounds.centerY.toFloat()
            when {
                centerY < headerBoundary -> headerElements.add(element)
                centerY >= footerBoundary -> footerElements.add(element)
                else -> contentElements.add(element)
            }
        }

        if (headerElements.isNotEmpty()) {
            zones.add(ScreenZone(
                type = ZoneType.HEADER,
                bounds = Bounds(0, 0, screenWidth, headerBoundary.toInt()),
                elements = headerElements
            ))
        }
        if (contentElements.isNotEmpty()) {
            zones.add(ScreenZone(
                type = ZoneType.CONTENT,
                bounds = Bounds(0, headerBoundary.toInt(), screenWidth, footerBoundary.toInt()),
                elements = contentElements
            ))
        }
        if (footerElements.isNotEmpty()) {
            zones.add(ScreenZone(
                type = ZoneType.FOOTER,
                bounds = Bounds(0, footerBoundary.toInt(), screenWidth, screenHeight),
                elements = footerElements
            ))
        }

        lastZones = zones
        analyzeCount++
        Log.d(tag, "analyzeLayout: 区域数=${zones.size}，元素数=${allElements.size}")
        return zones
    }

    // ============================================================
    // 元素分类
    // ============================================================

    /**
     * 对 UI 元素进行详细分类。
     *
     * 根据控件类名、可点击性、可编辑性、文本内容与屏幕上下文，
     * 将每个 [ScreenStateCache.UiElementInfo] 转换为带类型标注的 [ScreenElement]。
     *
     * 分类规则（按优先级从高到低）：
     * 1. 类名匹配：CheckBox/Switch → CHECKBOX，EditText → INPUT，Button → BUTTON，
     *    TabLayout → TAB，ImageView → IMAGE，TextView → TEXT
     * 2. 可编辑性：isEditable=true → INPUT
     * 3. 文本上下文：屏幕文本含搜索关键词且元素可编辑 → INPUT（搜索框）
     * 4. 可点击性 + 文本长度：可点击且文本很短 + 有 contentDescription → ICON
     * 5. 可点击性：isClickable=true → BUTTON
     * 6. 兜底：TEXT
     *
     * 同时计算每个元素的 [relevanceScore]（相关度分数），综合考虑大小、位置、
     * 可点击性与文本长度，用于后续目标推荐排序。
     *
     * @param screenText 屏幕文本（提供上下文辅助分类）
     * @param uiElements 无障碍服务采集的 UI 元素列表
     * @return 分类后的屏幕元素列表
     */
    fun classifyElements(
        screenText: String,
        uiElements: List<ScreenStateCache.UiElementInfo>
    ): List<ScreenElement> {
        if (uiElements.isEmpty()) {
            lastElements = emptyList()
            return emptyList()
        }

        // 推断屏幕尺寸（取所有元素边界的外接矩形）
        val screenBounds = inferScreenBounds(uiElements)
        val maxWidth = screenBounds.width.coerceAtLeast(1)
        val maxArea = uiElements.mapNotNull { parseBounds(it.bounds) }
            .maxOfOrNull { it.area }
            ?.coerceAtLeast(1) ?: 1

        // 屏幕文本是否包含搜索关键词（辅助分类搜索输入框）
        val hasSearchContext = SEARCH_KEYWORDS.any { kw ->
            screenText.contains(kw, ignoreCase = true)
        }

        // 列表项检测：找出垂直排列、尺寸相似的元素组
        val listItemTexts = detectListItemTexts(uiElements)

        val elements = uiElements.mapIndexed { _, info ->
            val bounds = parseBounds(info.bounds)
            val elementType = classifyElementType(info, hasSearchContext, listItemTexts)
            val isClickable = info.isClickable
            val isInteractive = isClickable || info.isEditable
            val score = computeElementRelevanceScore(
                bounds, isClickable, isInteractive, info.text, maxArea, maxWidth
            )

            ScreenElement(
                text = info.text.take(LINE_TEXT_MAX_CHARS),
                bounds = bounds,
                type = elementType,
                isClickable = isClickable,
                isInteractive = isInteractive,
                relevanceScore = score
            )
        }

        lastElements = elements
        Log.d(tag, "classifyElements: 分类 ${elements.size} 个元素")
        return elements
    }

    // ============================================================
    // 布局模式检测
    // ============================================================

    /**
     * 检测屏幕的布局模式。
     *
     * 依次检测以下模式，返回置信度最高的一个：
     * 1. DIALOG：存在 MODAL 区域且元素数较少
     * 2. SEARCH_BAR：HEADER 区域含 INPUT 元素且文本含搜索关键词
     * 3. NAV_DRAWER：存在 SIDEBAR 区域
     * 4. TAB_BAR：HEADER 或 FOOTER 区域含多个 TAB 元素
     * 5. LIST_VIEW：CONTENT 区域含多个 LIST_ITEM 元素
     * 6. GRID：CONTENT 区域含多个 IMAGE 元素且呈网格排列
     * 7. SPLIT_VIEW：CONTENT 区域分为左右两部分
     * 8. CAROUSEL：CONTENT 区域含横向滚动元素
     *
     * @param zones    屏幕区域列表
     * @param elements 屏幕元素列表
     * @return 置信度最高的布局模式
     */
    fun detectPattern(
        zones: List<ScreenZone>,
        elements: List<ScreenElement>
    ): LayoutPattern {
        if (zones.isEmpty() && elements.isEmpty()) {
            lastPattern = LayoutPattern(LayoutPatternType.UNKNOWN, 0f)
            return lastPattern!!
        }

        val candidates = mutableListOf<LayoutPattern>()

        // 1. 对话框检测
        detectDialog(zones)?.let { candidates.add(it) }

        // 2. 搜索栏检测
        detectSearchBar(zones, elements)?.let { candidates.add(it) }

        // 3. 导航抽屉检测
        detectNavDrawer(zones)?.let { candidates.add(it) }

        // 4. 标签栏检测
        detectTabBar(zones, elements)?.let { candidates.add(it) }

        // 5. 列表视图检测
        detectListView(zones, elements)?.let { candidates.add(it) }

        // 6. 网格视图检测
        detectGrid(zones, elements)?.let { candidates.add(it) }

        // 7. 分栏视图检测
        detectSplitView(zones, elements)?.let { candidates.add(it) }

        // 8. 轮播视图检测
        detectCarousel(zones, elements)?.let { candidates.add(it) }

        // 返回置信度最高的模式
        val best = candidates.maxByOrNull { it.confidence }
            ?: LayoutPattern(LayoutPatternType.UNKNOWN, 0f)

        lastPattern = best
        Log.d(tag, "detectPattern: ${best.type} (置信度=${"%.2f".format(best.confidence)})")
        return best
    }

    // ============================================================
    // 屏幕变化检测
    // ============================================================

    /**
     * 对比两次屏幕捕获，检测变化。
     *
     * 差异检测策略：
     * - addedElements：新屏幕中存在但旧屏幕中不存在的文本行
     * - removedElements：旧屏幕中存在但新屏幕中不存在的文本行
     * - movedElements：两屏都存在但位置（行号）发生变化的文本行
     * - zoneChanged：根据新增/消失行的位置推断发生变化的区域类型
     *
     * @param oldText 旧屏幕文本
     * @param newText 新屏幕文本
     * @return 屏幕差异结果
     */
    fun diffScreens(oldText: String, newText: String): ScreenDiff {
        val oldLines = extractTextLines(oldText)
        val newLines = extractTextLines(newText)

        if (oldLines.isEmpty() && newLines.isEmpty()) {
            return ScreenDiff()
        }

        val oldSet = oldLines.toSet()
        val newSet = newLines.toSet()

        // 新增的行
        val addedTexts = newSet - oldSet
        // 消失的行
        val removedTexts = oldSet - newSet
        // 两屏都存在的行
        val commonTexts = oldSet.intersect(newSet)

        // 检测移动的行：行文本相同但行号不同
        val movedTexts = mutableListOf<String>()
        for (text in commonTexts) {
            val oldIndex = oldLines.indexOf(text)
            val newIndex = newLines.indexOf(text)
            if (oldIndex != newIndex && oldIndex >= 0 && newIndex >= 0) {
                movedTexts.add(text)
            }
        }

        // 构建差异元素
        val addedElements = addedTexts.map { text ->
            ScreenElement(
                text = text.take(LINE_TEXT_MAX_CHARS),
                type = if (containsActionVerb(text)) ElementType.BUTTON else ElementType.TEXT,
                isClickable = containsActionVerb(text),
                isInteractive = containsActionVerb(text)
            )
        }
        val removedElements = removedTexts.map { text ->
            ScreenElement(
                text = text.take(LINE_TEXT_MAX_CHARS),
                type = if (containsActionVerb(text)) ElementType.BUTTON else ElementType.TEXT,
                isClickable = containsActionVerb(text),
                isInteractive = containsActionVerb(text)
            )
        }
        val movedElements = movedTexts.map { text ->
            ScreenElement(
                text = text.take(LINE_TEXT_MAX_CHARS),
                type = if (containsActionVerb(text)) ElementType.BUTTON else ElementType.TEXT,
                isClickable = containsActionVerb(text),
                isInteractive = containsActionVerb(text)
            )
        }

        // 推断变化的区域
        val zoneChanged = mutableSetOf<ZoneType>()
        val totalLines = newLines.size.coerceAtLeast(1)
        for (text in addedTexts) {
            val index = newLines.indexOf(text)
            if (index >= 0) {
                zoneChanged.add(estimateZoneFromPosition(index, totalLines))
            }
        }
        for (text in removedTexts) {
            val index = oldLines.indexOf(text)
            if (index >= 0) {
                zoneChanged.add(estimateZoneFromPosition(index, oldLines.size.coerceAtLeast(1)))
            }
        }

        val diff = ScreenDiff(
            addedElements = addedElements,
            removedElements = removedElements,
            movedElements = movedElements,
            zoneChanged = zoneChanged.toList()
        )

        Log.d(tag, "diffScreens: +${addedElements.size} -${removedElements.size} " +
                "移${movedElements.size} 区域变化=${zoneChanged.size}")
        return diff
    }

    // ============================================================
    // 目标推荐
    // ============================================================

    /**
     * 根据用户意图推荐最可能的目标元素。
     *
     * 推荐流程：
     * 1. 从意图文本中提取关键词（去除停用动词，保留名词/实体词）
     * 2. 对每个元素计算加权得分：
     *    - 文本关键词匹配（权重 0.50）：元素文本包含意图关键词的匹配率
     *    - 可点击性（权重 0.20）：可点击元素优先
     *    - 元素大小（权重 0.15）：面积更大的元素优先（更易点击）
     *    - 位置居中度（权重 0.10）：靠近屏幕中心的元素优先
     *    - 可交互性（权重 0.05）：可交互元素优先
     * 3. 返回得分最高且达到 [SUGGESTION_MIN_CONFIDENCE] 阈值的元素
     *
     * @param intent   用户意图文本（如「搜索猫咪」「给张三发消息」）
     * @param elements 屏幕元素列表
     * @return 目标推荐结果，无匹配时返回 null
     */
    fun suggestTarget(
        intent: String,
        elements: List<ScreenElement>
    ): TargetSuggestion? {
        if (intent.isBlank() || elements.isEmpty()) {
            return null
        }

        val keywords = extractIntentKeywords(intent)
        if (keywords.isEmpty()) {
            Log.d(tag, "suggestTarget: 未能从意图中提取关键词: $intent")
            return null
        }

        // 推断屏幕尺寸（取所有元素边界的外接矩形）
        val screenWidth = elements.map { it.bounds.right }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val screenHeight = elements.map { it.bounds.bottom }.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxArea = elements.map { it.bounds.area }.maxOrNull()?.coerceAtLeast(1) ?: 1

        var bestElement: ScreenElement? = null
        var bestScore = 0f
        var bestReason = ""

        for (element in elements) {
            val keywordScore = computeKeywordMatchScore(element.text, keywords)
            // 关键词不匹配的元素大幅降低得分
            if (keywordScore <= 0f) continue

            val clickableScore = if (element.isClickable) 1f else 0f
            val sizeScore = element.bounds.area.toFloat() / maxArea
            val positionScore = computePositionScore(element.bounds, screenWidth, screenHeight)
            val interactiveScore = if (element.isInteractive) 1f else 0f

            val score = keywordScore * WEIGHT_KEYWORD +
                    clickableScore * WEIGHT_CLICKABLE +
                    sizeScore * WEIGHT_SIZE +
                    positionScore * WEIGHT_POSITION +
                    interactiveScore * WEIGHT_INTERACTIVE

            if (score > bestScore) {
                bestScore = score
                bestElement = element
                bestReason = buildSuggestionReason(
                    keywordScore, clickableScore, sizeScore, positionScore, keywords
                )
            }
        }

        if (bestElement == null || bestScore < SUGGESTION_MIN_CONFIDENCE) {
            Log.d(tag, "suggestTarget: 无匹配目标 (最高得分=${"%.2f".format(bestScore)})")
            return null
        }

        val confidence = bestScore.coerceIn(0f, 1f)
        Log.d(tag, "suggestTarget: ${bestElement.text} (${ "%.1f".format(confidence * 100)}%) - $bestReason")
        return TargetSuggestion(bestElement, confidence, bestReason)
    }

    // ============================================================
    // 分析摘要
    // ============================================================

    /**
     * 获取分析摘要（用于 UI 展示与调试）。
     *
     * 返回最近一次分析的统计信息，包括区域数、元素分类分布、
     * 检测到的布局模式与累计分析次数。
     *
     * @return 分析摘要文本
     */
    fun getAnalysisSummary(): String {
        val zoneSummary = if (lastZones.isEmpty()) {
            "无"
        } else {
            lastZones.joinToString("、") { zone ->
                "${zone.type.name}(${zone.elements.size})"
            }
        }

        val elementSummary = if (lastElements.isEmpty()) {
            "无"
        } else {
            // 按类型分组统计
            val typeCounts = lastElements.groupBy { it.type }
                .map { (type, list) -> "${type.name}=${list.size}" }
                .joinToString("、")
            "共${lastElements.size}个 [$typeCounts]"
        }

        val patternSummary = lastPattern?.let { p ->
            "${p.type.name}(${"%.0f%%".format(p.confidence * 100)})"
        } ?: "未检测"

        return buildString {
            appendLine("屏幕布局分析: 分析次数=$analyzeCount")
            appendLine("  区域: $zoneSummary")
            appendLine("  元素: $elementSummary")
            appendLine("  模式: $patternSummary")
        }.trim()
    }

    /**
     * 重置分析状态（清空缓存的区域、元素与模式数据）。
     */
    fun reset() {
        lastZones = emptyList()
        lastElements = emptyList()
        lastPattern = null
        Log.d(tag, "已重置分析状态")
    }

    // ============================================================
    // 私有工具：区域检测
    // ============================================================

    /**
     * 检测模态弹窗区域。
     *
     * 判定条件：
     * - 元素总数 <= [MODAL_MAX_ELEMENTS]
     * - 元素文本包含至少一个对话框关键词
     * - 元素集中在屏幕中部（居中分布）
     *
     * @param elements     全部屏幕元素
     * @param screenWidth  屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 模态区域，不满足条件时返回 null
     */
    private fun detectModalZone(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenZone? {
        if (elements.size > MODAL_MAX_ELEMENTS) return null

        val hasModalKeyword = elements.any { el ->
            MODAL_KEYWORDS.any { kw -> el.text.contains(kw) }
        }
        if (!hasModalKeyword) return null

        // 检查元素是否集中在屏幕中部（Y 坐标在 20%-80% 之间）
        val centerYStart = screenHeight * 0.20f
        val centerYEnd = screenHeight * 0.80f
        val centeredCount = elements.count { el ->
            val cy = el.bounds.centerY.toFloat()
            cy in centerYStart..centerYEnd
        }

        // 超过半数元素在中间区域才视为模态弹窗
        if (centeredCount < elements.size * 0.5f) return null

        // 估算模态区域的边界（所有居中元素的外接矩形）
        val centeredElements = elements.filter { el ->
            val cy = el.bounds.centerY.toFloat()
            cy in centerYStart..centerYEnd
        }
        val bounds = computeOuterBounds(centeredElements, screenWidth, screenHeight)

        Log.d(tag, "检测到模态弹窗: ${centeredElements.size} 个居中元素")
        return ScreenZone(
            type = ZoneType.MODAL,
            bounds = bounds,
            elements = centeredElements
        )
    }

    /**
     * 检测侧边栏区域。
     *
     * 判定条件：文本前几行包含导航抽屉关键词（首页、菜单、设置等），
     * 表明可能是侧滑菜单。
     *
     * @param elements     全部屏幕元素
     * @param screenWidth  屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 侧边栏区域，不满足条件时返回 null
     */
    private fun detectSidebarZone(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int
    ): ScreenZone? {
        // 检查前 6 行是否包含导航抽屉关键词
        val headElements = elements.take(6)
        val navElements = headElements.filter { el ->
            NAV_DRAWER_KEYWORDS.any { kw -> el.text.contains(kw) }
        }

        // 至少匹配 2 个导航关键词才认为是侧边栏
        if (navElements.size < 2) return null

        // 侧边栏通常占屏幕宽度的 70%-80%
        val sidebarWidth = (screenWidth * 0.75f).toInt()
        val bounds = Bounds(0, 0, sidebarWidth, screenHeight)

        Log.d(tag, "检测到侧边栏: ${navElements.size} 个导航元素")
        return ScreenZone(
            type = ZoneType.SIDEBAR,
            bounds = bounds,
            elements = navElements
        )
    }

    /**
     * 计算多个元素的外接矩形。
     */
    private fun computeOuterBounds(
        elements: List<ScreenElement>,
        screenWidth: Int,
        screenHeight: Int
    ): Bounds {
        if (elements.isEmpty()) return Bounds(0, 0, screenWidth, screenHeight)
        val left = elements.minOf { it.bounds.left }
        val top = elements.minOf { it.bounds.top }
        val right = elements.maxOf { it.bounds.right }
        val bottom = elements.maxOf { it.bounds.bottom }
        return Bounds(left, top, right, bottom)
    }

    // ============================================================
    // 私有工具：元素分类
    // ============================================================

    /**
     * 根据控件信息分类元素类型。
     *
     * @param info             UI 元素信息
     * @param hasSearchContext 屏幕是否包含搜索上下文
     * @param listItemTexts    被识别为列表项的文本集合
     * @return 元素类型
     */
    private fun classifyElementType(
        info: ScreenStateCache.UiElementInfo,
        hasSearchContext: Boolean,
        listItemTexts: Set<String>
    ): ElementType {
        val className = info.className ?: ""
        val text = info.text
        val desc = info.contentDescription ?: ""

        // 1. 复选框/开关（优先级最高，类名最明确）
        if (CHECKBOX_CLASS_PATTERNS.any { className.contains(it) }) {
            return ElementType.CHECKBOX
        }

        // 2. 输入框
        if (INPUT_CLASS_PATTERNS.any { className.contains(it) } || info.isEditable) {
            return ElementType.INPUT
        }
        // 搜索上下文下的可编辑元素也归为输入框
        if (hasSearchContext && info.isEditable) {
            return ElementType.INPUT
        }

        // 3. 按钮
        if (BUTTON_CLASS_PATTERNS.any { className.contains(it) }) {
            return ElementType.BUTTON
        }

        // 4. 标签页
        if (TAB_CLASS_PATTERNS.any { className.contains(it) }) {
            return ElementType.TAB
        }
        // 文本含标签关键词且可点击
        if (info.isClickable && TAB_KEYWORDS.any { kw -> text.contains(kw) }) {
            // 需排除明显是按钮的文本
            if (!ACTION_VERBS.any { kw -> text == kw }) {
                return ElementType.TAB
            }
        }

        // 5. 图片
        if (IMAGE_CLASS_PATTERNS.any { className.contains(it) }) {
            return ElementType.IMAGE
        }

        // 6. 列表项
        if (text.isNotEmpty() && listItemTexts.contains(text)) {
            return ElementType.LIST_ITEM
        }

        // 7. 图标：可点击 + 文本很短/为空 + 有 contentDescription
        if (info.isClickable && text.length <= 2 && desc.isNotEmpty()) {
            return ElementType.ICON
        }

        // 8. 可点击元素兜底为按钮
        if (info.isClickable) {
            return ElementType.BUTTON
        }

        // 9. 文本
        if (TEXT_CLASS_PATTERNS.any { className.contains(it) } || text.isNotEmpty()) {
            return ElementType.TEXT
        }

        return ElementType.UNKNOWN
    }

    /**
     * 检测列表项文本集合。
     *
     * 通过分析元素的垂直排列与尺寸相似性，识别可能的列表项。
     * 如果 3 个以上元素具有相近的宽度和高度且垂直排列，则视为列表项。
     *
     * @param uiElements UI 元素列表
     * @return 被识别为列表项的文本集合
     */
    private fun detectListItemTexts(uiElements: List<ScreenStateCache.UiElementInfo>): Set<String> {
        val parsed = uiElements.mapNotNull { info ->
            val bounds = parseBounds(info.bounds)
            if (bounds.isValid && info.text.isNotEmpty()) {
                Triple(info.text, bounds, info)
            } else {
                null
            }
        }
        if (parsed.size < LIST_VIEW_MIN_ITEMS) return emptySet()

        // 按宽度分组（宽度相近的元素可能属于同一列表）
        val widthGroups = parsed.groupBy { (it.second.width / 50) * 50 }
        val result = mutableSetOf<String>()

        for ((_, group) in widthGroups) {
            if (group.size < LIST_VIEW_MIN_ITEMS) continue

            // 按 Y 坐标排序，检查是否垂直排列
            val sorted = group.sortedBy { it.second.top }
            var consecutive = 1
            for (i in 1 until sorted.size) {
                val prevHeight = sorted[i - 1].second.height
                val gap = sorted[i].second.top - sorted[i - 1].second.bottom
                // 间距不超过前一个元素高度的 2 倍，视为连续列表项
                if (gap in 0..(prevHeight * 2)) {
                    consecutive++
                } else {
                    if (consecutive >= LIST_VIEW_MIN_ITEMS) {
                        // 将这一连续段的所有元素文本加入结果
                        sorted.subList(i - consecutive, i).forEach { result.add(it.first) }
                    }
                    consecutive = 1
                }
            }
            // 处理末尾段
            if (consecutive >= LIST_VIEW_MIN_ITEMS) {
                sorted.subList(sorted.size - consecutive, sorted.size).forEach { result.add(it.first) }
            }
        }

        return result
    }

    /**
     * 计算文本元素的相关度分数（用于 analyzeLayout 中的纯文本分析）。
     *
     * @param text       文本内容
     * @param lineIndex  行序号
     * @param totalLines 总行数
     * @return 相关度分数（0.0-1.0）
     */
    private fun computeTextRelevanceScore(
        text: String,
        lineIndex: Int,
        totalLines: Int
    ): Float {
        var score = 0f

        // 包含动作动词的文本得分更高
        if (containsActionVerb(text)) {
            score += 0.4f
        }

        // 文本长度适中（5-30 字）的得分更高
        val len = text.length
        score += when {
            len in 5..30 -> 0.3f
            len in 2..50 -> 0.2f
            len > 0 -> 0.1f
            else -> 0f
        }

        // 位于屏幕中部的元素得分更高（用户视线焦点区域）
        val centerY = (lineIndex + 0.5f) / totalLines
        score += when {
            centerY in 0.3f..0.7f -> 0.3f
            centerY in 0.15f..0.85f -> 0.2f
            else -> 0.1f
        }

        return score.coerceIn(0f, 1f)
    }

    /**
     * 计算元素的相关度分数（用于 classifyElements 中的精确分析）。
     *
     * @param bounds        元素边界
     * @param isClickable   是否可点击
     * @param isInteractive 是否可交互
     * @param text          元素文本
     * @param maxArea       屏幕上最大元素的面积
     * @param maxWidth      屏幕宽度
     * @return 相关度分数（0.0-1.0）
     */
    private fun computeElementRelevanceScore(
        bounds: Bounds,
        isClickable: Boolean,
        isInteractive: Boolean,
        text: String,
        maxArea: Int,
        maxWidth: Int
    ): Float {
        var score = 0f

        // 可点击元素得分更高
        if (isClickable) score += 0.3f
        if (isInteractive) score += 0.1f

        // 面积得分（归一化到 0-0.3）
        val areaScore = if (maxArea > 0) {
            (bounds.area.toFloat() / maxArea * 0.3f).coerceAtMost(0.3f)
        } else {
            0f
        }
        score += areaScore

        // 文本得分
        if (text.isNotEmpty()) {
            score += when {
                text.length in 2..20 -> 0.2f
                else -> 0.1f
            }
        }

        // 宽度得分（占屏幕宽度比例高的元素更显眼）
        if (maxWidth > 0 && bounds.width > 0) {
            val widthRatio = bounds.width.toFloat() / maxWidth
            score += (widthRatio * 0.1f).coerceAtMost(0.1f)
        }

        return score.coerceIn(0f, 1f)
    }

    // ============================================================
    // 私有工具：模式检测
    // ============================================================

    /**
     * 检测对话框模式。
     *
     * 判定条件：存在 MODAL 区域或 OVERLAY 区域，且元素数较少。
     */
    private fun detectDialog(
        zones: List<ScreenZone>
    ): LayoutPattern? {
        val modalZone = zones.firstOrNull { it.type == ZoneType.MODAL }
        val overlayZone = zones.firstOrNull { it.type == ZoneType.OVERLAY }

        val targetZone = modalZone ?: overlayZone ?: return null

        // 元素数较少且包含对话框关键词
        val zoneElements = targetZone.elements
        val hasKeyword = zoneElements.any { el ->
            MODAL_KEYWORDS.any { kw -> el.text.contains(kw) }
        }
        if (!hasKeyword) return null

        val confidence = when {
            modalZone != null && zoneElements.size <= 5 -> 0.9f
            modalZone != null -> 0.7f
            else -> 0.5f
        }

        return LayoutPattern(
            type = LayoutPatternType.DIALOG,
            confidence = confidence,
            detectedElements = zoneElements
        )
    }

    /**
     * 检测搜索栏模式。
     *
     * 判定条件：HEADER 区域含 INPUT 元素，或元素文本含搜索关键词。
     */
    private fun detectSearchBar(
        zones: List<ScreenZone>,
        elements: List<ScreenElement>
    ): LayoutPattern? {
        val headerZone = zones.firstOrNull { it.type == ZoneType.HEADER }
        val searchElements = elements.filter { el ->
            el.type == ElementType.INPUT ||
            SEARCH_KEYWORDS.any { kw -> el.text.contains(kw, ignoreCase = true) }
        }

        if (searchElements.isEmpty()) return null

        // 如果搜索元素在 HEADER 区域，置信度更高
        val inHeader = headerZone?.elements?.any { headerEl ->
            searchElements.any { it.text == headerEl.text }
        } ?: false

        val confidence = if (inHeader) 0.85f else 0.6f

        return LayoutPattern(
            type = LayoutPatternType.SEARCH_BAR,
            confidence = confidence,
            detectedElements = searchElements
        )
    }

    /**
     * 检测导航抽屉模式。
     *
     * 判定条件：存在 SIDEBAR 区域。
     */
    private fun detectNavDrawer(zones: List<ScreenZone>): LayoutPattern? {
        val sidebarZone = zones.firstOrNull { it.type == ZoneType.SIDEBAR } ?: return null

        val navCount = sidebarZone.elements.count { el ->
            NAV_DRAWER_KEYWORDS.any { kw -> el.text.contains(kw) }
        }
        if (navCount < 2) return null

        val confidence = (0.5f + navCount * 0.1f).coerceAtMost(0.9f)

        return LayoutPattern(
            type = LayoutPatternType.NAV_DRAWER,
            confidence = confidence,
            detectedElements = sidebarZone.elements
        )
    }

    /**
     * 检测标签栏模式。
     *
     * 判定条件：HEADER 或 FOOTER 区域含 [TAB_BAR_MIN_ITEMS] 个以上 TAB 元素。
     */
    private fun detectTabBar(
        zones: List<ScreenZone>,
        elements: List<ScreenElement>
    ): LayoutPattern? {
        val tabElements = elements.filter { it.type == ElementType.TAB }
        if (tabElements.size < TAB_BAR_MIN_ITEMS) return null

        // 检查 TAB 元素是否在 HEADER 或 FOOTER 区域
        val headerZone = zones.firstOrNull { it.type == ZoneType.HEADER }
        val footerZone = zones.firstOrNull { it.type == ZoneType.FOOTER }

        val inHeader = headerZone?.elements?.count { headerEl ->
            tabElements.any { it.text == headerEl.text }
        } ?: 0
        val inFooter = footerZone?.elements?.count { footerEl ->
            tabElements.any { it.text == footerEl.text }
        } ?: 0

        val confidence = when {
            inHeader >= TAB_BAR_MIN_ITEMS -> 0.85f
            inFooter >= TAB_BAR_MIN_ITEMS -> 0.85f
            tabElements.size >= 3 -> 0.6f
            else -> 0.4f
        }

        return LayoutPattern(
            type = LayoutPatternType.TAB_BAR,
            confidence = confidence,
            detectedElements = tabElements
        )
    }

    /**
     * 检测列表视图模式。
     *
     * 判定条件：CONTENT 区域含 [LIST_VIEW_MIN_ITEMS] 个以上 LIST_ITEM 元素。
     */
    private fun detectListView(
        zones: List<ScreenZone>,
        elements: List<ScreenElement>
    ): LayoutPattern? {
        val contentZone = zones.firstOrNull { it.type == ZoneType.CONTENT }
        val targetElements = contentZone?.elements ?: elements

        val listItems = targetElements.filter { it.type == ElementType.LIST_ITEM }
        if (listItems.size < LIST_VIEW_MIN_ITEMS) return null

        // 列表项越多，置信度越高
        val confidence = (0.5f + listItems.size * 0.05f).coerceAtMost(0.9f)

        return LayoutPattern(
            type = LayoutPatternType.LIST_VIEW,
            confidence = confidence,
            detectedElements = listItems
        )
    }

    /**
     * 检测网格视图模式。
     *
     * 判定条件：CONTENT 区域含 [GRID_MIN_ITEMS] 个以上 IMAGE 元素，
     * 且这些元素呈多行多列排列。
     */
    private fun detectGrid(
        zones: List<ScreenZone>,
        elements: List<ScreenElement>
    ): LayoutPattern? {
        val contentZone = zones.firstOrNull { it.type == ZoneType.CONTENT }
        val targetElements = contentZone?.elements ?: elements

        val images = targetElements.filter { it.type == ElementType.IMAGE }
        if (images.size < GRID_MIN_ITEMS) return null

        // 检查是否呈多列排列：统计不同的 X 中心坐标
        val xCenters = images.map { it.bounds.centerX }.distinct()
        val isMultiColumn = xCenters.size >= 2

        val confidence = if (isMultiColumn) {
            (0.6f + images.size * 0.03f).coerceAtMost(0.9f)
        } else {
            0.4f
        }

        return LayoutPattern(
            type = LayoutPatternType.GRID,
            confidence = confidence,
            detectedElements = images
        )
    }

    /**
     * 检测分栏视图模式。
     *
     * 判定条件：CONTENT 区域的元素明显分为左右两组（X 中心分别靠近左半和右半）。
     */
    private fun detectSplitView(
        zones: List<ScreenZone>,
        elements: List<ScreenElement>
    ): LayoutPattern? {
        val contentZone = zones.firstOrNull { it.type == ZoneType.CONTENT }
        val targetElements = contentZone?.elements ?: elements
        if (targetElements.size < 4) return null

        val screenWidth = targetElements.maxOfOrNull { it.bounds.right } ?: return null
        if (screenWidth <= 0) return null
        val midX = screenWidth / 2

        // 按中心 X 坐标分为左右两组
        val leftGroup = targetElements.filter { it.bounds.centerX < midX }
        val rightGroup = targetElements.filter { it.bounds.centerX >= midX }

        // 两组各有至少 2 个元素才视为分栏
        if (leftGroup.size < 2 || rightGroup.size < 2) return null

        // 左侧元素的右边界应不超过中线太多，右侧元素的左边界应不小于中线太多
        val leftMaxRight = leftGroup.maxOf { it.bounds.right }
        val rightMinLeft = rightGroup.minOf { it.bounds.left }
        if (leftMaxRight > midX * 1.2f || rightMinLeft < midX * 0.8f) return null

        val confidence = 0.7f

        return LayoutPattern(
            type = LayoutPatternType.SPLIT_VIEW,
            confidence = confidence,
            detectedElements = leftGroup + rightGroup
        )
    }

    /**
     * 检测轮播视图模式。
     *
     * 判定条件：CONTENT 区域含多个尺寸相同的元素且水平排列，
     * 或文本中含轮播指示器关键词（如 1/5、2/5）。
     */
    private fun detectCarousel(
        zones: List<ScreenZone>,
        elements: List<ScreenElement>
    ): LayoutPattern? {
        val contentZone = zones.firstOrNull { it.type == ZoneType.CONTENT }
        val targetElements = contentZone?.elements ?: elements

        // 检测轮播指示器文本（如 1/5、2/3）
        val indicatorPattern = Regex("\\d+[/／]\\d+")
        val hasIndicator = targetElements.any { el ->
            indicatorPattern.containsMatchIn(el.text)
        }

        if (hasIndicator) {
            val indicators = targetElements.filter { el ->
                indicatorPattern.containsMatchIn(el.text)
            }
            return LayoutPattern(
                type = LayoutPatternType.CAROUSEL,
                confidence = 0.75f,
                detectedElements = indicators
            )
        }

        // 检测水平排列的相同尺寸元素
        val validElements = targetElements.filter { it.bounds.isValid }
        if (validElements.size < 3) return null

        // 按高度分组，找相同高度的元素组
        val heightGroups = validElements.groupBy { it.bounds.height / 10 * 10 }
        for ((_, group) in heightGroups) {
            if (group.size < 3) continue
            // 检查是否水平排列（Y 坐标相近）
            val sorted = group.sortedBy { it.bounds.centerX }
            val firstY = sorted.first().bounds.centerY
            val sameRow = sorted.all { abs(it.bounds.centerY - firstY) < it.bounds.height }
            if (sameRow) {
                return LayoutPattern(
                    type = LayoutPatternType.CAROUSEL,
                    confidence = 0.6f,
                    detectedElements = sorted
                )
            }
        }

        return null
    }

    // ============================================================
    // 私有工具：差异检测
    // ============================================================

    /**
     * 从屏幕文本中提取非空行列表。
     */
    private fun extractTextLines(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(50)
    }

    /**
     * 根据行序号估算所属区域类型。
     *
     * @param lineIndex  行序号（从 0 开始）
     * @param totalLines 总行数
     * @return 估算的区域类型
     */
    private fun estimateZoneFromPosition(lineIndex: Int, totalLines: Int): ZoneType {
        if (totalLines <= 0) return ZoneType.UNKNOWN
        val ratio = lineIndex.toFloat() / totalLines
        return when {
            ratio < HEADER_RATIO -> ZoneType.HEADER
            ratio >= (1f - FOOTER_RATIO) -> ZoneType.FOOTER
            else -> ZoneType.CONTENT
        }
    }

    // ============================================================
    // 私有工具：目标评分
    // ============================================================

    /**
     * 从意图文本中提取关键词。
     *
     * 去除常见停用动词后，按空格和标点分词，保留有意义的关键词。
     *
     * @param intent 意图文本
     * @return 关键词列表
     */
    private fun extractIntentKeywords(intent: String): List<String> {
        // 按空格和常见标点分词
        val rawWords = intent.split(Regex("[\\s,，。.!！?？、:：;；]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val keywords = mutableListOf<String>()

        for (word in rawWords) {
            // 跳过停用词
            if (INTENT_STOP_WORDS.contains(word)) continue
            // 跳过单字符（除非是有效中文词）
            if (word.length == 1 && word.first().code < 0x4E00) continue
            keywords.add(word)
        }

        // 如果分词后关键词为空（如纯中文无空格），尝试按停用词切分
        if (keywords.isEmpty()) {
            var remaining = intent.trim()
            for (stopWord in INTENT_STOP_WORDS.sortedByDescending { it.length }) {
                remaining = remaining.replace(stopWord, " ")
            }
            val splitWords = remaining.split(Regex("[\\s,，。.!！?？、:：;；]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length >= 1 }
            keywords.addAll(splitWords)
        }

        return keywords.distinct()
    }

    /**
     * 计算元素文本与意图关键词的匹配得分。
     *
     * @param text     元素文本
     * @param keywords 意图关键词列表
     * @return 匹配得分（0.0-1.0）
     */
    private fun computeKeywordMatchScore(text: String, keywords: List<String>): Float {
        if (text.isBlank() || keywords.isEmpty()) return 0f

        val textLower = text.lowercase()
        var matchCount = 0
        var partialCount = 0

        for (keyword in keywords) {
            val kwLower = keyword.lowercase()
            if (kwLower.isBlank()) continue
            if (textLower.contains(kwLower)) {
                matchCount++
            } else if (kwLower.length >= 2) {
                // 部分匹配：关键词的子串出现在文本中
                val sub = kwLower.take(kwLower.length / 2 + 1)
                if (sub.length >= 2 && textLower.contains(sub)) {
                    partialCount++
                }
            }
        }

        if (matchCount == 0 && partialCount == 0) return 0f

        val totalKeywords = keywords.size.coerceAtLeast(1)
        val score = (matchCount.toFloat() / totalKeywords) +
                (partialCount.toFloat() / totalKeywords * 0.5f)

        return score.coerceIn(0f, 1f)
    }

    /**
     * 计算元素位置的居中度得分。
     *
     * 越靠近屏幕中心，得分越高。
     *
     * @param bounds       元素边界
     * @param screenWidth  屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 居中度得分（0.0-1.0）
     */
    private fun computePositionScore(
        bounds: Bounds,
        screenWidth: Int,
        screenHeight: Int
    ): Float {
        if (screenWidth <= 0 || screenHeight <= 0) return 0.5f

        val centerX = bounds.centerX.toFloat() / screenWidth
        val centerY = bounds.centerY.toFloat() / screenHeight

        // 与中心点(0.5, 0.5)的距离（归一化到 0-1）
        val dx = (centerX - 0.5f) * 2f  // -1 到 1
        val dy = (centerY - 0.5f) * 2f  // -1 到 1
        val distance = sqrt(dx * dx + dy * dy).coerceIn(0f, 1.4f)

        // 距离越近得分越高（1 - distance/1.4）
        return (1f - distance / 1.4f).coerceIn(0f, 1f)
    }

    /**
     * 构建目标推荐依据的可读说明。
     */
    private fun buildSuggestionReason(
        keywordScore: Float,
        clickableScore: Float,
        sizeScore: Float,
        positionScore: Float,
        keywords: List<String>
    ): String {
        val parts = ArrayList<String>()
        if (keywordScore > 0.1f) {
            parts.add("文本匹配(${keywords.joinToString("/")})")
        }
        if (clickableScore > 0f) {
            parts.add("可点击")
        }
        if (sizeScore > 0.3f) {
            parts.add("面积较大")
        }
        if (positionScore > 0.6f) {
            parts.add("位置居中")
        }
        val detail = if (parts.isEmpty()) "综合推断" else parts.joinToString(" + ")
        return "$detail → 目标推荐"
    }

    // ============================================================
    // 私有工具：通用
    // ============================================================

    /**
     * 判断文本是否包含动作动词。
     */
    private fun containsActionVerb(text: String): Boolean {
        return ACTION_VERBS.any { text.contains(it) }
    }

    /**
     * 解析边界字符串为 [Bounds] 对象。
     *
     * 支持格式："(left,top,right,bottom)" 或 "left,top,right,bottom"。
     *
     * @param boundsStr 边界字符串
     * @return 解析后的边界矩形，解析失败返回空 Bounds
     */
    private fun parseBounds(boundsStr: String?): Bounds {
        if (boundsStr.isNullOrBlank()) return Bounds()
        return try {
            val parts = boundsStr.trim()
                .removeSurrounding("(", ")")
                .split(",")
                .map { it.trim().toIntOrNull() }
            if (parts.size >= 4 && parts.all { it != null }) {
                Bounds(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
            } else {
                Bounds()
            }
        } catch (e: Exception) {
            Bounds()
        }
    }

    /**
     * 从 UI 元素列表推断屏幕尺寸（外接矩形）。
     *
     * @param uiElements UI 元素列表
     * @return 屏幕外接矩形
     */
    private fun inferScreenBounds(uiElements: List<ScreenStateCache.UiElementInfo>): Bounds {
        var maxRight = 0
        var maxBottom = 0
        for (info in uiElements) {
            val bounds = parseBounds(info.bounds)
            if (bounds.right > maxRight) maxRight = bounds.right
            if (bounds.bottom > maxBottom) maxBottom = bounds.bottom
        }
        return Bounds(0, 0, maxRight, maxBottom)
    }
}
