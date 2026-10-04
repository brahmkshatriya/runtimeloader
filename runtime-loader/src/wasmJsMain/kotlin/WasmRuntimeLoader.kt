@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.brahmkshatriya.runtimeloader

import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.JsReference
import kotlin.js.get

private fun dynamicImport(path: String): Promise<JsAny> = js("import(path)")

@JsFun("(module, name) => module[name]()")
private external fun callRuntimeLoaderEntry(module: JsAny, name: String): JsReference<Any>

public class WasmLoadedCode internal constructor(
    override val path: String,
    public val module: JsAny,
) : LoadedCode {
    private var closed: Boolean = false

    override val isClosed: Boolean
        get() = closed

    override fun close() {
        // ES modules/Wasm instances are cached by the JS runtime and cannot be forcibly unloaded.
        closed = true
    }
}

public actual object RuntimeCodeLoader {
    public actual fun load(
        path: String,
        onLoaded: (LoadedCode) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        dynamicImport(path)
            .then { module ->
                onLoaded(WasmLoadedCode(path, module))
                null
            }
            .catch { reason ->
                onError(RuntimeLoaderException("Dynamic import failed for $path: $reason"))
                null
            }
    }
}

public actual fun LoadedCode.createEntryUntyped(): Any {
    val loaded = this as? WasmLoadedCode
        ?: throw RuntimeLoaderException("Expected a Wasm loaded-code handle")
    check(!loaded.isClosed) { "Loaded code handle is closed" }
    return callRuntimeLoaderEntry(loaded.module, RuntimeLoaderEntryPoint.WASM_EXPORT_NAME).get()
}
