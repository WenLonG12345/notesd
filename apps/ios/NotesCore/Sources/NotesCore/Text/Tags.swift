// #tags (architecture §7.5), ported from the server (internal/search/tags.go)
// via Android (core/text/Tags.kt), and pinned by shared/markdown-fixtures/tags.json.
// Keep them in step.

/// A tag in a note. `key` identifies it: `name` lowercased.
public struct Tag: Hashable, Sendable {
    public var key: String
    public var name: String

    public init(key: String, name: String) {
        self.key = key
        self.name = name
    }
}

/// A tag in a line of text; `start` and `end` are UTF-16 offsets covering the # marks.
public struct TagMatch: Hashable, Sendable {
    public var name: String
    public var start: Int
    public var end: Int
}

/// A note's tags, first spelling of each key, in order of appearance.
public func tags(title: String, markdown: String) -> [Tag] {
    var out: [Tag] = []
    var seen = Set<[Unicode.Scalar]>()
    func scan(_ line: Scalars) {
        for match in scanTags(line) {
            let key = match.name.lowercased()
            if seen.insert(key.scalars).inserted { out.append(Tag(key: key, name: match.name)) }
        }
    }
    scan(title.scalars)
    eachLine(markdown) { text, code in
        if !code { scan(inline(text, opaque: true)) }
    }
    return out
}

/// The key for a tag name given with or without its # marks, or "".
public func tagKey(_ name: String) -> String {
    var s = name.scalars
    if s.first == "#" { s.removeFirst() }
    if s.last == "#" { s.removeLast() }
    return normalize(s).lowercased()
}

/// How to write a tag in a note: closed with # when it needs it (spaces, digits only, punctuation).
public func tagText(_ name: String) -> String {
    let open = "#" + name
    let matches = scanTags(open)
    if matches.count == 1, matches[0].name.same(as: name), matches[0].end == open.utf16Count { return open }
    return open + "#"
}

/// The tags in one line of visible text, where U+FFFC stands for code, links and anything else tags can't be part of.
public func scanTags(_ line: String) -> [TagMatch] {
    scanTags(line.scalars)
}

func scanTags(_ r: Scalars) -> [TagMatch] {
    var at = [Int](repeating: 0, count: r.count + 1)
    for k in r.indices { at[k + 1] = at[k] + UTF16.width(r[k]) }

    var out: [TagMatch] = []
    var i = 0
    while i < r.count {
        if r[i] != "#" || (i > 0 && !opensTag(r[i - 1])) {
            i += 1
            continue
        }
        let next = r[safe: i + 1]
        if next == nil || isSpace(next!) || next == "#" || next == objectReplacement {
            i += 1
            continue
        }
        let close = closingHash(r, open: i)
        if close > 0 {
            let name = normalize(Array(r[(i + 1)..<close]))
            if !name.isEmpty { out.append(TagMatch(name: name, start: at[i], end: at[close + 1])) }
            i = close + 1
            continue
        }
        var end = i + 1
        while end < r.count && inWord(r[end]) { end += 1 }
        let name = normalize(Array(r[(i + 1)..<end]))
        if !name.isEmpty && !numeric(name) { out.append(TagMatch(name: name, start: at[i], end: at[end])) }
        i = end
    }
    return out
}

/// The # closing a multi-word tag opened at r[open]: the next # on the line, if it follows a non-space and ends a word.
private func closingHash(_ r: Scalars, open: Int) -> Int {
    var j = open + 2
    while j < r.count {
        if r[j] == objectReplacement { return 0 }
        if r[j] == "#" {
            if isSpace(r[j - 1]) || (j + 1 < r.count && inWord(r[j + 1])) { return 0 }
            return j
        }
        j += 1
    }
    return 0
}

private func opensTag(_ c: Unicode.Scalar) -> Bool {
    isSpace(c) || c == "(" || c == "[" || c == "{"
}

/// Anything but spaces, U+FFFC and punctuation, except - _ and the / between nested tags.
private func inWord(_ c: Unicode.Scalar) -> Bool {
    if c == "-" || c == "_" || c == "/" { return true }
    if c == objectReplacement || isSpace(c) { return false }
    if c.value < 0x80 { return isAsciiLetterOrDigit(c) }
    switch c.properties.generalCategory {
    case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation, .initialPunctuation,
        .finalPunctuation, .otherPunctuation:
        return false
    default:
        return true
    }
}

// JavaScript's /\s/u has no characters outside the BMP.
private func isSpace(_ c: Unicode.Scalar) -> Bool { isJsSpace(c) }

/// Trims each /-separated level, collapses inner spaces, drops empty levels.
private func normalize(_ s: Scalars) -> String {
    s.split(separator: "/", omittingEmptySubsequences: false)
        .map { level in level.split(whereSeparator: isJsSpace).map { $0.string }.joined(separator: " ") }
        .filter { !$0.isEmpty }
        .joined(separator: "/")
}

/// Names like 1 or 2024/10 are tags only when closed with #, so "#1 priority" stays text.
private func numeric(_ name: String) -> Bool {
    name.unicodeScalars.allSatisfy { ($0 >= "0" && $0 <= "9") || $0 == "-" || $0 == "_" || $0 == "/" }
}

extension ArraySlice where Element == Unicode.Scalar {
    var string: String { String(scalars: self) }
}
