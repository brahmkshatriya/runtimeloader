pluginManagement {
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kotlin-runtime-loader"

include(":runtime-loader")
include(":fixture:api")
include(":fixture:module")
include(":fixture:host")
include(":demo:client")
include(":demo:apps:android")
include(":demo:apps:jvm")
include(":demo:apps:ios")
include(":demo:apps:linux")
include(":demo:apps:linuxArm64")
include(":demo:apps:macosX64")
include(":demo:apps:macosArm64")
include(":demo:apps:windows")
include(":demo:apps:web")
include(":demo:store")
include(":demo:store:counter")
include(":demo:store:about")
