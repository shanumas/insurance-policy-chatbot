package com.hedvig.policies.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.hedvig.policies.domain.PolicyTermsChunk
import com.hedvig.policies.dto.ChunkMetadata
import com.hedvig.policies.repository.PolicyTermsChunkRepository
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File

data class TextChunk(
    val documentName: String,
    val chunkIndex: Int,
    val content: String,
    val metadata: ChunkMetadata
)

/**
 * Represents a topic from the table of contents
 */
data class TopicEntry(
    val topic: String,
    val startPage: Int
)

@Service
@Transactional
class PdfParsingService(
    private val policyTermsChunkRepository: PolicyTermsChunkRepository,
    private val openAIService: OpenAIService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(PdfParsingService::class.java)
    private val chunkSize = 1000 // characters per chunk (for fallback)

    fun parsePdfFromClasspath(resourcePath: String, documentName: String): List<PolicyTermsChunk> {
        logger.info("Parsing PDF from classpath: $resourcePath")

        val resource = ClassPathResource(resourcePath)
        if (!resource.exists()) {
            throw IllegalArgumentException("PDF file not found at: $resourcePath")
        }

        return parsePdfFile(resource.file, documentName)
    }

    fun parsePdfFile(file: File, documentName: String): List<PolicyTermsChunk> {
        logger.info("Loading PDF file: ${file.absolutePath}")

        val document = Loader.loadPDF(file)
        val chunks = mutableListOf<PolicyTermsChunk>()

        try {
            val stripper = PDFTextStripper()
            val totalPages = document.numberOfPages
            logger.info("PDF has $totalPages pages")

            // Extract text page by page
            for (pageNum in 1..totalPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val pageText = stripper.getText(document)

                // Chunk the page text
                val pageChunks = chunkText(pageText, documentName, pageNum, chunks.size)
                chunks.addAll(pageChunks)
            }

            logger.info("Created ${chunks.size} chunks from PDF")
        } finally {
            document.close()
        }

        return chunks
    }

    fun parseAndChunkPdf(file: File, documentName: String): List<TextChunk> {
        logger.info("Loading PDF file: ${file.absolutePath}")

        val document = Loader.loadPDF(file)
        val chunks = mutableListOf<TextChunk>()

        try {
            val stripper = PDFTextStripper()
            val totalPages = document.numberOfPages
            logger.info("PDF has $totalPages pages")

            // Extract text page by page
            for (pageNum in 1..totalPages) {
                stripper.startPage = pageNum
                stripper.endPage = pageNum
                val pageText = stripper.getText(document)

                // Chunk the page text
                val pageChunks = chunkTextSimple(pageText, documentName, pageNum, chunks.size)
                chunks.addAll(pageChunks)
            }

            logger.info("Created ${chunks.size} chunks from PDF")
        } finally {
            document.close()
        }

        return chunks
    }

    private fun chunkTextSimple(
        text: String,
        documentName: String,
        pageNumber: Int,
        startingIndex: Int
    ): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        var currentIndex = startingIndex
        var position = 0

        while (position < text.length) {
            val endPosition = minOf(position + chunkSize, text.length)
            var chunkEnd = endPosition

            // Try to find a good breaking point (end of sentence or paragraph)
            if (endPosition < text.length) {
                val lastPeriod = text.lastIndexOf('.', endPosition)
                val lastNewline = text.lastIndexOf('\n', endPosition)
                val breakPoint = maxOf(lastPeriod, lastNewline)

                if (breakPoint > position) {
                    chunkEnd = breakPoint + 1
                }
            }

            val chunkText = text.substring(position, chunkEnd).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(
                    TextChunk(
                        documentName = documentName,
                        chunkIndex = currentIndex,
                        content = chunkText,
                        metadata = ChunkMetadata(
                            topic = "Page $pageNumber",
                            startPage = pageNumber,
                            endPage = pageNumber,
                            plan = "All"
                        )
                    )
                )
                currentIndex++
            }

            position = chunkEnd
        }

        return chunks
    }

    private fun chunkText(
        text: String,
        documentName: String,
        pageNumber: Int,
        startingIndex: Int
    ): List<PolicyTermsChunk> {
        val chunks = mutableListOf<PolicyTermsChunk>()
        var currentIndex = startingIndex
        var position = 0

        while (position < text.length) {
            val endPosition = minOf(position + chunkSize, text.length)
            var chunkEnd = endPosition

            // Try to find a good breaking point (end of sentence or paragraph)
            if (endPosition < text.length) {
                val lastPeriod = text.lastIndexOf('.', endPosition)
                val lastNewline = text.lastIndexOf('\n', endPosition)
                val breakPoint = maxOf(lastPeriod, lastNewline)

                if (breakPoint > position) {
                    chunkEnd = breakPoint + 1
                }
            }

            val chunkText = text.substring(position, chunkEnd).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(
                    PolicyTermsChunk(
                        documentName = documentName,
                        chunkIndex = currentIndex,
                        content = chunkText,
                        pageNumber = pageNumber
                    )
                )
                currentIndex++
            }

            position = chunkEnd
        }

        return chunks
    }

    fun saveChunks(chunks: List<PolicyTermsChunk>): List<PolicyTermsChunk> {
        logger.info("Saving ${chunks.size} chunks to database")
        return policyTermsChunkRepository.saveAll(chunks)
    }

    fun getChunksByDocument(documentName: String): List<PolicyTermsChunk> {
        return policyTermsChunkRepository.findByDocumentNameOrderByChunkIndex(documentName)
    }

    fun searchChunks(documentName: String, keyword: String): List<PolicyTermsChunk> {
        return policyTermsChunkRepository.searchByKeyword(documentName, keyword)
    }

    fun deleteChunksByDocument(documentName: String) {
        logger.info("Deleting chunks for document: $documentName")
        policyTermsChunkRepository.deleteByDocumentName(documentName)
    }

    fun generateEmbeddingsForChunks(chunks: List<PolicyTermsChunk>): List<PolicyTermsChunk> {
        logger.info("Generating embeddings for ${chunks.size} chunks...")

        val chunksWithEmbeddings = chunks.mapIndexed { index, chunk ->
            try {
                logger.debug("Generating embedding for chunk ${index + 1}/${chunks.size}")

                val embedding = openAIService.generateEmbedding(chunk.content)

                if (embedding.isEmpty()) {
                    logger.warn("Empty embedding generated for chunk ${chunk.id}, skipping")
                    return@mapIndexed chunk
                }

                val embeddingBytes = OpenAIService.embeddingToBytes(embedding)

                chunk.copy(embedding = embeddingBytes)
            } catch (e: Exception) {
                logger.error("Failed to generate embedding for chunk ${chunk.id}: ${e.message}")
                chunk
            }
        }

        logger.info("Successfully generated embeddings for ${chunksWithEmbeddings.count { it.embedding != null }} chunks")

        return policyTermsChunkRepository.saveAll(chunksWithEmbeddings)
    }

    /**
     * Parse PDF by topics from table of contents
     * One chunk per topic, with metadata containing topic name and page range
     */
    fun parseByTopics(file: File, documentName: String): List<TextChunk> {
        logger.info("Parsing PDF by topics: ${file.absolutePath}")

        // Define table of contents
        val tableOfContents = getTableOfContents()

        val document = Loader.loadPDF(file)
        val chunks = mutableListOf<TextChunk>()

        try {
            val stripper = PDFTextStripper()

            // Determine plan boundaries
            // Pages 1-48: All plans (Bas is foundation for all)
            // Pages 49-57: Standard and Max
            // Pages 58+: Max only
            val standardStartPage = 49
            val maxStartPage = 58

            tableOfContents.forEachIndexed { index, entry ->
                // Determine end page (start of next topic - 1, or last page)
                val endPage = if (index < tableOfContents.size - 1) {
                    tableOfContents[index + 1].startPage - 1
                } else {
                    document.numberOfPages
                }

                // Extract text for this topic's page range
                stripper.startPage = entry.startPage
                stripper.endPage = endPage
                val topicText = stripper.getText(document).trim()

                if (topicText.isNotEmpty()) {
                    // Determine plan level based on page range
                    val plan = when {
                        entry.startPage >= maxStartPage -> "Max"
                        entry.startPage >= standardStartPage -> "Standard"
                        else -> "All"  // Pages 1-48: available to all plans
                    }

                    chunks.add(
                        TextChunk(
                            documentName = documentName,
                            chunkIndex = index,
                            content = topicText,
                            metadata = ChunkMetadata(
                                topic = entry.topic,
                                startPage = entry.startPage,
                                endPage = endPage,
                                plan = plan
                            )
                        )
                    )
                }
            }

            logger.info("Created ${chunks.size} topic-based chunks")
        } finally {
            document.close()
        }

        return chunks
    }

    /**
     * Table of contents from the insurance document
     */
    private fun getTableOfContents(): List<TopicEntry> {
        return listOf(
            TopicEntry("Vad ersätts, var gäller försäkringen och för vem?", 5),
            TopicEntry("Vad händer vid en skada?", 11),
            TopicEntry("Allmänna begränsningar", 12),
            TopicEntry("Generella säkerhetsföreskrifter", 14),
            TopicEntry("Hemförsäkring Bostadsrätt Bas", 16),
            TopicEntry("Skadegörelse, stöld och inbrott", 17),
            TopicEntry("I din bostad", 18),
            TopicEntry("Utanför din bostad", 19),
            TopicEntry("Förvarad i din bil", 21),
            TopicEntry("Medförda saker", 22),
            TopicEntry("Cykel och barvagn", 23),
            TopicEntry("Eldsvåda", 24),
            TopicEntry("Vattenläcka", 26),
            TopicEntry("Naturskada", 28),
            TopicEntry("Installationer", 30),
            TopicEntry("Glasrutor", 32),
            TopicEntry("Obrukbar bostad", 33),
            TopicEntry("Överfallsförsäkring", 34),
            TopicEntry("Hur mycket kan du få i ersättning?", 34),
            TopicEntry("Krisförsäkring", 36),
            TopicEntry("Reseskydd", 37),
            TopicEntry("Nödsituation", 41),
            TopicEntry("Ansvar", 42),
            TopicEntry("Rättsskydd", 44),
            TopicEntry("Hemförsäkring Bostadsrätt Standard", 49),
            TopicEntry("Drulle Dina saker", 50),
            TopicEntry("Drulle Egen bekostad fast inredning", 52),
            TopicEntry("ID-skydd", 54),
            TopicEntry("Onlinestöd", 55),
            TopicEntry("Hemförsäkring Bostadsrätt Max", 58),
            TopicEntry("Nyvärdesskydd", 59),
            TopicEntry("Uthyrning", 60),
            TopicEntry("Reseskydd Plus", 63),
            TopicEntry("Avbeställningsskydd", 66),
            TopicEntry("Resestarskydd", 68),
            TopicEntry("Personförsening", 69),
            TopicEntry("Bagageförsening", 70),
            TopicEntry("Outnyttjad resekostnad", 71),
            TopicEntry("Outnyttjad aktivitetsresa", 73),
            TopicEntry("Ersättningsresa", 74),
            TopicEntry("Outnyttjad evenemangskostnad", 75),
            TopicEntry("Självriskreducering hyrbil", 76),
            TopicEntry("Hur ersättning går till", 78),
            TopicEntry("Ersättningstabell för dina saker", 81),
            TopicEntry("Ersättningstabell för din bostadsrätt", 82),
            TopicEntry("Allmänna villkor", 84),
            TopicEntry("Betalning av premie", 84),
            TopicEntry("Begreppsförklaringar", 89)
        )
    }
}
