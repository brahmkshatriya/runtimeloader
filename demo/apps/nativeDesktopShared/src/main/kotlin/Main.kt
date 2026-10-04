@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.runtimeloader.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.brahmkshatriya.runtimeloader.NativeLoadedCode
import dev.brahmkshatriya.runtimeloader.RuntimeCodeLoader
import dev.brahmkshatriya.runtimeloader.createEntry
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rmdir

private const val STORE_PLATFORM = RUNTIME_LOADER_STORE_PLATFORM
private const val STORE_PLATFORM_LABEL = RUNTIME_LOADER_STORE_PLATFORM_LABEL

private class NativeOpenedExtension(
    val metadata: StoreExtension,
    val plugin: Plugin,
    private val code: NativeLoadedCode,
    private val directory: String,
    private val files: List<String>,
) {
    fun close() {
        code.close()
        files.forEach(::remove)
        rmdir(directory)
    }
}

public fun main(args: Array<String>) {
    val inputPath = args.firstOrNull() ?: error("Pass the module .so or external store ZIP path as the first argument")
    val options = args.drop(1).toSet()
    sharedCounter = 0
    pluginRenderCount = 0
    diagnosticMode = "--verify-compose" in options || "--store-verify-compose" in options

    if ("--store-demo" in options || "--store-smoke" in options || "--store-verify-compose" in options) {
        runExternalStore(inputPath, options)
        return
    }

    RuntimeCodeLoader.load(
        path = inputPath,
        onLoaded = { code -> runDirectModule(code as NativeLoadedCode, options) },
    )
}

private fun runExternalStore(storePath: String, options: Set<String>) {
    val store = ExternalExtensionStore.fromZip(readFileBytes(storePath))
    val extensions = store.extensionsFor(STORE_PLATFORM)
    check(extensions.isNotEmpty()) { "External store has no $STORE_PLATFORM_LABEL extensions" }
    println("store: loaded ${extensions.size} $STORE_PLATFORM_LABEL extension metadata entries from $storePath")
    extensions.forEach { println("store: ${it.id} -> ${it.artifactFor(STORE_PLATFORM).entry}") }

    if ("--store-smoke" in options) {
        extensions.forEachIndexed { index, metadata ->
            sharedCounter = 0
            val opened = openNativeExtension(store, metadata)
            opened.plugin.increment()
            check(sharedCounter > 0) { "${metadata.id} did not execute against host state" }
            println("PASS: Native store entry ${index + 1}/${extensions.size} '${metadata.id}' resolved from ZIP metadata")
            opened.close()
        }
        println("PASS: Native desktop loaded all compatible extension metadata + code from external store ZIP")
        return
    }

    if ("--store-verify-compose" in options) {
        val opened = openNativeExtension(store, extensions.first())
        pluginRenderCount = 0
        application {
            Window(onCloseRequest = ::exitApplication, title = "Native Extension Store Compose Verification") {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ExtensionContentPage(opened.metadata, opened.plugin, onBack = {})
                        SideEffect {
                            if (pluginRenderCount > 0) {
                                println("PASS: Native external-store extension opened on a Compose detail page")
                                exitApplication()
                            }
                        }
                    }
                }
            }
        }
        opened.close()
        return
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "External Extension Store — Native Desktop") {
            var opened by remember { mutableStateOf<NativeOpenedExtension?>(null) }
            DisposableEffect(Unit) {
                onDispose { opened?.close() }
            }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val current = opened
                    if (current == null) {
                        ExtensionStorePage(
                            extensions = extensions,
                            onOpen = { metadata ->
                                opened?.close()
                                opened = openNativeExtension(store, metadata)
                            },
                        )
                    } else {
                        ExtensionContentPage(
                            extension = current.metadata,
                            plugin = current.plugin,
                            onBack = {
                                current.close()
                                opened = null
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun openNativeExtension(
    store: ExternalExtensionStore,
    metadata: StoreExtension,
): NativeOpenedExtension {
    val artifact = metadata.artifactFor(STORE_PLATFORM)
    val directory = "/tmp/runtime-loader-store-${getpid()}-${metadata.id}"
    mkdir(directory, 0x1FFu)

    val paths = (listOf(artifact.path) + artifact.resources).map { archivePath ->
        val outputPath = "$directory/${archivePath.substringAfterLast('/')}"
        writeFileBytes(outputPath, store.file(archivePath))
        outputPath
    }
    val modulePath = paths.first()

    var loaded: NativeLoadedCode? = null
    RuntimeCodeLoader.load(
        path = modulePath,
        onLoaded = { loaded = it as NativeLoadedCode },
        onError = { throw it },
    )
    val code = checkNotNull(loaded)
    val instance = code.createEntry<Plugin>()
    println("store: opened '${metadata.name}' from ${artifact.path}")
    return NativeOpenedExtension(metadata, instance, code, directory, paths)
}

private fun runDirectModule(loaded: NativeLoadedCode, options: Set<String>) {
    val plugin = loaded.createEntry<Plugin>()

    if ("--smoke" in options) {
        println("host: attached native module ${loaded.metadata.moduleId} (ABI ${loaded.metadata.hostAbi.take(12)}...)")
        println("host: counter before extension = $sharedCounter")
        plugin.increment()
        println("host: counter after extension = $sharedCounter")
        check(sharedCounter > 0)
        println("PASS: app resolved its entry symbol from runtime-loaded Kotlin/Native code and mutated exact host state")
        loaded.close()
        loaded.close()
        check(loaded.isClosed)
        println("PASS: loaded-code close is idempotent")
        return
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "Kotlin/Native Runtime Loader") {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { PluginHost(plugin) }
            }
        }
    }

    loaded.close()
}

private fun readFileBytes(path: String): ByteArray = memScoped {
    val file = fopen(path, "rb") ?: error("Could not open $path")
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Could not seek $path" }
        val size = ftell(file).toInt()
        check(size >= 0) { "Could not determine size of $path" }
        check(fseek(file, 0, SEEK_SET) == 0) { "Could not rewind $path" }
        val buffer = allocArray<ByteVar>(size.coerceAtLeast(1))
        val read = fread(buffer, 1.convert(), size.convert(), file).toInt()
        check(read == size) { "Could not read all bytes from $path ($read/$size)" }
        buffer.readBytes(size)
    } finally {
        fclose(file)
    }
}

private fun writeFileBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("Could not create $path")
    try {
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
                check(written == bytes.size) { "Could not write all bytes to $path ($written/${bytes.size})" }
            }
        }
    } finally {
        fclose(file)
    }
}
