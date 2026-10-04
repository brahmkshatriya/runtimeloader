package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** PE/COFF backend for Kotlin/Native mingw_x64.
 *
 * The host executable owns the Kotlin runtime and exports the Kotlin ABI from its caches. Each
 * extension DLL contains only its separately compiled module cache and imports Kotlin symbols from
 * that host executable, so loaded objects stay in the exact host Kotlin/Native object graph.
 */
internal class WindowsPeRuntimeLoaderBackend(
    private val konanHome: File,
) : NativeRuntimeLoaderBackend {
    override val target: String = "mingw_x64"
    override val sharedLibraryExtension: String = "dll"
    override val requiresHostBeforeModules: Boolean = true

    override fun requireTools() {
        requireTool("llvm-ar")
        requireTool("llvm-nm")
        requireTool("llvm-objdump")
        requireTool("llvm-objcopy")
        windowsToolchain()
    }

    override fun runtimeFiles(): List<File> {
        val bin = File(windowsToolchain().sysroot, "bin")
        return listOf(
            "libstdc++-6.dll",
            "libgcc_s_seh-1.dll",
            "libwinpthread-1.dll",
        ).map { name ->
            File(bin, name).also { file ->
                if (!file.isFile) {
                    throw GradleException("Kotlin/Native MinGW runtime DLL is missing: $file")
                }
            }
        }
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
        val host = hostExecutable
            ?: throw GradleException("Windows module linking requires the already-linked host executable")
        val hostImportLibrary = importLibraryFor(host)
        if (!hostImportLibrary.isFile) {
            throw GradleException("Windows host import library is missing: $hostImportLibrary")
        }
        val tools = windowsToolchain()
        output.parentFile.mkdirs()
        val safeId = output.nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val bootstrapSource = File(workDirectory, "module_bootstrap_$safeId.c")
        val objectFile = File(workDirectory, "module_bootstrap_$safeId.o")
        val exportDefinition = File(workDirectory, "module_exports_$safeId.def")
        val initSymbol = "_Konan_init_${module.name}"
        val moduleFunctions = definedFunctionSymbols(moduleArchive).sorted()

        bootstrapSource.writeText(
            """
            #define WIN32_LEAN_AND_MEAN
            #include <windows.h>

            extern void konan_module_init(void)
                __asm__("${cString(initSymbol)}");

            static INIT_ONCE runtime_loader_once = INIT_ONCE_STATIC_INIT;

            static BOOL CALLBACK runtime_loader_init_once(
                PINIT_ONCE once,
                PVOID parameter,
                PVOID *context
            ) {
                (void)once;
                (void)parameter;
                (void)context;
                konan_module_init();
                return TRUE;
            }

            __declspec(dllexport)
            void runtime_loader_module_init(void) {
                InitOnceExecuteOnce(&runtime_loader_once, runtime_loader_init_once, NULL, NULL);
            }
            """.trimIndent() + "\n"
        )
        log("generated PE/COFF module-init bootstrap for ${module.name}")

        exportDefinition.writeText(buildString {
            appendLine("EXPORTS")
            appendLine("  runtime_loader_module_init")
            moduleFunctions.forEach { symbol ->
                require('"' !in symbol) { "Unsupported quote in PE module export symbol: $symbol" }
                appendLine("  \"$symbol\"")
            }
        })
        log("declared ${moduleFunctions.size} Kotlin module functions as PE exports")

        val common = buildList {
            add(tools.clang.absolutePath)
            add("--target=x86_64-w64-windows-gnu")
            add("--sysroot=${tools.sysroot.absolutePath}")
            add("-fuse-ld=lld")
            linkDirectories.forEach { add("-L${it.absolutePath}") }
        }
        runCommand(common + listOf(
            "-O0", "-g", "-c",
            bootstrapSource.absolutePath,
            "-o", objectFile.absolutePath,
        ))

        output.delete()
        runCommand(common + listOf(
            "-shared",
            "-o", output.absolutePath,
            objectFile.absolutePath,
            "-Wl,--whole-archive", moduleArchive.absolutePath,
            "-Wl,--no-whole-archive",
            hostImportLibrary.absolutePath,
            exportDefinition.absolutePath,
            "-static-libgcc",
            "-lbcrypt",
            "-Wl,-Bstatic,--whole-archive", "-lwinpthread",
            "-Wl,--no-whole-archive,-Bdynamic",
        ))
        log("linked PE/COFF module '${module.name}' against host exports")
        return output
    }

    override fun prepareHostCaches(
        resolved: ResolvedGraph,
        staticCache: File,
        hybridCache: File,
        moduleLibraries: List<File>,
        log: (String) -> Unit,
    ) {
        // MinGW platform/cinterop static caches contain wrappers for APIs that are present in the
        // MSVC headers used to build the KLIBs but are not linkable from the GNU CRT. Linking those
        // giant cache objects directly is therefore not viable. Compile platform/cinterop KLIBs
        // normally into the host, and extract only the cache ABI bridge globals that already-cached
        // pure Kotlin libraries reference. Those bridge objects contain no Kotlin runtime of their
        // own; they point directly at the host's normally linked native functions.
        hardlinkCopyTree(staticCache, hybridCache)
        supportObjectDirectory(hybridCache).apply {
            deleteRecursively()
            mkdirs()
        }
        val nativeLibraries = resolved.graph.keys.filter(::isNativeInteropCache).toSet()
        val cachedArchives = resolved.graph.keys
            .filterNot { it in nativeLibraries }
            .mapNotNull { cacheArchive(staticCache, it) }
        val cachedDefinitions = cachedArchives.flatMapTo(linkedSetOf(), ::definedSymbols)
        val requested = linkedSetOf<String>()
        (cachedArchives + moduleLibraries).forEach { requested += undefinedSymbols(it) }
        val requestedCacheAbiSymbols = requested.filterTo(linkedSetOf()) {
            it.startsWith("knifunptr_") || it.startsWith("kfun:")
        }.apply { removeAll(cachedDefinitions) }

        var supportObjects = 0
        var abiSymbols = 0
        var keptSections = 0
        nativeLibraries.forEach { name ->
            val archive = cacheArchive(staticCache, name) ?: return@forEach
            val objectInfo = parseArchive(name, archive)
            try {
                val seeds = requestedCacheAbiSymbols.filter { it in objectInfo.symbolSections }
                if (seeds.isNotEmpty()) {
                    val symbolsBySection = objectInfo.symbolSections.entries
                        .groupBy({ it.value }, { it.key })
                    val unwindSectionsByTarget = linkedMapOf<String, MutableSet<String>>()
                    objectInfo.relocations.forEach { (section, targets) ->
                        if (!section.startsWith(".pdata") && !section.startsWith(".xdata")) return@forEach
                        targets.forEach { target ->
                            unwindSectionsByTarget.getOrPut(target) { linkedSetOf() }.add(section)
                        }
                    }
                    val pendingSections = ArrayDeque<String>()

                    fun includeSection(section: String) {
                        if (objectInfo.includedSections.add(section)) pendingSections.addLast(section)
                    }

                    seeds.forEach { seed -> includeSection(objectInfo.symbolSections.getValue(seed)) }
                    while (pendingSections.isNotEmpty()) {
                        val section = pendingSections.removeFirst()
                        objectInfo.relocations[section].orEmpty().forEach { dependency ->
                            objectInfo.symbolSections[dependency]?.let(::includeSection)
                        }

                        // PE unwind metadata references the code it describes rather than being
                        // referenced by the code. Pull those reverse edges into the same closure.
                        symbolsBySection[section].orEmpty().forEach { symbol ->
                            unwindSectionsByTarget[symbol].orEmpty().forEach(::includeSection)
                        }
                    }

                    val output = supportObjectFor(hybridCache, name)
                    writeAbiSupportObject(objectInfo, output)
                    renameSupportObjectRefptrAliases(output, cachedDefinitions, name)
                    supportObjects++
                    abiSymbols += seeds.size
                    keptSections += objectInfo.includedSections.size
                }
            } finally {
                objectInfo.temporaryDirectory.deleteRecursively()
            }
        }
        log(
            "prepared $supportObjects Windows/PE cache ABI support objects with $abiSymbols roots " +
                "across $keptSections COFF sections; platform/cinterop KLIBs will compile directly into the host"
        )

        patchMsvcCrtDefinitions(hybridCache, resolved.graph.keys, log)
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
        val temporaryFiles = File(output.parentFile, "konan-link-temp").apply {
            deleteRecursively()
            mkdirs()
        }
        val supportObjects = order
            .filter(::isNativeInteropCache)
            .map { supportObjectFor(hybridCache, it) }
            .filter(File::isFile)
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
            "-Xtemporary-files-dir=${temporaryFiles.absolutePath}",
            "-Xverbose-phases=Linker",
            // Skiko contributes an explicit MinGW-compatible -lstdc++ import library. Kotlin/Native
            // also asks clang++ to add a static libstdc++ automatically, which creates a second set
            // of the same C++ ABI definitions. Suppress only that implicit driver library and keep
            // the explicit shared libstdc++ dependency used by the Compose desktop stack.
            "-linker-option", "-nostdlib++",
            "-output", output.absolutePath,
        )
        linkDirectories.forEach { directory ->
            command += listOf("-linker-option", "-L${directory.absolutePath}")
        }
        linkerOptions.forEach { option -> command += listOf("-linker-option", option) }
        order.forEach { name ->
            val item = graph.getValue(name)
            val linkLibrary = sanitizedHostLibrary(hybridCache, name, item.path)
            command += listOf("-library", linkLibrary.absolutePath)
            if (!isNativeInteropCache(name)) {
                command += "-Xcached-library=${item.path.absolutePath},${cacheDir(hybridCache, name).absolutePath}"
            }
        }
        supportObjects.forEach { objectFile ->
            command += listOf("-linker-option", objectFile.absolutePath)
        }
        log(
            "linking Windows/PE host with cached Kotlin libraries, direct platform/cinterop codegen, " +
                "and ${supportObjects.size} cache ABI support objects"
        )
        runCommand(command)

        val moduleImports = moduleLibraries.flatMapTo(linkedSetOf()) { undefinedSymbols(it) }
        val discoverySymbols = definedSymbols(output)
        // --gc-sections can discard host symbols referenced only by dynamic modules. Include pure
        // cache and bridge-object definitions as export candidates; the final .def roots exactly
        // the subset imported by extension DLLs.
        val hostDefinitionKinds = definedSymbolKinds(output).toMutableMap()
        order.asSequence()
            .filterNot(::isNativeInteropCache)
            .mapNotNull { name -> cacheArchive(hybridCache, name) }
            .forEach { archive ->
                definedSymbolKinds(archive).forEach { (symbol, kind) ->
                    hostDefinitionKinds.putIfAbsent(symbol, kind)
                }
            }
        supportObjects.forEach { objectFile ->
            definedSymbolKinds(objectFile).forEach { (symbol, kind) ->
                hostDefinitionKinds.putIfAbsent(symbol, kind)
            }
        }
        val hostDefinitions = hostDefinitionKinds.keys
        val requiredExports = moduleImports.filterTo(sortedSetOf()) { it in hostDefinitions }
        val exportDefinition = File(output.parentFile, "${output.nameWithoutExtension}.runtime-loader.def")
        exportDefinition.writeText(buildString {
            appendLine("EXPORTS")
            requiredExports.forEach { symbol ->
                require('"' !in symbol) { "Unsupported quote in PE export symbol: $symbol" }
                // PE import libraries must distinguish code from data. Without DATA, lld emits a
                // text import thunk even for Kotlin type-info globals such as kclass:Foo. Kotlin
                // code then treats that thunk's machine code as TypeInfo, which only fails later
                // when a module object crosses into the host (for example an `is Plugin` check).
                // Preserve the COFF symbol kind here so data imports bind to the host address.
                val data = hostDefinitionKinds[symbol]?.let(::isDataSymbolKind) == true
                append("  \"")
                append(symbol)
                append('"')
                if (data) append(" DATA")
                appendLine()
            }
        })
        val importLibrary = importLibraryFor(output).apply { delete() }

        output.delete()
        temporaryFiles.deleteRecursively()
        temporaryFiles.mkdirs()
        val finalCommand = buildList {
            addAll(command)
            add("-linker-option")
            add(exportDefinition.absolutePath)
            add("-linker-option")
            add("-Wl,--out-implib=${importLibrary.absolutePath}")
        }
        log(
            "relinking Windows/PE host with ${requiredExports.size} module-required exports " +
                "and import library ${importLibrary.name}"
        )
        runCommand(finalCommand)
        if (!importLibrary.isFile) {
            throw GradleException("Windows host linker did not produce import library: $importLibrary")
        }
        return output
    }

    /**
     * The Skiko MinGW cinterop currently publishes --allow-multiple-definition in its KLIB
     * linkerOpts. Runtime Loader no longer needs that escape hatch after normalizing the support
     * objects and CRT ownership, so link against a private manifest-only copy with that option
     * removed. Never mutate the Gradle module cache.
     */
    private fun sanitizedHostLibrary(hybridCache: File, name: String, source: File): File {
        if (name != "dev.brahmkshatriya.skiko:skiko-cinterop-skiko") return source

        val destinationDirectory = File(hybridCache, "runtime-loader-klibs").apply { mkdirs() }
        val destination = File(destinationDirectory, source.name)
        val sourceStamp = "${source.length()}:${source.lastModified()}"
        val stamp = File(destinationDirectory, source.name + ".stamp")
        if (destination.isFile && (if (stamp.isFile) stamp.readText() else null) == sourceStamp) return destination

        val temporary = File(destinationDirectory, source.name + ".tmp").apply { delete() }
        var changed = false
        ZipFile(source).use { input ->
            ZipOutputStream(temporary.outputStream().buffered()).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val replacement = ZipEntry(entry.name).apply {
                        time = entry.time
                        comment = entry.comment
                        extra = entry.extra
                    }
                    output.putNextEntry(replacement)
                    val bytes = input.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "default/manifest") {
                        val text = bytes.toString(Charsets.UTF_8)
                        val sanitized = text
                            .replace("-Wl,--allow-multiple-definition ", "")
                            .replace(" -Wl,--allow-multiple-definition", "")
                        if (sanitized != text) changed = true
                        output.write(sanitized.toByteArray(Charsets.UTF_8))
                    } else {
                        output.write(bytes)
                    }
                    output.closeEntry()
                }
            }
        }
        if (!changed) {
            temporary.delete()
            throw GradleException(
                "Expected Skiko MinGW cinterop linkerOpts to contain --allow-multiple-definition: $source"
            )
        }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        stamp.writeText(sourceStamp)
        return destination
    }

    private fun importLibraryFor(host: File): File =
        File(host.parentFile, "${host.nameWithoutExtension}.import.a")

    private fun definedSymbols(binary: File): Set<String> =
        definedSymbolKinds(binary).keys

    private fun definedSymbolKinds(binary: File): Map<String, Char> =
        runCommand(
            listOf(requireTool("llvm-nm"), "-g", "--defined-only", binary.absolutePath),
            capture = true,
        ).output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size != 3 || parts[2].isBlank() || parts[2].endsWith(":")) return@mapNotNull null
            val kind = parts[1].singleOrNull() ?: return@mapNotNull null
            parts[2] to kind
        }.toMap()

    private fun isDataSymbolKind(kind: Char): Boolean =
        kind.uppercaseChar() in setOf('B', 'C', 'D', 'G', 'R', 'S', 'V')

    private fun definedFunctionSymbols(binary: File): Set<String> =
        runCommand(
            listOf(requireTool("llvm-nm"), "-g", "--defined-only", binary.absolutePath),
            capture = true,
        ).output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 3)
            if (parts.size == 3 && parts[1] in setOf("T", "W")) parts[2] else null
        }.toSet()

    private fun isNativeInteropCache(name: String): Boolean =
        name.startsWith("org.jetbrains.kotlin.native.platform.") || "-cinterop-" in name

    private class ParsedCoffObject(
        val libraryName: String,
        val sourceArchive: File,
        val sourceObject: File,
        val temporaryDirectory: File,
        val symbolSections: Map<String, String>,
        val relocations: Map<String, Set<String>>,
    ) {
        // Selection state changes while the dependency closure is traversed. Keep it out of the
        // data-class identity/hashCode because ParsedCoffObject is also used as a map key.
        val includedSections: MutableSet<String> = linkedSetOf()
    }

    private fun parseArchive(libraryName: String, archive: File): ParsedCoffObject {
        val ar = requireTool("llvm-ar")
        val objdump = requireTool("llvm-objdump")
        val temporary = Files.createTempDirectory("runtime-loader-pe-cache-").toFile()
        val members = runCommand(listOf(ar, "t", archive.absolutePath), capture = true)
            .output.lineSequence().filter { it.isNotBlank() }.toList()
        if (members.size != 1) {
            temporary.deleteRecursively()
            throw GradleException("Expected one COFF object in Kotlin/Native cache $libraryName, found ${members.size}")
        }
        runCommand(listOf(ar, "x", archive.absolutePath), cwd = temporary)
        val objectFile = File(temporary, members.single())

        val sectionNames = mutableMapOf<Int, String>()
        val pendingSymbols = mutableMapOf<Int, MutableList<String>>()
        val symbolSections = linkedMapOf<String, String>()
        val relocations = linkedMapOf<String, MutableSet<String>>()
        var relocationSection: String? = null
        var inSymbols = false

        val process = ProcessBuilder(objdump, "-t", "-r", objectFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                when {
                    line == "SYMBOL TABLE:" -> {
                        inSymbols = true
                        relocationSection = null
                    }
                    line.startsWith("RELOCATION RECORDS FOR [") -> {
                        inSymbols = false
                        relocationSection = line.substringAfter('[').substringBeforeLast("]:" )
                    }
                    inSymbols && line.startsWith("[") && "](sec" in line -> {
                        val sectionStart = line.indexOf("](sec") + 5
                        val sectionEnd = line.indexOf(')', sectionStart)
                        if (sectionEnd <= sectionStart) return@forEach
                        val sectionNumber = line.substring(sectionStart, sectionEnd).trim().toIntOrNull()
                            ?: return@forEach
                        if (sectionNumber <= 0) return@forEach
                        val symbol = line.substringAfterLast(' ').trim()
                        if (symbol.isEmpty()) return@forEach
                        if (symbol.startsWith('.')) {
                            sectionNames.putIfAbsent(sectionNumber, symbol)
                            pendingSymbols.remove(sectionNumber).orEmpty().forEach { pending ->
                                symbolSections.putIfAbsent(pending, symbol)
                            }
                        }
                        val sectionName = sectionNames[sectionNumber]
                        if (sectionName != null) {
                            symbolSections.putIfAbsent(symbol, sectionName)
                        } else {
                            pendingSymbols.getOrPut(sectionNumber) { mutableListOf() }.add(symbol)
                        }
                    }
                    relocationSection != null && line.isNotEmpty() && line.first().isDigit() -> {
                        val parts = line.split(Regex("\\s+"), limit = 3)
                        if (parts.size == 3 && parts[0].all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                            val target = parts[2]
                                .substringBefore("+0x")
                                .substringBefore("-0x")
                            if (target.isNotBlank()) {
                                relocations.getOrPut(relocationSection!!) { linkedSetOf() }.add(target)
                            }
                        }
                    }
                }
            }
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            temporary.deleteRecursively()
            throw GradleException("llvm-objdump failed for Kotlin/Native cache $libraryName with exit $exitCode")
        }
        return ParsedCoffObject(
            libraryName = libraryName,
            sourceArchive = archive,
            sourceObject = objectFile,
            temporaryDirectory = temporary,
            symbolSections = symbolSections,
            relocations = relocations,
        )
    }

    private fun supportObjectDirectory(hybridCache: File): File =
        File(hybridCache.parentFile, "mingw-cache-abi")

    private fun supportObjectFor(hybridCache: File, libraryName: String): File {
        val safeName = libraryName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return File(supportObjectDirectory(hybridCache), "$safeName.o")
    }

    private fun writeAbiSupportObject(objectInfo: ParsedCoffObject, destination: File) {
        if (objectInfo.includedSections.isEmpty()) return
        destination.parentFile.mkdirs()
        val objcopy = requireTool("llvm-objcopy")
        val selected = File(objectInfo.temporaryDirectory, "cache-abi-selected.o")
        val sectionPatterns = collapsedSectionPatterns(objectInfo)
        val command = mutableListOf(objcopy)
        sectionPatterns.forEach { section ->
            command += "--only-section=$section"
        }
        command += objectInfo.sourceObject.absolutePath
        command += selected.absolutePath
        runCommand(command)

        // Section selection alone leaves undefined symbols from discarded MSVC-only wrappers in
        // the COFF symbol table. A second pass removes only symbols no retained relocation needs.
        // Keeping this separate from section selection avoids llvm-objcopy validating relocations
        // against sections that are about to be discarded.
        val stripped = File(objectInfo.temporaryDirectory, "cache-abi-stripped.o")
        runCommand(listOf(objcopy, "--strip-unneeded", selected.absolutePath, stripped.absolutePath))

        // --strip-unneeded removes COFF section-definition auxiliary symbols. If the retained
        // sections are still marked COMDAT, lld can then see a public symbol in llvm-nm while
        // silently rejecting its section as an invalid/incomplete COMDAT definition. These ABI
        // support sections are already an exact, unique closure, so COMDAT deduplication is not
        // needed. Re-state their ordinary section flags to clear IMAGE_SCN_LNK_COMDAT.
        val normalize = mutableListOf(objcopy)
        sectionPatterns.forEach { section ->
            val flags = when {
                section.startsWith(".text") -> "alloc,load,readonly,code,contents"
                section.startsWith(".bss") -> "alloc,data"
                section.startsWith(".data") -> "alloc,load,data,contents"
                section.startsWith(".rdata") || section.startsWith(".pdata") || section.startsWith(".xdata") ->
                    "alloc,load,readonly,data,contents"
                else -> null
            }
            if (flags != null) normalize += "--set-section-flags=$section=$flags"
        }
        destination.delete()
        normalize += stripped.absolutePath
        normalize += destination.absolutePath
        runCommand(normalize)
    }

    /**
     * The original K/N cinterop cache emits .refptr.* sections as COFF COMDAT aliases, so identical
     * copies across caches are normally coalesced. The support-object extraction strips the COMDAT
     * auxiliary records before normalizing section flags. Rename only support-object refptr aliases
     * that already exist in a normal cache; relocations inside the support object follow the renamed
     * alias, while the alias still points at the canonical host data symbol (for example kclass:*).
     */
    private fun renameSupportObjectRefptrAliases(
        objectFile: File,
        cachedDefinitions: Set<String>,
        libraryName: String,
    ) {
        val aliases = definedSymbols(objectFile)
            .filter { it.startsWith(".refptr.") && it in cachedDefinitions }
            .sorted()
        if (aliases.isEmpty()) return

        val objcopy = requireTool("llvm-objcopy")
        val patched = File(objectFile.parentFile, "${objectFile.name}.refptr-patched")
        val safeLibrary = libraryName.replace(Regex("[^A-Za-z0-9_]"), "_")
        val command = mutableListOf(objcopy)
        aliases.forEachIndexed { index, symbol ->
            command += "--redefine-sym=$symbol=runtime_loader_refptr_${safeLibrary}_$index"
        }
        command += objectFile.absolutePath
        command += patched.absolutePath
        runCommand(command)
        Files.move(patched.toPath(), objectFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Skiko's Windows native cache contains a small MSVC CRT compatibility subset because the
     * upstream Skia binary is built with MSVC. A MinGW Kotlin/Native executable supplies equivalent
     * process-level CRT definitions itself. Keeping both strong definitions previously depended
     * on Skiko's allow-multiple-definition linker option and produced noisy/fragile diagnostics.
     *
     * COFF does not support llvm-objcopy's --weaken-symbol. Instead rename only the conflicting
     * definitions inside their archive members. References from other members keep their original
     * names and therefore resolve to MinGW's process CRT; references internal to a renamed member
     * follow the renamed compatibility implementation. The rest of Skiko's support archive stays
     * untouched.
     */
    private fun patchMsvcCrtDefinitions(
        hybridCache: File,
        libraryNames: Collection<String>,
        log: (String) -> Unit,
    ) {
        val skikoName = libraryNames.firstOrNull { it == "dev.brahmkshatriya.skiko:skiko" } ?: return
        val archive = cacheArchive(hybridCache, skikoName) ?: return
        val collisions = setOf(
            "atexit",
            "_FindPESection",
            "_IsNonwritableInCurrentImage",
            "_ValidateImageBase",
            "__dyn_tls_init",
            "__dyn_tls_init_callback",
        )
        val nm = requireTool("llvm-nm")
        val ar = requireTool("llvm-ar")
        val objcopy = requireTool("llvm-objcopy")
        val definitionsByMember = linkedMapOf<String, MutableSet<String>>()
        val archivePrefix = "${archive.absolutePath}:"

        runCommand(listOf(nm, "-A", "-g", "--defined-only", archive.absolutePath), capture = true)
            .output.lineSequence()
            .forEach { line ->
                val match = Regex("^(.*): [0-9A-Fa-f]+ [A-Za-z] (.+)$").matchEntire(line.trim())
                    ?: return@forEach
                val symbol = match.groupValues[2]
                if (symbol !in collisions) return@forEach
                val qualifiedMember = match.groupValues[1]
                if (!qualifiedMember.startsWith(archivePrefix)) return@forEach
                val member = qualifiedMember.removePrefix(archivePrefix)
                definitionsByMember.getOrPut(member) { linkedSetOf() }.add(symbol)
            }

        if (definitionsByMember.isEmpty()) return

        // Break hardlinks to static-cache before rewriting this runtime-loader-only cache view.
        val detached = File(archive.parentFile, "${archive.name}.runtime-loader-patched")
        Files.copy(archive.toPath(), detached.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(detached.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val patchDirectory = Files.createTempDirectory("runtime-loader-pe-skiko-").toFile()
        try {
            definitionsByMember.entries.forEachIndexed { memberIndex, (member, symbols) ->
                val original = File(patchDirectory, "member-$memberIndex.obj")
                val basename = member.substringAfterLast('\\').substringAfterLast('/')
                val patched = File(patchDirectory, "patched-$memberIndex-$basename")
                val extract = ProcessBuilder(ar, "p", archive.absolutePath, member)
                    .redirectOutput(original)
                    .start()
                val stderr = extract.errorStream.bufferedReader().readText()
                val exit = extract.waitFor()
                if (exit != 0) {
                    throw GradleException("llvm-ar could not extract '$member' from ${archive.name}: $stderr")
                }

                val redefine = mutableListOf(objcopy)
                symbols.sorted().forEach { symbol ->
                    val safeSymbol = symbol.replace(Regex("[^A-Za-z0-9_]"), "_")
                    redefine += "--redefine-sym=$symbol=runtime_loader_msvc_${safeSymbol}_$memberIndex"
                }
                redefine += original.absolutePath
                redefine += patched.absolutePath
                runCommand(redefine)
                runCommand(listOf(ar, "d", archive.absolutePath, member))
                runCommand(listOf(ar, "r", archive.absolutePath, patched.absolutePath))
            }
            runCommand(listOf(ar, "s", archive.absolutePath))
        } finally {
            patchDirectory.deleteRecursively()
        }
        log(
            "redirected ${definitionsByMember.values.sumOf { it.size }} Skiko MSVC CRT " +
                "definitions to the MinGW process CRT"
        )
    }

    private fun collapsedSectionPatterns(objectInfo: ParsedCoffObject): List<String> {
        val remaining = objectInfo.includedSections.toMutableSet()
        val patterns = mutableListOf<String>()

        // K/N cinterop emits three external-type metadata sections for nearly every declaration.
        // Large platform KLIBs (notably Win32) contain thousands of each. When the dependency
        // closure already selected an entire family, one wildcard is exactly equivalent to tens of
        // thousands of --only-section arguments and keeps the objcopy invocation below ARG_MAX.
        listOf(
            ".rdata\$kextname:",
            ".rdata\$kextoff:",
            ".rdata\$kexttype:",
        ).forEach { prefix ->
            val family = objectInfo.symbolSections.values
                .asSequence()
                .filter { it.startsWith(prefix) }
                .toSet()
            if (family.isNotEmpty() && family.all { it in remaining }) {
                remaining.removeAll(family)
                patterns += "$prefix*"
            }
        }
        patterns += remaining.sorted()
        return patterns
    }

    private fun undefinedSymbols(archive: File): Set<String> =
        runCommand(
            listOf(requireTool("llvm-nm"), "-g", "-u", archive.absolutePath),
            capture = true,
        ).output.lineSequence().mapNotNull { line ->
            line.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.isNotBlank() && !it.endsWith(":") }
        }.toSet()

    private data class Toolchain(val clang: File, val sysroot: File)

    private fun windowsToolchain(): Toolchain {
        val properties = Properties().apply {
            File(konanHome, "konan/konan.properties").inputStream().use(::load)
        }
        val llvmName = properties.getProperty("llvm.linux_x64.user")
            ?: throw GradleException("Kotlin/Native LLVM dependency is missing for the Linux host")
        val sysrootName = properties.getProperty("toolchainDependency.mingw_x64")
            ?: throw GradleException("Kotlin/Native MinGW toolchain dependency is missing")
        val dependencies = File(konanHome.parentFile, "dependencies")
        val llvm = File(dependencies, llvmName)
        val sysroot = File(dependencies, sysrootName)
        val clang = File(llvm, "bin/clang")
        if (!clang.isFile || !sysroot.isDirectory) {
            throw GradleException(
                "Kotlin/Native Windows cross-toolchain is incomplete. Expected $clang and $sysroot. " +
                    "Compile a mingwX64 target once so Kotlin/Native downloads its dependencies."
            )
        }
        return Toolchain(clang, sysroot)
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
