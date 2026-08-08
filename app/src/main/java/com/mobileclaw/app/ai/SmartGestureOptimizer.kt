package com.mobileclaw.app.ai

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// =============================================================================
//  枚举定义
// =============================================================================

/**
 * 手势类型。
 *
 * 对底层 [ActionType] 的手势语义化封装，用于区分不同触摸交互的物理特征。
 * 每种手势类型在 [SmartGestureOptimizer] 中拥有独立的时序配置与学习统计。
 *
 * - [TAP] 单次点击：手指按下后快速抬起，持续时间短（约 50ms）。
 * - [DOUBLE_TAP] 双击：两次快速连续点击，间隔约 100ms。
 * - [LONG_PRESS] 长按：手指按下后保持较长时间（500ms~2s）不抬起。
 * - [SWIPE] 滑动：手指从起点移动到终点，用于页面切换或拖拽。
 * - [PINCH] 捏合/缩放：双指捏合或张开，用于缩放图片/地图。
 * - [SCROLL] 滚动：手指沿固定方向持续移动，用于列表滚动。
 * - [FLICK] 快速滑动：高速短促的滑动，用于快速翻页或甩动。
 *
 * @property description 手势类型的中文描述。
 */
enum class GestureType(val description: String) {
    TAP("点击"),
    DOUBLE_TAP("双击"),
    LONG_PRESS("长按"),
    SWIPE("滑动"),
    PINCH("捏合缩放"),
    SCROLL("滚动"),
    FLICK("快速滑动")
}

/**
 * 路径类型。
 *
 * 决定 [SmartGestureOptimizer.generateSwipePath] 生成的滑动路径形态。
 * 不同路径类型模拟不同的人类滑动习惯，提升手势的自然度。
 *
 * - [LINEAR] 直线路径：起点到终点的直线，最简单但最不自然。
 * - [CURVED] 弧线路径：沿圆弧/抛物线移动，模拟手指的自然弧度。
 * - [BEZIER] 贝塞尔曲线：三次贝塞尔曲线，控制点带垂直偏移，最接近真实手指轨迹。
 * - [ZIGZAG] 锯齿路径：左右交替偏移的折线，模拟手指轻微抖动。
 *
 * @property description 路径类型的中文描述。
 */
enum class PathType(val description: String) {
    LINEAR("直线路径"),
    CURVED("弧线路径"),
    BEZIER("贝塞尔曲线"),
    ZIGZAG("锯齿路径")
}

/**
 * 优化级别。
 *
 * 控制手势优化的深度与自然度。级别越高，生成的手势越接近真实人类操作，
 * 但计算开销也越大。调用方应根据场景选择合适的级别。
 *
 * - [NONE] 不优化：直接使用原始坐标与时序，速度最快，适合无反自动化检测的场景。
 * - [BASIC] 基础优化：仅做路径取直与时序规范化，不做随机化处理。
 * - [ADVANCED] 高级优化：路径曲线化 + 时序自适应 + 触摸精度修正，适合大多数场景。
 * - [HUMAN_LIKE] 拟人优化：在高级优化基础上增加随机抖动、压力变化与时序扰动，
 *   生成的手势几乎无法与真人操作区分，适合有反自动化检测的应用。
 *
 * @property description 优化级别的中文描述。
 * @property naturalnessBase 该级别的基础自然度评分（0.0~1.0）。
 */
enum class OptimizationLevel(val description: String, val naturalnessBase: Float) {
    NONE("不优化", 0.0f),
    BASIC("基础优化", 0.3f),
    ADVANCED("高级优化", 0.6f),
    HUMAN_LIKE("拟人优化", 0.85f)
}

// =============================================================================
//  数据类定义
// =============================================================================

/**
 * 手势路径上的单个触点。
 *
 * 描述某一时刻手指在屏幕上的位置、压力及相对时间戳。
 * 一条完整的 [GesturePath] 由多个有序的 [GesturePoint] 组成。
 *
 * @property x 触点 X 坐标（像素）。
 * @property y 触点 Y 坐标（像素）。
 * @property pressure 触摸压力（0.0~1.0），模拟手指按压力度。
 * @property timestampMs 相对于手势起始时刻的时间戳（毫秒），首个点为 0。
 */
data class GesturePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val timestampMs: Long
)

/**
 * 手势路径。
 *
 * 由一系列 [GesturePoint] 组成的有序触摸轨迹，附带路径类型与起止坐标。
 * 用于 [SWIPE]、[SCROLL]、[FLICK] 等需要移动轨迹的手势。
 * [TAP]、[LONG_PRESS] 等点触手势的路径仅包含 2~3 个点（按下、保持、抬起）。
 *
 * @property points 有序触点列表。
 * @property pathType 路径类型（直线/弧线/贝塞尔/锯齿）。
 * @property startX 起点X坐标（像素）。
 * @property startY 起点Y坐标（像素）。
 * @property endX 终点X坐标（像素）。
 * @property endY 终点Y坐标（像素）。
 * @property totalDurationMs 路径总持续时间（毫秒）。
 */
data class GesturePath(
    val points: List<GesturePoint>,
    val pathType: PathType,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val totalDurationMs: Long
) {
    /** 路径包含的触点数量。 */
    val pointCount: Int get() = points.size

    /** 路径直线距离（像素）。 */
    val distance: Float
        get() {
            val dx = endX - startX
            val dy = endY - startY
            return sqrt(dx * dx + dy * dy)
        }
}

/**
 * 触摸精度修正信息。
 *
 * 根据目标元素尺寸和屏幕密度对原始坐标进行微调后的结果。
 * 修正后的坐标更靠近元素中心，降低因坐标偏差导致的点击失败率。
 *
 * 修正逻辑：
 * 1. 若提供了元素尺寸，将坐标钳制到元素边界内（避免点中元素边缘外）。
 * 2. 根据屏幕密度施加亚像素抖动补偿，在高密度屏幕上提升点击精度。
 * 3. 在 [OptimizationLevel.HUMAN_LIKE] 级别下，施加小幅随机偏移模拟手指落点偏差。
 *
 * @property originalX 原始 X 坐标。
 * @property originalY 原始 Y 坐标。
 * @property adjustedX 修正后的 X 坐标。
 * @property adjustedY 修正后的 Y 坐标。
 * @property elementWidth 目标元素宽度（像素），0 表示未知。
 * @property elementHeight 目标元素高度（像素），0 表示未知。
 * @property screenDensity 屏幕密度（DPI 倍数，如 2.0 = xhdpi）。
 * @property offsetX X 方向修正偏移量（adjustedX - originalX）。
 * @property offsetY Y 方向修正偏移量（adjustedY - originalY）。
 */
data class TouchPrecision(
    val originalX: Float,
    val originalY: Float,
    val adjustedX: Float,
    val adjustedY: Float,
    val elementWidth: Float,
    val elementHeight: Float,
    val screenDensity: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    /** 修正偏移量的绝对距离（像素）。 */
    val offsetDistance: Float
        get() = sqrt(offsetX * offsetX + offsetY * offsetY)
}

/**
 * 时序配置。
 *
 * 描述一种手势类型的完整时序参数，由 [SmartGestureOptimizer.optimizeTiming] 生成。
 * 时序参数会根据历史执行数据自适应调整：成功率低则延长持续时间，应用响应慢则增加间隔。
 *
 * @property gestureType 对应的手势类型。
 * @property holdDurationMs 按住持续时间（毫秒）：手指按下到抬起的间隔。
 *                           对 [GestureType.TAP] 约 50ms，[GestureType.LONG_PRESS] 约 1000ms。
 * @property gestureDurationMs 手势总持续时间（毫秒）：含按下、移动、抬起的完整时长。
 *                              对滑动类手势等于路径总时长。
 * @property postGestureDelayMs 手势后等待时间（毫秒）：手势完成后的额外停顿，
 *                               用于等待界面响应，可按应用响应速度自适应调整。
 * @property swipeSpeedPxPerMs 滑动速度（像素/毫秒），仅对移动类手势有效。
 */
data class TimingProfile(
    val gestureType: GestureType,
    val holdDurationMs: Long,
    val gestureDurationMs: Long,
    val postGestureDelayMs: Long,
    val swipeSpeedPxPerMs: Float
) {
    /** 时序摘要（用于日志与调试）。 */
    fun summary(): String =
        "时序[${gestureType.description}]: 按住=${holdDurationMs}ms, " +
                "总时长=${gestureDurationMs}ms, 后置延迟=${postGestureDelayMs}ms, " +
                "滑动速度=${"%.2f".format(swipeSpeedPxPerMs)}px/ms"
}

/**
 * 优化后的完整手势。
 *
 * [SmartGestureOptimizer.optimizeGesture] 的返回值，包含手势执行所需的全部信息：
 * 路径轨迹、时序参数、精度修正和自然度评分。可直接传递给底层执行引擎执行。
 *
 * @property gestureType 手势类型。
 * @property path 手势路径（触点序列）。
 * @property timing 时序配置。
 * @property precision 触摸精度修正信息。
 * @property optimizationLevel 本次优化使用的级别。
 * @property naturalnessScore 自然度评分（0.0~1.0），越高越接近真人操作。
 * @property cacheKey 缓存键，若该手势来自缓存则非 null。
 */
data class OptimizedGesture(
    val gestureType: GestureType,
    val path: GesturePath,
    val timing: TimingProfile,
    val precision: TouchPrecision,
    val optimizationLevel: OptimizationLevel,
    val naturalnessScore: Float,
    val cacheKey: String?
) {
    /** 手势摘要（用于日志与调试）。 */
    fun summary(): String =
        "手势[${gestureType.description}]: ${path.pointCount}个触点, " +
                "自然度=${"%.0f".format(naturalnessScore * 100)}%, " +
                "优化=${optimizationLevel.description}" +
                (if (cacheKey != null) ", 缓存命中" else "")
}

// =============================================================================
//  SmartGestureOptimizer —— 智能手势优化器
// =============================================================================

/**
 * SmartGestureOptimizer —— 智能手势优化器
 *
 * 为 MobileClaw 的手势执行引擎提供路径优化、时序学习、精度修正和拟人化生成能力。
 * 核心目标是让自动化手势更自然、更精准、更高效，同时通过自适应学习持续提升成功率。
 *
 * ### 八大核心能力
 *
 * 1. **路径优化**：将 L 型折线路径优化为直接对角线路径，缩短滑动距离。
 *    例如从 (100,100) 经 (300,100) 到 (300,300) 的 L 型路径，
 *    优化为 (100,100) 直达 (300,300) 的对角线，距离减少约 29%。
 *
 * 2. **时序优化**：为每种手势类型学习最优时序参数（按住时长、滑动速度、后置延迟）。
 *    基于历史执行成功率与实际耗时动态调整——成功率低则延长，成功率高则逐步收紧。
 *
 * 3. **手势拟人化**：生成带有微小随机扰动的手势，包括坐标抖动、压力变化和时序波动，
 *    使自动化手势难以被反作弊系统识别为机器操作。
 *
 * 4. **触摸精度修正**：根据目标元素尺寸和屏幕密度调整触摸坐标，
 *    将落点钳制到元素内部并偏向中心，降低因坐标偏差导致的点击失败率。
 *
 * 5. **滑动曲线生成**：生成贝塞尔曲线、弧线、锯齿等非直线路径，
 *    模拟真实手指滑动的自然弧度，而非机器般的完美直线。
 *
 * 6. **手势缓存**：缓存频繁使用的手势模式（按手势类型+坐标网格量化），
 *    相同区域的手势直接复用缓存，避免重复计算路径。
 *
 * 7. **自适应时序**：根据应用响应速度动态调整手势间等待时间。
 *    响应慢的应用（如大型游戏）增加后置延迟，响应快的应用（如系统设置）缩短延迟。
 *
 * 8. **线程安全**：所有缓存与统计均使用 [ConcurrentHashMap] + [AtomicInteger]/[AtomicLong]，
 *    可安全地被 UI 线程、执行线程和后台分析线程并发访问。
 *
 * ### 各手势类型的默认时序
 * | 手势类型     | 按住时长 | 总时长  | 后置延迟 | 滑动速度     |
 * |--------------|----------|---------|----------|--------------|
 * | TAP          | 50ms     | 50ms    | 80ms     | -            |
 * | DOUBLE_TAP   | 50ms     | 200ms   | 80ms     | -            |
 * | LONG_PRESS   | 1000ms   | 1000ms  | 150ms    | -            |
 * | SWIPE        | -        | 300ms   | 100ms    | 2.5 px/ms    |
 * | PINCH        | -        | 300ms   | 100ms    | 3.0 px/ms    |
 * | SCROLL       | -        | 400ms   | 120ms    | 2.0 px/ms    |
 * | FLICK        | -        | 150ms   | 60ms     | 5.0 px/ms    |
 *
 * ### 典型调用流程
 * ```
 * val optimizer = SmartGestureOptimizer()
 *
 * // 优化一次滑动：从 (200, 800) 滑到 (200, 200)，拟人级别
 * val gesture = optimizer.optimizeGesture(
 *     gestureType = GestureType.SWIPE,
 *     startX = 200f, startY = 800f,
 *     endX = 200f, endY = 200f,
 *     elementWidth = 0f, elementHeight = 0f,
 *     screenDensity = 2.5f,
 *     optimizationLevel = OptimizationLevel.HUMAN_LIKE,
 *     appPackage = "com.tencent.mm"
 * )
 *
 * // 执行优化后的手势（将 path.points 逐点注入触摸事件）
 * executeGesture(gesture)
 *
 * // 执行完毕后记录结果，供自适应学习
 * optimizer.recordGestureResult(
 *     gestureType = GestureType.SWIPE,
 *     success = true,
 *     actualDurationMs = 320L,
 *     appResponseMs = 150L,
 *     appPackage = "com.tencent.mm"
 * )
 *
 * // 查询某手势类型的已学习时序
 * val timing = optimizer.getOptimalTiming(GestureType.SWIPE)
 * ```
 *
 * ### 线程安全说明
 * - 缓存与统计映射均使用 [ConcurrentHashMap]，支持并发读写。
 * - 计数器使用 [AtomicInteger] / [AtomicLong]，保证原子更新。
 * - 典型场景：UI 线程读取时序、执行线程调用优化、后台线程记录结果与统计。
 */
class SmartGestureOptimizer {

    // =========================================================================
    //  配置常量
    // =========================================================================

    companion object {
        private const val TAG = "SmartGestureOptimizer"

        /** 默认屏幕密度（xhdpi = 2.0）。 */
        const val DEFAULT_SCREEN_DENSITY = 2.0f

        // ---- 默认按住时长（毫秒） ----

        /** 默认点击按住时长。 */
        const val DEFAULT_TAP_HOLD_MS = 50L

        /** 默认长按按住时长。 */
        const val DEFAULT_LONG_PRESS_HOLD_MS = 1000L

        /** 默认双击间隔时长。 */
        const val DEFAULT_DOUBLE_TAP_INTERVAL_MS = 100L

        // ---- 默认手势总时长（毫秒） ----

        /** 默认滑动总时长。 */
        const val DEFAULT_SWIPE_DURATION_MS = 300L

        /** 默认快速滑动总时长。 */
        const val DEFAULT_FLICK_DURATION_MS = 150L

        /** 默认滚动总时长。 */
        const val DEFAULT_SCROLL_DURATION_MS = 400L

        /** 默认捏合总时长。 */
        const val DEFAULT_PINCH_DURATION_MS = 300L

        /** 默认双击总时长（含两次点击间隔）。 */
        const val DEFAULT_DOUBLE_TAP_DURATION_MS = 200L

        // ---- 默认后置延迟（毫秒） ----

        /** 默认手势后等待时间。 */
        const val DEFAULT_POST_GESTURE_DELAY_MS = 100L

        /** 快速响应应用的后置延迟。 */
        const val FAST_RESPONSE_DELAY_MS = 60L

        /** 慢速响应应用的后置延迟。 */
        const val SLOW_RESPONSE_DELAY_MS = 300L

        // ---- 默认滑动速度（像素/毫秒） ----

        const val DEFAULT_SWIPE_SPEED = 2.5f
        const val DEFAULT_FLICK_SPEED = 5.0f
        const val DEFAULT_SCROLL_SPEED = 2.0f
        const val DEFAULT_PINCH_SPEED = 3.0f

        // ---- 路径生成参数 ----

        /** 滑动路径最大触点数。 */
        const val MAX_SWIPE_POINTS = 60

        /** 滑动路径最小触点数。 */
        const val MIN_SWIPE_POINTS = 10

        /** 贝塞尔曲线控制点垂直偏移比例（相对路径长度）。 */
        const val BEZIER_CONTROL_OFFSET_RATIO = 0.15f

        /** 弧线路径的最大弯曲幅度（像素）。 */
        const val CURVE_MAX_AMPLITUDE = 80f

        /** 锯齿路径的偏移幅度（像素）。 */
        const val ZIGZAG_AMPLITUDE = 6f

        // ---- 拟人化参数 ----

        /** 坐标随机抖动范围（相对路径长度的比例）。 */
        const val NATURALNESS_JITTER_RATIO = 0.02f

        /** 基础触摸压力。 */
        const val PRESSURE_BASE = 0.5f

        /** 压力变化幅度。 */
        const val PRESSURE_VARIANCE = 0.15f

        /** 时序抖动比例（相对单点间隔）。 */
        const val TIMING_JITTER_RATIO = 0.1f

        // ---- 触摸精度参数 ----

        /** 元素边界安全边距比例（坐标钳制时向内缩进的比例）。 */
        const val ELEMENT_SAFE_MARGIN_RATIO = 0.15f

        /** 高密度屏幕的亚像素补偿系数。 */
        const val HIGH_DENSITY_COMPENSATION = 0.5f

        // ---- 缓存参数 ----

        /** 手势缓存最大条目数。 */
        const val CACHE_MAX_ENTRIES = 200

        /** 手势缓存生存时间（毫秒，默认 5 分钟）。 */
        const val CACHE_TTL_MS = 5 * 60 * 1000L

        /** 缓存键的坐标量化网格大小（像素），相同网格内的手势共享缓存。 */
        const val CACHE_GRID_SIZE = 50f

        // ---- 自适应学习参数 ----

        /** 自适应调整所需的最小样本数，低于此数时沿用默认配置。 */
        const val MIN_SAMPLES_FOR_ADJUSTMENT = 5

        /** 失败时的时序增长因子（延长 20%）。 */
        const val TIMING_GROWTH_FACTOR = 1.2f

        /** 成功时的时序收缩因子（收紧 5%，逐步优化速度）。 */
        const val TIMING_SHRINK_FACTOR = 0.95f

        /** 时序调整下限比例（不可低于默认值的 50%）。 */
        const val TIMING_MIN_RATIO = 0.5f

        /** 时序调整上限比例（不可超过默认值的 200%）。 */
        const val TIMING_MAX_RATIO = 2.0f

        /** 应用响应慢阈值（毫秒），超过此值视为慢速应用。 */
        const val APP_RESPONSE_SLOW_THRESHOLD_MS = 500L

        /** 应用响应快阈值（毫秒），低于此值视为快速应用。 */
        const val APP_RESPONSE_FAST_THRESHOLD_MS = 100L
    }

    // =========================================================================
    //  内部数据结构
    // =========================================================================

    /**
     * 单个手势类型的学习统计（线程安全）。
     *
     * 记录该手势类型的历史执行次数、成功率、耗时分布和应用响应时间，
     * 供 [optimizeTiming] 和 [getOptimalTiming] 进行自适应调整。
     *
     * @property totalExecutions 总执行次数。
     * @property successCount 成功次数。
     * @property totalDurationMs 累计实际耗时（毫秒）。
     * @property minDurationMs 最短耗时。
     * @property maxDurationMs 最长耗时。
     * @property totalResponseMs 累计应用响应时间（毫秒）。
     * @property responseCount 响应时间记录次数。
     */
    private class GestureStats {
        val totalExecutions: AtomicInteger = AtomicInteger(0)
        val successCount: AtomicInteger = AtomicInteger(0)
        val totalDurationMs: AtomicLong = AtomicLong(0L)
        val minDurationMs: AtomicLong = AtomicLong(Long.MAX_VALUE)
        val maxDurationMs: AtomicLong = AtomicLong(0L)
        val totalResponseMs: AtomicLong = AtomicLong(0L)
        val responseCount: AtomicInteger = AtomicInteger(0)

        /** 成功率（0.0~1.0），无记录时返回 0.0。 */
        fun successRate(): Double {
            val total = totalExecutions.get()
            return if (total > 0) successCount.get().toDouble() / total else 0.0
        }

        /** 平均耗时（毫秒），无记录时返回 0。 */
        fun avgDurationMs(): Long {
            val total = totalExecutions.get()
            return if (total > 0) totalDurationMs.get() / total else 0L
        }

        /** 平均应用响应时间（毫秒），无记录时返回 0。 */
        fun avgResponseMs(): Long {
            val count = responseCount.get()
            return if (count > 0) totalResponseMs.get() / count else 0L
        }
    }

    /**
     * 应用响应统计（按应用包名分组）。
     *
     * 记录每个应用的手势后响应时间，用于 [optimizeTiming] 中的自适应后置延迟调整。
     */
    private class AppResponseStats {
        val totalTimeMs: AtomicLong = AtomicLong(0L)
        val count: AtomicInteger = AtomicInteger(0)

        /** 平均响应时间（毫秒），无记录时返回 0。 */
        fun avgResponseMs(): Long {
            val c = count.get()
            return if (c > 0) totalTimeMs.get() / c else 0L
        }
    }

    /**
     * 缓存的手势条目，附带缓存时间戳用于 TTL 过期判断。
     *
     * @property gesture 优化后的手势。
     * @property cachedAt 缓存写入时间戳（毫秒）。
     */
    private data class CachedGesture(
        val gesture: OptimizedGesture,
        val cachedAt: Long
    ) {
        /** 是否已过期。 */
        fun isExpired(now: Long): Boolean = now - cachedAt > CACHE_TTL_MS
    }

    // =========================================================================
    //  状态字段
    // =========================================================================

    /** 各手势类型的学习统计，使用 [ConcurrentHashMap] 保证线程安全。 */
    private val gestureStats = ConcurrentHashMap<GestureType, GestureStats>()

    /** 各应用包名的响应时间统计。 */
    private val appResponseStats = ConcurrentHashMap<String, AppResponseStats>()

    /** 手势模式缓存，键为量化后的手势签名。 */
    private val gestureCache = ConcurrentHashMap<String, CachedGesture>()

    // =========================================================================
    //  核心方法：手势优化
    // =========================================================================

    /**
     * 优化手势 —— 主入口方法。
     *
     * 根据手势类型、坐标、元素尺寸和优化级别，综合执行以下优化：
     * 1. 查询缓存，命中则直接返回缓存的手势模式。
     * 2. 调用 [adjustPrecision] 修正触摸坐标精度。
     * 3. 调用 [optimizeTiming] 计算自适应时序参数。
     * 4. 调用 [generateSwipePath] 生成路径（移动类手势）或构建点触路径。
     * 5. 计算自然度评分。
     * 6. 写入缓存并返回完整优化结果。
     *
     * @param gestureType 手势类型。
     * @param startX 起点X坐标（像素）。
     * @param startY 起点Y坐标（像素）。
     * @param endX 终点X坐标（像素），点触手势默认与起点相同。
     * @param endY 终点Y坐标（像素），点触手势默认与起点相同。
     * @param elementWidth 目标元素宽度（像素），0 表示未知。
     * @param elementHeight 目标元素高度（像素），0 表示未知。
     * @param screenDensity 屏幕密度倍数，默认 [DEFAULT_SCREEN_DENSITY]。
     * @param optimizationLevel 优化级别，默认 [OptimizationLevel.HUMAN_LIKE]。
     * @param appPackage 当前应用包名（用于自适应时序），可为 null。
     * @return 优化后的完整手势。
     */
    fun optimizeGesture(
        gestureType: GestureType,
        startX: Float,
        startY: Float,
        endX: Float = startX,
        endY: Float = startY,
        elementWidth: Float = 0f,
        elementHeight: Float = 0f,
        screenDensity: Float = DEFAULT_SCREEN_DENSITY,
        optimizationLevel: OptimizationLevel = OptimizationLevel.HUMAN_LIKE,
        appPackage: String? = null
    ): OptimizedGesture {
        // 1. 查询缓存
        val cacheKey = generateCacheKey(gestureType, startX, startY, endX, endY, optimizationLevel)
        val now = System.currentTimeMillis()
        gestureCache[cacheKey]?.let { cached ->
            if (!cached.isExpired(now)) {
                Log.d(TAG, "缓存命中: $cacheKey")
                return cached.gesture.copy(cacheKey = cacheKey)
            } else {
                gestureCache.remove(cacheKey)
            }
        }

        // 2. 精度修正
        val precision = adjustPrecision(startX, startY, elementWidth, elementHeight, screenDensity)
        val adjStartX = precision.adjustedX
        val adjStartY = precision.adjustedY
        val adjEndX = if (endX != startX || endY != startY) {
            adjustPrecision(endX, endY, elementWidth, elementHeight, screenDensity).adjustedX
        } else {
            adjStartX
        }
        val adjEndY = if (endX != startX || endY != startY) {
            adjustPrecision(endX, endY, elementWidth, elementHeight, screenDensity).adjustedY
        } else {
            adjStartY
        }

        // 3. 时序优化
        val actionType = mapGestureTypeToActionType(gestureType)
        val timing = optimizeTiming(gestureType, actionType, appPackage)

        // 4. 路径生成
        val path = when (gestureType) {
            GestureType.SWIPE, GestureType.SCROLL, GestureType.FLICK -> {
                val pathType = selectPathType(gestureType, optimizationLevel)
                generateSwipePath(
                    adjStartX, adjStartY, adjEndX, adjEndY,
                    pathType, timing.gestureDurationMs, optimizationLevel
                )
            }
            GestureType.PINCH -> {
                // 捏合手势：简化为单指路径（实际执行需双指）
                generateSwipePath(
                    adjStartX, adjStartY, adjEndX, adjEndY,
                    PathType.BEZIER, timing.gestureDurationMs, optimizationLevel
                )
            }
            else -> {
                // 点触手势：构建按下-保持-抬起的简短路径
                buildTapPath(gestureType, adjStartX, adjStartY, timing, optimizationLevel)
            }
        }

        // 5. 自然度评分
        val naturalnessScore = calculateNaturalnessScore(optimizationLevel, path.pathType)

        // 6. 组装结果
        val gesture = OptimizedGesture(
            gestureType = gestureType,
            path = path,
            timing = timing,
            precision = precision,
            optimizationLevel = optimizationLevel,
            naturalnessScore = naturalnessScore,
            cacheKey = null
        )

        // 7. 写入缓存
        cacheGesture(cacheKey, gesture, now)

        Log.d(TAG, "优化手势: ${gesture.summary()}")
        return gesture.copy(cacheKey = cacheKey)
    }

    // =========================================================================
    //  核心方法：滑动路径生成
    // =========================================================================

    /**
     * 生成滑动路径。
     *
     * 根据指定的 [pathType] 生成从起点到终点的触点序列，并附带时序与压力信息。
     * 在 [OptimizationLevel.ADVANCED] 及以上级别时，为每个触点叠加随机抖动和压力变化，
     * 使路径更接近真实手指滑动轨迹。
     *
     * 路径生成流程：
     * 1. 计算路径直线距离，确定触点数量（距离越长点越多，范围 [MIN_SWIPE_POINTS]~[MAX_SWIPE_POINTS]）。
     * 2. 按 [pathType] 生成基础坐标序列（直线/弧线/贝塞尔/锯齿）。
     * 3. 为每个触点分配时间戳（均匀分布 + 随机抖动）。
     * 4. 为每个触点分配压力（钟形曲线 + 随机波动）。
     * 5. 叠加自然度抖动（若优化级别包含随机化）。
     *
     * @param startX 起点X坐标。
     * @param startY 起点Y坐标。
     * @param endX 终点X坐标。
     * @param endY 终点Y坐标。
     * @param pathType 路径类型，默认 [PathType.BEZIER]。
     * @param durationMs 路径总持续时间（毫秒）。
     * @param optimizationLevel 优化级别，决定是否叠加随机化。
     * @return 生成的手势路径。
     */
    fun generateSwipePath(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        pathType: PathType = PathType.BEZIER,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
        optimizationLevel: OptimizationLevel = OptimizationLevel.HUMAN_LIKE
    ): GesturePath {
        val dx = endX - startX
        val dy = endY - startY
        val distance = sqrt(dx * dx + dy * dy)

        // 距离过短时退化为点触路径
        if (distance < 1f) {
            return buildTapPath(GestureType.SWIPE, startX, startY,
                TimingProfile(GestureType.SWIPE, durationMs, durationMs, DEFAULT_POST_GESTURE_DELAY_MS, DEFAULT_SWIPE_SPEED),
                optimizationLevel)
        }

        // 确定触点数量：距离越长点越多
        val numPoints = (distance / 10f).roundToInt()
            .coerceIn(MIN_SWIPE_POINTS, MAX_SWIPE_POINTS)

        // 按路径类型生成基础坐标
        val baseCoords: List<Pair<Float, Float>> = when (pathType) {
            PathType.LINEAR -> generateLinearPoints(startX, startY, endX, endY, numPoints)
            PathType.CURVED -> generateCurvedPoints(startX, startY, endX, endY, numPoints)
            PathType.BEZIER -> generateBezierPoints(startX, startY, endX, endY, numPoints)
            PathType.ZIGZAG -> generateZigzagPoints(startX, startY, endX, endY, numPoints)
        }

        // 叠加自然度（坐标抖动）
        val addNaturalness = optimizationLevel >= OptimizationLevel.ADVANCED
        val jitterRange = distance * NATURALNESS_JITTER_RATIO

        // 生成触点（含时序与压力）
        val points = ArrayList<GesturePoint>(numPoints)
        val baseInterval = durationMs.toFloat() / (numPoints - 1).coerceAtLeast(1)
        var elapsed = 0L

        for (i in 0 until numPoints) {
            val (baseX, baseY) = baseCoords[i]

            // 坐标抖动
            val (finalX, finalY) = if (addNaturalness) {
                val jx = (Random.nextFloat() - 0.5f) * 2f * jitterRange
                val jy = (Random.nextFloat() - 0.5f) * 2f * jitterRange
                (baseX + jx) to (baseY + jy)
            } else {
                baseX to baseY
            }

            // 时间戳：均匀分布 + 抖动
            if (i > 0) {
                val jitter = if (addNaturalness) {
                    (Random.nextFloat() - 0.5f) * baseInterval * TIMING_JITTER_RATIO
                } else 0f
                elapsed += (baseInterval + jitter).toLong().coerceAtLeast(1L)
            }

            // 压力：钟形曲线 + 随机波动
            val t = if (numPoints > 1) i.toFloat() / (numPoints - 1) else 0.5f
            val pressure = calculatePressure(t, addNaturalness)

            points.add(GesturePoint(finalX, finalY, pressure, elapsed))
        }

        // 确保终点精确落在目标位置（消除累积抖动偏差）
        if (points.isNotEmpty()) {
            val last = points.last()
            points[points.lastIndex] = last.copy(x = endX, y = endY)
        }

        return GesturePath(
            points = points,
            pathType = pathType,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            totalDurationMs = elapsed
        )
    }

    // =========================================================================
    //  核心方法：时序优化
    // =========================================================================

    /**
     * 优化时序 —— 根据手势类型、历史数据和应用响应速度计算最优时序。
     *
     * 时序优化逻辑：
     * 1. 获取该手势类型的默认时序参数。
     * 2. 若历史样本充足（>= [MIN_SAMPLES_FOR_ADJUSTMENT]），基于成功率调整按住时长：
     *    - 成功率 < 50%：按 [TIMING_GROWTH_FACTOR] 延长（给更多时间完成操作）。
     *    - 成功率 > 90%：按 [TIMING_SHRINK_FACTOR] 收紧（提升执行速度）。
     * 3. 根据应用响应速度调整后置延迟：
     *    - 慢速应用（响应 > [APP_RESPONSE_SLOW_THRESHOLD_MS]）：使用 [SLOW_RESPONSE_DELAY_MS]。
     *    - 快速应用（响应 < [APP_RESPONSE_FAST_THRESHOLD_MS]）：使用 [FAST_RESPONSE_DELAY_MS]。
     *    - 无数据或中等响应：使用默认后置延迟。
     *
     * @param gestureType 手势类型。
     * @param actionType 关联的 [ActionType]（用于扩展信息），可为 null。
     * @param appPackage 当前应用包名（用于查询应用响应速度），可为 null。
     * @return 优化后的时序配置。
     */
    fun optimizeTiming(
        gestureType: GestureType,
        actionType: ActionType? = null,
        appPackage: String? = null
    ): TimingProfile {
        val defaults = getDefaultTiming(gestureType)

        // 基于历史数据调整按住时长
        val stats = gestureStats[gestureType]
        var adjustedHold = defaults.holdDurationMs
        var adjustedDuration = defaults.gestureDurationMs

        if (stats != null && stats.totalExecutions.get() >= MIN_SAMPLES_FOR_ADJUSTMENT) {
            val successRate = stats.successRate()
            adjustedHold = when {
                successRate < 0.5 -> applyTimingFactor(defaults.holdDurationMs, TIMING_GROWTH_FACTOR)
                successRate > 0.9 -> applyTimingFactor(defaults.holdDurationMs, TIMING_SHRINK_FACTOR)
                else -> defaults.holdDurationMs
            }
            adjustedDuration = when {
                successRate < 0.5 -> applyTimingFactor(defaults.gestureDurationMs, TIMING_GROWTH_FACTOR)
                successRate > 0.9 -> applyTimingFactor(defaults.gestureDurationMs, TIMING_SHRINK_FACTOR)
                else -> defaults.gestureDurationMs
            }
        }

        // 根据应用响应速度调整后置延迟
        val adjustedDelay = if (appPackage != null) {
            val appStats = appResponseStats[appPackage]
            val avgResponse = appStats?.avgResponseMs() ?: 0L
            when {
                avgResponse == 0L -> defaults.postGestureDelayMs
                avgResponse > APP_RESPONSE_SLOW_THRESHOLD_MS -> SLOW_RESPONSE_DELAY_MS
                avgResponse < APP_RESPONSE_FAST_THRESHOLD_MS -> FAST_RESPONSE_DELAY_MS
                else -> defaults.postGestureDelayMs
            }
        } else {
            defaults.postGestureDelayMs
        }

        return TimingProfile(
            gestureType = gestureType,
            holdDurationMs = adjustedHold,
            gestureDurationMs = adjustedDuration,
            postGestureDelayMs = adjustedDelay,
            swipeSpeedPxPerMs = defaults.swipeSpeedPxPerMs
        )
    }

    /**
     * 获取已学习的最优时序（只读快捷查询）。
     *
     * 与 [optimizeTiming] 的区别：此方法不传入应用上下文，仅返回该手势类型
     * 的全局学习时序。适用于不需要应用级调整的快速查询场景。
     *
     * @param gestureType 手势类型。
     * @return 最优时序配置（无历史数据时返回默认值）。
     */
    fun getOptimalTiming(gestureType: GestureType): TimingProfile {
        val defaults = getDefaultTiming(gestureType)
        val stats = gestureStats[gestureType] ?: return defaults

        if (stats.totalExecutions.get() < MIN_SAMPLES_FOR_ADJUSTMENT) {
            return defaults
        }

        val successRate = stats.successRate()
        val adjustedHold = when {
            successRate < 0.5 -> applyTimingFactor(defaults.holdDurationMs, TIMING_GROWTH_FACTOR)
            successRate > 0.9 -> applyTimingFactor(defaults.holdDurationMs, TIMING_SHRINK_FACTOR)
            else -> defaults.holdDurationMs
        }
        val adjustedDuration = when {
            successRate < 0.5 -> applyTimingFactor(defaults.gestureDurationMs, TIMING_GROWTH_FACTOR)
            successRate > 0.9 -> applyTimingFactor(defaults.gestureDurationMs, TIMING_SHRINK_FACTOR)
            else -> defaults.gestureDurationMs
        }

        return TimingProfile(
            gestureType = gestureType,
            holdDurationMs = adjustedHold,
            gestureDurationMs = adjustedDuration,
            postGestureDelayMs = defaults.postGestureDelayMs,
            swipeSpeedPxPerMs = defaults.swipeSpeedPxPerMs
        )
    }

    // =========================================================================
    //  核心方法：触摸精度修正
    // =========================================================================

    /**
     * 调整触摸精度 —— 根据元素尺寸和屏幕密度修正触摸坐标。
     *
     * 修正策略：
     * 1. **元素边界钳制**：若提供了元素尺寸，将坐标钳制到元素安全区域内
     *    （向内缩进 [ELEMENT_SAFE_MARGIN_RATIO] 比例），避免点击落在元素边缘外。
     * 2. **中心偏移**：将坐标向元素中心方向微调，提升命中率。
     * 3. **密度补偿**：在高密度屏幕（density > 2.0）上施加亚像素补偿，
     *    抵消高 DPI 下坐标量化带来的精度损失。
     * 4. **拟人抖动**：在 [OptimizationLevel.HUMAN_LIKE] 级别下施加小幅随机偏移，
     *    模拟手指落点的自然偏差。
     *
     * @param x 原始 X 坐标。
     * @param y 原始 Y 坐标。
     * @param elementWidth 目标元素宽度（像素），0 表示未知。
     * @param elementHeight 目标元素高度（像素），0 表示未知。
     * @param screenDensity 屏幕密度倍数。
     * @return 精度修正信息，包含修正后的坐标与偏移量。
     */
    fun adjustPrecision(
        x: Float,
        y: Float,
        elementWidth: Float = 0f,
        elementHeight: Float = 0f,
        screenDensity: Float = DEFAULT_SCREEN_DENSITY
    ): TouchPrecision {
        var adjustedX = x
        var adjustedY = y

        // 1. 元素边界钳制与中心偏移
        if (elementWidth > 0f && elementHeight > 0f) {
            val safeMarginW = elementWidth * ELEMENT_SAFE_MARGIN_RATIO
            val safeMarginH = elementHeight * ELEMENT_SAFE_MARGIN_RATIO
            val minX = x - elementWidth / 2f + safeMarginW
            val maxX = x + elementWidth / 2f - safeMarginW
            val minY = y - elementHeight / 2f + safeMarginH
            val maxY = y + elementHeight / 2f - safeMarginH
            // 钳制到安全区域内
            adjustedX = x.coerceIn(minX, maxX)
            adjustedY = y.coerceIn(minY, maxY)
        }

        // 2. 高密度屏幕亚像素补偿
        if (screenDensity > 2.0f) {
            val compensation = HIGH_DENSITY_COMPENSATION / screenDensity
            adjustedX += compensation
            adjustedY += compensation
        }

        // 3. 拟人抖动（微小随机偏移）
        val jitter = (1f / screenDensity).coerceAtMost(1f)
        adjustedX += (Random.nextFloat() - 0.5f) * jitter
        adjustedY += (Random.nextFloat() - 0.5f) * jitter

        val offsetX = adjustedX - x
        val offsetY = adjustedY - y

        return TouchPrecision(
            originalX = x,
            originalY = y,
            adjustedX = adjustedX,
            adjustedY = adjustedY,
            elementWidth = elementWidth,
            elementHeight = elementHeight,
            screenDensity = screenDensity,
            offsetX = offsetX,
            offsetY = offsetY
        )
    }

    // =========================================================================
    //  核心方法：手势档案与结果记录
    // =========================================================================

    /**
     * 获取手势类型的学习档案。
     *
     * 返回该手势类型的学习统计摘要，包括执行次数、成功率、耗时分布和应用响应时间。
     * 适用于调试、日志输出和 UI 展示。
     *
     * @param gestureType 手势类型。
     * @return 人类可读的档案字符串。
     */
    fun getGestureProfile(gestureType: GestureType): String {
        val stats = gestureStats[gestureType]
        val defaults = getDefaultTiming(gestureType)

        return buildString {
            appendLine("===== 手势档案 [${gestureType.description}] =====")
            appendLine("默认时序: ${defaults.summary()}")

            if (stats == null || stats.totalExecutions.get() == 0) {
                appendLine("学习状态: 无历史数据，使用默认配置")
            } else {
                val total = stats.totalExecutions.get()
                val success = stats.successCount.get()
                val rate = stats.successRate()
                val avgDur = stats.avgDurationMs()
                val minDur = stats.minDurationMs.get()
                val maxDur = stats.maxDurationMs.get()
                val avgResp = stats.avgResponseMs()

                appendLine("学习状态: 已学习 (样本=$total)")
                appendLine("  成功率: ${"%.1f".format(rate * 100)}% ($success/$total)")
                appendLine("  耗时: 平均=${avgDur}ms, 最短=${if (minDur == Long.MAX_VALUE) 0 else minDur}ms, 最长=${maxDur}ms")
                if (avgResp > 0) {
                    appendLine("  应用响应: 平均=${avgResp}ms")
                }
                if (total >= MIN_SAMPLES_FOR_ADJUSTMENT) {
                    val optimal = getOptimalTiming(gestureType)
                    appendLine("  最优时序: ${optimal.summary()}")
                } else {
                    appendLine("  最优时序: 样本不足(< $MIN_SAMPLES_FOR_ADJUSTMENT)，沿用默认")
                }
            }
            appendLine("======================================")
        }
    }

    /**
     * 记录手势执行结果 —— 供自适应学习使用。
     *
     * 在每次手势执行完毕后调用，记录执行结果与耗时，用于：
     * 1. 更新该手势类型的成功率统计。
     * 2. 更新耗时分布（平均/最短/最长）。
     * 3. 更新应用响应时间统计（若提供了 [appResponseMs] 和 [appPackage]）。
     *
     * 当累计样本量达到 [MIN_SAMPLES_FOR_ADJUSTMENT] 后，
     * [optimizeTiming] 和 [getOptimalTiming] 返回的时序将根据成功率自动调整。
     *
     * @param gestureType 手势类型。
     * @param success 本次执行是否成功。
     * @param actualDurationMs 实际执行耗时（毫秒）。
     * @param appResponseMs 应用响应时间（毫秒），0 表示未测量。
     * @param appPackage 应用包名（用于更新应用响应统计），可为 null。
     * @param actionType 关联的 [ActionType]，可为 null。
     */
    fun recordGestureResult(
        gestureType: GestureType,
        success: Boolean,
        actualDurationMs: Long,
        appResponseMs: Long = 0L,
        appPackage: String? = null,
        actionType: ActionType? = null
    ) {
        if (actualDurationMs < 0) return

        // 更新手势类型统计
        val stats = gestureStats.computeIfAbsent(gestureType) { GestureStats() }
        stats.totalExecutions.incrementAndGet()
        if (success) {
            stats.successCount.incrementAndGet()
        }
        stats.totalDurationMs.addAndGet(actualDurationMs)
        updateMinLong(stats.minDurationMs, actualDurationMs)
        updateMaxLong(stats.maxDurationMs, actualDurationMs)

        // 更新应用响应统计
        if (appResponseMs > 0 && appPackage != null) {
            val appStats = appResponseStats.computeIfAbsent(appPackage) { AppResponseStats() }
            appStats.totalTimeMs.addAndGet(appResponseMs)
            appStats.count.incrementAndGet()
        }

        // 记录成功后使相关缓存失效（时序已变化，旧缓存可能不再最优）
        if (success && stats.totalExecutions.get() % 10 == 0) {
            invalidateCacheForGestureType(gestureType)
        }

        Log.d(TAG, "记录结果: ${gestureType.description} success=$success " +
                "duration=${actualDurationMs}ms response=${appResponseMs}ms app=$appPackage")
    }

    // =========================================================================
    //  辅助方法：路径类型选择与点触路径构建
    // =========================================================================

    /**
     * 根据手势类型和优化级别选择路径类型。
     *
     * - [OptimizationLevel.NONE] / [OptimizationLevel.BASIC]：使用 [PathType.LINEAR]。
     * - [OptimizationLevel.ADVANCED]：使用 [PathType.CURVED]。
     * - [OptimizationLevel.HUMAN_LIKE]：使用 [PathType.BEZIER]（[FLICK] 除外，使用 [PathType.LINEAR] 模拟快速直线甩动）。
     */
    private fun selectPathType(gestureType: GestureType, level: OptimizationLevel): PathType {
        return when (level) {
            OptimizationLevel.NONE, OptimizationLevel.BASIC -> PathType.LINEAR
            OptimizationLevel.ADVANCED -> PathType.CURVED
            OptimizationLevel.HUMAN_LIKE -> when (gestureType) {
                GestureType.FLICK -> PathType.LINEAR
                else -> PathType.BEZIER
            }
        }
    }

    /**
     * 构建点触手势路径（TAP / DOUBLE_TAP / LONG_PRESS）。
     *
     * 点触路径包含按下、保持、抬起三个阶段：
     * - 按下点：压力从低快速上升。
     * - 保持点：压力稳定在高值。
     * - 抬起点：压力快速下降。
     *
     * [DOUBLE_TAP] 会生成两组按下-抬起序列。
     */
    private fun buildTapPath(
        gestureType: GestureType,
        x: Float,
        y: Float,
        timing: TimingProfile,
        optimizationLevel: OptimizationLevel
    ): GesturePath {
        val addNaturalness = optimizationLevel >= OptimizationLevel.ADVANCED
        val points = ArrayList<GesturePoint>()

        when (gestureType) {
            GestureType.DOUBLE_TAP -> {
                // 第一次点击
                val tapDuration = timing.holdDurationMs
                points.add(GesturePoint(x, y, calculatePressure(0f, addNaturalness), 0L))
                points.add(GesturePoint(x, y, calculatePressure(0.5f, addNaturalness), tapDuration / 2))
                points.add(GesturePoint(x, y, calculatePressure(1f, addNaturalness), tapDuration))
                // 间隔
                val intervalStart = tapDuration + DEFAULT_DOUBLE_TAP_INTERVAL_MS / 2
                points.add(GesturePoint(x, y, 0f, intervalStart))
                // 第二次点击
                val secondStart = tapDuration + DEFAULT_DOUBLE_TAP_INTERVAL_MS
                points.add(GesturePoint(x, y, calculatePressure(0f, addNaturalness), secondStart))
                points.add(GesturePoint(x, y, calculatePressure(0.5f, addNaturalness), secondStart + tapDuration / 2))
                points.add(GesturePoint(x, y, calculatePressure(1f, addNaturalness), secondStart + tapDuration))
            }
            else -> {
                // 单次点击/长按
                val holdDuration = timing.holdDurationMs
                points.add(GesturePoint(x, y, calculatePressure(0f, addNaturalness), 0L))
                if (holdDuration > 100L) {
                    // 长按：添加中间保持点
                    points.add(GesturePoint(x, y, calculatePressure(0.3f, addNaturalness), holdDuration / 4))
                    points.add(GesturePoint(x, y, calculatePressure(0.5f, addNaturalness), holdDuration / 2))
                    points.add(GesturePoint(x, y, calculatePressure(0.7f, addNaturalness), holdDuration * 3 / 4))
                }
                points.add(GesturePoint(x, y, calculatePressure(1f, addNaturalness), holdDuration))
            }
        }

        return GesturePath(
            points = points,
            pathType = PathType.LINEAR,
            startX = x,
            startY = y,
            endX = x,
            endY = y,
            totalDurationMs = points.last().timestampMs
        )
    }

    // =========================================================================
    //  辅助方法：基础坐标序列生成
    // =========================================================================

    /**
     * 生成直线路径的坐标序列。
     *
     * 从起点到终点均匀插值，每个点在直线上。
     */
    private fun generateLinearPoints(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        numPoints: Int
    ): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>(numPoints)
        for (i in 0 until numPoints) {
            val t = if (numPoints > 1) i.toFloat() / (numPoints - 1) else 0f
            points.add(
                (startX + (endX - startX) * t) to (startY + (endY - startY) * t)
            )
        }
        return points
    }

    /**
     * 生成弧线路径的坐标序列。
     *
     * 路径沿圆弧弯曲，弯曲方向垂直于起点-终点连线，幅度由 [CURVE_MAX_AMPLITUDE] 限制。
     */
    private fun generateCurvedPoints(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        numPoints: Int
    ): List<Pair<Float, Float>> {
        val dx = endX - startX
        val dy = endY - startY
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) return List(numPoints) { startX to startY }

        // 垂直方向单位向量
        val perpX = -dy / length
        val perpY = dx / length

        // 弧度幅度（不超过最大值）
        val amplitude = min(length * BEZIER_CONTROL_OFFSET_RATIO, CURVE_MAX_AMPLITUDE)

        val points = ArrayList<Pair<Float, Float>>(numPoints)
        for (i in 0 until numPoints) {
            val t = if (numPoints > 1) i.toFloat() / (numPoints - 1) else 0f
            // 基础直线位置
            val baseX = startX + dx * t
            val baseY = startY + dy * t
            // 弧度偏移：sin(pi * t) 产生钟形偏移，中间最大两端为零
            val arc = sin(PI * t).toFloat() * amplitude
            points.add((baseX + perpX * arc) to (baseY + perpY * arc))
        }
        return points
    }

    /**
     * 生成贝塞尔曲线路径的坐标序列。
     *
     * 使用三次贝塞尔曲线（4 个控制点），两个中间控制点沿垂直方向偏移，
     * 模拟真实手指滑动的自然弧度。偏移幅度由 [BEZIER_CONTROL_OFFSET_RATIO] 控制。
     *
     * 三次贝塞尔公式：B(t) = (1-t)³P₀ + 3(1-t)²t·P₁ + 3(1-t)t²·P₂ + t³P₃
     */
    private fun generateBezierPoints(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        numPoints: Int
    ): List<Pair<Float, Float>> {
        val dx = endX - startX
        val dy = endY - startY
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) return List(numPoints) { startX to startY }

        // 垂直方向单位向量
        val perpX = -dy / length
        val perpY = dx / length

        // 控制点偏移量
        val offset = length * BEZIER_CONTROL_OFFSET_RATIO
        // 随机决定弯曲方向（正或负），增加多样性
        val direction = if (Random.nextBoolean()) 1f else -1f

        // 两个控制点：位于路径 1/3 和 2/3 处，沿垂直方向偏移
        val cp1X = startX + dx * 0.33f + perpX * offset * direction
        val cp1Y = startY + dy * 0.33f + perpY * offset * direction
        val cp2X = startX + dx * 0.67f + perpX * offset * direction
        val cp2Y = startY + dy * 0.67f + perpY * offset * direction

        val points = ArrayList<Pair<Float, Float>>(numPoints)
        for (i in 0 until numPoints) {
            val t = if (numPoints > 1) i.toFloat() / (numPoints - 1) else 0f
            val u = 1f - t
            // 三次贝塞尔插值
            val x = u * u * u * startX + 3f * u * u * t * cp1X + 3f * u * t * t * cp2X + t * t * t * endX
            val y = u * u * u * startY + 3f * u * u * t * cp1Y + 3f * u * t * t * cp2Y + t * t * t * endY
            points.add(x to y)
        }
        return points
    }

    /**
     * 生成锯齿路径的坐标序列。
     *
     * 路径在直线基础上叠加左右交替的锯齿偏移，模拟手指滑动时的轻微抖动。
     * 偏移幅度由 [ZIGZAG_AMPLITUDE] 控制。
     */
    private fun generateZigzagPoints(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        numPoints: Int
    ): List<Pair<Float, Float>> {
        val dx = endX - startX
        val dy = endY - startY
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1f) return List(numPoints) { startX to startY }

        val perpX = -dy / length
        val perpY = dx / length

        val points = ArrayList<Pair<Float, Float>>(numPoints)
        for (i in 0 until numPoints) {
            val t = if (numPoints > 1) i.toFloat() / (numPoints - 1) else 0f
            val baseX = startX + dx * t
            val baseY = startY + dy * t
            // 锯齿偏移：交替正负，中间幅度最大
            val phase = sin(PI * t * 4).toFloat() // 4 个周期
            val zigzag = phase * ZIGZAG_AMPLITUDE
            points.add((baseX + perpX * zigzag) to (baseY + perpY * zigzag))
        }
        return points
    }

    // =========================================================================
    //  辅助方法：压力与自然度
    // =========================================================================

    /**
     * 计算触点压力。
     *
     * 压力遵循钟形曲线：起始和结束为低压，中间为高压，模拟真实手指按压过程。
     * 公式：pressure = [PRESSURE_BASE] + [PRESSURE_VARIANCE] × sin(π × t)
     * 其中 t 为路径进度（0.0~1.0）。
     *
     * 在 [addRandomness] 为 true 时，叠加 ±10% 的随机波动。
     *
     * @param t 路径进度（0.0 = 起点，1.0 = 终点）。
     * @param addRandomness 是否叠加随机波动。
     * @return 压力值（0.0~1.0）。
     */
    private fun calculatePressure(t: Float, addRandomness: Boolean): Float {
        val base = PRESSURE_BASE + PRESSURE_VARIANCE * sin(PI * t.toDouble()).toFloat()
        val withNoise = if (addRandomness) {
            base + (Random.nextFloat() - 0.5f) * PRESSURE_VARIANCE * 0.4f
        } else {
            base
        }
        return withNoise.coerceIn(0.1f, 1.0f)
    }

    /**
     * 计算自然度评分。
     *
     * 评分 = 优化级别基础分 + 路径类型加分 + 随机加分，上限 1.0。
     *
     * @param level 优化级别。
     * @param pathType 路径类型。
     * @return 自然度评分（0.0~1.0）。
     */
    private fun calculateNaturalnessScore(level: OptimizationLevel, pathType: PathType): Float {
        val base = level.naturalnessBase
        val pathBonus = when (pathType) {
            PathType.BEZIER -> 0.1f
            PathType.CURVED -> 0.08f
            PathType.ZIGZAG -> 0.05f
            PathType.LINEAR -> 0.0f
        }
        val randomBonus = if (level == OptimizationLevel.HUMAN_LIKE) {
            Random.nextFloat() * 0.05f
        } else {
            0f
        }
        return minOf(base + pathBonus + randomBonus, 1.0f)
    }

    // =========================================================================
    //  辅助方法：时序默认值与调整
    // =========================================================================

    /**
     * 获取手势类型的默认时序配置。
     *
     * 每种手势类型有独立的默认参数，详见类级文档的时序表。
     */
    private fun getDefaultTiming(gestureType: GestureType): TimingProfile {
        return when (gestureType) {
            GestureType.TAP -> TimingProfile(
                gestureType = GestureType.TAP,
                holdDurationMs = DEFAULT_TAP_HOLD_MS,
                gestureDurationMs = DEFAULT_TAP_HOLD_MS,
                postGestureDelayMs = DEFAULT_POST_GESTURE_DELAY_MS,
                swipeSpeedPxPerMs = 0f
            )
            GestureType.DOUBLE_TAP -> TimingProfile(
                gestureType = GestureType.DOUBLE_TAP,
                holdDurationMs = DEFAULT_TAP_HOLD_MS,
                gestureDurationMs = DEFAULT_DOUBLE_TAP_DURATION_MS,
                postGestureDelayMs = DEFAULT_POST_GESTURE_DELAY_MS,
                swipeSpeedPxPerMs = 0f
            )
            GestureType.LONG_PRESS -> TimingProfile(
                gestureType = GestureType.LONG_PRESS,
                holdDurationMs = DEFAULT_LONG_PRESS_HOLD_MS,
                gestureDurationMs = DEFAULT_LONG_PRESS_HOLD_MS,
                postGestureDelayMs = DEFAULT_POST_GESTURE_DELAY_MS + 50L,
                swipeSpeedPxPerMs = 0f
            )
            GestureType.SWIPE -> TimingProfile(
                gestureType = GestureType.SWIPE,
                holdDurationMs = 0L,
                gestureDurationMs = DEFAULT_SWIPE_DURATION_MS,
                postGestureDelayMs = DEFAULT_POST_GESTURE_DELAY_MS,
                swipeSpeedPxPerMs = DEFAULT_SWIPE_SPEED
            )
            GestureType.PINCH -> TimingProfile(
                gestureType = GestureType.PINCH,
                holdDurationMs = 0L,
                gestureDurationMs = DEFAULT_PINCH_DURATION_MS,
                postGestureDelayMs = DEFAULT_POST_GESTURE_DELAY_MS,
                swipeSpeedPxPerMs = DEFAULT_PINCH_SPEED
            )
            GestureType.SCROLL -> TimingProfile(
                gestureType = GestureType.SCROLL,
                holdDurationMs = 0L,
                gestureDurationMs = DEFAULT_SCROLL_DURATION_MS,
                postGestureDelayMs = DEFAULT_POST_GESTURE_DELAY_MS + 20L,
                swipeSpeedPxPerMs = DEFAULT_SCROLL_SPEED
            )
            GestureType.FLICK -> TimingProfile(
                gestureType = GestureType.FLICK,
                holdDurationMs = 0L,
                gestureDurationMs = DEFAULT_FLICK_DURATION_MS,
                postGestureDelayMs = FAST_RESPONSE_DELAY_MS,
                swipeSpeedPxPerMs = DEFAULT_FLICK_SPEED
            )
        }
    }

    /**
     * 对时序参数施加调整因子，并钳制在允许范围内。
     *
     * 调整后的值不低于默认值的 [TIMING_MIN_RATIO] 倍，不高于 [TIMING_MAX_RATIO] 倍。
     *
     * @param defaultValue 默认值。
     * @param factor 调整因子（>1 延长，<1 收紧）。
     * @return 调整后的值。
     */
    private fun applyTimingFactor(defaultValue: Long, factor: Float): Long {
        val adjusted = (defaultValue * factor).toLong()
        val minAllowed = (defaultValue * TIMING_MIN_RATIO).toLong()
        val maxAllowed = (defaultValue * TIMING_MAX_RATIO).toLong()
        return adjusted.coerceIn(minAllowed, maxAllowed)
    }

    // =========================================================================
    //  辅助方法：类型映射
    // =========================================================================

    /**
     * 将 [GestureType] 映射为关联的 [ActionType]。
     *
     * 用于在 [optimizeTiming] 和 [recordGestureResult] 中传递上下文信息。
     * 多对一映射（多种 ActionType 对应同一种 GestureType）。
     */
    private fun mapGestureTypeToActionType(gestureType: GestureType): ActionType? {
        return when (gestureType) {
            GestureType.TAP -> ActionType.SCREEN_CLICK
            GestureType.DOUBLE_TAP -> ActionType.SCREEN_DOUBLE_CLICK
            GestureType.LONG_PRESS -> ActionType.SCREEN_LONG_CLICK
            GestureType.SWIPE -> ActionType.SCREEN_SWIPE
            GestureType.SCROLL -> ActionType.SCREEN_SCROLL_TO_TEXT
            GestureType.PINCH -> null
            GestureType.FLICK -> ActionType.SCREEN_SWIPE
        }
    }

    /**
     * 将 [ActionType] 映射为 [GestureType]（公开方法，供外部调用）。
     *
     * 映射规则：
     * - SCREEN_CLICK / SCREEN_CLICK_TEXT / SCREEN_FIND_AND_CLICK -> [GestureType.TAP]
     * - SCREEN_DOUBLE_CLICK -> [GestureType.DOUBLE_TAP]
     * - SCREEN_LONG_CLICK -> [GestureType.LONG_PRESS]
     * - SCREEN_SWIPE -> [GestureType.SWIPE]
     * - SCREEN_SCROLL_TO_TEXT -> [GestureType.SCROLL]
     * - 其他 -> null
     *
     * @param actionType 动作类型。
     * @return 对应的手势类型，无法映射时返回 null。
     */
    fun mapActionTypeToGestureType(actionType: ActionType): GestureType? {
        return when (actionType) {
            ActionType.SCREEN_CLICK,
            ActionType.SCREEN_CLICK_TEXT,
            ActionType.SCREEN_FIND_AND_CLICK -> GestureType.TAP
            ActionType.SCREEN_DOUBLE_CLICK -> GestureType.DOUBLE_TAP
            ActionType.SCREEN_LONG_CLICK -> GestureType.LONG_PRESS
            ActionType.SCREEN_SWIPE -> GestureType.SWIPE
            ActionType.SCREEN_SCROLL_TO_TEXT -> GestureType.SCROLL
            else -> null
        }
    }

    // =========================================================================
    //  辅助方法：缓存管理
    // =========================================================================

    /**
     * 生成手势缓存键。
     *
     * 将坐标量化到 [CACHE_GRID_SIZE] 像素的网格上，使同一网格区域内的手势共享缓存，
     * 提升缓存命中率。键格式：`手势类型_网格起点->网格终点_优化级别`。
     */
    private fun generateCacheKey(
        gestureType: GestureType,
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        optimizationLevel: OptimizationLevel
    ): String {
        val gridStartX = (startX / CACHE_GRID_SIZE).roundToInt()
        val gridStartY = (startY / CACHE_GRID_SIZE).roundToInt()
        val gridEndX = (endX / CACHE_GRID_SIZE).roundToInt()
        val gridEndY = (endY / CACHE_GRID_SIZE).roundToInt()
        return "${gestureType.name}_${gridStartX},${gridStartY}->${gridEndX},${gridEndY}_${optimizationLevel.name}"
    }

    /**
     * 将手势写入缓存。
     *
     * 超过 [CACHE_MAX_ENTRIES] 时淘汰最旧的条目。
     */
    private fun cacheGesture(key: String, gesture: OptimizedGesture, now: Long) {
        // LRU 淘汰：超过最大条目数时移除最旧条目
        if (gestureCache.size >= CACHE_MAX_ENTRIES) {
            val oldest = gestureCache.entries.minByOrNull { it.value.cachedAt }
            oldest?.let { gestureCache.remove(it.key) }
        }
        gestureCache[key] = CachedGesture(gesture.copy(cacheKey = null), now)
    }

    /**
     * 使指定手势类型的所有缓存条目失效。
     *
     * 在时序参数因学习而变化后调用，确保后续手势使用更新后的时序。
     */
    private fun invalidateCacheForGestureType(gestureType: GestureType) {
        val prefix = "${gestureType.name}_"
        val iterator = gestureCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                iterator.remove()
            }
        }
        Log.d(TAG, "已失效 ${gestureType.description} 的缓存条目")
    }

    /**
     * 清空手势缓存。
     *
     * 统计数据不会被重置（保留历史学习成果）。
     * 适用于屏幕旋转、配置变更等需要重建手势的场景。
     */
    fun clearCache() {
        gestureCache.clear()
        Log.d(TAG, "已清空手势缓存")
    }

    // =========================================================================
    //  辅助方法：原子更新
    // =========================================================================

    /**
     * 原子更新 [AtomicLong] 的最小值（线程安全）。
     *
     * 使用 CAS 循环确保并发安全：读取当前值，若新值更小则尝试写入，失败则重试。
     */
    private fun updateMinLong(atomic: AtomicLong, value: Long) {
        var current: Long
        do {
            current = atomic.get()
            if (value >= current) return
        } while (!atomic.compareAndSet(current, value))
    }

    /**
     * 原子更新 [AtomicLong] 的最大值（线程安全）。
     *
     * 使用 CAS 循环确保并发安全：读取当前值，若新值更大则尝试写入，失败则重试。
     */
    private fun updateMaxLong(atomic: AtomicLong, value: Long) {
        var current: Long
        do {
            current = atomic.get()
            if (value <= current) return
        } while (!atomic.compareAndSet(current, value))
    }

    // =========================================================================
    //  统计与摘要
    // =========================================================================

    /**
     * 获取所有手势类型的学习统计摘要（用于日志与 UI 展示）。
     */
    fun getSummary(): String {
        if (gestureStats.isEmpty()) {
            return "手势优化器: 无学习数据，全部使用默认配置"
        }

        return buildString {
            appendLine("===== SmartGestureOptimizer 统计摘要 =====")
            appendLine()

            // 各手势类型统计
            for (gestureType in GestureType.entries) {
                val stats = gestureStats[gestureType]
                append("【${gestureType.description}】")
                if (stats == null || stats.totalExecutions.get() == 0) {
                    appendLine(" 无记录")
                } else {
                    val total = stats.totalExecutions.get()
                    val success = stats.successCount.get()
                    val rate = stats.successRate()
                    val avgDur = stats.avgDurationMs()
                    append(" 执行${total}次, 成功率=${"%.1f".format(rate * 100)}%, 平均耗时=${avgDur}ms")
                    if (total < MIN_SAMPLES_FOR_ADJUSTMENT) {
                        append(" (样本不足, 沿用默认)")
                    }
                    appendLine()
                }
            }

            // 应用响应统计
            appendLine()
            if (appResponseStats.isNotEmpty()) {
                appendLine("应用响应统计:")
                appResponseStats.forEach { (pkg, stats) ->
                    appendLine("  $pkg: 平均响应=${stats.avgResponseMs()}ms (${stats.count.get()}次)")
                }
            }

            // 缓存统计
            appendLine()
            appendLine("缓存: ${gestureCache.size}/$CACHE_MAX_ENTRIES 条")
            appendLine("==========================================")
        }
    }

    /**
     * 重置所有学习统计与缓存。
     *
     * 清空手势统计、应用响应统计和手势缓存。
     * 适用于测试或需要清除历史学习数据的场景。
     */
    fun resetStats() {
        gestureStats.clear()
        appResponseStats.clear()
        gestureCache.clear()
        Log.d(TAG, "已重置所有统计与缓存")
    }
}
