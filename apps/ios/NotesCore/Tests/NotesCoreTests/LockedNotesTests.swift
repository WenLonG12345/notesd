import Foundation
import Testing

@testable import NotesCore

/// shared/crypto-fixtures/locked.json, the vectors the web app and Android check too.
struct LockedNotesTests {
    struct Suite: Decodable {
        struct Kdf: Decodable {
            var name, password, salt, key: String
            var iterations: Int
        }
        struct Hkdf: Decodable {
            var name, prf, salt, key: String
        }
        struct Wrap: Decodable {
            var name, kind, kek, key, wrapped: String
        }
        struct Envelope: Decodable {
            var name, plaintext, key, envelope: String
        }
        struct Invalid: Decodable {
            var name, envelope, key: String
        }
        struct Image: Decodable {
            var name, key, sealed: String
            var plain: String?
        }
        struct Verifier: Decodable {
            var name, key, verifier: String
        }
        var kdf: [Kdf]
        var hkdf: [Hkdf]
        var wraps: [Wrap]
        var envelopes: [Envelope]
        var invalid: [Invalid]
        var images: [Image]
        var verifiers: [Verifier]
    }

    static let fixtures: Suite = {
        let data = try! Data(contentsOf: Fixtures.sharedDir.appending(path: "crypto-fixtures/locked.json"))
        return try! JSONDecoder().decode(Suite.self, from: data)
    }()

    private var f: Suite { Self.fixtures }
    private func b(_ s: String) -> Data { try! LockedNotes.decode(s) }

    @Test func kdf() {
        for v in f.kdf {
            #expect(
                LockedNotes.encode(LockedNotes.passwordKek(v.password, salt: b(v.salt), iterations: v.iterations))
                    == v.key, "\(v.name)")
        }
    }

    @Test func hkdf() {
        for v in f.hkdf {
            #expect(LockedNotes.encode(LockedNotes.passkeyKek(prf: b(v.prf), salt: b(v.salt))) == v.key, "\(v.name)")
        }
    }

    @Test func unwrap() throws {
        for v in f.wraps {
            let kind = try #require(WrapKind(rawValue: v.kind))
            #expect(try LockedNotes.encode(LockedNotes.unwrap(b(v.kek), v.wrapped, kind: kind)) == v.key, "\(v.name)")
            let other: WrapKind = kind == .password ? .recovery : .password
            #expect(throws: LockedError.self) { try LockedNotes.unwrap(b(v.kek), v.wrapped, kind: other) }
            let rewrapped = LockedNotes.wrap(b(v.kek), b(v.key), kind: kind)
            #expect(try LockedNotes.encode(LockedNotes.unwrap(b(v.kek), rewrapped, kind: kind)) == v.key)
        }
    }

    @Test func envelopes() throws {
        for v in f.envelopes {
            #expect(LockedNotes.isLocked(v.envelope), "\(v.name)")
            #expect(try LockedNotes.decrypt(b(v.key), v.envelope) == v.plaintext, "\(v.name)")
            let again = LockedNotes.encrypt(b(v.key), v.plaintext)
            #expect(again != v.envelope, "fresh nonce: \(v.name)")
            #expect(try LockedNotes.decrypt(b(v.key), again) == v.plaintext, "\(v.name)")
        }
    }

    @Test func refusesInvalid() {
        for v in f.invalid {
            #expect(throws: LockedError.self, "\(v.name)") { try LockedNotes.decrypt(b(v.key), v.envelope) }
        }
    }

    @Test func images() throws {
        for v in f.images {
            guard let plain = v.plain else {
                #expect(throws: LockedError.self, "\(v.name)") { try LockedNotes.decryptImage(b(v.key), b(v.sealed)) }
                continue
            }
            #expect(try LockedNotes.encode(LockedNotes.decryptImage(b(v.key), b(v.sealed))) == plain, "\(v.name)")
            let again = LockedNotes.encryptImage(b(v.key), b(plain))
            #expect(LockedNotes.encode(again) != v.sealed, "fresh nonce: \(v.name)")
            #expect(try LockedNotes.encode(LockedNotes.decryptImage(b(v.key), again)) == plain, "\(v.name)")
        }
    }

    @Test func verifiers() {
        for v in f.verifiers { #expect(LockedNotes.verifier(b(v.key)) == v.verifier, "\(v.name)") }
    }

    @Test func base64urlIsStrict() {
        for bad in ["a+b", "a/b", "ab==", "a b", "abcde"] {
            #expect(throws: LockedError.self, "\(bad)") { try LockedNotes.decode(bad) }
        }
    }
}
