package com.hedvig.policies.controller

import com.hedvig.policies.domain.PolicyTermsChunk
import com.hedvig.policies.service.PdfParsingService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/policy-terms")
class PolicyTermsController(
    private val pdfParsingService: PdfParsingService
) {

    @GetMapping("/{documentName}")
    fun getTerms(@PathVariable documentName: String): ResponseEntity<List<PolicyTermsChunk>> {
        val chunks = pdfParsingService.getChunksByDocument(documentName)
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
    ): ResponseEntity<List<PolicyTermsChunk>> {
        val chunks = pdfParsingService.searchChunks(documentName, keyword)
        return ResponseEntity.ok(chunks)
    }

    @GetMapping("/{documentName}/count")
    fun getChunkCount(@PathVariable documentName: String): ResponseEntity<Map<String, Int>> {
        val chunks = pdfParsingService.getChunksByDocument(documentName)
        return ResponseEntity.ok(mapOf("count" to chunks.size))
    }
}
