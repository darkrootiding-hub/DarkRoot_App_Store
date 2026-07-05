package com.example.data

import java.io.Serializable

data class UpdateConfigEntity(
    val latestVersionCode: Int = 0,
    val latestVersionName: String = "",
    val apkDownloadUrl: String = "",
    val updateTitle: String = "",
    val updateMessage: String = "",
    val forceUpdate: Boolean = false
) : Serializable
