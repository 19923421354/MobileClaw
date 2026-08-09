package com.mobileclaw.app.adapter.recyclerview

import android.content.ClipData
import android.content.ClipboardManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mobileclaw.app.R
import com.mobileclaw.app.databinding.ItemChatBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 聊天消息数据模型。
 */
data class ChatMessage(
    val type: Int,
    var content: String,
    var isThinking: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_USER = 0
        const val TYPE_AI = 1
        const val TYPE_DATE_SEPARATOR = 2

        /**
         * 智能格式化时间戳。
         *
         * @param timestamp 毫秒时间戳
         * @param isSeparator 是否为日期分隔头格式
         * @return 格式化后的时间字符串
         */
        fun formatTimestamp(timestamp: Long, isSeparator: Boolean = false): String {
            val now = Calendar.getInstance()
            val msgTime = Calendar.getInstance().apply { timeInMillis = timestamp }
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

            return if (isSeparator) {
                when {
                    isSameDay(now, msgTime) -> "\u2014\u2014 今天 \u2014\u2014"
                    isYesterday(now, msgTime) -> "\u2014\u2014 昨天 \u2014\u2014"
                    else -> {
                        val dateStr = SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(timestamp))
                        "\u2014\u2014 $dateStr \u2014\u2014"
                    }
                }
            } else {
                when {
                    isSameDay(now, msgTime) -> timeFormat.format(Date(timestamp))
                    isYesterday(now, msgTime) -> "昨天 ${timeFormat.format(Date(timestamp))}"
                    isSameWeek(now, msgTime) -> {
                        val dayNames = arrayOf("日", "一", "二", "三", "四", "五", "六")
                        val dayIndex = msgTime.get(Calendar.DAY_OF_WEEK) - 1
                        "周${dayNames[dayIndex]} ${timeFormat.format(Date(timestamp))}"
                    }
                    else -> SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
                }
            }
        }

        /**
         * 判断两个 Calendar 是否在同一天。
         */
        fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
            return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                    cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
        }

        /**
         * 判断 target 是否为昨天（相对于 now）。
         */
        private fun isYesterday(now: Calendar, target: Calendar): Boolean {
            val yesterday = Calendar.getInstance().apply {
                timeInMillis = now.timeInMillis - 86400000L
            }
            return isSameDay(yesterday, target)
        }

        /**
         * 判断 target 是否与 now 在同一周内。
         */
        private fun isSameWeek(now: Calendar, target: Calendar): Boolean {
            return now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                    now.get(Calendar.WEEK_OF_YEAR) == target.get(Calendar.WEEK_OF_YEAR)
        }
    }
}

/**
 * 聊天消息列表适配器。
 *
 * v2.1.0 优化：使用新的气泡背景 Drawable + 时间戳显示 + 更流畅的动画效果。
 * v2.2.0 增强：智能时间格式化 + 日期分隔头 + 空状态建议。
 * v2.3.0 增强：长按复制、选中状态高亮、日期分隔符美化、消息对齐。
 */
class ChatMessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private var selectedPosition = -1

    /**
     * 添加消息，自动判断是否需要插入日期分隔头。
     */
    fun addMessage(msg: ChatMessage) {
        // 检查是否需要插入日期分隔头
        if (msg.type != ChatMessage.TYPE_DATE_SEPARATOR && messages.isNotEmpty()) {
            val lastMsg = messages.last()
            val lastCal = Calendar.getInstance().apply { timeInMillis = lastMsg.timestamp }
            val msgCal = Calendar.getInstance().apply { timeInMillis = msg.timestamp }

            if (!ChatMessage.isSameDay(lastCal, msgCal)) {
                val separator = ChatMessage(
                    type = ChatMessage.TYPE_DATE_SEPARATOR,
                    content = ChatMessage.formatTimestamp(msg.timestamp, isSeparator = true),
                    timestamp = msg.timestamp
                )
                messages.add(separator)
                notifyItemInserted(messages.size - 1)
            }
        }
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun removeLast() {
        if (messages.isNotEmpty()) {
            messages.removeAt(messages.size - 1)
            notifyItemRemoved(messages.size)
        }
    }

    fun clear() {
        messages.clear()
        selectedPosition = -1
        notifyDataSetChanged()
    }

    fun getAllMessages(): List<ChatMessage> = messages.toList()

    override fun getItemViewType(position: Int): Int {
        return messages[position].type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == ChatMessage.TYPE_DATE_SEPARATOR) {
            // 创建容器 FrameLayout，用于居中显示日期分隔符
            val container = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 16, 0, 12)
            }
            // 创建带背景的日期分隔符 TextView
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                setBackgroundResource(R.drawable.bg_date_separator)
                setPadding(16, 5, 16, 5)
            }
            container.addView(tv)
            DateSeparatorViewHolder(container)
        } else {
            val binding = ItemChatBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            ViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is ViewHolder -> holder.bind(msg, position)
            is DateSeparatorViewHolder -> holder.bind(msg.content)
        }
    }

    override fun getItemCount(): Int = messages.size

    /**
     * 日期分隔头 ViewHolder。
     */
    class DateSeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(text: String) {
            // 在容器中查找唯一的 TextView
            val tv = (itemView as? FrameLayout)?.getChildAt(0) as? TextView
            tv?.text = text
        }
    }

    /**
     * 普通消息气泡 ViewHolder。
     */
    inner class ViewHolder(private val binding: ItemChatBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(msg: ChatMessage, position: Int) {
            val ctx = binding.root.context

            binding.txtContent.text = msg.content

            if (msg.type == ChatMessage.TYPE_USER) {
                // 用户消息：右对齐，品牌蓝渐变气泡
                binding.layoutMessage.gravity = android.view.Gravity.END
                // 根据选中状态选择背景
                if (selectedPosition == position) {
                    binding.txtContent.setBackgroundResource(R.drawable.bg_chat_bubble_user_selected)
                } else {
                    binding.txtContent.setBackgroundResource(R.drawable.bg_chat_bubble_user)
                }
                binding.txtContent.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
            } else {
                // AI 消息：左对齐，白色气泡 + 浅灰边框
                binding.layoutMessage.gravity = android.view.Gravity.START
                // 根据选中状态选择背景
                if (selectedPosition == position) {
                    binding.txtContent.setBackgroundResource(R.drawable.bg_chat_bubble_ai_selected)
                } else {
                    binding.txtContent.setBackgroundResource(R.drawable.bg_chat_bubble_ai)
                }
                binding.txtContent.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_primary)
                )
            }

            // 时间戳（使用智能格式化）
            val timeStr = ChatMessage.formatTimestamp(msg.timestamp)
            binding.txtTimestamp.text = timeStr
            binding.txtTimestamp.visibility = View.VISIBLE

            // 点击切换选中状态（蓝色边框高亮）
            binding.root.setOnClickListener {
                val previousSelected = selectedPosition
                selectedPosition = if (selectedPosition == position) -1 else position
                // 通知上一个选中项和当前选中项刷新
                if (previousSelected != -1) {
                    notifyItemChanged(previousSelected)
                }
                if (selectedPosition != -1) {
                    notifyItemChanged(selectedPosition)
                }
            }

            // 长按复制消息内容到剪贴板
            binding.root.setOnLongClickListener {
                val clipboard =
                    ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("chat_message", msg.content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                true
            }
        }
    }
}