package notes.app.notes

import android.content.Intent
import android.content.pm.LabeledIntent
import android.os.Build
import android.service.chooser.ChooserAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.app.MainActivity
import notes.app.NotesApp
import notes.app.TestNotesApp
import notes.core.api.Session
import notes.core.api.SignIn
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = TestNotesApp::class)
class EmailTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val graph = ApplicationProvider.getApplicationContext<NotesApp>().graph
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = MockWebServer()

    @After
    fun tearDown() {
        scenario?.close()
        graph.database.close()
        server.close()
    }

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction()).performScrollTo()

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `the share sheet gets an Email action that comes back to the note`() {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND), null)
        addEmailAction(ApplicationProvider.getApplicationContext(), chooser, "n1")
        @Suppress("DEPRECATION")
        val actions = chooser.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS)!!
        assertEquals(listOf("Email"), actions.map { (it as ChooserAction).label.toString() })
        assertNull(chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `before custom actions, Email is a target of its own`() {
        val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND), null)
        addEmailAction(ApplicationProvider.getApplicationContext(), chooser, "n1")
        @Suppress("DEPRECATION")
        val target = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)!!.single() as LabeledIntent
        assertEquals("Email", target.nonLocalizedLabel)
        assertEquals(MainActivity.ACTION_EMAIL_NOTE, target.action)
        assertEquals("n1", target.getStringExtra(MainActivity.EXTRA_NOTE_ID))
    }

    @Test
    fun `choosing Email opens the note and asks where to send it`() {
        graph.setOnboardingDismissed(true)
        runBlocking { graph.store.save("n1", "Plans", "Bike\n") }
        scenario = ActivityScenario.launch(emailIntent(ApplicationProvider.getApplicationContext(), "n1"))
        compose.waitUntilExactlyOneExists(hasText("Email this note"))
        compose.onNodeWithText("To").performTextInput("sam@example.org")
        compose.onNode(hasText("Send") and hasClickAction()).performClick()
        // Not connected to a server: nothing can send it.
        compose.waitUntilExactlyOneExists(
            hasText("This note hasn't reached the server yet. Try again once it's synced.")
        )
    }

    @Test
    fun `settings save the Resend key and sender, and remove them`() {
        var settings = """{"configured":false,"from":"","keyHint":""}"""
        val puts = ArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath != "/api/v1/settings/email") return MockResponse.Builder().code(404).build()
                when (request.method) {
                    "PUT" -> {
                        puts += request.body!!.utf8()
                        settings = """{"configured":true,"from":"Me <notes@example.com>","keyHint":"6789"}"""
                    }

                    "DELETE" -> {
                        settings = """{"configured":false,"from":"","keyHint":""}"""
                        return MockResponse.Builder().code(204).build()
                    }
                }
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(settings)
                    .build()
            }
        }
        server.start()
        graph.setOnboardingDismissed(true)
        runBlocking {
            graph.accounts.signedIn(
                server.url("/").toString(),
                SignIn(Session("s", "device", "P", 1, 1), "me", "a", Long.MAX_VALUE, "r", Long.MAX_VALUE)
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNode(hasContentDescription("Sidebar")).performClick()
        compose.onNode(hasContentDescription("Settings")).performClick()
        compose.waitUntilExactlyOneExists(hasText("Integrations"))
        compose.onNodeWithText("Integrations").performClick()
        compose.waitUntilExactlyOneExists(hasText("Resend API key"))

        button("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("Paste your Resend API key."))

        compose.onNodeWithText("Resend API key").performTextInput("re_123456789")
        compose.onNodeWithText("From").performTextInput("Me <notes@example.com>")
        button("Save").performClick()
        compose.waitUntilExactlyOneExists(hasText("Saved. Share a note and choose Email."))
        compose.waitUntilExactlyOneExists(hasText("Saved · …6789"))
        assertEquals(1, puts.size)
        assertTrue(
            "\"apiKey\":\"re_123456789\"" in puts[0] && "\"from\":\"Me <notes@example.com>\"" in puts[0],
            puts[0]
        )
        assertEquals("Me <notes@example.com>", graph.email.settings.value?.from)

        button("Remove").performClick()
        compose.waitUntilDoesNotExist(hasText("Remove") and hasClickAction())
        assertEquals(false, graph.email.settings.value?.configured)
    }
}
