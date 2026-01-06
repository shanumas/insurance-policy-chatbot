package com.hedvig.policies.service

import com.hedvig.policies.dto.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.nio.ByteBuffer

@Service
class OpenAIService(
    @Value("\${openai.api.key}") private val apiKey: String,
    @Value("\${openai.api.url}") private val apiUrl: String,
    @Value("\${openai.embedding.model}") private val embeddingModel: String,
    private val webClientBuilder: WebClient.Builder
) {
    private val logger = LoggerFactory.getLogger(OpenAIService::class.java)

    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(apiUrl)
            .defaultHeader("Authorization", "Bearer $apiKey")
            .defaultHeader("Content-Type", "application/json")
            .build()
    }

    fun generateEmbedding(text: String): DoubleArray {
        if (apiKey.isBlank()) {
            logger.warn("OpenAI API key not configured. Skipping embedding generation.")
            return DoubleArray(0)
        }

        try {
            val request = OpenAIEmbeddingRequest(
                model = embeddingModel,
                input = text
            )

            val response = webClient.post()
                .uri("/embeddings")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OpenAIEmbeddingResponse::class.java)
                .block()

            if (response == null || response.data.isEmpty()) {
                logger.error("Empty response from OpenAI embeddings API")
                return DoubleArray(0)
            }

            val embedding = response.data[0].embedding.toDoubleArray()
            logger.debug("Generated embedding of size ${embedding.size} for text of length ${text.length}")

            return embedding
        } catch (e: Exception) {
            logger.error("Error generating embedding: ${e.message}", e)
            throw RuntimeException("Failed to generate embedding", e)
        }
    }

    fun generateEmbeddings(texts: List<String>): List<DoubleArray> {
        return texts.map { generateEmbedding(it) }
    }

    fun chat(messages: List<ChatMessage>, model: String = "gpt-4o-mini", maxTokens: Int? = null): String {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key not configured")
        }

        try {
            val request = OpenAIChatRequest(
                model = model,
                messages = messages,
                temperature = 0.7,
                max_tokens = maxTokens
            )

            val response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OpenAIChatResponse::class.java)
                .block()

            if (response == null || response.choices.isEmpty()) {
                logger.error("Empty response from OpenAI chat API")
                throw RuntimeException("Empty response from OpenAI")
            }

            return response.choices[0].message.content
        } catch (e: Exception) {
            logger.error("Error in chat completion: ${e.message}", e)
            throw RuntimeException("Failed to get chat completion", e)
        }
    }

    companion object {
        // Utility methods for converting embeddings to/from ByteArray for database storage

        fun embeddingToBytes(embedding: DoubleArray): ByteArray {
            val buffer = ByteBuffer.allocate(embedding.size * 8)
            embedding.forEach { buffer.putDouble(it) }
            return buffer.array()
        }

        fun bytesToEmbedding(bytes: ByteArray): DoubleArray {
            val buffer = ByteBuffer.wrap(bytes)
            val embedding = DoubleArray(bytes.size / 8)
            for (i in embedding.indices) {
                embedding[i] = buffer.getDouble()
            }
            return embedding
        }

        fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
            if (a.size != b.size) {
                throw IllegalArgumentException("Vectors must have the same dimension")
            }

            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0

            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }

            return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB))
        }
    }
}
