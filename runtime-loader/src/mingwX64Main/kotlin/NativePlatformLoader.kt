@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.runtimeloader

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.wcstr
import platform.windows.GetLastError
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryW

internal fun openNativeLibrary(path: String): COpaquePointer = memScoped {
    val library = LoadLibraryW(path.wcstr.ptr)
        ?: throw RuntimeLoaderException("LoadLibraryW($path) failed: Win32 error ${GetLastError()}")
    library.reinterpret()
}

internal fun resolveNativeSymbol(library: COpaquePointer, name: String): COpaquePointer = memScoped {
    val address = GetProcAddress(library.reinterpret(), name.cstr.ptr)
        ?: throw RuntimeLoaderException("GetProcAddress($name) failed: Win32 error ${GetLastError()}")
    address.reinterpret()
}
