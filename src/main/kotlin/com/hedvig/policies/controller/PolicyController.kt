package com.hedvig.policies.controller

import com.hedvig.policies.dto.*
import com.hedvig.policies.service.PolicyService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/policies")
class PolicyController(private val policyService: PolicyService) {

    @PostMapping("/insurances")
    fun createInsurance(@RequestBody request: CreateInsuranceRequest): ResponseEntity<InsuranceResponse> {
        return try {
            val response = policyService.createInsurance(request)
            ResponseEntity.status(HttpStatus.CREATED).body(response)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }

    @PutMapping("/insurances/{personalNumber}")
    fun updatePolicy(
        @PathVariable personalNumber: String,
        @RequestBody request: UpdatePolicyRequest
    ): ResponseEntity<InsuranceResponse> {
        return try {
            val response = policyService.updatePolicy(personalNumber, request)
            ResponseEntity.ok(response)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().build()
        }
    }

    @GetMapping("/insurances/{personalNumber}")
    fun getInsurance(@PathVariable personalNumber: String): ResponseEntity<InsuranceResponse> {
        return try {
            val response = policyService.getInsuranceByPersonalNumber(personalNumber)
            ResponseEntity.ok(response)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/insurances")
    fun getAllInsurances(): ResponseEntity<List<InsuranceResponse>> {
        val insurances = policyService.getAllInsurances()
        return ResponseEntity.ok(insurances)
    }

    @GetMapping
    fun getPoliciesByPersonalNumberAndDate(
        @RequestParam personalNumber: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate
    ): ResponseEntity<List<PolicyResponse>> {
        return try {
            val policies = policyService.getPoliciesByPersonalNumberAndDate(personalNumber, date)
            ResponseEntity.ok(policies)
        } catch (e: IllegalArgumentException) {
            ResponseEntity.notFound().build()
        }
    }

    @GetMapping("/by-insurance")
    fun getPolicyByInsuranceAndDate(
        @RequestParam insuranceId: Long,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate
    ): ResponseEntity<PolicyResponse> {
        return policyService.getPolicyByInsuranceIdAndDate(insuranceId, date)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }
}
