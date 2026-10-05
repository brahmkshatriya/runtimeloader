package dev.brahmkshatriya.runtimeloader.gradle

import org.gradle.api.Project

/**
 * Compose Native platform publications contain a few transitive dependencies using their
 * multiplatform root module names. KGP 2.4.20 no longer always selects the concrete Native child
 * publication from those edges when they originate in an already platform-specific module.
 * Keep supported Compose Native targets entirely on the matching concrete platform publications.
 */
public fun Project.configureNativeDependencyCompatibility() {
    val marker = "dev.brahmkshatriya.runtime-loader.native-compatibility-configured"
    if (extensions.extraProperties.has(marker)) return
    extensions.extraProperties.set(marker, true)

    afterEvaluate {
        val selectedForkVersion = runtimeLoaderComposeNativeVersionOrNull()
        configurations.matching { configuration ->
            configuration.name in setOf(
            "linuxX64CompileKlibraries",
            "linuxArm64CompileKlibraries",
            "mingwX64CompileKlibraries",
            "macosX64CompileKlibraries",
            "macosArm64CompileKlibraries",
        )
    }.configureEach {
        val suffix = when (name) {
            "linuxX64CompileKlibraries" -> "linuxx64"
            "linuxArm64CompileKlibraries" -> "linuxarm64"
            "mingwX64CompileKlibraries" -> "mingwx64"
            "macosX64CompileKlibraries" -> "macosx64"
            "macosArm64CompileKlibraries" -> "macosarm64"
            else -> return@configureEach
        }

        resolutionStrategy.eachDependency {
            val group = requested.group ?: return@eachDependency
            val module = requested.name
            val version = requested.version ?: return@eachDependency

            if (group.startsWith("org.jetbrains.compose.")) {
                val forkGroup = group.replaceFirst(
                    "org.jetbrains.compose.",
                    "dev.brahmkshatriya.compose.",
                )
                val platformName = if (module.endsWith("-$suffix")) module else "$module-$suffix"
                val forkVersion = selectedForkVersion ?: version
                useTarget("$forkGroup:$platformName:$forkVersion")
                because("Compose Native $suffix uses the consumer-selected dev.brahmkshatriya fork publications")
                return@eachDependency
            }

            if (group.startsWith("org.jetbrains.androidx.")) {
                val forkGroup = group.replaceFirst(
                    "org.jetbrains.androidx.",
                    "dev.brahmkshatriya.androidx.",
                )
                val platformName = if (module.endsWith("-$suffix")) module else "$module-$suffix"
                val forkVersion = selectedForkVersion ?: version
                useTarget("$forkGroup:$platformName:$forkVersion")
                because("Compose Native $suffix uses the consumer-selected dev.brahmkshatriya AndroidX fork publications")
                return@eachDependency
            }

            if (suffix.startsWith("macos")) {
                val forkGroup = when {
                    group.startsWith("androidx.compose.") -> group.replaceFirst(
                        "androidx.compose.",
                        "dev.brahmkshatriya.compose.",
                    )
                    group == "androidx.collection" -> "dev.brahmkshatriya.androidx.collection"
                    group.startsWith("androidx.lifecycle") -> "dev.brahmkshatriya.androidx.lifecycle"
                    group.startsWith("androidx.savedstate") -> "dev.brahmkshatriya.androidx.savedstate"
                    group.startsWith("androidx.navigationevent") -> "dev.brahmkshatriya.androidx.navigationevent"
                    else -> null
                }
                if (forkGroup != null) {
                    val rootModule = module.removeSuffix("-$suffix")
                    val forkVersion = selectedForkVersion ?: version
                    useTarget("$forkGroup:$rootModule-$suffix:$forkVersion")
                    because("Compose Native macOS uses consumer-selected fork publications for desktop AndroidX/Compose dependencies")
                    return@eachDependency
                }
            }

            if (module.endsWith("-$suffix")) return@eachDependency

            val needsPlatformModule = when {
                group.startsWith("androidx.compose.") -> true
                group == "androidx.collection" -> true
                group == "androidx.graphics" -> true
                group.startsWith("androidx.lifecycle") -> true
                group.startsWith("androidx.savedstate") -> true
                group.startsWith("androidx.navigationevent") -> true
                group.startsWith("dev.brahmkshatriya.androidx.") -> true
                else -> false
            }
            if (needsPlatformModule) {
                useTarget("$group:$module-$suffix:$version")
                because("Compose Native requires the concrete $suffix publication")
                }
            }
        }
    }
}


/**
 * Resolve the fork version from the consumer project instead of pinning Runtime Loader to the
 * version used by its own demo. An explicit project property is available for unusual dependency
 * graphs with multiple fork versions.
 */
private fun Project.runtimeLoaderComposeNativeVersionOrNull(): String? {
    findProperty("runtimeLoader.composeNativeVersion")
        ?.toString()
        ?.takeIf(String::isNotBlank)
        ?.let { return it }

    val versions = configurations
        .asSequence()
        .flatMap { it.dependencies.asSequence() }
        .filter { dependency ->
            dependency.group?.startsWith("dev.brahmkshatriya.compose") == true ||
                dependency.group?.startsWith("dev.brahmkshatriya.androidx") == true
        }
        .mapNotNull { it.version }
        .filter(String::isNotBlank)
        .toSet()

    return when (versions.size) {
        0 -> null
        1 -> versions.single()
        else -> throw org.gradle.api.GradleException(
            "Runtime Loader found multiple Compose Native fork versions $versions. " +
                "Align the consumer dependencies or set -PruntimeLoader.composeNativeVersion=<version>."
        )
    }
}
