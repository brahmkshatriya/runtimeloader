plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":"))
    implementation(localGroovy())
}

gradlePlugin {
    plugins {
        create("demoExtensionsStore") {
            id = "dev.brahmkshatriya.demo.extensions-store"
            implementationClass = "dev.brahmkshatriya.runtimeloader.demo.gradle.DemoExtensionsStorePlugin"
        }
        create("demoExtension") {
            id = "dev.brahmkshatriya.demo.extension"
            implementationClass = "dev.brahmkshatriya.runtimeloader.demo.gradle.DemoExtensionPlugin"
        }
        create("demoNativeCompatibility") {
            id = "dev.brahmkshatriya.demo.native-compatibility"
            implementationClass = "dev.brahmkshatriya.runtimeloader.demo.gradle.DemoNativeCompatibilityPlugin"
        }
    }
}
