package notes.app.locks

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import notes.core.crypto.LockSession

/**
 * Fingerprint unlock for locked notes (§10.1): the locked-notes key sealed
 * by [biometrics] and kept on this device only. It's kept against the lock
 * password entry it was made with; once that changes (a new password, or a
 * reset elsewhere) it stops working and the password is asked for, so a
 * stale key never encrypts new notes.
 */
class FingerprintUnlock(context: Context, private val locks: LockSession, private val biometrics: Biometrics) {
    enum class Outcome {
        Done,

        Cancelled,

        /** The password changed, the keyring was reset or the fingerprints changed: turned off, use the password. */
        Stale,

        /** Locked notes must be unlocked (with the password) before fingerprint unlock can be turned on. */
        NeedsUnlock,

        Failed
    }

    private val prefs = context.getSharedPreferences("locks", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.contains(SEALED))

    /** Turned on here. Whether it still works shows on use. */
    val enabled: StateFlow<Boolean> = _enabled

    fun available() = biometrics.available()

    suspend fun enable(activity: FragmentActivity): Outcome {
        val key = locks.key() ?: return Outcome.NeedsUnlock
        val bound = locks.passwordEntry() ?: return Outcome.NeedsUnlock
        return when (val r = biometrics.seal(activity, key)) {
            is BiometricResult.Ok -> {
                prefs.edit {
                    putString(SEALED, Base64.encodeToString(r.data, Base64.NO_WRAP))
                    putString(BOUND, bound)
                }
                _enabled.value = true
                Outcome.Done
            }

            BiometricResult.Cancelled -> Outcome.Cancelled

            else -> Outcome.Failed
        }
    }

    suspend fun unlock(activity: FragmentActivity): Outcome {
        val sealed = prefs.getString(SEALED, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return Outcome.Stale
        val bound = prefs.getString(BOUND, null)
        if (bound == null || locks.passwordEntry() != bound) return stale()
        return when (val r = biometrics.open(activity, sealed)) {
            is BiometricResult.Ok -> {
                val ok = locks.unlockWithKey(r.data, bound)
                r.data.fill(0)
                if (ok) Outcome.Done else stale()
            }

            BiometricResult.Cancelled -> Outcome.Cancelled

            BiometricResult.Invalidated -> stale()

            BiometricResult.Failed -> Outcome.Failed
        }
    }

    /**
     * This device changed the lock password from the entry [rewrapped]: the key
     * is the same, so the sealed copy follows the new entry. Only if it was
     * kept against that entry, though; one kept against an older keyring (reset
     * elsewhere since) is stale, and turned off rather than revived.
     */
    fun passwordChanged(rewrapped: String?) {
        if (!prefs.contains(SEALED)) return
        val next = locks.keyEntry()
        if (rewrapped == null || next == null || prefs.getString(BOUND, null) != rewrapped) {
            disable()
        } else {
            prefs.edit { putString(BOUND, next) }
        }
    }

    fun disable() {
        prefs.edit {
            remove(SEALED)
            remove(BOUND)
        }
        biometrics.forget()
        _enabled.value = false
    }

    private fun stale(): Outcome {
        disable()
        return Outcome.Stale
    }

    private companion object {
        const val SEALED = "fingerprint_sealed"
        const val BOUND = "fingerprint_bound_to"
    }
}
