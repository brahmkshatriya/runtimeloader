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

private const val DEFAULT_STORE_FILE = "demo-extension-store.zip"
private const val DEFAULT_VERIFICATION_REPORT = "runtime-loader-windows-verification.txt"

private var verificationReportPath: String? = null
private val verificationMessages = mutableListOf<String>()
private var extractionSequence: Int = 0

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
    val handlerLaunch = args.isEmpty()
    // The registered desktop handler deliberately starts Windows executables from their
    // containing directory, so keep the no-argument verification paths relative to cwd. This
    // avoids translating Wine drive-letter paths back through POSIX file APIs.
    val inputPath = args.firstOrNull() ?: DEFAULT_STORE_FILE
    val options = if (handlerLaunch) setOf("--store-verify-all") else args.drop(1).toSet()
    verificationMessages.clear()
    verificationReportPath = if (handlerLaunch) DEFAULT_VERIFICATION_REPORT else null
    verificationReportPath?.let(::remove)
    reportDiagnostic("START: Windows Native verification entered Kotlin main (input=$inputPath)")
    try {
        sharedCounter = 0
        pluginRenderCount = 0
        diagnosticMode =
            "--verify-compose" in options ||
                "--store-verify-compose" in options ||
                "--store-verify-all" in options

        if (
            "--store-demo" in options ||
            "--store-smoke" in options ||
            "--store-verify-compose" in options ||
            "--store-verify-all" in options
        ) {
            runExternalStore(inputPath, options)
            return
        }

        RuntimeCodeLoader.load(
            path = inputPath,
            onLoaded = { code -> runDirectModule(code as NativeLoadedCode, options) },
        )
    } catch (failure: Throwable) {
        reportDiagnostic("FAIL: ${failure::class.simpleName}: ${failure.message}")
        throw failure
    }
}

private fun runExternalStore(storePath: String, options: Set<String>) {
    val store = ExternalExtensionStore.fromZip(readFileBytes(storePath))
    val extensions = store.extensionsFor("windows_x64")
    check(extensions.isNotEmpty()) { "External store has no Windows x64 extensions" }
    println("store: loaded ${extensions.size} Windows extension metadata entries from $storePath")
    extensions.forEach { println("store: ${it.id} -> ${it.artifactFor("windows_x64").entry}") }

    val verifyAll = "--store-verify-all" in options
    if (verifyAll) {
        val ids = extensions.mapTo(mutableSetOf()) { it.id }
        check("counter" in ids && "about" in ids) {
            "Windows verification requires both demo extensions; found ${ids.sorted()}"
        }
        verifyAbiRejectedBeforeLoad(store, extensions.first())
    }

    var openedForCompose: List<NativeOpenedExtension>? = null
    if ("--store-smoke" in options || verifyAll) {
        val opened = extensions.mapIndexed { index, metadata ->
            sharedCounter = 0
            val opened = openNativeExtension(store, metadata)
            opened.plugin.increment()
            val expected = when (metadata.id) {
                "counter" -> 1
                "about" -> 10
                else -> null
            }
            if (expected != null) {
                check(sharedCounter == expected) {
                    "${metadata.id} mutated a different state or wrong value: expected $expected, got $sharedCounter"
                }
            } else {
                check(sharedCounter != 0) { "${metadata.id} did not execute against host state" }
            }
            reportPass(
                "Native store entry ${index + 1}/${extensions.size} '${metadata.id}' " +
                    "resolved from ZIP metadata and mutated exact host state to $sharedCounter"
            )
            opened
        }
        reportPass("Windows Native desktop loaded all compatible extension metadata + code from external store ZIP")
        if (verifyAll) {
            openedForCompose = opened
        } else {
            opened.forEach(NativeOpenedExtension::close)
            return
        }
    }

    if ("--store-verify-compose" in options || verifyAll) {
        val opened = openedForCompose ?: extensions.map { openNativeExtension(store, it) }
        val renderedIds = mutableSetOf<String>()
        pluginRenderCount = 0
        application {
            Window(onCloseRequest = ::exitApplication, title = "Native Extension Store Compose Verification") {
                var extensionIndex by remember { mutableStateOf(0) }
                val current = opened[extensionIndex]
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ExtensionContentPage(current.metadata, current.plugin, onBack = {})
                        SideEffect {
                            if (pluginRenderCount > 0 && renderedIds.add(current.metadata.id)) {
                                reportPass(
                                    "Native external-store extension '${current.metadata.id}' rendered " +
                                        "inside the Windows host Compose tree"
                                )
                                if (renderedIds.size == opened.size) {
                                    if (verifyAll) {
                                        reportPass("Windows Native same-runtime external-store verification completed")
                                    }
                                    exitApplication()
                                } else {
                                    pluginRenderCount = 0
                                    extensionIndex += 1
                                }
                            }
                        }
                    }
                }
            }
        }
        opened.forEach(NativeOpenedExtension::close)
        return
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "External Extension Store — Windows Native") {
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
    val artifact = metadata.artifactFor("windows_x64")
    val directory = "runtime-loader-store-${getpid()}-${metadata.id}-${extractionSequence++}"
    mkdir(directory)

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

private fun verifyAbiRejectedBeforeLoad(store: ExternalExtensionStore, metadata: StoreExtension) {
    val artifact = metadata.artifactFor("windows_x64")
    val hostManifestResource = artifact.resources.firstOrNull {
        it.substringAfterLast('/') == "runtime-loader-host.properties"
    } ?: error("${metadata.id} store artifact is missing the host ABI manifest")
    val moduleManifestResource = artifact.resources.firstOrNull {
        it.endsWith(".runtime-loader.properties") && !it.endsWith("runtime-loader-host.properties")
    } ?: error("${metadata.id} store artifact is missing the module ABI manifest")

    val directory = "runtime-loader-abi-test-${getpid()}-${extractionSequence++}"
    mkdir(directory)
    val fakeModule = "$directory/incompatible.dll"
    val hostManifest = "$directory/runtime-loader-host.properties"
    val moduleManifest = "$fakeModule.runtime-loader.properties"
    writeFileBytes(fakeModule, "not a PE file".encodeToByteArray())
    writeFileBytes(hostManifest, store.file(hostManifestResource))
    val incompatibleManifest = store.file(moduleManifestResource).decodeToString().replace(
        Regex("(?m)^hostAbi=.*$"),
        "hostAbi=0000000000000000000000000000000000000000000000000000000000000000",
    )
    writeFileBytes(moduleManifest, incompatibleManifest.encodeToByteArray())

    var failure: Throwable? = null
    RuntimeCodeLoader.load(
        path = fakeModule,
        onLoaded = { error("Incompatible module unexpectedly reached native loading") },
        onError = { failure = it },
    )
    val message = failure?.message.orEmpty()
    check("Incompatible Kotlin/Native module host ABI" in message) {
        "Expected host ABI rejection before LoadLibraryW, got: $message"
    }
    reportPass("Windows ABI mismatch was rejected before LoadLibraryW")

    remove(moduleManifest)
    remove(hostManifest)
    remove(fakeModule)
    rmdir(directory)
}

private fun runDirectModule(loaded: NativeLoadedCode, options: Set<String>) {
    val plugin = loaded.createEntry<Plugin>()

    if ("--smoke" in options) {
        println("host: attached native module ${loaded.metadata.moduleId} (ABI ${loaded.metadata.hostAbi.take(12)}...)")
        println("host: counter before extension = $sharedCounter")
        plugin.increment()
        println("host: counter after extension = $sharedCounter")
        check(sharedCounter > 0)
        reportPass("app resolved its entry symbol from runtime-loaded Kotlin/Native code and mutated exact host state")
        loaded.close()
        loaded.close()
        check(loaded.isClosed)
        reportPass("loaded-code close is idempotent")
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

private fun reportPass(message: String) {
    reportDiagnostic("PASS: $message")
}

private fun reportDiagnostic(line: String) {
    println(line)
    verificationMessages += line
    verificationReportPath?.let { path ->
        writeFileBytes(path, (verificationMessages.joinToString("\n") + "\n").encodeToByteArray())
    }
}

private fun readFileBytes(path: String): ByteArray = memScoped {
    val file = fopen(path, "rb") ?: error("Could not open $path")
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Could not seek $path" }
        val size = ftell(file)
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
