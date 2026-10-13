package notes.app.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import notes.core.db.NoteStore
import notes.core.doc.Document
import notes.core.markdown.Markdown

enum class SaveStatus { Blank, Unsaved, Saved }

/**
 * One open note. Saves run on [appScope] so a save started as the screen
 * closes still lands; they're serialised, so an older text never overwrites a
 * newer one. A note that doesn't exist yet is created by its first non-blank
 * save.
 */
class EditorViewModel(private val store: NoteStore, val id: String, private val appScope: CoroutineScope) :
    ViewModel() {
    private val _document = MutableStateFlow<Document?>(null)

    /** The text to show, null while loading. Kept current on every save so a recreated view picks it up. */
    val document: StateFlow<Document?> = _document

    private val _status = MutableStateFlow(SaveStatus.Blank)
    val status: StateFlow<SaveStatus> = _status

    private val saveLock = Mutex()
    private var edits = 0
    private var saved = 0

    init {
        viewModelScope.launch {
            val note = store.get(id)
            _document.value = if (note == null) {
                Document.forNote("", Document.EMPTY)
            } else {
                withContext(Dispatchers.Default) { Document.forNote(note.title, Markdown.parse(note.content)) }
            }
            if (note != null) _status.value = SaveStatus.Saved
        }
    }

    fun edited() {
        edits++
        _status.value = SaveStatus.Unsaved
    }

    /** Stores [doc] if anything changed since the last save. Call with the editor's current document. */
    fun save(doc: Document) {
        if (edits == saved) return
        val edit = edits
        saved = edit
        _document.value = doc
        appScope.launch {
            saveLock.withLock {
                val content = withContext(Dispatchers.Default) { Markdown.serialize(doc) }
                val note = store.save(id, doc.title.orEmpty(), content)
                if (edit == edits) _status.value = if (note == null) SaveStatus.Blank else SaveStatus.Saved
            }
        }
    }
}
