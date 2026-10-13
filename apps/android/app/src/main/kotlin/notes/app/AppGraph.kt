package notes.app

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import notes.app.account.Account
import notes.app.account.AccountStore
import notes.app.account.KeystoreSealer
import notes.app.account.Sealer
import notes.app.notes.Deletions
import notes.app.settings.Appearance
import notes.app.sync.SyncScheduler
import notes.core.api.ApiClient
import notes.core.db.Database
import notes.core.db.NoteStore
import notes.core.sync.SyncEngine
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The app's long-lived objects, wired by hand. One per process, owned by [NotesApp]. */
class AppGraph(context: Context, sealer: Sealer = KeystoreSealer()) {
    /** Outlives screens: saves and deletes started here finish after the screen that asked is gone. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database = Database(context.getDatabasePath("notes.db").apply { parentFile?.mkdirs() }.path)
    val store = NoteStore(database)
    val deletions = Deletions(scope) { store.delete(it) }
    val appearance = Appearance(context)
    val accounts = AccountStore(File(context.filesDir, "account"), sealer)

    /** Null server while never connected; signed out, its requests come back Unauthenticated. */
    val sync = SyncEngine(database, { api() })
    val scheduler = SyncScheduler(context.applicationContext, sync, store, accounts, scope)

    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val _onboardingDismissed = MutableStateFlow(prefs.getBoolean(ONBOARDING_DISMISSED, false))

    /** "Use without a server" was chosen: start at the list instead of onboarding. */
    val onboardingDismissed: StateFlow<Boolean> = _onboardingDismissed

    fun setOnboardingDismissed(dismissed: Boolean) {
        prefs.edit { putBoolean(ONBOARDING_DISMISSED, dismissed) }
        _onboardingDismissed.value = dismissed
    }

    private var api: ApiClient? = null

    /** The client for the connected server, shared so its token refreshes are single-flight. Null if never connected. */
    fun api(): ApiClient? {
        val server = when (val a = accounts.account.value) {
            Account.None -> return null
            is Account.SignedIn -> a.server
            is Account.SignedOut -> a.server
        }
        return api?.takeIf { it.baseUrl.toString() == server }
            ?: ApiClient(server.toHttpUrl(), accounts).also { api = it }
    }

    /**
     * Ends the session on the server if it answers quickly, then forgets the
     * tokens. Notes and the server address stay: signing in again resumes.
     */
    suspend fun signOut() {
        withTimeoutOrNull(LOGOUT_WAIT_MS) { api()?.logout() }
        accounts.signOut()
    }

    /** Back to a fresh install: no notes, no account, onboarding on next launch. */
    suspend fun removeAllData() {
        if (accounts.account.value is Account.SignedIn) withTimeoutOrNull(LOGOUT_WAIT_MS) { api()?.logout() }
        store.wipe()
        accounts.clear()
        setOnboardingDismissed(false)
    }

    /** How this phone appears in the server's list of signed-in devices. */
    val deviceName: String = run {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    private companion object {
        const val ONBOARDING_DISMISSED = "onboarding_dismissed"
        const val LOGOUT_WAIT_MS = 5_000L
    }
}
