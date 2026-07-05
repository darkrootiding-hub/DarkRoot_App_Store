package com.example.data

data class DownloadEntity(
    val id: String,
    val name: String,
    val packageName: String,
    val status: String, // "PENDING", "DOWNLOADING", "DOWNLOADED", "INSTALLING", "INSTALLED", "FAILED"
    val progress: Int = 0,
    val size: String = "0 MB",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadSpeed: String = "0 KB/s",
    val localFilePath: String? = null
)
