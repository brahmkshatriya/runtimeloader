@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.compose")
    id("dev.brahmkshatriya.demo.native-compatibility")
}

kotlin {
    jvm()
    wasmJs {
        browser()
    }
    android {
        namespace = "dev.brahmkshatriya.runtimeloader.demo.client"
        compileSdk = 37
        minSdk = 24
    }
    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()
    iosArm64()

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        linuxX64Main.dependencies {
            api("dev.brahmkshatriya.compose.runtime:runtime:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        linuxArm64Main.dependencies {
            api("dev.brahmkshatriya.compose.runtime:runtime:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        mingwX64Main.dependencies {
            api("dev.brahmkshatriya.compose.runtime:runtime:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        macosX64Main.dependencies {
            api("dev.brahmkshatriya.compose.runtime:runtime:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        macosArm64Main.dependencies {
            api("dev.brahmkshatriya.compose.runtime:runtime:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            api("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
    }
}
