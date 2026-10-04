import Foundation
import Darwin
import RorkSign


/// Forces the two C entry points to be retained when this package is linked statically into an app.
public enum RuntimeLoaderIOSSigningBootstrap {
    public static func ensureLinked() {
        runtimeLoaderIosFreeString(nil)
        _ = runtimeLoaderIosSignFramework
    }
}

private func duplicateCString(_ value: String) -> UnsafeMutablePointer<CChar>? {
    value.withCString { strdup($0) }
}

@_cdecl("runtime_loader_ios_sign_framework")
public func runtimeLoaderIosSignFramework(
    frameworkPathPointer: UnsafePointer<CChar>?,
    provisioningProfilePathPointer: UnsafePointer<CChar>?,
    pkcs12PathPointer: UnsafePointer<CChar>?,
    pkcs12PasswordPointer: UnsafePointer<CChar>?,
    codeDirectoryIdentifierPointer: UnsafePointer<CChar>?,
    teamIdOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?,
    errorMessageOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
) -> Int32 {
    teamIdOut?.pointee = nil
    errorMessageOut?.pointee = nil

    guard
        let frameworkPathPointer,
        let provisioningProfilePathPointer,
        let pkcs12PathPointer,
        let pkcs12PasswordPointer,
        let codeDirectoryIdentifierPointer
    else {
        errorMessageOut?.pointee = duplicateCString("Runtime Loader received a null iOS signing argument")
        return 1
    }

    do {
        let frameworkURL = URL(fileURLWithPath: String(cString: frameworkPathPointer), isDirectory: true)
        let profileURL = URL(fileURLWithPath: String(cString: provisioningProfilePathPointer))
        let credentialURL = URL(fileURLWithPath: String(cString: pkcs12PathPointer))
        let password = String(cString: pkcs12PasswordPointer)
        let codeDirectoryIdentifier = String(cString: codeDirectoryIdentifierPointer)
        let profile = try Data(contentsOf: profileURL)
        let credential = try Data(contentsOf: credentialURL)

        // This verifies that the profile authorizes the certificate carried by the PKCS#12 before
        // any framework bytes are modified, and gives us the Team ID iOS library validation uses.
        let teamIdentifier = try RorkSigner.validatedTeamIdentifier(
            provisioningProfileData: profile,
            credentialData: credential,
            password: password
        )

        // Development/ad-hoc iOS apps normally carry the profile used to sign the host. When it is
        // available, reject credentials from another team before mutating the downloaded framework.
        // Some sideloading systems omit the embedded profile, so absence is not itself an error.
        if let hostProfileURL = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision") {
            let hostProfile = try Data(contentsOf: hostProfileURL)
            let hostTeamIdentifier = try RorkSigner.teamIdentifier(provisioningProfileData: hostProfile)
            guard hostTeamIdentifier == teamIdentifier else {
                throw NSError(
                    domain: "RuntimeLoaderIOSSigning",
                    code: 3,
                    userInfo: [
                        NSLocalizedDescriptionKey:
                            "Signing material Team ID \(teamIdentifier) does not match host Team ID \(hostTeamIdentifier)."
                    ]
                )
            }
        }

        var options = FrameworkSigningOptions()
        options.codeDirectoryIdentifier = codeDirectoryIdentifier
        options.codeDirectoryHashingMode = .compatible

        _ = try RorkSigner.signFrameworkWithCredential(
            at: frameworkURL,
            provisioningProfileData: profile,
            credentialData: credential,
            password: password,
            options: options
        )

        teamIdOut?.pointee = duplicateCString(teamIdentifier)
        return 0
    } catch {
        errorMessageOut?.pointee = duplicateCString(String(describing: error))
        return 2
    }
}

@_cdecl("runtime_loader_ios_free_string")
public func runtimeLoaderIosFreeString(_ value: UnsafeMutablePointer<CChar>?) {
    free(value)
}
