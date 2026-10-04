package dev.brahmkshatriya.runtimeloader.demo.gradle

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression

public open class DemoExtensionsStoreExtension {
    internal val projects: MutableList<ProjectDependency> = mutableListOf()

    public fun extension(project: ProjectDependency) {
        if (projects.any { it.path == project.path }) {
            throw GradleException("Extension project '${project.path}' was added more than once")
        }
        projects += project
    }

    public fun extensions(vararg projects: ProjectDependency) {
        projects.forEach(::extension)
    }
}

private data class ResolvedExtensionProject(
    val project: Project,
    val metadata: DemoExtensionProjectExtension,
    val runtimeLoader: RuntimeLoaderModuleView,
    val nativeHosts: Map<String, Project>,
    val platforms: List<String>,
)

private data class CatalogArtifact(
    val path: String,
    val entry: String,
    val resources: List<String> = emptyList(),
)

private data class NativeStoreTarget(
    val gradleTarget: String,
    val platform: String,
    val compileTask: String,
)

private val NATIVE_STORE_TARGETS = listOf(
    NativeStoreTarget("linuxX64", "linux_x64", "compileKotlinLinuxX64"),
    NativeStoreTarget("linuxArm64", "linux_arm64", "compileKotlinLinuxArm64"),
    NativeStoreTarget("mingwX64", "windows_x64", "compileKotlinMingwX64"),
    NativeStoreTarget("macosX64", "macos_x64", "compileKotlinMacosX64"),
    NativeStoreTarget("macosArm64", "macos_arm64", "compileKotlinMacosArm64"),
    NativeStoreTarget("iosArm64", "ios_arm64", "compileKotlinIosArm64"),
)

/**
 * Demo-only store plugin.
 *
 * A store declares only child extension projects. Metadata lives in those projects, and artifact
 * paths/tasks are inferred from the KMP + Runtime Loader outputs exposed by each project.
 */
public class DemoExtensionsStorePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val store = project.extensions.create(
            "extensionsStore",
            DemoExtensionsStoreExtension::class.java,
        )
        val generatedCatalog = project.layout.buildDirectory.file("extensions-store/catalog.json")

        val generate = project.tasks.register("generateExtensionsStore") {
            group = "extensions store"
            description = "Generate catalog.json from registered extension projects."
            outputs.file(generatedCatalog)
            doLast {
                val extensions = resolve(project, store)
                validate(extensions)
                val file = generatedCatalog.get().asFile
                file.parentFile.mkdirs()
                file.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            linkedMapOf(
                                "schemaVersion" to 1,
                                "extensions" to extensions.map(::toJsonModel),
                            )
                        )
                    ) + "\n"
                )
                project.logger.lifecycle("[extensions-store] generated ${file.absolutePath}")
            }
        }

        val validate = project.tasks.register("validateExtensionsStore") {
            group = "verification"
            description = "Validate metadata supplied by registered extension projects."
            doLast {
                val extensions = resolve(project, store)
                validate(extensions)
                project.logger.lifecycle("[extensions-store] ${extensions.size} extension project(s) are valid")
            }
        }

        val packageStore = project.tasks.register("packageExternalExtensionStore", Zip::class.java) {
            group = "extensions store"
            description = "Package inferred extension artifacts plus generated catalog metadata."
            dependsOn(generate)
            archiveFileName.set("demo-extension-store.zip")
            destinationDirectory.set(project.layout.buildDirectory.dir("distributions"))
            entryCompression = ZipEntryCompression.STORED
            from(generatedCatalog)
        }
        val packageAndroidStore = project.tasks.register("packageExternalExtensionStoreAndroid", Zip::class.java) {
            group = "extensions store"
            description = "Package only Android extension artifacts plus generated catalog metadata."
            dependsOn(generate)
            archiveFileName.set("demo-extension-store-android.zip")
            destinationDirectory.set(project.layout.buildDirectory.dir("distributions"))
            entryCompression = ZipEntryCompression.STORED
            from(generatedCatalog)
        }

        project.tasks.register("extensionsStore") {
            group = "extensions store"
            description = "Build the external extension store."
            dependsOn(packageStore)
        }
        project.tasks.findByName("build")?.dependsOn(packageStore)

        val prepareWeb = project.tasks.register("prepareWebExtensionStoreDemo", Sync::class.java) {
            group = "extensions store"
            description = "Build a browser distribution that loads the external store ZIP."
        }

        project.gradle.projectsEvaluated {
            val extensions = resolve(project, store)
            validate(extensions)
            packageStore.configure {
                extensions.forEach { extension -> configureArtifacts(this, extension) }
            }
            packageAndroidStore.configure {
                extensions.forEach { extension ->
                    configureArtifacts(this, extension, includedPlatforms = setOf("android"))
                }
            }

            val webExtension = extensions.firstOrNull { "web" in it.platforms }
            if (webExtension == null) {
                prepareWeb.configure { enabled = false }
            } else {
                val webHost = findWebHost(webExtension)
                    ?: throw GradleException(
                        "Extension '${webExtension.metadata.id}' has a Wasm target but no Runtime Loader Web host"
                    )
                val moduleId = webExtension.runtimeLoader.moduleId.get()
                val browserTask = "runtimeLoader${moduleId.toTaskSuffix()}WasmJsBrowserDistribution"
                val browserDistribution = webHost.layout.buildDirectory.dir(
                    "runtime-loader/wasmJs/browser/$moduleId"
                )
                val webStoreDistribution = project.layout.buildDirectory.dir("browser")

                prepareWeb.configure {
                    dependsOn(packageStore, "${webHost.path}:$browserTask")
                    from(browserDistribution)
                    from(packageStore.flatMap { it.archiveFile })
                    into(webStoreDistribution)
                    doLast {
                        val index = webStoreDistribution.get().file("index.html").asFile
                        val text = index.readText()
                        if ("runtimeLoaderStorePath" !in text) {
                            index.writeText(
                                text.replace(
                                    "globalThis.runtimeLoaderModulePath =",
                                    "globalThis.runtimeLoaderStorePath = './demo-extension-store.zip';\n    globalThis.runtimeLoaderModulePath =",
                                )
                            )
                        }
                        project.logger.lifecycle("[extensions-store] web demo: ${index.absolutePath}")
                    }
                }
            }
        }

        validate.configure { mustRunAfter(generate) }
    }

    private fun configureArtifacts(
        zip: Zip,
        extension: ResolvedExtensionProject,
        includedPlatforms: Set<String> = extension.platforms.toSet(),
    ) {
        val child = extension.project
        val id = extension.metadata.id

        if ("jvm" in includedPlatforms && "jvm" in extension.platforms) {
            val jar = child.tasks.named("jvmJar", Jar::class.java)
            zip.dependsOn(jar)
            zip.from(jar.flatMap { it.archiveFile }) {
                into("artifacts/$id/jvm")
                rename { "$id.jar" }
            }
        }

        if ("android" in includedPlatforms && "android" in extension.platforms) {
            val dexTask = child.tasks.named("runtimeLoaderAndroidDex")
            val dexFile = child.layout.buildDirectory.file(
                extension.runtimeLoader.moduleId.map { moduleId ->
                    "runtime-loader/android/$moduleId-dex.jar"
                }
            )
            zip.dependsOn(dexTask)
            zip.from(dexFile) {
                into("artifacts/$id/android")
                rename { "$id.jar" }
            }
        }

        if ("web" in includedPlatforms && "web" in extension.platforms) {
            val syncTask = child.tasks.named("wasmJsDevelopmentExecutableCompileSync")
            val moduleName = wasmModuleName(child)
            val syncDir = child.layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin")
            zip.dependsOn(syncTask)
            zip.from(syncDir) {
                into("artifacts/$id/web")
                include(
                    "$moduleName.mjs",
                    "$moduleName.import-object.mjs",
                    "$moduleName.wasm",
                )
            }
        }

        NATIVE_STORE_TARGETS.forEach { target ->
            if (target.platform !in includedPlatforms || target.platform !in extension.platforms) return@forEach
            val host = checkNotNull(extension.nativeHosts[target.gradleTarget])
            val outputName = nativeOutputName(extension.runtimeLoader, target.platform)
            val nativeDir = host.layout.buildDirectory.dir(
                "runtime-loader/${target.gradleTarget}/out"
            )
            zip.dependsOn("${host.path}:runtimeLoaderNativeBuild")
            zip.from(nativeDir) {
                into("artifacts/$id/${target.platform}")
                if (target.platform == "ios_arm64") {
                    include("$outputName/**", "runtime-loader-host.properties")
                } else {
                    include(
                        outputName,
                        "$outputName.runtime-loader.properties",
                        "runtime-loader-host.properties",
                    )
                }
            }
        }
    }

    private fun resolve(
        storeProject: Project,
        store: DemoExtensionsStoreExtension,
    ): List<ResolvedExtensionProject> {
        if (store.projects.isEmpty()) {
            throw GradleException("The extensions store has no extension projects")
        }

        return store.projects.map { dependency ->
            val child = storeProject.rootProject.project(dependency.path)
            val metadata = child.extensions.findByType(DemoExtensionProjectExtension::class.java)
                ?: throw GradleException("${child.path} must apply dev.brahmkshatriya.demo.extension")
            val runtimeLoader = child.runtimeLoaderModuleView()
                ?: throw GradleException("${child.path} must apply dev.brahmkshatriya.runtime-loader.module")
            val nativeHosts = NATIVE_STORE_TARGETS.mapNotNull { target ->
                findNativeHost(child, runtimeLoader, target.gradleTarget)
                    ?.let { target.gradleTarget to it }
            }.toMap()
            val platforms = buildList {
                if (child.tasks.findByName("jvmJar") != null) add("jvm")
                if (
                    child.tasks.findByName("runtimeLoaderAndroidDex") != null &&
                    child.tasks.findByName("bundleAndroidMainClassesToRuntimeJar") != null
                ) add("android")
                NATIVE_STORE_TARGETS.forEach { target ->
                    if (
                        nativeHosts[target.gradleTarget] != null &&
                        child.tasks.findByName(target.compileTask) != null &&
                        canBuildNativeStoreTarget(target.gradleTarget)
                    ) {
                        add(target.platform)
                    }
                }
                if (child.tasks.findByName("wasmJsDevelopmentExecutableCompileSync") != null) add("web")
            }
            ResolvedExtensionProject(child, metadata, runtimeLoader, nativeHosts, platforms)
        }
    }

    private fun nativeOutputName(
        runtimeLoader: RuntimeLoaderModuleView,
        platform: String,
    ): String = when (platform) {
        "windows_x64" -> runtimeLoader.windowsOutputFileName.get()
        "macos_x64", "macos_arm64" -> runtimeLoader.appleOutputFileName.get()
        "ios_arm64" -> runtimeLoader.iosOutputFileName.get()
        else -> runtimeLoader.outputFileName.get()
    }

    private fun canBuildNativeStoreTarget(target: String): Boolean {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val x64 = arch in setOf("x86_64", "amd64", "x64")
        val arm64 = arch in setOf("aarch64", "arm64")
        return when (target) {
            "linuxX64" -> os.contains("linux") && x64
            "linuxArm64" -> os.contains("linux") && arm64
            "mingwX64" -> true
            "macosX64", "macosArm64", "iosArm64" -> os.contains("mac")
            else -> false
        }
    }

    private fun findNativeHost(
        project: Project,
        runtimeLoader: RuntimeLoaderModuleView,
        target: String,
    ): Project? = runtimeLoader.hosts.orNull.orEmpty()
        .map { project.rootProject.project(it.path) }
        .firstOrNull { host ->
            val hostExtension = host.runtimeLoaderHostView()
            hostExtension != null && hostExtension.entryPoint.isPresent && (
                hostExtension.target.orNull == target || target in hostExtension.targets.orNull.orEmpty()
            )
        }

    private fun findWebHost(extension: ResolvedExtensionProject): Project? =
        extension.runtimeLoader.hosts.orNull.orEmpty()
            .map { extension.project.rootProject.project(it.path) }
            .firstOrNull { host ->
                host.runtimeLoaderHostView()?.wasmModuleRunner?.isPresent == true
            }

    private fun validate(extensions: List<ResolvedExtensionProject>) {
        extensions.groupBy { it.metadata.id }
            .filterValues { it.size > 1 }
            .keys.firstOrNull()
            ?.let { duplicate -> throw GradleException("Duplicate extension id '$duplicate'") }

        extensions.forEach { extension ->
            val metadata = extension.metadata
            requireValue(metadata.id, "id", metadata.id)
            requireValue(metadata.id, "name", metadata.name)
            requireValue(metadata.id, "description", metadata.description)
            requireValue(metadata.id, "updateUrl", metadata.updateUrl)
            if (metadata.capabilitiesSpec.values.isEmpty()) {
                throw GradleException("Extension '${metadata.id}' must declare at least one capability")
            }
            if (metadata.authors.isEmpty()) {
                throw GradleException("Extension '${metadata.id}' must declare at least one author")
            }
            if (extension.platforms.isEmpty()) {
                throw GradleException("Extension '${metadata.id}' has no inferred platform artifacts")
            }
        }
    }

    private fun requireValue(extensionId: String, field: String, value: String?) {
        if (value.isNullOrBlank()) {
            throw GradleException("Extension '$extensionId' is missing '$field'")
        }
    }

    private fun toJsonModel(extension: ResolvedExtensionProject): Map<String, Any?> {
        val metadata = extension.metadata
        val artifacts = linkedMapOf<String, CatalogArtifact>()
        if ("jvm" in extension.platforms) {
            artifacts["jvm"] = CatalogArtifact(
                "artifacts/${metadata.id}/jvm/${metadata.id}.jar",
                GENERATED_ENTRY_CLASS,
            )
        }
        if ("android" in extension.platforms) {
            artifacts["android"] = CatalogArtifact(
                "artifacts/${metadata.id}/android/${metadata.id}.jar",
                GENERATED_ENTRY_CLASS,
            )
        }
        NATIVE_STORE_TARGETS.forEach { target ->
            if (target.platform !in extension.platforms) return@forEach
            val outputName = nativeOutputName(extension.runtimeLoader, target.platform)
            val root = "artifacts/${metadata.id}/${target.platform}"
            if (target.platform == "ios_arm64") {
                val executable = outputName.removeSuffix(".framework")
                artifacts[target.platform] = CatalogArtifact(
                    path = "$root/$outputName/$executable",
                    entry = GENERATED_NATIVE_ENTRY,
                    resources = listOf(
                        "$root/$outputName/Info.plist",
                        "$root/$outputName/$executable.runtime-loader.properties",
                        "$root/runtime-loader-host.properties",
                    ),
                )
            } else {
                artifacts[target.platform] = CatalogArtifact(
                    path = "$root/$outputName",
                    entry = GENERATED_NATIVE_ENTRY,
                    resources = listOf(
                        "$root/$outputName.runtime-loader.properties",
                        "$root/runtime-loader-host.properties",
                    ),
                )
            }
        }
        if ("web" in extension.platforms) {
            val moduleName = wasmModuleName(extension.project)
            val root = "artifacts/${metadata.id}/web"
            artifacts["web"] = CatalogArtifact(
                path = "$root/$moduleName.mjs",
                entry = GENERATED_WASM_ENTRY,
                resources = listOf(
                    "$root/$moduleName.import-object.mjs",
                    "$root/$moduleName.wasm",
                ),
            )
        }

        return linkedMapOf(
            "id" to metadata.id,
            "type" to "native",
            "artifacts" to artifacts.mapValues { (_, artifact) ->
                linkedMapOf(
                    "path" to artifact.path,
                    "entry" to artifact.entry,
                    "resources" to artifact.resources,
                )
            },
            "capabilities" to metadata.capabilitiesSpec.values.toList(),
            "name" to metadata.name,
            "description" to metadata.description,
            "icon" to metadata.icon,
            "repoLink" to metadata.repoLink,
            "authors" to metadata.authors.map { author ->
                linkedMapOf("name" to author.name, "avatar" to author.avatar, "link" to author.link)
            },
            "updateUrl" to metadata.updateUrl,
            "supportedPlatforms" to extension.platforms,
        )
    }
}

private fun wasmModuleName(project: Project): String =
    project.rootProject.name + project.path.replace(':', '-')

private fun String.toTaskSuffix(): String =
    split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotBlank() }
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
        .ifEmpty { "Module" }
