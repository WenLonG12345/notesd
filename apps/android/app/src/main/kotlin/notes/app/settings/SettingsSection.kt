package notes.app.settings

import kotlinx.serialization.Serializable

/** Settings' categories, in the order docs/settings.md gives. */
@Serializable
enum class SettingsSection(val title: String) {
    Server("Server"),
    Security("Security"),
    Spaces("Spaces"),
    Integrations("Integrations"),
    Appearance("Appearance"),
    Device("Sync & data"),
    About("About")
}
