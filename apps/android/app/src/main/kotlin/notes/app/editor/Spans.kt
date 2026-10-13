package notes.app.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.View
import notes.core.doc.Container
import notes.core.doc.Leaf

/** Spans the editor owns. The codec reads only these; anything else in the text is ignored. */
interface EditorSpan

/** What the margin of an item's first line shows. */
sealed interface Marker {
    data class Bullet(val level: Int) : Marker

    data class Number(val n: Int) : Marker

    data class Box(val checked: Boolean) : Marker
}

/**
 * The one span every block (paragraph, heading, title, raw block) carries,
 * covering its text and the "\n" that ends it. Holds the block's container path
 * and type, and draws list markers and quote bars in its leading margin.
 * [marker] is derived by the normaliser (numbers are assigned there, not at
 * draw time).
 */
class BlockSpan(val path: List<Container>, val leaf: Leaf, val marker: Marker?, private val style: EditorStyle) :
    LeadingMarginSpan,
    LineBackgroundSpan,
    EditorSpan {
    fun sameAs(path: List<Container>, leaf: Leaf, marker: Marker?) =
        this.path == path && this.leaf == leaf && this.marker == marker

    override fun getLeadingMargin(first: Boolean): Int = margin().toInt()

    fun margin(): Float = path.sumOf {
        (if (it is Container.Quote) style.quoteIndent else style.itemIndent).toDouble()
    }.toFloat()

    /** Where the innermost item's marker column starts, from the margin edge. */
    private fun markerOffset(): Float = margin() - style.itemIndent

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout?
    ) {
        val savedColor = p.color
        val savedStyle = p.style
        var offset = 0f
        for (container in path) {
            if (container is Container.Quote) {
                p.color = style.accent
                p.style = Paint.Style.FILL
                val left = if (dir > 0) x + offset else x - offset - style.quoteBar
                c.drawRect(left, top.toFloat(), left + style.quoteBar, bottom.toFloat(), p)
                offset += style.quoteIndent
            } else {
                offset += style.itemIndent
            }
        }
        val spanned = text as? Spanned
        if (marker != null && first && spanned != null && spanned.getSpanStart(this) == start) {
            drawMarker(c, p, x, dir, baseline)
        }
        p.color = savedColor
        p.style = savedStyle
    }

    private fun drawMarker(c: Canvas, p: Paint, x: Int, dir: Int, baseline: Int) {
        val column = markerOffset()
        val center = markerCenterX(x.toFloat(), dir, column)
        val fm = p.fontMetrics
        val midY = baseline + (fm.ascent + fm.descent) / 2f
        when (val m = marker) {
            is Marker.Bullet -> {
                p.color = style.ink2
                p.style = if (m.level % 2 == 1) Paint.Style.STROKE else Paint.Style.FILL
                p.strokeWidth = style.dp(1.2f)
                c.drawCircle(center, midY, style.dp(2.6f), p)
            }

            is Marker.Number -> {
                p.color = style.ink2
                p.style = Paint.Style.FILL
                val label = "${m.n}."
                val width = p.measureText(label)
                // Right-aligned against the text, like a list's number column.
                val gap = style.dp(7f)
                val left = if (dir >
                    0
                ) {
                    x + column + style.itemIndent - gap - width
                } else {
                    x - column - style.itemIndent + gap
                }
                c.drawText(label, left, baseline.toFloat(), p)
            }

            is Marker.Box -> drawBox(c, p, center, midY, m.checked)

            null -> Unit
        }
    }

    private fun drawBox(c: Canvas, p: Paint, cx: Float, cy: Float, checked: Boolean) {
        val r = style.boxSize / 2f
        p.style = Paint.Style.FILL
        if (checked) {
            p.color = style.accent
            c.drawCircle(cx, cy, r, p)
            p.color = style.onAccent
            c.drawCircle(cx, cy, r * 0.45f, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = style.dp(1.5f)
        p.color = if (checked) style.accentDeep else style.ink3
        c.drawCircle(cx, cy, r - p.strokeWidth / 2f, p)
    }

    private fun markerCenterX(x: Float, dir: Int, column: Float): Float {
        val inColumn = style.itemIndent * 0.42f
        return if (dir > 0) x + column + inColumn else x - column - inColumn
    }

    /** The task box's centre in layout coordinates, for hit testing taps. */
    fun boxCenter(layout: Layout, line: Int): Pair<Float, Float>? {
        if (marker !is Marker.Box) return null
        val dir = layout.getParagraphDirection(line)
        val x = if (dir > 0) 0f else layout.width.toFloat()
        val baseline = layout.getLineBaseline(line)
        val fm = layout.paint.fontMetrics
        return markerCenterX(x, dir, markerOffset()) to baseline + (fm.ascent + fm.descent) / 2f
    }

    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int
    ) {
        if (leaf !is Leaf.Raw) return
        val saved = paint.color
        paint.color = style.hover
        val margin = margin()
        canvas.drawRect(left + margin, top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        paint.color = saved
    }
}

/** Type size, weight and colour for a block, plus the space around headings. */
class LeafStyleSpan(val leaf: Leaf, val dim: Boolean, private val style: EditorStyle) :
    MetricAffectingSpan(),
    LineHeightSpan,
    EditorSpan {
    fun sameAs(leaf: Leaf, dim: Boolean) = this.leaf == leaf && this.dim == dim

    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
        when {
            leaf is Leaf.Raw -> tp.color = style.ink2
            dim -> tp.color = style.ink3
        }
    }

    private fun apply(tp: TextPaint) {
        when (leaf) {
            Leaf.Title -> {
                tp.textSize *= style.titleScale
                tp.typeface = style.semibold
            }

            is Leaf.Heading -> {
                tp.textSize *= style.headingScale[leaf.level - 1]
                tp.typeface = style.semibold
            }

            is Leaf.Raw -> {
                tp.textSize *= style.rawScale
                tp.typeface = style.monospace
            }

            Leaf.Paragraph -> Unit
        }
    }

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt
    ) {
        val spanned = text as? Spanned ?: return
        val height = fm.descent - fm.ascent
        val above = when (leaf) {
            is Leaf.Heading -> height * 0.45f
            is Leaf.Raw -> height * 0.3f
            else -> 0f
        }.toInt()
        val below = when (leaf) {
            Leaf.Title -> height * 0.3f
            is Leaf.Raw -> height * 0.3f
            else -> 0f
        }.toInt()
        if (above > 0 && start == spanned.getSpanStart(this)) {
            fm.ascent -= above
            fm.top -= above
        }
        if (below > 0 && end >= spanned.getSpanEnd(this)) {
            fm.descent += below
            fm.bottom += below
        }
    }
}

class BoldSpan :
    StyleSpan(Typeface.BOLD),
    EditorSpan

class ItalicSpan :
    StyleSpan(Typeface.ITALIC),
    EditorSpan

class StrikeSpan :
    StrikethroughSpan(),
    EditorSpan

/** A link. Taps are handled by the editor (open / edit / remove), never by the span. */
class LinkSpan(val href: String, val title: String?, private val style: EditorStyle) :
    URLSpan(href),
    EditorSpan {
    override fun onClick(widget: View) = Unit

    override fun updateDrawState(ds: TextPaint) {
        ds.color = style.link
        ds.isUnderlineText = true
    }
}

/** Marks a "\n" that is a line break inside a paragraph, not the end of a block. */
class HardBreakSpan : EditorSpan

/** An inline run outside the dialect (code span, image): shown as written, not editable. */
class RawInlineSpan(val source: String, private val style: EditorStyle) :
    MetricAffectingSpan(),
    EditorSpan {
    override fun updateMeasureState(tp: TextPaint) {
        tp.typeface = style.monospace
        tp.textSize *= style.rawScale
    }

    override fun updateDrawState(tp: TextPaint) {
        updateMeasureState(tp)
        tp.color = style.ink2
        tp.bgColor = style.hover
    }
}

/**
 * An invisible character at the very end of the text while the last block is
 * an empty item, quote or heading. Android's Layout gives an empty last line
 * no paragraph spans (so no marker or indent), and hands a zero-length span at
 * the end to the line above instead; one character gives the block a real
 * range. It isn't content: block ranges end before it and the cursor never
 * goes past it.
 */
class EndMarkerSpan : EditorSpan

const val END_MARKER = '\u200B'

/** Where the end marker sits, or -1. */
fun endMarkerStart(text: Spanned): Int {
    val span = text.getSpans(0, text.length, EndMarkerSpan::class.java).firstOrNull() ?: return -1
    val s = text.getSpanStart(span)
    return if (s == text.length - 1 && text.getSpanEnd(span) == text.length && text[s] == END_MARKER) s else -1
}

/** Covers a raw block's source exactly, so edits inside it can be refused. */
class RawBlockSpan(val source: String) : EditorSpan
