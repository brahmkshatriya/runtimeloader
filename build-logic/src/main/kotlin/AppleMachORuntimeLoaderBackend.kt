package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Mach-O backend for Kotlin/Native macOS and iOS hosts/modules.
 *
 * Kotlin/Native static caches mark most Kotlin symbols as Mach-O private externs. A dylib loaded
 * with dlopen can only resolve against process-visible host symbols, and dlsym can only see
 * externally exported module symbols. For the small subset that crosses the host/module boundary,
 * this backend clears Mach-O's N_PEXT bit in cache object symbol tables before final linking. Empty
 * compatibility shims that duplicate concrete implementation symbols are marked N_WEAK_DEF, which
 * mirrors the ELF backend's weak-symbol cache view without duplicating a Kotlin runtime.
 */
internal class AppleMachORuntimeLoaderBackend(
    override val target: String,
) : NativeRuntimeLoaderBackend {
    init {
        require(target in setOf("macos_x64", "macos_arm64", "ios_arm64")) {
            "Apple Mach-O backend does not support $target"
        }
    }

    override val sharedLibraryExtension: String = if (target == "ios_arm64") "framework" else "dylib"

    private val xcrun: String by lazy { requireTool("xcrun") }
    private val clangTarget: String
        get() = when (target) {
            "macos_x64" -> "x86_64-apple-macos12.0"
            "macos_arm64" -> "arm64-apple-macos12.0"
            "ios_arm64" -> "arm64-apple-ios15.0"
            else -> error("unreachable")
        }

    private val sdkName: String
        get() = if (target == "ios_arm64") "iphoneos" else "macosx"

    private fun xcrun(vararg command: String): List<String> =
        listOf(xcrun, "--sdk", sdkName) + command

    override fun requireTools() {
        if (!System.getProperty("os.name").lowercase().contains("mac")) {
            throw GradleException(
                "The $target Runtime Loader host/modules must be final-linked on macOS with Xcode command-line tools."
            )
        }
        xcrun
        runCommand(xcrun("--find", "clang"), capture = true)
        runCommand(xcrun("--find", "nm"), capture = true)
        runCommand(xcrun("--find", "ar"), capture = true)
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
        output.parentFile.mkdirs()
        val iosFramework = target == "ios_arm64"
        val safeId = output.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val bootstrapSource = File(workDirectory, "module_bootstrap_$safeId.c")
        val objectFile = File(workDirectory, "module_bootstrap_$safeId.o")
        val initSymbol = "_Konan_init_${module.name}"

        exposeModuleSymbols(moduleArchive, initSymbol, log)
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
        runCommand(
            xcrun(
                "clang",
                "-target", clangTarget,
                "-O0", "-g", "-c",
                bootstrapSource.absolutePath,
                "-o", objectFile.absolutePath,
            )
        )

        val binaryOutput = if (iosFramework) {
            output.deleteRecursively()
            output.mkdirs()
            File(output, output.nameWithoutExtension)
        } else {
            output.delete()
            output
        }
        val installName = if (iosFramework) {
            "@rpath/${output.name}/${binaryOutput.name}"
        } else {
            "@rpath/${output.name}"
        }
        val command = xcrun(
            "clang",
            "-target", clangTarget,
            "-dynamiclib",
            "-undefined", "dynamic_lookup",
            "-Wl,-install_name,$installName",
            objectFile.absolutePath,
            "-Wl,-force_load,${moduleArchive.absolutePath}",
            "-o", binaryOutput.absolutePath,
        ).toMutableList()
        linkDirectories.forEach { directory ->
            command += "-L${directory.absolutePath}"
            command += "-F${directory.absolutePath}"
        }
        runCommand(command)

        if (iosFramework) {
            writeIosFrameworkInfoPlist(output, binaryOutput.name, safeId)
            log("linked unsigned iOS framework module '${module.name}' with host-resolved Kotlin imports")
        } else {
            log("linked Mach-O module '${module.name}' with host-resolved Kotlin imports")
        }
        return output
    }

    private fun exposeModuleSymbols(archive: File, initSymbol: String, log: (String) -> Unit) {
        val temp = Files.createTempDirectory("runtime-loader-macho-module-").toFile()
        try {
            val members = archiveMembers(archive)
            runCommand(xcrun("ar", "x", archive.absolutePath), cwd = temp)
            var exposed = 0
            var initExposed = false
            members.forEach { member ->
                val objectFile = File(temp, member)
                if (!objectFile.isFile) return@forEach
                val symbols = definedFunctionSymbols(objectFile)
                val externalSymbols = symbols + initSymbol
                val matched = patchMachOObjectSymbols(
                    objectFile,
                    makeExternal = externalSymbols,
                    makeWeak = emptySet(),
                )
                if (initSymbol in matched) initExposed = true
                exposed += symbols.size
            }
            rebuildArchive(archive, temp, members)
            if (!initExposed) {
                throw GradleException("Kotlin/Native cache is missing module init symbol '$initSymbol': $archive")
            }
            log("exposed $exposed Mach-O module functions plus '$initSymbol' for module bootstrap")
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
        log("creating Apple/Mach-O host cache view for ${moduleLibraries.size} module(s)")
        hardlinkCopyTree(staticCache, hybridCache)

        val imports = moduleLibraries.flatMapTo(linkedSetOf(), ::undefinedSymbols)
        val implementationSymbols = resolved.implementation.flatMapTo(linkedSetOf()) { name ->
            cacheArchive(staticCache, name)?.let(::definedSymbols).orEmpty()
        }
        val exposed = linkedSetOf<String>()
        var weakened = 0
        var changedArchives = 0

        resolved.graph.keys.forEach { name ->
            val archive = cacheArchive(hybridCache, name) ?: return@forEach
            val temp = Files.createTempDirectory("runtime-loader-macho-cache-").toFile()
            try {
                val members = archiveMembers(archive)
                runCommand(xcrun("ar", "x", archive.absolutePath), cwd = temp)
                var archiveChanged = false
                members.forEach memberLoop@{ member ->
                    val objectFile = File(temp, member)
                    if (!objectFile.isFile) return@memberLoop
                    val defined = definedSymbols(objectFile)
                    val makeExternal = (defined intersect imports).toSet()
                    val makeWeak = if (name in resolved.compatibility) {
                        (defined intersect implementationSymbols)
                            .filterNot { "Konan_init_" in it }
                            .toSet()
                    } else emptySet()
                    if (makeExternal.isEmpty() && makeWeak.isEmpty()) return@memberLoop
                    patchMachOObjectSymbols(objectFile, makeExternal, makeWeak)
                    exposed += makeExternal
                    weakened += makeWeak.size
                    archiveChanged = true
                }
                if (archiveChanged) {
                    rebuildArchive(archive, temp, members)
                    changedArchives++
                }
            } finally {
                temp.deleteRecursively()
            }
        }

        val systemImports = imports - exposed
        log(
            "Mach-O cache view: exported ${exposed.size} module symbols, weakened $weakened " +
                "compatibility definitions across $changedArchives archives"
        )
        if (systemImports.isNotEmpty()) {
            log("remaining module imports are supplied by Apple/system frameworks: ${systemImports.sorted().joinToString()}")
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
        if (target == "ios_arm64") {
            return linkIosHostFramework(
                graph = graph,
                order = order,
                konanc = konanc,
                hybridCache = hybridCache,
                hostKlib = hostKlib,
                output = output,
                moduleLibraries = moduleLibraries,
                linkDirectories = linkDirectories,
                linkerOptions = linkerOptions,
                log = log,
            )
        }

        output.delete()
        val moduleImports = moduleLibraries.flatMapTo(linkedSetOf(), ::undefinedSymbols)
        val hostDefinitions = order.flatMapTo(linkedSetOf()) { name ->
            cacheArchive(hybridCache, name)?.let(::definedSymbols).orEmpty()
        }
        val requiredExports = (moduleImports intersect hostDefinitions).toSortedSet()

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
            "-output", output.absolutePath,
        )
        // Kotlin/Native mangled symbol names contain '#'. Apple's exported-symbol list parser
        // treats lines beginning with '#' as comments and, in practice, also tokenizes these
        // mangled names at '#', producing bogus initial undefined symbols such as
        // `_kfun:androidx.compose.runtime`. Pass each export directly to ld instead so the symbol
        // name remains opaque and the linker also retains it through dead stripping.
        requiredExports.forEach { symbol ->
            command += listOf(
                "-linker-option", "-exported_symbol",
                "-linker-option", symbol,
            )
        }
        appendLinkInputs(command, graph, order, hybridCache, linkDirectories, linkerOptions)
        log("linking macOS/Mach-O host with ${requiredExports.size} module-required process exports")
        runCommand(command)
        return output
    }

    private fun linkIosHostFramework(
        graph: Map<String, Klib>,
        order: List<String>,
        konanc: File,
        hybridCache: File,
        hostKlib: File,
        output: File,
        moduleLibraries: List<File>,
        linkDirectories: List<File>,
        linkerOptions: List<String>,
        log: (String) -> Unit,
    ): File {
        require(output.extension == "framework") {
            "iOS Runtime Loader host output must be a .framework bundle: $output"
        }
        output.deleteRecursively()

        val moduleImports = moduleLibraries.flatMapTo(linkedSetOf(), ::undefinedSymbols)
        val hostDefinitions = order.flatMapTo(linkedSetOf()) { name ->
            cacheArchive(hybridCache, name)?.let(::definedSymbols).orEmpty()
        }
        val requiredExports = (moduleImports intersect hostDefinitions).toSortedSet()
        val frameworkBase = File(output.parentFile, output.nameWithoutExtension)
        frameworkBase.deleteRecursively()

        val command = mutableListOf(
            konanc.absolutePath,
            "-g",
            "-enable-assertions",
            "-nostdlib",
            "-produce", "framework",
            "-target", target,
            "-Xmulti-platform",
            "-Xpre-link-caches=disable",
            "-Xexport-library=${hostKlib.absolutePath}",
            "-module-name", output.nameWithoutExtension,
            "-output", frameworkBase.absolutePath,
        )
        // K/N-generated public Objective-C exports must remain visible, so unlike the macOS program
        // path we do not use an exported-symbol allow-list. Force the module-required Kotlin ABI
        // roots to survive dead stripping while the cache view makes those symbols external.
        requiredExports.forEach { symbol ->
            command += listOf("-linker-option", "-Wl,-u,$symbol")
        }
        appendLinkInputs(command, graph, order, hybridCache, linkDirectories, linkerOptions)
        log("linking iOS/Mach-O host framework with ${requiredExports.size} module-required Kotlin exports")
        runCommand(command)

        if (!output.isDirectory) {
            throw GradleException("Kotlin/Native did not produce the expected iOS framework: $output")
        }
        return output
    }

    private fun appendLinkInputs(
        command: MutableList<String>,
        graph: Map<String, Klib>,
        order: List<String>,
        hybridCache: File,
        linkDirectories: List<File>,
        linkerOptions: List<String>,
    ) {
        linkDirectories.forEach { directory ->
            command += listOf("-linker-option", "-L${directory.absolutePath}")
            command += listOf("-linker-option", "-F${directory.absolutePath}")
        }
        linkerOptions.forEach { option -> command += listOf("-linker-option", option) }
        order.forEach { name ->
            val item = graph.getValue(name)
            command += listOf("-library", item.path.absolutePath)
            if (cacheArchive(hybridCache, name) != null) {
                command += "-Xcached-library=${item.path.absolutePath},${cacheDir(hybridCache, name).absolutePath}"
            }
        }
    }

    private fun writeIosFrameworkInfoPlist(framework: File, executable: String, safeId: String) {
        File(framework, "Info.plist").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleExecutable</key><string>$executable</string>
    <key>CFBundleIdentifier</key><string>dev.brahmkshatriya.runtimeloader.module.$safeId</string>
    <key>CFBundleInfoDictionaryVersion</key><string>6.0</string>
    <key>CFBundleName</key><string>$executable</string>
    <key>CFBundlePackageType</key><string>FMWK</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>MinimumOSVersion</key><string>15.0</string>
    <key>CFBundleSupportedPlatforms</key><array><string>iPhoneOS</string></array>
</dict>
</plist>
"""
        )
    }

    private fun archiveMembers(archive: File): List<String> =
        runCommand(xcrun("ar", "t", archive.absolutePath), capture = true)
            .output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot { it.startsWith("__.SYMDEF") }
            .toList()

    private fun rebuildArchive(archive: File, directory: File, members: List<String>) {
        archive.delete()
        archive.parentFile.mkdirs()
        runCommand(xcrun("ar", "rcs", archive.absolutePath) + members, cwd = directory)
    }

    private fun definedFunctionSymbols(binary: File): Set<String> =
        nmDefined(binary).filterTo(linkedSetOf()) { (kind, _) ->
            kind.uppercaseChar() in setOf('T', 'W')
        }.mapTo(linkedSetOf()) { it.second }

    private fun definedSymbols(binary: File): Set<String> =
        nmDefined(binary).mapTo(linkedSetOf()) { it.second }

    private fun nmDefined(binary: File): List<Pair<Char, String>> {
        val macho = machoBinary(binary)
        val output = runCommand(
            xcrun("nm", "-gU", macho.absolutePath),
            capture = true,
            check = false,
        ).output
        return output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3 || parts.last().endsWith(':')) return@mapNotNull null
            val kind = parts[parts.lastIndex - 1].singleOrNull() ?: return@mapNotNull null
            if (kind.uppercaseChar() == 'U') null else kind to parts.last()
        }.toList()
    }

    private fun undefinedSymbols(binary: File): Set<String> {
        val macho = machoBinary(binary)
        val output = runCommand(
            xcrun("nm", "-u", macho.absolutePath),
            capture = true,
            check = false,
        ).output
        return output.lineSequence().mapNotNullTo(linkedSetOf()) { line ->
            val value = line.trim().split(Regex("\\s+")).lastOrNull()?.takeIf(String::isNotBlank)
            value?.takeUnless { it.endsWith(':') || it == "U" }
        }
    }

    private fun machoBinary(path: File): File =
        if (path.isDirectory && path.extension == "framework") File(path, path.nameWithoutExtension) else path

    /** Clear private-extern and/or add weak-def flags in a thin 64-bit little-endian Mach-O object. */
    private fun patchMachOObjectSymbols(
        objectFile: File,
        makeExternal: Set<String>,
        makeWeak: Set<String>,
    ): Set<String> {
        if (makeExternal.isEmpty() && makeWeak.isEmpty()) return emptySet()
        val bytes = objectFile.readBytes()
        if (bytes.size < MACH_HEADER_64_SIZE) return emptySet()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != MH_MAGIC_64) return emptySet()

        val ncmds = buffer.getInt(16)
        var commandOffset = MACH_HEADER_64_SIZE
        var symoff = -1
        var nsyms = 0
        var stroff = -1
        var strsize = 0
        repeat(ncmds) {
            if (commandOffset < 0 || commandOffset + 8 > bytes.size) return@repeat
            val command = buffer.getInt(commandOffset)
            val commandSize = buffer.getInt(commandOffset + 4)
            if (commandSize < 8 || commandOffset + commandSize > bytes.size) return@repeat
            if (command == LC_SYMTAB && commandSize >= 24) {
                symoff = buffer.getInt(commandOffset + 8)
                nsyms = buffer.getInt(commandOffset + 12)
                stroff = buffer.getInt(commandOffset + 16)
                strsize = buffer.getInt(commandOffset + 20)
            }
            commandOffset += commandSize
        }
        if (symoff < 0 || stroff < 0 || nsyms < 0 || strsize < 0) return emptySet()
        if (symoff.toLong() + nsyms.toLong() * NLIST_64_SIZE > bytes.size) return emptySet()
        if (stroff.toLong() + strsize.toLong() > bytes.size) return emptySet()

        var changed = false
        val matched = linkedSetOf<String>()
        repeat(nsyms) { index ->
            val offset = symoff + index * NLIST_64_SIZE
            val stringIndex = buffer.getInt(offset)
            if (stringIndex <= 0 || stringIndex >= strsize) return@repeat
            val start = stroff + stringIndex
            var end = start
            val stringLimit = stroff + strsize
            while (end < stringLimit && bytes[end].toInt() != 0) end++
            if (end <= start) return@repeat
            val name = String(bytes, start, end - start, Charsets.UTF_8)

            if (name in makeExternal) {
                matched += name
                val type = bytes[offset + 4].toInt() and 0xff
                val updated = (type and N_PEXT.inv()) or N_EXT
                if (updated != type) {
                    bytes[offset + 4] = updated.toByte()
                    changed = true
                }
            }
            if (name in makeWeak) {
                matched += name
                val description = buffer.getShort(offset + 6).toInt() and 0xffff
                val updated = description or N_WEAK_DEF
                if (updated != description) {
                    buffer.putShort(offset + 6, updated.toShort())
                    changed = true
                }
            }
        }
        if (changed) objectFile.writeBytes(bytes)
        return matched
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
        const val MACH_HEADER_64_SIZE = 32
        const val NLIST_64_SIZE = 16
        val MH_MAGIC_64: Int = 0xfeedfacfL.toInt()
        const val LC_SYMTAB = 0x2
        const val N_EXT = 0x01
        const val N_PEXT = 0x10
        const val N_WEAK_DEF = 0x0080
    }
}
