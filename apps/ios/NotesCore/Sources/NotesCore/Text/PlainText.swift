// Markdown → plain text, ported from the server (internal/search/extract.go)
// via the web app (apps/web/src/notes/plaintext.ts) and Android
// (core/text/PlainText.kt), and pinned by shared/markdown-fixtures/plaintext.json.
// Keep them in step.

/// The text indexed for search: one line per block, Markdown syntax removed.
public func plainText(_ markdown: String) -> String {
    var out: [Scalars] = []
    eachLine(markdown) { text, code in
        out.append(code ? text.trimEnd([" ", "\t"]) : inline(text).trim(isJsSpace))
    }
    return collapseBlankLines(out)
}

/// Calls `fn` for every line: code lines inside fences as they are, other lines
/// with block markers removed and inline syntax left in. Fence delimiters are
/// skipped and thematic breaks become "".
func eachLine(_ markdown: String, _ fn: (_ text: Scalars, _ code: Bool) -> Void) {
    let md = normalizeNewlines(markdown.scalars)
    var fence: Scalars = []  // the opening fence while inside a fenced code block
    for line in md.lines() {
        let trimmed = line.trimStart([" ", "\t"])
        if !fence.isEmpty {
            if trimmed.starts(with: fence) && trimmed.trim({ $0 == fence[0] }).isEmpty {
                fence = []
                continue
            }
            fn(line, true)
            continue
        }
        let opener = fenceOpener(trimmed)
        if !opener.isEmpty {
            fence = opener
            continue
        }
        if isRule(trimmed) {
            fn([], false)
            continue
        }
        fn(stripBlockMarkers(trimmed), false)
    }
}

private let previewChars = 160

/// The list preview the server sends (notes.preview): one line per block, ≤160 code points.
public func preview(_ markdown: String) -> String {
    previewOf(plainText(markdown))
}

/// `preview(_:)` for text already extracted: one block per line.
public func previewOf(_ text: String) -> String {
    let lines = text.scalars.lines()
        .map { line in line.split(whereSeparator: isJsSpace).map { $0.string }.joined(separator: " ") }
        .filter { !$0.isEmpty }
    let joined = lines.joined(separator: "\n").scalars
    if joined.count <= previewChars { return joined.string }
    return Array(joined[..<previewChars]).trim(isJsSpace).string + "…"
}

private func fenceOpener(_ s: Scalars) -> Scalars {
    for c: Unicode.Scalar in ["`", "~"] where s.starts(with: [c, c, c]) {
        return Scalars(repeating: c, count: runLength(s, 0, c))
    }
    return []
}

/// Thematic breaks and setext underlines: only -, *, _ or = (≥3, or ≥2 for =), ignoring spaces.
private func isRule(_ s: Scalars) -> Bool {
    let compact = s.filter { $0 != " " }
    if compact.count < 2 { return false }
    let c = compact[0]
    if c != "-" && c != "*" && c != "_" && c != "=" { return false }
    if !compact.allSatisfy({ $0 == c }) { return false }
    return c == "=" || compact.count >= 3
}

private let spaceOrTab: Scalars = [" ", "\t"]

private func stripBlockMarkers(_ input: Scalars) -> Scalars {
    var s = input
    while true {
        let before = s
        if s.first == ">" {
            s = Array(s.dropFirst()).trimStart(spaceOrTab)
        } else if s.count >= 2 && "-*+".unicodeScalars.contains(s[0]) && (s[1] == " " || s[1] == "\t") {
            s = Array(s.dropFirst(2)).trimStart(spaceOrTab)
        } else if s == ["-"] || s == ["*"] || s == ["+"] {
            s = []
        } else {
            var n = orderedMarker(s)
            if n == 0 { n = taskBox(s) }
            if n > 0 {
                s = Array(s.dropFirst(n)).trimStart(spaceOrTab)
            } else {
                n = headingMarker(s)
                if n > 0 { s = trimClosingHashes(Array(s.dropFirst(n)).trimStart(spaceOrTab)) }
            }
        }
        if s == before { return s }
    }
}

private func isDigit(_ c: Unicode.Scalar) -> Bool { c >= "0" && c <= "9" }

private func orderedMarker(_ s: Scalars) -> Int {
    var i = 0
    while i < s.count && i < 9 && isDigit(s[i]) { i += 1 }
    if i == 0 || i >= s.count || (s[i] != "." && s[i] != ")") { return 0 }
    if i + 1 == s.count { return i + 1 }
    if s[i + 1] == " " || s[i + 1] == "\t" { return i + 2 }
    return 0
}

private func taskBox(_ s: Scalars) -> Int {
    if s.count >= 3 && s[0] == "[" && s[2] == "]" && (s[1] == " " || s[1] == "x" || s[1] == "X") {
        if s.count == 3 || s[3] == " " || s[3] == "\t" { return 3 }
    }
    return 0
}

private func headingMarker(_ s: Scalars) -> Int {
    var n = 0
    while n < s.count && s[n] == "#" { n += 1 }
    if n == 0 || n > 6 { return 0 }
    if n == s.count || s[n] == " " || s[n] == "\t" { return n }
    return 0
}

private func trimClosingHashes(_ s: Scalars) -> Scalars {
    let t = s.trimEnd(spaceOrTab)
    let u = t.trimEnd(["#"])
    if u == t { return s }
    if u.isEmpty || u.last == " " || u.last == "\t" { return u.trimEnd(spaceOrTab) }
    return s
}

/// U+FFFC: stands for code, links and anything else scanners mustn't look inside.
public let objectReplacement: Unicode.Scalar = "\u{FFFC}"

/// Removes inline syntax. With `opaque`, code spans, links, images and
/// autolinks become a single U+FFFC instead of their text.
func inline(_ s: Scalars, opaque: Bool = false) -> Scalars {
    var out: Scalars = []
    out.reserveCapacity(s.count)
    var i = 0
    while i < s.count {
        let c = s[i]
        if c == "\\" && i + 1 < s.count && isAsciiPunct(s[i + 1]) {
            out.append(s[i + 1])
            i += 2
        } else if c == "`" {
            let n = runLength(s, i, "`")
            let ticks = Array(s[i..<(i + n)])
            if let end = s.index(of: ticks, from: i + n) {
                if opaque {
                    out.append(objectReplacement)
                } else {
                    out += Array(s[(i + n)..<end]).trim(isJsSpace)
                }
                i = end + n
            } else {
                out += ticks
                i += n
            }
        } else if c == "!" && i + 1 < s.count && s[i + 1] == "[" {
            if let (text, length) = link(s, from: i + 1) {
                if opaque { out.append(objectReplacement) } else { out += inline(text) }
                i += 1 + length
            } else {
                out.append(c)
                i += 1
            }
        } else if c == "[" {
            if let (text, length) = link(s, from: i) {
                if opaque { out.append(objectReplacement) } else { out += inline(text) }
                i += length
            } else {
                out.append(c)
                i += 1
            }
        } else if c == "<" {
            if let end = s.index(of: [">"], from: i), end - i > 1, isAutolink(Array(s[(i + 1)..<end])) {
                if opaque { out.append(objectReplacement) } else { out += s[(i + 1)..<end] }
                i = end + 1
            } else {
                out.append(c)
                i += 1
            }
        } else if c == "*" || c == "_" || c == "~" {
            let n = runLength(s, i, c)
            if (c == "~" && n < 2) || !isDelimiter(s, i, n, c) { out += s[i..<(i + n)] }
            i += n
        } else {
            out.append(c)
            i += 1
        }
    }
    return out
}

/// "[text](destination)" starting at s[start]: the text and how many scalars it spans.
private func link(_ s: Scalars, from start: Int) -> (Scalars, Int)? {
    var depth = 0
    var i = start
    while i < s.count {
        let c = s[i]
        if c == "\\" {
            i += 1
        } else if c == "[" {
            depth += 1
        } else if c == "]" {
            depth -= 1
            if depth != 0 {
                i += 1
                continue
            }
            if i + 1 >= s.count || s[i + 1] != "(" { return nil }
            var parens = 0
            var j = i + 1
            while j < s.count {
                let d = s[j]
                if d == "\\" {
                    j += 1
                } else if d == "(" {
                    parens += 1
                } else if d == ")" {
                    parens -= 1
                    if parens == 0 { return (Array(s[(start + 1)..<i]), j + 1 - start) }
                }
                j += 1
            }
            return nil
        }
        i += 1
    }
    return nil
}

private func isAutolink(_ s: Scalars) -> Bool {
    if s.contains(where: { $0 == " " || $0 == "\t" || $0 == "<" }) { return false }
    if let colon = s.firstIndex(of: ":"), colon >= 2 {
        return s[..<colon].allSatisfy { isAsciiLetterOrDigit($0) || $0 == "+" || $0 == "." || $0 == "-" }
    }
    guard let at = s.firstIndex(of: "@") else { return false }
    return at > 0 && s[at...].contains(".")
}

/// Emphasis run or literal character, approximating CommonMark flanking (snake_case is literal).
private func isDelimiter(_ s: Scalars, _ i: Int, _ n: Int, _ c: Unicode.Scalar) -> Bool {
    let before: Unicode.Scalar = i == 0 ? " " : s[i - 1]
    let after: Unicode.Scalar = i + n >= s.count ? " " : s[i + n]
    if isJsSpace(before) && isJsSpace(after) { return false }
    if c == "_" && isAlnum(before) && isAlnum(after) { return false }
    return true
}

/// Java's Character.isLetter or a decimal digit.
private func isAlnum(_ c: Unicode.Scalar) -> Bool {
    switch c.properties.generalCategory {
    case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: true
    default: false
    }
}

func isAsciiLetterOrDigit(_ c: Unicode.Scalar) -> Bool {
    (c >= "a" && c <= "z") || (c >= "A" && c <= "Z") || (c >= "0" && c <= "9")
}

func runLength(_ s: Scalars, _ i: Int, _ c: Unicode.Scalar) -> Int {
    var n = 0
    while i + n < s.count && s[i + n] == c { n += 1 }
    return n
}

private func isAsciiPunct(_ c: Unicode.Scalar) -> Bool {
    "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".unicodeScalars.contains(c)
}

private func collapseBlankLines(_ lines: [Scalars]) -> String {
    var out: Scalars = []
    var blank = true  // suppress leading blank lines
    for line in lines {
        if line.isEmpty {
            blank = true
            continue
        }
        if !out.isEmpty { out += blank ? ["\n", "\n"] : ["\n"] }
        out += line
        blank = false
    }
    return out.string
}
