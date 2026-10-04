plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.runtime-loader.host")
}

runtimeLoaderHost {
    // Deliberately no `target`: the plugin must infer both Native targets from this KMP project.
    entryPoint.set("dev.brahmkshatriya.runtimeloader.fixture.main")
    jvmMainClass.set("dev.brahmkshatriya.runtimeloader.fixture.FixtureMainKt")
    executableNames.put("mingwX64", "fixture.exe")
}

kotlin {
    jvm()
    linuxX64()
    mingwX64()

    sourceSets.commonMain.dependencies {
        implementation(projects.fixture.api)
        implementation(projects.runtimeLoader)
    }
}
