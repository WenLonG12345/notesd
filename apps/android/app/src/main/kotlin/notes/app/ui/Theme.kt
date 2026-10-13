package notes.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

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
        surfaceContainer = t.glassHi,
        surfaceContainerHigh = t.glassHi,
        surfaceContainerHighest = t.glassHi,
        surfaceContainerLow = t.bg,
        outline = t.hairStrong,
        outlineVariant = t.hair,
        error = t.danger
    )
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
