package dev.brahmkshatriya.runtimeloader.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.security.MessageDigest
import java.util.Properties

internal class NativeRuntimeLoaderPipeline(
    private val rootDirectory: File,
    private val workDirectory: File,
    private val outputDirectory: File,
    private val target: String,
    private val hostLibraries: List<File>,
    private val hostKlib: File,
    private val modules: List<ModuleBuildInput>,
    private val entryPoint: String,
    private val executableName: String,
    private val linkDirectories: List<File>,
    private val linkerOptions: List<String>,
    private val resolveMissingKlibs: (String, Set<String>) -> List<File>,
    private val log: (String) -> Unit,
) {
    private val staticCache = File(workDirectory, "static-cache")
    private val hybridCache = File(workDirectory, "hybrid-cache")
    private val out = outputDirectory
    private val graphFile = File(workDirectory, "graph.properties")
    private lateinit var konanHome: File
    private lateinit var konanc: File
    private lateinit var compilerJar: File
    private lateinit var inspector: KlibInspector
    private lateinit var backend: NativeRuntimeLoaderBackend

    fun build(): NativeRuntimeLoaderOutputs {
        if (modules.isEmpty()) throw GradleException("At least one runtime-loader module must be configured")
        modules.forEach {
            require(it.id.isNotBlank() && '\n' !in it.id && '=' !in it.id) { "Invalid module id: ${it.id}" }
        }

        val initialPaths = (hostLibraries + hostKlib).map { it.canonicalFile }.distinct()
        konanHome = detectKonanHome(initialPaths)
        if (target == "mingw_x64") {
            val patched = prepareMingwCacheEnabledCompiler(konanHome, workDirectory)
            konanc = patched.executable
            compilerJar = patched.compilerJar
            log("using Runtime Loader's isolated MinGW cache-enabled Kotlin/Native compiler patch")
        } else {
            konanc = resolveKonanTool(konanHome, "kotlinc-native")
            compilerJar = File(konanHome, "konan/lib/kotlin-native-compiler-embeddable.jar")
        }
        inspector = KlibInspector(konanHome)
        backend = backendFor(target, konanHome)
        backend.requireTools()
        modules.forEach {
            require(it.outputName.endsWith(".${backend.sharedLibraryExtension}")) {
                "${backend.target} module output must end in .${backend.sharedLibraryExtension}: ${it.outputName}"
            }
        }
        if (!konanc.isFile) throw GradleException("Kotlin/Native compiler is missing: $konanc")

        val moduleCache = gradleModuleCache(initialPaths)
        val resolved = resolveFullGraph(initialPaths, moduleCache)
        val order = topoOrder(resolved.graph)
        val kotlinVersion = compilerVersion()
        if (kotlinVersion != RUNTIME_LOADER_SUPPORTED_KOTLIN_VERSION) {
            throw GradleException(
                "Runtime Loader 0.1.x requires Kotlin/Native $RUNTIME_LOADER_SUPPORTED_KOTLIN_VERSION; " +
                    "the resolved compiler is $kotlinVersion at $konanHome"
            )
        }
        val compilerCacheIdentity = compilerCacheIdentity(kotlinVersion)
        prepareCacheRoot(resolved, compilerCacheIdentity)
        buildHostCaches(resolved.graph, order)

        val dependencyFingerprint = dependencyFingerprint(resolved.graph)
        val hostAbi = sha256Text(
            buildString {
                appendLine("formatVersion=1")
                appendLine("target=$target")
                appendLine("kotlinVersion=$kotlinVersion")
                appendLine("entryPoint=$entryPoint")
                appendLine("dependencyFingerprint=$dependencyFingerprint")
            }
        )

        out.mkdirs()
        val moduleCaches = modules.map { input ->
            val (module, archive) = buildModuleCache(input.klib, resolved.graph, order)
            Triple(input, module, archive)
        }
        val executableFile = File(out, executableName)
        val linkedModules: List<Triple<ModuleBuildInput, Pair<Klib, File>, File>>
        val executable: File
        if (backend.requiresHostBeforeModules) {
            backend.prepareHostCaches(
                resolved = resolved,
                staticCache = staticCache,
                hybridCache = hybridCache,
                moduleLibraries = moduleCaches.map { it.third },
                log = log,
            )
            executable = backend.linkHost(
                graph = resolved.graph,
                order = order,
                konanc = konanc,
                hybridCache = hybridCache,
                hostKlib = hostKlib,
                entryPoint = entryPoint,
                output = executableFile,
                moduleLibraries = moduleCaches.map { it.third },
                linkDirectories = linkDirectories,
                linkerOptions = linkerOptions,
                log = log,
            )
            linkedModules = moduleCaches.map { (input, module, archive) ->
                val sharedLibrary = backend.linkModule(
                    module = module,
                    moduleArchive = archive,
                    output = File(out, input.outputName),
                    workDirectory = workDirectory,
                    hostExecutable = executable,
                    linkDirectories = linkDirectories,
                    log = log,
                )
                Triple(input, module to archive, sharedLibrary)
            }
        } else {
            linkedModules = moduleCaches.map { (input, module, archive) ->
                val sharedLibrary = backend.linkModule(
                    module = module,
                    moduleArchive = archive,
                    output = File(out, input.outputName),
                    workDirectory = workDirectory,
                    hostExecutable = null,
                    linkDirectories = linkDirectories,
                    log = log,
                )
                Triple(input, module to archive, sharedLibrary)
            }
            backend.prepareHostCaches(
                resolved = resolved,
                staticCache = staticCache,
                hybridCache = hybridCache,
                moduleLibraries = linkedModules.map { it.third },
                log = log,
            )
            executable = backend.linkHost(
                graph = resolved.graph,
                order = order,
                konanc = konanc,
                hybridCache = hybridCache,
                hostKlib = hostKlib,
                entryPoint = entryPoint,
                output = executableFile,
                moduleLibraries = moduleCaches.map { it.third },
                linkDirectories = linkDirectories,
                linkerOptions = linkerOptions,
                log = log,
            )
        }

        backend.runtimeFiles().forEach { runtimeFile ->
            val destination = File(out, runtimeFile.name)
            runtimeFile.copyTo(destination, overwrite = true)
            log("runtime: $destination")
        }

        val hostManifest = File(out, "runtime-loader-host.properties")
        writeProperties(
            hostManifest,
            linkedMapOf(
                "formatVersion" to "1",
                "target" to target,
                "kotlinVersion" to kotlinVersion,
                "hostAbi" to hostAbi,
                "dependencyFingerprint" to dependencyFingerprint,
            ),
        )
        if (executable.isDirectory && executable.extension == "framework") {
            hostManifest.copyTo(File(executable, hostManifest.name), overwrite = true)
        }

        val artifacts = linkedModules.map { (input, moduleAndArchive, sharedLibrary) ->
            val (module, archive) = moduleAndArchive
            val moduleFingerprint = sha256Text(
                "klib=${sha256Path(module.path)}\ncache=${sha256Path(archive)}\n"
            )
            val manifest = if (sharedLibrary.isDirectory) {
                File(sharedLibrary, sharedLibrary.nameWithoutExtension + ".runtime-loader.properties")
            } else {
                File(sharedLibrary.absolutePath + ".runtime-loader.properties")
            }
            writeProperties(
                manifest,
                linkedMapOf(
                    "formatVersion" to "1",
                    "moduleId" to input.id,
                    "target" to target,
                    "kotlinVersion" to kotlinVersion,
                    "hostAbi" to hostAbi,
                    "moduleFingerprint" to moduleFingerprint,
                    "dependencyFingerprint" to dependencyFingerprint,
                ),
            )
            ModuleBuildArtifact(input.id, module, archive, sharedLibrary, manifest)
        }

        log("host ABI: $hostAbi")
        log("host:   $executable")
        artifacts.forEach { log("module '${it.id}': ${it.sharedLibrary}") }
        return NativeRuntimeLoaderOutputs(executable, hostManifest, artifacts)
    }

    private fun compilerVersion(): String {
        val properties = Properties().apply {
            File(konanHome, "konan/konan.properties").inputStream().use(::load)
        }
        return properties.getProperty("compilerVersion")
            ?: throw GradleException("Kotlin/Native compilerVersion is missing from konan.properties")
    }

    private fun compilerCacheIdentity(version: String): String {
        if (!compilerJar.isFile) {
            throw GradleException("Kotlin/Native compiler JAR is missing: $compilerJar")
        }
        return sha256Text(
            buildString {
                appendLine("version=$version")
                appendLine("target=$target")
                appendLine("konanHome=${konanHome.canonicalPath}")
                appendLine("compilerJar=${sha256Path(compilerJar)}")
                appendLine("cachePolicy=${if (target == "mingw_x64") "all-build-selective-link-v1" else "all-v1"}")
            }
        )
    }

    private fun dependencyFingerprint(graph: Map<String, Klib>): String = sha256Text(
        buildString {
            graph.toSortedMap().forEach { (name, klib) ->
                val archive = cacheArchive(staticCache, name, target)
                    ?: throw GradleException("Host cache is missing while fingerprinting: $name")
                append(name)
                append('=')
                append(sha256Path(klib.path))
                append(':')
                append(sha256Path(archive))
                append('\n')
            }
        }
    )

    private fun writeProperties(file: File, values: Map<String, String>) {
        file.parentFile.mkdirs()
        file.writeText(values.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" })
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHex()

    private fun sha256Path(path: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        if (path.isFile) {
            digest.update(path.name.toByteArray())
            path.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } else if (path.isDirectory) {
            path.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(path).invariantSeparatorsPath }.forEach { file ->
                digest.update(file.relativeTo(path).invariantSeparatorsPath.toByteArray())
                digest.update(0)
                file.inputStream().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        } else {
            throw GradleException("Cannot fingerprint missing path: $path")
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun detectKonanHome(paths: List<File>): File {
        paths.forEach { library ->
            val normalized = library.invariantSeparatorsPath
            if ("/klib/common/stdlib" in normalized && "kotlin-native-prebuilt-" in normalized) {
                return library.canonicalFile.parentFile.parentFile.parentFile
            }
        }
        throw GradleException("Could not derive Kotlin/Native home from the resolved stdlib KLIB")
    }

    private inner class KlibInspector(private val home: File) {
        private val klibTool = resolveKonanTool(home, "klib")
        private val cache = mutableMapOf<File, Klib>()

        fun info(path: File): Klib {
            val canonical = path.canonicalFile
            return cache.getOrPut(canonical) {
                val text = runCommand(
                    listOf(klibTool.absolutePath, "info", canonical.absolutePath),
                    capture = true,
                ).output
                val name = Regex("(?m)^  unique_name=(.*)$").find(text)?.groupValues?.get(1)
                    ?: throw GradleException("KLIB has no unique_name: $canonical")
                val depends = Regex("(?m)^  depends=(.*)$").find(text)
                    ?.groupValues?.get(1)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.split(Regex("\\s+"))
                    .orEmpty()
                Klib(name, canonical, depends)
            }
        }
    }

    private fun gradleModuleCache(paths: List<File>): File {
        val marker = "/caches/modules-2/files-2.1/"
        for (path in paths) {
            val value = path.invariantSeparatorsPath
            val index = value.indexOf(marker)
            if (index >= 0) return File(value.substring(0, index + marker.length - 1))
        }
        val fallback = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
        if (fallback.isDirectory) return fallback
        throw GradleException("Could not locate Gradle's modules-2/files-2.1 cache")
    }

    private fun moduleVersionEvidence(paths: Collection<File>): Map<String, Set<String>> {
        val versions = linkedMapOf<String, MutableSet<String>>()
        val seen = mutableSetOf<File>()
        paths.forEach { path ->
            if (!path.isFile || "modules-2/files-2.1" !in path.invariantSeparatorsPath) return@forEach
            val versionDir = path.parentFile?.parentFile ?: return@forEach
            versionDir.walkTopDown().maxDepth(2)
                .filter { it.isFile && it.extension == "module" }
                .forEach { moduleFile ->
                    if (!seen.add(moduleFile)) return@forEach
                    val data = runCatching { JsonSlurper().parse(moduleFile) as? Map<*, *> }.getOrNull()
                        ?: return@forEach
                    val variants = data["variants"] as? List<*> ?: return@forEach
                    variants.forEach { variantAny ->
                        val variant = variantAny as? Map<*, *> ?: return@forEach
                        val dependencies = variant["dependencies"] as? List<*> ?: return@forEach
                        dependencies.forEach { dependencyAny ->
                            val dependency = dependencyAny as? Map<*, *> ?: return@forEach
                            val group = dependency["group"] as? String ?: return@forEach
                            val module = dependency["module"] as? String ?: return@forEach
                            val versionData = dependency["version"] as? Map<*, *> ?: return@forEach
                            val version = sequenceOf("requires", "strictly", "prefers")
                                .mapNotNull { versionData[it] as? String }
                                .firstOrNull { it.firstOrNull()?.isDigit() == true }
                                ?: return@forEach
                            versions.getOrPut("$group:$module") { linkedSetOf() }.add(version)
                        }
                    }
                }
        }
        return versions
    }

    private fun cinteropParentUniqueName(uniqueName: String): String? {
        if (':' !in uniqueName) return null
        val group = uniqueName.substringBefore(':')
        val module = uniqueName.substringAfter(':')
        val parentModule = module.substringBefore("-cinterop-", missingDelimiterValue = "")
        return parentModule.takeIf(String::isNotEmpty)?.let { "$group:$it" }
    }

    private fun cachedMavenVersion(path: File): String? {
        val marker = "/caches/modules-2/files-2.1/"
        val normalized = path.invariantSeparatorsPath
        val index = normalized.indexOf(marker)
        if (index < 0) return null
        val relative = normalized.substring(index + marker.length)
        return relative.split('/').getOrNull(2)?.takeIf(String::isNotEmpty)
    }

    private fun findMavenKlib(uniqueName: String, evidence: Set<String>, moduleCache: File): File {
        if (':' !in uniqueName) throw GradleException("Missing non-Maven KLIB identity: $uniqueName")
        val group = uniqueName.substringBefore(':')
        val module = uniqueName.substringAfter(':')
        val parentModule = cinteropParentUniqueName(uniqueName)?.substringAfter(':')
        val groupDir = File(moduleCache, group)
        val suffix = when (target) {
            "linux_x64" -> "linuxx64"
            "linux_arm64" -> "linuxarm64"
            "mingw_x64" -> "mingwx64"
            "macos_x64" -> "macosx64"
            "macos_arm64" -> "macosarm64"
            "ios_arm64" -> "iosarm64"
            else -> throw GradleException("Unsupported runtime-loader target: $target")
        }
        val artifactModules = listOfNotNull(module, parentModule).distinct()
        val artifactDirs = artifactModules.flatMap { artifactModule ->
            listOf(File(groupDir, "$artifactModule-$suffix"), File(groupDir, artifactModule))
        }.distinct()
        val availableVersions = artifactDirs.flatMap { dir ->
            dir.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        }
        // KLIB manifests sometimes retain an upstream compatibility-module unique name/version
        // even when Gradle substituted the concrete platform implementation. Prefer the version
        // evidenced by the resolved graph, but allow another cached version with the exact same
        // KLIB unique name for these empty compatibility shims.
        val versions = (evidence + availableVersions)
            .distinct()
            .sortedWith(
                compareByDescending<String> { it in evidence }
                    .then(naturalVersionComparator.reversed())
            )

        val candidates = mutableListOf<Pair<String, File>>()
        versions.forEach { version ->
            artifactDirs.forEach { artifactDir ->
                val versionDir = File(artifactDir, version)
                if (!versionDir.isDirectory) return@forEach
                versionDir.walkTopDown().maxDepth(2)
                    .filter { it.isFile && it.extension == "klib" }
                    .forEach { candidate ->
                        runCatching { inspector.info(candidate) }.getOrNull()
                            ?.takeIf { it.name == uniqueName }
                            ?.let { candidates += version to candidate }
                    }
            }
        }
        if (candidates.isEmpty() && evidence.isNotEmpty()) {
            resolveMissingKlibs(uniqueName, evidence).forEach { candidate ->
                runCatching { inspector.info(candidate) }.getOrNull()
                    ?.takeIf { it.name == uniqueName }
                    ?.let { candidates += evidence.maxWithOrNull(naturalVersionComparator)!! to candidate }
            }
        }
        if (candidates.isEmpty()) {
            val expected = evidence.sortedWith(naturalVersionComparator).joinToString().ifEmpty { "an available version" }
            throw GradleException(
                "Could not find Native KLIB $uniqueName ($expected) in $moduleCache or resolve it through Gradle."
            )
        }
        return candidates.maxWithOrNull { a, b -> naturalVersionComparator.compare(a.first, b.first) }!!.second
    }

    private fun resolveFullGraph(initialPaths: List<File>, moduleCache: File): ResolvedGraph {
        val graph = linkedMapOf<String, Klib>()
        val implementation = linkedSetOf<String>()
        val compatibility = linkedSetOf<String>()
        initialPaths.forEach { path ->
            val info = inspector.info(path)
            graph[info.name] = info
            implementation += info.name
        }

        while (true) {
            val missing = graph.values.flatMap { it.depends }.filterNot { it in graph }.distinct().sorted()
            if (missing.isEmpty()) break
            val evidence = moduleVersionEvidence(graph.values.map { it.path })
            var added = false
            missing.forEach { name ->
                val platformPath = File(konanHome, "klib/platform/$target/$name")
                val info = if (platformPath.exists()) {
                    inspector.info(platformPath).also { implementation += it.name }
                } else {
                    val parentName = cinteropParentUniqueName(name)
                    val dependerVersions = graph.values.asSequence()
                        .filter { name in it.depends }
                        .mapNotNull { cachedMavenVersion(it.path) }
                        .toSet()
                    val resolutionEvidence = buildSet {
                        addAll(evidence[name].orEmpty())
                        if (parentName != null) {
                            addAll(evidence[parentName].orEmpty())
                            graph[parentName]?.path?.let(::cachedMavenVersion)?.let(::add)
                        }
                        addAll(dependerVersions)
                    }
                    inspector.info(findMavenKlib(name, resolutionEvidence, moduleCache))
                        .also { compatibility += it.name }
                }
                graph[info.name] = info
                added = true
            }
            if (!added) throw GradleException("Could not resolve KLIB dependencies: $missing")
        }
        log("resolved ${graph.size} KLIB identities (${implementation.size} implementation, ${compatibility.size} compatibility)")
        return ResolvedGraph(graph, implementation, compatibility)
    }

    private fun topoOrder(graph: Map<String, Klib>): List<String> {
        val dependencies = graph.mapValues { (name, item) -> item.depends.toSet() - name }
        val left = graph.keys.toMutableSet()
        val order = mutableListOf<String>()
        while (left.isNotEmpty()) {
            val ready = left.filter { name -> dependencies.getValue(name).none { it in left } }.sorted()
            if (ready.isEmpty()) throw GradleException("Cycle in KLIB dependency graph: ${left.sorted()}")
            order += ready
            left.removeAll(ready.toSet())
        }
        return order
    }

    private fun transitiveDependencies(name: String, graph: Map<String, Klib>): Set<String> {
        val result = linkedSetOf<String>()
        val stack = ArrayDeque(graph.getValue(name).depends)
        while (stack.isNotEmpty()) {
            val dependency = stack.removeLast()
            if (dependency == name || dependency in result) continue
            val item = graph[dependency] ?: throw GradleException("Unknown dependency $dependency referenced by $name")
            result += dependency
            item.depends.forEach(stack::addLast)
        }
        return result
    }

    private fun prepareCacheRoot(resolved: ResolvedGraph, compilerCacheIdentity: String) {
        workDirectory.mkdirs()
        val old = Properties().apply {
            if (graphFile.isFile) graphFile.inputStream().use(::load)
        }
        val oldCompilerCacheIdentity = old.getProperty("compilerCacheIdentity")
        val oldTarget = old.getProperty("target")
        if (
            staticCache.exists() &&
            (oldCompilerCacheIdentity != compilerCacheIdentity || oldTarget != target)
        ) {
            log("Kotlin/Native compiler/target changed; invalidating all static caches")
            staticCache.deleteRecursively()
            hybridCache.deleteRecursively()
        }
        val invalidated = mutableListOf<String>()
        old.stringPropertyNames().filter { it.startsWith("library.") }.forEach { key ->
            val name = key.removePrefix("library.")
            val newPath = resolved.graph[name]?.path?.absolutePath
            if (newPath != old.getProperty(key)) {
                cacheDir(staticCache, name, target).deleteRecursively()
                invalidated += name
            }
        }
        if (graphFile.isFile && invalidated.isNotEmpty()) {
            log("KLIB graph changed; preserving compatible caches and invalidating ${invalidated.size} identities")
        }
        staticCache.mkdirs()
        Properties().apply {
            setProperty("target", target)
            setProperty("compilerCacheIdentity", compilerCacheIdentity)
            resolved.graph.toSortedMap().forEach { (name, item) -> setProperty("library.$name", item.path.absolutePath) }
            setProperty("implementation", resolved.implementation.sorted().joinToString("|"))
            setProperty("compatibility", resolved.compatibility.sorted().joinToString("|"))
            graphFile.outputStream().use { store(it, "Kotlin/Native runtime-loader cache graph") }
        }
    }

    private fun isLocalKlib(path: File): Boolean = runCatching {
        path.canonicalFile.toPath().startsWith(rootDirectory.canonicalFile.toPath())
    }.getOrDefault(false)

    private fun isCompleteCache(name: String): Boolean {
        val directory = cacheDir(staticCache, name, target)
        if (cacheArchive(staticCache, name, target) == null) return false
        return listOf(
            "metadata.properties",
            "bin/bitcode_deps",
            "ir/class_fields",
            "ir/eager_init",
            "ir/inline_bodies",
            "ir/trivial_getters",
        ).all { relative -> File(directory, relative).isFile }
    }

    private fun buildHostCaches(graph: Map<String, Klib>, order: List<String>) {
        val dirty = linkedSetOf<String>()
        val rank = order.withIndex().associate { it.value to it.index }
        order.forEachIndexed { index, name ->
            val item = graph.getValue(name)
            var shouldBuild = !isCompleteCache(name) || isLocalKlib(item.path)
            if (item.depends.any { it in dirty }) shouldBuild = true
            if (!shouldBuild) {
                log("cache ${"%02d".format(index + 1)}/${order.size} reuse $name")
                return@forEachIndexed
            }

            log("cache ${"%02d".format(index + 1)}/${order.size} build $name")
            cacheDir(staticCache, name, target).deleteRecursively()
            val command = mutableListOf(
                konanc.absolutePath,
                "-target", target,
                "-g",
                "-produce", "static_cache",
                "-Xadd-cache=${item.path.absolutePath}",
                "-Xcache-directory=${staticCache.absolutePath}",
            )
            transitiveDependencies(name, graph).sortedBy { rank.getValue(it) }.forEach { dependency ->
                val dep = graph.getValue(dependency)
                val depCache = cacheDir(staticCache, dependency, target)
                if (!isCompleteCache(dependency)) {
                    throw GradleException("Dependency cache is missing: $dependency")
                }
                command += listOf(
                    "-library", dep.path.absolutePath,
                    "-Xcached-library=${dep.path.absolutePath},${depCache.absolutePath}",
                )
            }
            runCommand(command)
            if (!isCompleteCache(name)) {
                throw GradleException("Kotlin/Native produced an incomplete static cache for $name")
            }
            dirty += name
        }
    }

    private fun buildModuleCache(
        moduleKlib: File,
        graph: Map<String, Klib>,
        order: List<String>,
    ): Pair<Klib, File> {
        val module = inspector.info(moduleKlib)
        cacheDir(staticCache, module.name, target).deleteRecursively()
        val command = mutableListOf(
            konanc.absolutePath,
            "-target", target,
            "-g",
            "-produce", "static_cache",
            "-Xadd-cache=${module.path.absolutePath}",
            "-Xcache-directory=${staticCache.absolutePath}",
        )
        order.forEach { name ->
            val item = graph.getValue(name)
            command += listOf(
                "-library", item.path.absolutePath,
                "-Xcached-library=${item.path.absolutePath},${cacheDir(staticCache, name, target).absolutePath}",
            )
        }
        log("building module '${module.name}' as a separately compiled Kotlin/Native cache")
        runCommand(command)
        val archive = cacheArchive(staticCache, module.name, target)
            ?: throw GradleException("Module static cache was not produced: ${module.name}")
        return module to archive
    }
}

private val naturalVersionComparator = Comparator<String> { left, right ->
    val token = Regex("(\\d+)|([^\\d]+)")
    val a = token.findAll(left).map { it.value }.toList()
    val b = token.findAll(right).map { it.value }.toList()
    val count = maxOf(a.size, b.size)
    for (index in 0 until count) {
        val av = a.getOrNull(index) ?: return@Comparator -1
        val bv = b.getOrNull(index) ?: return@Comparator 1
        val an = av.toLongOrNull()
        val bn = bv.toLongOrNull()
        val comparison = if (an != null && bn != null) an.compareTo(bn) else av.lowercase().compareTo(bv.lowercase())
        if (comparison != 0) return@Comparator comparison
    }
    0
}
