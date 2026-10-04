import java.util.Properties

plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    id("com.android.application") version "9.4.0" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.0" apply false
    id("org.jetbrains.compose") version "1.13.0-alpha01" apply false
    id("dev.brahmkshatriya.compose") version "1.13.0-alpha05" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

val releaseProperties = Properties().apply {
    rootProject.file("gradle/runtime-loader-release.properties").inputStream().use(::load)
}
fun releaseProperty(name: String): String = checkNotNull(releaseProperties.getProperty(name)) {
    "Missing release property '$name'"
}

allprojects {
    group = releaseProperty("group")
    version = releaseProperty("version")
}

val publishRuntimeLoaderGradlePluginToMavenLocal = tasks.register<GradleBuild>(
    "publishRuntimeLoaderGradlePluginToMavenLocal"
) {
    group = "publishing"
    description = "Publish the Runtime Loader Gradle plugins to Maven Local."
    dir = file("build-logic")
    tasks = listOf("publishToMavenLocal")
}

tasks.register("publishRuntimeLoaderToMavenLocal") {
    group = "publishing"
    description = "Publish the Runtime Loader KMP library and reusable Gradle plugins to Maven Local."
    dependsOn(":runtime-loader:publishToMavenLocal")
    dependsOn(publishRuntimeLoaderGradlePluginToMavenLocal)
}

val publishRuntimeLoaderGradlePluginToReleaseTestRepository = tasks.register<GradleBuild>(
    "publishRuntimeLoaderGradlePluginToReleaseTestRepository"
) {
    group = "publishing"
    description = "Publish the reusable Gradle plugins to the isolated release-test Maven repository."
    dir = file("build-logic")
    tasks = listOf("validatePlugins", "publishAllPublicationsToReleaseTestRepository")
}

tasks.register("publishRuntimeLoaderToReleaseTestRepository") {
    group = "publishing"
    description = "Publish all consumer artifacts to the isolated release-test Maven repository."
    dependsOn(":runtime-loader:publishAllPublicationsToReleaseTestRepository")
    dependsOn(publishRuntimeLoaderGradlePluginToReleaseTestRepository)
}

val validateRuntimeLoaderGradlePlugin = tasks.register<GradleBuild>("validateRuntimeLoaderGradlePlugin") {
    group = "verification"
    description = "Run Gradle's plugin-development validation on the reusable Runtime Loader plugins."
    dir = file("build-logic")
    tasks = listOf("validatePlugins")
}

val verifyPublishedRuntimeLoaderConsumer = tasks.register<GradleBuild>("verifyPublishedRuntimeLoaderConsumer") {
    group = "verification"
    description = "Resolve Runtime Loader only from the isolated Maven repository and run the standalone consumer fixture."
    dependsOn("publishRuntimeLoaderToReleaseTestRepository")
    dir = file("fixture/published-consumer")
    tasks = listOf("clean", ":host:runtimeLoaderJvmIntegrationTest", ":host:runtimeLoaderNativeIntegrationTest")
}

tasks.register("runtimeLoaderReleaseCheck") {
    group = "verification"
    description = "Run release metadata/plugin validation and the Maven-only external consumer test."
    dependsOn(verifyPublishedRuntimeLoaderConsumer)
    dependsOn(":runtime-loader:check")
}
