package com.hedvig.policies.domain

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "insurance")
data class Insurance(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "personal_number", nullable = false, unique = true)
    val personalNumber: String,

    @OneToMany(mappedBy = "insurance", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val policies: MutableList<Policy> = mutableListOf(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
