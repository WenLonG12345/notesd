package notes.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.BuildConfig
import notes.app.account.Account
import notes.app.ui.LocalTokens

private sealed interface Confirm {
    /** [pending]: notes with changes the server hasn't received. */
    data class SignOut(val pending: Int) : Confirm

    data object RemoveAll : Confirm
}

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit, onConnect: () -> Unit, onSignIn: () -> Unit) {
    val t = LocalTokens.current
    val theme by graph.appearance.theme.collectAsStateWithLifecycle()
    val account by graph.accounts.account.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun act(block: suspend () -> Unit) {
        busy = true
        // The app scope: leaving the screen mustn't cancel a sign-out halfway.
        graph.scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Text("←", fontSize = 20.sp, color = t.ink)
            }
            Text(
                "Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.ink,
                modifier = Modifier.semantics { heading() }
            )
        }

        Section("Account")
        when (val a = account) {
            Account.None -> {
                Line("Not connected", "Notes are kept on this phone only.")
                Action("Connect to a server", t.link, enabled = true, onClick = onConnect)
            }

            is Account.SignedIn -> {
                Line("Server", a.server)
                Line("Username", a.username)
                Action(if (busy) "Signing out…" else "Sign out", t.link, enabled = !busy) {
                    scope.launch { confirm = Confirm.SignOut(graph.store.pendingNotes()) }
                }
            }

            is Account.SignedOut -> {
                Line("Server", a.server)
                Line("Username", "${a.username} · signed out")
                Action("Sign in again", t.link, enabled = true, onClick = onSignIn)
            }
        }

        Section("Appearance")
        Column(Modifier.selectableGroup()) {
            for (option in Theme.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = theme == option, role = Role.RadioButton) {
                            graph.appearance.set(option)
                        }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(option.label, color = t.ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    RadioButton(
                        selected = theme == option,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = t.accentDeep, unselectedColor = t.ink3)
                    )
                }
            }
        }

        Section("Data")
        Action("Remove all data from this device", t.danger, enabled = !busy) { confirm = Confirm.RemoveAll }

        Section("About")
        Line("Version", BuildConfig.VERSION_NAME)
    }

    when (val c = confirm) {
        is Confirm.SignOut -> ConfirmDialog(
            title = "Sign out?",
            text = if (c.pending == 0) {
                "Your notes stay on this phone."
            } else {
                "${c.pending} ${if (c.pending == 1) "note has" else "notes have"} changes the server hasn't " +
                    "received yet. They stay on this phone and are sent when you sign in again."
            },
            action = "Sign out",
            onDismiss = { confirm = null }
        ) { act(graph::signOut) }

        Confirm.RemoveAll -> ConfirmDialog(
            title = "Remove all data?",
            text = "Every note on this phone is deleted and you're signed out. Notes the server doesn't have " +
                "can't be recovered.",
            action = "Remove",
            danger = true,
            onDismiss = { confirm = null }
        ) { act(graph::removeAllData) }

        null -> Unit
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    action: String,
    danger: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val t = LocalTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(action, color = if (danger) t.danger else t.link) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Section(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalTokens.current.ink3,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp).semantics { heading() }
    )
}

@Composable
private fun Line(label: String, value: String) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(label, color = t.ink, fontSize = 16.sp)
        Text(value, color = t.ink2, fontSize = 14.sp)
    }
}

@Composable
private fun Action(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) color else LocalTokens.current.ink3,
        fontSize = 16.sp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    )
}
