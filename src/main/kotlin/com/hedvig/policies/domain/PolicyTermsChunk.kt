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

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
