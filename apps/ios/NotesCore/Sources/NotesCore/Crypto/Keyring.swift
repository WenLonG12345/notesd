import Foundation

/// Keyring logic shared by every way of unlocking (§10.1), apart from storage
/// and the network. The locked-notes key never leaves memory unwrapped.
public enum Keyring {
    /// A brand-new key and its password entry, to upload with `verifier`.
    public struct Created: Sendable {
        public var key: Data
        public var entry: LockKey
        public var verifier: String
    }

    public static func create(_ password: String, iterations: Int = LockedNotes.passwordIterations) -> Created {
        let key = LockedNotes.newKey()
        return Created(
            key: key, entry: passwordEntry(key, password, iterations: iterations), verifier: LockedNotes.verifier(key))
    }

    /// The password entry for `key`: a new salt, wrapped under `password`.
    public static func passwordEntry(_ key: Data, _ password: String, iterations: Int = LockedNotes.passwordIterations)
        -> LockKey
    {
        let salt = LockedNotes.randomBytes(16)
        let kek = LockedNotes.passwordKek(password, salt: salt, iterations: iterations)
        return LockKey(
            id: WrapKind.password.rawValue, kind: WrapKind.password.rawValue, salt: LockedNotes.encode(salt),
            iterations: iterations, wrapped: LockedNotes.wrap(kek, key, kind: .password))
    }

    /// The key, from the password entry. An error means a wrong password (or no lock password yet).
    public static func unlock(_ keys: [LockKey], _ password: String) throws(LockedError) -> Data {
        guard let entry = keys.first(where: { $0.kind == WrapKind.password.rawValue }) else {
            throw LockedError("No lock password set")
        }
        let kek = LockedNotes.passwordKek(
            password, salt: try LockedNotes.decode(entry.salt), iterations: entry.iterations)
        return try LockedNotes.unwrap(kek, entry.wrapped, kind: .password)
    }
}
