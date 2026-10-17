import Synchronization

/// A `Document` flattened into lines, the shape a native text view edits: one
/// line per textblock (title, paragraph, heading, raw block), each carrying the
/// path of containers (quotes, list items) it sits in.
///
/// Containers carry identities rather than depths so the flat form is lossless:
/// two adjacent bullet lists, a list item with two paragraphs and a list inside
/// a quote all survive `toLines()` → `linesToDocument(_:)`.
public struct Line: Hashable, Sendable {
    public var path: [Container]
    public var leaf: Leaf
    public var content: [Inline]

    public init(path: [Container], leaf: Leaf, content: [Inline] = []) {
        self.path = path
        self.leaf = leaf
        self.content = content
    }

    public var items: [Container.Item] {
        path.compactMap { if case .item(let item) = $0 { item } else { nil } }
    }

    public var item: Container.Item? {
        if case .item(let item)? = path.last { return item }
        return nil
    }
}

public enum Container: Hashable, Sendable {
    case quote(id: Int)
    case item(Item)

    public var id: Int {
        switch self {
        case .quote(let id): id
        case .item(let item): item.id
        }
    }

    /// A list item. `listId` groups items into one list; `id` groups the lines of
    /// one item. `start` is the list's first number (ordered lists only), `checked`
    /// the item's box (task lists only).
    public struct Item: Hashable, Sendable {
        public var kind: ListKind
        public var listId: Int
        public var id: Int
        public var checked: Bool
        public var start: Int

        public init(kind: ListKind, listId: Int, id: Int, checked: Bool = false, start: Int = 1) {
            self.kind = kind
            self.listId = listId
            self.id = id
            self.checked = checked
            self.start = start
        }
    }
}

public enum ListKind: Hashable, Sendable { case bullet, ordered, task }

public enum Leaf: Hashable, Sendable {
    case title
    case paragraph
    case heading(level: Int)
    case raw(source: String)
}

/// Identities for containers; unique within the process, which is all lines need.
public enum Ids {
    private static let counter = Atomic<Int>(1)

    public static func next() -> Int {
        counter.wrappingAdd(1, ordering: .relaxed).oldValue
    }
}

extension Document {
    public func toLines() -> [Line] {
        var out: [Line] = []
        flatten(blocks, path: [], into: &out)
        return out
    }
}

private func flatten(_ blocks: [Block], path: [Container], into out: inout [Line]) {
    for block in blocks {
        switch block {
        case .title(let text):
            out.append(Line(path: path, leaf: .title, content: [Inline.text(text)].normalized()))
        case .paragraph(let content):
            out.append(Line(path: path, leaf: .paragraph, content: content))
        case .heading(let level, let content):
            out.append(Line(path: path, leaf: .heading(level: level), content: content))
        case .rawBlock(let source):
            out.append(Line(path: path, leaf: .raw(source: source)))
        case .blockquote(let blocks):
            flatten(blocks, path: path + [.quote(id: Ids.next())], into: &out)
        case .bulletList(let items):
            let list = Ids.next()
            for item in items {
                flatten(
                    item.blocks, path: path + [.item(.init(kind: .bullet, listId: list, id: Ids.next()))], into: &out)
            }
        case .orderedList(let start, let items):
            let list = Ids.next()
            for item in items {
                let container = Container.Item(kind: .ordered, listId: list, id: Ids.next(), start: start)
                flatten(item.blocks, path: path + [.item(container)], into: &out)
            }
        case .taskList(let items):
            let list = Ids.next()
            for item in items {
                let container = Container.Item(kind: .task, listId: list, id: Ids.next(), checked: item.checked)
                flatten(item.blocks, path: path + [.item(container)], into: &out)
            }
        }
    }
}

/// Rebuilds the tree: consecutive lines sharing a container at the same depth
/// share that node. A list item whose first line isn't a paragraph gets an
/// empty one, as ProseMirror's schema would add.
public func linesToDocument(_ lines: [Line]) -> Document {
    let blocks = build(lines[...], depth: 0)
    return Document(blocks.isEmpty ? [.paragraph([])] : blocks)
}

private func build(_ lines: ArraySlice<Line>, depth: Int) -> [Block] {
    var out: [Block] = []
    var i = lines.startIndex
    while i < lines.endIndex {
        let line = lines[i]
        guard depth < line.path.count else {
            out.append(leafBlock(line))
            i += 1
            continue
        }
        let container = line.path[depth]
        var j = i + 1
        switch container {
        case .quote:
            while j < lines.endIndex && lines[j].path[safe: depth] == container { j += 1 }
            out.append(.blockquote(build(lines[i..<j], depth: depth + 1)))
        case .item(let item):
            while j < lines.endIndex, case .item(let other)? = lines[j].path[safe: depth], other.listId == item.listId {
                j += 1
            }
            out.append(list(first: item, lines[i..<j], depth: depth))
        }
        i = j
    }
    return out
}

private func list(first: Container.Item, _ lines: ArraySlice<Line>, depth: Int) -> Block {
    var items: [(Container.Item, [Block])] = []
    var i = lines.startIndex
    while i < lines.endIndex {
        guard case .item(let item) = lines[i].path[depth] else { preconditionFailure("not a list item") }
        var j = i + 1
        while j < lines.endIndex && lines[j].path[depth].id == item.id { j += 1 }
        let blocks = build(lines[i..<j], depth: depth + 1)
        if case .paragraph? = blocks.first {
            items.append((item, blocks))
        } else {
            items.append((item, [.paragraph([])] + blocks))
        }
        i = j
    }
    switch first.kind {
    case .bullet: return .bulletList(items.map { ListItem($0.1) })
    case .ordered: return .orderedList(start: first.start, items.map { ListItem($0.1) })
    case .task: return .taskList(items.map { TaskItem(checked: $0.0.checked, $0.1) })
    }
}

private func leafBlock(_ line: Line) -> Block {
    switch line.leaf {
    case .title:
        let text = line.content.compactMap { if case .text(let text, _) = $0 { text } else { nil } }.joined()
        return .title(text)
    case .paragraph: return .paragraph(line.content)
    case .heading(let level): return .heading(level: level, line.content)
    case .raw(let source): return .rawBlock(source)
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
