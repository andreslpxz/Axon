package com.bridge.accessibility

import com.google.gson.annotations.SerializedName

data class GroqRequest(
    val model: String = "qwen/qwen3-32b",
    val messages: List<GroqMessage>,
    val temperature: Float = 0.3f,
    @SerializedName("max_tokens") val maxTokens: Int = 1024
)

data class GroqMessage(
    val role: String,
    val content: String
)

data class GroqResponse(
    val choices: List<Choice>
)

data class Choice(
    val message: GroqMessage
)
