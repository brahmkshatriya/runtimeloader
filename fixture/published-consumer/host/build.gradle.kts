plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.runtime-loader.host")
}

runtimeLoaderHost {
    entryPoint.set("dev.brahmkshatriya.runtimeloader.publishedfixture.main")
    jvmMainClass.set("dev.brahmkshatriya.runtimeloader.publishedfixture.PublishedFixtureMainKt")
}

kotlin {
    jvm()
    linuxX64()
    sourceSets.commonMain.dependencies {
        implementation(project(":api"))
        implementation("dev.brahmkshatriya.runtimeloader:runtime-loader:0.1.0-alpha02")
    }
}
