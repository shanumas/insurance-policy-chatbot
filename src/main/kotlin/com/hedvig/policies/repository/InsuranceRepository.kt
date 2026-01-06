package com.hedvig.policies.repository

import com.hedvig.policies.domain.Insurance
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface InsuranceRepository : JpaRepository<Insurance, Long> {
    fun findByPersonalNumber(personalNumber: String): Optional<Insurance>
    fun existsByPersonalNumber(personalNumber: String): Boolean
}
