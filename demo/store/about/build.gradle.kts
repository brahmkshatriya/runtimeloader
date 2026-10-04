@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.compose")
    id("dev.brahmkshatriya.runtime-loader.module")
    id("dev.brahmkshatriya.demo.extension")
}

demoExtension {
    id = "about"
    name = "About Extension"
    description = "A second independently compiled extension project."
    icon = "info"
    repoLink = "https://example.org/about-extension"
    updateUrl = "https://example.org/about-extension/update.json"

    capabilities { lyrics() }
    author("Second Author") {
        avatar = "https://example.org/avatar.png"
        link = "https://example.org/authors/second"
    }
}

runtimeLoaderModule {
    api.set(projects.demo.client)
    entry {
        contract.set("dev.brahmkshatriya.runtimeloader.demo.Plugin")
        implementation.set("dev.brahmkshatriya.runtimeloader.extensions.about.AboutExtension")
    }
    hosts.set(
        listOf(
            projects.demo.apps.jvm,
            projects.demo.apps.linux,
            projects.demo.apps.linuxArm64,
            projects.demo.apps.macosX64,
            projects.demo.apps.macosArm64,
            projects.demo.apps.ios,
            projects.demo.apps.windows,
            projects.demo.apps.web,
        )
    )
    verifyCompose.set(true)
}

kotlin {
    jvm()
    wasmJs {
        nodejs()
        binaries.executable()
    }
    android {
        namespace = "dev.brahmkshatriya.runtimeloader.extensions.about"
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
            implementation(projects.demo.client)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
        linuxX64Main.dependencies {
            compileOnly(projects.runtimeLoader)
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        linuxArm64Main.dependencies {
            compileOnly(projects.runtimeLoader)
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        mingwX64Main.dependencies {
            compileOnly(projects.runtimeLoader)
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        macosX64Main.dependencies {
            compileOnly(projects.runtimeLoader)
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        macosArm64Main.dependencies {
            compileOnly(projects.runtimeLoader)
            implementation("dev.brahmkshatriya.compose.foundation:foundation:1.13.0-alpha05")
            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        }
        iosArm64Main.dependencies {
            compileOnly(projects.runtimeLoader)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        wasmJsMain.dependencies {
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(compose.ui)
        }
    }
}
