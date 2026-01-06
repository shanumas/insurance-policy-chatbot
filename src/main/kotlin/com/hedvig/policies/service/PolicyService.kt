package com.hedvig.policies.service

import com.hedvig.policies.domain.Insurance
import com.hedvig.policies.domain.Policy
import com.hedvig.policies.dto.CreateInsuranceRequest
import com.hedvig.policies.dto.InsuranceResponse
import com.hedvig.policies.dto.PolicyResponse
import com.hedvig.policies.dto.UpdatePolicyRequest
import com.hedvig.policies.repository.InsuranceRepository
import com.hedvig.policies.repository.PolicyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional
class PolicyService(
    private val insuranceRepository: InsuranceRepository,
    private val policyRepository: PolicyRepository
) {

    fun createInsurance(request: CreateInsuranceRequest): InsuranceResponse {
        if (insuranceRepository.existsByPersonalNumber(request.personalNumber)) {
            throw IllegalArgumentException("Insurance already exists for personnummer: ${request.personalNumber}")
        }

        val insurance = Insurance(personalNumber = request.personalNumber)
        val savedInsurance = insuranceRepository.save(insurance)

        val insuranceId = savedInsurance.id ?: throw IllegalStateException("Insurance ID cannot be null after save")

        val policy = Policy(
            insurance = savedInsurance,
            address = request.address,
            postalCode = request.postalCode,
            startDate = request.startDate,
            version = 1
        )
        policyRepository.save(policy)

        return getInsurance(insuranceId)
    }

    fun updatePolicy(personalNumber: String, request: UpdatePolicyRequest): InsuranceResponse {
        val insurance = insuranceRepository.findByPersonalNumber(personalNumber)
            .orElseThrow { IllegalArgumentException("No insurance found for personnummer: $personalNumber") }

        val insuranceId = insurance.id ?: throw IllegalStateException("Insurance ID cannot be null")

        val currentVersion = policyRepository.findMaxVersionByInsuranceId(insuranceId)
        val newVersion = currentVersion + 1

        // End the current active policy
        val activePolicies = policyRepository.findByInsuranceIdOrderByVersionDesc(insuranceId)
            .filter { it.endDate == null }

        activePolicies.forEach { activePolicy ->
            val endedPolicy = activePolicy.copy(endDate = request.startDate.minusDays(1))
            policyRepository.save(endedPolicy)
        }

        // Create new policy version
        val newPolicy = Policy(
            insurance = insurance,
            address = request.address,
            postalCode = request.postalCode,
            startDate = request.startDate,
            version = newVersion
        )
        policyRepository.save(newPolicy)

        return getInsurance(insuranceId)
    }

    fun getInsurance(insuranceId: Long): InsuranceResponse {
        val insurance = insuranceRepository.findById(insuranceId)
            .orElseThrow { IllegalArgumentException("Insurance not found with id: $insuranceId") }

        val policies = policyRepository.findByInsuranceIdOrderByVersionDesc(insuranceId)
            .map { PolicyResponse.from(it) }

        return InsuranceResponse(
            id = insuranceId,
            personalNumber = insurance.personalNumber,
            policies = policies
        )
    }

    fun getInsuranceByPersonalNumber(personalNumber: String): InsuranceResponse {
        val insurance = insuranceRepository.findByPersonalNumber(personalNumber)
            .orElseThrow { IllegalArgumentException("No insurance found for personnummer: $personalNumber") }
        val insuranceId = insurance.id ?: throw IllegalStateException("Insurance ID cannot be null")
        return getInsurance(insuranceId)
    }

    fun getPoliciesByPersonalNumberAndDate(personalNumber: String, date: LocalDate): List<PolicyResponse> {
        return policyRepository.findPoliciesByPersonalNumberAndDate(personalNumber, date)
            .map { PolicyResponse.from(it) }
    }

    fun getPolicyByInsuranceIdAndDate(insuranceId: Long, date: LocalDate): PolicyResponse? {
        return policyRepository.findPolicyByInsuranceIdAndDate(insuranceId, date)
            ?.let { PolicyResponse.from(it) }
    }

    fun getAllInsurances(): List<InsuranceResponse> {
        return insuranceRepository.findAll().map { insurance ->
            val insuranceId = insurance.id ?: throw IllegalStateException("Insurance ID cannot be null")
            InsuranceResponse(
                id = insuranceId,
                personalNumber = insurance.personalNumber,
                policies = policyRepository.findByInsuranceIdOrderByVersionDesc(insuranceId)
                    .map { PolicyResponse.from(it) }
            )
        }
    }
}
