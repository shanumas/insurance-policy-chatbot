package com.hedvig.policies.dto

data class PolicyChunkDto(
    val documentName: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PolicyChunkDto

        if (documentName != other.documentName) return false
        if (chunkIndex != other.chunkIndex) return false
        if (content != other.content) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = documentName.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + content.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

data class PolicyChunksStorage(
    val documentName: String,
    val chunks: List<PolicyChunkDto>,
    val generatedAt: String,
    val totalChunks: Int
)
