package dev.brahmkshatriya.runtimeloader.demo.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Demo plugins intentionally have no compile/runtime dependency on the publishable Runtime Loader
 * plugin implementation. They interact only with its named Gradle extension properties so the demo
 * layer cannot leak implementation classes or plugin markers into the release artifact.
 */
internal data class RuntimeLoaderModuleView(
    val moduleId: Property<String>,
    val outputFileName: Property<String>,
    val windowsOutputFileName: Property<String>,
    val appleOutputFileName: Property<String>,
    val iosOutputFileName: Property<String>,
    val hosts: ListProperty<ProjectDependency>,
)

internal data class RuntimeLoaderHostView(
    val target: Property<String>,
    val targets: ListProperty<String>,
    val entryPoint: Property<String>,
    val wasmModuleRunner: Property<String>,
)

internal fun Project.runtimeLoaderModuleView(): RuntimeLoaderModuleView? =
    extensions.findByName("runtimeLoaderModule")?.let { extension ->
        RuntimeLoaderModuleView(
            moduleId = extension.gradleProperty("moduleId"),
            outputFileName = extension.gradleProperty("outputFileName"),
            windowsOutputFileName = extension.gradleProperty("windowsOutputFileName"),
            appleOutputFileName = extension.gradleProperty("appleOutputFileName"),
            iosOutputFileName = extension.gradleProperty("iosOutputFileName"),
            hosts = extension.gradleListProperty("hosts"),
        )
    }

internal fun Project.runtimeLoaderHostView(): RuntimeLoaderHostView? =
    extensions.findByName("runtimeLoaderHost")?.let { extension ->
        RuntimeLoaderHostView(
            target = extension.gradleProperty("target"),
            targets = extension.gradleListProperty("targets"),
            entryPoint = extension.gradleProperty("entryPoint"),
            wasmModuleRunner = extension.gradleProperty("wasmModuleRunner"),
        )
    }

@Suppress("UNCHECKED_CAST")
private fun <T : Any> Any.gradleProperty(name: String): Property<T> =
    getter(name).invoke(this) as? Property<T>
        ?: error("Runtime Loader extension property '$name' is not a Gradle Property")

@Suppress("UNCHECKED_CAST")
private fun <T : Any> Any.gradleListProperty(name: String): ListProperty<T> =
    getter(name).invoke(this) as? ListProperty<T>
        ?: error("Runtime Loader extension property '$name' is not a Gradle ListProperty")

private fun Any.getter(name: String) = javaClass.methods.firstOrNull {
    it.name == "get${name.replaceFirstChar(Char::uppercaseChar)}" && it.parameterCount == 0
} ?: error("Runtime Loader extension no longer exposes '$name'; update the repository-local demo plugin")
