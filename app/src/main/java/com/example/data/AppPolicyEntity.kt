package com.example.data

import java.io.Serializable

data class AppPolicyEntity(
    val title: String = "DARK Store Terms & Community Agreement",
    val content: String = "I am responsible for the apps and content I upload.\nI will not upload malware, viruses, spyware, ransomware, or any harmful software.\nI will not upload apps that infringe copyrights, trademarks, or other intellectual property rights.\nI will not upload illegal, deceptive, or fraudulent content.\nI understand that every submitted app will be reviewed by the Dark Store team before publication.\nI understand that Dark Store may reject or remove any app that violates these terms.\nI will not impersonate another person, developer, or organization.\nI understand that my account may be suspended or permanently banned for repeated violations.\nI acknowledge that I download and install apps at my own discretion and responsibility.\nI agree to follow all Dark Store rules and future policy updates.",
    val lastUpdated: Long = 0L,
    val updatedBy: String = ""
) : Serializable
