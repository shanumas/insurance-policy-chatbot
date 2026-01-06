package com.hedvig.policies.controller

import com.hedvig.policies.dto.ChatRequest
import com.hedvig.policies.dto.ChatResponse
import com.hedvig.policies.service.ChatService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService
) {

    @PostMapping
    fun chat(@RequestBody request: ChatRequest): ResponseEntity<ChatResponse> {
        return try {
            val response = chatService.chat(request)
            ResponseEntity.ok(response)
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @DeleteMapping("/{conversationId}")
    fun clearConversation(@PathVariable conversationId: String): ResponseEntity<Void> {
        chatService.clearConversation(conversationId)
        return ResponseEntity.noContent().build()
    }
}
