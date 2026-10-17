import Foundation

/// Markdown ⇄ `Document` for the restricted dialect (spec §7.1, §7.4).
public enum Markdown {
    /// Parses stored Markdown into a document. Never throws on odd input.
    public static func parse(_ markdown: String) -> Document {
        let source = normalizeNewlines(markdown.scalars).string
        guard let tokens = Tokenizer.shared.tokens(source) else {
            // Can't happen with a working tokenizer; if it does, keep the note intact rather than lose it.
            return source.isEmpty ? .empty : Document([.rawBlock(source)])
        }
        var builder = TreeBuilder()
        return builder.build(tokens)
    }

    /// Deterministic output: no trailing whitespace, one final newline, "" when empty. Skips the title line.
    public static func serialize(_ doc: Document) -> String {
        MarkdownSerializer().serialize(doc)
    }

    /// The canonical form of stored Markdown: what any editor would save for it.
    public static func canonical(_ markdown: String) -> String {
        serialize(parse(markdown))
    }
}

/// One markdown-it token, as Resources/markdown.js simplifies it.
struct Token: Decodable {
    var y: String
    var l: Int?
    var s: Int?
    var k: Bool?
    var h: String?
    var i: String?
    var c: String?
    var ch: [Token]?
}

/// Builds the document from markdown-it's tokens the way prosemirror-markdown's
/// MarkdownParseState does on the web, including what ProseMirror's
/// `createAndFill` adds to satisfy the schema (an empty paragraph opening a
/// list item that starts with something else, or filling an empty quote).
private struct TreeBuilder {
    private enum Kind {
        case doc, paragraph
        case heading(Int)
        case blockquote, bulletList
        case orderedList(Int)
        case taskList, listItem
        case taskItem(Bool)
    }

    private enum Child {
        case block(Block)
        case inline(Inline)
        case item(ListItem)
        case task(TaskItem)
    }

    private struct Frame {
        var kind: Kind
        var content: [Child] = []
        var marks: [Mark] = []
    }

    private var stack = [Frame(kind: .doc)]

    mutating func build(_ tokens: [Token]) -> Document {
        parse(tokens)
        while stack.count > 1 { close() }
        let blocks = stack[0].content.compactMap { if case .block(let b) = $0 { b } else { nil } }
        return Document(blocks.isEmpty ? [.paragraph([])] : blocks)
    }

    private mutating func parse(_ tokens: [Token]) {
        for token in tokens {
            switch token.y {
            case "paragraph_open": open(.paragraph)
            case "heading_open": open(.heading(min(token.l ?? 1, 3)))  // levels 4–6 read as the smallest
            case "blockquote_open": open(.blockquote)
            case "bullet_list_open": open(.bulletList)
            case "ordered_list_open": open(.orderedList(token.s ?? 1))
            case "task_list_open": open(.taskList)
            case "list_item_open": open(.listItem)
            case "task_item_open": open(.taskItem(token.k ?? false))
            case "paragraph_close", "heading_close", "blockquote_close", "bullet_list_close", "ordered_list_close",
                "task_list_close", "list_item_close", "task_item_close":
                close()
            case "inline": parse(token.ch ?? [])
            case "text": addText(token.c ?? "")
            case "softbreak": addText(" ")
            case "hardbreak": add(.inline(.hardBreak(marks: top.marks)))
            case "raw_inline": add(.inline(.rawInline(token.c ?? "", marks: top.marks)))
            case "raw_block": add(.block(.rawBlock(token.c ?? "")))
            case "em_open": openMark(.italic)
            case "em_close": closeMark(.italic)
            case "strong_open": openMark(.bold)
            case "strong_close": closeMark(.bold)
            case "s_open": openMark(.strike)
            case "s_close": closeMark(.strike)
            case "link_open": openMark(.link(href: token.h ?? "", title: token.i))
            case "link_close": closeMark(.link(href: ""))
            default: break
            }
        }
    }

    private var top: Frame { stack[stack.count - 1] }

    private mutating func open(_ kind: Kind) {
        stack.append(Frame(kind: kind))
    }

    private mutating func add(_ child: Child) {
        stack[stack.count - 1].content.append(child)
    }

    private mutating func addText(_ text: String) {
        if text.isEmpty { return }
        add(.inline(.text(text, marks: top.marks)))
    }

    private mutating func openMark(_ mark: Mark) {
        stack[stack.count - 1].marks = Mark.setOf(top.marks + [mark])
    }

    /// Closing a mark removes every mark of its type, even when an outer one is still
    /// open (`**a **b** c**` leaves " c" plain): that is how the web document is built.
    private mutating func closeMark(_ mark: Mark) {
        stack[stack.count - 1].marks.removeAll { $0.rank == mark.rank }
    }

    private mutating func close() {
        let frame = stack.removeLast()
        if let node = node(frame) { add(node) }
    }

    /// The node a frame becomes, or nil where ProseMirror's createAndFill would give up.
    private func node(_ frame: Frame) -> Child? {
        func inlines() -> [Inline]? {
            var out: [Inline] = []
            for child in frame.content {
                guard case .inline(let inline) = child else { return nil }
                out.append(inline)
            }
            return out.normalized()
        }
        func blocks() -> [Block]? {
            var out: [Block] = []
            for child in frame.content {
                guard case .block(let block) = child else { return nil }
                out.append(block)
            }
            return out
        }
        func itemBlocks() -> [Block]? {
            guard let blocks = blocks() else { return nil }
            if case .paragraph? = blocks.first { return blocks }
            return [.paragraph([])] + blocks
        }
        switch frame.kind {
        case .doc:
            return nil
        case .paragraph:
            return inlines().map { .block(.paragraph($0)) }
        case .heading(let level):
            return inlines().map { .block(.heading(level: level, $0)) }
        case .blockquote:
            return blocks().map { .block(.blockquote($0.isEmpty ? [.paragraph([])] : $0)) }
        case .bulletList, .orderedList:
            var items: [ListItem] = []
            for child in frame.content {
                guard case .item(let item) = child else { return nil }
                items.append(item)
            }
            if items.isEmpty { items = [ListItem([.paragraph([])])] }
            if case .orderedList(let start) = frame.kind { return .block(.orderedList(start: start, items)) }
            return .block(.bulletList(items))
        case .taskList:
            var items: [TaskItem] = []
            for child in frame.content {
                guard case .task(let item) = child else { return nil }
                items.append(item)
            }
            if items.isEmpty { items = [TaskItem(checked: false, [.paragraph([])])] }
            return .block(.taskList(items))
        case .listItem:
            return itemBlocks().map { .item(ListItem($0)) }
        case .taskItem(let checked):
            return itemBlocks().map { .task(TaskItem(checked: checked, $0)) }
        }
    }
}
