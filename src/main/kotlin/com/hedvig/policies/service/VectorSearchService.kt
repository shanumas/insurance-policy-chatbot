package com.hedvig.policies.service

import com.hedvig.policies.dto.ChunkMetadata
import com.hedvig.policies.dto.PolicyChunkDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Filters for two-stage retrieval based on topics
 */
data class MetadataFilter(
    val plan: String? = null,              // Filter by plan (Bas, Standard, Max, or All)
    val topic: String? = null              // Filter by specific topic name (or partial match)
)

@Service
class VectorSearchService(
    private val jsonStorageService: JsonStorageService,
    private val openAIService: OpenAIService
) {
    private val logger = LoggerFactory.getLogger(VectorSearchService::class.java)

    // Cache the chunks in memory after first load
    private var cachedChunks: List<PolicyChunkDto>? = null

    data class SearchResult(
        val chunk: PolicyChunkDto,
        val similarity: Double
    )

    private fun loadChunks(): List<PolicyChunkDto> {
        if (cachedChunks != null) {
            return cachedChunks!!
        }

        val chunksStorage = jsonStorageService.loadChunks()
        if (chunksStorage == null) {
            logger.warn("No chunks found in JSON storage")
            return emptyList()
        }

        cachedChunks = chunksStorage.chunks
        logger.info("Loaded and cached ${cachedChunks!!.size} chunks from JSON")
        return cachedChunks!!
    }

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

        // Get all chunks from JSON
        val allChunks = loadChunks()
        val chunks = allChunks.filter { it.documentName == documentName }

        if (chunks.isEmpty()) {
            logger.warn("No chunks found for document: $documentName")
            return emptyList()
        }

        logger.debug("Found ${chunks.size} chunks for document")

        // Calculate similarity for each chunk
        val results = chunks.mapNotNull { chunk ->
            try {
                val similarity = OpenAIService.cosineSimilarity(queryEmbedding, chunk.embedding)

                if (similarity >= minSimilarity) {
                    SearchResult(chunk, similarity)
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.error("Error calculating similarity for chunk ${chunk.chunkIndex}: ${e.message}")
                null
            }
        }

        // Sort by similarity descending and take top K
        val topResults = results.sortedByDescending { it.similarity }.take(topK)

        logger.info("Found ${topResults.size} similar chunks with similarity >= $minSimilarity")
        topResults.forEachIndexed { index, result ->
            logger.debug("Result ${index + 1}: similarity=${String.format("%.3f", result.similarity)}, chunk_index=${result.chunk.chunkIndex}")
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

        val allChunks = loadChunks()

        if (allChunks.isEmpty()) {
            logger.warn("No chunks found in storage")
            return emptyList()
        }

        val results = allChunks.mapNotNull { chunk ->
            try {
                val similarity = OpenAIService.cosineSimilarity(queryEmbedding, chunk.embedding)
                SearchResult(chunk, similarity)
            } catch (e: Exception) {
                logger.error("Error calculating similarity: ${e.message}")
                null
            }
        }

        return results.sortedByDescending { it.similarity }.take(topK)
    }

    /**
     * TWO-STAGE RETRIEVAL: Filter by metadata first, then rank by semantic similarity
     * This is critical for insurance documents to avoid hallucination
     */
    fun searchWithMetadataFilter(
        query: String,
        filter: MetadataFilter,
        topK: Int = 5,
        minSimilarity: Double = 0.6
    ): List<SearchResult> {
        logger.info("Two-stage search: query='$query', filter=$filter")

        // STAGE 1: Filter by metadata (cheap, deterministic)
        val allChunks = loadChunks()
        val filteredChunks = allChunks.filter { chunk ->
            matchesFilter(chunk, filter)
        }

        logger.info("Stage 1 (metadata filter): ${filteredChunks.size}/${allChunks.size} chunks match")

        if (filteredChunks.isEmpty()) {
            logger.warn("No chunks match metadata filter")
            return emptyList()
        }

        // STAGE 2: Rank by semantic similarity
        val queryEmbedding = try {
            openAIService.generateEmbedding(query)
        } catch (e: Exception) {
            logger.error("Failed to generate query embedding: ${e.message}", e)
            return emptyList()
        }

        if (queryEmbedding.isEmpty()) {
            return emptyList()
        }

        val results = filteredChunks.mapNotNull { chunk ->
            try {
                val similarity = OpenAIService.cosineSimilarity(queryEmbedding, chunk.embedding)
                if (similarity >= minSimilarity) {
                    SearchResult(chunk, similarity)
                } else {
                    null
                }
            } catch (e: Exception) {
                logger.error("Error calculating similarity: ${e.message}")
                null
            }
        }

        val topResults = results.sortedByDescending { it.similarity }.take(topK)
        logger.info("Stage 2 (semantic ranking): ${topResults.size} chunks with similarity >= $minSimilarity")

        return topResults
    }

    /**
     * Check if a chunk matches the metadata filter
     */
    private fun matchesFilter(chunk: PolicyChunkDto, filter: MetadataFilter): Boolean {
        val metadata = chunk.metadata

        // Plan filter: match exact plan OR "All" chunks
        if (filter.plan != null) {
            val planMatches = metadata.plan == filter.plan || metadata.plan == "All"
            if (!planMatches) return false
        }

        // Topic filter: partial match on topic name (case-insensitive)
        if (filter.topic != null) {
            if (!metadata.topic.contains(filter.topic, ignoreCase = true)) {
                return false
            }
        }

        return true
    }
}
