package notes.app.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import notes.app.ui.ButtonKind
import notes.app.ui.Heading
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Type

/** The note pane with no note open, beside the list (web: EmptyPane). */
@Composable
fun NothingOpen(hasNotes: Boolean, onNew: () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxSize().background(t.bg), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 380.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (hasNotes) {
                Kicker("Nothing selected")
                Heading("Pick a note, or start one.", Modifier, Type.heading.copy(textAlign = TextAlign.Center))
            } else {
                // Lone kicker names the pane for screen readers.
                Kicker("No notes yet", Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(6.dp))
            NotesButton("Write a note", onNew, kind = ButtonKind.Solid, icon = Icons.Compose)
        }
    }
}
