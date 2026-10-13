package notes.app.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import notes.app.ui.LocalTokens
import notes.core.db.Search

@Composable
fun NotesListScreen(
    vm: NotesListViewModel,
    signedOut: Boolean,
    onSignIn: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit
) {
    val t = LocalTokens.current
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.deletions.pending.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    // One snackbar for the latest delete; it goes away when that delete is committed or undone.
    val latest = pending.lastOrNull()
    LaunchedEffect(latest) {
        if (latest == null) return@LaunchedEffect
        val result = snackbar.showSnackbar("Note deleted", actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
        if (result == SnackbarResult.ActionPerformed) vm.deletions.undo(latest)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event ==
                Lifecycle.Event.ON_STOP
            ) {
                vm.deletions.commitAll()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.deletions.commitAll()
        }
    }

    Scaffold(
        containerColor = t.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, containerColor = t.accent, contentColor = t.onAccent) {
                Text("New note")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notes",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.ink,
                    modifier = Modifier.weight(1f).semantics { heading() }
                )
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = {
                        menu = true
                    }, modifier = Modifier.semantics { contentDescription = "More" }) {
                        Text("⋯", fontSize = 20.sp, color = t.ink)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            menu = false
                            onSettings()
                        })
                    }
                }
            }
            if (signedOut) {
                // Notes keep working; only syncing waits for the user to sign in.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(t.hover, RoundedCornerShape(12.dp))
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Signed out. Changes stay on this phone until you sign in.",
                        fontSize = 14.sp,
                        color = t.ink2,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp)
                    )
                    TextButton(onClick = onSignIn) { Text("Sign in", color = t.link) }
                }
            }
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.search(it)
                },
                placeholder = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = t.hover,
                    unfocusedContainerColor = t.hover,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            val searching = state.query.isNotBlank()
            when {
                !state.loaded -> Unit

                searching && state.hits.isEmpty() -> Message("No notes match “${state.query.trim()}”.")

                !searching && state.notes.isEmpty() -> Message("No notes yet. Tap New note to write one.")

                searching -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.hits, key = { it.id }) { hit ->
                        NoteRow(
                            title = hit.title,
                            time = listTime(hit.updatedAt),
                            preview = highlight(
                                hit.excerpt,
                                SpanStyle(background = t.accent.copy(alpha = 0.35f), color = t.ink)
                            ),
                            onOpen = { onOpen(hit.id) },
                            onDelete = { vm.deletions.request(hit.id) }
                        )
                    }
                }

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    for (group in groupByDate(state.notes, { it.updatedAt })) {
                        item(key = "group:${group.label}") {
                            Text(
                                group.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = t.ink3,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp).semantics {
                                    heading()
                                }
                            )
                        }
                        items(group.items, key = { it.id }) { note ->
                            NoteRow(
                                title = note.title,
                                time = listTime(note.updatedAt),
                                preview = AnnotatedString(
                                    note.preview.split('\n').joinToString(" · ").ifEmpty {
                                        "No additional text"
                                    }
                                ),
                                onOpen = { onOpen(note.id) },
                                onDelete = { vm.deletions.request(note.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(title: String, time: String, preview: AnnotatedString, onOpen: () -> Unit, onDelete: () -> Unit) {
    val t = LocalTokens.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .combinedClickable(onClickLabel = "Open", onLongClickLabel = "More actions", onLongClick = {
                    menu = true
                }, onClick = onOpen)
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                title.trim().ifEmpty { "New note" },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                Text(time, fontSize = 14.sp, color = t.ink2, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(preview, fontSize = 14.sp, color = t.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HorizontalDivider(Modifier.padding(start = 20.dp).align(Alignment.BottomStart), color = t.hair)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Delete note", color = t.danger) }, onClick = {
                menu = false
                onDelete()
            })
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        color = LocalTokens.current.ink2,
        fontSize = 15.sp,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
    )
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
