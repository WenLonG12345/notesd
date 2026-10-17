import CommonCrypto
import CryptoKit
import Foundation

/// The body failed to open: wrong key, tampering, or not an envelope at all.
public struct LockedError: Error, Equatable {
    public var message: String

    public init(_ message: String = "Could not open the locked note") { self.message = message }
}

/// Which secret a keyring entry is wrapped under (§10.1).
public enum WrapKind: String, Sendable, CaseIterable {
    case password, recovery, passkey
}

/// Locked notes crypto (architecture §10.1), pinned by
/// shared/crypto-fixtures/locked.json. The web app (src/locks/crypto.ts) and
/// Android (LockedNotes.kt) implement the same rules; keep them in step.
///
/// A locked note's content is `prefix` + base64url(nonce ‖ ciphertext ‖ tag)
/// of its Markdown body under the 32-byte locked-notes key. The keyring holds
/// that key wrapped the same way under keys derived from the lock password,
/// the recovery code or a passkey.
public enum LockedNotes {
    public static let prefix = "notesd:locked:v1:"
    public static let passwordIterations = 600_000
    public static let recoveryIterations = 10_000
    public static let keyBytes = 32

    private static let bodyAAD = "notesd:locked:v1"
    private static let imageAAD = "notesd:image:v1"
    private static let nonceBytes = 12
    private static let tagBytes = 16

    /// Whether `content` is an encrypted body. Byte-exact, as on the server.
    public static func isLocked(_ content: String) -> Bool {
        content.utf8.starts(with: prefix.utf8)
    }

    public static func newKey() -> Data { randomBytes(keyBytes) }

    public static func randomBytes(_ n: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: n)
        let status = SecRandomCopyBytes(kSecRandomDefault, n, &bytes)
        precondition(status == errSecSuccess, "no system randomness")
        return Data(bytes)
    }

    /// Unpadded base64url.
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString().replacing("+", with: "-").replacing("/", with: "_").replacing("=", with: "")
    }

    /// Strict unpadded base64url: anything else is refused.
    public static func decode(_ s: String) throws(LockedError) -> Data {
        guard s.unicodeScalars.allSatisfy(isBase64URL), s.count % 4 != 1 else { throw LockedError("Not base64url") }
        var b64 = s.replacing("-", with: "+").replacing("_", with: "/")
        b64 += String(repeating: "=", count: (4 - b64.count % 4) % 4)
        guard let data = Data(base64Encoded: b64) else { throw LockedError("Not base64url") }
        return data
    }

    private static func isBase64URL(_ c: Unicode.Scalar) -> Bool {
        switch c {
        case "A"..."Z", "a"..."z", "0"..."9", "-", "_": true
        default: false
        }
    }

    /// Encrypts a note's Markdown body into its stored content.
    public static func encrypt(_ key: Data, _ markdown: String) -> String {
        prefix + encode(seal(key, Data(markdown.utf8), bodyAAD))
    }

    /// Decrypts stored content; throws unless it is an envelope `key` sealed.
    public static func decrypt(_ key: Data, _ content: String) throws(LockedError) -> String {
        guard isLocked(content) else { throw LockedError("Not a locked note") }
        let plain = try open(key, decode(String(content.dropFirst(prefix.count))), bodyAAD)
        guard let text = String(data: plain, encoding: .utf8) else { throw LockedError("Not UTF-8") }
        return text
    }

    /// Encrypts a locked note's image file (§7.6): nonce ‖ ciphertext ‖ tag, binary.
    public static func encryptImage(_ key: Data, _ file: Data) -> Data { seal(key, file, imageAAD) }

    /// Decrypts a locked note's image; throws unless `key` sealed it as an image.
    public static func decryptImage(_ key: Data, _ sealed: Data) throws(LockedError) -> Data {
        try open(key, sealed, imageAAD)
    }

    /// PBKDF2-HMAC-SHA256 of the NFC-normalized password.
    public static func passwordKek(_ password: String, salt: Data, iterations: Int) -> Data {
        let normalized = Array(password.precomposedStringWithCanonicalMapping.utf8)
        var out = [UInt8](repeating: 0, count: keyBytes)
        let status = normalized.withUnsafeBufferPointer { pw in
            salt.withUnsafeBytes { s in
                pw.withMemoryRebound(to: Int8.self) { pw8 in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2), pw8.baseAddress, pw8.count,
                        s.bindMemory(to: UInt8.self).baseAddress, s.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations), &out, out.count)
                }
            }
        }
        precondition(status == kCCSuccess, "PBKDF2 failed")
        return Data(out)
    }

    /// HKDF-SHA256 (RFC 5869) of a passkey's PRF output.
    public static func passkeyKek(prf: Data, salt: Data) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: prf), salt: salt, info: Data("notesd:passkey:v1".utf8),
            outputByteCount: keyBytes)
        return key.withUnsafeBytes { Data($0) }
    }

    public static func wrap(_ kek: Data, _ key: Data, kind: WrapKind) -> String {
        encode(seal(kek, key, "notesd:lnk:v1:\(kind.rawValue)"))
    }

    /// The locked-notes key; an error means the password (or code) was wrong.
    public static func unwrap(_ kek: Data, _ wrapped: String, kind: WrapKind) throws(LockedError) -> Data {
        let key = try open(kek, decode(wrapped), "notesd:lnk:v1:\(kind.rawValue)")
        guard key.count == keyBytes else { throw LockedError("Bad key length") }
        return key
    }

    /// HMAC(key, "notesd:verifier:v1"): proves to the server that this client holds the key, without revealing it.
    public static func verifier(_ key: Data) -> String {
        let mac = HMAC<SHA256>.authenticationCode(
            for: Data("notesd:verifier:v1".utf8), using: SymmetricKey(data: key))
        return encode(Data(mac))
    }

    private static func seal(_ key: Data, _ plain: Data, _ aad: String) -> Data {
        // A failure here means a key of the wrong length: a programming error.
        let box = try! AES.GCM.seal(plain, using: SymmetricKey(data: key), authenticating: Data(aad.utf8))
        return box.combined!
    }

    private static func open(_ key: Data, _ sealed: Data, _ aad: String) throws(LockedError) -> Data {
        guard sealed.count >= nonceBytes + tagBytes, [16, 24, 32].contains(key.count) else {
            throw LockedError("Too short")
        }
        do {
            let box = try AES.GCM.SealedBox(combined: sealed)
            return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: Data(aad.utf8))
        } catch {
            throw LockedError()
        }
    }
}
