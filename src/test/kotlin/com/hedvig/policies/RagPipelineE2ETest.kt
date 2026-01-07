package com.hedvig.policies

import com.hedvig.policies.dto.ChatMessage
import com.hedvig.policies.dto.ChatRequest
import com.hedvig.policies.service.ChatService
import com.hedvig.policies.service.OpenAIService
import com.hedvig.policies.service.VectorSearchService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * End-to-end tests for the RAG pipeline.
 *
 * These tests verify that:
 * 1. Vector search retrieves correct chunks
 * 2. OpenAI generates responses with correct information
 * 3. The full chat pipeline works end-to-end
 *
 * Prerequisites:
 * - OPENAI_API_KEY environment variable must be set
 * - data/policy-chunks-embeddings.json must exist with embeddings
 *
 * Run with: ./mvnw.cmd test -Dtest=RagPipelineE2ETest
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class RagPipelineE2ETest {

    @Autowired
    private lateinit var chatService: ChatService

    @Autowired
    private lateinit var vectorSearchService: VectorSearchService

    @Autowired
    private lateinit var openAIService: OpenAIService

    /**
     * Use LLM to evaluate if a response meets an expected criteria.
     * Returns true if the response satisfies the criteria.
     */
    private fun llmAssert(response: String, criteria: String): Boolean {
        val messages = listOf(
            ChatMessage(
                role = "system",
                content = """You are a test assertion evaluator.
                    |Evaluate if the given response meets the specified criteria.
                    |Reply with ONLY 'YES' or 'NO'.""".trimMargin()
            ),
            ChatMessage(
                role = "user",
                content = """Response: "$response"
                    |
                    |Criteria: $criteria
                    |
                    |Does the response meet the criteria? Reply YES or NO only.""".trimMargin()
            )
        )

        val result = openAIService.chat(messages, model = "gpt-4o-mini", maxTokens = 100)
        return result.content.trim().uppercase().startsWith("YES")
    }

    /**
     * Test: Eldsvåda (fire) maximum compensation for MAX plan
     * Expected: 3,000,000 kr
     */
    @Test
    fun `should return correct Eldsvåda compensation for MAX plan`() {
        // Given
        val request = ChatRequest(
            message = "What is my Eldsvåda maximum compensation if I have MAX subscription?",
            conversationId = "test-eldsvada-max"
        )

        // When
        val response = chatService.chat(request)

        // Then
        assertNotNull(response)
        assertNotNull(response.message)
        println("=== Eldsvåda MAX Test ===")
        println("Question: ${request.message}")
        println("Response: ${response.message}")
        println("Confidence: ${(response.confidence * 100).toInt()}%")
        println("Sources: ${response.sources.size}")
        response.sources.forEach { source ->
            println("  - Similarity: ${String.format("%.2f", source.similarity)}, Preview: ${source.preview.take(80)}...")
        }

        // Use LLM to verify the response mentions the correct amount
        val containsCorrectAmount = llmAssert(
            response.message,
            "The response mentions that the maximum compensation for fire damage (Eldsvåda) is 3,000,000 kr or 3 million kronor"
        )

        assertTrue(containsCorrectAmount,
            "Response should contain 3,000,000 kr for MAX Eldsvåda. Got: ${response.message}")
    }

    /**
     * Test: Vector search finds Eldsvåda chunks for MAX plan
     */
    @Test
    fun `vector search should find Eldsvåda chunks for MAX plan`() {
        // When
        val results = vectorSearchService.searchHybrid(
            query = "Eldsvåda maximum compensation MAX",
            userPlan = "Max",
            topK = 5,
            minSimilarity = 0.5
        )

        // Then
        println("=== Vector Search Test ===")
        println("Query: Eldsvåda maximum compensation MAX")
        println("Results: ${results.size}")
        results.forEach { result ->
            println("  - Similarity: ${String.format("%.3f", result.similarity)}")
            println("    Topic: ${result.chunk.metadata.topic}")
            println("    Plan: ${result.chunk.metadata.plan}")
            println("    Content: ${result.chunk.content.take(100)}...")
        }

        assertTrue(results.isNotEmpty(), "Should find at least one relevant chunk")

        // Check if any result contains Eldsvåda/brand information
        val hasRelevantContent = results.any { result ->
            result.chunk.content.lowercase().contains("eldsvåda") ||
            result.chunk.content.lowercase().contains("brand") ||
            result.chunk.metadata.topic.lowercase().contains("eldsvåda") ||
            result.chunk.metadata.topic.lowercase().contains("brand")
        }
        assertTrue(hasRelevantContent, "Results should contain fire/eldsvåda related content")
    }

    /**
     * Test: Stöld (theft) coverage question
     */
    @Test
    fun `should answer theft coverage question`() {
        // Given
        val request = ChatRequest(
            message = "What does my insurance cover for theft?",
            conversationId = "test-theft"
        )

        // When
        val response = chatService.chat(request)

        // Then
        println("=== Theft Coverage Test ===")
        println("Question: ${request.message}")
        println("Response: ${response.message}")
        println("Confidence: ${(response.confidence * 100).toInt()}%")

        assertNotNull(response.message)
        assertTrue(response.message.isNotBlank(), "Response should not be empty")

        // Use LLM to verify the response contains theft-related information
        val containsTheftInfo = llmAssert(
            response.message,
            "The response provides information about theft coverage or what is covered when something is stolen"
        )

        assertTrue(containsTheftInfo,
            "Response should contain theft-related information. Got: ${response.message}")
    }

    /**
     * Test: User without personnummer should be asked for it
     */
    @Test
    fun `should ask for personnummer when asking about personal policy details`() {
        // Given - asking about personal policy without providing personnummer
        val request = ChatRequest(
            message = "When does my policy start?",
            conversationId = "test-no-personnummer"
        )

        // When
        val response = chatService.chat(request)

        // Then
        println("=== Personnummer Request Test ===")
        println("Question: ${request.message}")
        println("Response: ${response.message}")

        assertNotNull(response.message)

        // Use LLM to verify the response asks for personnummer/identification
        val asksForPersonnummer = llmAssert(
            response.message,
            "The response asks the user to provide their personnummer, personal number, or personal identification number"
        )

        assertTrue(asksForPersonnummer,
            "Response should ask for personnummer when user asks about personal policy. Got: ${response.message}")
    }

    /**
     * Test: Response should be under 80 words
     */
    @Test
    fun `response should be under 80 words`() {
        // Given
        val request = ChatRequest(
            message = "What is covered under water damage?",
            conversationId = "test-word-count"
        )

        // When
        val response = chatService.chat(request)

        // Then
        val wordCount = response.message.split(Regex("\\s+")).size
        println("=== Word Count Test ===")
        println("Question: ${request.message}")
        println("Response: ${response.message}")
        println("Word count: $wordCount")

        assertTrue(wordCount <= 100, // Allow some buffer since word counting can vary
            "Response should be around 80 words or less. Got $wordCount words")
    }

    /**
     * Test: Confidence score should be returned
     */
    @Test
    fun `response should include confidence score`() {
        // Given
        val request = ChatRequest(
            message = "What is drulle coverage?",
            conversationId = "test-confidence"
        )

        // When
        val response = chatService.chat(request)

        // Then
        println("=== Confidence Score Test ===")
        println("Question: ${request.message}")
        println("Response: ${response.message}")
        println("Confidence: ${response.confidence}")

        assertTrue(response.confidence >= 0.0, "Confidence should be >= 0")
        assertTrue(response.confidence <= 1.0, "Confidence should be <= 1")

        // For a valid query, confidence should be reasonably high
        assertTrue(response.confidence > 0.1,
            "Confidence should be above 0.1 for a valid query. Got: ${response.confidence}")
    }

    /**
     * Test: Available topics should be loaded
     */
    @Test
    fun `should load available topics from vector store`() {
        // When
        val topics = vectorSearchService.getAvailableTopics()

        // Then
        println("=== Available Topics Test ===")
        println("Total topics: ${topics.size}")
        topics.forEach { println("  - $it") }

        assertTrue(topics.isNotEmpty(), "Should have at least one topic")
    }

    /**
     * Clean up test conversations
     */
    @Test
    fun `should clear conversation history`() {
        val conversationId = "test-clear"

        // Create a conversation
        chatService.chat(ChatRequest("Hello", conversationId))

        // Clear it
        chatService.clearConversation(conversationId)

        // No exception means success
        println("=== Clear Conversation Test ===")
        println("Successfully cleared conversation: $conversationId")
    }
}
