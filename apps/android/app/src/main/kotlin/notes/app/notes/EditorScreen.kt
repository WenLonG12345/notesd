package notes.app.notes

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.widget.NestedScrollView
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
import notes.app.editor.TagStrip
import notes.app.ui.Banner
import notes.app.ui.BannerKind
import notes.app.ui.IconAction
import notes.app.ui.Icons
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.NotesDialog
import notes.app.ui.Reveal
import notes.app.ui.Sheet
import notes.app.ui.SheetItem
import notes.app.ui.StatusPill
import notes.app.ui.Type
import notes.app.ui.hairline
import notes.app.ui.pressScale
import notes.app.ui.windowInsetsController
import notes.core.doc.Document
import notes.core.markdown.Markdown

private const val SAVE_DELAY_MS = 700L

/**
 * A note. Saves locally shortly after typing stops, and at once on leaving or
 * backgrounding. [showBack] is false beside the list (two panes), where
 * there's nothing to go back to. [connected]: a server exists, so "Saved"
 * means "not yet synced".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenTag: (String) -> Unit = {},
    showBack: Boolean = true,
    connected: Boolean = true
) {
    val context = LocalContext.current
    val t = LocalTokens.current
    val loaded by vm.document.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val original by vm.original.collectAsStateWithLifecycle()
    val gone by vm.gone.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<NoteEditText?>(null) }
    var state by remember { mutableStateOf(FormatState()) }
    var edits by remember { mutableIntStateOf(0) }
    var scrolled by remember { mutableStateOf(false) }
    var linkTap by remember { mutableStateOf<LinkTap?>(null) }
    var linkDialog by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var markdown by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    fun save() {
        editor?.let { vm.save(it.document()) }
    }

    LaunchedEffect(gone) { if (gone) onBack() }
    LaunchedEffect(vm) { vm.incoming.collect { editor?.setDocument(it, keepSelection = true) } }
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

    val typing = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding().imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .then(if (scrolled) Modifier.hairline(t.hair) else Modifier)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) BackLink(onBack) else Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (status != SaveStatus.Blank) {
                    val (word, dot, ink) = when (status) {
                        SaveStatus.Synced -> Triple("Synced", t.statusSaved, t.statusSavedInk)

                        SaveStatus.Saved -> if (connected) {
                            Triple("Saved", t.statusSaving, t.statusSavingInk)
                        } else {
                            Triple("Saved", t.statusSaved, t.statusSavedInk)
                        }

                        else -> Triple("Editing", t.statusUnsaved, t.statusUnsavedInk)
                    }
                    AnimatedContent(word, transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    }, label = "status") {
                        StatusPill(it, dot, ink)
                    }
                }
            }
            IconAction(Icons.Undo, "Undo", { editor?.undo() }, enabled = state.canUndo)
            IconAction(Icons.Redo, "Redo", { editor?.redo() }, enabled = state.canRedo)
            IconAction(Icons.More, "More", { menu = true })
        }
        original?.let { o ->
            Banner(
                "Kept from a conflict with “${o.title.ifBlank { "New note" }}”",
                BannerKind.Warn,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                action = "Open",
                onAction = { onOpen(o.id) }
            )
        }
        val doc = loaded
        if (doc != null) {
            AndroidView(
                factory = { ctx ->
                    // The scroll view, not the EditText, scrolls: TextView's own scrolling has no fling or edge stretch.
                    val scroller = NestedScrollView(ctx).apply {
                        isFillViewport = true
                        isVerticalScrollBarEnabled = true
                        setOnScrollChangeListener { _: NestedScrollView, _: Int, y: Int, _: Int, _: Int ->
                            scrolled = y > 0
                        }
                    }
                    NoteEditText(ctx).apply {
                        val pad = editorStyle.dp(22f).toInt()
                        setPadding(pad, editorStyle.dp(6f).toInt(), pad, editorStyle.dp(40f).toInt())
                        hint = "Title"
                        setDocument(doc)
                        onChange = {
                            vm.edited()
                            edits++
                        }
                        onFormatState = { state = it }
                        onLinkTap = { linkTap = it }
                        editor = this
                        // A new note opens ready to type the title. Focus alone doesn't raise the keyboard, and
                        // neither works before the view is attached.
                        if (vm.isNew) {
                            doOnAttach {
                                requestFocus()
                                // After focus: gaining focus moves the cursor to the end (the body).
                                setSelection(0)
                                windowInsetsController()?.show(WindowInsetsCompat.Type.ime())
                            }
                        }
                        scroller.addView(this, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                    }
                    scroller
                },
                onRelease = { editor = null },
                // Compose doesn't clip a native view: the edge stretch would draw the text over the top bar.
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
            )
            Reveal(typing) {
                Column {
                    state.tag?.let { tag ->
                        TagStrip(
                            tag,
                            tags,
                            onPick = { name -> editor?.insertTag(tag.start, tag.end, name) },
                            onShow = {
                                save()
                                onOpenTag(it)
                            }
                        )
                    }
                    FormattingBar(state, { editor }, onLink = { linkDialog = editor?.linkContext() })
                }
            }
            if (!typing) Spacer(Modifier.navigationBarsPadding())
        }
    }

    if (menu) {
        Sheet(onDismiss = { menu = false }, title = editor?.document()?.title?.trim()?.ifEmpty { null } ?: "New note") {
            SheetItem(Icons.Share, "Share", {
                menu = false
                editor?.document()?.let { share(context, it) }
            })
            if (BuildConfig.DEBUG) {
                SheetItem(Icons.Edit, "Show Markdown", {
                    menu = false
                    markdown = editor?.document()?.let(::exportMarkdown)
                })
                SheetItem(Icons.Edit, "Load sample note", {
                    menu = false
                    editor?.setDocument(Document.forNote(SAMPLE_TITLE, Markdown.parse(SAMPLE_MARKDOWN)))
                    vm.edited()
                    edits++
                })
            }
            // A blank new note has nothing to delete: leaving discards it.
            if (status != SaveStatus.Blank) {
                SheetItem(Icons.Trash, "Delete note", {
                    menu = false
                    save()
                    onDelete()
                }, danger = true)
            }
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
        NotesDialog(onDismiss = { markdown = null }, title = "Markdown") {
            Text(
                md,
                style = Type.mono.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = t.ink2,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
            )
            Spacer(Modifier.height(16.dp))
            NotesButton("Close", { markdown = null }, Modifier.fillMaxWidth())
        }
    }
}

/** The web's mobile back: a chevron and "Notes" in the tint colour. */
@Composable
private fun BackLink(onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction, 0.94f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Back" }
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Back, null, Modifier.size(22.dp), tint = t.tint)
        // At very large text the word would crowd the status out of the bar; the chevron says enough.
        if (LocalDensity.current.fontScale <= 1.3f) {
            Text("Notes", style = Type.body.copy(fontSize = 16.sp), color = t.tint)
        }
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
