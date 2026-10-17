/// `Document` → canonical Markdown (spec §7.4). A line-for-line port of
/// prosemirror-markdown's MarkdownSerializerState plus the node rules in
/// apps/web/src/editor/markdown.ts (by way of Android's MarkdownSerializer.kt),
/// because the output has to match the web byte for byte: a client whose
/// canonical form differs would make every note it opens look edited to the others.
final class MarkdownSerializer {
    private var out = String.UnicodeScalarView()
    private var delim: Scalars = []
    private var closed: Block.Kind?
    private var inTightList = false
    private var atBlockStart = false
    private var inAutolink: Bool?

    func serialize(_ doc: Document) -> String {
        renderBlocks(doc.blocks)
        let lines = Array(out).lines().map { $0.trimEnd([" ", "\t"]) }
        // Not a full trim: an indented code block at the very start keeps its indent.
        let text = Array(lines.joined(separator: ["\n"])).trimStart(["\n"]).trimEnd(isJsSpace)
        return text.isEmpty ? "" : text.string + "\n"
    }

    // MARK: blocks

    private func renderBlocks(_ blocks: [Block]) {
        for (i, block) in blocks.enumerated() { render(block, siblings: blocks, index: i) }
    }

    private func render(_ node: Block, siblings: [Block], index: Int) {
        switch node {
        case .title:
            // The editor's first line is the note's title, stored in its own field.
            break
        case .paragraph(let content):
            renderInline(content)
            closeBlock(node)
        case .heading(let level, let content):
            write(String(repeating: "#", count: level) + " ")
            renderInline(content, fromBlockStart: false)
            closeBlock(node)
        case .blockquote(let blocks):
            wrapBlock("> ", nil, node) { renderBlocks(blocks) }
        case .bulletList(let items):
            let bullet = alternate(siblings, index, node) ? "* " : "- "
            renderList(node, siblings, index, items.map(\.blocks), indent: { _ in 2 }, marker: { _ in bullet })
        case .orderedList(let start, let items):
            let delim = alternate(siblings, index, node) ? ") " : ". "
            // Continuation lines indent to the item's own content column ("9. " → 3, "10. " → 4):
            // the widest number's column would add a space to a code block under "9." per save.
            let marker = { (i: Int) in "\(start + i)\(delim)" }
            renderList(
                node, siblings, index, items.map(\.blocks), indent: { marker($0).unicodeScalars.count }, marker: marker)
        case .taskList(let items):
            let bullet = alternate(siblings, index, node) ? "*" : "-"
            // Nested content aligns after the bullet: "[ ]" is part of the item's text.
            let marker = { (i: Int) in items[i].checked ? "\(bullet) [x] " : "\(bullet) [ ] " }
            renderList(node, siblings, index, items.map(\.blocks), indent: { _ in 2 }, marker: marker)
        case .rawBlock(let source):
            text(source.scalars, escape: false)
            closeBlock(node)
        }
    }

    /// Markdown merges adjacent lists that use the same marker, so a list right
    /// after a sibling list of its kind switches marker ("-" ↔ "*", "." ↔ ")").
    /// Bullet and task lists share the "-" marker, so they count as one kind.
    private func alternate(_ siblings: [Block], _ index: Int, _ node: Block) -> Bool {
        var flip = false
        var i = index - 1
        while i >= 0 && listKind(siblings[i]) == listKind(node) {
            flip.toggle()
            i -= 1
        }
        return flip
    }

    private func listKind(_ block: Block) -> String? {
        switch block {
        case .bulletList, .taskList: "bullet"
        case .orderedList: "ordered"
        default: nil
        }
    }

    private func renderList(
        _ node: Block,
        _ siblings: [Block],
        _ index: Int,
        _ items: [[Block]],
        indent: (Int) -> Int,
        marker firstDelim: (Int) -> String
    ) {
        // prosemirror-markdown separates a list from a same-type predecessor with
        // an extra blank line; alternate() already keeps them apart.
        if index > 0 && siblings[index - 1].kind == node.kind { flushClose(2) }
        if let closed, closed == node.kind {
            flushClose(3)
        } else if inTightList {
            flushClose(1)
        }
        let prevTight = inTightList
        inTightList = true
        for (i, blocks) in items.enumerated() {
            if i > 0 { flushClose(1) }
            wrapBlock(String(repeating: " ", count: indent(i)), firstDelim(i), node) { renderBlocks(blocks) }
        }
        inTightList = prevTight
    }

    private func wrapBlock(_ delim: String, _ firstDelim: String?, _ node: Block, _ f: () -> Void) {
        let old = self.delim
        write(firstDelim ?? delim)
        self.delim += delim.unicodeScalars
        f()
        self.delim = old
        closeBlock(node)
    }

    private func closeBlock(_ node: Block) {
        closed = node.kind
    }

    private func flushClose(_ size: Int = 2) {
        guard closed != nil else { return }
        if !atBlank { out.append("\n") }
        if size > 1 {
            let delimMin = delim.trimEnd(isJsSpace)
            for _ in 0..<(size - 1) {
                out.append(contentsOf: delimMin)
                out.append("\n")
            }
        }
        closed = nil
    }

    private var atBlank: Bool { out.isEmpty || out.last == "\n" }

    private func write(_ content: String? = nil) {
        flushClose()
        if !delim.isEmpty && atBlank { out.append(contentsOf: delim) }
        if let content { out.append(contentsOf: content.unicodeScalars) }
    }

    private func text(_ text: Scalars, escape: Bool = true) {
        let lines = text.lines()
        for (i, line) in lines.enumerated() {
            write()
            // Escape exclamation marks in front of links.
            if !escape && line.first == "[" && endsWithUnescapedBang {
                out.removeLast()
                out.append(contentsOf: "\\!".unicodeScalars)
            }
            out.append(contentsOf: escape ? esc(line, startOfLine: atBlockStart) : line)
            if i != lines.count - 1 { out.append("\n") }
        }
    }

    private var endsWithUnescapedBang: Bool {
        guard out.last == "!" else { return false }
        return out.count == 1 || out[out.index(out.endIndex, offsetBy: -2)] != "\\"
    }

    // MARK: inline

    private func renderInline(_ content: [Inline], fromBlockStart: Bool = true) {
        atBlockStart = fromBlockStart
        var active: [Mark] = []
        var trailing: Scalars = []

        func progress(_ original: Inline?, _ index: Int) {
            var node = original
            var marks = node?.marks ?? []
            // Remove marks from a hard break that is the last node inside them, to
            // avoid parser edge cases with a newline just before closing marks.
            if case .hardBreak? = node {
                marks = marks.filter { m in
                    if index + 1 == content.count { return false }
                    let next = content[index + 1]
                    guard next.marks.contains(m) else { return false }
                    if case .text(let t, _) = next { return t.unicodeScalars.contains { !isJsSpace($0) } }
                    return true
                }
            }
            let len = marks.count
            // Reorder mixable marks (all of ours are), which Markdown may open and
            // close in any order, so the order for this node matches `active`.
            outer: for i in 0..<len {
                let mark = marks[i]
                for j in active.indices where mark == active[j] {
                    if i > j {
                        marks = marks.jsSlice(0, j) + [mark] + marks.jsSlice(j, i) + marks.jsSlice(i + 1, len)
                    } else if j > i {
                        marks = marks.jsSlice(0, i) + marks.jsSlice(i + 1, j) + [mark] + marks.jsSlice(j, len)
                    }
                    continue outer
                }
            }
            // Find the prefix of the mark set that didn't change.
            var keep = 0
            while keep < min(active.count, len) && marks[keep] == active[keep] { keep += 1 }

            var leading = trailing
            trailing = []
            // Expel whitespace at the edges of bold/italic/strike outside the delimiters.
            if case .text(let t, let nodeMarks)? = node,
                marks.contains(where: { m in expels(m) && !active.prefix(keep).contains(m) })
            {
                let s = t.scalars
                let lead = Array(s.prefix(while: isJsSpace))
                if !lead.isEmpty {
                    leading += lead
                    let rest = Array(s.dropFirst(lead.count))
                    node = rest.isEmpty ? nil : .text(rest.string, marks: nodeMarks)
                    if node == nil { marks = active }
                }
            }
            if case .text(let t, let nodeMarks)? = node,
                marks.indices.contains(where: { i in
                    expels(marks[i]) && !isMarkAhead(content, index + 1, Array(marks[0...i]))
                })
            {
                let s = t.scalars
                let trail = Array(s.reversed().prefix(while: isJsSpace).reversed())
                if !trail.isEmpty {
                    trailing = trail
                    let rest = Array(s.dropLast(trail.count))
                    node = rest.isEmpty ? nil : .text(rest.string, marks: nodeMarks)
                    if node == nil { marks = active }
                }
            }

            // Close the marks that need to be closed.
            if node != nil || index == content.count {
                while keep < active.count {
                    text(markString(active.removeLast(), open: false, content, index).scalars, escape: false)
                }
            }
            // Output any previously expelled trailing whitespace outside the marks.
            if !leading.isEmpty { text(leading) }
            if let node {
                while active.count < len {
                    let add = marks[active.count]
                    active.append(add)
                    text(markString(add, open: true, content, index).scalars, escape: false)
                    atBlockStart = false
                }
                renderInlineNode(node, content, index)
                atBlockStart = false
            }
        }

        for (i, node) in content.enumerated() { progress(node, i) }
        progress(nil, content.count)
        atBlockStart = false
    }

    private func renderInlineNode(_ node: Inline, _ content: [Inline], _ index: Int) {
        switch node {
        case .text(let t, _):
            text(t.scalars, escape: inAutolink != true)
        case .rawInline(let source, _):
            text(source.scalars, escape: false)
        case .hardBreak:
            // Written only when something other than hard breaks follows: a trailing break is dropped.
            let rest = content[(index + 1)...]
            if rest.contains(where: { if case .hardBreak = $0 { false } else { true } }) { write("\\\n") }
        }
    }

    private func expels(_ mark: Mark) -> Bool {
        if case .link = mark { return false }
        return true
    }

    private func isMarkAhead(_ content: [Inline], _ start: Int, _ marks: [Mark]) -> Bool {
        var index = start
        while true {
            if index >= content.count { return false }
            let next = content[index]
            if case .hardBreak = next {
                // Skips the node after a hard break too, as prosemirror-markdown does.
                index += 2
                continue
            }
            return next.marks.count >= marks.count && Array(next.marks.prefix(marks.count)) == marks
        }
    }

    private func markString(_ mark: Mark, open: Bool, _ content: [Inline], _ index: Int) -> String {
        switch mark {
        case .bold:
            return "**"
        case .italic:
            return "*"
        case .strike:
            return "~~"
        case .link(let href, let title):
            if open {
                inAutolink = isPlainUrl(mark, content, index)
                return inAutolink == true ? "<" : "["
            }
            let wasAutolink = inAutolink
            inAutolink = nil
            if wasAutolink == true { return ">" }
            var escapedHref = String.UnicodeScalarView()
            for c in href.unicodeScalars {
                if c == "(" || c == ")" || c == "\"" { escapedHref.append("\\") }
                escapedHref.append(c)
            }
            let titlePart = title.map { " \"" + $0.replacing("\"", with: "\\\"") + "\"" } ?? ""
            return "](" + String(escapedHref) + titlePart + ")"
        }
    }

    private func isPlainUrl(_ link: Mark, _ content: [Inline], _ index: Int) -> Bool {
        guard case .link(let href, let title) = link, title == nil, hasScheme(href) else { return false }
        guard case .text(let t, let marks) = content[index], t.same(as: href), marks.last == link else { return false }
        return index == content.count - 1 || !content[index + 1].marks.contains(link)
    }

    /// `^\w+:` with Java's ASCII `\w`.
    private func hasScheme(_ href: String) -> Bool {
        let s = href.scalars
        let n = s.prefix(while: isWordChar).count
        return n > 0 && n < s.count && s[n] == ":"
    }

    /// prosemirror-markdown's esc(): escape so text reads back as the same text.
    private func esc(_ str: Scalars, startOfLine: Bool) -> Scalars {
        var s: Scalars = []
        s.reserveCapacity(str.count + 8)
        for (i, c) in str.enumerated() {
            if "`*\\~[]_".unicodeScalars.contains(c) {
                let intraword =
                    c == "_" && i > 0 && i + 1 < str.count && isWordChar(str[i - 1]) && isWordChar(str[i + 1])
                if !intraword { s.append("\\") }
            }
            s.append(c)
        }
        guard startOfLine else { return s }
        // ^(\+ |[-*>]) → a backslash in front
        if s.starts(with: ["+", " "]) || s.first == "-" || s.first == "*" || s.first == ">" {
            s.insert("\\", at: 0)
        }
        // ^(\s*)(#{1,6})(\s|$) → a backslash before the hashes
        let spaces = s.prefix(while: isJsSpace).count
        let hashes = s[spaces...].prefix(while: { $0 == "#" }).count
        if (1...6).contains(hashes) && (spaces + hashes == s.count || isJsSpace(s[spaces + hashes])) {
            s.insert("\\", at: spaces)
        }
        // ^(\s*[0-9]+)\.\s → "$1\. "
        let lead = s.prefix(while: isJsSpace).count
        let digits = s[lead...].prefix(while: { $0 >= "0" && $0 <= "9" }).count
        let dot = lead + digits
        if digits > 0 && dot + 1 < s.count && s[dot] == "." && isJsSpace(s[dot + 1]) {
            s.replaceSubrange(dot...(dot + 1), with: ["\\", ".", " "])
        }
        return s
    }

    private func isWordChar(_ c: Unicode.Scalar) -> Bool {
        isAsciiLetterOrDigit(c) || c == "_"
    }
}

extension Array {
    /// JavaScript's Array.slice: indices clamp instead of trapping.
    fileprivate func jsSlice(_ from: Int, _ to: Int) -> [Element] {
        let end = Swift.min(to, count)
        return from >= end ? [] : Array(self[from..<end])
    }
}
