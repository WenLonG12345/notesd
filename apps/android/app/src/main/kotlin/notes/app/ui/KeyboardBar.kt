package notes.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A full-width slot on top of the keyboard. The editor puts its toolbars here
 * rather than in its own pane, so beside the list or the tags sidebar they
 * still span the keyboard.
 */
class KeyboardBar {
    internal var owner: Any? by mutableStateOf(null)
    internal var content: (@Composable () -> Unit)? by mutableStateOf(null)

    /** How tall the slot's content is now, so the owner can keep its text clear of it. */
    var height: Dp by mutableStateOf(0.dp)
        internal set
}

val LocalKeyboardBar = staticCompositionLocalOf<KeyboardBar?> { null }

/** Shows [content] in the window's keyboard bar while this is composed. */
@Composable
fun KeyboardBarContent(bar: KeyboardBar, content: @Composable () -> Unit) {
    val owner = remember { Any() }
    val latest by rememberUpdatedState(content)
    DisposableEffect(bar) {
        bar.owner = owner
        bar.content = { latest() }
        onDispose {
            // A newer editor (a note swapped beside the list) may have taken the slot already.
            if (bar.owner === owner) {
                bar.owner = null
                bar.content = null
                bar.height = 0.dp
            }
        }
    }
}

/** Where the keyboard bar draws: along the bottom of the window, above the keyboard. */
@Composable
fun KeyboardBarSlot(bar: KeyboardBar, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val content = bar.content ?: return
    Box(
        modifier
            .fillMaxWidth()
            .imePadding()
            .onSizeChanged { bar.height = with(density) { it.height.toDp() } }
    ) { content() }
}
