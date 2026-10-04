# Release runbook

Runtime Loader uses the same release version for the Kotlin Multiplatform library, the three Gradle
plugins, and the repository tag consumed by Swift Package Manager.

## 1. Choose the version

Update `gradle/runtime-loader-release.properties`. Keep `CHANGELOG.md`, README examples, and the
standalone `fixture/published-consumer` coordinates in sync. The `0.1.x` line is intentionally pinned
to Kotlin/Kotlin Native `2.4.20` and Gradle `9.6` through `9.x`.

Do not publish a stable-looking version until the platform status table in the README reflects the
runtime validation actually completed for that release.

## 2. Run local release gates

Use JDK 17 or newer.

```sh
./gradlew runtimeLoaderReleaseCheck
```

This validates the Gradle plugins, runs the Runtime Loader library checks, publishes all KMP variants
and plugin markers into `build/release-test-repository`, and then builds/runs a separate Maven-only
consumer for JVM and the current Linux Native host.

Also run the platform demos available on the release machine. On x64 Linux the useful checks are:

```sh
./gradlew \
  :demo:apps:linux:runtimeLoaderNativeIntegrationTest \
  :demo:apps:web:runtimeLoaderWasmJsIntegrationTest \
  :demo:apps:android:assembleDebug \
  :demo:apps:windows:runtimeLoaderNativeBuildMingwX64 \
  :demo:apps:ios:compileKotlinIosArm64 \
  :demo:store:packageExternalExtensionStore
```

Before promoting a release, run matching-host validation for every status claimed as runtime-tested:
Linux arm64 on arm64 Linux; macOS x64/arm64 on macOS with Xcode/SDL3; native Windows on Windows; and
the iOS signer/framework flow on a supported physical sideloaded device. Do not convert a
cross-compile result into a runtime-support claim.

## 3. GitHub Actions release publication

Pushing a release tag runs `.github/workflows/publish-runtime-loader-central.yml`. The workflow is
modeled after the Compose Native Central publication flow: it validates the tag first, runs
matching-host release gates on Linux, Windows, and macOS, and performs the irreversible publication
only after every platform job succeeds.

The tag may optionally start with `v`, but after removing that prefix it must exactly match the
`version` in `gradle/runtime-loader-release.properties`, and `CHANGELOG.md` must contain a matching
release heading. For example, both of these tags select `0.1.0-alpha03`:

```text
0.1.0-alpha03
v0.1.0-alpha03
```

Configure exactly these two repository secrets before pushing a release tag:

```text
GRADLE_PROPERTIES_CONTENT
GPG_SECRET_KEY_RING_BASE64
```

`GRADLE_PROPERTIES_CONTENT` is written verbatim to `~/.gradle/gradle.properties` in the publish job.
It should contain the same standard Gradle publication properties used for a local release, for
example:

```properties
mavenCentralUsername=...
mavenCentralPassword=...
signing.keyId=...
signing.password=...
gradle.publish.key=...
gradle.publish.secret=...
```

`signing.keyId` is required by Gradle's file-based PGP signer and must be the short 8-hex-digit key
ID (for example `00B5050F`). `GPG_SECRET_KEY_RING_BASE64` is the base64 encoding of the binary GPG
secret keyring. The workflow
decodes it into a private file under `~/.gradle/` and injects `signing.secretKeyRingFile` into the
restored Gradle properties, following the same scheme used by the Landscapist Native publication
workflow. Do not include `signing.secretKeyRingFile` in the secret itself because the runner path is
generated at release time.

CI keeps each target surface isolated on its own runner: JVM, WasmJs, Android, Linux x64,
Windows x64, macOS Native, and iOS arm64 each have an independent validation job. Release packaging
and external extension-store packaging run as separate integration jobs rather than being folded
into a target result. Central and the Plugin Portal are not contacted until every target and
integration job passes.

## 4. Maven Central credentials and signing

Create a Central Portal user token and make the signing key available to Gradle. CI restores the
standard `mavenCentralUsername`, `mavenCentralPassword`, `signing.keyId`, `signing.password`, and
`signing.secretKeyRingFile` properties from the two GitHub secrets above. Local manual publication
may use those same standard Gradle signing properties or Gradle's in-memory signing properties.

Verify the release task graph without publishing:

```sh
./gradlew :runtime-loader:tasks --all \
  -PmavenCentralPublishing=true \
  -PsignAllPublications=true
```

Publish and automatically release the KMP deployment:

```sh
./gradlew :runtime-loader:publishAndReleaseToMavenCentral \
  -PmavenCentralPublishing=true \
  -PsignAllPublications=true
```

Never commit Central credentials or private signing keys.

## 5. Gradle Plugin Portal

The Plugin Portal release contains only:

```text
dev.brahmkshatriya.runtime-loader.host
dev.brahmkshatriya.runtime-loader.module
dev.brahmkshatriya.runtime-loader.compose-native-compatibility
```

The demo plugins under `build-logic:demo-plugins` are intentionally excluded. Configure the Plugin
Portal API key/secret in the normal Gradle user properties/environment, then publish:

```sh
./gradlew -p build-logic validatePlugins publishPlugins
```

Use the same version already selected in `gradle/runtime-loader-release.properties`.

## 6. Swift Package Manager and repository tag

The root `Package.swift` exposes `RuntimeLoaderIOSSigning`; SwiftPM version resolution therefore uses
the repository's Git tags. Create a tag exactly matching the Runtime Loader release version, for
example:

```text
0.1.0-alpha03
```

The tag must contain the matching Kotlin artifacts/source, root `Package.swift`, and signer source.
Do not move an already published release tag to different contents.

## 7. Final release record

Create the public repository release from that immutable tag and copy the matching `CHANGELOG.md`
section into the release notes. Record any platform validation that was not completed rather than
implying it passed.

After publication, resolve the released Maven/Plugin Portal coordinates from a clean external sample
before announcing the release. For iOS, also resolve `RuntimeLoaderIOSSigning` from the repository tag
in Xcode/SwiftPM on macOS.
