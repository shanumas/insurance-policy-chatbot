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
        var insuranceNotFound = false
        val userInsurance = storedPersonnummer?.let {
            try {
                policyService.getInsuranceByPersonalNumber(it)
            } catch (e: Exception) {
                logger.warn("Could not fetch insurance for personnummer: ${e.message}")
                insuranceNotFound = true
                null
            }
        }

        // If personnummer was just provided but no insurance found, inform the user
        if (personnummer != null && insuranceNotFound) {
            val notFoundMessage = """
                Tack för att du delar ditt personnummer!

                Jag kunde tyvärr inte hitta någon försäkring kopplad till personnummer ${formatPersonnummer(personnummer)}.

                Detta kan bero på att:
                - Du inte har en aktiv försäkring hos Hedvig än
                - Personnumret kan vara felstavat
                - Det finns en teknisk fördröjning i systemet

                Vill du att jag hjälper dig med:
                - Allmän information om våra hemförsäkringar?
                - Hur du tecknar en ny försäkring?
                - Kontaktinformation till kundservice för att registrera din försäkring?
            """.trimIndent()

            conversationHistory.add(ChatMessage(role = "user", content = request.message))
            conversationHistory.add(ChatMessage(role = "assistant", content = notFoundMessage))

            return ChatResponse(
                message = notFoundMessage,
                conversationId = conversationId,
                sources = emptyList()
            )
        }

        // Check if the query needs clarification
        if (shouldAskClarification(request.message, conversationHistory)) {
            logger.info("Query needs clarification, generating follow-up questions")
            val clarificationResponse = generateClarificationQuestions(request.message, userInsurance)

            if (clarificationResponse != null) {
                // Update conversation history with clarification
                conversationHistory.add(ChatMessage(role = "user", content = request.message))
                conversationHistory.add(ChatMessage(role = "assistant", content = clarificationResponse))

                return ChatResponse(
                    message = clarificationResponse,
                    conversationId = conversationId,
                    sources = emptyList()
                )
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
            contextBuilder.append("Kundnamn: ${userInsurance.customerName}\n")
            contextBuilder.append("Försäkringstyp: ${userInsurance.policyType} (VIKTIGT: Svara baserat på denna försäkringsnivå)\n")
            contextBuilder.append("Antal försäkringsversioner: ${userInsurance.policies.size}\n")

            // Sort policies by start date to get chronological order
            val sortedPolicies = userInsurance.policies.sortedBy { it.startDate }

            // Get the first (oldest) policy
            val firstPolicy = sortedPolicies.firstOrNull()
            if (firstPolicy != null) {
                contextBuilder.append("\nFÖRSTA FÖRSÄKRING (Version ${firstPolicy.version}):\n")
                contextBuilder.append("- Startdatum: ${firstPolicy.startDate}\n")
                contextBuilder.append("- Adress: ${firstPolicy.address}\n")
                contextBuilder.append("- Postnummer: ${firstPolicy.postalCode}\n")
                if (firstPolicy.endDate != null) {
                    contextBuilder.append("- Slutdatum: ${firstPolicy.endDate}\n")
                }
            }

            // Get current active policy
            val currentPolicy = userInsurance.policies.firstOrNull { it.endDate == null }
            if (currentPolicy != null && currentPolicy != firstPolicy) {
                contextBuilder.append("\nNUVARANDE AKTIV FÖRSÄKRING (Version ${currentPolicy.version}):\n")
                contextBuilder.append("- Startdatum: ${currentPolicy.startDate}\n")
                contextBuilder.append("- Adress: ${currentPolicy.address}\n")
                contextBuilder.append("- Postnummer: ${currentPolicy.postalCode}\n")
            }

            // List all policy versions with details
            if (userInsurance.policies.size > 1) {
                contextBuilder.append("\nALLA FÖRSÄKRINGSVERSIONER (kronologisk ordning):\n")
                sortedPolicies.forEachIndexed { index, policy ->
                    val status = if (policy.endDate == null) "AKTIV" else "Avslutad"
                    contextBuilder.append("${index + 1}. Version ${policy.version} ($status)\n")
                    contextBuilder.append("   Start: ${policy.startDate}")
                    if (policy.endDate != null) {
                        contextBuilder.append(" → Slut: ${policy.endDate}")
                    }
                    contextBuilder.append("\n")
                    contextBuilder.append("   Adress: ${policy.address}, ${policy.postalCode}\n")
                }
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
                - VIKTIGT: Vi erbjuder tre försäkringsnivåer (BAS, STANDARD, MAX) med olika täckning
                - Om kunden har angett sitt personnummer och du har tillgång till deras försäkringstyp, ANVÄND DENNA information för att ge specifika svar
                - När du svarar om täckning eller ersättning, var tydlig med vilken försäkringsnivå svaret gäller för
                - Om villkoren skiljer sig mellan BAS, STANDARD och MAX, förklara skillnaderna
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

    private fun shouldAskClarification(message: String, conversationHistory: List<ChatMessage>): Boolean {
        // Keywords that often indicate ambiguous questions about compensation/coverage
        val ambiguousPatterns = listOf(
            Regex("""ersättning.*bas""", RegexOption.IGNORE_CASE),
            Regex("""skada.*täckt""", RegexOption.IGNORE_CASE),
            Regex("""hur mycket.*få""", RegexOption.IGNORE_CASE),
            Regex("""vad täcker""", RegexOption.IGNORE_CASE),
            Regex("""täcker.*försäkring""", RegexOption.IGNORE_CASE)
        )

        // Check if message matches ambiguous patterns and lacks specific context
        val isAmbiguous = ambiguousPatterns.any { it.containsMatchIn(message) }

        // Check if the message lacks specific incident type
        val hasSpecificIncident = listOf(
            "brand", "stöld", "inbrott", "vatten", "läckage", "skadegörelse",
            "explosion", "storm", "översvämning", "rån", "glasskada"
        ).any { message.contains(it, ignoreCase = true) }

        // Don't ask for clarification if user is clearly responding to a previous question
        val isLikelyResponse = conversationHistory.isNotEmpty() &&
            conversationHistory.last().role == "assistant" &&
            conversationHistory.last().content.contains("?")

        return isAmbiguous && !hasSpecificIncident && !isLikelyResponse
    }

    private fun generateClarificationQuestions(
        message: String,
        userInsurance: com.hedvig.policies.dto.InsuranceResponse?
    ): String? {
        try {
            val prompt = """
                Analysera följande kundfråga och avgör om den behöver förtydligande.

                Kundfråga: "$message"
                ${if (userInsurance != null) "Kunden har en försäkring på ${userInsurance.policies.firstOrNull()?.address}" else ""}

                Om frågan är för vag eller allmän (t.ex. frågar om ersättning utan att specificera typ av skada),
                generera 2-3 konkreta följdfrågor för att förtydliga. Fokusera på:
                - Typ av skada (brand, vatten, stöld, etc.)
                - Vilken typ av egendom som skadats
                - Om det är akut eller något som redan hänt

                Svara ENDAST med förtydligande frågor på svenska, eller svara "INGEN_CLARIFICATION" om frågan är tillräckligt specifik.

                Exempel på bra förtydligande:
                "Jag skulle gärna hjälpa dig med information om ersättning! För att ge dig rätt information behöver jag veta:

                - Vilken typ av skada handlar det om? (t.ex. brand, vattenskada, stöld, inbrott)
                - Vad är det som har skadats?
                - Har skadan redan inträffat eller undrar du generellt?"
            """.trimIndent()

            val clarificationMessages = listOf(
                ChatMessage(role = "system", content = "Du är en hjälpsam assistent som ställer förtydligande frågor."),
                ChatMessage(role = "user", content = prompt)
            )

            val response = openAIService.chat(clarificationMessages, model = "gpt-4o-mini", maxTokens = 200)

            return if (response.contains("INGEN_CLARIFICATION", ignoreCase = true)) {
                null
            } else {
                response.trim()
            }

        } catch (e: Exception) {
            logger.error("Error generating clarification questions: ${e.message}", e)
            return null
        }
    }

    private fun formatPersonnummer(personnummer: String): String {
        // Format as YYYYMMDD-XXXX for readability
        return if (personnummer.length == 12) {
            "${personnummer.substring(0, 8)}-${personnummer.substring(8)}"
        } else {
            personnummer
        }
    }

    fun clearConversation(conversationId: String) {
        conversations.remove(conversationId)
        conversationPersonnummer.remove(conversationId)
        logger.info("Cleared conversation and personnummer for: $conversationId")
    }
}
