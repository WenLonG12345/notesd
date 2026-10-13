package notes.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalTokens = staticCompositionLocalOf { Tokens.Light }

/** Material 3 driven by the web app's tokens: one amber accent, warm paper and ink, no dynamic colour. */
@Composable
fun NotesTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val t = if (dark) Tokens.Dark else Tokens.Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val colors = base.copy(
        primary = t.accentDeep,
        onPrimary = t.onAccent,
        primaryContainer = t.accent,
        onPrimaryContainer = t.onAccent,
        secondary = t.ink2,
        onSecondary = t.bg,
        background = t.bg,
        onBackground = t.ink,
        surface = t.bg,
        onSurface = t.ink,
        surfaceVariant = t.hover,
        onSurfaceVariant = t.ink2,
        surfaceContainer = t.bg,
        surfaceContainerHigh = t.bg,
        surfaceContainerHighest = t.bg,
        surfaceContainerLow = t.bg,
        inverseSurface = t.solid,
        inverseOnSurface = t.onSolid,
        outline = t.hairStrong,
        outlineVariant = t.hair,
        error = t.danger,
        scrim = t.scrim
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp)
    )
    CompositionLocalProvider(
        LocalTokens provides t,
        LocalReduceMotion provides systemReducesMotion(),
        LocalTextSelectionColors provides TextSelectionColors(t.accentDeep, t.accent.copy(alpha = 0.4f))
    ) {
        MaterialTheme(colorScheme = colors, typography = NotesTypography, shapes = shapes, content = content)
    }
}
