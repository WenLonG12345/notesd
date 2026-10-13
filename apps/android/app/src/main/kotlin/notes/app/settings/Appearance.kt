package notes.app.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Theme(val label: String, val nightMode: Int) {
    System("System", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    Light("Light", AppCompatDelegate.MODE_NIGHT_NO),
    Dark("Dark", AppCompatDelegate.MODE_NIGHT_YES)
}

/**
 * The light/dark choice, kept on this device. Applied as AppCompat's night
 * mode, so the configuration itself changes: Compose, resources and the
 * editor's View-drawn spans all follow it.
 */
class Appearance(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(
        prefs.getString(KEY, null)?.let { saved -> Theme.entries.firstOrNull { it.name == saved } } ?: Theme.System
    )
    val theme: StateFlow<Theme> = _theme

    init {
        AppCompatDelegate.setDefaultNightMode(_theme.value.nightMode)
    }

    fun set(theme: Theme) {
        prefs.edit { putString(KEY, theme.name) }
        _theme.value = theme
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
    }

    private companion object {
        const val KEY = "theme"
    }
}
