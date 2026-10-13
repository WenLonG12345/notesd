package notes.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import notes.app.R
import notes.core.doc.Document
import notes.core.markdown.Markdown
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draws the editor offscreen (real Skia via Robolectric) so markers and margins can be eyeballed. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EditorRenderTest {
    @Test
    fun `render sample note`() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Notes)
        val e = NoteEditText(context)
        val pad = e.editorStyle.dp(20f).toInt()
        e.setPadding(pad, pad, pad, pad)
        val md = File(
            System.getProperty("notes.sharedDir"),
            "../apps/android/app/src/test/resources/sample.md"
        ).readText()
        e.setDocument(Document.forNote("Weekend plan", Markdown.parse(md)))
        val w = 1080
        e.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        e.layout(0, 0, w, e.measuredHeight)
        val bitmap = Bitmap.createBitmap(w, e.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(
            e.editorStyle.tokens.bg.let {
                android.graphics.Color.argb(it.alpha, it.red, it.green, it.blue)
            }
        )
        e.draw(canvas)
        val out = File("build/render/editor.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
