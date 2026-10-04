package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class RuntimeLoaderModulePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.requireRuntimeLoaderToolchain()
        project.configureNativeDependencyCompatibility()
        val extension = project.extensions.create("runtimeLoaderModule", RuntimeLoaderModuleExtension::class.java)
        extension.moduleId.convention(project.name)
        extension.outputFileName.convention(extension.moduleId.map { id -> "libruntime-loader-$id.so" })
        extension.windowsOutputFileName.convention(extension.moduleId.map { id -> "runtime-loader-$id.dll" })
        extension.appleOutputFileName.convention(extension.moduleId.map { id -> "libruntime-loader-$id.dylib" })
        extension.iosOutputFileName.convention(extension.moduleId.map { id -> "runtime-loader-$id.framework" })

        val generateEntry = project.tasks.register(
            "generateRuntimeLoaderEntry",
            GenerateRuntimeLoaderEntryTask::class.java,
        ) {
            group = "runtime loader"
            description = "Generate consumer-agnostic runtime entry bridges for this module."
            contract.set(extension.entry.contract)
            implementation.set(extension.entry.implementation)
            commonOutputDirectory.set(project.layout.buildDirectory.dir("generated/runtime-loader-entry/commonMain/kotlin"))
            wasmOutputDirectory.set(project.layout.buildDirectory.dir("generated/runtime-loader-entry/wasmJsMain/kotlin"))
            nativeOutputDirectory.set(project.layout.buildDirectory.dir("generated/runtime-loader-entry/nativeMain/kotlin"))
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kmp = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kmp.sourceSets.named("commonMain").configure {
                kotlin.srcDir(generateEntry.flatMap { it.commonOutputDirectory })
            }
            kmp.sourceSets.matching { it.name == "wasmJsMain" }.configureEach {
                kotlin.srcDir(generateEntry.flatMap { it.wasmOutputDirectory })
            }
            val nativeSourceSets = setOf(
                "linuxX64Main",
                "linuxArm64Main",
                "mingwX64Main",
                "macosX64Main",
                "macosArm64Main",
                "iosArm64Main",
                "iosSimulatorArm64Main",
            )
            kmp.sourceSets.matching { it.name in nativeSourceSets }.configureEach {
                kotlin.srcDir(generateEntry.flatMap { it.nativeOutputDirectory })
            }
        }

        val androidDex = project.tasks.register("runtimeLoaderAndroidDex", AndroidDexModuleTask::class.java) {
            group = "runtime loader"
            description = "Build a DEX JAR that can be attached with Android Runtime Loader."
            rootDirectory.set(project.rootProject.layout.projectDirectory)
            classesJar.set(
                project.layout.buildDirectory.file(
                    "intermediates/runtime_library_classes_jar/androidMain/" +
                        "bundleAndroidMainClassesToRuntimeJar/classes.jar"
                )
            )
            minSdk.set(extension.androidMinSdk)
            outputJar.set(
                project.layout.buildDirectory.file(
                    extension.moduleId.map { id -> "runtime-loader/android/$id-dex.jar" }
                )
            )
        }

        project.afterEvaluate {
            val api = extension.api.orNull
                ?: throw GradleException("runtimeLoaderModule.api is required")
            val hostProjects = extension.hosts.orNull.orEmpty()
            if (hostProjects.isEmpty()) {
                throw GradleException("runtimeLoaderModule.hosts must contain at least one app shell")
            }
            val apiProject = project.rootProject.project(api.path)
            val moduleAndroidClasses = project.tasks.findByName("bundleAndroidMainClassesToRuntimeJar")
            val hostAndroidClasses = apiProject.tasks.findByName("bundleAndroidMainClassesToRuntimeJar")
            if (moduleAndroidClasses != null && hostAndroidClasses != null) {
                androidDex.configure {
                    dependsOn(moduleAndroidClasses, hostAndroidClasses)
                    hostClassesJar.set(
                        apiProject.layout.buildDirectory.file(
                            "intermediates/runtime_library_classes_jar/androidMain/" +
                                "bundleAndroidMainClassesToRuntimeJar/classes.jar"
                        )
                    )
                    project.configurations.findByName("androidMainCompileClasspath")?.let { classpath ->
                        libraries.from(classpath)
                    }
                }
            }

            hostProjects.forEach { host ->
                val hostProject = project.rootProject.project(host.path)
                project.runtimeLoaderBuildRegistry().register(
                    RegisteredRuntimeLoaderModule(
                        id = extension.moduleId.get(),
                        hostProjectPath = host.path,
                        moduleProjectPath = project.path,
                        outputFileName = extension.outputFileName.get(),
                        windowsOutputFileName = extension.windowsOutputFileName.get(),
                        appleOutputFileName = extension.appleOutputFileName.get(),
                        iosOutputFileName = extension.iosOutputFileName.get(),
                        smokeArguments = extension.smokeArguments.get(),
                        verifyCompose = extension.verifyCompose.get(),
                        verifyArguments = extension.verifyArguments.get(),
                        verifyMarker = extension.verifyMarker.get(),
                    )
                )
            }
        }
    }
}
