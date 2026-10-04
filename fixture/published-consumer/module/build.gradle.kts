import org.gradle.api.artifacts.ProjectDependency

plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.runtime-loader.module")
}

runtimeLoaderModule {
    api.set(project.dependencies.project(mapOf("path" to ":api")) as ProjectDependency)
    hosts.set(listOf(project.dependencies.project(mapOf("path" to ":host")) as ProjectDependency))
    entry {
        contract.set("dev.brahmkshatriya.runtimeloader.publishedfixture.GreetingExtension")
        implementation.set("dev.brahmkshatriya.runtimeloader.publishedfixture.GreetingExtensionImpl")
    }
}

kotlin {
    jvm()
    linuxX64()
    sourceSets.commonMain.dependencies {
        implementation(project(":api"))
    }
}
