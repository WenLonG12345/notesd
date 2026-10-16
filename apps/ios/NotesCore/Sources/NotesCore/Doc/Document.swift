/// The editor document, mirroring the web editor's ProseMirror schema node for
/// node so every client parses and serializes the same tree (spec §7.1).
///
/// Invariants (what ProseMirror's schema enforces on the web): `blocks` is never
/// empty; list items and task items start with a paragraph; a blockquote is
/// never empty; inline content is `normalized()`.
public struct Document: Hashable, Sendable {
    public var blocks: [Block]

    public init(_ blocks: [Block]) {
        self.blocks = blocks
    }

    /// The title line, when this is an editor document (`title block+`).
    public var title: String? {
        if case .title(let text) = blocks.first { return text }
        return nil
    }

    /// The body without the title line: what is serialized to `content`.
    public var body: [Block] {
        if case .title = blocks.first { return Array(blocks.dropFirst()) }
        return blocks
    }

    public static let empty = Document([.paragraph([])])

    /// The editor document for a note: its title as the first line, then the body.
    public static func forNote(title: String, body: Document) -> Document {
        Document([.title(foldTitle(title))] + body.body)
    }

    /// Titles from other clients can hold line breaks; the title line can't.
    /// Every run of whitespace holding a "\n" becomes one space, as Java's `\s*\n\s*`.
    public static func foldTitle(_ title: String) -> String {
        let s = Array(title.unicodeScalars)
        var out = String.UnicodeScalarView()
        var i = 0
        while i < s.count {
            guard isJavaSpace(s[i]) else {
                out.append(s[i])
                i += 1
                continue
            }
            var j = i
            var newline = false
            while j < s.count && isJavaSpace(s[j]) {
                if s[j] == "\n" { newline = true }
                j += 1
            }
            if newline { out.append(" ") } else { out.append(contentsOf: s[i..<j]) }
            i = j
        }
        return String(out)
    }

    private static func isJavaSpace(_ c: Unicode.Scalar) -> Bool {
        switch c.value {
        case 0x09...0x0D, 0x20: true
        default: false
        }
    }
}

public indirect enum Block: Hashable, Sendable {
    /// The note's first line, stored as the plain-text `title` field, never in the Markdown.
    case title(String)
    case paragraph([Inline])
    /// Levels 1–3.
    case heading(level: Int, [Inline])
    case bulletList([ListItem])
    case orderedList(start: Int, [ListItem])
    case taskList([TaskItem])
    case blockquote([Block])
    /// Markdown outside the dialect (code, tables, rules…), kept as its exact source.
    case rawBlock(String)

    /// Which case this is, ignoring its contents.
    var kind: Kind {
        switch self {
        case .title: .title
        case .paragraph: .paragraph
        case .heading: .heading
        case .bulletList: .bulletList
        case .orderedList: .orderedList
        case .taskList: .taskList
        case .blockquote: .blockquote
        case .rawBlock: .rawBlock
        }
    }

    enum Kind { case title, paragraph, heading, bulletList, orderedList, taskList, blockquote, rawBlock }
}

public struct ListItem: Hashable, Sendable {
    public var blocks: [Block]

    public init(_ blocks: [Block]) {
        self.blocks = blocks
    }
}

public struct TaskItem: Hashable, Sendable {
    public var checked: Bool
    public var blocks: [Block]

    public init(checked: Bool, _ blocks: [Block]) {
        self.checked = checked
        self.blocks = blocks
    }
}

public enum Inline: Hashable, Sendable {
    case text(String, marks: [Mark] = [])
    case hardBreak(marks: [Mark] = [])
    /// An inline construct outside the dialect (code span, image), kept as its exact source.
    case rawInline(String, marks: [Mark] = [])

    public var marks: [Mark] {
        switch self {
        case .text(_, let marks), .hardBreak(let marks), .rawInline(_, let marks): marks
        }
    }
}

/// Marks, ordered by `rank` the way the web schema orders them: link outermost.
public enum Mark: Hashable, Sendable {
    case link(href: String, title: String? = nil)
    case bold
    case italic
    case strike

    public var rank: Int {
        switch self {
        case .link: 0
        case .bold: 1
        case .italic: 2
        case .strike: 3
        }
    }

    /// A mark set in canonical order, one mark per type (a new link replaces the old).
    public static func setOf(_ marks: some Sequence<Mark>) -> [Mark] {
        var byRank: [Int: Mark] = [:]
        for mark in marks { byRank[mark.rank] = mark }
        return byRank.keys.sorted().map { byRank[$0]! }
    }
}

extension [Inline] {
    /// Merges adjacent text runs with the same marks and drops empty ones, as ProseMirror does.
    public func normalized() -> [Inline] {
        var out: [Inline] = []
        out.reserveCapacity(count)
        for node in self {
            if case .text(let text, _) = node, text.isEmpty { continue }
            if case .text(let text, let marks) = node, case .text(let last, let lastMarks)? = out.last,
                lastMarks == marks
            {
                out[out.count - 1] = .text(last + text, marks: marks)
            } else {
                out.append(node)
            }
        }
        return out
    }
}
