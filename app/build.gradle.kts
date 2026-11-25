plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "cc.szakacs.aurion"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.szakacs.aurion"
        minSdk = 28
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("aurion") {
            storeFile = rootProject.file("keystore/aurion.jks")
            storePassword = "aurion2026"
            keyAlias = "aurion"
            keyPassword = "aurion2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aurion")
        }
        debug {
            signingConfig = signingConfigs.getByName("aurion")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Lokales LLM (on-device)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // MPImage/BitmapImageBuilder für Bild-Eingaben (Gemma 3n)
    implementation("com.google.mediapipe:tasks-core:0.10.21")

    // On-Device Texterkennung + Objekterkennung (gebündelte Modelle, offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")

    // Natürliche Offline-Stimmen (Piper/VITS) + Whisper-Spracherkennung
    // Die AAR wird im CI-Build von den sherpa-onnx-Releases geladen (siehe .github/workflows/build.yml)
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
}
