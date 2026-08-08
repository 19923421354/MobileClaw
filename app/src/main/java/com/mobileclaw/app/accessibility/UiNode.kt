package com.mobileclaw.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * UI节点数据模型
 *
 * 用于从 [AccessibilityNodeInfo] 中提取并保存界面控件的关键信息，
 * 支持序列化为 JSON，便于跨模块传递、日志记录或上报给 AI 模型进行决策。
 *
 * 该模型与系统 [AccessibilityNodeInfo] 解耦：系统节点对象有生命周期限制且不可跨线程
 * 长期持有，而 [UiNode] 是一个纯数据快照，可安全地缓存、序列化与传输。
 *
 * @property text 控件显示的文本内容
 * @property contentDescription 控件的内容描述（用于无障碍朗读）
 * @property className 控件的类名（如 android.widget.TextView）
 * @property viewId 控件的资源ID（如 com.android.settings:id/title）
 * @property bounds 控件在屏幕中的边界矩形
 * @property isClickable 控件是否可点击
 * @property isScrollable 控件是否可滚动
 * @property isEnabled 控件是否可用
 * @property children 子节点列表（构成 UI 树）
 */
@Serializable
data class UiNode(
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val viewId: String = "",
    val bounds: Bounds = Bounds(),
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEnabled: Boolean = false,
    val children: List<UiNode> = emptyList()
) {

    /**
     * 控件边界矩形（屏幕坐标系，单位为像素）。
     *
     * @property left 左边界
     * @property top 上边界
     * @property right 右边界
     * @property bottom 下边界
     */
    @Serializable
    data class Bounds(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0
    ) {
        /** 中心点 X 坐标 */
        val centerX: Int
            get() = (left + right) / 2

        /** 中心点 Y 坐标 */
        val centerY: Int
            get() = (top + bottom) / 2

        /** 控件宽度 */
        val width: Int
            get() = right - left

        /** 控件高度 */
        val height: Int
            get() = bottom - top
    }

    /**
     * 将当前节点（含所有子节点）序列化为格式化的 JSON 字符串。
     *
     * @return JSON 格式的字符串
     */
    fun toJson(): String {
        // encodeToString(SerializationStrategy, T) 是 StringFormat 的成员方法，
        // UiNode.serializer() 由 @Serializable 插件自动生成。
        return Json { prettyPrint = true }.encodeToString(UiNode.serializer(), this)
    }

    companion object {

        /**
         * 从 [AccessibilityNodeInfo] 转换为 [UiNode]，递归处理所有子节点，
         * 生成整棵 UI 子树的数据快照。
         *
         * 转换过程中对单个子节点的获取异常进行吞并处理，避免因某个节点不可访问
         * 而导致整棵树的构建失败。
         *
         * @param nodeInfo 原始无障碍节点信息
         * @return 转换后的 UI 节点数据模型
         */
        fun fromAccessibilityNodeInfo(nodeInfo: AccessibilityNodeInfo): UiNode {
            // 获取控件在屏幕中的边界
            val rect = Rect()
            nodeInfo.getBoundsInScreen(rect)

            // 递归构建子节点列表
            val children = mutableListOf<UiNode>()
            for (i in 0 until nodeInfo.childCount) {
                try {
                    val child = nodeInfo.getChild(i)
                    if (child != null) {
                        children.add(fromAccessibilityNodeInfo(child))
                    }
                } catch (e: Exception) {
                    // 单个子节点获取失败时跳过，不影响整体遍历
                }
            }

            return UiNode(
                text = nodeInfo.text?.toString() ?: "",
                contentDescription = nodeInfo.contentDescription?.toString() ?: "",
                className = nodeInfo.className?.toString() ?: "",
                viewId = nodeInfo.viewIdResourceName ?: "",
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                isClickable = nodeInfo.isClickable,
                isScrollable = nodeInfo.isScrollable,
                isEnabled = nodeInfo.isEnabled,
                children = children
            )
        }
    }
}
