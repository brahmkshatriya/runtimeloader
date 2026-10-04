@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.runtimeloader

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.toKString
import platform.posix.RTLD_NOW
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

internal fun openNativeLibrary(path: String): COpaquePointer =
    dlopen(path, RTLD_NOW)
        ?: throw RuntimeLoaderException("dlopen($path) failed: ${dlerror()?.toKString()}")

internal fun resolveNativeSymbol(library: COpaquePointer, name: String): COpaquePointer {
    dlerror()
    return dlsym(library, name)
        ?: throw RuntimeLoaderException("dlsym($name) failed: ${dlerror()?.toKString()}")
}
