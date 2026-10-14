package notes.app.editor

import android.app.Activity
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import notes.app.R
import notes.core.doc.Document
import notes.core.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class CursorInViewTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val scroller = NestedScrollView(activity)
    private val editor = NoteEditText(ContextThemeWrapper(activity, R.style.Theme_Notes))

    init {
        editor.setDocument(Document.forNote("T", Markdown.parse((1..40).joinToString("\n\n") { "Line $it" })))
        scroller.addView(editor, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scroller.keepCursorInView(editor)
        activity.setContentView(
            FrameLayout(activity).apply {
                addView(scroller, FrameLayout.LayoutParams(MATCH_PARENT, 2000))
            }
        )
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun resize(height: Int) {
        scroller.layoutParams = scroller.layoutParams.apply { this.height = height }
        idle()
    }

    /** The cursor's line, in the scroller's visible coordinates. */
    private fun cursorLine(): IntRange {
        val layout = editor.layout
        val line = layout.getLineForOffset(editor.selectionEnd)
        val top = editor.totalPaddingTop + layout.getLineTop(line) - scroller.scrollY
        return top..(top + layout.getLineBottom(line) - layout.getLineTop(line))
    }

    @Test
    fun `the cursor stays in view when the keyboard shortens the note`() {
        editor.requestFocus()
        editor.setSelection(editor.text!!.length)
        scroller.scrollTo(0, editor.height)
        idle()
        assertTrue("cursor visible to start with", cursorLine().last <= scroller.height)

        resize(scroller.height / 2)

        assertTrue(
            "cursor line ${cursorLine()} within 0..${scroller.height}",
            cursorLine().first >= 0 && cursorLine().last <= scroller.height
        )
    }

    @Test
    fun `without focus nothing scrolls`() {
        editor.setSelection(editor.text!!.length)
        resize(400)
        assertEquals(0, scroller.scrollY)
    }
}
