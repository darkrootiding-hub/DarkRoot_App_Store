package com.example.data

import java.io.Serializable

data class UserEntity(
    val uid: String,
    val email: String,
    val displayName: String,
    val role: String, // "user" or "admin"
    val createdAt: Long = System.currentTimeMillis(),
    val fcmToken: String = "",
    val isDeveloper: Boolean = (role == "admin"),
    val devWebsite: String = "",
    val devGithub: String = "",
    val devName: String = "",
    val devBio: String = ""
) : Serializable
