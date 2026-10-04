# Changelog

## 0.1.0-alpha03

Release-CI portability follow-up.

- Execute Kotlin/Native `.bat`/`.cmd` tool launchers through `cmd.exe` on Windows, including `klib`
  inspection and Runtime Loader's patched `kotlinc-native` wrapper.
- Validate the full Linux/Windows/macOS platform matrix on pushes to `main`; Maven Central and the
  Gradle Plugin Portal remain tag-only publication steps.
- Install the remaining SDL3 X11 development dependencies required by Ubuntu runners and make the
  macOS SDL framework lookup tolerant of the official DMG/XCFramework layout.
- Update failed-run artifact upload to the current GitHub Actions runtime.
- Patch ELF64 symbol visibility and weak bindings directly so Linux Native runtime linking does not
  depend on host `objcopy`/`llvm-objcopy` version-specific options.
- Normalize MinGW static-cache directory names so Maven KLIB identities containing `:` build on
  native Windows filesystems as well as cross-build hosts.
- Use Kotlin/Native response files for long Windows compiler invocations so large cache graphs do
  not exceed the `cmd.exe` command-line limit, and use `objcopy` response files for large PE cache
  ABI extraction commands that exceed the native Windows `CreateProcess` command-line limit.
- Ignore Apple archive symbol-table pseudo-members such as `__.SYMDEF SORTED` when rebuilding
  patched Kotlin/Native caches with Xcode `ar`.
- Pass macOS Kotlin exports directly to the Apple linker instead of an exported-symbol list whose
  comment syntax truncates Kotlin/Native mangled names containing `#`.
- Explicitly externalize each Mach-O module's Kotlin/Native init symbol from the raw cache symbol
  table so the module bootstrap can resolve it even when Darwin `nm -g` intentionally hides it.

## 0.1.0-alpha02

Release-CI portability fixes after the unpublished `0.1.0-alpha01` validation run.

- Install the SDL3 XScreenSaver build dependency on Ubuntu release runners.
- Select the macOS slice from SDL3's release XCFramework instead of assuming a root framework.
- Resolve LLVM tools portably on native Windows from Kotlin/Native or the runner LLVM installation.
- Use the host path separator and Windows `.exe` lookup when spawning external tools.

## 0.1.0-alpha01

Initial public alpha.

- Common `LoadedCode.createEntry<T>()` consumer API across JVM, Android, Wasm, and Native.
- Consumer-owned module contracts and generated mechanical entry bridges.
- Kotlin/Native same-runtime module loading for Linux x64 and Windows x64, including Compose and
  exact shared host state verification.
- Linux arm64 and macOS x64/arm64 Native backends with cross-build/source validation.
- iOS arm64 signed-framework path with host-provided Apple signing material and a mandatory
  application-owned trust verifier before signing.
- Multi-target Native host inference and target-specific linker/output configuration.
- Consumer-selected Compose Native fork alignment instead of a Runtime Loader-owned Compose version.
- Maven/Gradle publication metadata, Maven-only external-consumer verification, and configuration
  cache compatibility declaration.
- Tag-gated GitHub Actions release publication with Linux, native Windows, macOS/iOS framework,
  Maven Central, and Gradle Plugin Portal stages.

Known alpha limitations:

- Native module ABI depends on Kotlin/Native compiler/cache/linker internals; `0.1.x` is pinned to
  Kotlin `2.4.20`.
- Linux arm64, macOS, and iOS still require final runtime validation on matching hardware/OS.
- Runtime Loader validates Native compatibility but intentionally does not define an extension-store
  trust/signature format. Consumers must authenticate downloaded artifacts before loading them.
