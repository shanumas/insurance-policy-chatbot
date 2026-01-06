package com.hedvig.policies.service

import com.hedvig.policies.dto.ChatMessage
import com.hedvig.policies.dto.ChatRequest
import com.hedvig.policies.dto.ChatResponse
import com.hedvig.policies.dto.SourceReference
import com.hedvig.policies.util.PersonnummerExtractor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service
class ChatService(
    private val vectorSearchService: VectorSearchService,
    private val openAIService: OpenAIService,
    private val policyService: PolicyService
) {
    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    // In-memory conversation storage (consider using database for production)
    private val conversations = ConcurrentHashMap<String, MutableList<ChatMessage>>()

    // Store personnummer for each conversation
    private val conversationPersonnummer = ConcurrentHashMap<String, String>()

    companion object {
        private const val DOCUMENT_NAME = "home-insurance-terms"
        private const val TOP_K = 5
        private const val MIN_SIMILARITY = 0.6
        private const val MAX_CONTEXT_LENGTH = 2000
    }

    fun chat(request: ChatRequest): ChatResponse {
        val conversationId = request.conversationId ?: UUID.randomUUID().toString()

        logger.info("Processing chat request for conversation: $conversationId")
        logger.debug("User message: ${request.message}")

        // Get or create conversation history
        val conversationHistory = conversations.getOrPut(conversationId) { mutableListOf() }

        // Try to extract personnummer from the message
        val personnummer = PersonnummerExtractor.extractPersonnummer(request.message)
        if (personnummer != null) {
            conversationPersonnummer[conversationId] = personnummer
            logger.info("Detected personnummer for conversation: $conversationId")
        }

        // Get stored personnummer for this conversation (if any)
        val storedPersonnummer = conversationPersonnummer[conversationId]

        // Fetch user insurance details if we have a personnummer
        val userInsurance = storedPersonnummer?.let {
            try {
                policyService.getInsuranceByPersonalNumber(it)
            } catch (e: Exception) {
                logger.warn("Could not fetch insurance for personnummer: ${e.message}")
                null
            }
        }

        // Search for relevant policy chunks
        val searchResults = vectorSearchService.searchAllDocuments(
            query = request.message,
            topK = TOP_K
        ).filter { it.similarity >= MIN_SIMILARITY }

        logger.info("Found ${searchResults.size} relevant chunks")

        // Build context from search results and user data
        val context = buildContext(searchResults, userInsurance)

        // Build messages for the chat API
        val messages = buildMessages(conversationHistory, request.message, context)

        // Get response from OpenAI
        val assistantMessage = try {
            openAIService.chat(messages, model = "gpt-4o-mini", maxTokens = 500)
        } catch (e: Exception) {
            logger.error("Error getting chat response: ${e.message}", e)

            // Return a helpful error message based on the exception
            when {
                e.message?.contains("401") == true || e.message?.contains("authentication") == true ->
                    "Jag kan inte ansluta till AI-tjänsten (ogiltig API-nyckel). Vänligen kontakta supporten."
                e.message?.contains("429") == true || e.message?.contains("rate limit") == true ->
                    "AI-tjänsten är överbelastad just nu. Vänligen försök igen om ett ögonblick."
                e.message?.contains("timeout") == true || e.message?.contains("connection") == true ->
                    "Jag kan inte nå AI-tjänsten för tillfället. Vänligen kontrollera din internetanslutning."
                else ->
                    "Jag kan tyvärr inte svara på din fråga just nu. Vänligen försök igen senare eller kontakta supporten."
            }
        }

        // Update conversation history
        conversationHistory.add(ChatMessage(role = "user", content = request.message))
        conversationHistory.add(ChatMessage(role = "assistant", content = assistantMessage))

        // Limit conversation history to last 10 messages
        if (conversationHistory.size > 10) {
            conversationHistory.removeAt(0)
            conversationHistory.removeAt(0)
        }

        // Build source references
        val sources = searchResults.map { result ->
            SourceReference(
                documentName = result.chunk.documentName,
                chunkIndex = result.chunk.chunkIndex,
                similarity = result.similarity,
                preview = result.chunk.content.take(150) + if (result.chunk.content.length > 150) "..." else ""
            )
        }

        logger.info("Chat response generated successfully")

        return ChatResponse(
            message = assistantMessage,
            conversationId = conversationId,
            sources = sources
        )
    }

    private fun buildContext(
        searchResults: List<VectorSearchService.SearchResult>,
        userInsurance: com.hedvig.policies.dto.InsuranceResponse? = null
    ): String {
        val contextBuilder = StringBuilder()

        // Add user-specific information if available
        if (userInsurance != null) {
            contextBuilder.append("KUNDINFORMATION:\n")
            contextBuilder.append("Personnummer: ${userInsurance.personalNumber}\n")

            // Get current active policy
            val currentPolicy = userInsurance.policies.firstOrNull { it.endDate == null }
            if (currentPolicy != null) {
                contextBuilder.append("Nuvarande försäkring:\n")
                contextBuilder.append("- Adress: ${currentPolicy.address}\n")
                contextBuilder.append("- Postnummer: ${currentPolicy.postalCode}\n")
                contextBuilder.append("- Startdatum: ${currentPolicy.startDate}\n")
                contextBuilder.append("- Version: ${currentPolicy.version}\n")
            }

            // List all policy versions
            if (userInsurance.policies.size > 1) {
                contextBuilder.append("\nTidigare versioner: ${userInsurance.policies.size - 1} st\n")
            }

            contextBuilder.append("\n")
        }

        // Add policy terms information
        if (searchResults.isEmpty()) {
            if (userInsurance == null) {
                contextBuilder.append("Ingen relevant information hittades i försäkringsvillkoren.")
            }
        } else {
            contextBuilder.append("RELEVANT INFORMATION FRÅN FÖRSÄKRINGSVILLKOREN:\n\n")

            var totalLength = contextBuilder.length
            for ((index, result) in searchResults.withIndex()) {
                val chunkText = result.chunk.content

                if (totalLength + chunkText.length > MAX_CONTEXT_LENGTH) {
                    break
                }

                contextBuilder.append("${index + 1}. ${chunkText}\n\n")
                totalLength += chunkText.length
            }
        }

        return contextBuilder.toString().trim()
    }

    private fun buildMessages(
        conversationHistory: List<ChatMessage>,
        userMessage: String,
        context: String
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // System message with instructions
        messages.add(ChatMessage(
            role = "system",
            content = """
                Du är en AI-assistent för Hedvig, ett svenskt försäkringsbolag.
                Din uppgift är att hjälpa kunder att förstå deras hemförsäkringsvillkor och ge personlig service.

                Riktlinjer:
                - Svara alltid på svenska
                - Var vänlig, professionell och hjälpsam
                - Basera dina svar på den kontext som tillhandahålls
                - Om kunden har angett sitt personnummer och du har tillgång till deras försäkringsinformation, använd denna för att ge personliga svar
                - När du känner till kundens adress eller försäkringsdetaljer, referera till dem naturligt i ditt svar
                - Om informationen inte finns i kontexten, säg det ärligt
                - Ge kortfattade och tydliga svar
                - Om du är osäker, rekommendera kunden att kontakta kundservice

                Tillgänglig kontext:
                $context
            """.trimIndent()
        ))

        // Add conversation history (skip first system message if it exists)
        messages.addAll(conversationHistory.filter { it.role != "system" })

        // Add current user message
        messages.add(ChatMessage(role = "user", content = userMessage))

        return messages
    }

    fun clearConversation(conversationId: String) {
        conversations.remove(conversationId)
        conversationPersonnummer.remove(conversationId)
        logger.info("Cleared conversation and personnummer for: $conversationId")
    }
}
