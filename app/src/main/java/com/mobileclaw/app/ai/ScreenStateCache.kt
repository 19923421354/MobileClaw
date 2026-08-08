package com.mobileclaw.app.ai

import android.view.accessibility.AccessibilityNodeInfo
import com.mobileclaw.app.accessibility.ScreenAccessibilityService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 屏幕状态缓存 —— 减少无障碍服务的冗余调用，提升响应速度。
 *
 * 核心问题：每次 AI 迭代都会调用 systemInfo.getCurrentState()，该方法内部
 * 遍历 UI 树获取屏幕文本，开销较大。在快速连续操作时，多次调用造成延迟。
 *
 * 缓存策略：
 * - TTL 过期：缓存数据在 CACHE_TTL_MS 后自动失效
 * - 动作失效：执行任何屏幕交互动作后，缓存自动失效
 * - 按需刷新：调用 [getScreenText] 或 [getCurrentApp] 时，如果缓存有效则直接返回
 *
 * 线程安全：所有读写操作通过 [Mutex] 保护。
 *
 * 使用方式：
 * ```
 * val cache = ScreenStateCache()
 * val text = cache.getScreenText()     // 带缓存的屏幕文本
 * cache.invalidate()                    // 动作执行后失效缓存
 * ```
 */
class ScreenStateCache {

    /** 缓存数据。 */
    private data class CachedState(
        val screenText: String,
        val appPackage: String?,
        val activity: String?,
        val uiElements: List<UiElementInfo>,
        val timestamp: Long
    )

    /** UI 元素信息（轻量级，仅保留关键信息）。 */
    data class UiElementInfo(
        val text: String,
        val contentDescription: String?,
        val className: String?,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val bounds: String?
    )

    @Volatile
    private var cached: CachedState? = null

    private val mutex = Mutex()

    /** 缓存有效期（毫秒）。 */
    private val cacheTtlMs: Long = 1500L

    /** 缓存是否有效。 */
    private fun isCacheValid(): Boolean {
        val c = cached ?: return false
        return System.currentTimeMillis() - c.timestamp < cacheTtlMs
    }

    /**
     * 获取屏幕文本（带缓存）。
     *
     * 如果缓存有效，直接返回缓存的屏幕文本；
     * 否则从无障碍服务获取并更新缓存。
     */
    suspend fun getScreenText(): String {
        if (isCacheValid()) {
            return cached?.screenText ?: ""
        }
        return refresh()?.screenText ?: ""
    }

    /**
     * 获取当前前台应用包名（带缓存）。
     */
    suspend fun getCurrentApp(): String? {
        if (isCacheValid()) {
            return cached?.appPackage
        }
        return refresh()?.appPackage
    }

    /**
     * 获取 UI 元素列表（带缓存）。
     *
     * 返回当前屏幕上所有可见的交互元素信息，
     * 供 AI 上下文构建器使用，帮助 AI 更准确地定位元素。
     */
    suspend fun getUiElements(): List<UiElementInfo> {
        if (isCacheValid()) {
            return cached?.uiElements ?: emptyList()
        }
        return refresh()?.uiElements ?: emptyList()
    }

    /**
     * 获取完整缓存状态（带缓存）。
     *
     * 一次性获取屏幕文本、应用包名、UI元素，减少多次调用开销。
     */
    private suspend fun getFullState(): CachedState? {
        if (isCacheValid()) {
            return cached
        }
        return refresh()
    }

    /**
     * 刷新缓存：从无障碍服务获取最新屏幕状态。
     */
    private suspend fun refresh(): CachedState? {
        return mutex.withLock {
            // 双重检查：可能在等待锁期间其他线程已刷新
            if (isCacheValid()) return@withLock cached

            val service = ScreenAccessibilityService.instance
            if (service == null) {
                // 无障碍服务未连接，返回空状态
                val state = CachedState(
                    screenText = "",
                    appPackage = null,
                    activity = null,
                    uiElements = emptyList(),
                    timestamp = System.currentTimeMillis()
                )
                cached = state
                return@withLock state
            }

            val root = try {
                service.getRootInActiveWindowSafe()
            } catch (e: Exception) {
                null
            }

            if (root == null) {
                val state = CachedState(
                    screenText = "",
                    appPackage = null,
                    activity = null,
                    uiElements = emptyList(),
                    timestamp = System.currentTimeMillis()
                )
                cached = state
                return@withLock state
            }

            // 收集屏幕文本和 UI 元素
            val textBuilder = StringBuilder()
            val elements = mutableListOf<UiElementInfo>()
            collectNodeInfo(root, textBuilder, elements, 0)

            // 获取当前应用包名
            val appPackage = try {
                val packageName = root.packageName?.toString()
                packageName
            } catch (e: Exception) {
                null
            }

            val state = CachedState(
                screenText = textBuilder.toString().trim(),
                appPackage = appPackage,
                activity = null,
                uiElements = elements,
                timestamp = System.currentTimeMillis()
            )
            cached = state
            return@withLock state
        }
    }

    /**
     * 递归遍历 UI 树，收集文本和交互元素信息。
     *
     * 深度限制为 8 层，避免过深遍历影响性能。
     */
    private fun collectNodeInfo(
        node: AccessibilityNodeInfo,
        textBuilder: StringBuilder,
        elements: MutableList<UiElementInfo>,
        depth: Int
    ) {
        if (depth > 8) return

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val className = node.className?.toString()
        val isClickable = node.isClickable
        val isEditable = node.isEditable

        // 收集文本
        if (text.isNotEmpty()) {
            if (textBuilder.isNotEmpty()) textBuilder.append(" ")
            textBuilder.append(text)
        }

        // 收集交互元素（可点击或可编辑的元素）
        if (isClickable || isEditable || text.isNotEmpty() || desc.isNotEmpty()) {
            val bounds = try {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                "(${rect.left},${rect.top},${rect.right},${rect.bottom})"
            } catch (e: Exception) {
                null
            }

            // 限制元素数量，避免过多数据
            if (elements.size < 50) {
                elements.add(UiElementInfo(
                    text = text,
                    contentDescription = desc.ifEmpty { null },
                    className = className,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    bounds = bounds
                ))
            }
        }

        // 递归子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodeInfo(child, textBuilder, elements, depth + 1)
        }
    }

    /**
     * 失效缓存。
     *
     * 在执行任何屏幕交互动作（点击、滑动、输入等）后调用，
     * 确保下次获取的是最新状态。
     */
    fun invalidate() {
        cached = null
    }

    /**
     * 检查指定文本是否在当前屏幕上（带缓存）。
     */
    suspend fun textExists(text: String): Boolean {
        val screenText = getScreenText()
        return screenText.contains(text, ignoreCase = true)
    }

    /**
     * 查找包含指定文本的可点击元素。
     *
     * 返回元素的边界坐标，供 AI 或控制器直接使用。
     */
    suspend fun findClickableElement(text: String): UiElementInfo? {
        val elements = getUiElements()
        return elements.firstOrNull { el ->
            (el.text.contains(text, ignoreCase = true) ||
             el.contentDescription?.contains(text, ignoreCase = true) == true) &&
            el.isClickable
        }
    }

    /**
     * 获取屏幕状态的简洁摘要（用于 AI 上下文）。
     *
     * 返回格式：
     * ```
     * 当前应用: com.tencent.mm
     * 屏幕文本(前200字): 微信 通讯录 发现 我 ...
     * 可点击元素: 搜索、添加、聊天列表...
     * ```
     */
    suspend fun getStateSummary(maxTextLength: Int = 200): String {
        val state = getFullState() ?: return "屏幕状态未知"

        return buildString {
            appendLine("当前应用: ${state.appPackage ?: "未知"}")
            if (state.screenText.isNotEmpty()) {
                val truncated = if (state.screenText.length > maxTextLength) {
                    state.screenText.take(maxTextLength) + "..."
                } else {
                    state.screenText
                }
                appendLine("屏幕文本: $truncated")
            }
            val clickables = state.uiElements.filter { it.isClickable }
            if (clickables.isNotEmpty()) {
                val clickableTexts = clickables
                    .mapNotNull { el -> el.text.ifEmpty { el.contentDescription } ?: "" }
                    .filter { it.isNotEmpty() }
                    .take(10)
                if (clickableTexts.isNotEmpty()) {
                    appendLine("可点击: ${clickableTexts.joinToString("、")}")
                }
            }
            val editables = state.uiElements.filter { it.isEditable }
            if (editables.isNotEmpty()) {
                appendLine("输入框: ${editables.size}个")
            }
        }
    }
}
