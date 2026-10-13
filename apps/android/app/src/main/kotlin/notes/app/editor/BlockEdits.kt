package notes.app.editor

import android.text.Editable
import android.text.Spanned
import notes.core.doc.Container
import notes.core.doc.Ids
import notes.core.doc.Leaf
import notes.core.doc.ListKind

/** The deepest list nesting the editor creates. */
const val MAX_LIST_DEPTH = 5

/**
 * Structural edits on the block spans: list, quote, heading and indentation
 * changes. Reads every block once, rewrites paths and types in memory, then
 * [commit] replaces only the spans that changed. The caller normalises after.
 */
class BlockEdits(private val text: Editable, private val style: EditorStyle) {
    val blocks: List<BlockRange> = Blocks.of(text)
    private val paths = blocks.map { it.span.path }.toMutableList()
    private val leaves = blocks.map { it.span.leaf }.toMutableList()

    fun path(k: Int) = paths[k]

    fun leaf(k: Int) = leaves[k]

    /** Indices of the blocks touched by [from, to], without the title. */
    fun selected(from: Int, to: Int): List<Int> = blocks.indices.filter { k ->
        k > 0 && blocks[k].start <= to && blocks[k].contentEnd >= from
    }

    fun indexAt(offset: Int): Int = blocks.indexOfLast { it.start <= offset }.coerceAtLeast(0)

    fun commit() {
        blocks.forEachIndexed { k, b ->
            if (b.span.path != paths[k] || b.span.leaf != leaves[k]) {
                text.removeSpan(b.span)
                text.setSpan(
                    BlockSpan(paths[k], leaves[k], null, style),
                    b.start,
                    b.end,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun item(k: Int) = paths[k].lastOrNull() as? Container.Item

    fun isItemStart(k: Int): Boolean {
        val item = item(k) ?: return false
        return paths.getOrNull(k - 1).orEmpty().none { it is Container.Item && it.id == item.id }
    }

    /** First line of the innermost quote it sits in directly. */
    fun isQuoteStart(k: Int): Boolean {
        val quote = paths[k].lastOrNull() as? Container.Quote ?: return false
        return paths.getOrNull(k - 1).orEmpty().none { it == quote }
    }

    // --- headings ------------------------------------------------------------

    fun toggleHeading(selection: List<Int>, level: Int) {
        val targets = selection.filter { leaves[it] !is Leaf.Raw }
        val all = targets.isNotEmpty() && targets.all { leaves[it] == Leaf.Heading(level) }
        for (k in targets) {
            if (all) {
                leaves[k] = Leaf.Paragraph
            } else {
                leaves[k] = Leaf.Heading(level)
                // A heading can't be a list item: it leaves the list, keeping its quotes.
                if (item(k) != null) unlist(k)
            }
        }
    }

    fun setParagraph(selection: List<Int>) {
        for (k in selection) if (leaves[k] is Leaf.Heading) leaves[k] = Leaf.Paragraph
    }

    // --- lists ---------------------------------------------------------------

    fun toggleList(selection: List<Int>, kind: ListKind) {
        val targets = selection.filter { leaves[it] !is Leaf.Raw }
        if (targets.isEmpty()) return
        if (targets.all { item(it)?.kind == kind }) {
            for (k in targets) unlist(k)
            return
        }
        for (k in targets) {
            val current = item(k)
            if (current != null) {
                if (current.kind != kind) changeKind(current.listId, kind)
                continue
            }
            leaves[k] = Leaf.Paragraph
            val listId = adjacentList(k, kind) ?: Ids.next()
            paths[k] = paths[k] + Container.Item(kind, listId, Ids.next())
        }
    }

    /** A list of [kind] right above or below block [k], at the same nesting, to join. */
    private fun adjacentList(k: Int, kind: ListKind): Long? {
        for (n in listOf(k - 1, k + 1)) {
            if (n <= 0 || n >= paths.size) continue
            val other = paths[n].lastOrNull() as? Container.Item ?: continue
            if (other.kind == kind && paths[n].dropLast(1) == paths[k]) return other.listId
        }
        return null
    }

    private fun changeKind(listId: Long, kind: ListKind) {
        for (k in paths.indices) {
            paths[k] = paths[k].map {
                if (it is Container.Item && it.listId == listId) it.copy(kind = kind, checked = false) else it
            }
        }
    }

    /** Takes block [k] out of every list, keeping its quotes; list items after it start a new list. */
    private fun unlist(k: Int) {
        val path = paths[k]
        val firstItem = path.indexOfFirst { it is Container.Item }
        if (firstItem < 0) return
        val outer = path[firstItem] as Container.Item
        paths[k] = path.filterIndexed { i, c -> i < firstItem || c is Container.Quote }
        splitListAfter(k, firstItem, outer.listId)
    }

    private fun splitListAfter(k: Int, depth: Int, listId: Long) {
        val fresh = Ids.next()
        var n = k + 1
        while (n < paths.size) {
            val c = paths[n].getOrNull(depth) as? Container.Item ?: break
            if (c.listId != listId) break
            paths[n] = paths[n].toMutableList().also { it[depth] = c.copy(listId = fresh, start = 1) }
            n++
        }
    }

    fun toggleChecked(k: Int) {
        val item = item(k) ?: return
        if (item.kind != ListKind.Task) return
        for (n in paths.indices) {
            paths[n] =
                paths[n].map { if (it is Container.Item && it.id == item.id) it.copy(checked = !item.checked) else it }
        }
    }

    // --- quotes --------------------------------------------------------------

    fun toggleQuote(selection: List<Int>) {
        if (selection.isEmpty()) return
        if (selection.all { k -> paths[k].any { it is Container.Quote } }) {
            for (k in selection) {
                val at = paths[k].indexOfLast { it is Container.Quote }
                paths[k] = paths[k].filterIndexed { i, _ -> i != at }
            }
            return
        }
        // Join a quote that ends right above the selection.
        val id =
            paths.getOrNull(selection.first() - 1)?.firstOrNull() as? Container.Quote ?: Container.Quote(Ids.next())
        for (k in selection) {
            if (paths[k].firstOrNull() !is Container.Quote) paths[k] = listOf(id) + paths[k]
        }
    }

    // --- indentation ---------------------------------------------------------

    fun canIndent(k: Int): Boolean = isItemStart(k) && previousSibling(k) != null &&
        paths[k].count { it is Container.Item } < MAX_LIST_DEPTH

    fun canOutdent(k: Int): Boolean = item(k) != null || paths[k].lastOrNull() is Container.Quote

    /** The item before [k]'s item in the same list, if any. */
    private fun previousSibling(k: Int): Container.Item? {
        val item = item(k) ?: return null
        val depth = paths[k].lastIndex
        for (n in k - 1 downTo 1) {
            val c = paths[n].getOrNull(depth) as? Container.Item ?: return null
            if (c.listId != item.listId) return null
            if (c.id != item.id) return c
        }
        return null
    }

    /** Makes [k]'s item (with everything nested in it) a child of the item above it. */
    fun indent(k: Int) {
        if (!canIndent(k)) return
        val item = item(k)!!
        val depth = paths[k].lastIndex
        val parent = previousSibling(k)!!
        // Join the parent's last nested list when it's of the same kind.
        val above = paths[k - 1]
        val joined = (above.getOrNull(depth + 1) as? Container.Item)
            ?.takeIf { above[depth].id == parent.id && it.kind == item.kind }
        val nested = item.copy(listId = joined?.listId ?: Ids.next(), start = joined?.start ?: 1)
        var n = k
        while (n < paths.size && (paths[n].getOrNull(depth) as? Container.Item)?.id == item.id) {
            val p = paths[n]
            paths[n] = p.subList(0, depth) + parent + nested + p.subList(depth + 1, p.size)
            n++
        }
    }

    /**
     * One level out. A nested item moves into its parent's list, taking the items
     * after it as its children; a top-level item leaves the list; a quoted line
     * leaves its innermost quote.
     */
    fun outdent(k: Int) {
        val path = paths[k]
        val last = path.lastOrNull() ?: return
        val depth = path.lastIndex
        if (last is Container.Quote) {
            paths[k] = path.dropLast(1)
            return
        }
        val item = last as Container.Item
        val parent = path.getOrNull(depth - 1) as? Container.Item
        if (parent == null) {
            leaves[k] = if (leaves[k] is Leaf.Raw) leaves[k] else Leaf.Paragraph
            var n = k
            while (n < paths.size && (paths[n].getOrNull(depth) as? Container.Item)?.id == item.id) {
                paths[n] = paths[n].filterIndexed { i, _ -> i != depth }
                n++
            }
            splitListAfter(n - 1, depth, item.listId)
            return
        }
        val lifted = Container.Item(
            kind = parent.kind,
            listId = parent.listId,
            id = item.id,
            checked = item.checked && parent.kind == ListKind.Task,
            start = parent.start
        )
        var n = k
        while (n < paths.size) {
            val p = paths[n]
            val c = p.getOrNull(depth) as? Container.Item
            if (p.getOrNull(depth - 1)?.id != parent.id || c == null || c.listId != item.listId) break
            paths[n] = if (c.id == item.id) {
                p.subList(0, depth - 1) + lifted + p.subList(depth + 1, p.size)
            } else {
                // A later sibling becomes a child of the lifted item.
                p.subList(0, depth - 1) + lifted + p.subList(depth, p.size)
            }
            n++
        }
    }
}
