package dev.brahmkshatriya.runtimeloader.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.brahmkshatriya.runtimeloader.AndroidLoadedCode
import dev.brahmkshatriya.runtimeloader.RuntimeCodeLoader
import dev.brahmkshatriya.runtimeloader.createEntry
import java.io.File

private class AndroidOpenedExtension(
    val metadata: StoreExtension,
    val plugin: Plugin,
    private val code: AndroidLoadedCode,
    private val file: File,
) {
    fun close() {
        code.close()
        file.delete()
    }
}

class MainActivity : ComponentActivity() {
    private var openedExtension: AndroidOpenedExtension? = null
    private val verificationOpened = mutableListOf<AndroidOpenedExtension>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedCounter = 0
        pluginRenderCount = 0
        diagnosticMode = false

        val (storeBytes, storeSource) = loadExtensionStoreBytes()
        val store = ExternalExtensionStore.fromZip(storeBytes)
        val extensions = store.extensionsFor("android")
        check(extensions.isNotEmpty()) { "External store has no Android extensions" }
        Log.i(
            "RuntimeLoaderDemo",
            "loaded ${extensions.size} Android extension metadata entries from $storeSource",
        )

        if (intent.getBooleanExtra("runtimeLoaderVerifyAll", false)) {
            runVerification(store, extensions)
            return
        }

        setContent {
            var opened by remember { mutableStateOf<AndroidOpenedExtension?>(null) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val current = opened
                    if (current == null) {
                        ExtensionStorePage(
                            extensions = extensions,
                            onOpen = { metadata ->
                                openedExtension?.close()
                                val next = openAndroidExtension(store, metadata)
                                openedExtension = next
                                opened = next
                            },
                        )
                    } else {
                        ExtensionContentPage(
                            extension = current.metadata,
                            plugin = current.plugin,
                            onBack = {
                                current.close()
                                if (openedExtension === current) openedExtension = null
                                opened = null
                            },
                        )
                    }
                }
            }
        }
    }

    private fun runVerification(
        store: ExternalExtensionStore,
        extensions: List<StoreExtension>,
    ) {
        val ids = extensions.mapTo(mutableSetOf()) { it.id }
        check("counter" in ids && "about" in ids) {
            "Android verification requires both demo extensions; found ${ids.sorted()}"
        }

        val opened = extensions.mapIndexed { index, metadata ->
            sharedCounter = 0
            val current = openAndroidExtension(store, metadata)
            current.plugin.increment()
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
            Log.i(
                "RuntimeLoaderDemo",
                "PASS: Android store entry ${index + 1}/${extensions.size} '${metadata.id}' " +
                    "loaded from external store and mutated exact host state to $sharedCounter",
            )
            verificationOpened += current
            current
        }

        pluginRenderCount = 0
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    opened.forEach { current ->
                        ExtensionContentPage(
                            extension = current.metadata,
                            plugin = current.plugin,
                            onBack = {},
                        )
                    }
                    SideEffect {
                        if (pluginRenderCount >= opened.size) {
                            opened.forEach { current ->
                                Log.i(
                                    "RuntimeLoaderDemo",
                                    "PASS: Android external-store extension '${current.metadata.id}' rendered " +
                                        "inside the host Compose tree",
                                )
                            }
                            Log.i(
                                "RuntimeLoaderDemo",
                                "ANDROID_RUNTIME_LOADER_PASS: external-store runtime loading, exact host state, and Compose verified",
                            )
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun loadExtensionStoreBytes(): Pair<ByteArray, String> {
        intent.data?.let { uri ->
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) return bytes to "external URI $uri"
        }

        intent.getStringExtra("extensionStorePath")?.let { path ->
            val file = File(path)
            if (file.isFile) return file.readBytes() to "external file ${file.absolutePath}"
        }

        return assets.open("demo-extension-store.zip").use { input ->
            input.readBytes() to "bundled demo fallback asset"
        }
    }

    private fun openAndroidExtension(
        store: ExternalExtensionStore,
        metadata: StoreExtension,
    ): AndroidOpenedExtension {
        val artifact = metadata.artifactFor("android")
        val file = File(codeCacheDir, "store-${metadata.id}-${System.nanoTime()}.jar")
        file.writeBytes(store.file(artifact.path))
        check(file.setReadOnly()) { "Could not make runtime-loaded DEX read-only: $file" }

        var loaded: AndroidLoadedCode? = null
        RuntimeCodeLoader.load(
            path = file.absolutePath,
            onLoaded = { loaded = it as AndroidLoadedCode },
            onError = { throw it },
        )
        val code = checkNotNull(loaded)
        val instance = code.createEntry<Plugin>()
        Log.i("RuntimeLoaderDemo", "opened '${metadata.name}' from ${artifact.path}")
        return AndroidOpenedExtension(metadata, instance, code, file)
    }

    override fun onDestroy() {
        openedExtension?.close()
        openedExtension = null
        verificationOpened.forEach(AndroidOpenedExtension::close)
        verificationOpened.clear()
        super.onDestroy()
    }
}
