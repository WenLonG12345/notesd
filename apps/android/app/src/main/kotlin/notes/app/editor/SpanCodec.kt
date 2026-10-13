package notes.app.editor

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import notes.core.doc.Document
import notes.core.doc.Inline
import notes.core.doc.Leaf
import notes.core.doc.Line
import notes.core.doc.Mark
import notes.core.doc.linesToDocument
import notes.core.doc.normalize
import notes.core.doc.toLines

/** [Document] ⇄ the editor's [Spannable]: one line of text per block, structure in spans. */
object SpanCodec {
    /** Text and structure spans for [doc]. Markers and type styles come from [Normalizer.normalize]. */
    fun toSpannable(doc: Document, style: EditorStyle): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        // Applied once the text is complete: appending would grow spans with an inclusive end.
        val spans = ArrayList<Placement>()
        val lines = doc.toLines()
        lines.forEachIndexed { i, line ->
            val start = sb.length
            when (val leaf = line.leaf) {
                is Leaf.Raw -> {
                    sb.append(leaf.source)
                    spans += Placement(RawBlockSpan(leaf.source), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                else -> appendInlines(sb, line.content, style, spans)
            }
            if (i < lines.lastIndex) sb.append('\n')
            spans +=
                Placement(
                    BlockSpan(line.path, line.leaf, null, style),
                    start,
                    sb.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
        }
        for (p in spans) sb.setSpan(p.span, p.start, p.end, p.flags)
        return sb
    }

    private class Placement(val span: Any, val start: Int, val end: Int, val flags: Int)

    private fun appendInlines(
        sb: SpannableStringBuilder,
        content: List<Inline>,
        style: EditorStyle,
        spans: MutableList<Placement>
    ) {
        // Open mark spans by rank, extended while consecutive runs keep the same mark.
        val open = HashMap<Int, Pair<Mark, Int>>()
        fun close(rank: Int) {
            val (mark, from) = open.remove(rank) ?: return
            if (from < sb.length) spans += Placement(markSpan(mark, style), from, sb.length, flagsFor(mark))
        }
        for (node in content) {
            for (rank in open.keys.toList()) {
                if (node.marks.none { it == open[rank]!!.first }) close(rank)
            }
            for (mark in node.marks) {
                if (mark.rank !in open) open[mark.rank] = mark to sb.length
            }
            val start = sb.length
            when (node) {
                is Inline.Text -> sb.append(node.text)

                is Inline.HardBreak -> {
                    sb.append('\n')
                    spans += Placement(HardBreakSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                is Inline.RawInline -> {
                    sb.append(node.source)
                    spans +=
                        Placement(RawInlineSpan(node.source, style), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        for (rank in open.keys.toList()) close(rank)
    }

    fun markSpan(mark: Mark, style: EditorStyle): EditorSpan = when (mark) {
        Mark.Bold -> BoldSpan()
        Mark.Italic -> ItalicSpan()
        Mark.Strike -> StrikeSpan()
        is Mark.Link -> LinkSpan(mark.href, mark.title, style)
    }

    /** Typing at the end of bold, italic or struck text continues it; a link doesn't grow. */
    fun flagsFor(mark: Mark): Int =
        if (mark is Mark.Link) Spanned.SPAN_EXCLUSIVE_EXCLUSIVE else Spanned.SPAN_EXCLUSIVE_INCLUSIVE

    fun markOf(span: Any): Mark? = when (span) {
        is BoldSpan -> Mark.Bold
        is ItalicSpan -> Mark.Italic
        is StrikeSpan -> Mark.Strike
        is LinkSpan -> Mark.Link(span.href, span.title)
        else -> null
    }

    /** The document the text represents. Expects normalised text (see [Normalizer]). */
    fun toDocument(text: Spanned): Document {
        val lines = Blocks.of(text).map { block ->
            when (val leaf = block.span.leaf) {
                is Leaf.Raw -> Line(block.span.path, leaf)

                Leaf.Title -> Line(
                    emptyList(),
                    Leaf.Title,
                    listOf(Inline.Text(text.substring(block.start, block.contentEnd))).normalize()
                )

                else -> Line(block.span.path, leaf, inlines(text, block.start, block.contentEnd))
            }
        }
        return linesToDocument(lines)
    }

    /** The inline content of [start, end): runs split wherever a mark, break or raw run starts or ends. */
    fun inlines(text: Spanned, start: Int, end: Int): List<Inline> {
        val spans = text.getSpans(start, end, EditorSpan::class.java).filter {
            markOf(it) != null || it is HardBreakSpan || it is RawInlineSpan
        }
        val cuts = sortedSetOf(start, end)
        if (start >= end) return emptyList()
        for (span in spans) {
            cuts += text.getSpanStart(span).coerceIn(start, end)
            cuts += text.getSpanEnd(span).coerceIn(start, end)
        }
        val out = ArrayList<Inline>()
        val points = cuts.toList()
        var lastRaw: RawInlineSpan? = null
        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val covering = spans.filter { text.getSpanStart(it) <= a && text.getSpanEnd(it) >= b }
            val marks = Mark.setOf(covering.mapNotNull(::markOf))
            val raw = covering.firstOrNull { it is RawInlineSpan } as RawInlineSpan?
            // A raw run is atomic: a mark boundary inside it doesn't make two runs.
            if (raw != null && raw === lastRaw) continue
            lastRaw = raw
            out += when {
                raw != null -> Inline.RawInline(raw.source, marks)
                covering.any { it is HardBreakSpan } -> Inline.HardBreak(marks)
                else -> Inline.Text(text.substring(a, b), marks)
            }
        }
        return out.normalize()
    }
}

/** A block's range in the text: [start, end) includes its trailing "\n"; [contentEnd] doesn't. */
data class BlockRange(val start: Int, val end: Int, val contentEnd: Int, val span: BlockSpan)

object Blocks {
    /** Every block, in order. Each ends with its "\n" separator except the last. */
    fun of(text: Spanned): List<BlockRange> {
        val spans = text.getSpans(0, text.length, BlockSpan::class.java).sortedWith(
            compareBy({ text.getSpanStart(it) }, { text.getSpanEnd(it) })
        )
        val marker = endMarkerStart(text)
        return spans.mapIndexed { i, span ->
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val contentEnd = when {
                i < spans.lastIndex && end > start -> end - 1
                marker >= start -> marker
                else -> end
            }
            BlockRange(start, end, contentEnd, span)
        }
    }

    fun at(text: Spanned, offset: Int): BlockRange? {
        val all = of(text)
        return all.lastOrNull { it.start <= offset && offset <= it.contentEnd } ?: all.lastOrNull { it.start <= offset }
    }

    fun inRange(text: Spanned, from: Int, to: Int): List<BlockRange> =
        of(text).filter { it.start <= to && it.contentEnd >= from }.ifEmpty { listOfNotNull(at(text, from)) }
}
