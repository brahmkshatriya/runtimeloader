@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.brahmkshatriya.runtimeloader

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.posix.RTLD_NOW
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

/** Signing material supplied by the host user for sideloaded iOS frameworks. */
public data class IosFrameworkSigningMaterial(
    /** Apple provisioning profile used by the sideloaded host application. */
    public val provisioningProfilePath: String,
    /** PKCS#12 identity containing the Apple-issued signing certificate and private key. */
    public val pkcs12Path: String,
    /** Password protecting [pkcs12Path]. */
    public val pkcs12Password: String,
    /**
     * CodeDirectory identifier to use for the guest framework. The host bundle identifier is used
     * by default because locally provisioned iOS hosts may require guest code to use that identity.
     */
    public val codeDirectoryIdentifier: String? = null,
)

public data class IosSignedFramework(
    public val frameworkPath: String,
    public val executablePath: String,
    public val teamIdentifier: String,
)

/**
 * iOS-specific signed-framework loader.
 *
 * The host links the `RuntimeLoaderIOSSigning` Swift package. The caller must authenticate the
 * framework with [RuntimeCodeVerifier] before Runtime Loader validates its Kotlin ABI, asks the
 * signer package to apply host-provided signing material, then attaches it with dlopen. Runtime
 * Loader never treats ABI fingerprints or a successful code signature as publisher authenticity.
 */
public object IosFrameworkRuntimeLoader {
    private const val SIGN_SYMBOL: String = "runtime_loader_ios_sign_framework"
    private const val FREE_SYMBOL: String = "runtime_loader_ios_free_string"

    public fun sign(
        frameworkPath: String,
        signingMaterial: IosFrameworkSigningMaterial,
        hostManifestPath: String,
        verifier: RuntimeCodeVerifier,
    ): IosSignedFramework {
        // Signing downloaded code grants it the user's execution identity. Authenticate the
        // framework first; ABI manifests only establish compatibility, not publisher trust.
        verifier.verify(frameworkPath)
        val executablePath = frameworkExecutablePath(frameworkPath)
        // Reject an incompatible module before touching it with the user's signing identity.
        NativeRuntimeLoader.validate(executablePath, hostManifestPath)

        val teamIdentifier = signFramework(frameworkPath, signingMaterial)
        return IosSignedFramework(
            frameworkPath = frameworkPath,
            executablePath = executablePath,
            teamIdentifier = teamIdentifier,
        )
    }

    public fun signAndLoad(
        frameworkPath: String,
        signingMaterial: IosFrameworkSigningMaterial,
        hostManifestPath: String,
        verifier: RuntimeCodeVerifier,
    ): NativeLoadedCode {
        val signed = sign(frameworkPath, signingMaterial, hostManifestPath, verifier)
        return NativeRuntimeLoader.load(signed.executablePath, hostManifestPath)
    }

    private fun frameworkExecutablePath(frameworkPath: String): String {
        val bundle = NSBundle.bundleWithPath(frameworkPath)
            ?: throw RuntimeLoaderException("Invalid iOS framework bundle: $frameworkPath")
        return bundle.executablePath
            ?: throw RuntimeLoaderException("iOS framework has no CFBundleExecutable: $frameworkPath")
    }

    private fun signFramework(
        frameworkPath: String,
        signingMaterial: IosFrameworkSigningMaterial,
    ): String = memScoped {
        val process = dlopen(null, RTLD_NOW)
            ?: throw RuntimeLoaderException("Could not inspect iOS host symbols: ${dlerror()?.toKString()}")
        try {
            val signPointer = dlsym(process, SIGN_SYMBOL)
                ?: throw RuntimeLoaderException(
                    "RuntimeLoaderIOSSigning is not linked into the iOS host. " +
                        "Add the RuntimeLoaderIOSSigning Swift package product to the app target."
                )
            val freePointer = dlsym(process, FREE_SYMBOL)
                ?: throw RuntimeLoaderException("RuntimeLoaderIOSSigning is missing $FREE_SYMBOL")
            val sign = signPointer.reinterpret<IosSignFrameworkFunction>()
            val free = freePointer.reinterpret<IosFreeStringFunction>()

            val teamId = alloc<CPointerVar<ByteVar>>()
            val error = alloc<CPointerVar<ByteVar>>()
            teamId.value = null
            error.value = null
            val identifier = signingMaterial.codeDirectoryIdentifier
                ?: NSBundle.mainBundle.bundleIdentifier
                ?: throw RuntimeLoaderException("The iOS host bundle has no bundle identifier")

            val result = sign(
                frameworkPath.cstr.ptr,
                signingMaterial.provisioningProfilePath.cstr.ptr,
                signingMaterial.pkcs12Path.cstr.ptr,
                signingMaterial.pkcs12Password.cstr.ptr,
                identifier.cstr.ptr,
                teamId.ptr,
                error.ptr,
            )
            try {
                if (result != 0) {
                    val message = error.value?.toKString() ?: "unknown signing failure"
                    throw RuntimeLoaderException("Could not sign iOS extension framework: $message")
                }
                teamId.value?.toKString()
                    ?: throw RuntimeLoaderException("iOS signer returned no Team ID")
            } finally {
                teamId.value?.let { free(it) }
                error.value?.let { free(it) }
            }
        } finally {
            dlclose(process)
        }
    }
}

private typealias IosSignFrameworkFunction = CFunction<(
    CPointer<ByteVar>?,
    CPointer<ByteVar>?,
    CPointer<ByteVar>?,
    CPointer<ByteVar>?,
    CPointer<ByteVar>?,
    CPointer<CPointerVar<ByteVar>>?,
    CPointer<CPointerVar<ByteVar>>?,
) -> Int>

private typealias IosFreeStringFunction = CFunction<(CPointer<ByteVar>?) -> Unit>
