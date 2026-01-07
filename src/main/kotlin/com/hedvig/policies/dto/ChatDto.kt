package com.hedvig.policies.dto

data class ChatRequest(
    val message: String,
    val conversationId: String? = null
)

data class ChatResponse(
    val message: String,
    val conversationId: String,
    val confidence: Double = 0.0,
    val sources: List<SourceReference> = emptyList()
)

data class SourceReference(
    val documentName: String,
    val chunkIndex: Int,
    val similarity: Double,
    val preview: String
)
