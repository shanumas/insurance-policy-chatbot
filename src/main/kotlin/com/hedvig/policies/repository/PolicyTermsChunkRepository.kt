package com.hedvig.policies.repository

import com.hedvig.policies.domain.PolicyTermsChunk
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface PolicyTermsChunkRepository : JpaRepository<PolicyTermsChunk, Long> {

    fun findByDocumentNameOrderByChunkIndex(documentName: String): List<PolicyTermsChunk>

    fun existsByDocumentName(documentName: String): Boolean

    fun deleteByDocumentName(documentName: String)

    @Query("""
        SELECT p FROM PolicyTermsChunk p
        WHERE p.documentName = :documentName
        AND LOWER(p.content) LIKE LOWER(CONCAT('%', :keyword, '%'))
        ORDER BY p.chunkIndex
    """)
    fun searchByKeyword(
        @Param("documentName") documentName: String,
        @Param("keyword") keyword: String
    ): List<PolicyTermsChunk>
}
