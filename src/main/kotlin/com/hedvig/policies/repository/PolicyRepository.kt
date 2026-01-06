package com.hedvig.policies.repository

import com.hedvig.policies.domain.Policy
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface PolicyRepository : JpaRepository<Policy, Long> {

    @Query("""
        SELECT p FROM Policy p
        WHERE p.insurance.personalNumber = :personalNumber
        AND p.startDate <= :date
        AND (p.endDate IS NULL OR p.endDate > :date)
        ORDER BY p.startDate DESC
    """)
    fun findPoliciesByPersonalNumberAndDate(
        @Param("personalNumber") personalNumber: String,
        @Param("date") date: LocalDate
    ): List<Policy>

    @Query("""
        SELECT p FROM Policy p
        WHERE p.insurance.id = :insuranceId
        AND p.startDate <= :date
        AND (p.endDate IS NULL OR p.endDate > :date)
        ORDER BY p.startDate DESC
    """)
    fun findPolicyByInsuranceIdAndDate(
        @Param("insuranceId") insuranceId: Long,
        @Param("date") date: LocalDate
    ): Policy?

    fun findByInsuranceIdOrderByVersionDesc(insuranceId: Long): List<Policy>

    @Query("""
        SELECT COALESCE(MAX(p.version), 0) FROM Policy p
        WHERE p.insurance.id = :insuranceId
    """)
    fun findMaxVersionByInsuranceId(@Param("insuranceId") insuranceId: Long): Int
}
