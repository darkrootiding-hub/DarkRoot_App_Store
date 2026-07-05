package com.example.data

import java.io.Serializable

data class TermsAgreementEntity(
    val id: String = "",
    val userEmail: String = "",
    val userName: String = "",
    val timestamp: Long = 0L,
    val version: String = "v1"
) : Serializable
