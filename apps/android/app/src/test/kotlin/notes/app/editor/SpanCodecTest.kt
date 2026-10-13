package notes.app.editor

import android.text.SpannableStringBuilder
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import notes.app.R
import notes.core.doc.Document
import notes.core.markdown.Markdown
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpanCodecTest {
    private val style = EditorStyle.from(
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)
    )

    @Test
    fun `fixtures survive parse, spans and back`() {
        val failures = fixtures("roundtrip.json").mapNotNull { (name, md) ->
            val doc = Document.forNote("A title", Markdown.parse(md))
            val back = roundTrip(doc)
            val out = Markdown.serialize(back)
            when {
                out != md -> "$name: serialized ${out.quote()} expected ${md.quote()}"
                back != doc -> "$name: $back expected $doc"
                back.title != "A title" -> "$name: title ${back.title}"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `any Markdown survives the spans`() {
        val failures = fixtures("plaintext.json").mapNotNull { (name, md) ->
            val doc = Document.forNote("", Markdown.parse(md))
            val back = roundTrip(doc)
            if (back == doc) null else "$name: $back expected $doc"
        }
        assertEquals(emptyList<String>(), failures)
    }

    private fun roundTrip(doc: Document): Document {
        val text: SpannableStringBuilder = SpanCodec.toSpannable(doc, style)
        Normalizer(style).normalize(text)
        return SpanCodec.toDocument(text)
    }
}

internal fun fixtures(file: String): List<Pair<String, String>> {
    val dir = System.getProperty("notes.sharedDir") ?: error("notes.sharedDir not set (run through Gradle)")
    val cases = JSONObject(File(dir, "markdown-fixtures/$file").readText()).getJSONArray("cases")
    return (0 until cases.length()).map {
        cases.getJSONObject(it).let { c ->
            c.getString("name") to
                c.getString("markdown")
        }
    }
}

internal fun String.quote() = "\"" + replace("\n", "\\n") + "\""
