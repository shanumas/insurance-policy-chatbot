package com.hedvig.policies.dto

data class OpenAIEmbeddingRequest(
    val model: String,
    val input: String
)

data class OpenAIEmbeddingResponse(
    val `object`: String,
    val data: List<EmbeddingData>,
    val model: String,
    val usage: Usage
)

data class EmbeddingData(
    val `object`: String,
    val embedding: List<Double>,
    val index: Int
)

data class Usage(
    val prompt_tokens: Int,
    val total_tokens: Int
)

data class OpenAIChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int? = null,
    val logprobs: Boolean = false,
    val top_logprobs: Int? = null
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class OpenAIChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage
)

data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String,
    val logprobs: ChoiceLogprobs? = null
)

data class ChoiceLogprobs(
    val content: List<TokenLogprob>? = null
)

data class TokenLogprob(
    val token: String,
    val logprob: Double,
    val bytes: List<Int>? = null
)

data class ChatResult(
    val content: String,
    val confidence: Double
)
