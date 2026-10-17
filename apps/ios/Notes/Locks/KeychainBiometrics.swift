import Foundation
import LocalAuthentication
import NotesCore
import Security
import Synchronization

/// Face ID or Touch ID, as this iPhone has it: what the app calls it, and its symbol.
nonisolated struct BiometryKind: Sendable {
    let name: String
    let symbol: String

    static let faceID = BiometryKind(name: "Face ID", symbol: "faceid")
    static let touchID = BiometryKind(name: "Touch ID", symbol: "touchid")

    /// What this device has enrolled; Face ID when it can't tell.
    static func current() -> BiometryKind {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return context.biometryType == .touchID ? .touchID : .faceID
    }
}

/// The locked-notes key as a Keychain item that only Face ID (or Touch ID)
/// reads, every time, on this device only. iOS makes it unreadable when the
/// enrolled faces or fingers change (`.biometryCurrentSet`), as Android's
/// Keystore key is destroyed then.
nonisolated final class KeychainBiometrics: Biometrics {
    private let service: String
    private let kind: BiometryKind

    init(service: String, kind: BiometryKind) {
        self.service = service
        self.kind = kind
    }

    func available() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    func seal(_ key: Data) async -> BiometricResult {
        let context = LAContext()
        context.localizedCancelTitle = "Not Now"
        if let refused = await evaluate(context, reason: "Turn on \(kind.name) unlock for locked notes.") {
            return refused
        }
        guard
            let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .biometryCurrentSet, nil)
        else { return .failed }
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = key
        item[kSecAttrAccessControl as String] = access
        item[kSecUseAuthenticationContext as String] = context
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess ? .ok(Data()) : .failed
    }

    func open() async -> BiometricResult {
        // Gone (Face ID enrolled again, or never kept): say so without asking for a face first.
        let probe = LAContext()
        probe.interactionNotAllowed = true
        var check = query
        check[kSecUseAuthenticationContext as String] = probe
        let found = SecItemCopyMatching(check as CFDictionary, nil)
        guard found == errSecInteractionNotAllowed || found == errSecSuccess else { return .invalidated }

        let context = LAContext()
        context.localizedFallbackTitle = "Use Password"
        if let refused = await evaluate(context, reason: "Open your locked notes.") { return refused }
        // Already confirmed: the read doesn't ask again.
        context.interactionNotAllowed = true
        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        read[kSecUseAuthenticationContext as String] = context
        var result: AnyObject?
        switch SecItemCopyMatching(read as CFDictionary, &result) {
        case errSecSuccess:
            guard let key = result as? Data else { return .failed }
            return .ok(key)
        case errSecItemNotFound, errSecAuthFailed: return .invalidated
        default: return .failed
        }
    }

    func forget() {
        SecItemDelete(query as CFDictionary)
    }

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: "key",
        ]
    }

    /// Nil once Face ID matched; otherwise what stopped it. Only the user's own
    /// dismissal is `cancelled`, so a lockout or an interruption isn't taken for a choice.
    private func evaluate(_ context: LAContext, reason: String) async -> BiometricResult? {
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason)
                ? nil : .failed
        } catch let error as LAError where error.code == .userCancel || error.code == .userFallback {
            return .cancelled
        } catch {
            return .failed
        }
    }
}

/// Face ID for UI tests: `--fake-biometrics=match` matches at once,
/// `--fake-biometrics=cancel` is dismissed. Without the flag it isn't available,
/// so flows that don't test it aren't interrupted.
nonisolated final class FakeBiometrics: Biometrics {
    private let mode: String?
    private let kept = Mutex<Data?>(nil)

    init(arguments: [String]) {
        mode = arguments.first { $0.hasPrefix("--fake-biometrics=") }?.split(separator: "=").last.map(String.init)
    }

    func available() -> Bool { mode != nil }

    func seal(_ key: Data) async -> BiometricResult {
        guard mode == "match" else { return .cancelled }
        kept.withLock { $0 = key }
        return .ok(Data())
    }

    func open() async -> BiometricResult {
        guard mode == "match" else { return .cancelled }
        return kept.withLock { $0 }.map { .ok($0) } ?? .invalidated
    }

    func forget() { kept.withLock { $0 = nil } }
}
