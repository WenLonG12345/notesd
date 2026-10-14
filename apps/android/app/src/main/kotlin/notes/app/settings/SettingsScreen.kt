package notes.app.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import notes.app.AppGraph
import notes.app.BuildConfig
import notes.app.account.Account
import notes.app.ui.BrandMark
import notes.app.ui.ConfirmDialog
import notes.app.ui.Icons
import notes.app.ui.Kicker
import notes.app.ui.LocalReduceMotion
import notes.app.ui.LocalTokens
import notes.app.ui.Type
import notes.app.ui.pressScale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    Column(Modifier.fillMaxSize().background(t.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BackLink(onBack)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Text(
                    "Settings",
                    style = Type.display,
                    color = t.ink,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp).semantics { heading() }
                )

                Section("Server")
                Card {
                    when (val a = account) {
                        Account.None -> {
                            Info("Not connected", "Notes are kept on this phone only.")
                            Divider()
                            Action("Connect to a server", t.link, enabled = true, onClick = onConnect)
                        }

                        is Account.SignedIn -> {
                            Who(a.username, a.server, null)
                            Divider()
                            Action(if (busy) "Signing out…" else "Sign out", t.link, enabled = !busy) {
                                scope.launch { confirm = Confirm.SignOut(graph.store.pendingNotes()) }
                            }
                        }

                        is Account.SignedOut -> {
                            Who(a.username, a.server, "Signed out")
                            Divider()
                            Action("Sign in again", t.link, enabled = true, onClick = onSignIn)
                        }
                    }
                }

                Section("Appearance")
                Segmented(Theme.entries, theme, { it.label }) { graph.appearance.set(it) }

                Section("Data")
                Card {
                    Action("Remove all data from this device", t.danger, enabled = !busy) {
                        confirm = Confirm.RemoveAll
                    }
                }
                Text(
                    "Deletes every note on this phone and signs you out. Notes on the server aren't touched.",
                    style = Type.small,
                    color = t.ink3,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                )

                Section("About")
                Card {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BrandMark(24.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("notesd", style = Type.brand, color = t.ink, modifier = Modifier.weight(1f))
                        Text(
                            "Version ${BuildConfig.VERSION_NAME}",
                            style = Type.mono.copy(fontSize = 12.sp),
                            color = t.ink3
                        )
                    }
                }
            }
        }
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
        Text("Notes", style = Type.body.copy(fontSize = 16.sp), color = t.tint)
    }
}

@Composable
private fun Section(label: String) {
    Kicker(label, Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp).semantics { heading() })
}

/** A rounded group on the chip colour, like the web's settings sheet. */
@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.chip)
            .border(1.dp, t.edge, shape),
        content = content
    )
}

@Composable
private fun Divider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(LocalTokens.current.hair))
}

/** The server's address, then the account on it; [note] flags a problem, such as being signed out. */
@Composable
private fun Who(username: String, server: String, note: String?) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                server.toHttpUrlOrNull()?.let { it.host + if (it.port != 80 && it.port != 443) ":${it.port}" else "" }
                    ?: server,
                style = Type.mono.copy(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (note != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    note,
                    style = Type.label.copy(fontSize = 12.sp),
                    color = t.warnInk,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(t.warnBg)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            if (note ==
                null
            ) {
                "Signed in as $username"
            } else {
                "Account: $username"
            },
            style = Type.rowMeta,
            color = t.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Info(label: String, value: String) {
    val t = LocalTokens.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(label, style = Type.rowTitle, color = t.ink)
        Text(value, style = Type.rowMeta, color = t.ink2)
    }
}

@Composable
private fun Action(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = Type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        color = if (enabled) color else LocalTokens.current.ink3,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 17.dp)
    )
}

/** A segmented control whose highlight slides to the chosen option. */
@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val t = LocalTokens.current
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(t.hover)
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(
            segment * options.indexOf(selected),
            if (reduce) spring(stiffness = 100_000f) else spring(dampingRatio = 0.8f, stiffness = 600f),
            label = "segment"
        )
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(t.bg)
                .border(1.dp, t.edge, RoundedCornerShape(11.dp))
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            for (option in options) {
                val on = option == selected
                val color by animateColorAsState(if (on) t.ink else t.ink2, label = "segColor")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(on, role = Role.RadioButton, interactionSource = null, indication = null) {
                            onSelect(option)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(option),
                        style = Type.label.copy(
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = color
                    )
                }
            }
        }
    }
}
