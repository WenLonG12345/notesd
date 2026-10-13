package notes.app.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import notes.core.db.NoteStore
import notes.core.db.NoteSummary
import notes.core.db.SearchHit

data class ListState(
    val loaded: Boolean = false,
    val query: String = "",
    val notes: List<NoteSummary> = emptyList(),
    val hits: List<SearchHit> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotesListViewModel(private val store: NoteStore, val deletions: Deletions) : ViewModel() {
    private val query = MutableStateFlow("")

    // Re-queried on every write: local and cheap at this scale. Notes waiting out the undo window are hidden.
    val state: StateFlow<ListState> =
        combine(
            store.changes.onStart {
                emit(Unit)
            },
            query,
            deletions.pending
        ) { _, q, pending -> q to pending.toSet() }
            .mapLatest { (q, pending) ->
                if (q.isBlank()) {
                    ListState(true, q, notes = store.list().filter { it.id !in pending })
                } else {
                    ListState(true, q, hits = store.search(q).filter { it.id !in pending })
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    fun search(q: String) {
        query.value = q
    }
}
