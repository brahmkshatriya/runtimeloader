package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class LinuxElfRuntimeLoaderBackend(
    override val target: String,
    private val konanHome: File,
) : NativeRuntimeLoaderBackend {
    init {
        require(target == "linux_x64" || target == "linux_arm64") {
            "Linux ELF backend does not support $target"
        }
    }
    override val sharedLibraryExtension: String = "so"

    override fun requireTools() {
        requireTool("nm")
        requireTool("ar")
        objcopyTool()
        linuxToolchain()
    }

    override fun linkModule(
        module: Klib,
        moduleArchive: File,
        output: File,
        workDirectory: File,
        hostExecutable: File?,
        linkDirectories: List<File>,
        log: (String) -> Unit,
    ): File {
        val toolchain = linuxToolchain()
        val clang = toolchain.clang.absolutePath
        output.parentFile.mkdirs()
        val safeId = output.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val bootstrapSource = File(workDirectory, "module_bootstrap_$safeId.c")
        val objectFile = File(workDirectory, "module_bootstrap_$safeId.o")
        val initSymbol = "_Konan_init_${module.name}"
        exposeModuleSymbols(moduleArchive, log)
        bootstrapSource.writeText(
            """
            #include <pthread.h>

            extern void konan_module_init(void)
                __asm__("${cString(initSymbol)}");

            static pthread_once_t runtime_loader_once = PTHREAD_ONCE_INIT;

            static void runtime_loader_init_once(void) {
                konan_module_init();
            }

            __attribute__((visibility("default")))
            void runtime_loader_module_init(void) {
                pthread_once(&runtime_loader_once, runtime_loader_init_once);
            }
            """.trimIndent() + "\n"
        )
        log("generated ELF module-init bootstrap for ${module.name}")
        runCommand(
            listOf(clang) + toolchain.compilerArgs + listOf(
                "-fPIC", "-O0", "-g", "-pthread", "-c",
                bootstrapSource.absolutePath, "-o", objectFile.absolutePath,
            )
        )
        output.delete()
        runCommand(
            listOf(clang) + toolchain.compilerArgs + listOf(
                "-shared", "-o", output.absolutePath,
                objectFile.absolutePath,
                "-Wl,--whole-archive", moduleArchive.absolutePath,
                "-Wl,--no-whole-archive",
                "-Wl,--allow-shlib-undefined",
                "-pthread",
                "-fuse-ld=${toolchain.linker.absolutePath}",
            )
        )
        return output
    }

    private fun exposeModuleSymbols(archive: File, log: (String) -> Unit) {
        val ar = requireTool("ar")
        val objcopy = objcopyTool()
        val temp = Files.createTempDirectory("runtime-loader-module-").toFile()
        try {
            val members = runCommand(listOf(ar, "t", archive.absolutePath), capture = true)
                .output.lineSequence().filter { it.isNotBlank() }.toList()
            runCommand(listOf(ar, "x", archive.absolutePath), cwd = temp)
            var exported = 0
            members.forEach { member ->
                val obj = File(temp, member)
                if (!obj.isFile) return@forEach
                val callableSymbols = rawDefinedFunctionSymbols(obj).sorted()
                if (callableSymbols.isEmpty()) return@forEach
                val command = mutableListOf(objcopy)
                callableSymbols.forEach { symbol -> command += "--set-symbol-visibility=$symbol=default" }
                command += obj.absolutePath
                runCommand(command)
                exported += callableSymbols.size
            }
            archive.delete()
            archive.parentFile.mkdirs()
            runCommand(listOf(ar, "rcs", archive.absolutePath) + members, cwd = temp)
            log("exposed $exported module-defined symbols for application-level resolution")
        } finally {
            temp.deleteRecursively()
        }
    }

    override fun prepareHostCaches(
        resolved: ResolvedGraph,
        staticCache: File,
        hybridCache: File,
        moduleLibraries: List<File>,
        log: (String) -> Unit,
    ) {
        val objcopy = objcopyTool()
        val ar = requireTool("ar")
        log("creating Linux/ELF host cache view for ${moduleLibraries.size} module(s)")
        hardlinkCopyTree(staticCache, hybridCache)

        val imports = moduleLibraries.flatMapTo(linkedSetOf(), ::rawModuleImports)
        val implementationSymbols = resolved.implementation.flatMapTo(linkedSetOf()) { name ->
            cacheArchive(staticCache, name)?.let(::rawDefinedSymbols).orEmpty()
        }
        val exposed = linkedSetOf<String>()
        var weakened = 0
        var changedArchives = 0

        resolved.graph.keys.forEach { name ->
            val archive = cacheArchive(hybridCache, name) ?: return@forEach
            val temp = Files.createTempDirectory("runtime-loader-cache-").toFile()
            try {
                val members = runCommand(listOf(ar, "t", archive.absolutePath), capture = true)
                    .output.lineSequence().filter { it.isNotBlank() }.toList()
                runCommand(listOf(ar, "x", archive.absolutePath), cwd = temp)
                var archiveChanged = false
                members.forEach memberLoop@{ member ->
                    val obj = File(temp, member)
                    if (!obj.exists()) return@memberLoop
                    val defined = rawDefinedSymbols(obj)
                    val makeDefault = (defined intersect imports).sorted()
                    val makeWeak = if (name in resolved.compatibility) {
                        (defined intersect implementationSymbols)
                            .filterNot { it.startsWith("_Konan_init_") }
                            .sorted()
                    } else emptyList()
                    if (makeDefault.isEmpty() && makeWeak.isEmpty()) return@memberLoop

                    val command = mutableListOf(objcopy)
                    makeDefault.forEach { command += "--set-symbol-visibility=$it=default" }
                    makeWeak.forEach { command += "--weaken-symbol=$it" }
                    command += obj.absolutePath
                    runCommand(command)
                    exposed += makeDefault
                    weakened += makeWeak.size
                    archiveChanged = true
                }
                if (archiveChanged) {
                    archive.delete()
                    runCommand(listOf(ar, "rcs", archive.absolutePath) + members, cwd = temp)
                    changedArchives++
                }
            } finally {
                temp.deleteRecursively()
            }
        }

        val systemImports = imports - exposed
        log(
            "ELF cache view: exported ${exposed.size} module symbols, weakened $weakened " +
                "compatibility definitions across $changedArchives archives"
        )
        if (systemImports.isNotEmpty()) {
            log("remaining module imports are supplied by libc/toolchain: ${systemImports.sorted().joinToString()}")
        }
    }

    override fun linkHost(
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
    ): File {
        output.parentFile.mkdirs()
        output.delete()
        val command = mutableListOf(
            konanc.absolutePath,
            "-g",
            "-enable-assertions",
            "-entry", entryPoint,
            "-nostdlib",
            "-produce", "program",
            "-target", target,
            "-Xmulti-platform",
            "-Xpre-link-caches=disable",
            "-linker-option", "-Wl,--export-dynamic",
            "-output", output.absolutePath,
        )
        linkDirectories.forEach { directory ->
            command += listOf("-linker-option", "-L${directory.absolutePath}")
        }
        linkerOptions.forEach { option -> command += listOf("-linker-option", option) }
        order.forEach { name ->
            val item = graph.getValue(name)
            command += listOf("-library", item.path.absolutePath)
            if (cacheArchive(hybridCache, name) != null) {
                command += "-Xcached-library=${item.path.absolutePath},${cacheDir(hybridCache, name).absolutePath}"
            }
        }
        log("linking Linux/ELF host executable with a process-visible Kotlin ABI")
        runCommand(command)
        return output
    }

    private fun rawDefinedFunctionSymbols(objectFile: File): Set<String> {
        val output = runCommand(
            listOf(requireTool("nm"), "-g", "--defined-only", objectFile.absolutePath),
            capture = true,
        ).output
        return output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size == 3 && parts[1] in setOf("T", "W")) parts[2] else null
        }.toSet()
    }

    private fun rawDefinedSymbols(archiveOrObject: File): Set<String> {
        val output = runCommand(
            listOf(requireTool("nm"), "-g", "--defined-only", archiveOrObject.absolutePath),
            capture = true,
        ).output
        return output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size == 3) parts[2] else null
        }.toSet()
    }

    private fun rawModuleImports(sharedLibrary: File): Set<String> {
        val output = runCommand(
            listOf(requireTool("nm"), "-D", "-u", sharedLibrary.absolutePath),
            capture = true,
        ).output
        return output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[parts.size - 2] in setOf("U", "w")) {
                parts.last().substringBefore('@')
            } else null
        }.toSet()
    }

    private fun objcopyTool(): String =
        runCatching { requireTool("llvm-objcopy") }.getOrElse { requireTool("objcopy") }


    private data class LinuxToolchain(
        val clang: File,
        val linker: File,
        val compilerArgs: List<String>,
    )

    private fun linuxToolchain(): LinuxToolchain {
        val properties = java.util.Properties().apply {
            File(konanHome, "konan/konan.properties").inputStream().use(::load)
        }
        val llvmName = properties.getProperty("llvm.linux_x64.user")
            ?: throw GradleException("Kotlin/Native LLVM dependency is missing for the Linux host")
        val gccName = properties.getProperty("toolchainDependency.$target")
            ?: throw GradleException("Kotlin/Native GCC toolchain dependency is missing for $target")
        val triple = properties.getProperty("targetTriple.$target")
            ?: throw GradleException("Kotlin/Native target triple is missing for $target")
        val dependencies = File(konanHome.parentFile, "dependencies")
        val llvm = File(dependencies, llvmName)
        val gcc = File(dependencies, gccName)
        val clang = File(llvm, "bin/clang")
        val linker = File(llvm, "bin/ld.lld")
        val sysroot = File(gcc, "$triple/sysroot")
        if (!clang.isFile || !linker.isFile || !sysroot.isDirectory) {
            throw GradleException(
                "Kotlin/Native Linux cross-toolchain is incomplete for $target. " +
                    "Expected $clang, $linker, and $sysroot. Compile the target once so Kotlin/Native downloads its dependencies."
            )
        }
        return LinuxToolchain(
            clang = clang,
            linker = linker,
            compilerArgs = listOf(
                "--target=$triple",
                "--sysroot=${sysroot.absolutePath}",
                "--gcc-toolchain=${gcc.absolutePath}",
            ),
        )
    }

    private fun cString(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun hardlinkCopyTree(source: File, destination: File) {
        destination.deleteRecursively()
        source.walkTopDown().forEach { item ->
            val relative = item.relativeTo(source)
            val targetFile = File(destination, relative.path)
            if (item.isDirectory) {
                targetFile.mkdirs()
            } else {
                targetFile.parentFile.mkdirs()
                runCatching { Files.createLink(targetFile.toPath(), item.toPath()) }
                    .recoverCatching {
                        Files.copy(
                            item.toPath(), targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                    }
                    .getOrThrow()
            }
        }
    }
}
