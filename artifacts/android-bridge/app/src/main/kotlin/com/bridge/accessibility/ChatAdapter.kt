package com.bridge.accessibility

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.bridge.accessibility.databinding.ItemChatMessageBinding

class ChatAdapter(
    private val onApprove: () -> Unit,
    private val onReject: () -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private var messages = listOf<ChatMessage>()

    fun setMessages(newMessages: List<ChatMessage>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        holder.bind(msg)
    }

    override fun getItemCount(): Int = messages.size

    inner class ChatViewHolder(private val binding: ItemChatMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(msg: ChatMessage) {
            binding.messageText.text = msg.content

            val params = binding.messageCard.layoutParams as LinearLayout.LayoutParams
            if (msg.type == MessageType.USER) {
                params.gravity = Gravity.END
                binding.messageCard.setCardBackgroundColor(0xFF00E5FF.toInt())
                binding.messageText.setTextColor(0xFF1B1B2F.toInt())
                binding.planControls.visibility = View.GONE
            } else {
                params.gravity = Gravity.START
                binding.messageCard.setCardBackgroundColor(0xFF2A2A3E.toInt())
                binding.messageText.setTextColor(0xFFFFFFFF.toInt())

                if (msg.type == MessageType.PLAN) {
                    binding.planControls.visibility = View.VISIBLE
                    binding.approveButton.setOnClickListener { onApprove() }
                    binding.rejectButton.setOnClickListener {
                        onReject()
                        binding.planControls.visibility = View.GONE
                    }
                } else {
                    binding.planControls.visibility = View.GONE
                }
            }
            binding.messageCard.layoutParams = params
        }
    }
}
