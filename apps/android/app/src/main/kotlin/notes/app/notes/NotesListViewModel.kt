package notes.app.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import notes.core.db.NoteFilter
import notes.core.db.NoteStore
import notes.core.db.NoteSummary
import notes.core.db.SearchHit
import notes.core.db.SyncEvent
import notes.core.sync.SyncEngine
import notes.core.sync.SyncStatus

data class ListState(
    val loaded: Boolean = false,
    val query: String = "",
    val filter: NoteFilter? = null,
    val notes: List<NoteSummary> = emptyList(),
    val hits: List<SearchHit> = emptyList()
)

/** What the list's status line shows: the engine's state and how many notes wait to be sent. */
data class SyncLine(val status: SyncStatus, val pending: Int)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModel(
    private val store: NoteStore,
    val deletions: Deletions,
    private val engine: SyncEngine,
    private val filter: MutableStateFlow<NoteFilter?>
) : ViewModel() {
    val events: SharedFlow<SyncEvent> get() = engine.events

    val sync: StateFlow<SyncLine> =
        combine(engine.status, store.changes.onStart { emit(Unit) }.mapLatest { store.pendingNotes() }, ::SyncLine)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncLine(engine.status.value, 0))

    private val query = MutableStateFlow("")

    // Re-queried on every write: local and cheap at this scale. Notes waiting out the undo window are hidden.
    val state: StateFlow<ListState> =
        combine(
            store.changes.onStart {
                emit(Unit)
            },
            query,
            filter,
            deletions.pending
        ) { _, q, f, pending -> Triple(q, f, pending.toSet()) }
            .mapLatest { (q, f, pending) ->
                if (q.isBlank()) {
                    ListState(true, q, f, notes = store.list(f).filter { it.id !in pending })
                } else {
                    ListState(true, q, f, hits = store.search(q, f).filter { it.id !in pending })
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    fun search(q: String) {
        query.value = q
    }

    fun filter(f: NoteFilter?) {
        filter.value = f
    }
}
