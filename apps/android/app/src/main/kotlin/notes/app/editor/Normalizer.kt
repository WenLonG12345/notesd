package notes.app.editor

import android.text.Editable
import android.text.Spanned
import notes.core.doc.Container
import notes.core.doc.Ids
import notes.core.doc.Leaf
import notes.core.doc.ListKind

/**
 * Re-derives the structure spans from the text after every change, so editing
 * can never leave the spans inconsistent:
 *
 * - Blocks are the text between separators: every "\n" that isn't a hard break
 *   and isn't inside a raw block.
 * - Each block gets exactly one [BlockSpan]. A block that kept its span's start
 *   keeps its type; a block split off by Enter takes the type the line above
 *   continues into (next list item, same quote, paragraph after a heading or
 *   the title); blocks joined by deleting a separator take the first one's type.
 * - The first block is the title; nothing else is.
 * - List numbers, bullets and boxes are assigned here, not at draw time.
 * - Marks never cross a block, and the title and raw blocks have none.
 *
 * Only span work happens while an IME composition is active, with one
 * exception that can't wait: a raw run that lost part of its text is removed
 * whole (raw text is never edited in place).
 */
class Normalizer(private val style: EditorStyle) {
    private data class Info(val path: List<Container>, val leaf: Leaf)

    private class Range(val start: Int, val contentEnd: Int, val end: Int)

    fun normalize(text: Editable, composingStart: Int = -1, composingEnd: Int = -1) {
        removeDamagedRaw(text, composingStart, composingEnd)
        splitRawBlocks(text)
        cleanHardBreaks(text)
        val ranges = ranges(text)
        val infos = assign(text, ranges)
        val markers = markers(infos)
        applyBlockSpans(text, ranges, infos, markers)
        applyLeafStyles(text, ranges, infos)
        clipMarks(text, ranges, infos)
    }

    // --- raw runs ------------------------------------------------------------

    private fun removeDamagedRaw(text: Editable, composingStart: Int, composingEnd: Int) {
        val damaged = text.getSpans(0, text.length, EditorSpan::class.java)
            .filter { it is RawInlineSpan || it is RawBlockSpan }
            .filter { span ->
                val s = text.getSpanStart(span)
                val e = text.getSpanEnd(span)
                text.substring(s, e) != sourceOf(span) && !(composingStart < e && composingEnd > s)
            }
            .sortedByDescending { text.getSpanStart(it) }
        for (span in damaged) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            text.removeSpan(span)
            if (e > s) text.delete(s, e)
        }
    }

    /** Keeps a raw block alone on its line: text that ended up beside it gets its own block. */
    private fun splitRawBlocks(text: Editable) {
        val raws = text.getSpans(0, text.length, RawBlockSpan::class.java).sortedByDescending { text.getSpanStart(it) }
        for (raw in raws) {
            val s = text.getSpanStart(raw)
            val e = text.getSpanEnd(raw)
            if (e < text.length && text[e] != '\n') text.insert(e, "\n")
            if (s > 0 && text[s - 1] != '\n') text.insert(s, "\n")
        }
    }

    private fun sourceOf(span: EditorSpan) = when (span) {
        is RawInlineSpan -> span.source
        is RawBlockSpan -> span.source
        else -> ""
    }

    private fun cleanHardBreaks(text: Editable) {
        for (span in text.getSpans(0, text.length, HardBreakSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (e - s != 1 || text[s] != '\n') text.removeSpan(span)
        }
    }

    // --- blocks --------------------------------------------------------------

    private fun ranges(text: Spanned): List<Range> {
        val breaks = text.getSpans(0, text.length, HardBreakSpan::class.java).mapTo(HashSet()) { text.getSpanStart(it) }
        val raws = text.getSpans(0, text.length, RawBlockSpan::class.java).map {
            text.getSpanStart(it) until
                text.getSpanEnd(it)
        }
        val out = ArrayList<Range>()
        var start = 0
        for (i in 0 until text.length) {
            if (text[i] != '\n' || i in breaks || raws.any { i in it }) continue
            out += Range(start, i, i + 1)
            start = i + 1
        }
        out += Range(start, text.length, text.length)
        return out
    }

    private fun assign(text: Spanned, ranges: List<Range>): List<Info> {
        val spans = text.getSpans(0, text.length, BlockSpan::class.java).sortedBy { text.getSpanStart(it) }
        val owners = arrayOfNulls<BlockSpan>(ranges.size)
        val infos = ArrayList<Info>(ranges.size)
        ranges.forEachIndexed { k, r ->
            val owner = spans.firstOrNull { text.getSpanStart(it) in r.start..r.contentEnd }
            owners[k] = owner
            // A block without a span of its own was split off the one above (Enter, a paste).
            infos += when {
                owner != null -> Info(owner.path, owner.leaf)
                k > 0 -> derive(infos[k - 1])
                else -> Info(emptyList(), Leaf.Paragraph)
            }
        }

        // Enter at the start of a non-empty block: the new empty line goes above,
        // and the text keeps its type (a heading stays a heading, an item its box).
        for (k in 0 until ranges.lastIndex) {
            val owner = owners[k] ?: continue
            if (owner.leaf == Leaf.Title || owners[k + 1] != null) continue
            if (ranges[k].start == ranges[k].contentEnd && ranges[k + 1].start < ranges[k + 1].contentEnd) {
                infos[k + 1] = infos[k]
                infos[k] = derive(infos[k])
            }
        }

        for (k in infos.indices) {
            val info = infos[k]
            infos[k] = when {
                k == 0 -> Info(emptyList(), Leaf.Title)
                info.leaf == Leaf.Title -> Info(emptyList(), Leaf.Paragraph)
                info.leaf is Leaf.Raw && !hasRaw(text, ranges[k]) -> Info(info.path, Leaf.Paragraph)
                else -> info
            }
        }
        return infos
    }

    private fun hasRaw(text: Spanned, r: Range) =
        text.getSpans(r.start, r.contentEnd, RawBlockSpan::class.java).any { text.getSpanStart(it) == r.start }

    /** The type a new line continues into from the line it was split off. */
    private fun derive(info: Info): Info = when (info.leaf) {
        Leaf.Title -> Info(emptyList(), Leaf.Paragraph)

        is Leaf.Heading, is Leaf.Raw -> Info(info.path, Leaf.Paragraph)

        Leaf.Paragraph -> {
            val item = info.path.lastOrNull() as? Container.Item
            if (item ==
                null
            ) {
                info
            } else {
                Info(info.path.dropLast(1) + item.copy(id = Ids.next(), checked = false), Leaf.Paragraph)
            }
        }
    }

    private fun markers(infos: List<Info>): List<Marker?> {
        val next = HashMap<Long, Int>()
        return infos.mapIndexed { k, info ->
            val item = info.path.lastOrNull() as? Container.Item ?: return@mapIndexed null
            val previous = infos.getOrNull(k - 1)?.path.orEmpty()
            if (previous.any { it is Container.Item && it.id == item.id }) return@mapIndexed null
            when (item.kind) {
                ListKind.Bullet -> Marker.Bullet(info.path.count { it is Container.Item } - 1)

                ListKind.Task -> Marker.Box(item.checked)

                ListKind.Ordered -> {
                    val n = next[item.listId] ?: item.start
                    next[item.listId] = n + 1
                    Marker.Number(n)
                }
            }
        }
    }

    private fun applyBlockSpans(text: Editable, ranges: List<Range>, infos: List<Info>, markers: List<Marker?>) {
        val existing = text.getSpans(0, text.length, BlockSpan::class.java)
        val byStart = existing.groupBy { text.getSpanStart(it) }
        val keep = HashSet<BlockSpan>()
        ranges.forEachIndexed { k, r ->
            val info = infos[k]
            val reuse = byStart[r.start]?.firstOrNull {
                it !in keep && it.sameAs(info.path, info.leaf, markers[k]) && text.getSpanEnd(it) == r.end
            }
            if (reuse != null) {
                keep += reuse
            } else {
                val span = BlockSpan(info.path, info.leaf, markers[k], style)
                text.setSpan(span, r.start, r.end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                keep += span
            }
        }
        for (span in existing) if (span !in keep) text.removeSpan(span)
    }

    private fun applyLeafStyles(text: Editable, ranges: List<Range>, infos: List<Info>) {
        val existing = text.getSpans(0, text.length, LeafStyleSpan::class.java)
        val byStart = existing.groupBy { text.getSpanStart(it) }
        val keep = HashSet<LeafStyleSpan>()
        ranges.forEachIndexed { k, r ->
            val info = infos[k]
            val dim = info.path.any { it is Container.Item && it.kind == ListKind.Task && it.checked }
            val reuse = byStart[r.start]?.firstOrNull {
                it !in keep && it.sameAs(info.leaf, dim) && text.getSpanEnd(it) == r.end
            }
            if (reuse != null) {
                keep += reuse
            } else {
                val span = LeafStyleSpan(info.leaf, dim, style)
                text.setSpan(span, r.start, r.end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                keep += span
            }
        }
        for (span in existing) if (span !in keep) text.removeSpan(span)
    }

    // --- marks ---------------------------------------------------------------

    private fun clipMarks(text: Editable, ranges: List<Range>, infos: List<Info>) {
        val markable = ranges.filterIndexed { k, _ -> infos[k].leaf != Leaf.Title && infos[k].leaf !is Leaf.Raw }
        val spans = text.getSpans(0, text.length, EditorSpan::class.java).filter { SpanCodec.markOf(it) != null }
        for (span in spans) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            val pieces = markable.mapNotNull { r ->
                val a = maxOf(s, r.start)
                val b = minOf(e, r.contentEnd)
                if (a < b) a to b else null
            }
            if (pieces.size == 1 && pieces[0] == (s to e)) continue
            val mark = SpanCodec.markOf(span)!!
            text.removeSpan(span)
            for ((a, b) in pieces) text.setSpan(SpanCodec.markSpan(mark, style), a, b, SpanCodec.flagsFor(mark))
        }
    }
}
