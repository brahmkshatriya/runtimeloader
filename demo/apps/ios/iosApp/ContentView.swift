import SwiftUI
import UniformTypeIdentifiers
import RuntimeLoaderDemoHost

struct ContentView: View {
    @State private var frameworkURL: URL?
    @State private var profileURL: URL?
    @State private var p12URL: URL?
    @State private var password = ""
    @State private var result = "Select an unsigned extension framework, mobileprovision, and P12."
    @State private var picker: PickerKind?

    enum PickerKind: Identifiable {
        case framework, profile, p12
        var id: Int { hashValue }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Unsigned extension") {
                    pickRow("Framework", value: frameworkURL?.lastPathComponent) { picker = .framework }
                }
                Section("User signing material") {
                    pickRow("Provisioning profile", value: profileURL?.lastPathComponent) { picker = .profile }
                    pickRow("PKCS#12", value: p12URL?.lastPathComponent) { picker = .p12 }
                    SecureField("P12 password", text: $password)
                }
                Button("Sign and load") { verify() }
                    .disabled(frameworkURL == nil || profileURL == nil || p12URL == nil)
                Section("Result") { Text(result).textSelection(.enabled) }
            }
            .navigationTitle("Runtime Loader")
        }
        .fileImporter(
            isPresented: Binding(
                get: { picker != nil },
                set: { if !$0 { picker = nil } }
            ),
            allowedContentTypes: picker == .framework ? [.package, .folder] : [.data],
            allowsMultipleSelection: false
        ) { response in
            guard let kind = picker else { return }
            picker = nil
            do {
                let selected = try response.get().first!
                let local = try importIntoContainer(selected, kind: kind)
                switch kind {
                case .framework: frameworkURL = local
                case .profile: profileURL = local
                case .p12: p12URL = local
                }
            } catch {
                result = "Import failed: \(error)"
            }
        }
    }

    @ViewBuilder
    private func pickRow(_ title: String, value: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                Text(value ?? "Choose…").foregroundStyle(.secondary)
            }
        }
    }

    private func importIntoContainer(_ source: URL, kind: PickerKind) throws -> URL {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let root = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ).appendingPathComponent("RuntimeLoaderDemo", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let destination = root.appendingPathComponent(source.lastPathComponent, isDirectory: kind == .framework)
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.copyItem(at: source, to: destination)
        return destination
    }

    private func verify() {
        guard let frameworkURL, let profileURL, let p12URL else { return }
        guard let hostFramework = Bundle(identifier: "RuntimeLoaderDemoHost") ??
            Bundle(path: Bundle.main.privateFrameworksPath?.appending("/RuntimeLoaderDemoHost.framework") ?? ""),
              let manifest = hostFramework.path(forResource: "runtime-loader-host", ofType: "properties")
        else {
            result = "RuntimeLoaderDemoHost.framework is missing its ABI manifest."
            return
        }
        do {
            result = try IosDemoKt.verifySignedExtension(
                frameworkPath: frameworkURL.path,
                hostManifestPath: manifest,
                provisioningProfilePath: profileURL.path,
                pkcs12Path: p12URL.path,
                pkcs12Password: password
            )
        } catch {
            result = "Sign/load failed: \(error)"
        }
    }
}
