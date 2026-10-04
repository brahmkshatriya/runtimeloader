package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

private const val CACHED_LIBRARIES_CLASS =
    "org/jetbrains/kotlin/backend/konan/CachedLibraries.class"
private const val CACHE_SUPPORT_CLASS =
    "org/jetbrains/kotlin/backend/konan/CacheSupport.class"
private const val CACHED_DEPENDENCIES_COMPUTER_CLASS =
    "org/jetbrains/kotlin/backend/konan/DependenciesTrackerImpl${'$'}CachedBitcodeDependenciesComputer.class"
private const val CACHE_BINARIES_RESOLVER_CLASS =
    "org/jetbrains/kotlin/backend/konan/CacheBinariesResolverKt.class"

/**
 * Kotlin/Native 2.4.20 contains full MinGW static-cache production support, but deliberately
 * discards every non-stdlib cache in CachedLibraries. Runtime Loader depends on those caches to
 * keep a dynamically attached module in the host's existing Kotlin object/runtime world.
 *
 * Keep the workaround local: copy the compiler JAR and replace only the hard-coded
 * `target == MINGW_X64` predicate with `false`. The installed Kotlin distribution is never
 * modified. Remove this patch as soon as upstream K/N enables MinGW library caches itself.
 */
internal fun prepareMingwCacheEnabledCompiler(
    konanHome: File,
    workDirectory: File,
): PatchedKonanCompiler {
    val originalJar = File(konanHome, "konan/lib/kotlin-native-compiler-embeddable.jar")
    if (!originalJar.isFile) {
        throw GradleException("Kotlin/Native compiler JAR is missing: $originalJar")
    }

    val patchDirectory = File(workDirectory, "mingw-cache-compiler").apply { mkdirs() }
    val patchedJar = File(patchDirectory, "kotlin-native-compiler-embeddable.jar")
    patchCompilerJar(originalJar, patchedJar)

    val javaExecutable = File(
        System.getProperty("java.home"),
        if (System.getProperty("os.name").lowercase().contains("windows")) "bin/java.exe" else "bin/java",
    )
    if (!javaExecutable.isFile) {
        throw GradleException("Java executable is missing: $javaExecutable")
    }

    val windowsHost = System.getProperty("os.name").lowercase().contains("windows")
    val wrapper = File(patchDirectory, if (windowsHost) "kotlinc-native-runtime-loader.cmd" else "kotlinc-native-runtime-loader")
    if (windowsHost) {
        wrapper.writeText(
            """
            @echo off
            "${javaExecutable.absolutePath}" -ea -Xmx3G -XX:TieredStopAtLevel=1 --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 "-Dkonan.home=${konanHome.absolutePath}" -cp "${patchedJar.absolutePath}" org.jetbrains.kotlin.cli.utilities.MainKt konanc %*
            """.trimIndent() + "\r\n"
        )
    } else {
        wrapper.writeText(
            """
            #!/usr/bin/env bash
            exec '${shellQuote(javaExecutable.absolutePath)}' -ea -Xmx3G -XX:TieredStopAtLevel=1 --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 '-Dkonan.home=${shellQuote(konanHome.absolutePath)}' -cp '${shellQuote(patchedJar.absolutePath)}' org.jetbrains.kotlin.cli.utilities.MainKt konanc "${'$'}@"
            """.trimIndent() + "\n"
        )
        wrapper.setExecutable(true)
    }
    return PatchedKonanCompiler(wrapper, patchedJar)
}

internal data class PatchedKonanCompiler(
    val executable: File,
    val compilerJar: File,
)

private fun patchCompilerJar(source: File, output: File) {
    val sourceStamp = "v7:${source.length()}:${source.lastModified()}"
    val stampFile = File(output.parentFile, "source.stamp")
    if (output.isFile && stampFile.readTextOrNull() == sourceStamp) return

    val temporary = File(output.parentFile, output.name + ".tmp")
    temporary.delete()
    var patchedCachedLibraries = false
    var patchedCacheSupport = false
    var patchedCachedDependencies = false
    var patchedCacheBinariesResolver = false
    JarFile(source).use { input ->
        val manifest = input.manifest
        JarOutputStream(FileOutputStream(temporary), manifest).use { result ->
            val entries = input.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                if (entry.name.startsWith("META-INF/") &&
                    (entry.name.endsWith(".SF") || entry.name.endsWith(".RSA") || entry.name.endsWith(".DSA"))) {
                    continue
                }
                val copy = java.util.jar.JarEntry(entry.name).apply {
                    time = entry.time
                }
                result.putNextEntry(copy)
                val bytes = input.getInputStream(entry).use { it.readBytes() }
                when (entry.name) {
                    CACHED_LIBRARIES_CLASS -> {
                        result.write(patchCachedLibraries(bytes))
                        patchedCachedLibraries = true
                    }
                    CACHE_SUPPORT_CLASS -> {
                        result.write(patchCacheSupport(bytes))
                        patchedCacheSupport = true
                    }
                    CACHED_DEPENDENCIES_COMPUTER_CLASS -> {
                        result.write(patchCachedDependencyFallback(bytes))
                        patchedCachedDependencies = true
                    }
                    CACHE_BINARIES_RESOLVER_CLASS -> {
                        result.write(patchCacheBinariesResolver(bytes))
                        patchedCacheBinariesResolver = true
                    }
                    else -> result.write(bytes)
                }
                result.closeEntry()
            }
        }
    }
    if (!patchedCachedLibraries || !patchedCacheSupport || !patchedCachedDependencies || !patchedCacheBinariesResolver) {
        temporary.delete()
        throw GradleException(
            "Could not patch Kotlin/Native MinGW cache internals: " +
                "CachedLibraries=$patchedCachedLibraries CacheSupport=$patchedCacheSupport " +
                "CachedDependencies=$patchedCachedDependencies CacheBinariesResolver=$patchedCacheBinariesResolver"
        )
    }
    if (output.exists() && !output.delete()) {
        throw GradleException("Could not replace patched Kotlin/Native compiler: $output")
    }
    if (!temporary.renameTo(output)) {
        temporary.copyTo(output, overwrite = true)
        temporary.delete()
    }
    stampFile.writeText(sourceStamp)
}



private fun patchCacheBinariesResolver(bytes: ByteArray): ByteArray {
    val node = ClassNode()
    ClassReader(bytes).accept(node, 0)
    val method = node.methods.singleOrNull {
        it.name == "resolveCacheBinaries" &&
            it.desc.contains("CachedLibraries") &&
            it.desc.contains("DependenciesTrackingResult")
    } ?: throw GradleException(
        "Expected CacheBinariesResolverKt.resolveCacheBinaries(). " +
            "The compiler internals changed and Runtime Loader must be updated."
    )

    val getCache = method.instructions.toArray().filterIsInstance<MethodInsnNode>().singleOrNull {
        it.opcode == Opcodes.INVOKESTATIC &&
            it.owner == "org/jetbrains/kotlin/backend/konan/CachedLibraries" &&
            it.name == "getLibraryCache${'$'}default"
    } ?: throw GradleException("Could not find CachedLibraries.getLibraryCache in resolveCacheBinaries")

    var cursor = getCache.next
    var nonNullJump: JumpInsnNode? = null
    while (cursor != null) {
        if (cursor is JumpInsnNode && cursor.opcode == Opcodes.IFNONNULL) {
            nonNullJump = cursor
            break
        }
        cursor = cursor.next
    }
    val jump = nonNullJump
        ?: throw GradleException("Could not find null-cache branch in resolveCacheBinaries")

    val instructions = method.instructions.toArray().toList()
    val indexes = instructions.withIndex().associate { it.value to it.index }
    val outerContinue = instructions.filterIsInstance<JumpInsnNode>()
        .filter { it.opcode == Opcodes.GOTO }
        .filter { candidate ->
            val source = indexes.getValue(candidate)
            val target = indexes[candidate.label] ?: return@filter false
            source > indexes.getValue(jump) && target < indexes.getValue(getCache)
        }
        .maxByOrNull { indexes.getValue(it) }
        ?.label
        ?: throw GradleException("Could not find outer dependency-loop continuation in resolveCacheBinaries")

    val nullBranch = mutableListOf<org.objectweb.asm.tree.AbstractInsnNode>()
    var old = jump.next
    while (old != null && old !== jump.label) {
        nullBranch += old
        old = old.next
    }
    if (nullBranch.isEmpty()) {
        throw GradleException("Unexpected empty null-cache branch in resolveCacheBinaries")
    }

    val replacement = org.objectweb.asm.tree.InsnList().apply {
        // getLibraryCache() was DUPed before IFNONNULL; consume the remaining null and skip this
        // cache binary. The patched dependency tracker already records the same KLIB as a raw
        // module dependency, so normal MinGW final linking supplies it without a static cache.
        add(InsnNode(Opcodes.POP))
        add(JumpInsnNode(Opcodes.GOTO, outerContinue))
    }
    method.instructions.insert(jump, replacement)
    nullBranch.forEach(method.instructions::remove)

    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray()
}

private fun patchCachedDependencyFallback(bytes: ByteArray): ByteArray {
    val node = ClassNode()
    ClassReader(bytes).accept(node, 0)
    val method = node.methods.singleOrNull {
        it.name == "addDependency" &&
            it.desc == "(Lorg/jetbrains/kotlin/backend/konan/DependenciesTracker\$ResolvedDependency;)V"
    } ?: throw GradleException(
        "Expected CachedBitcodeDependenciesComputer.addDependency(). " +
            "The compiler internals changed and Runtime Loader must be updated."
    )

    val getCache = method.instructions.toArray().filterIsInstance<MethodInsnNode>().singleOrNull {
        it.opcode == Opcodes.INVOKESTATIC &&
            it.owner == "org/jetbrains/kotlin/backend/konan/CachedLibraries" &&
            it.name == "getLibraryCache\$default"
    } ?: throw GradleException("Could not find CachedLibraries.getLibraryCache in addDependency")

    var cursor = getCache.next
    var nonNullJump: JumpInsnNode? = null
    while (cursor != null) {
        if (cursor is JumpInsnNode && cursor.opcode == Opcodes.IFNONNULL) {
            nonNullJump = cursor
            break
        }
        cursor = cursor.next
    }
    val jump = nonNullJump
        ?: throw GradleException("Could not find null-cache branch in addDependency")

    val nullBranch = mutableListOf<org.objectweb.asm.tree.AbstractInsnNode>()
    var old = jump.next
    while (old != null && old !== jump.label) {
        nullBranch += old
        old = old.next
    }
    if (nullBranch.isEmpty()) {
        throw GradleException("Unexpected empty null-cache branch in addDependency")
    }

    val replacement = org.objectweb.asm.tree.InsnList().apply {
        // getLibraryCache() was DUPed before IFNONNULL, so consume the remaining null.
        add(InsnNode(Opcodes.POP))
        // A cache can reference a platform/cinterop KLIB that Runtime Loader intentionally leaves
        // uncached at the final MinGW host link. Treat that dependency conservatively as a whole
        // raw module; normal Kotlin/Native codegen/linking will materialize it in the host.
        add(VarInsnNode(Opcodes.ALOAD, 0))
        add(org.objectweb.asm.tree.FieldInsnNode(
            Opcodes.GETFIELD,
            "org/jetbrains/kotlin/backend/konan/DependenciesTrackerImpl\$CachedBitcodeDependenciesComputer",
            "moduleDependencies",
            "Ljava/util/Set;",
        ))
        add(VarInsnNode(Opcodes.ALOAD, 2))
        add(MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "java/util/Set",
            "add",
            "(Ljava/lang/Object;)Z",
            true,
        ))
        add(InsnNode(Opcodes.POP))
        add(InsnNode(Opcodes.RETURN))
    }
    method.instructions.insert(jump, replacement)
    nullBranch.forEach(method.instructions::remove)

    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray()
}

private fun patchCacheSupport(bytes: ByteArray): ByteArray {
    val node = ClassNode()
    ClassReader(bytes).accept(node, 0)
    val methods = node.methods.filter { it.name == "checkConsistency" && it.desc == "()V" }
    if (methods.size != 1) {
        throw GradleException(
            "Expected exactly one CacheSupport.checkConsistency(), found ${methods.size}. " +
                "The compiler internals changed and Runtime Loader must be updated."
        )
    }
    val method = methods.single()
    method.instructions.clear()
    method.tryCatchBlocks.clear()
    method.localVariables?.clear()
    method.instructions.add(InsnNode(Opcodes.RETURN))
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray()
}

private fun patchCachedLibraries(bytes: ByteArray): ByteArray {
    val node = ClassNode()
    ClassReader(bytes).accept(node, 0)
    var patches = 0
    node.methods.filter { it.name == "<init>" }.forEach { method ->
        var instruction = method.instructions.first
        while (instruction != null) {
            val next = instruction.next
            if (
                instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESTATIC &&
                instruction.owner == "kotlin/jvm/internal/Intrinsics" &&
                instruction.name == "areEqual"
            ) {
                val previous = instruction.previous
                if (
                    previous is FieldInsnNode &&
                    previous.opcode == Opcodes.GETSTATIC &&
                    previous.owner == "org/jetbrains/kotlin/konan/target/KonanTarget${'$'}MINGW_X64" &&
                    previous.name == "INSTANCE"
                ) {
                    // Consume the same two reference operands as Intrinsics.areEqual and leave a
                    // boolean false on the stack, preserving the existing stack-map frame shape.
                    method.instructions.insertBefore(instruction, InsnNode(Opcodes.POP2))
                    method.instructions.insertBefore(instruction, InsnNode(Opcodes.ICONST_0))
                    method.instructions.remove(instruction)
                    patches++
                }
            }
            instruction = next
        }
    }
    if (patches != 1) {
        throw GradleException(
            "Expected to patch exactly one Kotlin/Native MinGW cache guard, patched $patches. " +
                "The compiler internals changed and Runtime Loader must be updated."
        )
    }
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    return writer.toByteArray()
}

private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

private fun shellQuote(value: String): String = value.replace("'", "'\\''")
