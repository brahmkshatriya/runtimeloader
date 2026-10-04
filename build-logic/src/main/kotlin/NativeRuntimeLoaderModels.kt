package dev.brahmkshatriya.runtimeloader.gradle

import java.io.File

internal data class Klib(
    val name: String,
    val path: File,
    val depends: List<String>,
)

internal data class ResolvedGraph(
    val graph: LinkedHashMap<String, Klib>,
    val implementation: Set<String>,
    val compatibility: Set<String>,
)

internal data class ModuleBuildInput(
    val id: String,
    val klib: File,
    val outputName: String,
)

internal data class ModuleBuildArtifact(
    val id: String,
    val klib: Klib,
    val cacheArchive: File,
    val sharedLibrary: File,
    val manifest: File,
)

internal data class NativeRuntimeLoaderOutputs(
    val executable: File,
    val hostManifest: File,
    val modules: List<ModuleBuildArtifact>,
)

internal fun cacheDir(root: File, uniqueName: String, target: String? = null): File {
    val cacheName = "$uniqueName-cache"
    val platformName = if (target == "mingw_x64") cacheName.replace(':', '_') else cacheName
    return File(root, platformName)
}

internal fun cacheArchive(root: File, uniqueName: String, target: String? = null): File? =
    File(cacheDir(root, uniqueName, target), "bin").listFiles()?.firstOrNull { it.extension == "a" }
