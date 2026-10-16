// Text work in this package is done on Unicode scalars, not Characters: Swift
// compares and indexes Strings by grapheme cluster and canonical equivalence,
// while the server (Go), the web (JS) and Android (Kotlin) work on code units
// and compare exactly. Every special character the extractors look for is
// ASCII, so scalar indices behave like their UTF-16 indices.

typealias Scalars = [Unicode.Scalar]

extension String {
    var scalars: Scalars { Array(unicodeScalars) }

    init(scalars: some Sequence<Unicode.Scalar>) {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        self = String(view)
    }

    /// Exact equality, code point for code point (`==` treats "é" and "e\u{301}" as equal).
    func same(as other: String) -> Bool {
        unicodeScalars.elementsEqual(other.unicodeScalars)
    }

    /// The UTF-16 length, the unit NSString, the text view and Kotlin offsets use.
    var utf16Count: Int { utf16.count }
}

extension Array where Element == Unicode.Scalar {
    func trimStart(_ drop: (Unicode.Scalar) -> Bool) -> Scalars {
        Array(self.drop(while: drop))
    }

    func trimEnd(_ drop: (Unicode.Scalar) -> Bool) -> Scalars {
        var end = count
        while end > 0 && drop(self[end - 1]) { end -= 1 }
        return Array(self[..<end])
    }

    func trim(_ drop: (Unicode.Scalar) -> Bool) -> Scalars {
        trimStart(drop).trimEnd(drop)
    }

    func trimStart(_ set: Scalars) -> Scalars { trimStart { set.contains($0) } }

    func trimEnd(_ set: Scalars) -> Scalars { trimEnd { set.contains($0) } }

    /// The first index of `needle` at or after `from`, as Kotlin's indexOf.
    func index(of needle: Scalars, from: Int = 0) -> Int? {
        guard !needle.isEmpty else { return from <= count ? from : nil }
        var i = Swift.max(from, 0)
        while i + needle.count <= count {
            if self[i] == needle[0] && self[i..<(i + needle.count)].elementsEqual(needle) { return i }
            i += 1
        }
        return nil
    }

    /// Splits on "\n", keeping empty pieces.
    func lines() -> [Scalars] {
        split(separator: "\n", omittingEmptySubsequences: false).map(Array.init)
    }

    var string: String { String(scalars: self) }
}

/// "\r\n" and a lone "\r" become "\n".
func normalizeNewlines(_ s: Scalars) -> Scalars {
    guard s.contains("\r") else { return s }
    var out: Scalars = []
    out.reserveCapacity(s.count)
    var i = 0
    while i < s.count {
        if s[i] == "\r" {
            out.append("\n")
            if i + 1 < s.count && s[i + 1] == "\n" { i += 1 }
        } else {
            out.append(s[i])
        }
        i += 1
    }
    return out
}
