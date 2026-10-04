package dev.brahmkshatriya.runtimeloader.demo.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

internal const val GENERATED_ENTRY_CLASS =
    "dev.brahmkshatriya.runtimeloader.generated.RuntimeLoaderGeneratedEntry"
internal const val GENERATED_WASM_ENTRY = "runtimeExtensionCreate"
internal const val GENERATED_NATIVE_ENTRY =
    "kfun:dev.brahmkshatriya.runtimeloader.generated#runtimeExtensionCreate(){}" +
        "kotlinx.cinterop.CPointer<out|kotlinx.cinterop.CPointed>"

public class DemoAuthorSpec internal constructor(
    public var name: String,
) {
    public var avatar: String? = null
    public var link: String? = null
}

public class DemoCapabilitiesSpec internal constructor() {
    internal val values: LinkedHashSet<String> = linkedSetOf()

    public fun mediaData() { values += "media_data" }
    public fun audio() { values += "audio" }
    public fun video() { values += "video" }
    public fun lyrics() { values += "lyrics" }
    public fun downloader() { values += "downloader" }
    public fun player() { values += "player" }
    public fun custom(id: String) { values += id }
}

public open class DemoExtensionProjectExtension {
    public var id: String = ""
    public var name: String = ""
    public var description: String = ""
    public var icon: String? = null
    public var repoLink: String? = null
    public var updateUrl: String? = null

    internal val capabilitiesSpec: DemoCapabilitiesSpec = DemoCapabilitiesSpec()
    internal val authors: MutableList<DemoAuthorSpec> = mutableListOf()

    public fun capabilities(configure: DemoCapabilitiesSpec.() -> Unit) {
        capabilitiesSpec.configure()
    }

    public fun author(name: String, configure: DemoAuthorSpec.() -> Unit = {}) {
        authors += DemoAuthorSpec(name).apply(configure)
    }
}

/** Demo metadata only. Entry generation belongs to the reusable runtime-loader.module plugin. */
public class DemoExtensionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "demoExtension",
            DemoExtensionProjectExtension::class.java,
        )

        project.pluginManager.withPlugin("dev.brahmkshatriya.runtime-loader.module") {
            val runtimeLoader = checkNotNull(project.runtimeLoaderModuleView()) {
                "runtimeLoaderModule extension was not created"
            }
            runtimeLoader.moduleId.convention(project.provider {
                extension.id.ifBlank { project.name }
            })
            runtimeLoader.outputFileName.convention(runtimeLoader.moduleId.map { id ->
                "libruntime-loader-$id.so"
            })
        }
    }
}
