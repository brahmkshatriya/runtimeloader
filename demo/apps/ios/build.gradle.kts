plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.runtime-loader.host")
    id("dev.brahmkshatriya.runtime-loader.compose-native-compatibility")
}

runtimeLoaderHost {
    target.set("iosArm64")
    // iOS emits a dynamic framework rather than a program; the backend does not use an entry point.
    entryPoint.set("dev.brahmkshatriya.runtimeloader.demo.iosRuntimeLoaderHost")
    executableName.set("RuntimeLoaderDemoHost.framework")
}

kotlin {
    iosArm64()

    sourceSets.iosArm64Main.dependencies {
        implementation(projects.demo.client)
        implementation(projects.runtimeLoader)
    }
}
