# Security policy

## Supported versions

Until `1.0`, only the latest published Runtime Loader alpha is supported for security fixes. Native
loading depends on compiler/linker internals, so fixes may require rebuilding both the host and every
native module with the same supported Kotlin/Native toolchain.

## Trust boundary

Runtime Loader loads executable code into the host process. A loaded module has the same effective
process privileges as the host application and, on Kotlin/Native, intentionally shares the host
Kotlin runtime and object graph.

Runtime Loader's native `hostAbi`, dependency fingerprint, module fingerprint, and manifest format
checks are compatibility checks. They are **not signatures and do not authenticate a publisher or
artifact**. An application that accepts downloaded modules must establish its own trust root before
loading them, for example by verifying a signed catalog, a publisher signature, or a digest obtained
through an authenticated channel.

Use `RuntimeCodeLoader.loadVerified` / `loadVerifiedCode` when loading external artifacts and make
`RuntimeCodeVerifier` reject anything that is not authenticated by the consuming application's
policy. The raw `RuntimeCodeLoader.load` / `loadCode` APIs are intended for already-trusted input.

Verification and platform loading are separate filesystem operations; Runtime Loader does not make
that boundary atomic. A consumer with a local attacker or concurrently writable download directory
in its threat model should verify only after moving/copying the artifact and required sidecars into
an application-private location that untrusted writers cannot replace before or during loading.
Do not verify a mutable shared path and assume the same bytes are necessarily opened afterwards.

On iOS, `IosFrameworkRuntimeLoader.sign` and `signAndLoad` require a `RuntimeCodeVerifier`. The
verifier runs before Runtime Loader touches the user's signing identity. A successful Apple code
signature establishes platform code-signing validity; it does not prove that the extension came
from a publisher the application trusts.

Treat PKCS#12 files and their passwords as private signing credentials. Do not log them, upload them,
or persist their passwords through Runtime Loader.

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting / Security Advisory flow for
`brahmkshatriya/runtimeloader`. Do not open a public issue for an unpatched vulnerability.
Include the affected Runtime Loader version, platform, Kotlin/Gradle versions, reproduction steps,
and whether untrusted code execution or signing material is involved.
