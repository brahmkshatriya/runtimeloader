@file:OptIn(kotlin.js.ExperimentalJsExport::class, kotlin.js.ExperimentalWasmJsInterop::class)

package dev.brahmkshatriya.runtimeloader.demo

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import dev.brahmkshatriya.runtimeloader.RuntimeCodeLoader
import dev.brahmkshatriya.runtimeloader.WasmLoadedCode
import dev.brahmkshatriya.runtimeloader.createEntry
import kotlin.JsFun
import kotlin.js.JsAny
import kotlin.js.JsExport
import kotlin.js.JsReference
import kotlin.js.Promise
import kotlin.js.get
import kotlin.js.unsafeCast

private class WebOpenedExtension(
    val metadata: StoreExtension,
    val plugin: Plugin,
    private val code: WasmLoadedCode,
) {
    fun close() = code.close()
}

@JsFun("(value) => { globalThis.runtimeLoaderResult = value; }")
private external fun setRuntimeLoaderResult(value: String)

@JsFun("() => typeof document !== 'undefined' && typeof window !== 'undefined'")
private external fun isBrowser(): Boolean

@JsFun("() => globalThis.runtimeLoaderModulePath || './kotlin-runtime-loader-demo-store-counter.mjs'")
private external fun browserModulePath(): String

@JsFun("() => globalThis.runtimeLoaderStorePath || ''")
private external fun browserStorePath(): String

@JsFun("() => globalThis.runtimeLoaderAutoOpen === true || new URLSearchParams(globalThis.location?.search || '').has('autoOpen')")
private external fun browserAutoOpen(): Boolean

@JsFun("(value) => String(value)")
private external fun jsString(value: JsAny): String

@JsFun(
    """(url) => fetch(url).then(response => {
        if (!response.ok) throw new Error("HTTP " + response.status + " for " + url);
        return response.arrayBuffer();
    }).then(buffer => {
        const bytes = new Uint8Array(buffer);
        let result = '';
        for (let i = 0; i < bytes.length; i += 32768) {
            result += String.fromCharCode(...bytes.subarray(i, Math.min(bytes.length, i + 32768)));
        }
        return result;
    })"""
)
private external fun fetchBinary(url: String): Promise<JsAny>

@JsFun(
    """(mainText, importText, wasmBinary, importFileName, wasmFileName) => {
        const absolutize = text => text.replace(/(['"])\.\/([^'"]+)\1/g, (_all, quote, name) => {
            return quote + new URL(name, document.baseURI).href + quote;
        });
        const rewrittenImports = absolutize(importText);
        const importUrl = URL.createObjectURL(new Blob([rewrittenImports], { type: 'text/javascript' }));

        const wasmBytes = new Uint8Array(wasmBinary.length);
        for (let i = 0; i < wasmBinary.length; i++) wasmBytes[i] = wasmBinary.charCodeAt(i) & 0xff;
        const wasmUrl = URL.createObjectURL(new Blob([wasmBytes], { type: 'application/wasm' }));

        let rewrittenMain = mainText
            .split('./' + importFileName).join(importUrl)
            .split('./' + wasmFileName).join(wasmUrl);
        rewrittenMain = absolutize(rewrittenMain);
        return URL.createObjectURL(new Blob([rewrittenMain], { type: 'text/javascript' }));
    }"""
)
private external fun prepareWasmModule(
    mainText: String,
    importText: String,
    wasmBinary: String,
    importFileName: String,
    wasmFileName: String,
): String

@JsFun("(module, name) => module[name]()")
private external fun callExtensionExport(module: JsAny, name: String): JsReference<Any>

private fun WasmLoadedCode.resolveDemoExtension(): Plugin = createEntry()

@JsExport
public fun runRuntimeLoadedModule(path: String) {
    sharedCounter = 0
    RuntimeCodeLoader.load(
        path = path,
        onLoaded = { code ->
            val loaded = code as WasmLoadedCode
            val plugin = loaded.resolveDemoExtension()
            println("host: attached Wasm module ${loaded.path}")
            println("host: sharedCounter before extension = $sharedCounter")
            plugin.increment()
            println("host: sharedCounter after extension = $sharedCounter")
            check(sharedCounter > 0)
            println("PASS: app resolved its entry export from runtime-loaded Wasm code and mutated exact host state")
            loaded.close()
            loaded.close()
            check(loaded.isClosed)
            setRuntimeLoaderResult("PASS")
        },
        onError = { failure ->
            println("FAIL: ${failure.message}")
            setRuntimeLoaderResult("FAIL: ${failure.message}")
        },
    )
}

@JsExport
public fun runBrowserRuntimeLoadedModule(path: String) {
    sharedCounter = 0
    pluginRenderCount = 0
    diagnosticMode = true
    setRuntimeLoaderResult("LOADING")
    RuntimeCodeLoader.load(
        path = path,
        onLoaded = { code ->
            val plugin = (code as WasmLoadedCode).resolveDemoExtension()
            ComposeViewport {
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        PluginHost(plugin)
                        SideEffect {
                            check(pluginRenderCount > 0)
                            setRuntimeLoaderResult("COMPOSE_PASS")
                        }
                    }
                }
            }
        },
        onError = { failure ->
            println("FAIL: ${failure.message}")
            setRuntimeLoaderResult("FAIL: ${failure.message}")
        },
    )
}

@JsExport
public fun runBrowserExtensionStore(path: String) {
    sharedCounter = 0
    pluginRenderCount = 0
    diagnosticMode = true
    setRuntimeLoaderResult("STORE_LOADING")

    ComposeViewport {
        var store by remember { mutableStateOf<ExternalExtensionStore?>(null) }
        var opened by remember { mutableStateOf<WebOpenedExtension?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(path) {
            fetchBinary(path)
                .then { raw ->
                    try {
                        val parsed = ExternalExtensionStore.fromZip(binaryStringToBytes(jsString(raw)))
                        store = parsed
                        val entries = parsed.extensionsFor("web")
                        check(entries.isNotEmpty()) { "External store has no Web extensions" }
                        println("store: loaded ${entries.size} Web extension metadata entries from $path")
                        setRuntimeLoaderResult("STORE_READY")
                        if (browserAutoOpen()) {
                            openWebExtension(parsed, entries.first()) { opened = it }
                        }
                    } catch (failure: Throwable) {
                        error = failure.message ?: failure.toString()
                        setRuntimeLoaderResult("FAIL: $error")
                    }
                    null
                }
                .catch { reason ->
                    error = jsString(reason)
                    setRuntimeLoaderResult("FAIL: $error")
                    null
                }
        }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val failure = error
                val current = opened
                val currentStore = store
                when {
                    failure != null -> Text("Extension store failed: $failure")
                    current != null -> {
                        ExtensionContentPage(
                            extension = current.metadata,
                            plugin = current.plugin,
                            onBack = {
                                current.close()
                                opened = null
                            },
                        )
                        SideEffect {
                            if (pluginRenderCount > 0) setRuntimeLoaderResult("STORE_COMPOSE_PASS")
                        }
                    }
                    currentStore != null -> ExtensionStorePage(
                        extensions = currentStore.extensionsFor("web"),
                        onOpen = { metadata ->
                            opened?.close()
                            openWebExtension(currentStore, metadata) { opened = it }
                        },
                    )
                    else -> Text("Loading external extension store…")
                }
            }
        }
    }
}

private fun openWebExtension(
    store: ExternalExtensionStore,
    metadata: StoreExtension,
    onOpened: (WebOpenedExtension) -> Unit,
) {
    val artifact = metadata.artifactFor("web")
    val importPath = artifact.resources.first { it.endsWith(".import-object.mjs") }
    val wasmPath = artifact.resources.first { it.endsWith(".wasm") }
    val moduleUrl = prepareWasmModule(
        mainText = store.file(artifact.path).decodeToString(),
        importText = store.file(importPath).decodeToString(),
        wasmBinary = bytesToBinaryString(store.file(wasmPath)),
        importFileName = importPath.substringAfterLast('/'),
        wasmFileName = wasmPath.substringAfterLast('/'),
    )

    RuntimeCodeLoader.load(
        path = moduleUrl,
        onLoaded = { code ->
            val loaded = code as WasmLoadedCode
            val instance = loaded.createEntry<Plugin>()
            println("store: opened '${metadata.name}' from ${artifact.path}")
            onOpened(WebOpenedExtension(metadata, instance, loaded))
        },
        onError = { failure ->
            setRuntimeLoaderResult("FAIL: ${failure.message}")
            throw failure
        },
    )
}

private fun binaryStringToBytes(value: String): ByteArray =
    ByteArray(value.length) { index -> value[index].code.toByte() }

private fun bytesToBinaryString(bytes: ByteArray): String = buildString(bytes.size) {
    bytes.forEach { byte -> append((byte.toInt() and 0xff).toChar()) }
}

public fun main() {
    if (!isBrowser()) return
    val storePath = browserStorePath()
    if (storePath.isNotEmpty()) {
        runBrowserExtensionStore(storePath)
    } else {
        runBrowserRuntimeLoadedModule(browserModulePath())
    }
}
