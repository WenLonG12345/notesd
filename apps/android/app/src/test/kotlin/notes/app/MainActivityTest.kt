package notes.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.children
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import notes.app.editor.NoteEditText
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = (compose.activity.application as NotesApp).graph

    @After
    fun closeDatabase() = graph.database.close()

    @Test
    fun `a new note left blank leaves nothing behind`() {
        compose.waitUntilExactlyOneExists(hasText("No notes yet", substring = true))
        compose.onNodeWithText("New note").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes yet", substring = true))
    }

    @Test
    fun `typing saves the note and it appears in the list`() {
        compose.onNodeWithText("New note").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        val editor = editor()
        compose.runOnUiThread { editor.text!!.insert(0, "Packing list") }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Packing list"))
        compose.onNodeWithText("Packing list").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        val reopened = editor()
        compose.runOnUiThread { assertEquals("Packing list", reopened.document().title) }
    }

    /** The editor view, once the note has loaded. */
    private fun editor(): NoteEditText {
        fun find(v: View): NoteEditText? =
            v as? NoteEditText ?: (v as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
        compose.waitUntil { find(compose.activity.window.decorView) != null }
        return find(compose.activity.window.decorView)!!
    }

    @Test
    fun `stored notes are listed and searchable`() {
        runBlocking {
            graph.store.save("a", "Groceries", "- Oat milk\n")
            graph.store.save("b", "Trip", "Lisbon\n")
        }
        compose.waitUntilExactlyOneExists(hasText("Groceries"))
        compose.onNodeWithText("Oat milk").assertExists()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Search").performTextInput("lisb")
        compose.waitUntilDoesNotExist(hasText("Groceries"))
        compose.onNodeWithText("Trip").assertExists()
    }
}
