package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project

class ComposeNativeCompatibilityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.requireRuntimeLoaderToolchain()
        project.configureNativeDependencyCompatibility()
    }
}
