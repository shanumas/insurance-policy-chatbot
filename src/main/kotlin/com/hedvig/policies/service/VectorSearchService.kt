package com.hedvig.policies.service

import com.hedvig.policies.domain.PolicyTermsChunk
import com.hedvig.policies.repository.PolicyTermsChunkRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class VectorSearchService(
    private val policyTermsChunkRepository: PolicyTermsChunkRepository,
    private val openAIService: OpenAIService
) {
    private val logger = LoggerFactory.getLogger(VectorSearchService::class.java)

    data class SearchResult(
        val chunk: PolicyTermsChunk,
        val similarity: Double
    )

    fun searchSimilarChunks(
        query: String,
        documentName: String,
        topK: Int = 5,
        minSimilarity: Double = 0.7
    ): List<SearchResult> {
        logger.info("Searching for similar chunks to query: '$query' in document: $documentName")

        // Generate embedding for query
        val queryEmbedding = try {
            openAIService.generateEmbedding(query)
        } catch (e: Exception) {
            logger.error("Failed to generate query embedding: ${e.message}", e)
            return emptyList()
        }

        if (queryEmbedding.isEmpty()) {
            logger.warn("Empty query embedding generated")
            return emptyList()
        }

        // Get all chunks with embeddings from database
        val chunks = policyTermsChunkRepository.findByDocumentNameOrderByChunkIndex(documentName)
            .filter { it.embedding != null }

        if (chunks.isEmpty()) {
            logger.warn("No chunks with embeddings found for document: $documentName")
            return emptyList()
        }

        logger.debug("Found ${chunks.size} chunks with embeddings")

        // Calculate similarity for each chunk
        val results = chunks.mapNotNull { chunk ->
            try {
                val chunkEmbedding = OpenAIService.bytesToEmbedding(chunk.embedding!!)
                val similarity = OpenAIService.cosineSimilarity(queryEmbedding, chunkEmbedding)

                if (similarity >= minSimilarity) {
                    SearchResult(chunk, similarity)
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.error("Error calculating similarity for chunk ${chunk.id}: ${e.message}")
                null
            }
        }

        // Sort by similarity descending and take top K
        val topResults = results.sortedByDescending { it.similarity }.take(topK)

        logger.info("Found ${topResults.size} similar chunks with similarity >= $minSimilarity")
        topResults.forEachIndexed { index, result ->
            logger.debug("Result ${index + 1}: similarity=${String.format("%.3f", result.similarity)}, chunk_id=${result.chunk.id}")
        }

        return topResults
    }

    fun searchAllDocuments(query: String, topK: Int = 5): List<SearchResult> {
        logger.info("Searching across all documents for query: '$query'")

        val queryEmbedding = try {
            openAIService.generateEmbedding(query)
        } catch (e: Exception) {
            logger.error("Failed to generate query embedding: ${e.message}", e)
            return emptyList()
        }

        if (queryEmbedding.isEmpty()) {
            return emptyList()
        }

        val allChunks = policyTermsChunkRepository.findAll()
            .filter { it.embedding != null }

        val results = allChunks.mapNotNull { chunk ->
            try {
                val chunkEmbedding = OpenAIService.bytesToEmbedding(chunk.embedding!!)
                val similarity = OpenAIService.cosineSimilarity(queryEmbedding, chunkEmbedding)
                SearchResult(chunk, similarity)
            } catch (e: Exception) {
                logger.error("Error calculating similarity: ${e.message}")
                null
            }
        }

        return results.sortedByDescending { it.similarity }.take(topK)
    }
}
