import NotesCore
import UIKit

/// The deepest list nesting the editor creates.
nonisolated let maxListDepth = 5

/// Structural edits on the blocks: list, quote, heading and indentation
/// changes. Reads every block once, rewrites paths and types in memory, then
/// `commit` replaces the attributes that changed. The caller normalises after.
/// A port of Android's BlockEdits.kt.
nonisolated struct BlockEdits {
    let blocks: [BlockRange]
    private(set) var paths: [[Container]]
    private(set) var leaves: [Leaf]

    init(_ text: NSAttributedString) {
        blocks = Blocks.of(text)
        paths = blocks.map { $0.attr?.path ?? [] }
        leaves = blocks.map { $0.attr?.leaf ?? .paragraph }
    }

    /// Indices of the blocks touched by [from, to], without the title.
    func selected(from: Int, to: Int) -> [Int] {
        Blocks.indices(in: blocks, from: from, to: to).filter { $0 > 0 }
    }

    func index(at offset: Int) -> Int { Blocks.index(in: blocks, at: offset) }

    func commit(_ text: NSMutableAttributedString) {
        for (k, b) in blocks.enumerated() {
            if b.attr?.path != paths[k] || b.attr?.leaf != leaves[k] {
                text.addAttribute(.notesBlock, value: BlockAttr(path: paths[k], leaf: leaves[k]), range: b.whole)
            }
        }
    }

    private func item(_ k: Int) -> Container.Item? {
        if case .item(let item)? = paths[k].last { return item }
        return nil
    }

    func isItemStart(_ k: Int) -> Bool {
        guard let item = item(k) else { return false }
        return k == 0 || !paths[k - 1].contains { $0.id == item.id && isItem($0) }
    }

    /// First line of the innermost quote it sits in directly.
    func isQuoteStart(_ k: Int) -> Bool {
        guard case .quote? = paths[k].last, let quote = paths[k].last else { return false }
        return k == 0 || !paths[k - 1].contains(quote)
    }

    // MARK: headings

    mutating func toggleHeading(_ selection: [Int], level: Int) {
        let targets = selection.filter { !isRaw(leaves[$0]) }
        let all = !targets.isEmpty && targets.allSatisfy { leaves[$0] == .heading(level: level) }
        for k in targets {
            if all {
                leaves[k] = .paragraph
            } else {
                leaves[k] = .heading(level: level)
                // A heading can't be a list item: it leaves the list, keeping its quotes.
                if item(k) != nil { unlist(k) }
            }
        }
    }

    // MARK: lists

    mutating func toggleList(_ selection: [Int], kind: ListKind) {
        let targets = selection.filter { !isRaw(leaves[$0]) }
        if targets.isEmpty { return }
        if targets.allSatisfy({ item($0)?.kind == kind }) {
            for k in targets { unlist(k) }
            return
        }
        for k in targets {
            if let current = item(k) {
                if current.kind != kind { changeKind(current.listId, kind) }
                continue
            }
            leaves[k] = .paragraph
            let listId = adjacentList(k, kind) ?? Ids.next()
            paths[k] = paths[k] + [.item(.init(kind: kind, listId: listId, id: Ids.next()))]
        }
    }

    /// A list of `kind` right above or below block `k`, at the same nesting, to join.
    private func adjacentList(_ k: Int, _ kind: ListKind) -> Int? {
        for n in [k - 1, k + 1] where n > 0 && n < paths.count {
            guard case .item(let other)? = paths[n].last else { continue }
            if other.kind == kind && Array(paths[n].dropLast()) == paths[k] { return other.listId }
        }
        return nil
    }

    private mutating func changeKind(_ listId: Int, _ kind: ListKind) {
        for k in paths.indices {
            paths[k] = paths[k].map {
                guard case .item(var item) = $0, item.listId == listId else { return $0 }
                item.kind = kind
                item.checked = false
                return .item(item)
            }
        }
    }

    /// Takes block `k` out of every list, keeping its quotes; list items after it start a new list.
    private mutating func unlist(_ k: Int) {
        let path = paths[k]
        guard let firstItem = path.firstIndex(where: isItem), case .item(let outer) = path[firstItem] else { return }
        paths[k] = path.enumerated().filter { i, c in i < firstItem || !isItem(c) }.map(\.element)
        splitList(after: k, depth: firstItem, listId: outer.listId)
    }

    private mutating func splitList(after k: Int, depth: Int, listId: Int) {
        let fresh = Ids.next()
        var n = k + 1
        while n < paths.count, depth < paths[n].count, case .item(var c) = paths[n][depth], c.listId == listId {
            c.listId = fresh
            c.start = 1
            paths[n][depth] = .item(c)
            n += 1
        }
    }

    mutating func toggleChecked(_ k: Int) {
        guard let item = item(k), item.kind == .task else { return }
        for n in paths.indices {
            paths[n] = paths[n].map {
                guard case .item(var c) = $0, c.id == item.id else { return $0 }
                c.checked = !item.checked
                return .item(c)
            }
        }
    }

    // MARK: quotes

    mutating func toggleQuote(_ selection: [Int]) {
        guard let first = selection.first else { return }
        if selection.allSatisfy({ k in paths[k].contains { !isItem($0) } }) {
            for k in selection {
                if let at = paths[k].lastIndex(where: { !isItem($0) }) { paths[k].remove(at: at) }
            }
            return
        }
        // Join a quote that ends right above the selection.
        var quote = Container.quote(id: Ids.next())
        if first - 1 > 0, let above = paths[first - 1].first, !isItem(above) { quote = above }
        for k in selection where paths[k].first.map(isItem) ?? true {
            paths[k] = [quote] + paths[k]
        }
    }

    // MARK: indentation

    func canIndent(_ k: Int) -> Bool {
        isItemStart(k) && previousSibling(k) != nil && paths[k].filter(isItem).count < maxListDepth
    }

    func canOutdent(_ k: Int) -> Bool { !paths[k].isEmpty }

    /// The item before `k`'s item in the same list, if any.
    private func previousSibling(_ k: Int) -> Container.Item? {
        guard let item = item(k) else { return nil }
        let depth = paths[k].count - 1
        var n = k - 1
        while n >= 1 {
            guard depth < paths[n].count, case .item(let c) = paths[n][depth], c.listId == item.listId else {
                return nil
            }
            if c.id != item.id { return c }
            n -= 1
        }
        return nil
    }

    /// Makes `k`'s item (with everything nested in it) a child of the item above it.
    mutating func indent(_ k: Int) {
        guard canIndent(k), let item = item(k), let parent = previousSibling(k) else { return }
        let depth = paths[k].count - 1
        // Join the parent's last nested list when it's of the same kind.
        let above = paths[k - 1]
        var joined: Container.Item?
        if depth + 1 < above.count, case .item(let c) = above[depth + 1], above[depth].id == parent.id,
            c.kind == item.kind
        {
            joined = c
        }
        var nested = item
        nested.listId = joined?.listId ?? Ids.next()
        nested.start = joined?.start ?? 1
        var n = k
        while n < paths.count, depth < paths[n].count, case .item(let c) = paths[n][depth], c.id == item.id {
            let p = paths[n]
            paths[n] = Array(p[..<depth]) + [.item(parent), .item(nested)] + Array(p[(depth + 1)...])
            n += 1
        }
    }

    /// One level out. A nested item moves into its parent's list, taking the items
    /// after it as its children; a top-level item leaves the list; a quoted line
    /// leaves its innermost quote.
    mutating func outdent(_ k: Int) {
        let path = paths[k]
        guard let last = path.last else { return }
        let depth = path.count - 1
        guard case .item(let item) = last else {
            paths[k] = Array(path.dropLast())
            return
        }
        guard depth >= 1, case .item(let parent) = path[depth - 1] else {
            if !isRaw(leaves[k]) { leaves[k] = .paragraph }
            var n = k
            while n < paths.count, depth < paths[n].count, case .item(let c) = paths[n][depth], c.id == item.id {
                paths[n].remove(at: depth)
                n += 1
            }
            splitList(after: n - 1, depth: depth, listId: item.listId)
            return
        }
        let lifted = Container.Item(
            kind: parent.kind, listId: parent.listId, id: item.id, checked: item.checked && parent.kind == .task,
            start: parent.start)
        var n = k
        while n < paths.count {
            let p = paths[n]
            guard depth < p.count, p[depth - 1].id == parent.id, case .item(let c) = p[depth], c.listId == item.listId
            else { break }
            if c.id == item.id {
                paths[n] = Array(p[..<(depth - 1)]) + [.item(lifted)] + Array(p[(depth + 1)...])
            } else {
                // A later sibling becomes a child of the lifted item.
                paths[n] = Array(p[..<(depth - 1)]) + [.item(lifted)] + Array(p[depth...])
            }
            n += 1
        }
    }
}

nonisolated func isItem(_ c: Container) -> Bool {
    if case .item = c { return true }
    return false
}

nonisolated func isRaw(_ leaf: Leaf) -> Bool {
    if case .raw = leaf { return true }
    return false
}
