package notes.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** The content glass surfaces on this screen blur; null where nothing scrolls under them. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * A translucent panel over the content behind it, like the web's backdrop-filter
 * surfaces. Real blur on Android 12+; earlier versions get the opaque glass colour.
 */
@Composable
fun Modifier.glass(hi: Boolean = true): Modifier {
    val t = LocalTokens.current
    val tint = if (hi) t.glassHi else t.glass
    val state = LocalHaze.current ?: return background(tint)
    return hazeBlur(
        HazeInput.Sources(state),
        HazeBlurStyle {
            blurRadius(22.dp)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.tint(tint)))
            fallbackColorEffect(HazeColorEffect.tint(t.glassHi))
        }
    )
}

/** A one-pixel hairline along the bottom (or top) edge, the web's border-bottom: 1px solid var(--hair). */
fun Modifier.hairline(color: Color, top: Boolean = false): Modifier = drawBehind {
    val y = if (top) 0f else size.height - 1f
    drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, y), size = size.copy(height = 1f))
}

private fun Modifier.background(color: Color): Modifier = drawBehind { drawRect(color) }
