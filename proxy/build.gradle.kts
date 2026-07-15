plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.libmlkitproxy.proxy"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Exclude these files to prevent merge conflict
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    compileOnly(project(":api"))

    // Hidden API bypass for Android 9+ compatibility
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // SLF4J logging redirect to Android Logcat
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("uk.uuid.slf4j:slf4j-android:2.0.17-0")

    // Lightweight web server with HTTP/2 support
    implementation("io.ktor:ktor-server-core:3.5.1")
    implementation("io.ktor:ktor-server-netty:3.5.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.5.1")
    implementation("io.ktor:ktor-serialization-gson:3.5.1")

    // JSON deserialization support
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // ML Kit GenAI APIs
    implementation("com.google.mlkit:common:18.11.0")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")

    // Coroutines support
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}