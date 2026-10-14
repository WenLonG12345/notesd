package notes.app.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import notes.app.ui.NotesTheme
import notes.core.doc.ListKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormattingBarTest {
    @get:Rule
    val compose = createComposeRule()

    private val writing = listOf(
        "Heading 1",
        "Heading 2",
        "Heading 3",
        "Bulleted list",
        "Numbered list",
        "Checklist",
        "Quote"
    )
    private val selecting = listOf("Bold", "Italic", "Strikethrough", "Link")

    @Test
    fun `writing shows blocks, selecting shows marks and link`() {
        var state by mutableStateOf(FormatState(inTitle = false))
        compose.setContent { NotesTheme(dark = false) { FormattingBar(state, { null }, onLink = {}) } }

        writing.forEach { compose.onNodeWithContentDescription(it).assertExists() }
        selecting.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }

        state = state.copy(selecting = true)
        compose.waitForIdle()
        selecting.forEach { compose.onNodeWithContentDescription(it).assertExists() }
        writing.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
    }

    @Test
    fun `indent and outdent appear only where they apply`() {
        var state by mutableStateOf(FormatState(inTitle = false))
        compose.setContent { NotesTheme(dark = false) { FormattingBar(state, { null }, onLink = {}) } }
        compose.onNodeWithContentDescription("Indent").assertDoesNotExist()
        compose.onNodeWithContentDescription("Outdent").assertDoesNotExist()

        state = state.copy(list = ListKind.Bullet, canOutdent = true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Indent").assertExists()
        compose.onNodeWithContentDescription("Outdent").assertExists()
    }
}
