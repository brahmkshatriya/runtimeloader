package dev.brahmkshatriya.runtimeloader.demo.gradle

import dev.brahmkshatriya.runtimeloader.gradle.configureNativeDependencyCompatibility
import org.gradle.api.Plugin
import org.gradle.api.Project

/** Repository-local compatibility for the shared Compose demo project, which is neither a host nor a module. */
public class DemoNativeCompatibilityPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.configureNativeDependencyCompatibility()
    }
}
