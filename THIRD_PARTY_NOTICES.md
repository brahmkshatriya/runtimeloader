# Third-party notices

Runtime Loader does not vendor third-party source into the Kotlin runtime library. Its build and
platform integrations use upstream dependencies under their respective licenses.

## RorkSign

The optional `ios-signing` Swift package depends on RorkSign 0.6.5:

- Project: https://github.com/rorkai/rork-sign
- License: Apache License 2.0

RorkSign is used to validate the supplied iOS provisioning/signing material and sign an extension
framework. Runtime Loader adds its own host Team-ID and pre-sign trust checks around that operation.

## Kotlin, Gradle, ASM, Compose, and platform toolchains

The build uses Kotlin/Kotlin Native, Gradle, ASM, Compose-related dependencies, and platform
compiler/linker/SDK components. They are resolved as build dependencies/toolchains and are not
relicensed by Runtime Loader. Their distributions retain their own license and notice files.
