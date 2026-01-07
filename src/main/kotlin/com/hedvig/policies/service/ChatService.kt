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
        private const val TOP_K = 3  // Reduced since hybrid search is more targeted
        private const val MIN_SIMILARITY = 0.6
        private const val MAX_CONTEXT_LENGTH = 8000  // Increased for complete topic coverage
    }

    private fun sanitizeMessage(message: String, personnummer: String?): String {
        if (personnummer == null) return message

        // Replace personnummer with placeholder to avoid OpenAI refusing to process it
        var sanitized = message.replace(personnummer, "[PERSONNUMMER]")

        // Also try to replace formatted version (YYYYMMDD-XXXX)
        val formatted = formatPersonnummer(personnummer)
        sanitized = sanitized.replace(formatted, "[PERSONNUMMER]")

        return sanitized
    }

    private fun buildContextualizedQuery(
        currentMessage: String,
        conversationHistory: List<ChatMessage>
    ): String {
        // If no history or history is short, just use current message
        if (conversationHistory.size < 2) {
            return currentMessage
        }

        // Get last 3 exchanges (6 messages) for context
        val recentHistory = conversationHistory.takeLast(6)

        // Build a contextualized query by combining recent context with current message
        val historyContext = recentHistory.joinToString(" ") {
            if (it.role == "user") it.content else ""
        }.trim()

        // If current message is short/vague, prepend with history context
        return if (currentMessage.length < 50 && historyContext.isNotEmpty()) {
            "$historyContext. $currentMessage"
        } else {
            currentMessage
        }
    }

    fun chat(request: ChatRequest): ChatResponse {
        val conversationId = request.conversationId ?: UUID.randomUUID().toString()

        logger.info("Processing chat request for conversation: $conversationId")
        logger.debug("User message: ${request.message}")

        // Get or create conversation history
        val conversationHistory = conversations.getOrPut(conversationId) { mutableListOf() }

        // Try to extract personnummer from the message
        val personnummer = PersonnummerExtractor.extractPersonnummer(request.message)
        val isNewPersonnummer = personnummer != null && conversationPersonnummer[conversationId] != personnummer

        if (personnummer != null) {
            conversationPersonnummer[conversationId] = personnummer
            logger.info("Detected and stored personnummer: $personnummer for conversation: $conversationId")
        } else {
            logger.debug("No personnummer found in message: ${request.message}")
        }

        // Get stored personnummer for this conversation (if any)
        val storedPersonnummer = conversationPersonnummer[conversationId]

        // Sanitize message by replacing personnummer with placeholder
        val sanitizedMessage = sanitizeMessage(request.message, storedPersonnummer)
        logger.debug("Sanitized message: $sanitizedMessage")

        // Fetch user insurance details if we have a personnummer
        var insuranceNotFound = false
        val userInsurance = storedPersonnummer?.let {
            try {
                logger.info("Fetching insurance for personnummer: $it")
                val insurance = policyService.getInsuranceByPersonalNumber(it)
                logger.info("Successfully fetched insurance for ${insurance.customerName} with plan ${insurance.policyType}")
                insurance
            } catch (e: Exception) {
                logger.warn("Could not fetch insurance for personnummer $it: ${e.message}")
                insuranceNotFound = true
                null
            }
        }

        // If personnummer was just provided but no insurance found, inform the user
        if (isNewPersonnummer && insuranceNotFound) {
            val notFoundMessage = """
                Tack för att du delar ditt personnummer!

                Jag kunde tyvärr inte hitta någon försäkring kopplad till personnummer ${formatPersonnummer(personnummer!!)}.

                Detta kan bero på att:
                - Du inte har en aktiv försäkring hos Hedvig än
                - Personnumret kan vara felstavat
                - Det finns en teknisk fördröjning i systemet

                Vill du att jag hjälper dig med:
                - Allmän information om våra hemförsäkringar?
                - Hur du tecknar en ny försäkring?
            """.trimIndent()

            conversationHistory.add(ChatMessage(role = "user", content = sanitizedMessage))
            conversationHistory.add(ChatMessage(role = "assistant", content = notFoundMessage))

            return ChatResponse(
                message = notFoundMessage,
                conversationId = conversationId,
                sources = emptyList()
            )
        }

        // If personnummer was just provided and insurance WAS found, acknowledge it
        if (isNewPersonnummer && userInsurance != null) {
            val welcomeMessage = """
                Tack för ditt personnummer! Jag har hittat din försäkring.

                Kund: ${userInsurance.customerName}
                Försäkringsnivå: ${userInsurance.policyType}
                Adress: ${userInsurance.policies.firstOrNull { it.endDate == null }?.address ?: "Okänd"}

                Hur kan jag hjälpa dig med din försäkring?
            """.trimIndent()

            conversationHistory.add(ChatMessage(role = "user", content = sanitizedMessage))
            conversationHistory.add(ChatMessage(role = "assistant", content = welcomeMessage))

            return ChatResponse(
                message = welcomeMessage,
                conversationId = conversationId,
                sources = emptyList()
            )
        }

        // Check if the query needs clarification
        if (shouldAskClarification(sanitizedMessage, conversationHistory)) {
            logger.info("Query needs clarification, generating follow-up questions")
            val clarificationResponse = generateClarificationQuestions(sanitizedMessage, userInsurance)

            if (clarificationResponse != null) {
                // Update conversation history with clarification
                conversationHistory.add(ChatMessage(role = "user", content = sanitizedMessage))
                conversationHistory.add(ChatMessage(role = "assistant", content = clarificationResponse))

                return ChatResponse(
                    message = clarificationResponse,
                    conversationId = conversationId,
                    sources = emptyList()
                )
            }
        }

        // Build contextualized query using conversation history
        val searchQuery = buildContextualizedQuery(sanitizedMessage, conversationHistory)
        logger.debug("Search query (with context): $searchQuery")

        // Search for relevant policy chunks using hybrid search (AI topic mapping + filtering + ranking)
        val userPlan = userInsurance?.policyType?.name  // Get user's plan (Bas/Standard/Max)
        val searchResults = vectorSearchService.searchHybrid(
            query = searchQuery,
            userPlan = userPlan,
            topK = TOP_K,
            minSimilarity = MIN_SIMILARITY
        )

        logger.info("Hybrid search found ${searchResults.size} relevant chunks for plan=$userPlan")

        // Build context from search results and user data
        val context = buildContext(searchResults, userInsurance)

        // Build messages for the chat API
        val messages = buildMessages(conversationHistory, sanitizedMessage, context, userInsurance)

        // Get response from OpenAI
        val chatResult = try {
            openAIService.chat(messages, model = "gpt-4o-mini", maxTokens = 500)
        } catch (e: Exception) {
            logger.error("Error getting chat response: ${e.message}", e)

            // Return a helpful error message based on the exception
            val errorMessage = when {
                e.message?.contains("401") == true || e.message?.contains("authentication") == true ->
                    "Jag kan inte ansluta till AI-tjänsten (ogiltig API-nyckel). Vänligen kontakta supporten."
                e.message?.contains("429") == true || e.message?.contains("rate limit") == true ->
                    "AI-tjänsten är överbelastad just nu. Vänligen försök igen om ett ögonblick."
                e.message?.contains("timeout") == true || e.message?.contains("connection") == true ->
                    "Jag kan inte nå AI-tjänsten för tillfället. Vänligen kontrollera din internetanslutning."
                else ->
                    "Jag kan tyvärr inte svara på din fråga just nu. Vänligen försök igen senare eller kontakta supporten."
            }
            com.hedvig.policies.dto.ChatResult(content = errorMessage, confidence = 0.0)
        }

        val assistantMessage = chatResult.content
        val confidence = chatResult.confidence

        // Update conversation history
        conversationHistory.add(ChatMessage(role = "user", content = sanitizedMessage))
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

        logger.info("Chat response generated successfully with confidence: ${"%.1f".format(confidence * 100)}%")

        return ChatResponse(
            message = assistantMessage,
            conversationId = conversationId,
            confidence = confidence,
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
        context: String,
        userInsurance: com.hedvig.policies.dto.InsuranceResponse? = null
    ): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // System message with instructions
        messages.add(ChatMessage(
            role = "system",
            content = """
                Du är en AI-assistent för Hedvig hemförsäkringar. Du ÄR kundservice.

                VIKTIGT: Håll ALLTID svaret under 80 ord. Var koncis och tydlig.

                REGLER:
                1. Svara baserat på den tillhandahållna kontexten från försäkringsvillkoren
                2. Om kunden har en försäkring, svara för deras försäkringsnivå (${userInsurance?.policyType ?: "okänd"})
                3. Om specifik information (t.ex. exakta belopp, procentsatser) INTE finns i kontexten → säg det ärligt
                4. Om allmän information finns men detaljer saknas → ge den allmänna informationen + säg vilka detaljer som saknas
                5. Nämn ALDRIG att kunden ska kontakta kundservice på eget initiativ - du är kundservice
                6. Om kunden uttryckligen ber om att prata med en människa → hänvisa dem till example@example.com

                Försäkringsnivåer (hierarkiska):
                - Bas: Grundskydd (täcker det som står i kontexten för "Bas")
                - Standard: Bas + extra funktioner
                - Max: Standard + ytterligare extra funktioner

                Svarsstil:
                - Svara alltid på svenska
                - MAX 80 ord per svar
                - Var hjälpsam och informativ
                - Om begränsningar eller undantag nämns i kontexten → ta med dem
                - Om belopp/procent finns → citera dem

                Tillgänglig kontext från försäkringsvillkoren:
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
        // Disable clarification for now - hybrid search with topic mapping handles specificity better
        return false

        // Original logic kept for reference:
        /*
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
            "explosion", "storm", "översvämning", "rån", "glasskada", "eldsvåda"
        ).any { message.contains(it, ignoreCase = true) }

        // Don't ask for clarification if user is clearly responding to a previous question
        val isLikelyResponse = conversationHistory.isNotEmpty() &&
            conversationHistory.last().role == "assistant" &&
            conversationHistory.last().content.contains("?")

        return isAmbiguous && !hasSpecificIncident && !isLikelyResponse
        */
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

            val result = openAIService.chat(clarificationMessages, model = "gpt-4o-mini", maxTokens = 200)

            return if (result.content.contains("INGEN_CLARIFICATION", ignoreCase = true)) {
                null
            } else {
                result.content.trim()
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
