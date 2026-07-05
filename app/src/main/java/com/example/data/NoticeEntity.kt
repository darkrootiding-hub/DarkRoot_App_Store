package com.example.data

import java.io.Serializable

data class NoticeEntity(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val imageUrl: String = "",
    val timestamp: Long = 0L,
    val targetAppId: String = "all",
    val isRead: Boolean = false
) : Serializable
