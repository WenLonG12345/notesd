package notes.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import notes.app.ui.LocalTokens
import notes.core.doc.ListKind

/** The formatting bar above the keyboard. Every button reflects the state at the selection. */
@Composable
fun FormattingBar(state: FormatState, editor: () -> NoteEditText?, onLink: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val body = !state.inTitle
    Column(modifier.fillMaxWidth().background(t.glassHi)) {
        HorizontalDivider(color = t.hair)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp).height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Toggle("Bold", state.bold, body, TextStyle(fontWeight = FontWeight.Bold)) { editor()?.toggleBold() }
            Toggle("Italic", state.italic, body, TextStyle(fontStyle = FontStyle.Italic), label = "I") {
                editor()?.toggleItalic()
            }
            Toggle(
                "Strikethrough",
                state.strike,
                body,
                TextStyle(textDecoration = TextDecoration.LineThrough),
                label = "S"
            ) {
                editor()?.toggleStrike()
            }
            Gap()
            for (level in 1..3) {
                Toggle("Heading $level", state.heading == level, body, label = "H$level") {
                    editor()?.toggleHeading(level)
                }
            }
            Gap()
            Toggle("Bulleted list", state.list == ListKind.Bullet, body, label = "•") {
                editor()?.toggleList(ListKind.Bullet)
            }
            Toggle("Numbered list", state.list == ListKind.Ordered, body, label = "1.") {
                editor()?.toggleList(ListKind.Ordered)
            }
            Toggle("Checklist", state.list == ListKind.Task, body, label = "○") { editor()?.toggleList(ListKind.Task) }
            Toggle("Quote", state.quote, body, label = "“") { editor()?.toggleQuote() }
            Gap()
            Action("Outdent", state.canOutdent, "⇤") { editor()?.outdent() }
            Action("Indent", state.canIndent, "⇥") { editor()?.indent() }
            Gap()
            Toggle("Link", state.link, body, TextStyle(textDecoration = TextDecoration.Underline), label = "Link") {
                onLink()
            }
        }
    }
}

@Composable
private fun Toggle(
    description: String,
    checked: Boolean,
    enabled: Boolean,
    style: TextStyle = TextStyle(),
    label: String = description.take(1),
    onToggle: () -> Unit
) {
    val t = LocalTokens.current
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Box(
            Modifier.background(
                if (checked) t.on else androidx.compose.ui.graphics.Color.Transparent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(label, style = style.merge(TextStyle(fontSize = 16.sp)), color = if (enabled) t.ink else t.ink3)
        }
    }
}

@Composable
private fun Action(description: String, enabled: Boolean, label: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = description
        }
    ) {
        Text(label, fontSize = 18.sp, color = if (enabled) t.ink else t.ink3)
    }
}

@Composable
private fun Gap() = Box(Modifier.width(6.dp))
