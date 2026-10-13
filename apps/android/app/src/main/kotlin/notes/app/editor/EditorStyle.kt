package notes.app.editor

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import notes.app.ui.Tokens

/** Sizes and colours the editor's spans draw with, resolved once per view. */
class EditorStyle(val density: Float, val tokens: Tokens, val typeface: Typeface) {
    fun dp(v: Float) = v * density

    val ink = tokens.ink.toArgb()
    val ink2 = tokens.ink2.toArgb()
    val ink3 = tokens.ink3.toArgb()
    val accent = tokens.accent.toArgb()
    val accentDeep = tokens.accentDeep.toArgb()
    val onAccent = tokens.onAccent.toArgb()
    val link = tokens.link.toArgb()
    val hover = tokens.hover.toArgb()
    val hairStrong = tokens.hairStrong.toArgb()

    /** Width one list level adds; the marker is drawn inside it. */
    val itemIndent = dp(28f)

    /** Width one quote level adds; the bar sits at its start. */
    val quoteIndent = dp(18f)
    val quoteBar = dp(2f)
    val boxSize = dp(17f)

    // Relative to the body size (17sp, set on the view) so system font scaling applies.
    val titleScale = 26f / 17f
    val headingScale = floatArrayOf(1.5f, 1.25f, 1.1f)
    val rawScale = 0.85f

    val semibold: Typeface = Typeface.create(typeface, 600, false)
    val monospace: Typeface = Typeface.MONOSPACE

    companion object {
        fun from(context: Context, typeface: Typeface = Typeface.DEFAULT): EditorStyle {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            return EditorStyle(
                density = context.resources.displayMetrics.density,
                tokens = if (night) Tokens.Dark else Tokens.Light,
                typeface = typeface
            )
        }
    }
}
