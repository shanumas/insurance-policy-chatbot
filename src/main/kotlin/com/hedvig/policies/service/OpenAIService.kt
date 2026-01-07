package com.hedvig.policies.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.hedvig.policies.dto.ChatMessage
import com.hedvig.policies.dto.ChatResult
import com.hedvig.policies.dto.ChoiceLogprobs
import com.hedvig.policies.dto.OpenAIChatRequest
import com.hedvig.policies.dto.OpenAIChatResponse
import com.hedvig.policies.dto.OpenAIEmbeddingRequest
import com.hedvig.policies.dto.OpenAIEmbeddingResponse
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
    private val webClientBuilder: WebClient.Builder,
    private val objectMapper: ObjectMapper
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

    fun chat(messages: List<ChatMessage>, model: String = "gpt-4o-mini", maxTokens: Int? = null): ChatResult {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key not configured")
        }

        try {
            val request = OpenAIChatRequest(
                model = model,
                messages = messages,
                temperature = 0.7,
                max_tokens = maxTokens,
                logprobs = true,
                top_logprobs = 1
            )

            logger.debug("Sending chat request to OpenAI with model: $model")

            val response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .onStatus({ status -> status.isError }) { clientResponse ->
                    clientResponse.bodyToMono(String::class.java).map { body ->
                        logger.error("OpenAI API error response: Status=${clientResponse.statusCode()}, Body=$body")
                        RuntimeException("OpenAI API error: ${clientResponse.statusCode()} - $body")
                    }
                }
                .bodyToMono(OpenAIChatResponse::class.java)
                .block()

            if (response == null || response.choices.isEmpty()) {
                logger.error("Empty response from OpenAI chat API")
                throw RuntimeException("Empty response from OpenAI")
            }

            val choice = response.choices[0]
            val confidence = calculateConfidence(choice.logprobs)

            logger.debug("Successfully received response from OpenAI with confidence: ${"%.2f".format(confidence * 100)}%")
            return ChatResult(
                content = choice.message.content,
                confidence = confidence
            )
        } catch (e: Exception) {
            logger.error("Error in chat completion: ${e.message}", e)
            throw RuntimeException("Failed to get chat completion: ${e.message}", e)
        }
    }

    /**
     * Calculate confidence score from logprobs.
     * Converts average log probability to a 0-1 confidence score.
     */
    private fun calculateConfidence(logprobs: ChoiceLogprobs?): Double {
        val tokenLogprobs = logprobs?.content
        if (tokenLogprobs.isNullOrEmpty()) {
            return 0.0
        }

        // Calculate average probability from log probabilities
        // logprob is in natural log, so exp(logprob) gives the probability
        val avgLogprob = tokenLogprobs.map { it.logprob }.average()

        // Convert to probability (0 to 1 range)
        // exp(logprob) gives the actual probability
        val avgProbability = Math.exp(avgLogprob)

        // Clamp to 0-1 range and round to 2 decimal places
        return avgProbability.coerceIn(0.0, 1.0)
    }

    /**
     * Map a user query to relevant insurance topics
     * Uses fast model for quick pre-filtering
     */
    fun mapQueryToTopics(query: String, availableTopics: List<String>): List<String> {
        if (apiKey.isBlank()) {
            logger.warn("OpenAI API key not configured. Returning all topics.")
            return availableTopics
        }

        try {
            val topicList = availableTopics.joinToString("\n") { "- $it" }

            val systemPrompt = """
                Du är en expert på svenska hemförsäkringar.
                Din uppgift: analysera användarens fråga och identifiera vilka topics från innehållsförteckningen som är relevanta.

                SCENARIO-MAPPNING (viktigt!):
                - Tappade/råkade/av misstag/gick sönder/skadade själv → "Drulle" (allrisk/olyckshändelser)
                - Stulet/borta/inbrott/rånad → Stöld-relaterade topics
                - Brand/eld/rök/explosion → Brand-relaterade topics
                - Vatten/läcka/översvämning → Vattenskada-relaterade topics
                - Storm/blåst/hagel/naturkatastrof → Naturskada-relaterade topics
                - Resa/utomlands/semester → Reseskydd-relaterade topics

                Regler:
                - Returnera ENDAST topic-namn från listan (exakt som de är skrivna)
                - Inkludera 2-5 mest relevanta topics
                - Om frågan handlar om ersättning/täckning, inkludera både det specifika momentet OCH "Hur mycket kan du få i ersättning?"
                - Om frågan är allmän, inkludera de bredare topics
                - Svara i JSON-format: {"topics": ["topic1", "topic2"]}

                Tillgängliga topics:
                $topicList
            """.trimIndent()

            val userMessage = "Användarfråga: $query"

            val request = mapOf(
                "model" to "gpt-4o-mini",  // Fast and cheap
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userMessage)
                ),
                "response_format" to mapOf("type" to "json_object"),
                "temperature" to 0.3,
                "max_tokens" to 200
            )

            logger.debug("Mapping query to topics: '$query'")

            val response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .onStatus({ status -> status.isError }) { clientResponse ->
                    clientResponse.bodyToMono(String::class.java).map { body ->
                        logger.error("OpenAI API error: Status=${clientResponse.statusCode()}, Body=$body")
                        RuntimeException("OpenAI API error: ${clientResponse.statusCode()}")
                    }
                }
                .bodyToMono(Map::class.java)
                .block()

            @Suppress("UNCHECKED_CAST")
            val choices = response?.get("choices") as? List<Map<String, Any>>
            val messageContent = (choices?.get(0)?.get("message") as? Map<String, Any>)?.get("content") as? String

            if (messageContent.isNullOrBlank()) {
                logger.warn("Empty response from topic mapper, using all topics")
                return availableTopics
            }

            // Parse JSON response
            val jsonResponse = objectMapper.readValue(messageContent, Map::class.java)
            @Suppress("UNCHECKED_CAST")
            val topics = (jsonResponse["topics"] as? List<String>) ?: emptyList()

            logger.info("Mapped query to ${topics.size} topics: $topics")
            return topics

        } catch (e: Exception) {
            logger.error("Error mapping query to topics: ${e.message}", e)
            logger.warn("Falling back to using all topics")
            return availableTopics
        }
    }

    /**
     * Expand user query with Swedish insurance terminology for better semantic search
     */
    fun expandQueryWithInsuranceTerms(query: String): String {
        if (apiKey.isBlank()) {
            logger.warn("OpenAI API key not configured. Returning original query.")
            return query
        }

        try {
            val systemPrompt = """
                Du är en expert på svenska hemförsäkringar.
                Din uppgift: expandera användarens fråga med relevanta svenska försäkringstermer för bättre sökning.

                Regler:
                - Behåll originalfrågan och lägg till relevanta svenska försäkringstermer
                - Mappa scenarion till försäkringstermer:
                  * Tappade/råkade/av misstag/gick sönder → drulle, allrisk, olyckshändelse
                  * Stulet/borta/inbrott → stöld, inbrott, skadegörelse
                  * Brand/eld/rök → eldsvåda, brand, explosion
                  * Vatten/läcka → vattenläcka, vattenskada
                  * Resa/utomlands → reseskydd
                - Svara ENDAST med den expanderade frågan, ingen förklaring
                - Max 50 ord totalt
            """.trimIndent()

            val request = mapOf(
                "model" to "gpt-4o-mini",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to query)
                ),
                "temperature" to 0.3,
                "max_tokens" to 100
            )

            val response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map::class.java)
                .block()

            @Suppress("UNCHECKED_CAST")
            val choices = response?.get("choices") as? List<Map<String, Any>>
            val expandedQuery = (choices?.get(0)?.get("message") as? Map<String, Any>)?.get("content") as? String

            return expandedQuery?.trim() ?: query

        } catch (e: Exception) {
            logger.error("Error expanding query: ${e.message}", e)
            return query
        }
    }

    /**
     * Uses OpenAI to intelligently chunk insurance document text with metadata
     * Uses JSON mode for structured output
     */
    fun generateStructuredChunks(fullText: String, documentName: String): String {
        if (apiKey.isBlank()) {
            throw IllegalStateException("OpenAI API key not configured")
        }

        try {
            val systemPrompt = """
                Du är en expert på att analysera svenska försäkringsdokument.
                Din uppgift är att dela upp texten i meningsfulla chunks med metadata.

                VIKTIGA REGLER:
                1. Dela upp tabeller/jämförelser i SEPARATA chunks (en per försäkringstyp: Bas, Standard, Max)
                2. Varje chunk = EN komplett semantisk enhet (inte avbruten mitt i en regel)
                3. Optimal chunk-storlek: 300-700 tokens
                4. Markera begränsningar/undantag med "isNegativeContext": true
                5. Om en sektion gäller alla planer, använd "plan": "All"
                6. Om en sektion är specifik för en plan (Bas/Standard/Max), använd den planen

                EXEMPEL PÅ CHUNKING:

                Om texten innehåller en tabell:
                ```
                Vattenläcka:
                Bas: 50 000 kr, självrisk 5 000 kr
                Standard: 100 000 kr, självrisk 2 500 kr
                Max: 200 000 kr, självrisk 1 000 kr
                ```

                Ska delas till 3 chunks:
                1. {"content": "Vattenläcka - Bas: Maximal ersättning 50 000 kr, självrisk 5 000 kr", "plan": "Bas", "moment": "Vattenläcka", "documentSection": "Vad ersätts"}
                2. {"content": "Vattenläcka - Standard: Maximal ersättning 100 000 kr, självrisk 2 500 kr", "plan": "Standard", "moment": "Vattenläcka", "documentSection": "Vad ersätts"}
                3. {"content": "Vattenläcka - Max: Maximal ersättning 200 000 kr, självrisk 1 000 kr", "plan": "Max", "moment": "Vattenläcka", "documentSection": "Vad ersätts"}

                Metadata-fält:
                - plan: "Bas" | "Standard" | "Max" | "All" (använd "All" om det gäller alla planer)
                - moment: "Stöld" | "Brand" | "Vattenläcka" | "Reseskydd" | "Ansvar" | etc (skadetyp)
                - locationScope: "I bostaden" | "Utanför bostaden" | "På resa" | "Förvarad i bil" | null
                - documentSection: "Vad ersätts" | "Begränsningar" | "Undantag" | "Säkerhetsföreskrifter" | "Hur man anmäler skada" | etc
                - appliesTo: "Egendom" | "Person" | "Bostad" | null
                - isNegativeContext: true (för undantag/begränsningar), false (för täckning)

                Svara ENDAST med giltig JSON i detta format:
                {
                  "chunks": [
                    {
                      "content": "full chunk text",
                      "plan": "Bas",
                      "moment": "Stöld",
                      "locationScope": "I bostaden",
                      "documentSection": "Vad ersätts",
                      "appliesTo": "Egendom",
                      "isNegativeContext": false
                    }
                  ]
                }
            """.trimIndent()

            val userMessage = "Analysera och dela upp följande försäkringstext:\n\n$fullText"

            val request = mapOf(
                "model" to "gpt-4o-2024-08-06",  // Supports structured outputs
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userMessage)
                ),
                "response_format" to mapOf("type" to "json_object"),
                "temperature" to 0.3  // Lower for more deterministic chunking
            )

            logger.info("Sending structured chunking request to OpenAI (document: $documentName)")

            val response = webClient.post()
                .uri("/chat/completions")
                .bodyValue(request)
                .retrieve()
                .onStatus({ status -> status.isError }) { clientResponse ->
                    clientResponse.bodyToMono(String::class.java).map { body ->
                        logger.error("OpenAI API error: Status=${clientResponse.statusCode()}, Body=$body")
                        RuntimeException("OpenAI API error: ${clientResponse.statusCode()} - $body")
                    }
                }
                .bodyToMono(Map::class.java)
                .block()

            @Suppress("UNCHECKED_CAST")
            val choices = response?.get("choices") as? List<Map<String, Any>>
            val messageContent = (choices?.get(0)?.get("message") as? Map<String, Any>)?.get("content") as? String

            if (messageContent.isNullOrBlank()) {
                throw RuntimeException("Empty response from OpenAI structured chunking")
            }

            logger.info("Successfully received structured chunks from OpenAI")
            return messageContent

        } catch (e: Exception) {
            logger.error("Error in structured chunking: ${e.message}", e)
            throw RuntimeException("Failed to generate structured chunks: ${e.message}", e)
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
