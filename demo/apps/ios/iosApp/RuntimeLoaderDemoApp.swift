import SwiftUI
import RuntimeLoaderIOSSigning

@main
struct RuntimeLoaderDemoApp: App {
    init() {
        // The Kotlin framework resolves the signer through dlsym so force the package into the app.
        RuntimeLoaderIOSSigningBootstrap.ensureLinked()
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}
