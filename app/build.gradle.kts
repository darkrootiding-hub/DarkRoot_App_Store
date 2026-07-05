import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.darkstore.darkroot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.moshi.kotlin)
    implementation(libs.play.services.auth)
    implementation("com.google.firebase:firebase-messaging:23.4.1")
    
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val downloadAssetsTask = tasks.register("downloadAssets") {
    doLast {
        val adBadgeDest = file("src/main/res/drawable/img_ad_badge.png")
        try {
            val adBadgeUri = URI("https://i.ibb.co/YFHRgL0r/Picsart-26-06-15-09-31-05-304.png")
            adBadgeDest.parentFile.mkdirs()
            adBadgeDest.writeBytes(adBadgeUri.toURL().readBytes())
        } catch (e: Exception) {
            logger.warn("Failed to download ad badge: ${e.message}")
        }

        val appLogoDest = file("src/main/res/drawable/img_app_logo_new.png")
        val appIconDest = file("src/main/res/drawable/img_app_icon.png")
        try {
            val appLogoUri = URI("https://i.ibb.co/jnf0Wj8/2f7e92384edf.png")
            val logoBytes = appLogoUri.toURL().readBytes()
            appLogoDest.parentFile.mkdirs()
            appLogoDest.writeBytes(logoBytes)
            appIconDest.writeBytes(logoBytes)
        } catch (e: Exception) {
            logger.warn("Failed to download app logo: ${e.message}")
        }
    }
}

// Make preBuild depend on downloadAssets
tasks.named("preBuild") {
    dependsOn(downloadAssetsTask)
}



