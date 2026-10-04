plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.compose")
    id("dev.brahmkshatriya.runtime-loader.host")
}

runtimeLoaderHost {
    target.set("mingwX64")
    entryPoint.set("dev.brahmkshatriya.runtimeloader.demo.main")
    executableName.set("app.exe")
}

kotlin {
    mingwX64 {
        binaries.executable {
            entryPoint = "dev.brahmkshatriya.runtimeloader.demo.main"
        }
    }

    sourceSets.mingwX64Main.dependencies {
        implementation(projects.demo.client)
        implementation(projects.runtimeLoader)
        implementation("dev.brahmkshatriya.compose.material3:material3:1.13.0-alpha05")
        implementation("dev.brahmkshatriya.compose.desktop:desktop-native:1.13.0-alpha05")
    }
}

val extensionStoreProject = project(projects.demo.store.path)
val extensionStoreZip = extensionStoreProject.layout.buildDirectory.file("distributions/demo-extension-store.zip")
val runtimeLoaderOutput = layout.buildDirectory.dir("runtime-loader/mingwX64/out")
val runtimeLoaderExecutable = runtimeLoaderOutput.map { it.file("app.exe") }

tasks.register<Copy>("stageExtensionStoreDemo") {
    group = "distribution"
    description = "Stage the external extension-store ZIP beside the Windows Native host executable."
    dependsOn("${extensionStoreProject.path}:packageExternalExtensionStore")
    from(extensionStoreZip)
    into(runtimeLoaderOutput)
}

tasks.register<Exec>("runExtensionStoreDemo") {
    group = "application"
    description = "Launch the cross-built Windows Native external-store verification through the desktop handler."
    dependsOn("stageExtensionStoreDemo")
    doFirst {
        commandLine("gio", "open", runtimeLoaderExecutable.get().asFile.absolutePath)
    }
}
