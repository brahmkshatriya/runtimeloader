# Kotlin Runtime Loader

Runtime Loader is an experimental Kotlin Multiplatform library for attaching separately compiled Kotlin code to an already-running application.

The library deliberately does **not** define a plugin or extension framework. It does not know an extension ID, entry class, capability list, Compose contract, author metadata, update URL, or any other application-level concept. Those belong to the consuming application.

The common boundary is simply:

```kotlin
val code: LoadedCode = loadVerifiedCode(
    path = path,
    verifier = RuntimeCodeVerifier { artifact -> verifyMyStoreSignature(artifact) },
)
```

`loadCode(path)` and `RuntimeCodeLoader.load(...)` remain available as low-level APIs for paths the
application already trusts. Runtime Loader does not define an extension-store signature format.

or, without coroutines:

```kotlin
RuntimeCodeLoader.load(
    path = path,
    onLoaded = { code -> /* application decides what this code means */ },
    onError = { error -> /* handle failure */ },
)
```

## Supported runtimes

| Runtime | Attachment mechanism | Status |
| --- | --- | --- |
| JVM | `URLClassLoader` | Working |
| Android | `DexClassLoader` | Working; external-store DEX loading, exact host state, and Compose verified on device |
| Wasm JS | JavaScript dynamic `import()` of a separately compiled Kotlin/Wasm module | Working in Node and browser Compose demo |
| Kotlin/Native Linux x64 | `dlopen`, compatibility validation, K/N module initialization, shared host runtime/cache ABI | Working, including Compose |
| Kotlin/Native Linux arm64 | Same ELF backend as Linux x64 | Source/demo compile support is in place; final native link/runtime verification requires a Linux arm64 host with the desktop system libraries installed |
| Kotlin/Native Windows x64 | `LoadLibraryW` / PE import library against host-exported Kotlin ABI | Runtime-verified end-to-end under the desktop Proton/Wine handler, including external-store DLL loading, exact host state, and Compose; native Windows 10/11 verification is still pending |
| Kotlin/Native macOS x64 | `dlopen` / Mach-O host exports and dylib module cache | Backend and demo sources compile; final linking/runtime verification requires macOS, Xcode command-line tools, and SDL3 |
| Kotlin/Native macOS arm64 | `dlopen` / Mach-O host exports and dylib module cache | Backend and demo sources compile; final linking/runtime verification requires macOS, Xcode command-line tools, and SDL3 |
| iOS arm64 | Host-signed downloaded `.framework` + `dlopen`, shared K/N host runtime/cache ABI | API, host, extension KLIBs, signer bridge, and build graph compile/check; final framework link/sign/load requires macOS/Xcode and a sideloaded physical device |

Kotlin/Native support is intentionally experimental. It depends on Kotlin/Native static-cache/compiler internals rather than a stable public dynamic-module ABI. Host and downloaded modules must be built against a compatible compiler/dependency/cache world. The `0.1.x` line intentionally supports Kotlin/Kotlin Native `2.4.20` and Gradle `9.6` through `9.x`; the Gradle plugins fail early outside that range.

## Project layout

```text
runtime-loader/
  src/commonMain/kotlin/RuntimeCodeLoader.kt
  src/jvmMain/kotlin/JvmRuntimeLoader.kt
  src/androidMain/kotlin/AndroidRuntimeLoader.kt
  src/wasmJsMain/kotlin/WasmRuntimeLoader.kt
  src/nativeShared/kotlin/NativeRuntimeLoader.kt
  src/posixDynamicMain/kotlin/NativePlatformLoader.kt
  src/mingwX64Main/kotlin/NativePlatformLoader.kt
  src/iosMain/kotlin/IosFrameworkRuntimeLoader.kt

Package.swift      repository-level SwiftPM product for RuntimeLoaderIOSSigning
ios-signing/
  Package.swift     local/demo SwiftPM manifest for the same signer product
  Sources/RuntimeLoaderIOSSigning/RuntimeLoaderIOSSigning.swift

build-logic/
  Runtime Loader Gradle plugins and the Kotlin/Native cache/link pipeline
  demo-plugins/    repository-local demo/store Gradle plugins; never published

demo/
  client/        host-owned Plugin/Compose contract + extension-store UI/models
  store/
    build.gradle.kts   store only references child extension projects
    counter/           independently compiled demo extension
    about/             independently compiled demo extension
  apps/
    jvm/
    android/
    web/
    linux/
    linuxArm64/
    windows/
    macosX64/
    macosArm64/
```

All source sets use the flattened layout: Kotlin files sit directly under each source set's `kotlin/` directory.

## Library API

`commonMain` contains the platform-independent loader and typed-entry API:

```kotlin
public interface LoadedCode {
    public val path: String
    public val isClosed: Boolean
    public fun close()
}

public expect object RuntimeCodeLoader {
    public fun load(
        path: String,
        onLoaded: (LoadedCode) -> Unit,
        onError: (Throwable) -> Unit = { throw it },
    )
}

public fun interface RuntimeCodeVerifier {
    public fun verify(path: String)
}

public suspend fun loadCode(path: String): LoadedCode
public suspend fun loadVerifiedCode(path: String, verifier: RuntimeCodeVerifier): LoadedCode
public inline fun <reified T : Any> LoadedCode.createEntry(): T
```

The loader still knows nothing about the meaning of `T`. It only attaches the platform artifact and
instantiates the fixed mechanical entry generated by the Gradle module plugin. The contract itself,
extension IDs, capabilities, store schema, update policy, UI, and lifecycle above `LoadedCode` remain
owned by the consuming application.

A consumer can therefore keep its runtime code path in common code while retaining its own trust
policy:

```kotlin
val code = loadVerifiedCode(
    downloadedArtifactPath,
    RuntimeCodeVerifier { artifact -> extensionStore.verifyArtifact(artifact) },
)
val extension: EchoExtension = code.createEntry()
```

The verifier should authenticate the artifact (and any relevant sidecars) against a trusted root,
such as a signed catalog, publisher signature, or pinned digest. Native ABI fingerprints are only
compatibility metadata; they do not authenticate downloaded code.

On JVM and Android the helper instantiates the generated entry class through the module classloader.
On Wasm it calls the generated module export. On Kotlin/Native it resolves the generated Kotlin
function, retains its `StableRef` for the lifetime of `NativeLoadedCode`, and returns the object from
the host Kotlin runtime. The same Native helper is used after iOS framework signing/loading.

The lower-level platform handles (`classLoader`, `module`, `symbol()`) remain public for consumers
that intentionally want a custom entry protocol.

## Consumer-owned extension contract

Runtime Loader generates only the entry bridge. The consuming extension system defines the actual
interface and implementation:

```kotlin
public interface EchoExtension {
    public fun id(): String
}

public class YouTubeExtension : EchoExtension {
    override fun id(): String = "youtube"
}
```

The module project tells Runtime Loader which two types to connect:

```kotlin
runtimeLoaderModule {
    api.set(projects.extensionApi)
    hosts.set(listOf(projects.apps.desktop, projects.apps.ios))

    entry {
        contract.set("dev.example.echo.extensions.EchoExtension")
        implementation.set("dev.example.youtube.YouTubeExtension")
    }
}
```

The generated common class delegates to the implementation as the configured contract, so an
incorrect implementation fails during compilation. Runtime Loader also generates the matching Wasm
and Kotlin/Native bridges. A higher-level Echo Gradle plugin can derive these two names from its own
annotation or DSL; Runtime Loader does not require or define an annotation.

The stable generated entry convention is:

```text
JVM / Android   dev.brahmkshatriya.runtimeloader.generated.RuntimeLoaderGeneratedEntry
Wasm            runtimeExtensionCreate
Kotlin/Native   generated runtimeExtensionCreate() Kotlin symbol returning a StableRef pointer
```

Applications normally do not need these names because `LoadedCode.createEntry<T>()` hides them.

`NativeLoadedCode.close()` intentionally does not call `dlclose()`. Kotlin objects, type metadata,
and remembered Compose lambdas may still point into the loaded module. It does release the
`StableRef` handles created by `createEntry()`.

## Gradle plugins

The reusable build publishes these plugin IDs:

```text
dev.brahmkshatriya.runtime-loader.host
dev.brahmkshatriya.runtime-loader.module
dev.brahmkshatriya.runtime-loader.compose-native-compatibility
```

The demo extension/store plugins are repository-local examples in the non-published
`build-logic:demo-plugins` subproject. They are not part of the Runtime Loader consumer surface or
Plugin Portal publication.

A Native host may either select one target explicitly for backwards compatibility:

```kotlin
runtimeLoaderHost {
    target.set("linuxX64")
    entryPoint.set("dev.example.main")
}
```

or omit `target` completely. In that case Runtime Loader infers all supported Kotlin/Native targets
present in the KMP host project and registers target-specific tasks such as:

```text
runtimeLoaderNativeBuildLinuxX64
runtimeLoaderNativeBuildLinuxArm64
runtimeLoaderNativeBuildMingwX64
runtimeLoaderNativeBuildMacosX64
runtimeLoaderNativeBuildMacosArm64
runtimeLoaderNativeBuildIosArm64
```

`runtimeLoaderNativeBuild` builds the explicitly selected targets, or the current host target when
targets were inferred. `runtimeLoaderNativeBuildAllTargets` requests every configured target.
Per-target output names and linker flags can be supplied with `executableNames` and
`targetLinkerOptions`; `linkerOptions` remains the common option list.

The Compose Native compatibility plugin no longer owns a Compose Native version. It preserves the
fork version selected by the consumer dependency graph. An unusual graph containing multiple fork
versions can explicitly select one with `-PruntimeLoader.composeNativeVersion=<version>`.

## Maven Local consumption

The current release version is `0.1.0-alpha01`. Publish both the KMP library and reusable Gradle
plugins to Maven Local with:

```bash
./gradlew publishRuntimeLoaderToMavenLocal
```

Then a consumer build can resolve Runtime Loader without an `includeBuild`:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("dev.brahmkshatriya.runtime-loader.host") version "0.1.0-alpha01"
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.runtimeloader:runtime-loader:0.1.0-alpha01")
    }
}
```

The root KMP coordinate is:

```text
dev.brahmkshatriya.runtimeloader:runtime-loader:0.1.0-alpha01
```

Gradle module metadata selects the Android/JVM/Wasm/Linux/Windows/macOS/iOS platform publication.
The reusable plugin implementation is published as
`dev.brahmkshatriya.runtimeloader:runtime-loader-gradle-plugin:0.1.0-alpha01`; normal consumers
should use the plugin IDs rather than depending on that implementation artifact directly.

## Release publication

Release tags are published by `.github/workflows/publish-runtime-loader-central.yml`. The tag is
validated against `gradle/runtime-loader-release.properties` and `CHANGELOG.md`, then Linux,
Windows, and macOS/iOS release gates run before Maven Central or the Gradle Plugin Portal is touched.
The workflow needs only `GRADLE_PROPERTIES_CONTENT` and `GPG_SECRET_KEY_RING_BASE64`; their
contents and the manual fallback procedure are documented in `RELEASING.md`.

The KMP library is configured for Maven Central with full POM metadata and source/documentation
artifacts. Central publishing/signing remains opt-in for manual local publication so normal builds do
not require a private key. Provide Central user-token credentials and an in-memory GPG key as Gradle
properties or `ORG_GRADLE_PROJECT_...` environment variables, then enable the release targets:

```sh
./gradlew :runtime-loader:publishToMavenCentral \
  -PmavenCentralPublishing=true \
  -PsignAllPublications=true
```

The three reusable Gradle plugins are configured for the Gradle Plugin Portal with project/VCS
metadata, descriptions, tags, and declared configuration-cache support. Publish them from the
`build-logic` build after setting the Plugin Portal key/secret:

```sh
./gradlew -p build-logic publishPlugins
```

The Plugin Portal publication deliberately declares Isolated Projects unsupported for `0.1.x`; the
plugins currently coordinate host/module projects through the root build registry.

Before publishing, run the repository-local release check:

```sh
./gradlew runtimeLoaderReleaseCheck
```

It publishes both consumer artifacts into `build/release-test-repository` and runs a standalone
fixture that resolves the library and plugin markers only from that Maven repository. No
`includeBuild` or Runtime Loader project dependency is available to that fixture.

## Consumer-independence fixture

`fixture/api`, `fixture/module`, and `fixture/host` are deliberately unrelated to the demo extension
model. They define their own `GreetingExtension` interface, use `runtimeLoaderModule.entry` to
create the bridge, and call `LoadedCode.createEntry<GreetingExtension>()`. The host contains both
`linuxX64` and `mingwX64` without setting `runtimeLoaderHost.target`, exercising multi-target
inference. JVM and Linux Native integration tests also verify that the separately loaded module
mutates the exact host-owned fixture state.

## External extension-store demo

The repository now includes a higher-level demo extension system on top of Runtime Loader. This layer is intentionally **application-owned**; it is not part of Runtime Loader's API.

`demo/store/build.gradle.kts` only declares which extension projects belong to the store:

```kotlin
extensionsStore {
    extensions(
        projects.demo.store.counter,
        projects.demo.store.about,
    )
}
```

Each child extension project owns its own metadata and source. Artifact paths, supported platforms, build tasks, and generated entry bridges are inferred automatically. The store builds one external ZIP:

```text
demo/store/build/distributions/demo-extension-store.zip
```

The ZIP contains a shared `catalog.json` plus separately compiled artifacts for each supported runtime:

```text
catalog.json
artifacts/
  counter/
    jvm/counter.jar
    android/counter.jar
    web/...
    linux_x64/...
    linux_arm64/...   # when packaged on Linux arm64
    windows_x64/...
    macos_x64/...     # when packaged on macOS
    macos_arm64/...   # when packaged on macOS
  about/
    jvm/about.jar
    android/about.jar
    web/...
    linux_x64/...
```

The catalog owns application-level information such as ID, extension type, name, description, capabilities, authors, update/repository links, and inferred supported platforms. Native store artifacts are emitted only for targets that can be final-linked on the current host, so an x64 Linux build does not pretend to contain Linux arm64 or macOS dylibs. Platform entry names are generated framework conventions, not handwritten extension metadata. Runtime Loader sees none of this metadata.

All app shells follow the same user-facing flow:

```text
external store ZIP
      ↓
parse catalog.json
      ↓
show compatible extensions
      ↓
user presses "Open extension"
      ↓
extract/select that platform's artifact
      ↓
RuntimeCodeLoader attaches the code
      ↓
app resolves the generated framework entry
      ↓
navigate to a new Compose page
      ↓
render the runtime-loaded extension's Content()
```

Build the external store:

```sh
./gradlew :demo:store:packageExternalExtensionStore
```

Open the JVM picker UI:

```sh
./gradlew :demo:apps:jvm:runExtensionStoreDemo
```

Verify JVM ZIP parsing + both metadata-selected entries without opening a window:

```sh
./gradlew :demo:apps:jvm:verifyExtensionStoreDemo
```

Verify that a metadata-selected JVM extension opens on the host Compose detail page:

```sh
./gradlew :demo:apps:jvm:verifyExtensionStoreComposeDemo
```

Open the Kotlin/Native Linux desktop picker UI:

```sh
./gradlew :demo:apps:linux:runExtensionStoreDemo
```

Verify Native ZIP parsing + both metadata-selected symbol resolutions:

```sh
./gradlew :demo:apps:linux:verifyExtensionStoreDemo
```

Verify that a metadata-selected Native extension opens on the host Compose detail page:

```sh
./gradlew :demo:apps:linux:verifyExtensionStoreComposeDemo
```

Build Android. `MainActivity` accepts an externally supplied store through its intent data URI or an `extensionStorePath` intent extra. For a self-contained demo, the same generated ZIP is also copied into the APK as `assets/demo-extension-store.zip` and used only as a fallback. Selecting an extension extracts only its Android DEX JAR into `codeCacheDir` and loads it with Runtime Loader:

```sh
./gradlew :demo:apps:android:assembleDebug
```

Build the browser demo distribution:

```sh
./gradlew :demo:store:prepareWebExtensionStoreDemo
```

The output is:

```text
demo/store/build/browser/index.html
```

The browser fetches the external ZIP, parses the same catalog, displays the picker, and dynamically imports the selected Wasm artifact. For automated verification the `?autoOpen` query parameter selects the first compatible entry; a normal browser session waits for the user to press **Open extension**.

The current proof has been runtime-verified from the ZIP on JVM, Android, Linux x64 Native, Windows x64 Native under the desktop Proton/Wine handler, and Wasm. Android, Linux x64, and that Windows compatibility run also prove that separately loaded extension code mutates the exact host-owned state and executes inside the host Compose runtime; native Windows 10/11 remains a separate validation item. Linux arm64 and macOS compile paths are present but still require matching hosts for final desktop linking/runtime verification. iOS arm64 compiles the host/client/extensions and signed-framework API, but final Mach-O framework linking and physical-device loading require a macOS/Xcode build host.

## Demo verification

JVM shared-runtime loading:

```sh
./gradlew :demo:apps:jvm:runtimeLoaderJvmIntegrationTest
```

This runs both `counter` and `about`, including their Compose checks.

Host-native Kotlin/Native shared runtime, ABI rejection, shared state, and Compose:

```sh
./gradlew :demo:apps:linux:runtimeLoaderNativeIntegrationTest
```

`runtimeLoaderLinuxIntegrationTest` remains as a compatibility alias on Linux hosts. The same
`runtimeLoaderNativeIntegrationTest` task is created by the Linux arm64 and macOS demos and runs
when Gradle is executing on the matching native host architecture.

On matching hosts, the equivalent commands are:

```sh
./gradlew :demo:apps:linuxArm64:runtimeLoaderNativeIntegrationTest
./gradlew :demo:apps:macosX64:runtimeLoaderNativeIntegrationTest
./gradlew :demo:apps:macosArm64:runtimeLoaderNativeIntegrationTest
```

The macOS demos expect SDL3 as a framework. Set `COMPOSE_MACOS_SDL_FRAMEWORK_DIR` if it is not in
`/Library/Frameworks`.

### iOS signed-framework loading

The iOS path is intended for sideloaded/development hosts. The extension store carries an unsigned
`ios_arm64` framework. The consumer supplies the signing material it already uses for the host and
Runtime Loader signs the downloaded framework before attaching it:

```kotlin
val loaded = IosFrameworkRuntimeLoader.signAndLoad(
    frameworkPath = downloadedFrameworkPath,
    signingMaterial = IosFrameworkSigningMaterial(
        provisioningProfilePath = profilePath,
        pkcs12Path = p12Path,
        pkcs12Password = password,
    ),
    hostManifestPath = hostManifestPath,
    verifier = RuntimeCodeVerifier { framework ->
        extensionStore.verifySignedCatalogEntry(framework)
    },
)
```

The iOS host must also link the `RuntimeLoaderIOSSigning` Swift package product. Published
consumers can add this repository directly as a Swift Package because the root `Package.swift`
exports that product. The in-repository Xcode demo uses the equivalent local `ios-signing/` package.
The signer is part of Runtime Loader's iOS implementation and uses RorkSign 0.6.5 to validate the
profile/PKCS#12 pair and sign the framework in place. `RuntimeCodeVerifier` is mandatory here and
runs before the user's signing identity is used; a successful Apple code signature is not treated as
publisher authenticity. If the running app contains
`embedded.mobileprovision`, the signer also rejects a supplied Team ID that differs from the host's
Team ID before modifying the framework. Runtime Loader validates its own Kotlin/Native ABI manifest
before signing, then initializes and resolves the extension through the same K/N runtime as the host.
The library does not persist the PKCS#12 password.

On a macOS/Xcode machine, build the unsigned demo host/extensions with:

```sh
./gradlew :demo:apps:ios:runtimeLoaderNativeBuild
```

Then open `demo/apps/ios/iosApp.xcodeproj`. The Swift demo lets the user choose an unsigned extension
framework, provisioning profile, PKCS#12 file, and password, copies them into its writable container,
and calls the Kotlin verifier. The verifier checks that the loaded object implements the host-owned
`Plugin` interface and mutates the exact host `sharedCounter`.

The Kotlin source/KLIB graph and Xcode/Swift-package wiring are checkable from non-macOS hosts, but
Kotlin/Native static-cache output for `ios_arm64` and the final Mach-O framework link require macOS.
A physical-device run is still required to confirm that the target iOS/sideloading environment permits
`dlopen` of the newly signed framework from the app's writable container.

Wasm Node integration:

```sh
./gradlew :demo:apps:web:runtimeLoaderWasmJsIntegrationTest
```

Wasm browser distributions for all registered extension modules:

```sh
./gradlew :demo:apps:web:runtimeLoaderWasmJsBrowserDistribution
```

Android DEX modules are inferred from the extension projects and packaged into the external store:

```sh
./gradlew :demo:store:packageExternalExtensionStore
./gradlew :demo:apps:android:assembleDebug
```

Important generated outputs include:

```text
demo/apps/linux/build/runtime-loader/linuxX64/out/app.kexe
demo/apps/linux/build/runtime-loader/linuxX64/out/libruntime-loader-counter.so
demo/apps/linux/build/runtime-loader/linuxX64/out/libruntime-loader-about.so
demo/apps/linux/build/runtime-loader/linuxX64/out/runtime-loader-host.properties
demo/apps/linuxArm64/build/runtime-loader/linuxArm64/out/app.kexe
demo/apps/macosArm64/build/runtime-loader/macosArm64/out/libruntime-loader-counter.dylib
demo/apps/macosX64/build/runtime-loader/macosX64/out/libruntime-loader-counter.dylib
demo/apps/ios/build/runtime-loader/iosArm64/out/RuntimeLoaderDemoHost.framework
demo/apps/ios/build/runtime-loader/iosArm64/out/runtime-loader-counter.framework
demo/apps/ios/build/runtime-loader/iosArm64/out/runtime-loader-about.framework
demo/store/counter/build/runtime-loader/android/counter-dex.jar
demo/store/about/build/runtime-loader/android/about-dex.jar

demo/apps/web/build/runtime-loader/wasmJs/browser/counter/index.html
demo/apps/web/build/runtime-loader/wasmJs/browser/about/index.html
```

## What the demo proves

The demo's `client` module defines an application-owned `Plugin` interface and Compose UI contract. That contract is intentionally outside Runtime Loader.

The separately compiled demo code implements that host interface and mutates the host's exact `sharedCounter`. JVM, Wasm, and Kotlin/Native integration tests verify that the runtime-loaded object participates in the existing host type/runtime world rather than communicating through a separate process/runtime bridge.

The Compose tests additionally verify that code originating from the separately compiled module executes inside the host application's existing Compose composition.

On Android, extension implementation classes are absent from the application's normal DEX. Their DEX artifacts live only inside `assets/demo-extension-store.zip` and are extracted/loaded after selection. The connected-device verifier loads both external DEX modules, checks the exact host-state mutations, and enters both runtime-loaded Compose bodies.

## Native compatibility metadata

Native desktop builds emit a host manifest and one sidecar per loadable module. The loader validates this metadata before opening the platform library, including:

```text
format version
target
Kotlin/Native compiler version
host ABI fingerprint
dependency/cache fingerprint
```

An incompatible artifact is rejected before any native module initializer can execute. These fields
are not a signature or integrity/authenticity proof; an attacker able to replace an artifact can also
replace its sidecar. Authenticate external artifacts before calling the loader.

This metadata is Runtime Loader infrastructure. It is separate from an application's extension metadata such as name, authors, capabilities, supported platforms, repository URL, update URL, icon, or entry declaration.

## Intended higher-level architecture

Runtime Loader is designed to sit below an application-specific extension system:

```text
extension store / update manifest
        ↓
app downloads platform artifact
        ↓
RuntimeCodeLoader / loadCode
        ↓
LoadedCode
        ↓
application-owned entry resolver
        ↓
application extension interface
        ↓
Compose / media / player / downloader / etc.
```

That allows an application to publish its own `lib` and privileged `client` APIs and its own extension-store Gradle plugin without coupling those policies to Runtime Loader itself.


## License and security

Runtime Loader is licensed under Apache-2.0. See `LICENSE`, `NOTICE`, and
`THIRD_PARTY_NOTICES.md`. Security and executable-code trust guidance is in `SECURITY.md`; release
changes are tracked in `CHANGELOG.md`, and the manual publication sequence is in `RELEASING.md`.
