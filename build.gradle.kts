plugins {
    id("com.android.library") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
