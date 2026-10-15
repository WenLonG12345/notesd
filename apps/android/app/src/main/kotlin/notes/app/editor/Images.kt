package notes.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Spannable
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt
import notes.core.images.ImageRef

/** Where the editor gets pictures (architecture §7.6). */
fun interface ImageSource {
    /**
     * The picture [id] decoded no wider than [width] pixels, or null while it
     * loads (then [onReady] runs, on the main thread) or when it can't be had.
     */
    fun bitmap(id: String, width: Int, onReady: () -> Unit): Bitmap?
}

/** What an [ImageSpan] needs from the editor showing it. */
interface ImageHost {
    val imageSource: ImageSource?

    /** The widest a picture may be drawn: the text's width. */
    val imageMaxWidth: Int

    /** The editor's line spacing multiplier, which every line's height gets, a picture's too. */
    val imageLineSpacing: Float
    val editorStyle: EditorStyle

    /** A picture finished loading: redraw the lines that show it. */
    fun imageReady(id: String)
}

/**
 * Draws an image run (a [RawInlineSpan] holding an app image reference)
 * as the picture, sized from the reference so the text doesn't move when it
 * arrives. Not an [EditorSpan]: the run's [RawInlineSpan] is what the document
 * keeps; [ImageSpans] lays this over it after every change.
 */
class ImageSpan(val source: String, val ref: ImageRef, private val host: ImageHost) : ReplacementSpan() {
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clip = Path()

    /** Width and height to draw at: the picture's own size in dp, shrunk to fit the line. */
    private fun size(): Pair<Int, Int> {
        val style = host.editorStyle
        val max = host.imageMaxWidth.takeIf { it > 0 } ?: style.dp(FALLBACK_WIDTH_DP).toInt()
        val w = minOf(max, (ref.width * style.density).roundToInt()).coerceAtLeast(1)
        val h = (w.toLong() * ref.height / ref.width).toInt().coerceAtLeast(1)
        return w to h
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val (w, h) = size()
        if (fm != null) {
            // Line spacing multiplies every line's height; ask for less so the line ends up as tall as the picture.
            val asked = (h / host.imageLineSpacing.coerceAtLeast(1f)).roundToInt()
            fm.ascent = -asked
            fm.top = -asked
            fm.descent = 0
            fm.bottom = 0
        }
        return w
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        var (w, h) = size()
        // The last line may not get the spacing: fit what the line has.
        val room = bottom - top
        if (h > room && room > 0) {
            w = (w.toLong() * room / h).toInt().coerceAtLeast(1)
            h = room
        }
        val style = host.editorStyle
        val radius = style.dp(RADIUS_DP)
        rect.set(x, top.toFloat(), x + w, (top + h).toFloat())
        val bitmap = host.imageSource?.bitmap(ref.id, w) { host.imageReady(ref.id) }
        if (bitmap == null) {
            placeholder.color = style.hover
            canvas.drawRoundRect(rect, radius, radius, placeholder)
            return
        }
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        canvas.restore()
    }

    private companion object {
        const val FALLBACK_WIDTH_DP = 320f
        const val RADIUS_DP = 10f
    }
}

/** Keeps an [ImageSpan] on every image run, and none anywhere else. Run after each normalisation. */
class ImageSpans(private val host: ImageHost) {
    fun apply(text: Spannable) {
        val raws = text.getSpans(0, text.length, RawInlineSpan::class.java)
        for (img in text.getSpans(0, text.length, ImageSpan::class.java)) {
            val s = text.getSpanStart(img)
            val e = text.getSpanEnd(img)
            val kept = raws.any { it.source == img.source && text.getSpanStart(it) == s && text.getSpanEnd(it) == e }
            if (!kept) text.removeSpan(img)
        }
        for (raw in raws) {
            val ref = ImageRef.parse(raw.source) ?: continue
            val s = text.getSpanStart(raw)
            val e = text.getSpanEnd(raw)
            val has = text.getSpans(s, e, ImageSpan::class.java)
                .any { text.getSpanStart(it) == s && text.getSpanEnd(it) == e }
            if (!has) text.setSpan(ImageSpan(raw.source, ref, host), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Re-places the spans showing [id], which makes the text redraw their lines. */
    fun refresh(text: Spannable, id: String) {
        for (img in text.getSpans(0, text.length, ImageSpan::class.java)) {
            if (img.ref.id != id) continue
            val s = text.getSpanStart(img)
            val e = text.getSpanEnd(img)
            text.removeSpan(img)
            text.setSpan(img, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
