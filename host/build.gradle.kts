plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.libmlkitproxy"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Extract other build artifacts
    sourceSets {
        getByName("main") {
            // Bundle the proxy AAR
            assets.srcDir(layout.buildDirectory.dir("generated/proxy_assets"))

            // Include the API interface directly
            java.srcDir(project(":api").file("src/main/java"))
        }
    }
}

dependencies {
    implementation(project(":api"))
}

tasks.register<Copy>("copyProxyPayload") {
    // Force the :proxy module to compile its release build before this task runs
    dependsOn(":proxy:assembleRelease")

    // Define where to find the compiled proxy payload
    val proxyBuildDir = project(":proxy").layout.buildDirectory
    from(proxyBuildDir.dir("outputs/apk/release/")) {
        include("**/*.apk")

        // Standardize the filename so your AssetExtractor always looks for the same file,
        // stripping out version names or "unsigned" suffixes added by AGP.
        rename(".*", "libmlkit-proxy.apk")
    }

    // Output the renamed file into the generated directory defined in the sourceSets
    into(layout.buildDirectory.dir("generated/proxy_assets"))
}

// Ensure the copy task runs automatically before the host module begins building
tasks.named("preBuild").configure {
    dependsOn("copyProxyPayload")
}
