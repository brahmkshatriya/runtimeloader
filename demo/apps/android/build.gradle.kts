plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}

android {
    namespace = "dev.brahmkshatriya.runtimeloader.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.brahmkshatriya.runtimeloader.demo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets.getByName("main") {
        assets.directories.add(layout.buildDirectory.dir("generated/runtimeLoaderAssets").get().asFile.absolutePath)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val extensionStoreProject = project(projects.demo.store.path)
val androidExtensionStoreZip = extensionStoreProject.layout.buildDirectory.file(
    "distributions/demo-extension-store-android.zip"
)

val prepareExtensionStoreAsset = tasks.register<Copy>("prepareExtensionStoreAsset") {
    dependsOn("${extensionStoreProject.path}:packageExternalExtensionStoreAndroid")
    from(androidExtensionStoreZip) {
        rename { "demo-extension-store.zip" }
    }
    into(layout.buildDirectory.dir("generated/runtimeLoaderAssets"))
}

tasks.named("preBuild").configure {
    dependsOn(prepareExtensionStoreAsset)
}

dependencies {
    implementation(projects.demo.client)
    implementation(projects.runtimeLoader)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-alpha29")
}
