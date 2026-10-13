package notes.app.ui

import androidx.compose.ui.graphics.Color

/**
 * The web app's design tokens (apps/web/src/styles/tokens.css), converted from
 * oklch to sRGB. The oklch source is kept beside each value so the two can be
 * compared when the web palette changes. Translucent web tokens (hair, hover,
 * glass) are rgb() there already and are copied as is.
 */
data class Tokens(
    val bg: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val tint: Color,
    val link: Color,
    val danger: Color,
    val solid: Color,
    val onSolid: Color,
    val hair: Color,
    val hairStrong: Color,
    val hover: Color,
    val on: Color,
    val glass: Color,
    val glassHi: Color,
    val statusSaved: Color,
    val statusUnsaved: Color,
    val statusSaving: Color,
    val statusFailed: Color,
    val statusConflict: Color
) {
    companion object {
        val Light = Tokens(
            bg = Color(0xFFF6F3EE), // oklch(0.965 0.007 80)
            ink = Color(0xFF241E1A), // oklch(0.24 0.012 60)
            ink2 = Color(0xFF524C47), // oklch(0.42 0.012 60)
            ink3 = Color(0xFF69625D), // oklch(0.5 0.012 60)
            accent = Color(0xFFEFB146), // oklch(0.8 0.14 78)
            accentDeep = Color(0xFFDD9314), // oklch(0.72 0.15 72)
            onAccent = Color(0xFF1F1915), // oklch(0.22 0.012 60)
            tint = Color(0xFFBE7200), // oklch(0.62 0.15 70)
            link = Color(0xFF874E00), // oklch(0.48 0.11 65)
            danger = Color(0xFFAC312C), // oklch(0.5 0.16 27)
            solid = Color(0xFF26201C), // oklch(0.25 0.012 60)
            onSolid = Color(0xFFFAF8F5), // oklch(0.98 0.005 80)
            hair = Color(0x173C2D19), // rgb(60 45 25 / 0.09)
            hairStrong = Color(0x293C2D19), // rgb(60 45 25 / 0.16)
            hover = Color(0x123C2D19), // rgb(60 45 25 / 0.07)
            on = Color(0x1C3C2D19), // rgb(60 45 25 / 0.11)
            glass = Color(0xBDFFFDFA), // rgb(255 253 250 / 0.74)
            glassHi = Color(0xE0FFFDFA), // rgb(255 253 250 / 0.88)
            statusSaved = Color(0xFF56AE6C), // oklch(0.68 0.13 150)
            statusUnsaved = Color(0xFF96918C), // oklch(0.66 0.01 70)
            statusSaving = Color(0xFFE8A127), // oklch(0.76 0.15 75)
            statusFailed = Color(0xFFE24942), // oklch(0.62 0.19 27)
            statusConflict = Color(0xFFE67339) // oklch(0.68 0.16 45)
        )

        val Dark = Tokens(
            bg = Color(0xFF161310), // oklch(0.19 0.008 70)
            ink = Color(0xFFEEEBE5), // oklch(0.94 0.008 80)
            ink2 = Color(0xFFB8B3AD), // oklch(0.77 0.01 75)
            ink3 = Color(0xFF99948F), // oklch(0.67 0.01 75)
            accent = Color(0xFFEFB146), // oklch(0.8 0.14 78)
            accentDeep = Color(0xFFDD9314), // oklch(0.72 0.15 72)
            onAccent = Color(0xFF1F1915), // oklch(0.22 0.012 60)
            tint = Color(0xFFF0BA59), // oklch(0.82 0.13 80)
            link = Color(0xFFEFBA64), // oklch(0.82 0.12 78)
            danger = Color(0xFFFB8276), // oklch(0.74 0.15 27)
            solid = Color(0xFFEBE7E2), // oklch(0.93 0.008 80)
            onSolid = Color(0xFF191511), // oklch(0.2 0.01 70)
            hair = Color(0x14FFFFFF), // rgb(255 255 255 / 0.08)
            hairStrong = Color(0x29FFFFFF), // rgb(255 255 255 / 0.16)
            hover = Color(0x12FFFFFF), // rgb(255 255 255 / 0.07)
            on = Color(0x21FFFFFF), // rgb(255 255 255 / 0.13)
            glass = Color(0xB82C2824), // rgb(44 40 36 / 0.72)
            glassHi = Color(0xE0322E2A), // rgb(50 46 42 / 0.88)
            statusSaved = Color(0xFF56AE6C), // oklch(0.68 0.13 150)
            statusUnsaved = Color(0xFF96918C), // oklch(0.66 0.01 70)
            statusSaving = Color(0xFFE8A127), // oklch(0.76 0.15 75)
            statusFailed = Color(0xFFE24942), // oklch(0.62 0.19 27)
            statusConflict = Color(0xFFE67339) // oklch(0.68 0.16 45)
        )
    }
}
