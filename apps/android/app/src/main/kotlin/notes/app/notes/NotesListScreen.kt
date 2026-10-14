package notes.app.notes

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import notes.app.ui.Backdrop
import notes.app.ui.Banner
import notes.app.ui.BannerKind
import notes.app.ui.BrandMark
import notes.app.ui.ButtonKind
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalHaze
import notes.app.ui.LocalTokens
import notes.app.ui.NotesButton
import notes.app.ui.Reveal
import notes.app.ui.Sheet
import notes.app.ui.SheetItem
import notes.app.ui.ToastHost
import notes.app.ui.Type
import notes.app.ui.breathing
import notes.app.ui.glass
import notes.app.ui.hairline
import notes.app.ui.pressScale
import notes.core.db.NoteFilter
import notes.core.db.Search
import notes.core.db.SyncEvent
import notes.core.sync.SyncStatus

/**
 * The notes list. [selected] is the note open beside it in two-pane layouts;
 * [account] is the signed-in user's name, shown as the settings button's initial.
 * [beside]: a note pane sits to the right, so the list draws the line between them.
 */
@Composable
fun NotesListScreen(
    vm: NotesListViewModel,
    signedOut: Boolean,
    account: String?,
    selected: String?,
    beside: Boolean,
    tagTree: TagTreeState,
    onSignIn: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit
) {
    val t = LocalTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.deletions.pending.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val tagsOpen by tagTree.open.collectAsStateWithLifecycle()
    val expanded by tagTree.expanded.collectAsStateWithLifecycle()
    val tree = remember(tags) { buildTagTree(tags) }
    var query by rememberSaveable { mutableStateOf("") }
    var sheetFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    val toasts = remember { SnackbarHostState() }
    val haze = rememberHazeState()
    val list = rememberLazyListState()

    // One toast for the latest delete; it goes away when that delete is committed or undone.
    val latest = pending.lastOrNull()
    LaunchedEffect(latest) {
        if (latest == null) return@LaunchedEffect
        val result = toasts.showSnackbar("Note deleted", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) vm.deletions.undo(latest)
    }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            val message = when (event) {
                is SyncEvent.ConflictedCopy -> "Changed in two places. Your version was kept as “${event.title}”."

                is SyncEvent.RecoveredCopy ->
                    "A note you edited was deleted elsewhere. It was kept as “${event.title}”."

                is SyncEvent.Restored ->
                    "“${event.title.ifBlank { "New note" }}” changed on another device and wasn't deleted."
            }
            toasts.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.deletions.commitAll()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.deletions.commitAll()
        }
    }

    val collapsed by remember {
        derivedStateOf {
            list.firstVisibleItemIndex > 0 ||
                list.firstVisibleItemScrollOffset > 120
        }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val searching = state.query.isNotBlank()
    val scope = when (val f = state.filter) {
        null -> null
        NoteFilter.Untagged -> "Untagged"
        is NoteFilter.Tag -> "#${f.name}"
    }

    CompositionLocalProvider(LocalHaze provides haze) {
        Box(
            Modifier
                .fillMaxSize()
                .background(t.bg)
                .imePadding()
                .then(
                    if (beside) {
                        Modifier.drawWithContent {
                            drawContent()
                            drawRect(t.hairStrong, Offset(size.width - 1f, 0f), Size(1f, size.height))
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Box(Modifier.fillMaxSize().hazeSource(haze)) {
                Backdrop()
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(top = top + 56.dp, bottom = bottom + 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "header", contentType = "header") {
                        Header(sync.status, sync.pending, scope, onClear = { vm.filter(null) })
                    }
                    // Nothing to search before the first note.
                    if (state.notes.isNotEmpty() || query.isNotEmpty() || scope != null) {
                        item(key = "search", contentType = "search") {
                            SearchField(query, scope, onChange = {
                                query = it
                                vm.search(it)
                            })
                        }
                    }
                    if (signedOut) {
                        item(key = "signedOut", contentType = "banner") {
                            Banner(
                                "Signed out. Changes stay on this phone until you sign in.",
                                BannerKind.Warn,
                                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                action = "Sign in",
                                onAction = onSignIn
                            )
                        }
                    }
                    if (!searching && (tags.isNotEmpty() || scope != null)) {
                        tagSection(
                            tree,
                            state.filter,
                            tagsOpen,
                            expanded,
                            onOpen = tagTree::setOpen,
                            onExpand = tagTree::setExpanded,
                            onFilter = vm::filter
                        )
                    }
                    when {
                        !state.loaded -> Unit

                        !searching && state.notes.isEmpty() && scope != null -> item(key = "emptyScope") {
                            Empty(
                                kicker = scope,
                                title = "Nothing here yet",
                                text = if (state.filter == NoteFilter.Untagged) {
                                    "Every note has a tag."
                                } else {
                                    "Notes you write while $scope is open start with the tag."
                                },
                                action = "Write a note",
                                onAction = onNew
                            )
                        }

                        searching && state.hits.isEmpty() -> item(key = "noMatch") {
                            Empty(
                                kicker = "Search",
                                title = "No matches",
                                text = "Nothing in your notes matches “${state.query.trim()}”."
                            )
                        }

                        !searching && state.notes.isEmpty() -> item(key = "empty") {
                            Empty(
                                kicker = "No notes yet",
                                title = "Your first note starts here.",
                                text = "The first line becomes the title. Everything saves as you type.",
                                action = "Write a note",
                                onAction = onNew
                            )
                        }

                        searching -> {
                            item(key = "results") { GroupLabel(results(state.hits.size)) }
                            items(state.hits, key = { it.id }, contentType = { "row" }) { hit ->
                                NoteRow(
                                    title = hit.title,
                                    time = listTime(hit.updatedAt),
                                    preview = highlight(
                                        hit.excerpt,
                                        SpanStyle(background = t.accent.copy(alpha = 0.45f))
                                    ),
                                    selected = hit.id == selected,
                                    onOpen = { onOpen(hit.id) },
                                    onMore = { sheetFor = hit.id to hit.title },
                                    onDelete = { vm.deletions.request(hit.id) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }

                        else -> for (group in groupByDate(state.notes, { it.updatedAt })) {
                            item(key = "group:${group.label}", contentType = "group") {
                                GroupLabel(group.label, Modifier.animateItem())
                            }
                            items(group.items, key = { it.id }, contentType = { "row" }) { note ->
                                NoteRow(
                                    title = note.title,
                                    time = listTime(note.updatedAt),
                                    preview = AnnotatedString(
                                        note.preview.split('\n').joinToString(" · ").ifEmpty { "No additional text" }
                                    ),
                                    selected = note.id == selected,
                                    onOpen = { onOpen(note.id) },
                                    onMore = { sheetFor = note.id to note.title },
                                    onDelete = { vm.deletions.request(note.id) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }

            TopBar(collapsed, scope ?: "Notes", account, onSettings, Modifier.align(Alignment.TopCenter))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                ToastHost(toasts, Modifier.align(Alignment.CenterHorizontally))
                Footer(state.notes.size, searching, onNew)
            }
        }
    }

    sheetFor?.let { (id, title) ->
        Sheet(onDismiss = { sheetFor = null }, title = title.trim().ifEmpty { "New note" }) {
            SheetItem(Icons.Edit, "Open", {
                sheetFor = null
                onOpen(id)
            })
            SheetItem(Icons.Trash, "Delete note", {
                sheetFor = null
                vm.deletions.request(id)
            }, danger = true)
        }
    }
}

private fun results(n: Int) = if (n == 1) "1 result" else "$n results"

/** Transparent over the large title; once it scrolls away, a glass bar with the title small. */
@Composable
private fun TopBar(collapsed: Boolean, title: String, account: String?, onSettings: () -> Unit, modifier: Modifier) {
    val t = LocalTokens.current
    val shown by animateFloatAsState(if (collapsed) 1f else 0f, tween(200), label = "bar")
    Box(modifier.fillMaxWidth()) {
        if (shown > 0f) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = shown }.glass().hairline(t.hair))
        }
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(48.dp))
            Text(
                title,
                style = Type.title.copy(fontSize = 20.sp),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = shown }
                    .clearAndSetSemantics { },
                textAlign = TextAlign.Center
            )
            AccountButton(account, onSettings)
        }
    }
}

/** Settings: the account's initial in serif when signed in (web: .account), otherwise a sliders icon. */
@Composable
private fun AccountButton(account: String?, onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .pressScale(interaction, 0.9f)
            .clickable(interaction, ripple(bounded = false, radius = 22.dp), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Settings" },
        contentAlignment = Alignment.Center
    ) {
        val initial = account?.trim()?.firstOrNull()?.uppercaseChar()
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(t.chip)
                .border(1.dp, t.edge, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (initial != null) {
                Text(initial.toString(), style = Type.title.copy(fontSize = 19.sp, lineHeight = 19.sp), color = t.ink)
            } else {
                Icon(Icons.Settings, null, Modifier.size(20.dp), tint = t.ink)
            }
        }
    }
}

/** The large title (the selected tag, if any, with a way back to all notes), with the sync state beneath it. */
@Composable
private fun Header(status: SyncStatus, pending: Int, scope: String?, onClear: () -> Unit) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(26.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                scope ?: "Notes",
                style = Type.display,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() }
            )
            if (scope != null) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClear)
                        .semantics { contentDescription = "Show all notes" },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(t.on), contentAlignment = Alignment.Center) {
                        Icon(Icons.Close, null, Modifier.size(13.dp), tint = t.ink2)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SyncState(status, pending)
    }
}

/** A quiet dot and a word: where sync stands. The dot breathes while syncing. */
@Composable
private fun SyncState(status: SyncStatus, pending: Int) {
    val t = LocalTokens.current
    val toSend = if (pending > 0) " · $pending to send" else ""
    val (word, color) = when (status) {
        SyncStatus.NotConnected -> "On this phone" to t.statusUnsaved
        SyncStatus.SignedOut -> "Signed out$toSend" to t.statusConflict
        SyncStatus.Syncing -> "Syncing…" to t.statusSaving
        is SyncStatus.Synced -> "Synced$toSend" to t.statusSaved
        SyncStatus.Offline -> "Offline$toSend" to t.statusUnsaved
        is SyncStatus.Failed -> "Sync error$toSend" to t.statusFailed
    }
    val dot by animateColorAsState(color, label = "dot")
    val alpha = breathing(status == SyncStatus.Syncing)
    Row(
        Modifier.padding(start = 38.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).drawBehind { drawCircle(dot.copy(alpha = alpha)) })
        Spacer(Modifier.width(7.dp))
        AnimatedContent(
            word,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
            label = "sync"
        ) {
            Text(it, style = Type.small, color = t.ink3)
        }
    }
}

@Composable
private fun SearchField(query: String, scope: String?, onChange: (String) -> Unit) {
    val t = LocalTokens.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(t.field)
            .border(1.dp, t.hair, shape)
            .clickable(indication = null, interactionSource = null) { requester.requestFocus() }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Search, null, Modifier.size(18.dp), tint = t.ink3)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    if (scope == null) "Search" else "Search in $scope",
                    style = Type.body.copy(fontSize = 16.sp),
                    color = t.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(fontSize = 16.sp, color = t.ink),
                cursorBrush = SolidColor(t.tint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .semantics { contentDescription = "Search notes" }
            )
        }
        Reveal(query.isNotEmpty()) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) {
                        onChange("")
                        focus.clearFocus()
                    }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(t.on), contentAlignment = Alignment.Center) {
                    Icon(Icons.Close, null, Modifier.size(12.dp), tint = t.ink2)
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(label: String, modifier: Modifier = Modifier) {
    Kicker(label, modifier.padding(start = 24.dp, top = 20.dp, bottom = 6.dp).semantics { heading() })
}

/**
 * One note. Tap opens; long-press opens its actions; swiping left deletes
 * (with Undo). TalkBack gets Delete as a custom action, since swipes aren't
 * available to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    title: String,
    time: String,
    preview: AnnotatedString,
    selected: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalTokens.current
    val haptics = LocalHapticFeedback.current
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            onDelete()
        }
    }
    val shape = RoundedCornerShape(16.dp)
    val bg by animateColorAsState(if (selected) t.accent else Color.Transparent, label = "selected")
    val titleColor = if (selected) t.onAccent else t.ink
    val metaColor = if (selected) t.onAccent2 else t.ink3
    val shownTitle = title.trim().ifEmpty { "New note" }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        backgroundContent = {
            if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val reached = swipe.targetValue == SwipeToDismissBoxValue.EndToStart
            val color by animateColorAsState(if (reached) t.danger else t.dangerBg, label = "swipe")
            Box(
                Modifier.fillMaxSize().clip(shape).background(color).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Trash, null, Modifier.size(22.dp), tint = if (reached) Color.White else t.danger)
            }
        }
    ) {
        val interaction = remember { MutableInteractionSource() }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                // Opaque only while swiped, to cover the delete colour beneath; at rest the backdrop shows through.
                .background(if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) Color.Transparent else t.bg)
                .background(bg)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = ripple(),
                    onClickLabel = "Open",
                    onLongClickLabel = "More actions",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMore()
                    },
                    onClick = onOpen
                )
                .semantics {
                    this.selected = selected
                    customActions = listOf(
                        CustomAccessibilityAction("Delete note") {
                            onDelete()
                            true
                        }
                    )
                }
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(shownTitle, style = Type.rowTitle, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(
                    time,
                    style = Type.rowMeta,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) t.onAccent else t.ink2,
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                Text(preview, style = Type.rowMeta, color = metaColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Empty(kicker: String, title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val t = LocalTokens.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Kicker(kicker)
        Text(title, style = Type.heading, color = t.ink, modifier = Modifier.semantics { heading() })
        Text(text, style = Type.lede, color = t.ink2)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            NotesButton(action, onAction, kind = ButtonKind.Solid, icon = Icons.Compose)
        }
    }
}

/** The web's mobile footer: a glass strip with the compose button at the right. */
@Composable
private fun Footer(count: Int, searching: Boolean, onNew: () -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier
            .fillMaxWidth()
            .glass()
            .hairline(t.hair, top = true)
            .navigationBarsPadding()
            .height(72.dp)
            .padding(start = 24.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (searching) {
                ""
            } else if (count == 1) {
                "1 note"
            } else if (count == 0) {
                ""
            } else {
                "$count notes"
            },
            style = Type.small,
            color = t.ink3,
            modifier = Modifier.weight(1f).clearAndSetSemantics { }
        )
        ComposeButton(onNew)
    }
}

@Composable
private fun ComposeButton(onClick: () -> Unit) {
    val t = LocalTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(52.dp)
            .pressScale(interaction, 0.9f)
            .clip(shape)
            .background(t.accent)
            .drawBehind {
                drawRect(t.accentDeep, Offset(0f, size.height - 2.5.dp.toPx()), Size(size.width, 2.5.dp.toPx()))
            }
            .clickable(interaction, ripple(color = t.onAccent), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "New note" },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Compose, null, Modifier.size(24.dp), tint = t.onAccent)
    }
}

/** An excerpt with the store's match markers turned into [style]. */
fun highlight(excerpt: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    var rest = excerpt
    while (rest.isNotEmpty()) {
        val open = rest.indexOf(Search.MARK_OPEN)
        if (open < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, open))
        val close = rest.indexOf(Search.MARK_CLOSE, open).let { if (it < 0) rest.length else it }
        withStyle(style) { append(rest.substring(open + 1, close)) }
        rest = if (close < rest.length) rest.substring(close + 1) else ""
    }
}
