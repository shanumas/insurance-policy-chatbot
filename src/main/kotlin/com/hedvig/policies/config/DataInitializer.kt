package com.hedvig.policies.config

import com.hedvig.policies.dto.PolicyChunkDto
import com.hedvig.policies.dto.PolicyChunksStorage
import com.hedvig.policies.service.JsonStorageService
import com.hedvig.policies.service.OpenAIService
import com.hedvig.policies.service.PdfParsingService
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class DataInitializer(
    private val pdfParsingService: PdfParsingService,
    private val jsonStorageService: JsonStorageService,
    private val openAIService: OpenAIService,
    private val resourceLoader: ResourceLoader
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(DataInitializer::class.java)
    private val documentName = "hedvig-brf-standard"
    private val pdfPath = "docs/terms/hedvig-brf-standard.pdf"

    override fun run(vararg args: String?) {
        logger.info("Starting data initialization...")

        try {
            // Check if JSON file already exists
            if (jsonStorageService.chunksFileExists()) {
                logger.info("Policy chunks JSON file already exists at: ${jsonStorageService.getChunksFilePath()}")
                val chunksStorage = jsonStorageService.loadChunks()
                if (chunksStorage != null) {
                    logger.info("Found ${chunksStorage.totalChunks} existing chunks in JSON file")
                    logger.info("Generated at: ${chunksStorage.generatedAt}")
                    logger.info("Skipping PDF parsing and embedding generation to save costs.")
                    return
                }
            }

            logger.info("JSON file not found. Will parse PDF and generate embeddings...")

            // Load PDF from file system
            val resource: Resource = resourceLoader.getResource("file:$pdfPath")

            if (!resource.exists()) {
                logger.warn("PDF file not found at: $pdfPath")
                logger.warn("Skipping PDF initialization. Chatbot will work with limited functionality.")
                return
            }

            logger.info("Parsing PDF file by topics from table of contents: ${resource.filename}")

            val textChunks = pdfParsingService.parseByTopics(resource.file, documentName)

            logger.info("Created ${textChunks.size} topic-based chunks from PDF")

            // Generate embeddings for chunks
            logger.info("Generating embeddings for ${textChunks.size} chunks... This may take a few minutes and will incur API costs.")
            val chunksWithEmbeddings = mutableListOf<PolicyChunkDto>()

            textChunks.forEachIndexed { index, chunk ->
                try {
                    if ((index + 1) % 10 == 0) {
                        logger.info("Generated embeddings for ${index + 1}/${textChunks.size} chunks...")
                    }

                    val embedding = openAIService.generateEmbedding(chunk.content)

                    if (embedding.isNotEmpty()) {
                        chunksWithEmbeddings.add(
                            PolicyChunkDto(
                                documentName = chunk.documentName,
                                chunkIndex = chunk.chunkIndex,
                                content = chunk.content,
                                embedding = embedding,
                                metadata = chunk.metadata
                            )
                        )
                    } else {
                        logger.warn("Empty embedding generated for chunk ${chunk.chunkIndex}")
                    }

                } catch (e: Exception) {
                    logger.error("Failed to generate embedding for chunk ${chunk.chunkIndex}: ${e.message}")
                }
            }

            logger.info("Successfully generated embeddings for ${chunksWithEmbeddings.size}/${textChunks.size} chunks")

            // Save to JSON file
            val chunksStorage = PolicyChunksStorage(
                documentName = documentName,
                chunks = chunksWithEmbeddings,
                generatedAt = LocalDateTime.now().toString(),
                totalChunks = chunksWithEmbeddings.size
            )

            jsonStorageService.saveChunks(chunksStorage)
            logger.info("Successfully saved chunks with embeddings to JSON file: ${jsonStorageService.getChunksFilePath()}")

        } catch (e: Exception) {
            logger.error("Error during data initialization: ${e.message}", e)
            logger.warn("Application will continue but chatbot functionality may be limited")
        }
    }
}
