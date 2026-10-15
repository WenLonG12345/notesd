package notes.app.notes

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.core.crypto.Keyring
import notes.core.crypto.LockSession
import notes.core.crypto.LockedNotes
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Locked notes (§10.1) on the phone: the prompt, unlocking offline, locking again, removing the lock. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class LockedNoteFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var key: ByteArray

    @Before
    fun seed() {
        graph.setOnboardingDismissed(true)
        // A keyring as sync would have brought it; few iterations keep the test quick.
        val created = Keyring.create(PASSWORD, iterations = 10_000)
        key = created.key
        runBlocking {
            graph.sync.lockKeys.replace(listOf(created.entry))
            graph.locks.refresh()
            graph.store.save("n", "Bank", LockedNotes.encrypt(key, "PIN 1234\n"))
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        graph.database.close()
    }

    private fun openBank() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Locked"))
        compose.onNodeWithText("Bank").performClick()
        compose.waitUntilExactlyOneExists(hasText("This note is locked."))
    }

    private fun unlock(password: String) {
        compose.onNodeWithText("Lock password").performTextInput(password)
        compose.onNode(hasText("Unlock") and hasClickAction()).performClick()
    }

    @Test
    fun `the list shows Locked; the note opens with the lock password, offline`() {
        openBank()
        compose.onNodeWithText("PIN 1234").assertDoesNotExist()

        unlock("wrong one")
        compose.waitUntilExactlyOneExists(hasText("Wrong password."))
        compose.onNodeWithText("Lock password").performTextClearance()
        unlock(PASSWORD)
        compose.waitUntil(5_000) { graph.locks.state.value == LockSession.State.Unlocked }
        compose.waitUntilDoesNotExist(hasText("This note is locked."))
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
    }

    @Test
    fun `Lock now and leaving the app both lock again`() {
        openBank()
        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
        compose.onNodeWithContentDescription("Lock now").performClick()
        compose.waitUntilExactlyOneExists(hasText("This note is locked."))
        assertEquals(LockSession.State.Locked, graph.locks.state.value)

        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
        scenario?.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { graph.locks.state.value == LockSession.State.Locked }
    }

    @Test
    fun `removing the lock stores the text plainly`() {
        openBank()
        unlock(PASSWORD)
        compose.waitUntilExactlyOneExists(hasContentDescription("Lock now"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Remove lock").performClick()
        compose.waitUntil(5_000) { runBlocking { graph.store.get("n")?.content } == "PIN 1234\n" }
        compose.waitUntilDoesNotExist(hasContentDescription("Lock now"))
        assertTrue(runBlocking { graph.store.list().single() }.let { !it.locked && it.preview == "PIN 1234" })
    }

    @Test
    fun `locking another note asks for the existing lock password, not a new one`() {
        runBlocking { graph.store.save("d", "Diary", "Dear diary\n") }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilExactlyOneExists(hasText("Diary"))
        compose.onNodeWithText("Diary").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("More"))
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Lock note").performClick()
        compose.waitUntilExactlyOneExists(hasText("Lock this note"))
        compose.onNodeWithText("Create a lock password").assertDoesNotExist()
        compose.onNodeWithText("Lock password").performTextInput(PASSWORD)
        compose.onNode(hasText("Lock") and hasClickAction()).performClick()
        compose.waitUntil(5_000) {
            runBlocking { graph.store.get("d")?.content }?.let { LockedNotes.isLocked(it) } == true
        }
        assertEquals("Dear diary\n", LockedNotes.decrypt(key, runBlocking { graph.store.get("d")!!.content }))
    }

    private companion object {
        const val PASSWORD = "lock pass 123"
    }
}
