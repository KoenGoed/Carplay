plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Elke bouw op GitHub krijgt een hoger versienummer, zodat de app updates herkent.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "nl.koen.autoscherm"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.koen.autoscherm"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
        // Alleen de processors van moderne telefoons; houdt de app kleiner
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Vaste sleutel: elke update moet met dezelfde sleutel ondertekend zijn,
    // anders weigert Android hem over de oude versie heen te installeren.
    signingConfigs {
        create("autoscherm") {
            storeFile = file("autoscherm.keystore")
            storePassword = "autoscherm"
            keyAlias = "autoscherm"
            keyPassword = "autoscherm"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("autoscherm")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("autoscherm")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    // Offline spraakherkenning voor "Hey Golf"
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
