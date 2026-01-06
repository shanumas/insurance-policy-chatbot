package com.hedvig.policies.controller

import com.hedvig.policies.dto.PolicyChunkDto
import com.hedvig.policies.service.JsonStorageService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/policy-terms")
class PolicyTermsController(
    private val jsonStorageService: JsonStorageService
) {

    @GetMapping("/{documentName}")
    fun getTerms(@PathVariable documentName: String): ResponseEntity<List<PolicyChunkDto>> {
        val chunksStorage = jsonStorageService.loadChunks()
        if (chunksStorage == null) {
            return ResponseEntity.notFound().build()
        }

        val chunks = chunksStorage.chunks.filter { it.documentName == documentName }
        return if (chunks.isEmpty()) {
            ResponseEntity.notFound().build()
        } else {
            ResponseEntity.ok(chunks)
        }
    }

    @GetMapping("/{documentName}/search")
    fun searchTerms(
        @PathVariable documentName: String,
        @RequestParam keyword: String
    ): ResponseEntity<List<PolicyChunkDto>> {
        val chunksStorage = jsonStorageService.loadChunks()
        if (chunksStorage == null) {
            return ResponseEntity.ok(emptyList())
        }

        val chunks = chunksStorage.chunks
            .filter { it.documentName == documentName }
            .filter { it.content.contains(keyword, ignoreCase = true) }

        return ResponseEntity.ok(chunks)
    }

    @GetMapping("/{documentName}/count")
    fun getChunkCount(@PathVariable documentName: String): ResponseEntity<Map<String, Int>> {
        val chunksStorage = jsonStorageService.loadChunks()
        if (chunksStorage == null) {
            return ResponseEntity.ok(mapOf("count" to 0))
        }

        val count = chunksStorage.chunks.count { it.documentName == documentName }
        return ResponseEntity.ok(mapOf("count" to count))
    }
}
