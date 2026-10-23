plugins {
    id("com.android.application")
    kotlin("plugin.compose") version "2.4.0"
}

android {
    namespace = "io.github.northster.sekaitracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.northster.sekaitracker"
        minSdk = 30
        targetSdk = 37
        // CI passes its run number, so every build installs over the previous one
        val build = System.getenv("SEKAI_BUILD_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 1000 + build
        versionName = "0.1.$build"
        ndk { abiFilters += listOf("arm64-v8a") } // Galaxy Z Fold; keeps the ML Kit native libraries small
    }

    // One fixed key for every build so updates install over each other. It is only an app-signing key for this personal
    // app (not a secret service credential); a key from the SEKAI_KEYSTORE_* secrets takes its place when they are set.
    signingConfigs {
        create("shared") {
            val ci = System.getenv("SEKAI_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }?.takeIf { it.exists() }
            storeFile = ci ?: file("sekai-shared.keystore")
            storePassword = if (ci != null) System.getenv("SEKAI_KEYSTORE_PASSWORD") else "sekaitracker"
            keyAlias = if (ci != null) System.getenv("SEKAI_KEY_ALIAS") else "sekai"
            keyPassword = if (ci != null) System.getenv("SEKAI_KEY_PASSWORD") ?: System.getenv("SEKAI_KEYSTORE_PASSWORD") else "sekaitracker"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint { checkReleaseBuilds = false }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // on-device OCR, models bundled in the APK (no download, no network)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
