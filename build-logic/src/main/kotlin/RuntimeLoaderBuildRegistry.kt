package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.concurrent.ConcurrentHashMap

internal data class RegisteredRuntimeLoaderModule(
    val id: String,
    val hostProjectPath: String,
    val moduleProjectPath: String,
    val outputFileName: String,
    val windowsOutputFileName: String,
    val appleOutputFileName: String,
    val iosOutputFileName: String,
    val smokeArguments: List<String>,
    val verifyCompose: Boolean,
    val verifyArguments: List<String>,
    val verifyMarker: String,
) {
    fun outputFileName(target: String): String = when (target) {
        "mingwX64" -> windowsOutputFileName
        "macosX64", "macosArm64" -> appleOutputFileName
        "iosArm64" -> iosOutputFileName
        else -> outputFileName
    }
}

/**
 * Wrapper around classloader-neutral Gradle root state.
 *
 * Host and module plugin IDs can be loaded through distinct Gradle plugin classloaders. Storing a
 * Kotlin implementation object as a root extension makes an identical class name from another
 * classloader fail the cast. The backing state therefore contains only JDK collections, strings,
 * booleans and string lists; each plugin classloader may wrap/deserialize it independently.
 */
internal class RuntimeLoaderBuildRegistry(
    private val registrations: MutableMap<String, Map<String, Any>>,
) {
    fun register(module: RegisteredRuntimeLoaderModule) {
        val key = "${module.hostProjectPath}|${module.moduleProjectPath}"
        val encoded = module.encode()
        val previous = registrations.putIfAbsent(key, encoded)
        if (previous != null && previous != encoded) {
            throw GradleException(
                "Runtime-loader module ${module.moduleProjectPath} registered more than once with different settings"
            )
        }
    }

    fun modulesForHost(hostProjectPath: String): List<RegisteredRuntimeLoaderModule> =
        registrations.values.asSequence()
            .map(::decodeRegisteredModule)
            .filter { it.hostProjectPath == hostProjectPath }
            .sortedBy { it.id }
            .toList()
}

private const val REGISTRY_EXTRA_PROPERTY = "dev.brahmkshatriya.runtime-loader.build-registry"

@Suppress("UNCHECKED_CAST")
internal fun Project.runtimeLoaderBuildRegistry(): RuntimeLoaderBuildRegistry {
    val extras = rootProject.extensions.extraProperties
    val existing = if (extras.has(REGISTRY_EXTRA_PROPERTY)) extras.get(REGISTRY_EXTRA_PROPERTY) else null
    val state = when (existing) {
        null -> ConcurrentHashMap<String, Map<String, Any>>().also {
            extras.set(REGISTRY_EXTRA_PROPERTY, it)
        }
        is MutableMap<*, *> -> existing as MutableMap<String, Map<String, Any>>
        else -> throw GradleException(
            "Gradle root property '$REGISTRY_EXTRA_PROPERTY' is reserved by Runtime Loader"
        )
    }
    return RuntimeLoaderBuildRegistry(state)
}

private fun RegisteredRuntimeLoaderModule.encode(): Map<String, Any> = linkedMapOf(
    "id" to id,
    "hostProjectPath" to hostProjectPath,
    "moduleProjectPath" to moduleProjectPath,
    "outputFileName" to outputFileName,
    "windowsOutputFileName" to windowsOutputFileName,
    "appleOutputFileName" to appleOutputFileName,
    "iosOutputFileName" to iosOutputFileName,
    "smokeArguments" to smokeArguments.toList(),
    "verifyCompose" to verifyCompose,
    "verifyArguments" to verifyArguments.toList(),
    "verifyMarker" to verifyMarker,
)

private fun decodeRegisteredModule(values: Map<String, Any>): RegisteredRuntimeLoaderModule =
    RegisteredRuntimeLoaderModule(
        id = values.string("id"),
        hostProjectPath = values.string("hostProjectPath"),
        moduleProjectPath = values.string("moduleProjectPath"),
        outputFileName = values.string("outputFileName"),
        windowsOutputFileName = values.string("windowsOutputFileName"),
        appleOutputFileName = values.string("appleOutputFileName"),
        iosOutputFileName = values.string("iosOutputFileName"),
        smokeArguments = values.stringList("smokeArguments"),
        verifyCompose = values["verifyCompose"] as? Boolean
            ?: throw GradleException("Invalid Runtime Loader registry value 'verifyCompose'"),
        verifyArguments = values.stringList("verifyArguments"),
        verifyMarker = values.string("verifyMarker"),
    )

private fun Map<String, Any>.string(name: String): String =
    this[name] as? String ?: throw GradleException("Invalid Runtime Loader registry value '$name'")

private fun Map<String, Any>.stringList(name: String): List<String> =
    (this[name] as? List<*>)?.map { value ->
        value as? String ?: throw GradleException("Invalid Runtime Loader registry list '$name'")
    } ?: throw GradleException("Invalid Runtime Loader registry value '$name'")
