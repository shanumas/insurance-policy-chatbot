package com.hedvig.policies.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.hedvig.policies.dto.PolicyChunksStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime

@Service
class JsonStorageService {
    private val logger = LoggerFactory.getLogger(JsonStorageService::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
    }

    companion object {
        private const val STORAGE_DIR = "data"
        private const val CHUNKS_FILE = "policy-chunks-embeddings.json"
    }

    init {
        // Create storage directory if it doesn't exist
        val dir = File(STORAGE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info("Created storage directory: $STORAGE_DIR")
        }
    }

    fun saveChunks(chunksStorage: PolicyChunksStorage) {
        try {
            val file = File(STORAGE_DIR, CHUNKS_FILE)
            objectMapper.writeValue(file, chunksStorage)
            logger.info("Successfully saved ${chunksStorage.totalChunks} chunks to ${file.absolutePath}")
        } catch (e: Exception) {
            logger.error("Error saving chunks to JSON: ${e.message}", e)
            throw RuntimeException("Failed to save chunks to JSON", e)
        }
    }

    fun loadChunks(): PolicyChunksStorage? {
        return try {
            val file = File(STORAGE_DIR, CHUNKS_FILE)
            if (!file.exists()) {
                logger.info("Chunks file does not exist: ${file.absolutePath}")
                return null
            }

            val chunksStorage = objectMapper.readValue<PolicyChunksStorage>(file)
            logger.info("Successfully loaded ${chunksStorage.totalChunks} chunks from ${file.absolutePath}")
            chunksStorage
        } catch (e: Exception) {
            logger.error("Error loading chunks from JSON: ${e.message}", e)
            null
        }
    }

    fun chunksFileExists(): Boolean {
        val file = File(STORAGE_DIR, CHUNKS_FILE)
        return file.exists()
    }

    fun getChunksFilePath(): String {
        return File(STORAGE_DIR, CHUNKS_FILE).absolutePath
    }
}
