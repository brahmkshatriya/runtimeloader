package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        val temp = Files.createTempDirectory("runtime-loader-module-").toFile()
        try {
            val members = runCommand(listOf(ar, "t", archive.absolutePath), capture = true)
                .output.lineSequence().filter { it.isNotBlank() }.toList()
            runCommand(listOf(ar, "x", archive.absolutePath), cwd = temp)
            var exported = 0
            members.forEach { member ->
                val obj = File(temp, member)
                if (!obj.isFile) return@forEach
                val callableSymbols = rawDefinedFunctionSymbols(obj)
                if (callableSymbols.isEmpty()) return@forEach
                patchElfObjectSymbols(obj, makeDefault = callableSymbols, makeWeak = emptySet())
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
                    val makeDefault = (defined intersect imports).toSet()
                    val makeWeak = if (name in resolved.compatibility) {
                        (defined intersect implementationSymbols)
                            .filterNot { it.startsWith("_Konan_init_") }
                            .toSet()
                    } else emptySet()
                    if (makeDefault.isEmpty() && makeWeak.isEmpty()) return@memberLoop

                    patchElfObjectSymbols(obj, makeDefault, makeWeak)
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

    /**
     * Patch ELF64 symbol visibility/binding directly so Runtime Loader is not coupled to a
     * particular host llvm-objcopy version. Kotlin/Native Linux x64/arm64 cache objects are
     * little-endian ELF64 relocatables. st_other's low two bits encode visibility, while the high
     * nibble of st_info encodes GLOBAL/WEAK binding.
     */
    private fun patchElfObjectSymbols(
        objectFile: File,
        makeDefault: Set<String>,
        makeWeak: Set<String>,
    ) {
        if (makeDefault.isEmpty() && makeWeak.isEmpty()) return
        val bytes = objectFile.readBytes()
        if (bytes.size < ELF64_HEADER_SIZE ||
            bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
            bytes[ELF_EI_CLASS].toInt() != ELFCLASS64 ||
            bytes[ELF_EI_DATA].toInt() != ELFDATA2LSB
        ) {
            throw GradleException("Expected a little-endian ELF64 cache object: $objectFile")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sectionOffset = buffer.getLong(ELF64_E_SHOFF).checkedOffset("section table", objectFile)
        val sectionEntrySize = buffer.getShort(ELF64_E_SHENTSIZE).toInt() and 0xffff
        var sectionCount = buffer.getShort(ELF64_E_SHNUM).toInt() and 0xffff
        if (sectionEntrySize < ELF64_SECTION_HEADER_SIZE || sectionOffset + sectionEntrySize > bytes.size) {
            throw GradleException("Invalid ELF64 section table in $objectFile")
        }
        if (sectionCount == 0) {
            val extendedCount = buffer.getLong(sectionOffset + ELF64_SH_SIZE)
            if (extendedCount <= 0 || extendedCount > Int.MAX_VALUE) {
                throw GradleException("Invalid extended ELF64 section count in $objectFile")
            }
            sectionCount = extendedCount.toInt()
        }
        if (sectionOffset.toLong() + sectionCount.toLong() * sectionEntrySize > bytes.size.toLong()) {
            throw GradleException("ELF64 section table exceeds file bounds: $objectFile")
        }

        var changed = false
        repeat(sectionCount) { sectionIndex ->
            val section = sectionOffset + sectionIndex * sectionEntrySize
            val type = buffer.getInt(section + ELF64_SH_TYPE)
            if (type != SHT_SYMTAB && type != SHT_DYNSYM) return@repeat

            val symbolsOffset = buffer.getLong(section + ELF64_SH_OFFSET).checkedOffset("symbol table", objectFile)
            val symbolsSize = buffer.getLong(section + ELF64_SH_SIZE)
            val symbolEntrySize = buffer.getLong(section + ELF64_SH_ENTSIZE)
            val stringTableIndex = buffer.getInt(section + ELF64_SH_LINK).toLong() and 0xffffffffL
            if (symbolsSize < 0 || symbolEntrySize < ELF64_SYMBOL_SIZE || stringTableIndex >= sectionCount) {
                throw GradleException("Invalid ELF64 symbol table in $objectFile")
            }
            if (symbolsOffset.toLong() + symbolsSize > bytes.size.toLong()) {
                throw GradleException("ELF64 symbol table exceeds file bounds: $objectFile")
            }

            val stringsSection = sectionOffset + stringTableIndex.toInt() * sectionEntrySize
            val stringsOffset = buffer.getLong(stringsSection + ELF64_SH_OFFSET).checkedOffset("string table", objectFile)
            val stringsSize = buffer.getLong(stringsSection + ELF64_SH_SIZE)
            if (stringsSize < 0 || stringsOffset.toLong() + stringsSize > bytes.size.toLong()) {
                throw GradleException("ELF64 string table exceeds file bounds: $objectFile")
            }

            val symbolCount = symbolsSize / symbolEntrySize
            if (symbolCount > Int.MAX_VALUE) {
                throw GradleException("ELF64 symbol table is too large: $objectFile")
            }
            repeat(symbolCount.toInt()) symbolLoop@{ symbolIndex ->
                val symbolOffset = symbolsOffset + (symbolIndex * symbolEntrySize).toInt()
                val stringIndex = buffer.getInt(symbolOffset + ELF64_ST_NAME).toLong() and 0xffffffffL
                if (stringIndex <= 0 || stringIndex >= stringsSize) return@symbolLoop
                val name = elfString(bytes, stringsOffset + stringIndex.toInt(), stringsOffset + stringsSize.toInt())
                if (name.isEmpty()) return@symbolLoop

                if (name in makeDefault) {
                    val otherOffset = symbolOffset + ELF64_ST_OTHER
                    val other = bytes[otherOffset].toInt() and 0xff
                    val updated = other and ELF_ST_VISIBILITY_MASK.inv()
                    if (updated != other) {
                        bytes[otherOffset] = updated.toByte()
                        changed = true
                    }
                }
                if (name in makeWeak) {
                    val infoOffset = symbolOffset + ELF64_ST_INFO
                    val info = bytes[infoOffset].toInt() and 0xff
                    if ((info ushr 4) == STB_GLOBAL) {
                        bytes[infoOffset] = ((STB_WEAK shl 4) or (info and 0x0f)).toByte()
                        changed = true
                    }
                }
            }
        }
        if (changed) objectFile.writeBytes(bytes)
    }

    private fun Long.checkedOffset(label: String, objectFile: File): Int {
        if (this < 0 || this > Int.MAX_VALUE) {
            throw GradleException("Invalid ELF64 $label offset in $objectFile")
        }
        return toInt()
    }

    private fun elfString(bytes: ByteArray, start: Int, limit: Int): String {
        var end = start
        while (end < limit && bytes[end].toInt() != 0) end++
        return if (end > start) String(bytes, start, end - start, Charsets.UTF_8) else ""
    }

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

    private companion object {
        const val ELF_EI_CLASS = 4
        const val ELF_EI_DATA = 5
        const val ELFCLASS64 = 2
        const val ELFDATA2LSB = 1
        const val ELF64_HEADER_SIZE = 64
        const val ELF64_SECTION_HEADER_SIZE = 64
        const val ELF64_SYMBOL_SIZE = 24L
        const val ELF64_E_SHOFF = 40
        const val ELF64_E_SHENTSIZE = 58
        const val ELF64_E_SHNUM = 60
        const val ELF64_SH_TYPE = 4
        const val ELF64_SH_OFFSET = 24
        const val ELF64_SH_SIZE = 32
        const val ELF64_SH_LINK = 40
        const val ELF64_SH_ENTSIZE = 56
        const val ELF64_ST_NAME = 0
        const val ELF64_ST_INFO = 4
        const val ELF64_ST_OTHER = 5
        const val SHT_SYMTAB = 2
        const val SHT_DYNSYM = 11
        const val STB_GLOBAL = 1
        const val STB_WEAK = 2
        const val ELF_ST_VISIBILITY_MASK = 0x03
    }
}
