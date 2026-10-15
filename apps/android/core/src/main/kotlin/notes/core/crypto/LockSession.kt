package notes.core.crypto

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.db.LockKeyStore

/**
 * The locked-notes key on this device (§10.1). It is only ever in memory:
 * unlocked with the lock password against the synced keyring (so offline
 * too), dropped by [lock] after [idleMs] without [touch], when the app leaves
 * the screen, or on request. Edits to locked notes are saved by the
 * [onBeforeLock] hooks while the key is still there.
 */
class LockSession(
    private val keys: LockKeyStore,
    private val api: () -> ApiClient?,
    private val scope: CoroutineScope,
    private val iterations: Int = LockedNotes.PASSWORD_ITERATIONS,
    private val idleMs: Long = 5 * 60_000L
) {
    enum class State {
        /** No lock password yet: nothing has been locked. */
        None,

        /** Locked notes exist; their key isn't in memory. */
        Locked,

        /** The key is in memory: locked notes open without asking. */
        Unlocked
    }

    /** How a keyring change ended. */
    enum class Outcome { Done, WrongPassword, NotConnected, Offline, Failed }

    private val _state = MutableStateFlow(State.None)
    val state: StateFlow<State> = _state

    private val mutex = Mutex()
    private var key: ByteArray? = null
    private var idle: Job? = null
    private val beforeLock = CopyOnWriteArrayList<suspend () -> Unit>()

    /** The key while unlocked. Callers must not keep it. */
    fun key(): ByteArray? = key

    /** Runs before the key is dropped, e.g. to save an open locked note while it can still be encrypted. */
    fun onBeforeLock(fn: suspend () -> Unit): () -> Unit {
        beforeLock += fn
        return { beforeLock -= fn }
    }

    /** Re-reads the synced keyring: whether there's a lock password at all. */
    suspend fun refresh() = mutex.withLock {
        if (_state.value != State.Unlocked) _state.value = if (hasPassword()) State.Locked else State.None
    }

    /** False on a wrong password. */
    suspend fun unlock(password: String): Boolean = mutex.withLock {
        val unlocked = try {
            withContext(Dispatchers.Default) { Keyring.unlock(keys.keys(), password) }
        } catch (_: LockedException) {
            return@withLock false
        }
        adopt(unlocked)
        true
    }

    /** First use: a new key under [password]. Needs the server, which keeps the keyring for every device. */
    suspend fun setup(password: String): Outcome = mutex.withLock {
        val client = api() ?: return@withLock Outcome.NotConnected
        val created = withContext(Dispatchers.Default) { Keyring.create(password, iterations) }
        when (val r = client.putLockKey(created.entry, created.verifier)) {
            is ApiResult.Ok -> {
                keys.replace(listOf(r.value))
                adopt(created.key)
                Outcome.Done
            }

            else -> r.outcome()
        }
    }

    /** Re-wraps the same key under [next]: no note changes, and other devices take it on their next sync. */
    suspend fun changePassword(current: String, next: String): Outcome = mutex.withLock {
        val client = api() ?: return@withLock Outcome.NotConnected
        val stored = keys.keys()
        val unlocked = try {
            withContext(Dispatchers.Default) { Keyring.unlock(stored, current) }
        } catch (_: LockedException) {
            return@withLock Outcome.WrongPassword
        }
        val entry = withContext(Dispatchers.Default) { Keyring.passwordEntry(unlocked, next, iterations) }
        when (val r = client.putLockKey(entry, LockedNotes.verifier(unlocked))) {
            is ApiResult.Ok -> {
                keys.replace(listOf(r.value) + stored.filter { it.id != r.value.id })
                adopt(unlocked)
                Outcome.Done
            }

            else -> r.outcome()
        }
    }

    /** Forgets the keyring everywhere after a forgotten lock password; notes locked so far can't be opened again. */
    suspend fun reset(accountPassword: String): Outcome = mutex.withLock {
        val client = api() ?: return@withLock Outcome.NotConnected
        when (val r = client.resetLocks(accountPassword)) {
            is ApiResult.Ok -> {
                keys.replace(emptyList())
                drop()
                _state.value = State.None
                Outcome.Done
            }

            is ApiResult.Rejected if r.status == 401 -> Outcome.WrongPassword

            else -> r.outcome()
        }
    }

    /** Saves what the hooks have pending, then drops the key. */
    suspend fun lock() {
        if (_state.value != State.Unlocked) return
        for (fn in beforeLock) runCatching { fn() }
        mutex.withLock {
            drop()
            _state.value = if (hasPassword()) State.Locked else State.None
        }
    }

    /** Signed out or wiped: drop the key without saving. */
    suspend fun forget() = mutex.withLock {
        drop()
        _state.value = State.None
    }

    /** The user did something: the idle timeout starts over. */
    fun touch() {
        if (_state.value != State.Unlocked) return
        idle?.cancel()
        idle = scope.launch {
            delay(idleMs)
            lock()
        }
    }

    private fun adopt(unlocked: ByteArray) {
        key?.fill(0)
        key = unlocked
        _state.value = State.Unlocked
        touch()
    }

    private fun drop() {
        idle?.cancel()
        key?.fill(0)
        key = null
    }

    private suspend fun hasPassword() = keys.keys().any { it.kind == WrapKind.Password.wire }

    private fun ApiResult<*>.outcome(): Outcome = when (this) {
        is ApiResult.Unreachable, is ApiResult.RateLimited -> Outcome.Offline
        ApiResult.Unauthenticated -> Outcome.NotConnected
        else -> Outcome.Failed
    }
}
