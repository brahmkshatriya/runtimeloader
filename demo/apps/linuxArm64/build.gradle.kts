plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.compose")
    id("dev.brahmkshatriya.runtime-loader.host")
}

runtimeLoaderHost {
    target.set("linuxArm64")
    entryPoint.set("dev.brahmkshatriya.runtimeloader.demo.main")
    executableName.set("app.kexe")
}

kotlin {
    linuxArm64()

    sourceSets.linuxArm64Main {
        kotlin.srcDir("../nativeDesktopShared/src/main/kotlin")
        dependencies {
            implementation(projects.demo.client)
            implementation(projects.runtimeLoader)
            implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
            implementation("dev.brahmkshatriya.compose.desktop:desktop-native:1.13.0-alpha05")
        }
    }
}

val extensionStoreProject = project(projects.demo.store.path)
val extensionStoreZip = extensionStoreProject.layout.buildDirectory.file("distributions/demo-extension-store.zip")
val runtimeLoaderExecutable = layout.buildDirectory.file("runtime-loader/linuxArm64/out/app.kexe")

tasks.register<Exec>("verifyExtensionStoreDemo") {
    group = "verification"
    description = "Load metadata and a Native extension from the external extension-store ZIP."
    dependsOn("${extensionStoreProject.path}:packageExternalExtensionStore")
    doFirst {
        commandLine(
            runtimeLoaderExecutable.get().asFile.absolutePath,
            extensionStoreZip.get().asFile.absolutePath,
            "--store-smoke",
        )
    }
}

tasks.register<Exec>("runExtensionStoreDemo") {
    group = "application"
    description = "Open the Native desktop extension-store picker UI from the external ZIP."
    dependsOn("${extensionStoreProject.path}:packageExternalExtensionStore")
    doFirst {
        commandLine(
            runtimeLoaderExecutable.get().asFile.absolutePath,
            extensionStoreZip.get().asFile.absolutePath,
            "--store-demo",
        )
    }
}

tasks.register<Exec>("verifyExtensionStoreComposeDemo") {
    group = "verification"
    description = "Open a metadata-selected Native extension from the external ZIP inside host Compose."
    dependsOn("${extensionStoreProject.path}:packageExternalExtensionStore")
    doFirst {
        commandLine(
            runtimeLoaderExecutable.get().asFile.absolutePath,
            extensionStoreZip.get().asFile.absolutePath,
            "--store-verify-compose",
        )
    }
}
