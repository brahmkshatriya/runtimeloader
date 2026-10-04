# Contributing

Runtime Loader deliberately has a small scope: attach separately compiled Kotlin code while keeping
application-level plugin/store policy in the consumer. Changes that introduce extension catalogs,
capability systems, update policy, or application-specific contracts should live outside this repo.

## Toolchain

The `0.1.x` line intentionally supports Kotlin/Kotlin Native `2.4.20` and Gradle `9.6` through `9.x`.
The Gradle plugins fail early outside that range because the Native backends use compiler-cache and
linker internals that are not a stable Kotlin API. Use JDK 17 or newer to run Gradle.

## Local checks

Set `JAVA_HOME` to a JDK 17+ installation, then run:

```sh
./gradlew runtimeLoaderReleaseCheck
```

That validates the published Gradle plugin model, publishes the library/plugin markers to an
isolated local Maven repository, and builds/runs a standalone consumer that has no composite-build
or project-dependency access to Runtime Loader.

Useful focused checks include:

```sh
./gradlew :build-logic:compileKotlin
./gradlew :fixture:host:runtimeLoaderJvmIntegrationTest
./gradlew :fixture:host:runtimeLoaderNativeIntegrationTest
./gradlew :demo:apps:web:runtimeLoaderWasmJsIntegrationTest
./gradlew :demo:apps:android:assembleDebug
./gradlew :demo:apps:windows:runtimeLoaderNativeBuildMingwX64
```

Platform-specific runtime checks still require their matching host environments for Linux arm64,
macOS, native Windows, and iOS device execution.

## Release changes

Keep `gradle/runtime-loader-release.properties`, `CHANGELOG.md`, and README coordinates in sync. Do
not publish a `SNAPSHOT` version to the Gradle Plugin Portal. Public releases use the three reusable
plugin IDs only; demo/store plugins are repository-local examples in `build-logic:demo-plugins`.
