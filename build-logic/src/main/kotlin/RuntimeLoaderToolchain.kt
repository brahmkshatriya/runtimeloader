package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

internal const val RUNTIME_LOADER_SUPPORTED_KOTLIN_VERSION: String = "2.4.20"
private val minimumGradleVersion = GradleVersion.version("9.6")
private val nextUnsupportedGradleMajor = GradleVersion.version("10.0")

/** Fail before task registration drifts into unsupported compiler/linker internals. */
internal fun Project.requireRuntimeLoaderToolchain() {
    val currentGradle = GradleVersion.current()
    if (currentGradle < minimumGradleVersion || currentGradle.baseVersion >= nextUnsupportedGradleMajor) {
        throw GradleException(
            "Runtime Loader 0.1.x supports Gradle 9.6 through 9.x; found $currentGradle. " +
                "Use a supported Gradle version or a Runtime Loader release that declares support for this Gradle version."
        )
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val kotlinVersion = getKotlinPluginVersion()
        if (kotlinVersion != RUNTIME_LOADER_SUPPORTED_KOTLIN_VERSION) {
            throw GradleException(
                "Runtime Loader 0.1.x requires Kotlin Gradle Plugin/Kotlin Native " +
                    "$RUNTIME_LOADER_SUPPORTED_KOTLIN_VERSION; found $kotlinVersion. " +
                    "The Native backends depend on compiler cache/linker internals and unsupported versions are intentionally rejected."
            )
        }
    }
}
