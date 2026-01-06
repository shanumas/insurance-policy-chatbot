package com.hedvig.policies.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "policy_terms_chunk")
data class PolicyTermsChunk(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "document_name", nullable = false)
    val documentName: String,

    @Column(name = "chunk_index", nullable = false)
    val chunkIndex: Int,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "page_number")
    val pageNumber: Int? = null,

    @Lob
    @Column(name = "embedding", columnDefinition = "BLOB")
    val embedding: ByteArray? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PolicyTermsChunk

        if (id != other.id) return false
        if (documentName != other.documentName) return false
        if (chunkIndex != other.chunkIndex) return false
        if (content != other.content) return false
        if (pageNumber != other.pageNumber) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + documentName.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + content.hashCode()
        result = 31 * result + (pageNumber ?: 0)
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
