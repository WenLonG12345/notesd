package notes.app.notes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Deletes that can still be undone. The note is only hidden until the undo
 * window ends (or the list goes away); the store delete happens then. Deleting
 * at once and restoring on undo would race the sync engine, which could send
 * the delete before the user taps Undo.
 */
class Deletions(
    private val scope: CoroutineScope,
    private val windowMs: Long = 5_000,
    private val delete: suspend (String) -> Unit
) {
    private val timers = HashMap<String, Job>()
    private val _pending = MutableStateFlow<List<String>>(emptyList())

    /** Ids waiting to be deleted, oldest first. */
    val pending: StateFlow<List<String>> = _pending

    fun request(id: String) {
        if (id in timers) return
        _pending.value += id
        timers[id] = scope.launch {
            delay(windowMs)
            commit(id)
        }
    }

    fun undo(id: String) {
        timers.remove(id)?.cancel() ?: return
        _pending.value -= id
    }

    /** Deletes everything pending now (the list left the screen). */
    fun commitAll() {
        for (id in timers.keys.toList()) {
            timers[id]?.cancel()
            commit(id)
        }
    }

    private fun commit(id: String) {
        timers.remove(id) ?: return
        scope.launch {
            delete(id)
            _pending.value -= id
        }
    }
}
