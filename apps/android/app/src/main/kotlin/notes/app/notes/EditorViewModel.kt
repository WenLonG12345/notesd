package notes.app.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import notes.core.db.Note
import notes.core.db.NoteStore
import notes.core.db.TagCount
import notes.core.doc.Document
import notes.core.markdown.Markdown
import notes.core.text.tagText

enum class SaveStatus { Blank, Unsaved, Saved, Synced }

/** The note a conflicted copy was split from. */
data class Original(val id: String, val title: String)

/**
 * One open note. Saves run on [appScope] so a save started as the screen
 * closes still lands; they're serialised, so an older text never overwrites a
 * newer one. A note that doesn't exist yet is created by its first non-blank
 * save.
 *
 * A new note can start with a tag ([startTag]); with nothing more typed it
 * still counts as blank.
 *
 * Sync can change the note while it's open. With no unsaved typing, the change
 * goes straight into the editor ([incoming]). Otherwise the next save passes
 * the stored version the typing grew from, and the store merges the two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModel(
    private val store: NoteStore,
    val id: String,
    private val appScope: CoroutineScope,
    private val startTag: String? = null
) : ViewModel() {
    private val _document = MutableStateFlow<Document?>(null)

    /** The text to show, null while loading. Kept current on every save so a recreated view picks it up. */
    val document: StateFlow<Document?> = _document

    private val _incoming =
        MutableSharedFlow<Document>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Text that changed underneath the editor (sync, or a merge on save): show it, keeping the cursor. */
    val incoming: SharedFlow<Document> = _incoming

    private val _status = MutableStateFlow(SaveStatus.Blank)
    val status: StateFlow<SaveStatus> = _status

    private val _original = MutableStateFlow<Original?>(null)
    val original: StateFlow<Original?> = _original

    /** Every tag, for completing one being typed. */
    val tags: StateFlow<List<TagCount>> =
        store.changes.onStart { emit(Unit) }.mapLatest { store.tags() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _gone = MutableStateFlow(false)

    /** The note was deleted elsewhere; any unsaved typing was kept as a recovered copy. */
    val gone: StateFlow<Boolean> = _gone

    /** The store had no such note when it was opened: a new note. Set before [document]. */
    var isNew = false
        private set

    private val saveLock = Mutex()
    private var edits = 0
    private var saved = 0
    private var saving = 0

    /** The stored title and content the editor's text grew from; null until the note exists. */
    private var known: Pair<String, String>? = null

    /** What a new note starts with; unchanged, it isn't stored. */
    private val blankContent = startTag?.let { tagText(it) + "\n" } ?: ""

    init {
        viewModelScope.launch {
            val note = store.get(id)
            isNew = note == null
            known = note?.let { it.title to it.content }
            _document.value = when {
                note != null -> parse(note)
                blankContent.isEmpty() -> Document.forNote("", Document.EMPTY)
                else -> Document.forNote("", Markdown.parse(blankContent))
            }
            if (note != null) show(note)
            store.changes.conflate().collect { refresh() }
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
        saving++
        _document.value = doc
        appScope.launch {
            try {
                saveLock.withLock { store(doc, edit) }
            } finally {
                saving--
            }
        }
    }

    private suspend fun store(doc: Document, edit: Int) {
        val title = doc.title.orEmpty()
        val content = withContext(Dispatchers.Default) { Markdown.serialize(doc) }
        val basedOn = known
        if (basedOn == null && title.isBlank() && content.trim() == blankContent.trim()) {
            if (edit == edits) _status.value = SaveStatus.Blank
            return
        }
        val note = store.save(id, title, content, basedOn)
        if (note == null) {
            if (basedOn != null && basedOn != (title to content)) {
                // Deleted elsewhere while typed into here: keep the typing.
                store.recover(title, content)
                _gone.value = true
            } else if (edit == edits) {
                _status.value = SaveStatus.Blank
            }
            return
        }
        val stored = note.title to note.content
        if (stored != (title to content) && edit == edits) {
            // Merged with a sync change: the editor shows the result.
            known = stored
            parse(note).let {
                _document.value = it
                _incoming.tryEmit(it)
            }
        } else {
            // If typing continued, the next save merges from what this one sent.
            known = if (stored == (title to content)) stored else title to content
        }
        if (edit == edits) show(note)
    }

    private suspend fun refresh() {
        if (edits != saved || saving > 0) return
        val note = store.get(id) ?: return
        if (note.deletedAt != null) {
            _gone.value = true
            return
        }
        val stored = note.title to note.content
        val before = known
        if (before != null && stored != before) {
            known = stored
            parse(note).let {
                _document.value = it
                _incoming.tryEmit(it)
            }
        }
        show(note)
    }

    private suspend fun show(note: Note) {
        if (edits ==
            saved
        ) {
            _status.value =
                if (!note.dirty && note.serverRevision != null) SaveStatus.Synced else SaveStatus.Saved
        }
        val originalId = note.conflictOf
        if (originalId != null && _original.value?.id != originalId) {
            _original.value = store.get(originalId)?.takeIf { it.deletedAt == null }?.let { Original(it.id, it.title) }
        }
    }

    private suspend fun parse(note: Note) =
        withContext(Dispatchers.Default) { Document.forNote(note.title, Markdown.parse(note.content)) }
}
