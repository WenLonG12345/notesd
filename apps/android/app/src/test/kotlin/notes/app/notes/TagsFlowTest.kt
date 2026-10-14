package notes.app.notes

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.app.editor.NoteEditText
import notes.core.db.NoteFilter
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
// A phone's height: the tags sit above the notes.
@Config(application = TestNotesApp::class, qualifiers = "w411dp-h891dp")
class TagsFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun launch() {
        graph.setOnboardingDismissed(true)
        runBlocking {
            graph.store.save("a", "Roof", "Call about #home/repairs\n")
            graph.store.save("b", "Garden", "Plant bulbs #home\n")
            graph.store.save("c", "Standup", "Notes #work\n")
            graph.store.save("d", "Loose", "No tags here\n")
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
        compose.waitUntilExactlyOneExists(tag("#work, 1 note"))
    }

    @After
    fun tearDown() {
        scenario.close()
        graph.database.close()
    }

    private fun tag(description: String) = hasContentDescription(description)

    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        compose.onNode(hasScrollAction()).performScrollToNode(matcher)
    }

    @Test
    fun `the list shows tags with counts and filters by one, nested tags included`() {
        compose.waitUntilExactlyOneExists(tag("#home, 2 notes"))
        compose.onNode(tag("#home, 2 notes")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Garden"))
        compose.onNodeWithText("Roof").assertExists()
        compose.onNodeWithText("Garden").assertExists()
        compose.onNodeWithText("Standup").assertDoesNotExist()

        scrollTo(hasText("Untagged"))
        compose.onNodeWithText("Untagged").performClick()
        compose.waitUntilExactlyOneExists(hasText("Loose"))
        compose.onNodeWithText("Roof").assertDoesNotExist()

        compose.onNodeWithContentDescription("Show all notes").performClick()
        compose.waitUntil { graph.listFilter.value == null }
        compose.waitUntilDoesNotExist(hasContentDescription("Show all notes"))
    }

    @Test
    fun `nested tags show under an expanded parent`() {
        compose.waitUntilExactlyOneExists(tag("Expand home"))
        compose.onNode(tag("#home/repairs, 1 note")).assertDoesNotExist()
        compose.onNode(tag("Expand home")).performClick()
        compose.waitUntilExactlyOneExists(tag("#home/repairs, 1 note"))
        assertTrue("home" in graph.tagTree.expanded.value)
    }

    @Test
    fun `a new note in a tag starts with it and is dropped if left blank`() {
        compose.waitUntilExactlyOneExists(tag("#work, 1 note"))
        compose.onNode(tag("#work, 1 note")).performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Show all notes"))

        compose.onNodeWithContentDescription("New note").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        val editor = editor()
        compose.runOnUiThread {
            assertEquals("#work", editor.document().let { notes.core.markdown.Markdown.serialize(it).trim() })
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Standup"))
        assertEquals(4, runBlocking { graph.store.list().size })

        compose.onNodeWithContentDescription("New note").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Back"))
        val second = editor()
        compose.runOnUiThread { second.text!!.insert(0, "Retro") }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Retro"))
        val work = runBlocking { graph.store.list(NoteFilter.Tag("work", "work")) }
        assertEquals(setOf("Retro", "Standup"), work.map { it.title }.toSet())
    }

    private fun editor(): NoteEditText {
        fun find(v: View): NoteEditText? =
            v as? NoteEditText ?: (v as? ViewGroup)?.children?.firstNotNullOfOrNull(::find)
        repeat(50) {
            compose.waitForIdle()
            find(activity.window.decorView)?.let { return it }
            Thread.sleep(100)
        }
        error("The editor never appeared")
    }
}
