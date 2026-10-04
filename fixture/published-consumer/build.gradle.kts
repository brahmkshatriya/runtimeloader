plugins {
    kotlin("multiplatform") apply false
    id("dev.brahmkshatriya.runtime-loader.host") apply false
    id("dev.brahmkshatriya.runtime-loader.module") apply false
    id("dev.brahmkshatriya.runtime-loader.compose-native-compatibility") apply false
}

allprojects {
    group = "dev.brahmkshatriya.runtimeloader.publishedfixture"
    version = "1.0"
}
