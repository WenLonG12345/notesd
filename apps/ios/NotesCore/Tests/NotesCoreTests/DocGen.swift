@testable import NotesCore

/// Random documents an editor can produce. The generator stays inside what the
/// dialect can express, which the web's serializer shares: marks change at word
/// boundaries and a space between words is marked only when both neighbours carry
/// the same marks; no leading/trailing whitespace in a block; no hard break in a
/// heading (a heading is one line); a line after a hard break starts with a letter
/// (a "- " or "#" there isn't escaped); and a nested ordered list starts at 1 (inside a
/// list nothing separates it from the text above, and CommonMark won't let another
/// number interrupt a paragraph).
struct DocGen {
    var r: SeededRandom

    init(seed: UInt64) {
        r = SeededRandom(seed: seed)
    }

    private let letters = "abcdefghijklmnopqrstuvwxyzéü日🎉שלום".unicodeScalars.map { String($0) }
    private let symbols = "*_`[]\\~#-+>!:,?\"'".unicodeScalars.map { String($0) }
    private let links: [Mark] = [.link(href: "https://example.com/a"), .link(href: "mailto:me@x.y", title: "Mail")]

    mutating func document() -> Document {
        Document(blocks(depth: 0, count: 1 + r.int(5)))
    }

    private mutating func blocks(depth: Int, count: Int) -> [Block] {
        (0..<count).map { _ in block(depth: depth) }
    }

    private mutating func block(depth: Int) -> Block {
        switch r.int(depth > 2 ? 3 : 9) {
        case 0, 1:
            return paragraph()
        case 2:
            return .heading(level: 1 + r.int(3), inlines(lettersOnly: true))
        case 3:
            return .bulletList((0..<(1 + r.int(3))).map { _ in ListItem(itemBlocks(depth: depth)) })
        case 4:
            let start = depth > 0 ? 1 : 1 + r.int(12)
            return .orderedList(start: start, (0..<(1 + r.int(3))).map { _ in ListItem(itemBlocks(depth: depth)) })
        case 5:
            return .taskList((0..<(1 + r.int(3))).map { _ in TaskItem(checked: r.bool(), itemBlocks(depth: depth)) })
        case 6:
            return .blockquote(blocks(depth: depth + 1, count: 1 + r.int(2)))
        case 7:
            return .rawBlock(r.pick(["```\ncode *x*\n\n  more\n```", "---", "| a | b |\n| - | - |\n| 1 | 2 |"]))
        default:
            return paragraph()
        }
    }

    private mutating func itemBlocks(depth: Int) -> [Block] {
        [paragraph()] + (0..<r.int(2)).map { _ in block(depth: depth + 1) }
    }

    private mutating func paragraph() -> Block {
        .paragraph(inlines(lettersOnly: false))
    }

    private mutating func inlines(lettersOnly: Bool) -> [Inline] {
        var out: [Inline] = []
        var prevMarks: [Mark] = []
        var afterBreak = false
        for i in 0..<(1 + r.int(6)) {
            let marks = marks()
            if i > 0 {
                let shared = prevMarks == marks ? marks : []
                if !lettersOnly && !afterBreak && r.int(8) == 0 {
                    out.append(.hardBreak(marks: shared))
                    afterBreak = true
                } else {
                    out.append(.text(" ", marks: shared))
                    afterBreak = false
                }
            }
            if !afterBreak && r.int(10) == 0 {
                out.append(.rawInline(r.pick(["`code`", "``a ` b``", "![alt](p.png)"]), marks: marks))
            } else {
                out.append(.text(word(lettersOnly: lettersOnly || afterBreak), marks: marks))
            }
            afterBreak = false
            prevMarks = marks
        }
        return out.normalized()
    }

    private mutating func word(lettersOnly: Bool) -> String {
        let chars = lettersOnly ? letters : letters + symbols
        var rest = (0..<r.int(5)).map { _ in r.pick(chars) }.joined()
        while rest.hasSuffix("\\") { rest.removeLast() }
        return r.pick(letters) + rest
    }

    private mutating func marks() -> [Mark] {
        var marks: [Mark] = []
        if r.int(4) == 0 { marks.append(.bold) }
        if r.int(4) == 0 { marks.append(.italic) }
        if r.int(5) == 0 { marks.append(.strike) }
        if r.int(6) == 0 { marks.append(r.pick(links)) }
        return Mark.setOf(marks)
    }
}
