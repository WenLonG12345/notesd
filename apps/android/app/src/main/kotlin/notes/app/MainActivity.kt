package notes.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import notes.app.notes.NotesNavigation
import notes.app.ui.NotesTheme

class MainActivity : AppCompatActivity() {
    /** "New note" from the launcher shortcut, delivered once to whichever screen is showing. */
    private val newNote = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, theme change) must not open another note.
        if (savedInstanceState == null) handle(intent)
        val graph = (application as NotesApp).graph
        setContent {
            NotesTheme {
                NotesNavigation(graph, newNote.receiveAsFlow())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_NEW_NOTE) newNote.trySend(Unit)
    }

    companion object {
        const val ACTION_NEW_NOTE = "notes.app.action.NEW_NOTE"
    }
}
