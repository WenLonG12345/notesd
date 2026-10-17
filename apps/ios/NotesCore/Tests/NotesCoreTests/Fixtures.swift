import Foundation

/// The cross-platform fixtures in shared/markdown-fixtures.
enum Fixtures {
    struct Case: Decodable {
        var name: String
        var markdown: String
        var text: String?
        var title: String?
        var tags: [String]?
    }

    private struct Suite: Decodable {
        var cases: [Case]
    }

    static let roundtrip = load("roundtrip.json")
    static let plaintext = load("plaintext.json")
    static let tags = load("tags.json")

    /// The repository's shared/ directory, found from this file's path.
    static let sharedDir: URL = {
        var url = URL(fileURLWithPath: #filePath)
        // Fixtures.swift → NotesCoreTests → Tests → NotesCore → ios → apps → repository root
        for _ in 0..<6 { url.deleteLastPathComponent() }
        return url.appending(path: "shared")
    }()

    private static func load(_ name: String) -> [Case] {
        let url = sharedDir.appending(path: "markdown-fixtures/\(name)")
        do {
            return try JSONDecoder().decode(Suite.self, from: Data(contentsOf: url)).cases
        } catch {
            fatalError("can't read \(url.path): \(error)")
        }
    }
}

extension String {
    var quoted: String {
        "\"" + replacing("\\", with: "\\\\").replacing("\n", with: "\\n") + "\""
    }
}

/// A seeded generator, so random tests are repeatable (SplitMix64).
struct SeededRandom: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }

    mutating func int(_ bound: Int) -> Int { Int.random(in: 0..<bound, using: &self) }

    mutating func bool() -> Bool { Bool.random(using: &self) }

    mutating func pick<T>(_ items: [T]) -> T { items[int(items.count)] }
}
