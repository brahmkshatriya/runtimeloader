@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.runtimeloader

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

public data class NativeModuleMetadata(
    public val formatVersion: Int,
    public val moduleId: String,
    public val target: String,
    public val kotlinVersion: String,
    public val hostAbi: String,
    public val moduleFingerprint: String,
    public val dependencyFingerprint: String,
)

public data class NativeHostMetadata(
    public val formatVersion: Int,
    public val target: String,
    public val kotlinVersion: String,
    public val hostAbi: String,
    public val dependencyFingerprint: String,
)

public class NativeLoadedCode internal constructor(
    override val path: String,
    @PublishedApi internal val library: COpaquePointer,
    public val metadata: NativeModuleMetadata,
) : LoadedCode {
    private var closed: Boolean = false
    private val retainedEntries: MutableList<StableRef<Any>> = mutableListOf()

    override val isClosed: Boolean
        get() = closed

    /** Resolve an application-defined symbol from the attached module. */
    public fun symbol(name: String): COpaquePointer {
        check(!closed) { "Loaded code handle is closed" }
        return resolveNativeSymbol(library, name)
    }

    internal fun retainEntry(reference: StableRef<Any>): Any {
        check(!closed) { "Loaded code handle is closed" }
        retainedEntries += reference
        return reference.get()
    }

    override fun close() {
        if (closed) return
        // Intentionally no dlclose: Kotlin objects/type metadata/lambdas may retain code pointers.
        // StableRef handles created by createEntry() are no longer needed once callers close this
        // handle; objects still strongly referenced by host code remain ordinary Kotlin objects.
        retainedEntries.forEach(StableRef<Any>::dispose)
        retainedEntries.clear()
        closed = true
    }
}

public object NativeRuntimeLoader {
    public const val MODULE_INIT_SYMBOL: String = "runtime_loader_module_init"
    public const val HOST_MANIFEST_NAME: String = "runtime-loader-host.properties"
    public const val MODULE_MANIFEST_SUFFIX: String = ".runtime-loader.properties"

    public fun load(
        path: String,
        hostManifestPath: String = defaultHostManifestPath(path),
    ): NativeLoadedCode {
        val metadata = validate(path, hostManifestPath)
        val library = openNativeLibrary(path)
        val initSymbol = resolveNativeSymbol(library, MODULE_INIT_SYMBOL)
        initSymbol.reinterpret<CFunction<() -> Unit>>().invoke()
        return NativeLoadedCode(path, library, metadata)
    }

    public fun readModuleMetadata(path: String): NativeModuleMetadata =
        parseModuleMetadata(readProperties("$path$MODULE_MANIFEST_SUFFIX"))

    public fun readHostMetadata(path: String): NativeHostMetadata =
        parseHostMetadata(readProperties(path))

    public fun validate(
        path: String,
        hostManifestPath: String = defaultHostManifestPath(path),
    ): NativeModuleMetadata {
        val host = readHostMetadata(hostManifestPath)
        val module = readModuleMetadata(path)
        validateCompatibility(host, module)
        return module
    }

    public fun defaultHostManifestPath(modulePath: String): String {
        val slash = maxOf(modulePath.lastIndexOf('/'), modulePath.lastIndexOf('\\'))
        return if (slash < 0) HOST_MANIFEST_NAME else modulePath.substring(0, slash + 1) + HOST_MANIFEST_NAME
    }

    private fun validateCompatibility(host: NativeHostMetadata, module: NativeModuleMetadata) {
        if (host.formatVersion != module.formatVersion) incompatible("manifest format", host.formatVersion.toString(), module.formatVersion.toString())
        if (host.target != module.target) incompatible("target", host.target, module.target)
        if (host.kotlinVersion != module.kotlinVersion) incompatible("Kotlin/Native compiler", host.kotlinVersion, module.kotlinVersion)
        if (host.hostAbi != module.hostAbi) incompatible("host ABI", host.hostAbi, module.hostAbi)
        if (host.dependencyFingerprint != module.dependencyFingerprint) {
            incompatible("dependency/cache fingerprint", host.dependencyFingerprint, module.dependencyFingerprint)
        }
    }

    private fun incompatible(label: String, host: String, module: String): Nothing =
        throw RuntimeLoaderException(
            "Incompatible Kotlin/Native module $label: host=$host module=$module. Rebuild the module against this host."
        )

    private fun parseModuleMetadata(values: Map<String, String>): NativeModuleMetadata =
        NativeModuleMetadata(
            formatVersion = values.required("formatVersion").toIntOrNull()
                ?: throw RuntimeLoaderException("Invalid module manifest formatVersion"),
            moduleId = values.required("moduleId"),
            target = values.required("target"),
            kotlinVersion = values.required("kotlinVersion"),
            hostAbi = values.required("hostAbi"),
            moduleFingerprint = values.required("moduleFingerprint"),
            dependencyFingerprint = values.required("dependencyFingerprint"),
        )

    private fun parseHostMetadata(values: Map<String, String>): NativeHostMetadata =
        NativeHostMetadata(
            formatVersion = values.required("formatVersion").toIntOrNull()
                ?: throw RuntimeLoaderException("Invalid host manifest formatVersion"),
            target = values.required("target"),
            kotlinVersion = values.required("kotlinVersion"),
            hostAbi = values.required("hostAbi"),
            dependencyFingerprint = values.required("dependencyFingerprint"),
        )

    private fun Map<String, String>.required(key: String): String =
        this[key]?.takeIf { it.isNotEmpty() }
            ?: throw RuntimeLoaderException("Runtime-loader manifest is missing '$key'")

    private fun readProperties(path: String): Map<String, String> {
        val text = readTextFile(path)
        return buildMap {
            text.lineSequence().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
                val separator = line.indexOf('=')
                if (separator <= 0) throw RuntimeLoaderException("Invalid runtime-loader manifest line ${index + 1} in $path")
                put(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
            }
        }
    }

    private fun readTextFile(path: String): String = memScoped {
        val file = fopen(path, "r") ?: throw RuntimeLoaderException("Runtime-loader manifest not found: $path")
        try {
            val buffer = allocArray<ByteVar>(4096)
            buildString {
                while (fgets(buffer, 4096, file) != null) append(buffer.toKString())
            }
        } finally {
            fclose(file)
        }
    }
}

public actual object RuntimeCodeLoader {
    public actual fun load(
        path: String,
        onLoaded: (LoadedCode) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        try {
            onLoaded(NativeRuntimeLoader.load(path))
        } catch (failure: Throwable) {
            onError(failure)
        }
    }
}


public actual fun LoadedCode.createEntryUntyped(): Any {
    val loaded = this as? NativeLoadedCode
        ?: throw RuntimeLoaderException("Expected a Kotlin/Native loaded-code handle")
    check(!loaded.isClosed) { "Loaded code handle is closed" }
    val create = loaded
        .symbol(RuntimeLoaderEntryPoint.NATIVE_SYMBOL_NAME)
        .reinterpret<CFunction<() -> COpaquePointer>>()
    val reference: StableRef<Any> = create().asStableRef()
    return loaded.retainEntry(reference)
}
