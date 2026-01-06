package com.hedvig.policies.service

import com.hedvig.policies.domain.PolicyTermsChunk
import com.hedvig.policies.repository.PolicyTermsChunkRepository
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.File

@Service
@Transactional
class PdfParsingService(
    private val policyTermsChunkRepository: PolicyTermsChunkRepository
) {
    private val logger = LoggerFactory.getLogger(PdfParsingService::class.java)
    private val chunkSize = 1000 // characters per chunk

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
}
