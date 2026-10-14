package notes.app.editor

import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import notes.app.R
import notes.core.db.TagCount
import notes.core.doc.Document
import notes.core.markdown.Markdown
import notes.core.text.tags
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class TagEditingTest {
    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)

    private fun editor(title: String, body: String): NoteEditText {
        val e = NoteEditText(context)
        e.setDocument(Document.forNote(title, Markdown.parse(body)))
        return e
    }

    private fun NoteEditText.tagged(): List<String> {
        val t = text!!
        return t.getSpans(0, t.length, TagSpan::class.java)
            .sortedBy { t.getSpanStart(it) }
            .map { t.substring(t.getSpanStart(it), t.getSpanEnd(it)) }
    }

    private fun NoteEditText.type(s: String) {
        text!!.replace(selectionStart, selectionEnd, s)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `styled tags match the shared extractor on what the editor saves`() {
        val dir = System.getProperty("notes.sharedDir") ?: error("notes.sharedDir not set (run through Gradle)")
        val cases = JSONObject(File(dir, "markdown-fixtures/tags.json").readText()).getJSONArray("cases")
        val failures = (0 until cases.length()).map { cases.getJSONObject(it) }.mapNotNull { c ->
            val e = editor(c.optString("title"), c.getString("markdown"))
            val doc = e.document()
            val saved = tags(doc.title.orEmpty(), Markdown.serialize(doc)).map { it.key }
            val styled = e.text!!.let { t ->
                t.getSpans(0, t.length, TagSpan::class.java).map { it.name.lowercase() }.distinct()
            }
            if (styled.toSet() == saved.toSet()) null else "${c.getString("name")}: styled $styled, saved $saved"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `tags are styled as typed and never reach the document`() {
        val e = editor("Trip", "Book #travel and [#link](https://x.y) now\n")
        assertEquals(listOf("#travel"), e.tagged())
        e.setSelection(e.text!!.length)
        e.type(" #summer trip#")
        assertEquals(listOf("#travel", "#summer trip#"), e.tagged())
        assertEquals("Book #travel and [#link](https://x.y) now #summer trip#\n", Markdown.serialize(e.document()))
    }

    @Test
    fun `the cursor in a tag reports it, with a query only while completing`() {
        val e = editor("T", "See #home/re\n")
        var state = FormatState()
        e.onFormatState = { state = it }
        val text = e.text.toString()
        e.setSelection(text.indexOf("/re") + 3)
        assertEquals(TagAtCursor("home/re", text.indexOf('#'), text.indexOf("/re") + 3, "home/re"), state.tag)
        e.setSelection(text.indexOf('#') + 2)
        assertNull(state.tag?.query)
        e.setSelection(0)
        assertNull(state.tag)
    }

    @Test
    fun `picking a suggestion writes the tag, closed if needed, and a space`() {
        val e = editor("T", "Plan #sum\n")
        val text = e.text.toString()
        e.setSelection(text.indexOf("#sum") + 4)
        e.insertTag(text.indexOf("#sum"), text.indexOf("#sum") + 4, "summer trip")
        assertEquals("T\nPlan #summer trip# ", e.text.toString().trimEnd('\u200B', '\n'))
        assertEquals(e.text.toString().indexOf("# ") + 2, e.selectionStart)
    }

    @Test
    fun `suggestions rank whole-name prefixes first and skip the tag already typed`() {
        val all = listOf(
            TagCount("home", "Home", 3),
            TagCount("home/repairs", "Home/Repairs", 1),
            TagCount("work", "Work", 5),
            TagCount("homework", "homework", 1)
        )
        assertEquals(listOf("home", "home/repairs", "homework"), suggestTags(all, "ho").map { it.key })
        assertEquals(listOf("home/repairs"), suggestTags(all, "rep").map { it.key })
        assertEquals(listOf("homework"), suggestTags(all, "work").map { it.key })
        assertEquals(emptyList<TagCount>(), suggestTags(all, ""))
    }
}
