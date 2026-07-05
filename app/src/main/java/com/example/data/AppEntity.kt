package com.example.data

import java.io.Serializable

data class AppEntity(
    val id: String,
    val name: String,
    val developer: String,
    val version: String,
    val size: String,
    val category: String,
    val rating: String,
    val description: String,
    val logo: String,
    val screenshots: String, // Comma separated URLs
    val apkUrl: String,
    val packageName: String,
    val isFeatured: Boolean = false,
    val isPremium: Boolean = false,
    val price: String = "",
    val isUpcoming: Boolean = false,
    val isPopular: Boolean = false,
    val isRecent: Boolean = false,
    val versionCode: Int = 1,
    val isApproved: Boolean = true,
    val submittedBy: String = "",
    val hasAds: Boolean = false,
    val isSuspended: Boolean = false,
    val suspensionReason: String = "",
    val reportsJson: String = "" // Semicolon or comma-separated user reports
) : Serializable
