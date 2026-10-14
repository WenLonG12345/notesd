package notes.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.EmailSettings

/**
 * The server's email settings as last seen: whether the share sheet offers
 * Email, and what Settings shows. Null while unknown (never connected, not
 * loaded yet, signed out).
 */
class EmailSetup(private val api: () -> ApiClient?) {
    private val _settings = MutableStateFlow<EmailSettings?>(null)
    val settings: StateFlow<EmailSettings?> = _settings

    /** Re-reads them; they can change from another device. Failures keep what was known. */
    suspend fun refresh() {
        val r = api()?.emailSettings() ?: return
        if (r is ApiResult.Ok) _settings.value = r.value
    }

    /** A null [apiKey] keeps the saved one. */
    suspend fun save(from: String, apiKey: String?): ApiResult<EmailSettings> {
        val r = api()?.saveEmailSettings(from, apiKey) ?: ApiResult.Unauthenticated
        if (r is ApiResult.Ok) _settings.value = r.value
        return r
    }

    suspend fun remove(): ApiResult<Unit> {
        val r = api()?.deleteEmailSettings() ?: ApiResult.Unauthenticated
        if (r is ApiResult.Ok) _settings.value = EmailSettings()
        return r
    }

    fun forget() {
        _settings.value = null
    }
}
