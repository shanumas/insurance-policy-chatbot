package com.hedvig.policies.dto

import com.hedvig.policies.domain.Policy
import java.time.LocalDate

data class CreateInsuranceRequest(
    val personalNumber: String,
    val address: String,
    val postalCode: String,
    val startDate: LocalDate
)

data class UpdatePolicyRequest(
    val address: String,
    val postalCode: String,
    val startDate: LocalDate
)

data class PolicyResponse(
    val id: Long,
    val personalNumber: String,
    val address: String,
    val postalCode: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val version: Int
) {
    companion object {
        fun from(policy: Policy): PolicyResponse {
            val policyId = policy.id ?: throw IllegalStateException("Policy ID cannot be null")
            return PolicyResponse(
                id = policyId,
                personalNumber = policy.insurance.personalNumber,
                address = policy.address,
                postalCode = policy.postalCode,
                startDate = policy.startDate,
                endDate = policy.endDate,
                version = policy.version
            )
        }
    }
}

data class InsuranceResponse(
    val id: Long,
    val personalNumber: String,
    val policies: List<PolicyResponse>
)
