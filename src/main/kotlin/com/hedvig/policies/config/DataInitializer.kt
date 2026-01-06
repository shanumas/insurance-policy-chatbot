package com.hedvig.policies.config

import com.hedvig.policies.repository.PolicyTermsChunkRepository
import com.hedvig.policies.service.PdfParsingService
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.io.File

@Component
class DataInitializer(
    private val pdfParsingService: PdfParsingService,
    private val policyTermsChunkRepository: PolicyTermsChunkRepository,
    private val resourceLoader: ResourceLoader
) : CommandLineRunner {

    private val logger = LoggerFactory.getLogger(DataInitializer::class.java)
    private val documentName = "hedvig-brf-standard"
    private val pdfPath = "docs/terms/hedvig-brf-standard.pdf"

    override fun run(vararg args: String?) {
        logger.info("Starting data initialization...")

        try {
            // Check if already loaded
            if (policyTermsChunkRepository.existsByDocumentName(documentName)) {
                logger.info("Policy terms already loaded in database. Skipping PDF parsing.")
                val count = policyTermsChunkRepository.findByDocumentNameOrderByChunkIndex(documentName).size
                logger.info("Found $count existing chunks for document: $documentName")
                return
            }

            // Load PDF from file system (not classpath, since it's in docs/)
            val resource: Resource = resourceLoader.getResource("file:$pdfPath")

            if (!resource.exists()) {
                logger.warn("PDF file not found at: $pdfPath")
                logger.warn("Skipping PDF initialization. Chatbot will work with limited functionality.")
                return
            }

            logger.info("Parsing PDF file: ${resource.filename}")
            val chunks = pdfParsingService.parsePdfFile(resource.file, documentName)

            logger.info("Saving ${chunks.size} chunks to database...")
            val savedChunks = pdfParsingService.saveChunks(chunks)

            logger.info("Successfully loaded policy terms document with ${chunks.size} chunks")

            // Generate embeddings for chunks
            try {
                logger.info("Generating embeddings for chunks... This may take a few minutes.")
                pdfParsingService.generateEmbeddingsForChunks(savedChunks)
                logger.info("Successfully generated embeddings for all chunks")
            } catch (e: Exception) {
                logger.error("Failed to generate embeddings: ${e.message}", e)
                logger.warn("Application will continue but RAG functionality may be limited")
            }

        } catch (e: Exception) {
            logger.error("Error loading policy terms PDF: ${e.message}", e)
            logger.warn("Application will continue but chatbot functionality may be limited")
        }
    }
}
