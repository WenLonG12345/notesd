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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.BuildConfig
import notes.app.ui.LocalTokens
import notes.core.db.NoteStore

/** Settings. Server and account arrive with sign-in; until then this device is the only copy. */
@Composable
fun SettingsScreen(appearance: Appearance, store: NoteStore, onBack: () -> Unit) {
    val t = LocalTokens.current
    val theme by appearance.theme.collectAsStateWithLifecycle()
    var confirmWipe by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                modifier = Modifier.semantics {
                    heading()
                }
            )
        }

        Section("Appearance")
        Column(Modifier.selectableGroup()) {
            for (option in Theme.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = theme == option, role = Role.RadioButton) { appearance.set(option) }
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

        Section("Account")
        Line("Not signed in", "Notes are kept on this device only.")

        Section("Data")
        Text(
            "Remove all data from this device",
            color = t.danger,
            fontSize = 16.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { confirmWipe = true }
                .padding(horizontal = 20.dp, vertical = 14.dp)
        )

        Section("About")
        Line("Version", BuildConfig.VERSION_NAME)
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Remove all data?") },
            text = {
                Text("Every note on this device will be deleted. Notes that only exist here can't be recovered.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch { store.wipe() }
                }) { Text("Remove", color = t.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } }
        )
    }
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
