package com.mobileclaw.app.adapter.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mobileclaw.app.R
import com.mobileclaw.app.databinding.ItemChatBinding

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
 */
class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

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
            binding.txtContent.text = msg.content
            val ctx = binding.root.context
            if (msg.type == ChatMessage.TYPE_USER) {
                // 用户消息：右对齐，品牌蓝背景
                binding.layoutMessage.gravity = android.view.Gravity.END
                binding.cardMessage.setCardBackgroundColor(
                    ctx.getColor(com.mobileclaw.app.R.color.brand_primary)
                )
                binding.txtContent.setTextColor(
                    ctx.getColor(android.R.color.white)
                )
            } else {
                // AI 消息：左对齐，白色卡片背景
                binding.layoutMessage.gravity = android.view.Gravity.START
                binding.cardMessage.setCardBackgroundColor(
                    ctx.getColor(com.mobileclaw.app.R.color.surface_white)
                )
                binding.txtContent.setTextColor(
                    ctx.getColor(android.R.color.black)
                )
            }
            binding.txtContent.visibility = View.VISIBLE
        }
    }
}
