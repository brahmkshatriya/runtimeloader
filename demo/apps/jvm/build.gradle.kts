plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("dev.brahmkshatriya.runtime-loader.host")
}

runtimeLoaderHost {
    jvmMainClass.set("dev.brahmkshatriya.runtimeloader.demo.MainKt")
}

kotlin {
    jvm()

    sourceSets.jvmMain.dependencies {
        implementation(projects.demo.client)
        implementation(projects.runtimeLoader)
        implementation(compose.material3)
    }
}

val extensionStoreProject = project(projects.demo.store.path)
val extensionStoreZip = extensionStoreProject.layout.buildDirectory.file("distributions/demo-extension-store.zip")
val jvmHostJar = tasks.named<org.gradle.jvm.tasks.Jar>("jvmJar")
val jvmRuntimeClasspath = configurations.named("jvmRuntimeClasspath")

tasks.register<JavaExec>("verifyExtensionStoreDemo") {
    group = "verification"
    description = "Load metadata and a JVM extension from the external extension-store ZIP."
    dependsOn(jvmHostJar, "${extensionStoreProject.path}:packageExternalExtensionStore")
    mainClass.set("dev.brahmkshatriya.runtimeloader.demo.MainKt")
    classpath(jvmHostJar.flatMap { it.archiveFile }, jvmRuntimeClasspath)
    doFirst {
        args(extensionStoreZip.get().asFile.absolutePath, "--store-smoke")
    }
}

tasks.register<JavaExec>("runExtensionStoreDemo") {
    group = "application"
    description = "Open the JVM extension-store picker UI from the external ZIP."
    dependsOn(jvmHostJar, "${extensionStoreProject.path}:packageExternalExtensionStore")
    mainClass.set("dev.brahmkshatriya.runtimeloader.demo.MainKt")
    classpath(jvmHostJar.flatMap { it.archiveFile }, jvmRuntimeClasspath)
    doFirst {
        args(extensionStoreZip.get().asFile.absolutePath, "--store-demo")
    }
}

tasks.register<JavaExec>("verifyExtensionStoreComposeDemo") {
    group = "verification"
    description = "Open a metadata-selected JVM extension from the external ZIP inside host Compose."
    dependsOn(jvmHostJar, "${extensionStoreProject.path}:packageExternalExtensionStore")
    mainClass.set("dev.brahmkshatriya.runtimeloader.demo.MainKt")
    classpath(jvmHostJar.flatMap { it.archiveFile }, jvmRuntimeClasspath)
    doFirst {
        args(extensionStoreZip.get().asFile.absolutePath, "--store-verify-compose")
    }
}
