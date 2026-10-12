plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.runplan.app"
    compileSdk = 35

    defaultConfig {
        // Same applicationId as the watch app: required for the Wearable Data Layer.
        applicationId = "dev.runplan"
        minSdk = 26
        targetSdk = 35
        // CI run number, so every build is newer than the last and the in-app updater can compare
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    signingConfigs {
        // Shared, committed key so CI builds of phone + watch always match and update in place.
        // Fine for personal sideloading; use a private key before publishing anywhere.
        create("shared") {
            storeFile = rootProject.file("keystore/shared-debug.keystore")
            storePassword = "runplan"
            keyAlias = "runplan"
            keyPassword = "runplan"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.osmdroid)
}
