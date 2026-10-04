plugins {
    kotlin("multiplatform")
    id("dev.brahmkshatriya.runtime-loader.module")
}

runtimeLoaderModule {
    api.set(projects.fixture.api)
    hosts.set(listOf(projects.fixture.host))
    entry {
        contract.set("dev.brahmkshatriya.runtimeloader.fixture.GreetingExtension")
        implementation.set("dev.brahmkshatriya.runtimeloader.fixture.GreetingExtensionImpl")
    }
}

kotlin {
    jvm()
    linuxX64()
    mingwX64()

    sourceSets.commonMain.dependencies {
        implementation(projects.fixture.api)
    }
}
