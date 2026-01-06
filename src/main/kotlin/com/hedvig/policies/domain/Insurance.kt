package com.hedvig.policies.domain

import jakarta.persistence.*
import java.time.Instant

enum class PolicyType {
    BAS,
    STANDARD,
    MAX
}

@Entity
@Table(name = "insurance")
data class Insurance(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "personal_number", nullable = false, unique = true)
    val personalNumber: String,

    @Column(name = "customer_name", nullable = false)
    val customerName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false)
    val policyType: PolicyType,

    @OneToMany(mappedBy = "insurance", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val policies: MutableList<Policy> = mutableListOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
