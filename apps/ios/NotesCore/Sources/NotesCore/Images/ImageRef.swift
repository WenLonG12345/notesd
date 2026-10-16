import Foundation

/// An image the apps added to a note (architecture §7.6), as the raw inline
/// run holding it: `![alt](notesd:image/<sha256 hex>?w=<px>&h=<px>)`. The web
/// app parses the same form in src/images/ref.ts.
public struct ImageRef: Hashable, Sendable {
    public var id: String
    public var width: Int
    public var height: Int

    public init(id: String, width: Int, height: Int) {
        self.id = id
        self.width = width
        self.height = height
    }

    /// The Markdown for this image; `alt` is escaped so it stays one plain run.
    public func markdown(alt: String = "") -> String {
        let collapsed = alt.replacing(/\s+/, with: " ")
        let escaped = collapsed.replacing(/[\\\[\]*_`~<>!#&]/) { "\\" + $0.output }
        return "![\(escaped)](notesd:image/\(id)?w=\(width)&h=\(height))"
    }

    nonisolated(unsafe) private static let source =
        /^!\[(.*)\]\(notesd:image\/([0-9a-f]{64})\?w=([1-9]\d{0,5})&h=([1-9]\d{0,5})\)$/.dotMatchesNewlines()

    /// An image reference's destination in Markdown, from its "](": the id is the capture.
    nonisolated(unsafe) static let reference = /\]\(notesd:image\/([0-9a-f]{64})\?w=[1-9]\d{0,5}&h=[1-9]\d{0,5}\)/

    /// The image in a raw inline run's `source`; nil for any other run (code, other images).
    public static func parse(_ source: String) -> ImageRef? {
        guard let m = source.wholeMatch(of: Self.source), let w = Int(m.3), let h = Int(m.4) else { return nil }
        return ImageRef(id: String(m.2), width: w, height: h)
    }

    /// The alt text, unescaped, for describing the image to VoiceOver.
    public static func alt(_ source: String) -> String {
        guard let m = source.wholeMatch(of: Self.source) else { return "" }
        return String(m.1).replacing(/\\(.)/.dotMatchesNewlines()) { String($0.1) }
    }

    /// The images `markdown` shows, each once: what a locked note's save
    /// lists, since the server can't read them from the ciphertext. One
    /// written inside code counts too, which only keeps that image longer.
    public static func ids(_ markdown: String) -> [String] {
        var seen = Set<String>()
        return markdown.matches(of: reference).map { String($0.1) }.filter { seen.insert($0).inserted }
    }

    public static func sha256(_ bytes: Data) -> String { sha256Hex(bytes) }
}
