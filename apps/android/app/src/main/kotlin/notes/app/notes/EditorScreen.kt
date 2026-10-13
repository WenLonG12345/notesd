package notes.app.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import notes.app.BuildConfig
import notes.app.editor.FormatState
import notes.app.editor.FormattingBar
import notes.app.editor.LinkDialog
import notes.app.editor.LinkSheet
import notes.app.editor.LinkTap
import notes.app.editor.NoteEditText
import notes.app.editor.SAMPLE_MARKDOWN
import notes.app.editor.SAMPLE_TITLE
import notes.app.ui.LocalTokens
import notes.core.doc.Document
import notes.core.markdown.Markdown

private const val SAVE_DELAY_MS = 700L

/** A note, full screen. Saves locally shortly after typing stops, and at once on leaving or backgrounding. */
@Composable
fun EditorScreen(vm: EditorViewModel, onBack: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val t = LocalTokens.current
    val loaded by vm.document.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<NoteEditText?>(null) }
    var state by remember { mutableStateOf(FormatState()) }
    var edits by remember { mutableIntStateOf(0) }
    var linkTap by remember { mutableStateOf<LinkTap?>(null) }
    var linkDialog by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var markdown by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    fun save() {
        editor?.let { vm.save(it.document()) }
    }

    LaunchedEffect(edits) {
        if (edits == 0) return@LaunchedEffect
        delay(SAVE_DELAY_MS)
        save()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) save() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            save()
        }
    }

    Column(Modifier.fillMaxSize().background(t.bg).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton("←", "Back", t.ink, onClick = onBack)
            GlyphButton("↶", "Undo", if (state.canUndo) t.ink else t.ink3, enabled = state.canUndo) { editor?.undo() }
            GlyphButton("↷", "Redo", if (state.canRedo) t.ink else t.ink3, enabled = state.canRedo) { editor?.redo() }
            Spacer(Modifier.weight(1f))
            if (status != SaveStatus.Blank) {
                val saved = status == SaveStatus.Saved
                Box(Modifier.size(7.dp).background(if (saved) t.statusSaved else t.statusUnsaved, CircleShape))
                Text(
                    if (saved) "Saved" else "Editing",
                    fontSize = 13.sp,
                    color = t.ink2,
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp)
                )
            }
            Box {
                GlyphButton("⋯", "More", t.ink) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                        menu = false
                        editor?.document()?.let { share(context, it) }
                    })
                    // A blank new note has nothing to delete: leaving discards it.
                    if (status != SaveStatus.Blank) {
                        DropdownMenuItem(text = { Text("Delete note", color = t.danger) }, onClick = {
                            menu = false
                            save()
                            onDelete()
                        })
                    }
                    if (BuildConfig.DEBUG) {
                        DropdownMenuItem(text = { Text("Show Markdown") }, onClick = {
                            menu = false
                            markdown = editor?.document()?.let(::exportMarkdown)
                        })
                        DropdownMenuItem(text = { Text("Load sample note") }, onClick = {
                            menu = false
                            editor?.setDocument(Document.forNote(SAMPLE_TITLE, Markdown.parse(SAMPLE_MARKDOWN)))
                            vm.edited()
                            edits++
                        })
                    }
                }
            }
        }
        val doc = loaded
        if (doc != null) {
            AndroidView(
                factory = { ctx ->
                    NoteEditText(ctx).apply {
                        val pad = editorStyle.dp(20f).toInt()
                        setPadding(pad, editorStyle.dp(8f).toInt(), pad, pad)
                        hint = "New note"
                        setDocument(doc)
                        onChange = {
                            vm.edited()
                            edits++
                        }
                        onFormatState = { state = it }
                        onLinkTap = { linkTap = it }
                        editor = this
                        if (text.isNullOrEmpty()) requestFocus()
                    }
                },
                onRelease = { editor = null },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            FormattingBar(state, { editor }, onLink = { linkDialog = editor?.linkContext() })
        }
    }

    linkTap?.let { tap ->
        LinkSheet(
            tap,
            onDismiss = { linkTap = null },
            onOpen = {
                linkTap = null
                open(context, tap.href)
            },
            onEdit = {
                linkTap = null
                editor?.setSelection(tap.start, tap.end)
                linkDialog = editor?.linkContext()
            },
            onRemove = {
                linkTap = null
                editor?.removeLink(tap.start, tap.end)
            }
        )
    }
    linkDialog?.let { (text, href) ->
        LinkDialog(text, href, onDismiss = { linkDialog = null }) { url, label ->
            linkDialog = null
            editor?.setLink(url, label)
        }
    }
    markdown?.let { md ->
        AlertDialog(
            onDismissRequest = { markdown = null },
            confirmButton = { TextButton(onClick = { markdown = null }) { Text("Close") } },
            text = {
                Text(
                    md,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        )
    }
}

@Composable
private fun GlyphButton(glyph: String, label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Text(glyph, fontSize = 20.sp, color = color)
    }
}

/** The note as one Markdown file: the title as a heading, then the canonical body. */
private fun exportMarkdown(doc: Document): String {
    val title = doc.title.orEmpty().trim()
    val body = Markdown.serialize(doc)
    return if (title.isEmpty()) body else "# $title\n\n$body"
}

private fun share(context: Context, doc: Document) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, doc.title.orEmpty())
        .putExtra(Intent.EXTRA_TEXT, exportMarkdown(doc))
    context.startActivity(Intent.createChooser(send, null))
}

private fun open(context: Context, href: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, href.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; the sheet already showed the address.
    }
}
