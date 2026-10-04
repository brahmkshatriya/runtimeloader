@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.runtimeloader.demo

import dev.brahmkshatriya.runtimeloader.IosFrameworkRuntimeLoader
import dev.brahmkshatriya.runtimeloader.IosFrameworkSigningMaterial
import dev.brahmkshatriya.runtimeloader.RuntimeCodeVerifier
import dev.brahmkshatriya.runtimeloader.createEntry
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret

/**
 * Marker retained in the host KLIB. The iOS backend emits this KLIB as RuntimeLoaderDemoHost.framework.
 */
public fun iosRuntimeLoaderHost() = Unit

/**
 * End-to-end iOS demo entry for the Swift shell.
 *
 * The framework is unsigned when supplied here. Runtime Loader validates its K/N ABI, signs it with
 * the user's own provisioning profile + PKCS#12 identity, dlopens it, and resolves the generated
 * Kotlin entry inside the host runtime. A successful return proves the plugin touched host state.
 */
@Throws(Exception::class)
public fun verifySignedExtension(
    frameworkPath: String,
    hostManifestPath: String,
    provisioningProfilePath: String,
    pkcs12Path: String,
    pkcs12Password: String,
): String {
    sharedCounter = 0
    val loaded = IosFrameworkRuntimeLoader.signAndLoad(
        frameworkPath = frameworkPath,
        signingMaterial = IosFrameworkSigningMaterial(
            provisioningProfilePath = provisioningProfilePath,
            pkcs12Path = pkcs12Path,
            pkcs12Password = pkcs12Password,
        ),
        hostManifestPath = hostManifestPath,
        // The demo's trust root is explicit user selection from the document picker. Production
        // consumers should verify a signed catalog/signature or pinned artifact digest here.
        verifier = RuntimeCodeVerifier { selectedPath ->
            check(selectedPath == frameworkPath) { "Unexpected framework selected for signing" }
        },
    )
    try {
        val instance = loaded.createEntry<Plugin>()
        instance.increment()
        check(sharedCounter > 0) { "Runtime-loaded iOS extension did not mutate host-owned state" }
        return "PASS: signed iOS extension '${loaded.metadata.moduleId}' loaded into the host Kotlin runtime; sharedCounter=$sharedCounter"
    } finally {
        loaded.close()
    }
}
