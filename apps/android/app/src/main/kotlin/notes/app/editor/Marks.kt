package notes.app.editor

import android.text.Editable
import android.text.Spanned
import notes.core.doc.Leaf
import notes.core.doc.Mark

/** Adding, removing and reading inline marks over ranges of the text. */
object Marks {
    /** [from, to) cut to the text of markable blocks (not the title, not raw blocks, no separators). */
    fun pieces(text: Spanned, from: Int, to: Int): List<Pair<Int, Int>> = Blocks.of(text).drop(1)
        .filter { it.span.leaf !is Leaf.Raw }
        .mapNotNull { b ->
            val a = maxOf(from, b.start)
            val e = minOf(to, b.contentEnd)
            if (a < e) a to e else null
        }

    private fun spansOf(text: Spanned, mark: Mark, from: Int, to: Int): List<EditorSpan> =
        text.getSpans(from, to, EditorSpan::class.java).filter { span ->
            val m = SpanCodec.markOf(span) ?: return@filter false
            if (mark is Mark.Link) m is Mark.Link else m == mark
        }

    /** Whether every markable character in [from, to) has [mark] (any link, for links). */
    fun covers(text: Spanned, mark: Mark, from: Int, to: Int): Boolean {
        val pieces = pieces(text, from, to)
        if (pieces.isEmpty()) return false
        return pieces.all { (a, b) ->
            val spans = spansOf(text, mark, a, b).map {
                text.getSpanStart(it) to text.getSpanEnd(it)
            }.sortedBy { it.first }
            var reached = a
            for ((s, e) in spans) {
                if (s > reached) break
                reached = maxOf(reached, e)
            }
            reached >= b
        }
    }

    /** Marks active for text typed at [offset]: bold, italic and strike continue at their end; links don't. */
    fun at(text: Spanned, offset: Int): Set<Mark> = text.getSpans(offset, offset, EditorSpan::class.java)
        .mapNotNull { span ->
            val mark = SpanCodec.markOf(span) ?: return@mapNotNull null
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            val active = if (mark is Mark.Link) offset in (s + 1) until e else offset in (s + 1)..e
            if (active) mark else null
        }.toSet()

    fun add(text: Editable, mark: Mark, from: Int, to: Int, style: EditorStyle) {
        for ((a, b) in pieces(text, from, to)) {
            var start = a
            var end = b
            // Merge with the same mark touching the range, so runs stay one span.
            for (span in spansOf(text, mark, a - 1, b + 1)) {
                if (mark is Mark.Link && SpanCodec.markOf(span) != mark) {
                    clip(text, span, a, b, style)
                    continue
                }
                start = minOf(start, text.getSpanStart(span))
                end = maxOf(end, text.getSpanEnd(span))
                text.removeSpan(span)
            }
            text.setSpan(SpanCodec.markSpan(mark, style), start, end, SpanCodec.flagsFor(mark))
        }
    }

    fun remove(text: Editable, mark: Mark, from: Int, to: Int, style: EditorStyle) {
        for ((a, b) in pieces(text, from, to)) {
            for (span in spansOf(text, mark, a, b)) clip(text, span, a, b, style)
        }
    }

    /** Removes [span] from [a, b), keeping its parts outside. */
    private fun clip(text: Editable, span: EditorSpan, a: Int, b: Int, style: EditorStyle) {
        val s = text.getSpanStart(span)
        val e = text.getSpanEnd(span)
        if (e <= a || s >= b) return
        val mark = SpanCodec.markOf(span)!!
        text.removeSpan(span)
        if (s < a) text.setSpan(SpanCodec.markSpan(mark, style), s, a, SpanCodec.flagsFor(mark))
        if (e > b) text.setSpan(SpanCodec.markSpan(mark, style), b, e, SpanCodec.flagsFor(mark))
    }

    /** The link span at [offset], for taps and the link sheet. */
    fun linkAt(text: Spanned, offset: Int): LinkSpan? = text.getSpans(offset, offset, LinkSpan::class.java)
        .firstOrNull { text.getSpanStart(it) <= offset && offset <= text.getSpanEnd(it) }
}
