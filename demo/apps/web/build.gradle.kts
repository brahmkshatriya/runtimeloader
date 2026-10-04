@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.runtime-loader.host")
}

runtimeLoaderHost {
    wasmModuleRunner.set("runRuntimeLoadedModule")
}

kotlin {
    wasmJs {
        browser()
        nodejs()
        binaries.executable()
    }

    sourceSets.wasmJsMain.dependencies {
        implementation(projects.demo.client)
        implementation(projects.runtimeLoader)
        implementation(compose.ui)
        implementation(compose.material3)
    }
}
