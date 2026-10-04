package dev.brahmkshatriya.runtimeloader.gradle

import java.io.File

internal interface NativeRuntimeLoaderBackend {
    val target: String
    val sharedLibraryExtension: String
    val requiresHostBeforeModules: Boolean get() = false

    fun requireTools()

    fun runtimeFiles(): List<File> = emptyList()

    fun linkModule(
        module: Klib,
        moduleArchive: File,
        output: File,
        workDirectory: File,
        hostExecutable: File?,
        linkDirectories: List<File>,
        log: (String) -> Unit,
    ): File

    fun prepareHostCaches(
        resolved: ResolvedGraph,
        staticCache: File,
        hybridCache: File,
        moduleLibraries: List<File>,
        log: (String) -> Unit,
    )

    fun linkHost(
        graph: Map<String, Klib>,
        order: List<String>,
        konanc: File,
        hybridCache: File,
        hostKlib: File,
        entryPoint: String,
        output: File,
        moduleLibraries: List<File>,
        linkDirectories: List<File>,
        linkerOptions: List<String>,
        log: (String) -> Unit,
    ): File
}

internal fun backendFor(target: String, konanHome: File): NativeRuntimeLoaderBackend = when (target) {
    "linux_x64", "linux_arm64" -> LinuxElfRuntimeLoaderBackend(target, konanHome)
    "mingw_x64" -> WindowsPeRuntimeLoaderBackend(konanHome)
    "macos_x64", "macos_arm64", "ios_arm64" -> AppleMachORuntimeLoaderBackend(target)
    else -> error("Kotlin/Native runtime-loader backend is not implemented for $target")
}
