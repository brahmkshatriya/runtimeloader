import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugin.compatibility.compatibility
import java.util.Properties

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

val releaseProperties = Properties().apply {
    rootProject.projectDir.resolve("../gradle/runtime-loader-release.properties")
        .inputStream().use(::load)
}
fun releaseProperty(name: String): String = checkNotNull(releaseProperties.getProperty(name)) {
    "Missing release property '$name'"
}

val projectUrl = releaseProperty("projectUrl")
val projectName = releaseProperty("projectName")
val projectDescription = releaseProperty("projectDescription")
val licenseName = releaseProperty("licenseName")
val licenseUrl = releaseProperty("licenseUrl")
val developerId = releaseProperty("developerId")
val developerName = releaseProperty("developerName")
val developerUrl = releaseProperty("developerUrl")

group = releaseProperty("group")
version = releaseProperty("version")
description = projectDescription

dependencies {
    // Runtime Loader intentionally supports one KGP/Kotlin/Native toolchain in the 0.1.x line.
    // Do not publish KGP as a transitive plugin dependency: consumers own their Kotlin plugin.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    implementation("org.ow2.asm:asm:9.9")
    implementation("org.ow2.asm:asm-tree:9.9")
    implementation(localGroovy())
}

gradlePlugin {
    website.set(projectUrl)
    vcsUrl.set("$projectUrl.git")

    plugins {
        create("runtimeLoaderHost") {
            id = "dev.brahmkshatriya.runtime-loader.host"
            implementationClass = "dev.brahmkshatriya.runtimeloader.gradle.RuntimeLoaderHostPlugin"
            displayName = "Kotlin Runtime Loader Host"
            description = "Build and verify host applications that attach separately compiled Runtime Loader modules."
            tags.set(listOf("kotlin", "kotlin-native", "multiplatform", "runtime", "plugins"))
            compatibility {
                features {
                    configurationCache = true
                    isolatedProjects = false
                }
            }
        }
        create("runtimeLoaderModule") {
            id = "dev.brahmkshatriya.runtime-loader.module"
            implementationClass = "dev.brahmkshatriya.runtimeloader.gradle.RuntimeLoaderModulePlugin"
            displayName = "Kotlin Runtime Loader Module"
            description = "Generate and package consumer-owned runtime-loadable Kotlin module entry bridges."
            tags.set(listOf("kotlin", "kotlin-native", "multiplatform", "runtime", "plugins"))
            compatibility {
                features {
                    configurationCache = true
                    isolatedProjects = false
                }
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") {
            artifactId = "runtime-loader-gradle-plugin"
        }
        pom {
            name.set(projectName)
            description.set(projectDescription)
            url.set(projectUrl)
            licenses {
                license {
                    name.set(licenseName)
                    url.set(licenseUrl)
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set(developerId)
                    name.set(developerName)
                    url.set(developerUrl)
                }
            }
            scm {
                url.set(projectUrl)
                connection.set("scm:git:$projectUrl.git")
                developerConnection.set("scm:git:ssh://git@github.com/brahmkshatriya/runtimeloader.git")
            }
        }
    }
    repositories {
        maven {
            name = "releaseTest"
            url = uri(rootProject.projectDir.parentFile.resolve("build/release-test-repository"))
        }
    }
}
