package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

@DisableCachingByDefault(because = "Invokes the consumer's local Android SDK/D8 toolchain")
abstract class AndroidDexModuleTask : DefaultTask() {
    @get:Classpath
    abstract val classesJar: RegularFileProperty

    @get:Classpath
    abstract val hostClassesJar: RegularFileProperty

    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    init {
        minSdk.convention(24)
    }

    @TaskAction
    fun dex() {
        val sdk = androidSdkDirectory(rootDirectory.get().asFile)
        val d8 = newestVersionedTool(File(sdk, "build-tools"), "d8")
        val androidJar = newestAndroidJar(File(sdk, "platforms"))
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        output.delete()

        val command = mutableListOf(
            d8.absolutePath,
            "--min-api", minSdk.get().toString(),
            "--lib", androidJar.absolutePath,
            "--output", output.absolutePath,
        )
        (listOf(hostClassesJar.get().asFile) + libraries.files)
            .distinctBy(File::getAbsolutePath)
            .filter(File::exists)
            .forEach { library -> command += listOf("--classpath", library.absolutePath) }
        command += classesJar.get().asFile.absolutePath
        runCommand(command)
        logger.lifecycle("[runtime-loader] Android DEX module: $output")
    }
}

private fun androidSdkDirectory(root: File): File {
    val env = sequenceOf("ANDROID_SDK_ROOT", "ANDROID_HOME")
        .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        .map(::File)
        .firstOrNull(File::isDirectory)
    if (env != null) return env

    val local = File(root, "local.properties")
    if (local.isFile) {
        val properties = Properties().apply { local.inputStream().use(::load) }
        val sdk = properties.getProperty("sdk.dir")?.let(::File)
        if (sdk?.isDirectory == true) return sdk
    }
    throw GradleException("Android SDK not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or sdk.dir in local.properties")
}

private fun newestVersionedTool(parent: File, name: String): File {
    val candidates = parent.listFiles().orEmpty()
        .filter(File::isDirectory)
        .map { it to File(it, name) }
        .filter { (_, tool) -> tool.isFile && tool.canExecute() }
    return candidates.maxWithOrNull(compareBy<Pair<File, File>> { versionKey(it.first.name) })?.second
        ?: throw GradleException("Android build-tools/$name not found under $parent")
}

private fun newestAndroidJar(platforms: File): File = platforms.listFiles().orEmpty()
    .filter { it.isDirectory && it.name.startsWith("android-") }
    .mapNotNull { dir ->
        dir.name.removePrefix("android-").substringBefore('.').toIntOrNull()
            ?.let { it to File(dir, "android.jar") }
    }
    .filter { it.second.isFile }
    .maxByOrNull { it.first }
    ?.second
    ?: throw GradleException("No Android platform android.jar found under $platforms")

private fun versionKey(value: String): String = value.split('.', '-', '_')
    .joinToString(".") { it.toIntOrNull()?.toString()?.padStart(6, '0') ?: it }
