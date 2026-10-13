package notes.app.editor

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
import java.io.File
import kotlinx.coroutines.delay
import notes.app.ui.LocalTokens
import notes.core.doc.Document
import notes.core.markdown.Markdown
import org.json.JSONObject

/**
 * The editor prototype (roadmap step 1): one note, saved to a local file 750 ms
 * after the last change and on stop, reopened from that file on demand. Stands
 * in for the notes database until the store exists.
 */
@Composable
fun EditorDebugScreen() {
    val context = LocalContext.current
    val t = LocalTokens.current
    val store = remember { PrototypeStore(File(context.filesDir, "prototype-note.json")) }
    var editor by remember { mutableStateOf<NoteEditText?>(null) }
    var state by remember { mutableStateOf(FormatState()) }
    var edits by remember { mutableIntStateOf(0) }
    var saved by remember { mutableIntStateOf(0) }
    var linkTap by remember { mutableStateOf<LinkTap?>(null) }
    var linkDialog by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var markdown by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    fun save() {
        val e = editor ?: return
        if (saved == edits) return
        store.save(e.document())
        saved = edits
    }

    LaunchedEffect(edits) {
        if (edits == saved) return@LaunchedEffect
        delay(750)
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
            IconButton(
                onClick = { editor?.undo() },
                enabled = state.canUndo,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Undo"
                }
            ) {
                Text("↶", fontSize = 20.sp, color = if (state.canUndo) t.ink else t.ink3)
            }
            IconButton(
                onClick = { editor?.redo() },
                enabled = state.canRedo,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Redo"
                }
            ) {
                Text("↷", fontSize = 20.sp, color = if (state.canRedo) t.ink else t.ink3)
            }
            Spacer(Modifier.weight(1f))
            val synced = saved == edits
            Box(Modifier.size(7.dp).background(if (synced) t.statusSaved else t.statusUnsaved, CircleShape))
            Text(
                if (synced) "Saved locally" else "Unsaved",
                fontSize = 13.sp,
                color = t.ink2,
                modifier = Modifier.padding(start = 6.dp, end = 4.dp)
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = "More" }) {
                    Text("⋯", fontSize = 20.sp, color = t.ink)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Reopen saved note") }, onClick = {
                        menu = false
                        save()
                        editor?.setDocument(store.load() ?: Document.forNote("", Document.EMPTY))
                    })
                    DropdownMenuItem(text = { Text("Load sample note") }, onClick = {
                        menu = false
                        editor?.setDocument(Document.forNote(SAMPLE_TITLE, Markdown.parse(SAMPLE)))
                        edits++
                    })
                    DropdownMenuItem(text = { Text("Show Markdown") }, onClick = {
                        menu = false
                        markdown = editor?.document()?.let { "# ${it.title}\n\n" + Markdown.serialize(it) }
                    })
                }
            }
        }
        AndroidView(
            factory = { ctx ->
                NoteEditText(ctx).apply {
                    val pad = editorStyle.dp(20f).toInt()
                    setPadding(pad, editorStyle.dp(8f).toInt(), pad, pad)
                    hint = "New note"
                    store.load()?.let { setDocument(it) }
                    onChange = { edits++ }
                    onFormatState = { state = it }
                    onLinkTap = { linkTap = it }
                    editor = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        FormattingBar(state, { editor }, onLink = { linkDialog = editor?.linkContext() })
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

private fun open(context: Context, href: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, href.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; the sheet already showed the address.
    }
}

/** One note in a JSON file: the title and the canonical Markdown, as the server would store them. */
private class PrototypeStore(private val file: File) {
    fun save(doc: Document) {
        val json = JSONObject().put("title", doc.title.orEmpty()).put("content", Markdown.serialize(doc))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    fun load(): Document? {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        return Document.forNote(json.optString("title"), Markdown.parse(json.optString("content")))
    }
}

private const val SAMPLE_TITLE = "Weekend plan"

private val SAMPLE = """
    |# Saturday
    |
    |Pick up the **bike** from the shop, then *maybe* the ~~market~~ park.
    |
    |- Groceries
    |  - Bread
    |  - Coffee beans
    |    - [the good ones](https://example.com/coffee)
    |- Laundry
    |
    |## Checklist
    |
    |- [x] Book the table
    |- [ ] Call Sam
    |  - [ ] Ask about Sunday
    |
    |1. Leave at 9
    |2. Lunch by the river
    |
    |> Rest is part of the plan.
    |
    |```sh
    |rsync -av ~/photos /backup
    |```
    |
    |Run `make deploy` when back.
""".trimMargin() + "\n"
