package com.mobileclaw.app.adapter.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.mobileclaw.app.R
import com.mobileclaw.app.databinding.ItemChatBinding
import java.text.SimpleDateFormat
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
    }
}

/**
 * 聊天消息列表适配器。
 *
 * v2.1.0 优化：使用新的气泡背景 Drawable + 时间戳显示 + 更流畅的动画效果。
 */
class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun addMessage(msg: ChatMessage) {
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
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChatBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount(): Int = messages.size

    class ViewHolder(private val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(msg: ChatMessage) {
            val ctx = binding.root.context

            binding.txtContent.text = msg.content

            if (msg.type == ChatMessage.TYPE_USER) {
                // 用户消息：右对齐，品牌蓝渐变气泡
                binding.layoutMessage.gravity = android.view.Gravity.END
                binding.txtContent.setBackgroundResource(R.drawable.bg_chat_bubble_user)
                binding.txtContent.setTextColor(
                    ContextCompat.getColor(ctx, android.R.color.white)
                )
            } else {
                // AI 消息：左对齐，白色气泡 + 浅灰边框
                binding.layoutMessage.gravity = android.view.Gravity.START
                binding.txtContent.setBackgroundResource(R.drawable.bg_chat_bubble_ai)
                binding.txtContent.setTextColor(
                    ContextCompat.getColor(ctx, R.color.text_primary)
                )
            }

            // 时间戳
            val timeStr = formatTime(msg.timestamp)
            binding.txtTimestamp.text = timeStr
            binding.txtTimestamp.visibility = View.VISIBLE
        }

        private fun formatTime(timestamp: Long): String {
            return try {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp))
            } catch (e: Exception) {
                ""
            }
        }
    }
}