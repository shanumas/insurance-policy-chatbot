package com.hedvig.policies.dto

/**
 * Metadata for insurance chunks - based on table of contents
 */
data class ChunkMetadata(
    val topic: String,                     // Topic name from table of contents
    val startPage: Int,                    // Starting page number
    val endPage: Int,                      // Ending page number (inclusive)
    val plan: String = "All"               // "Bas", "Standard", "Max", or "All"
)

data class PolicyChunkDto(
    val documentName: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: DoubleArray,
    val metadata: ChunkMetadata
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PolicyChunkDto

        if (documentName != other.documentName) return false
        if (chunkIndex != other.chunkIndex) return false
        if (content != other.content) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (metadata != other.metadata) return false

        return true
    }

    override fun hashCode(): Int {
        var result = documentName.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + content.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

data class PolicyChunksStorage(
    val documentName: String,
    val chunks: List<PolicyChunkDto>,
    val generatedAt: String,
    val totalChunks: Int
)
