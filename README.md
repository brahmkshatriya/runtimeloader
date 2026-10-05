# Kotlin Runtime Loader

Load separately compiled Kotlin code into a running Kotlin Multiplatform app.

Runtime Loader gives your app a small common API for attaching code at runtime. Your application still owns the extension model, permissions, metadata, update system, UI, and trust policy.

Typical uses include:

- extension or plugin systems
- downloadable app features
- separately distributed Compose UI modules
- application-owned extension stores

## Supported targets

| Target | Status |
| --- | --- |
| JVM | Supported |
| Android | Supported |
| WasmJs | Supported |
| Linux x64 | Supported |
| Linux arm64 | Supported |
| Windows x64 | Supported |
| macOS x64 | Supported |
| macOS arm64 | Supported |
| iOS arm64 | Build and signing support |
| iOS Simulator arm64 | Supported for compilation |

For the `0.1.x` releases, use Kotlin `2.4.20` and Gradle `9.6` or newer in the Gradle 9.x line.

## Installation

Version used below:

```text
0.1.0-alpha03
```

Make sure your build can use Maven Central and the Gradle Plugin Portal:

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
```

Add Runtime Loader to the host application:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.20"
    id("dev.brahmkshatriya.runtime-loader.host") version "0.1.0-alpha03"
}

kotlin {
    // Add the targets your app uses.
    jvm()
    linuxX64()

    sourceSets.commonMain.dependencies {
        implementation("dev.brahmkshatriya.runtimeloader:runtime-loader:0.1.0-alpha03")
    }
}
```

For a runtime-loadable module, apply the module plugin:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.20"
    id("dev.brahmkshatriya.runtime-loader.module") version "0.1.0-alpha03"
}
```

## Basic project structure

A simple setup normally has three projects:

```text
:extension-api   shared interface between the host and modules
:app             the host application
:my-extension    separately compiled runtime-loadable code
```

The shared project contains the contract that both sides understand:

```kotlin
interface GreetingExtension {
    fun greeting(): String
}
```

The extension implements that contract:

```kotlin
class GreetingExtensionImpl : GreetingExtension {
    override fun greeting(): String = "Hello from a loaded extension"
}
```

## Configure the host

Apply the host plugin and add the Runtime Loader library:

```kotlin
plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.runtime-loader.host")
}

runtimeLoaderHost {
    // Required for Kotlin/Native hosts.
    entryPoint.set("com.example.app.main")

    // Required when using the JVM host tasks.
    jvmMainClass.set("com.example.app.MainKt")
}

kotlin {
    jvm()
    linuxX64()

    sourceSets.commonMain.dependencies {
        implementation(project(":extension-api"))
        implementation("dev.brahmkshatriya.runtimeloader:runtime-loader:0.1.0-alpha03")
    }
}
```

If you only use JVM, Android, or Wasm, configure only the settings needed by those targets.

## Configure a loadable module

The module plugin needs to know:

- which project contains the shared API
- which host app or apps will load the module
- which interface the module exposes
- which implementation class should be created

```kotlin
plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.runtime-loader.module")
}

runtimeLoaderModule {
    api.set(projects.extensionApi)
    hosts.set(listOf(projects.app))

    entry {
        contract.set("com.example.extensions.GreetingExtension")
        implementation.set("com.example.extensions.GreetingExtensionImpl")
    }
}

kotlin {
    jvm()
    linuxX64()

    sourceSets.commonMain.dependencies {
        implementation(projects.extensionApi)
    }
}
```

The implementation class must have a no-argument constructor.

Runtime Loader generates the small bridge needed by each platform. Your app does not need to know platform-specific symbol or class names.

## Load a module

The common API is the same across supported platforms:

```kotlin
import dev.brahmkshatriya.runtimeloader.createEntry
import dev.brahmkshatriya.runtimeloader.loadCode

val code = loadCode(pathToModule)
val extension = code.createEntry<GreetingExtension>()

println(extension.greeting())

code.close()
```

There is also a callback API:

```kotlin
RuntimeCodeLoader.load(
    path = pathToModule,
    onLoaded = { code ->
        val extension = code.createEntry<GreetingExtension>()
        println(extension.greeting())
    },
    onError = { error ->
        println("Could not load extension: $error")
    },
)
```

## Verify downloaded code before loading it

If the module came from the network or an external store, authenticate it before loading it.

```kotlin
val code = loadVerifiedCode(
    path = downloadedModule,
    verifier = RuntimeCodeVerifier { path ->
        verifyMySignatureOrDigest(path)
    },
)

val extension = code.createEntry<GreetingExtension>()
```

Runtime Loader checks whether Native modules are compatible with the host, but compatibility checks are not a security signature. Your application is responsible for deciding which downloaded code it trusts.

## Compose extensions

The shared contract can expose Compose functions just like any other Kotlin interface.

For example:

```kotlin
interface UiExtension {
    @Composable
    fun Content()
}
```

The separately compiled module can implement that interface and render inside the host application's existing Compose UI.

If your Native project uses the Compose Native compatibility setup used by this repository, the optional plugin is:

```kotlin
plugins {
    id("dev.brahmkshatriya.runtime-loader.compose-native-compatibility") version "0.1.0-alpha03"
}
```

Most consumers should not apply it unless their Native Compose dependency graph requires it.

## Platform notes

JVM modules are loaded from JARs. Android modules are loaded from DEX JARs. Wasm modules are loaded dynamically by the generated JavaScript output.

Linux, Windows, and macOS use native shared libraries generated by the Runtime Loader Gradle plugin. The host app and its modules should be built with the same supported Kotlin/Native toolchain and compatible dependencies.

iOS uses signed frameworks. A downloaded iOS framework must be verified and signed with signing material appropriate for the host application before it can be loaded. Runtime Loader includes the signing integration used by the demo, but whether dynamically loaded code is permitted still depends on the target iOS environment and distribution method.

## Android

The Runtime Loader library supports Android API 23 and newer. Runtime-loadable Android modules default to min SDK 24; this can be changed in the module configuration if needed:

```kotlin
runtimeLoaderModule {
    androidMinSdk.set(24)
}
```

## Native build tasks

For Kotlin/Native hosts, the host plugin creates target-specific build tasks. Examples:

```text
runtimeLoaderNativeBuildLinuxX64
runtimeLoaderNativeBuildLinuxArm64
runtimeLoaderNativeBuildMingwX64
runtimeLoaderNativeBuildMacosX64
runtimeLoaderNativeBuildMacosArm64
runtimeLoaderNativeBuildIosArm64
```

If the host project contains multiple supported Native targets, Runtime Loader can infer them automatically. You normally do not need to set `runtimeLoaderHost.target` yourself.

## What Runtime Loader does not provide

Runtime Loader intentionally does not define:

- an extension marketplace
- extension metadata
- permissions or capabilities
- update URLs
- dependency resolution between third-party extensions
- a publisher identity system
- a trust or signature format

Those are application-level decisions. Runtime Loader only handles building, loading, compatibility checks, and creation of the shared entry object.

## Examples

This repository includes working examples for JVM, Android, WasmJs, Linux, Windows, macOS, and iOS under `demo/`.

There is also a small consumer-style fixture under `fixture/published-consumer` showing the host/API/module structure without depending on the demo extension system.

## Version status

`0.1.0-alpha03` is an alpha release. APIs and Native implementation details may still change before a stable release.

## License

Apache License 2.0.
