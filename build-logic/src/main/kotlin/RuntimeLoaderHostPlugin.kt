package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

class RuntimeLoaderHostPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.requireRuntimeLoaderToolchain()
        project.configureNativeDependencyCompatibility()
        val extension = project.extensions.create("runtimeLoaderHost", RuntimeLoaderHostExtension::class.java)
        val buildAll = project.tasks.register("runtimeLoaderNativeBuild", DefaultTask::class.java) {
            group = "runtime loader"
            description = "Build Runtime Loader for the explicitly selected or current inferred Native target(s)."
        }
        val buildEveryTarget = project.tasks.register("runtimeLoaderNativeBuildAllTargets", DefaultTask::class.java) {
            group = "runtime loader"
            description = "Build Runtime Loader for every Native target configured in this host project."
        }
        val smokeAll = project.tasks.register("runtimeLoaderNativeSmoke", DefaultTask::class.java) {
            group = "verification"
            description = "Run shared-state smoke tests for every module registered against this host."
        }
        val verifyAll = project.tasks.register("runtimeLoaderNativeVerify", DefaultTask::class.java) {
            group = "verification"
            description = "Run the full runtime-loader integration suite for every module registered against this host."
        }
        val integrationAll = project.tasks.register("runtimeLoaderNativeIntegrationTest", DefaultTask::class.java) {
            group = "verification"
            description = "Verify Native typed loading, ABI rejection, shared state, and optional Compose integration."
        }
        val jvmIntegrationAll = project.tasks.register("runtimeLoaderJvmIntegrationTest", DefaultTask::class.java) {
            group = "verification"
            description = "Verify JVM module loading, host type identity, and shared state."
        }
        val wasmIntegrationAll = project.tasks.register("runtimeLoaderWasmJsIntegrationTest", DefaultTask::class.java) {
            group = "verification"
            description = "Verify WasmJs open-world dynamic module loading and shared host state."
        }
        val wasmBrowserDistributionAll = project.tasks.register("runtimeLoaderWasmJsBrowserDistribution", DefaultTask::class.java) {
            group = "distribution"
            description = "Build open-world WasmJs browser distributions with separately compiled modules."
        }
        val androidArtifacts = project.tasks.register("runtimeLoaderAndroidArtifacts", DefaultTask::class.java) {
            group = "verification"
            description = "Build Android DexClassLoader module artifacts for this host."
        }
        val check = project.tasks.findByName("check")?.let { project.tasks.named("check") }
            ?: project.tasks.register("check", DefaultTask::class.java) {
                group = "verification"
                description = "Run runtime-loader verification for this host."
            }
        val runAlias = project.tasks.register("runtimeLoaderNativeRun", DefaultTask::class.java) {
            group = "runtime loader"
            description = "Run the registered module when exactly one module targets this host."
        }
        project.tasks.register("runtimeLoaderNativeClean", Delete::class.java) {
            group = "runtime loader"
            description = "Remove generated runtime-loader caches and linked native artifacts for this host."
            delete(project.layout.buildDirectory.dir("runtime-loader"))
        }

        project.gradle.projectsEvaluated {
            val specs = project.runtimeLoaderBuildRegistry().modulesForHost(project.path)
            if (specs.isEmpty()) {
                throw GradleException(
                    "No runtime-loader modules target ${project.path}. " +
                        "Apply dev.brahmkshatriya.runtime-loader.module to a module project and add this shell to runtimeLoaderModule.hosts."
                )
            }
            specs.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { duplicate ->
                throw GradleException("Multiple runtime-loader modules targeting ${project.path} use moduleId '$duplicate'")
            }
            specs.groupBy { it.outputFileName }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { duplicate ->
                throw GradleException("Multiple runtime-loader modules targeting ${project.path} use output '$duplicate'")
            }

            specs.forEach { spec ->
                val moduleProject = project.rootProject.project(spec.moduleProjectPath)
                moduleProject.tasks.findByName("runtimeLoaderAndroidDex")?.let { androidDex ->
                    androidArtifacts.configure { dependsOn(androidDex) }
                }
            }

            val hostJvmJar = project.tasks.findByName("jvmJar")
                ?.let { project.tasks.named("jvmJar", Jar::class.java) }
            val jvmRuntimeClasspath = project.configurations.findByName("jvmRuntimeClasspath")
            if (hostJvmJar != null && jvmRuntimeClasspath != null && extension.jvmMainClass.isPresent) {
                specs.forEach { spec ->
                    val suffix = spec.id.toTaskSuffix()
                    val moduleProject = project.rootProject.project(spec.moduleProjectPath)
                    val moduleJvmJar = moduleProject.tasks.named("jvmJar", Jar::class.java)
                    val jvmTest = project.tasks.register(
                        "runtimeLoader${suffix}JvmIntegrationTest",
                        JavaExec::class.java,
                    ) {
                        group = "verification"
                        description = "Run JVM integration checks for runtime-loader module '${spec.id}'."
                        dependsOn(hostJvmJar, moduleJvmJar)
                        mainClass.set(extension.jvmMainClass)
                        classpath(project.files(hostJvmJar.flatMap { it.archiveFile }, jvmRuntimeClasspath))
                        argumentProviders.add(CommandLineArgumentProvider {
                            listOf(
                                moduleJvmJar.get().archiveFile.get().asFile.absolutePath,
                                "--smoke",
                            )
                        })
                    }
                    jvmIntegrationAll.configure { dependsOn(jvmTest) }
                    check.configure { dependsOn(jvmIntegrationAll) }

                    if (spec.verifyCompose) {
                        val jvmComposeTest = project.tasks.register(
                            "runtimeLoader${suffix}JvmComposeIntegrationTest",
                            JavaExec::class.java,
                        ) {
                            group = "verification"
                            description = "Run JVM Compose integration checks for runtime-loader module '${spec.id}'."
                            dependsOn(hostJvmJar, moduleJvmJar)
                            mainClass.set(extension.jvmMainClass)
                            classpath(project.files(hostJvmJar.flatMap { it.archiveFile }, jvmRuntimeClasspath))
                            argumentProviders.add(CommandLineArgumentProvider {
                                listOf(
                                    moduleJvmJar.get().archiveFile.get().asFile.absolutePath,
                                    "--verify-compose",
                                )
                            })
                        }
                        jvmIntegrationAll.configure { dependsOn(jvmComposeTest) }
                    }
                }
            }

            val hostWasmSync = project.tasks.findByName("wasmJsDevelopmentExecutableCompileSync")
            if (hostWasmSync != null && extension.wasmModuleRunner.isPresent) {
                val hostModuleName = project.rootProject.name + project.path.replace(':', '-')
                specs.forEach { spec ->
                    val suffix = spec.id.toTaskSuffix()
                    val moduleProject = project.rootProject.project(spec.moduleProjectPath)
                    val moduleWasmSync = moduleProject.tasks.findByName("wasmJsDevelopmentExecutableCompileSync")
                        ?: return@forEach
                    val moduleName = project.rootProject.name + moduleProject.path.replace(':', '-')
                    val wasmTest = project.tasks.register(
                        "runtimeLoader${suffix}WasmJsIntegrationTest",
                        WasmJsIntegrationTestTask::class.java,
                    ) {
                        group = "verification"
                        description = "Run WasmJs integration checks for runtime-loader module '${spec.id}'."
                        dependsOn(hostWasmSync, moduleWasmSync)
                        hostPackageDirectory.set(
                            project.rootProject.layout.buildDirectory.dir(
                                "wasm/packages/$hostModuleName/kotlin"
                            )
                        )
                        moduleSyncDirectory.set(
                            moduleProject.layout.buildDirectory.dir(
                                "compileSync/wasmJs/main/developmentExecutable/kotlin"
                            )
                        )
                        this.hostModuleName.set(hostModuleName)
                        this.moduleName.set(moduleName)
                        runnerExport.set(extension.wasmModuleRunner)
                    }
                    wasmIntegrationAll.configure { dependsOn(wasmTest) }
                    check.configure { dependsOn(wasmIntegrationAll) }

                    val browserDistribution = project.tasks.register(
                        "runtimeLoader${suffix}WasmJsBrowserDistribution",
                        WasmJsBrowserDistributionTask::class.java,
                    ) {
                        group = "distribution"
                        description = "Build a browser distribution for runtime-loader module '${spec.id}'."
                        dependsOn(hostWasmSync, moduleWasmSync)
                        hostPackageDirectory.set(
                            project.rootProject.layout.buildDirectory.dir(
                                "wasm/packages/$hostModuleName/kotlin"
                            )
                        )
                        moduleSyncDirectory.set(
                            moduleProject.layout.buildDirectory.dir(
                                "compileSync/wasmJs/main/developmentExecutable/kotlin"
                            )
                        )
                        outputDirectory.set(
                            project.layout.buildDirectory.dir("runtime-loader/wasmJs/browser/${spec.id}")
                        )
                        this.hostModuleName.set(hostModuleName)
                        this.moduleName.set(moduleName)
                    }
                    wasmBrowserDistributionAll.configure { dependsOn(browserDistribution) }
                    wasmIntegrationAll.configure { dependsOn(browserDistribution) }
                }
            }

            val legacyTarget = extension.target.orNull?.takeIf(String::isNotBlank)
            val declaredTargets = extension.targets.orNull.orEmpty().filter(String::isNotBlank).distinct()
            if (legacyTarget != null && declaredTargets.isNotEmpty()) {
                throw GradleException(
                    "runtimeLoaderHost.target and runtimeLoaderHost.targets are mutually exclusive; " +
                        "use target for a legacy single-target host or targets/inference for a multi-target host."
                )
            }

            val supportedTargets = listOf(
                "linuxX64",
                "linuxArm64",
                "mingwX64",
                "macosX64",
                "macosArm64",
                "iosArm64",
            )
            val inferredTargets = supportedTargets.filter { target ->
                project.tasks.findByName("compileKotlin${target.replaceFirstChar { it.uppercase() }}") is KotlinNativeCompile
            }
            val targetNames = when {
                legacyTarget != null -> listOf(legacyTarget)
                declaredTargets.isNotEmpty() -> declaredTargets
                else -> inferredTargets
            }
            if (targetNames.isEmpty()) {
                buildAll.configure { enabled = false }
                buildEveryTarget.configure { enabled = false }
                smokeAll.configure { enabled = false }
                verifyAll.configure { enabled = false }
                integrationAll.configure { enabled = false }
                runAlias.configure { enabled = false }
                return@projectsEvaluated
            }
            val unsupported = targetNames.filterNot { it in supportedTargets }
            if (unsupported.isNotEmpty()) {
                throw GradleException(
                    "Unsupported Kotlin/Native Runtime Loader host target(s) $unsupported. " +
                        "Supported hosts are ${supportedTargets.joinToString()}."
                )
            }
            if (!extension.entryPoint.isPresent) {
                throw GradleException("runtimeLoaderHost.entryPoint is required for Native Runtime Loader hosts")
            }

            val currentTargets = targetNames.filter(::isCurrentNativeHostTarget)
            if (targetNames.any { it.startsWith("linux") }) {
                project.tasks.register("runtimeLoaderLinuxIntegrationTest", DefaultTask::class.java) {
                    group = "verification"
                    description = "Compatibility alias for runtimeLoaderNativeIntegrationTest on Linux hosts."
                    dependsOn(integrationAll)
                }
            }

            val currentRunTasks = mutableListOf<Any>()
            targetNames.forEach { targetName ->
                val targetSuffix = targetName.replaceFirstChar { it.uppercase() }
                val compileTaskName = "compileKotlin$targetSuffix"
                val hostCompileTask = project.tasks.findByName(compileTaskName)
                if (hostCompileTask !is KotlinNativeCompile) {
                    throw GradleException(
                        "Runtime Loader target '$targetName' was configured for ${project.path}, " +
                            "but task '$compileTaskName' does not exist. Add that Kotlin/Native target or remove it from runtimeLoaderHost.targets."
                    )
                }
                val hostCompile = project.tasks.named(compileTaskName, KotlinNativeCompile::class.java)
                val moduleCompiles = specs.associateWith { spec ->
                    val moduleProject = project.rootProject.project(spec.moduleProjectPath)
                    val compile = moduleProject.tasks.findByName(compileTaskName)
                    if (compile !is KotlinNativeCompile) {
                        throw GradleException(
                            "Runtime-loader module ${spec.moduleProjectPath} targets ${project.path}, but does not define $targetName. " +
                                "Add the target to the module or explicitly narrow runtimeLoaderHost.targets."
                        )
                    }
                    moduleProject.tasks.named(compileTaskName, KotlinNativeCompile::class.java)
                }

                val targetRoot = project.layout.buildDirectory.dir("runtime-loader/$targetName")
                val workDirProvider = targetRoot.map { it.dir("work") }
                val outDirProvider = targetRoot.map { it.dir("out") }
                val outputName = extension.executableNames.orNull.orEmpty()[targetName]
                    ?: when (targetName) {
                        "mingwX64" -> if (legacyTarget != null) extension.executableName.get() else "app.exe"
                        "iosArm64" -> if (legacyTarget != null) extension.executableName.get() else "RuntimeLoaderHost.framework"
                        else -> extension.executableName.get()
                    }
                val executableFile = outDirProvider.map { it.file(outputName) }
                val hostManifest = outDirProvider.map { it.file("runtime-loader-host.properties") }

                val targetBuild = project.tasks.register(
                    "runtimeLoaderNativeBuild$targetSuffix",
                    BuildNativeRuntimeLoaderTask::class.java,
                ) {
                    group = "runtime loader"
                    description = "Build Runtime Loader host/modules for $targetName."
                    rootDirectory.set(project.rootProject.layout.projectDirectory)
                    dependsOn(hostCompile)
                    dependsOn(moduleCompiles.values)
                    hostLibraries.from(hostCompile.map { it.libraries })
                    hostKlib.from(hostCompile.flatMap { it.klibDirectory })
                    moduleKlibs.from(moduleCompiles.values.map { it.flatMap { task -> task.klibDirectory } })
                    target.set(hostCompile.map { it.target })
                    entryPoint.set(extension.entryPoint)
                    executableName.set(outputName)
                    nativeLinkerOptions.set(
                        project.provider {
                            extension.linkerOptions.get() +
                                extension.targetLinkerOptions.orNull.orEmpty()[targetName].orEmpty()
                        }
                    )
                    workDirectory.set(workDirProvider)
                    outputDirectory.set(outDirProvider)
                    moduleDescriptors.set(
                        project.provider {
                            specs.map { spec ->
                                val compile = moduleCompiles.getValue(spec).get()
                                listOf(
                                    spec.id,
                                    compile.klibDirectory.get().asFile.absolutePath,
                                    spec.outputFileName(targetName),
                                ).joinToString("\t")
                            }
                        }
                    )
                }

                specs.groupBy { it.outputFileName(targetName) }
                    .filterValues { it.size > 1 }
                    .keys
                    .firstOrNull()
                    ?.let { duplicate ->
                        throw GradleException(
                            "Multiple runtime-loader modules targeting ${project.path}/$targetName use output '$duplicate'"
                        )
                    }

                if (targetName.startsWith("linux") && isCurrentNativeHostTarget(targetName)) {
                    targetBuild.configure { nativeLinkDirectories.add("/usr/lib") }
                }

                if (targetName == "mingwX64") {
                    project.tasks.findByName("prepareWindowsX64SdlRuntime")?.let { sdlTask ->
                        val sdlDirectory = project.layout.buildDirectory.dir("composeNativeApplication/windowsX64/sdl")
                        targetBuild.configure {
                            dependsOn(sdlTask)
                            nativeLinkDirectories.add(sdlDirectory.map { it.asFile.absolutePath })
                            nativeRuntimeFiles.from(sdlDirectory.map { it.file("SDL3.dll") })
                        }
                    }
                    project.configurations.findByName("composeNativeWindowsIcuData")?.let { icuData ->
                        val stagedIcuData = project.layout.buildDirectory.file(
                            "runtime-loader/$targetName/runtime/icudtl.dat"
                        )
                        val prepareIcuDataName = "prepareRuntimeLoaderWindowsIcuData"
                        val prepareIcuData = project.tasks.findByName(prepareIcuDataName)?.let {
                            project.tasks.named(prepareIcuDataName, Copy::class.java)
                        } ?: project.tasks.register(prepareIcuDataName, Copy::class.java) {
                            from(icuData)
                            into(stagedIcuData.map { it.asFile.parentFile })
                            rename { "icudtl.dat" }
                        }
                        targetBuild.configure {
                            dependsOn(prepareIcuData)
                            nativeRuntimeFiles.from(stagedIcuData)
                        }
                    }
                }

                buildEveryTarget.configure { dependsOn(targetBuild) }
                if (legacyTarget != null || declaredTargets.isNotEmpty() || isCurrentNativeHostTarget(targetName)) {
                    buildAll.configure { dependsOn(targetBuild) }
                }

                if (isCurrentNativeHostTarget(targetName)) {
                    check.configure { dependsOn(integrationAll) }
                    specs.forEach { spec ->
                        val suffix = spec.id.toTaskSuffix()
                        val moduleOutputName = spec.outputFileName(targetName)
                        val moduleFile = outDirProvider.map { it.file(moduleOutputName) }
                        val moduleManifest = outDirProvider.map {
                            it.file(moduleOutputName + ".runtime-loader.properties")
                        }

                        val smoke = project.tasks.register(
                            "runtimeLoader${suffix}Smoke",
                            SmokeNativeRuntimeLoaderTask::class.java,
                        ) {
                            group = "verification"
                            description = "Smoke test runtime-loader module '${spec.id}' on $targetName."
                            dependsOn(targetBuild)
                            executable.set(executableFile)
                            sharedLibrary.set(moduleFile)
                            arguments.set(spec.smokeArguments)
                        }
                        smokeAll.configure { dependsOn(smoke) }

                        if (spec.verifyCompose) {
                            project.tasks.register(
                                "runtimeLoader${suffix}VerifyUi",
                                VerifyNativeRuntimeLoaderTask::class.java,
                            ) {
                                group = "verification"
                                description = "Verify UI integration for runtime-loader module '${spec.id}' on $targetName."
                                dependsOn(targetBuild)
                                executable.set(executableFile)
                                sharedLibrary.set(moduleFile)
                                arguments.set(spec.verifyArguments)
                                marker.set(spec.verifyMarker)
                                timeoutSeconds.set(extension.verifyTimeoutSeconds)
                            }
                        }

                        val integration = project.tasks.register(
                            "runtimeLoader${suffix}NativeIntegrationTest",
                            NativeRuntimeLoaderIntegrationTestTask::class.java,
                        ) {
                            group = "verification"
                            description = "Run Native integration checks for runtime-loader module '${spec.id}' on $targetName."
                            dependsOn(targetBuild)
                            executable.set(executableFile)
                            sharedLibrary.set(moduleFile)
                            this.hostManifest.set(hostManifest)
                            this.moduleManifest.set(moduleManifest)
                            smokeArguments.set(spec.smokeArguments)
                            verifyCompose.set(spec.verifyCompose)
                            verifyArguments.set(spec.verifyArguments)
                            verifyMarker.set(spec.verifyMarker)
                            timeoutSeconds.set(extension.verifyTimeoutSeconds)
                        }
                        integrationAll.configure { dependsOn(integration) }
                        verifyAll.configure { dependsOn(integration) }
                        if (targetName.startsWith("linux")) {
                            project.tasks.register(
                                "runtimeLoader${suffix}LinuxIntegrationTest",
                                DefaultTask::class.java,
                            ) {
                                group = "verification"
                                description = "Compatibility alias for the Native integration test for '${spec.id}'."
                                dependsOn(integration)
                            }
                        }

                        val run = project.tasks.register(
                            "runtimeLoader${suffix}Run",
                            RunNativeRuntimeLoaderTask::class.java,
                        ) {
                            group = "runtime loader"
                            description = "Run runtime-loader module '${spec.id}' on $targetName."
                            dependsOn(targetBuild)
                            executable.set(executableFile)
                            sharedLibrary.set(moduleFile)
                        }
                        currentRunTasks += run
                    }
                }
            }

            if (currentTargets.isEmpty()) {
                smokeAll.configure { enabled = false }
                verifyAll.configure { enabled = false }
                integrationAll.configure { enabled = false }
                runAlias.configure { enabled = false }
            } else if (currentRunTasks.size == 1) {
                runAlias.configure { dependsOn(currentRunTasks.single()) }
            } else {
                runAlias.configure {
                    doLast {
                        throw GradleException(
                            "runtimeLoaderNativeRun is ambiguous with ${currentRunTasks.size} modules; " +
                                "run a runtimeLoader<ModuleId>Run task instead."
                        )
                    }
                }
            }
        }
    }
}

private fun isCurrentNativeHostTarget(target: String): Boolean {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val x64 = arch in setOf("x86_64", "amd64", "x64")
    val arm64 = arch in setOf("aarch64", "arm64")
    return when (target) {
        "linuxX64" -> os.contains("linux") && x64
        "linuxArm64" -> os.contains("linux") && arm64
        "macosX64" -> os.contains("mac") && x64
        "macosArm64" -> os.contains("mac") && arm64
        "mingwX64" -> os.contains("windows") && x64
        else -> false
    }
}

private fun String.toTaskSuffix(): String =
    split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        .ifEmpty { "Module" }
