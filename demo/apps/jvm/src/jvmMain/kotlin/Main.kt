package dev.brahmkshatriya.runtimeloader.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.brahmkshatriya.runtimeloader.JvmLoadedCode
import dev.brahmkshatriya.runtimeloader.RuntimeCodeLoader
import dev.brahmkshatriya.runtimeloader.createEntry
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess


private class JvmOpenedExtension(
    val metadata: StoreExtension,
    val plugin: Plugin,
    private val code: JvmLoadedCode,
    private val directory: File,
) {
    fun close() {
        code.close()
        directory.deleteRecursively()
    }
}

public fun main(args: Array<String>) {
    val inputPath = args.firstOrNull() ?: error("Pass the module JAR or external store ZIP path as the first argument")
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
        onLoaded = { loaded -> runDirectModule(loaded as JvmLoadedCode, options) },
    )
}

private fun runExternalStore(storePath: String, options: Set<String>) {
    val store = ExternalExtensionStore.fromZip(File(storePath).readBytes())
    val extensions = store.extensionsFor("jvm")
    check(extensions.isNotEmpty()) { "External store has no JVM extensions" }
    println("store: loaded ${extensions.size} JVM extension metadata entries from $storePath")
    extensions.forEach { println("store: ${it.id} -> ${it.artifactFor("jvm").entry}") }

    if ("--store-smoke" in options) {
        extensions.forEachIndexed { index, metadata ->
            sharedCounter = 0
            val opened = openJvmExtension(store, metadata)
            opened.plugin.increment()
            check(sharedCounter > 0) { "${metadata.id} did not execute against host state" }
            println("PASS: JVM store entry ${index + 1}/${extensions.size} '${metadata.id}' resolved from ZIP metadata")
            opened.close()
        }
        println("PASS: JVM loaded all compatible extension metadata + code from external store ZIP")
        return
    }

    if ("--store-verify-compose" in options) {
        val opened = openJvmExtension(store, extensions.first())
        pluginRenderCount = 0
        application {
            Window(onCloseRequest = ::exitApplication, title = "JVM Extension Store Compose Verification") {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        ExtensionContentPage(opened.metadata, opened.plugin, onBack = {})
                    }
                }
                LaunchedEffect(Unit) {
                    delay(500)
                    check(pluginRenderCount > 0) { "Runtime-loaded store composable never entered the host composition" }
                    println("PASS: JVM external-store extension opened on a Compose detail page")
                    opened.close()
                    exitProcess(0)
                }
            }
        }
        opened.close()
        return
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "External Extension Store — JVM") {
            var opened by remember { mutableStateOf<JvmOpenedExtension?>(null) }
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
                                opened = openJvmExtension(store, metadata)
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

private fun openJvmExtension(
    store: ExternalExtensionStore,
    metadata: StoreExtension,
): JvmOpenedExtension {
    val artifact = metadata.artifactFor("jvm")
    val directory = Files.createTempDirectory("runtime-loader-store-${metadata.id}-").toFile()
    val jar = File(directory, "extension.jar")
    jar.writeBytes(store.file(artifact.path))

    var loaded: JvmLoadedCode? = null
    RuntimeCodeLoader.load(
        path = jar.absolutePath,
        onLoaded = { loaded = it as JvmLoadedCode },
        onError = { throw it },
    )
    val code = checkNotNull(loaded)
    val instance = code.createEntry<Plugin>()
    println("store: opened '${metadata.name}' from ${artifact.path}")
    return JvmOpenedExtension(metadata, instance, code, directory)
}

private fun runDirectModule(loaded: JvmLoadedCode, options: Set<String>) {
    val plugin = loaded.createEntry<Plugin>()

    if ("--smoke" in options) {
        println("host: attached JVM module ${loaded.path}")
        println("host: sharedCounter before extension = $sharedCounter")
        plugin.increment()
        println("host: sharedCounter after extension = $sharedCounter")
        check(sharedCounter > 0)
        println("PASS: app resolved its entry class from runtime-loaded JVM code and mutated exact host state")
        loaded.close()
        loaded.close()
        check(loaded.isClosed)
        println("PASS: loaded-code close is idempotent")
        return
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "JVM Runtime Loader") {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { PluginHost(plugin) }
            }
            if (diagnosticMode) {
                LaunchedEffect(Unit) {
                    delay(500)
                    check(pluginRenderCount > 0)
                    println("PASS: runtime-loaded JVM code entered the host Compose composition")
                    loaded.close()
                    exitProcess(0)
                }
            }
        }
    }
    loaded.close()
}
