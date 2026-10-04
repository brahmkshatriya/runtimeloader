package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.Action
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class RuntimeLoaderEntrySpec @Inject constructor(objects: ObjectFactory) {
    /** Fully-qualified interface/base type that the generated entry must implement. */
    val contract: Property<String> = objects.property(String::class.java)

    /** Fully-qualified no-argument implementation class instantiated by the generated entry. */
    val implementation: Property<String> = objects.property(String::class.java)
}

abstract class RuntimeLoaderModuleExtension @Inject constructor(objects: ObjectFactory) {
    val api: Property<ProjectDependency> = objects.property(ProjectDependency::class.java)
    val hosts: ListProperty<ProjectDependency> = objects.listProperty(ProjectDependency::class.java)
    val moduleId: Property<String> = objects.property(String::class.java)
    val outputFileName: Property<String> = objects.property(String::class.java)
    val windowsOutputFileName: Property<String> = objects.property(String::class.java)
    val appleOutputFileName: Property<String> = objects.property(String::class.java)
    val iosOutputFileName: Property<String> = objects.property(String::class.java)
    val androidMinSdk: Property<Int> = objects.property(Int::class.java).convention(24)
    val entry: RuntimeLoaderEntrySpec = objects.newInstance(RuntimeLoaderEntrySpec::class.java)

    fun entry(configure: Action<RuntimeLoaderEntrySpec>) {
        configure.execute(entry)
    }

    // Development verification only; these do not describe or instantiate the loaded code.
    val smokeArguments: ListProperty<String> = objects.listProperty(String::class.java).convention(listOf("--smoke"))
    val verifyCompose: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val verifyArguments: ListProperty<String> = objects.listProperty(String::class.java).convention(listOf("--verify-compose"))
    val verifyMarker: Property<String> = objects.property(String::class.java).convention("runtime-loaded composable: rendering")
}

abstract class RuntimeLoaderHostExtension @Inject constructor(objects: ObjectFactory) {
    val entryPoint: Property<String> = objects.property(String::class.java)
    val jvmMainClass: Property<String> = objects.property(String::class.java)
    val wasmModuleRunner: Property<String> = objects.property(String::class.java)

    /**
     * Legacy/single-target selector. When neither [target] nor [targets] is configured, Runtime
     * Loader infers every supported Kotlin/Native target present in the project.
     */
    val target: Property<String> = objects.property(String::class.java)
    val targets: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    /** Single-target/default output name. */
    val executableName: Property<String> = objects.property(String::class.java).convention("app.kexe")

    /** Per-target executable/framework name overrides for a multi-target host. */
    val executableNames: MapProperty<String, String> =
        objects.mapProperty(String::class.java, String::class.java).convention(emptyMap())

    /** Linker options applied to every configured Native target. */
    val linkerOptions: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    /** Additional linker options keyed by Kotlin/Native target name. */
    @Suppress("UNCHECKED_CAST")
    val targetLinkerOptions: MapProperty<String, List<String>> =
        objects.mapProperty(String::class.java, List::class.java as Class<List<String>>).convention(emptyMap())

    val verifyTimeoutSeconds: Property<Double> = objects.property(Double::class.java).convention(8.0)
}
