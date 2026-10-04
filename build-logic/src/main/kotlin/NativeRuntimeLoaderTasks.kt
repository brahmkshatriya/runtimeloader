package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinUsages
import java.io.File

@DisableCachingByDefault(because = "Invokes local Kotlin/Native and platform linker toolchains and maintains local compiler caches")
abstract class BuildNativeRuntimeLoaderTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hostLibraries: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val hostKlib: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleKlibs: ConfigurableFileCollection

    @get:Input
    abstract val moduleDescriptors: ListProperty<String>

    @get:Input
    abstract val target: Property<String>

    @get:Input
    abstract val entryPoint: Property<String>

    @get:Input
    abstract val executableName: Property<String>

    @get:Input
    abstract val nativeLinkDirectories: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeRuntimeFiles: ConfigurableFileCollection

    @get:Input
    abstract val nativeLinkerOptions: ListProperty<String>

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:LocalState
    abstract val workDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun buildNativeRuntimeLoader() {
        logger.lifecycle("[runtime-loader] building ${moduleDescriptors.get().size} module(s) from Gradle-resolved KLIB inputs")
        val inputs = moduleDescriptors.get().map { encoded ->
            val parts = encoded.split('\t')
            if (parts.size != 3) throw GradleException("Invalid internal runtime-loader module descriptor: $encoded")
            ModuleBuildInput(parts[0], File(parts[1]), parts[2])
        }
        val pipeline = NativeRuntimeLoaderPipeline(
            rootDirectory = rootDirectory.get().asFile,
            workDirectory = workDirectory.get().asFile,
            outputDirectory = outputDirectory.get().asFile,
            target = target.get(),
            hostLibraries = hostLibraries.files.toList(),
            hostKlib = hostKlib.singleFileChecked("host KLIB"),
            modules = inputs,
            entryPoint = entryPoint.get(),
            executableName = executableName.get(),
            linkDirectories = nativeLinkDirectories.get().map(::File),
            linkerOptions = nativeLinkerOptions.get(),
            resolveMissingKlibs = ::resolveMissingNativeKlibs,
            log = { logger.lifecycle("[runtime-loader] $it") },
        )
        val outputs = pipeline.build()
        nativeRuntimeFiles.files.filter { it.isFile }.forEach { source ->
            source.copyTo(File(outputDirectory.get().asFile, source.name), overwrite = true)
        }
        if (outputs.executable.parentFile.canonicalFile != outputDirectory.get().asFile.canonicalFile) {
            throw GradleException("Unexpected runtime-loader output directory: ${outputs.executable.parentFile}")
        }
    }

    private fun resolveMissingNativeKlibs(uniqueName: String, versions: Set<String>): List<File> {
        if (':' !in uniqueName || versions.isEmpty()) return emptyList()
        val nativeTargetAttribute = Attribute.of("org.jetbrains.kotlin.native.target", String::class.java)
        val resolved = linkedSetOf<File>()
        versions.sortedDescending().forEach { version ->
            val configuration = project.configurations.detachedConfiguration(
                project.dependencies.create("$uniqueName:$version")
            ).apply {
                isTransitive = false
                attributes {
                    attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
                    attribute(nativeTargetAttribute, target.get())
                    attribute(
                        Usage.USAGE_ATTRIBUTE,
                        project.objects.named(Usage::class.java, KotlinUsages.KOTLIN_API),
                    )
                }
            }
            runCatching { configuration.resolve() }
                .getOrNull()
                ?.filterTo(resolved) { it.isFile && it.extension == "klib" }
        }
        if (resolved.isNotEmpty()) {
            logger.lifecycle(
                "[runtime-loader] Gradle resolved missing Native KLIB $uniqueName for ${target.get()}"
            )
        }
        return resolved.toList()
    }
}

@DisableCachingByDefault(because = "Executes the produced native application as a verification task")
abstract class SmokeNativeRuntimeLoaderTask : DefaultTask() {
    @get:Internal abstract val executable: RegularFileProperty
    @get:Internal abstract val sharedLibrary: RegularFileProperty
    @get:Input abstract val arguments: ListProperty<String>

    @TaskAction
    fun smoke() {
        logger.lifecycle("[runtime-loader] smoke ${sharedLibrary.get().asFile.name}")
        runCommand(
            listOf(executable.get().asFile.absolutePath, sharedLibrary.get().asFile.absolutePath) + arguments.get()
        )
    }
}

@DisableCachingByDefault(because = "Executes the produced native UI application as a verification task")
abstract class VerifyNativeRuntimeLoaderTask : DefaultTask() {
    @get:Internal abstract val executable: RegularFileProperty
    @get:Internal abstract val sharedLibrary: RegularFileProperty
    @get:Input abstract val arguments: ListProperty<String>
    @get:Input abstract val marker: Property<String>
    @get:Input abstract val timeoutSeconds: Property<Double>

    @TaskAction
    fun verify() {
        verifyComposeProcess(
            executable = executable.get().asFile,
            module = sharedLibrary.get().asFile,
            arguments = arguments.get(),
            marker = marker.get(),
            timeout = timeoutSeconds.get(),
            log = { logger.lifecycle(it) },
        )
    }
}

@DisableCachingByDefault(because = "Interactive application launch task")
abstract class RunNativeRuntimeLoaderTask : DefaultTask() {
    @get:Internal abstract val executable: RegularFileProperty
    @get:Internal abstract val sharedLibrary: RegularFileProperty

    @TaskAction
    fun runApp() {
        runCommand(listOf(executable.get().asFile.absolutePath, sharedLibrary.get().asFile.absolutePath))
    }
}

@DisableCachingByDefault(because = "Executes native applications and intentionally creates incompatible test artifacts")
abstract class NativeRuntimeLoaderIntegrationTestTask : DefaultTask() {
    @get:Internal abstract val executable: RegularFileProperty
    @get:Internal abstract val sharedLibrary: RegularFileProperty
    @get:Internal abstract val hostManifest: RegularFileProperty
    @get:Internal abstract val moduleManifest: RegularFileProperty
    @get:Input abstract val smokeArguments: ListProperty<String>
    @get:Input abstract val verifyCompose: Property<Boolean>
    @get:Input abstract val verifyArguments: ListProperty<String>
    @get:Input abstract val verifyMarker: Property<String>
    @get:Input abstract val timeoutSeconds: Property<Double>

    @TaskAction
    fun integrationTest() {
        val app = executable.get().asFile
        val module = sharedLibrary.get().asFile
        logger.lifecycle("[runtime-loader:test] typed load + shared-state mutation")
        runCommand(listOf(app.absolutePath, module.absolutePath) + smokeArguments.get())

        logger.lifecycle("[runtime-loader:test] incompatible ABI is rejected before native loading")
        val badDir = File(temporaryDir, "incompatible").apply { deleteRecursively(); mkdirs() }
        val fakePlugin = File(badDir, "incompatible.${module.extension}").apply {
            writeText("not a native library")
        }
        File(badDir, "runtime-loader-host.properties").writeText(hostManifest.get().asFile.readText())
        val badManifest = File(fakePlugin.absolutePath + ".runtime-loader.properties")
        badManifest.writeText(
            moduleManifest.get().asFile.readText().replace(
                Regex("(?m)^hostAbi=.*$"),
                "hostAbi=0000000000000000000000000000000000000000000000000000000000000000",
            )
        )
        val incompatible = runCommand(
            listOf(app.absolutePath, fakePlugin.absolutePath) + smokeArguments.get(),
            capture = true,
            check = false,
        )
        if (incompatible.exitCode == 0 || "Incompatible Kotlin/Native module host ABI" !in incompatible.output) {
            throw GradleException(
                "Expected pre-dlopen ABI rejection, got exit=${incompatible.exitCode}:\n${incompatible.output}"
            )
        }
        if ("dlopen(" in incompatible.output) {
            throw GradleException("ABI mismatch reached dlopen; validation must happen before native loading")
        }

        if (verifyCompose.get()) {
            logger.lifecycle("[runtime-loader:test] runtime-loaded composable enters host composition")
            verifyComposeProcess(
                executable = app,
                module = module,
                arguments = verifyArguments.get(),
                marker = verifyMarker.get(),
                timeout = timeoutSeconds.get(),
                log = { logger.lifecycle(it) },
            )
        }
        logger.lifecycle("[runtime-loader:test] PASS")
    }
}

private fun verifyComposeProcess(
    executable: File,
    module: File,
    arguments: List<String>,
    marker: String,
    timeout: Double,
    log: (String) -> Unit,
) {
    log("[runtime-loader] launching UI verification for ${timeout.toString().removeSuffix(".0")}s")
    val result = runCommandWithTimeout(
        listOf(executable.absolutePath, module.absolutePath) + arguments,
        timeout,
    )
    print(result.output)
    if (result.finished) {
        if (result.exitCode != 0) throw GradleException("UI app exited early with status ${result.exitCode}")
        throw GradleException("UI app exited before the verification timeout")
    }
    if (marker !in result.output) {
        throw GradleException("Window stayed alive, but verification marker '$marker' was not observed")
    }
    log("[runtime-loader] PASS: runtime-loaded UI entered the host event loop/composition")
}

private fun ConfigurableFileCollection.singleFileChecked(label: String): File {
    val values = files.toList()
    if (values.size != 1) throw GradleException("Expected exactly one $label, found: $values")
    return values.single()
}
