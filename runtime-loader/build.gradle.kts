@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import java.util.Properties

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    `maven-publish`
    id("com.vanniktech.maven.publish")
}

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    wasmJs {
        nodejs()
    }
    android {
        namespace = "dev.brahmkshatriya.runtimeloader"
        compileSdk = 36
        minSdk = 23
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    linuxX64()
    linuxArm64()
    mingwX64()
    macosX64()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
        linuxX64Main {
            kotlin.srcDir("src/nativeShared/kotlin")
            kotlin.srcDir("src/posixDynamicMain/kotlin")
        }
        linuxArm64Main {
            kotlin.srcDir("src/nativeShared/kotlin")
            kotlin.srcDir("src/posixDynamicMain/kotlin")
        }
        mingwX64Main {
            kotlin.srcDir("src/nativeShared/kotlin")
        }
        macosX64Main {
            kotlin.srcDir("src/nativeShared/kotlin")
            kotlin.srcDir("src/posixDynamicMain/kotlin")
        }
        macosArm64Main {
            kotlin.srcDir("src/nativeShared/kotlin")
            kotlin.srcDir("src/posixDynamicMain/kotlin")
        }
        iosArm64Main {
            kotlin.srcDir("src/nativeShared/kotlin")
            kotlin.srcDir("src/posixDynamicMain/kotlin")
            kotlin.srcDir("src/iosMain/kotlin")
        }
        iosSimulatorArm64Main {
            kotlin.srcDir("src/nativeShared/kotlin")
            kotlin.srcDir("src/posixDynamicMain/kotlin")
            kotlin.srcDir("src/iosMain/kotlin")
        }
    }
}


val releaseProperties = Properties().apply {
    rootProject.file("gradle/runtime-loader-release.properties").inputStream().use(::load)
}
fun releaseProperty(name: String): String = checkNotNull(releaseProperties.getProperty(name)) {
    "Missing release property '$name'"
}

mavenPublishing {
    pom {
        name.set(releaseProperty("projectName"))
        description.set(releaseProperty("projectDescription"))
        url.set(releaseProperty("projectUrl"))
        licenses {
            license {
                name.set(releaseProperty("licenseName"))
                url.set(releaseProperty("licenseUrl"))
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(releaseProperty("developerId"))
                name.set(releaseProperty("developerName"))
                url.set(releaseProperty("developerUrl"))
            }
        }
        scm {
            url.set(releaseProperty("projectUrl"))
            connection.set("scm:git:${releaseProperty("projectUrl")}.git")
            developerConnection.set("scm:git:ssh://git@github.com/brahmkshatriya/runtimeloader.git")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "releaseTest"
            url = uri(rootProject.layout.buildDirectory.dir("release-test-repository"))
        }
    }
}


tasks.named<Test>("jvmTest") {
    useJUnit()
}
