package notes.app.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.NotesField
import notes.app.ui.Sheet
import notes.app.ui.SheetItem

/** Adds or edits a link. [text] is the selected text; empty means a new linked label is inserted. */
@Composable
fun LinkDialog(text: String, href: String?, onDismiss: () -> Unit, onSave: (href: String, label: String) -> Unit) {
    var url by remember { mutableStateOf(href.orEmpty()) }
    var label by remember { mutableStateOf("") }
    val askLabel = text.isEmpty() && href == null
    NotesDialog(onDismiss, if (href == null) "Add link" else "Edit link") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NotesField(
                url,
                { url = it },
                "Address",
                placeholder = "https://",
                mono = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = if (askLabel) ImeAction.Next else ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) onSave(url, label) })
            )
            if (askLabel) {
                NotesField(
                    label,
                    { label = it },
                    "Text",
                    hint = "Optional. The address is shown if left empty.",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (url.isNotBlank()) onSave(url, label) })
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesButton("Cancel", onDismiss, Modifier.weight(1f), kind = ButtonKind.Chip)
            NotesButton("Save", { onSave(url, label) }, Modifier.weight(1f), enabled = url.isNotBlank())
        }
    }
}

/** What a tap on a link offers. */
@Composable
fun LinkSheet(tap: LinkTap, onDismiss: () -> Unit, onOpen: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    Sheet(onDismiss, title = tap.href) {
        Column(Modifier.fillMaxWidth()) {
            SheetItem(Icons.OpenExternal, "Open link", onOpen)
            SheetItem(Icons.Edit, "Edit link", onEdit)
            SheetItem(Icons.Close, "Remove link", onRemove, danger = true)
        }
    }
}
