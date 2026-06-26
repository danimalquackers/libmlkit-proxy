plugins {
    id("com.android.library") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    
    // Keep the versions plugin for future checks
    id("com.github.ben-manes.versions") version "0.51.0"
}

android {
    namespace = "com.libmlkitproxy"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // NanoHTTPD for the ultra-lightweight web server
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    
    // JSON parsing
    implementation("org.json:json:20260522")

    // ML Kit GenAI APIs
    implementation("com.google.mlkit:common:18.11.0")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")

    // Coroutines for blocking NanoHTTPD threads safely
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
