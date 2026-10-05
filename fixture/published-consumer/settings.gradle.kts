pluginManagement {
    repositories {
        maven { url = uri(file("../../build/release-test-repository")) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("multiplatform") version "2.4.20"
        id("dev.brahmkshatriya.runtime-loader.host") version "0.1.1"
        id("dev.brahmkshatriya.runtime-loader.module") version "0.1.1"
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri(file("../../build/release-test-repository")) }
        google()
        mavenCentral()
    }
}

rootProject.name = "runtime-loader-published-consumer"
include(":api", ":module", ":host")
