package notes.app.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import notes.app.ui.ButtonKind
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Type
import notes.core.sync.EmailOutcome

/** Addresses for the note, then Send; [send] syncs the note and asks the server to email it. */
@Composable
fun EmailDialog(from: String?, onDismiss: () -> Unit, send: suspend (List<String>) -> EmailOutcome) {
    val t = LocalTokens.current
    val scope = rememberCoroutineScope()
    var to by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<List<String>?>(null) }

    fun submit() {
        val recipients = to.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (recipients.isEmpty()) {
            error = "Add at least one address."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                when (val r = send(recipients)) {
                    EmailOutcome.Sent -> sentTo = recipients

                    EmailOutcome.Offline ->
                        error =
                            "Can't reach the server. Email sends from there; try again when online."

                    EmailOutcome.NotSynced ->
                        error =
                            "This note hasn't reached the server yet. Try again once it's synced."

                    is EmailOutcome.Failed -> error = r.message
                }
            } finally {
                busy = false
            }
        }
    }

    val sent = sentTo
    if (sent != null) {
        NotesDialog(onDismiss, "Sent") {
            Text(
                "Sent to ${sent.joinToString(", ")}.",
                style = Type.body,
                color = t.ink2,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Spacer(Modifier.height(24.dp))
            NotesButton("Done", onDismiss, Modifier.fillMaxWidth())
        }
        return
    }
    NotesDialog({ if (!busy) onDismiss() }, "Email this note") {
        NotesField(
            value = to,
            onValueChange = {
                to = it
                error = null
            },
            label = "To",
            placeholder = "name@example.com",
            error = error,
            hint = listOfNotNull(from?.let { "From $it." }, "Separate addresses with commas.").joinToString(" "),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!busy) submit() })
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip, enabled = !busy)
            NotesButton(if (busy) "Sending…" else "Send", ::submit, Modifier.weight(1f), busy = busy)
        }
    }
}
