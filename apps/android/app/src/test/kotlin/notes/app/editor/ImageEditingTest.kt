package notes.app.editor

import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import notes.app.R
import notes.core.doc.Document
import notes.core.images.ImageRef
import notes.core.markdown.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Pictures in the editor (architecture §7.6): image runs drawn as pictures, atomic, kept as their source. */
@RunWith(AndroidJUnit4::class)
class ImageEditingTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)
    private val a = ImageRef("a".repeat(64), 1600, 1200)
    private val b = ImageRef("b".repeat(64), 1600, 1200)

    private fun editor(body: String): NoteEditText {
        val e = NoteEditText(context)
        e.setDocument(Document.forNote("T", Markdown.parse(body)))
        e.setSelection(e.text!!.length)
        return e
    }

    private fun NoteEditText.body() = Markdown.serialize(document())

    private fun NoteEditText.pictures(): List<String> {
        val t = text!!
        return t.getSpans(0, t.length, ImageSpan::class.java).sortedBy { t.getSpanStart(it) }.map { it.ref.id }
    }

    private fun NoteEditText.backspace() {
        onKeyDown(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        onKeyUp(KeyEvent.KEYCODE_DEL, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `an image run is drawn as a picture and saved as written`() {
        val body = "Before\n\n${a.markdown("a dot")}\n\nAfter\n"
        val e = editor(body)
        assertEquals(listOf(a.id), e.pictures())
        assertEquals(setOf(a.id), e.imageIds())
        assertEquals(body, e.body())
        // TalkBack reads the alt text, not the Markdown.
        val t = e.text!!
        val said = t.getSpans(0, t.length, ImageDescription::class.java).single()
        assertEquals("Picture: a dot", said.args.getString(android.text.style.TtsSpan.ARG_TEXT))
    }

    @Test
    fun `other images and code stay plain raw runs`() {
        val e = editor("![a](cat.png) and `${a.markdown()}`\n")
        assertEquals(emptyList<String>(), e.pictures())
        assertEquals(emptySet<String>(), e.imageIds())
    }

    @Test
    fun `an added picture goes on its own line after the cursor's line`() {
        val e = editor("Hello\n\nLater\n")
        e.setSelection(e.text.toString().indexOf("Hello") + 2)
        e.insertImage(a.markdown())
        assertEquals("Hello\n\n${a.markdown()}\n\nLater\n", e.body())
        assertEquals(listOf(a.id), e.pictures())
    }

    @Test
    fun `an added picture fills the empty line the cursor is on`() {
        val e = editor("Hello\n")
        e.text!!.insert(e.text!!.length, "\n")
        shadowOf(Looper.getMainLooper()).idle()
        e.insertImage(a.markdown())
        assertEquals("Hello\n\n${a.markdown()}\n", e.body())
    }

    @Test
    fun `a picture added in the title goes first in the body`() {
        val e = editor("Body\n")
        e.setSelection(1)
        e.insertImage(a.markdown())
        assertEquals("${a.markdown()}\n\nBody\n", e.body())
        assertEquals("T", e.document().title)
    }

    @Test
    fun `backspace after a picture deletes all of it`() {
        val e = editor("Before\n\n${a.markdown()}\n")
        e.backspace()
        assertFalse(e.body().contains("notesd:image"))
        assertEquals(emptyList<String>(), e.pictures())
    }

    @Test
    fun `a lock change points the runs at the moved images and leaves the rest`() {
        val e = editor("One ${a.markdown("x")} two\n\n${a.markdown()}\n\n${b.markdown()}\n")
        e.replaceImages(mapOf(a.id to "c".repeat(64)))
        val c = ImageRef("c".repeat(64), 1600, 1200)
        assertEquals("One ${c.markdown("x")} two\n\n${c.markdown()}\n\n${b.markdown()}\n", e.body())
        assertEquals(listOf(c.id, c.id, b.id), e.pictures())
    }
}
