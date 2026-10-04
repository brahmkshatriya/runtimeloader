plugins {
    base
    id("dev.brahmkshatriya.demo.extensions-store")
}

extensionsStore {
    extensions(
        projects.demo.store.counter,
        projects.demo.store.about,
    )
}
