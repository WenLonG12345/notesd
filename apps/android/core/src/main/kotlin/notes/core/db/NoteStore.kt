package notes.core.db

import androidx.sqlite.SQLiteStatement
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import notes.core.text.Uuid7
import notes.core.text.plainText
import notes.core.text.previewOf

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val conflictOf: String?,
    val serverRevision: Long?,
    val dirty: Boolean
)

data class NoteSummary(val id: String, val title: String, val preview: String, val updatedAt: Long)

/** A search result. [excerpt] marks matches with [Search.MARK_OPEN] / [Search.MARK_CLOSE]. */
data class SearchHit(val id: String, val title: String, val excerpt: String, val updatedAt: Long)

/**
 * Notes on this device. Every local change is one transaction that updates the
 * note, its search entry and the sync outbox together, so the three can't
 * disagree after a crash. Until a sync engine sends them, outbox operations
 * coalesce: an unsent create or update takes the latest text; once sent, a
 * later change queues a new operation (a sent payload must be resent as is).
 */
class NoteStore(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    val changes: Flow<Unit> get() = db.changes

    suspend fun get(id: String): Note? = db.read {
        it.one("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", id, row = ::note)
    }

    /** Live notes, most recently edited first. */
    suspend fun list(): List<NoteSummary> = db.read { sql ->
        sql.query(
            """
            SELECT n.id, n.title, n.updated_at, f.body_text
            FROM notes n LEFT JOIN notes_fts f ON f.rowid = n.seq
            WHERE n.deleted_at IS NULL
            ORDER BY n.updated_at DESC, n.id DESC
            """
        ) { NoteSummary(it.getText(0), it.getText(1), previewOf(it.textOrNull(3).orEmpty()), it.getLong(2)) }
    }

    /** Ranked as on the server: title matches weigh 10×, then the most recently edited. */
    suspend fun search(input: String, limit: Int = 50): List<SearchHit> {
        val match = Search.buildQuery(input)
        if (match.isEmpty()) return emptyList()
        return db.read { sql ->
            sql.query(
                """
                SELECT n.id, n.title, n.updated_at, snippet(notes_fts, 1, ?, ?, '…', 20)
                FROM notes_fts JOIN notes n ON n.seq = notes_fts.rowid
                WHERE notes_fts MATCH ? AND n.deleted_at IS NULL
                ORDER BY bm25(notes_fts, ?, ?), n.updated_at DESC
                LIMIT ?
                """,
                Search.MARK_OPEN,
                Search.MARK_CLOSE,
                match,
                Search.TITLE_WEIGHT,
                Search.CONTENT_WEIGHT,
                limit
            ) { SearchHit(it.getText(0), it.getText(1), it.textOrNull(3).orEmpty(), it.getLong(2)) }
        }
    }

    /**
     * Saves the editor's text for [id], creating the note on first save. A note
     * that doesn't exist yet isn't created while blank, so opening "New note"
     * and leaving leaves nothing behind. Returns the stored note, or null when
     * nothing was stored (blank draft, deleted note).
     */
    suspend fun save(id: String, title: String, content: String): Note? = db.write { sql ->
        val current = sql.one("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", id, row = ::note)
        when {
            current == null -> if (title.isBlank() && content.isBlank()) null else create(sql, id, title, content)
            current.deletedAt != null -> null
            current.title == title && current.content == content -> current
            else -> update(sql, current, title, content)
        }
    }

    private fun create(sql: Sql, id: String, title: String, content: String): Note {
        val now = clock()
        sql.exec(
            "INSERT INTO notes (id, title, content, created_at, updated_at, dirty) VALUES (?, ?, ?, ?, ?, 1)",
            id,
            title,
            content,
            now,
            now
        )
        val note = sql.one("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", id, row = ::note)!!
        index(sql, id, title, content)
        enqueue(sql, id, CREATE, null, Payload(title, content, createdAt = now, updatedAt = now))
        return note
    }

    private fun update(sql: Sql, current: Note, title: String, content: String): Note {
        val now = maxOf(clock(), current.updatedAt)
        // Right-hand sides see the row before the update: base_* capture the synced text when a clean note first changes.
        sql.exec(
            """
            UPDATE notes SET title = ?, content = ?, updated_at = ?, dirty = 1,
                base_title = CASE WHEN dirty = 0 THEN title ELSE base_title END,
                base_content = CASE WHEN dirty = 0 THEN content ELSE base_content END,
                base_deleted_at = CASE WHEN dirty = 0 THEN deleted_at ELSE base_deleted_at END
            WHERE id = ?
            """,
            title,
            content,
            now,
            current.id
        )
        index(sql, current.id, title, content)
        val pending = sql.one(
            """
            SELECT operation_id, operation_type, payload_json FROM sync_outbox
            WHERE note_id = ? AND sent = 0 ORDER BY created_at DESC, rowid DESC LIMIT 1
            """,
            current.id
        ) { Triple(it.getText(0), it.getText(1), json.decodeFromString<Payload>(it.getText(2))) }
        if (pending != null && pending.second != DELETE) {
            val payload = pending.third.copy(title = title, content = content, updatedAt = now)
            sql.exec(
                "UPDATE sync_outbox SET payload_json = ? WHERE operation_id = ?",
                json.encodeToString(payload),
                pending.first
            )
        } else {
            // The base revision is null while the create is still in flight; the sync engine fills it in.
            enqueue(sql, current.id, UPDATE, current.serverRevision, Payload(title, content, updatedAt = now))
        }
        return current.copy(title = title, content = content, updatedAt = now, dirty = true)
    }

    /**
     * Deletes [id]: a note the server never received is simply removed;
     * otherwise it becomes a local tombstone (hidden, out of the index) with a
     * delete queued against its last known revision, replacing unsent edits.
     */
    suspend fun delete(id: String) {
        db.write { sql ->
            val current = sql.one("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", id, row = ::note)
            if (current == null || current.deletedAt != null) return@write
            val sent = sql.long("SELECT count(*) FROM sync_outbox WHERE note_id = ? AND sent = 1", id) > 0
            sql.exec("DELETE FROM notes_fts WHERE rowid = (SELECT seq FROM notes WHERE id = ?)", id)
            if (current.serverRevision == null && !sent) {
                sql.exec("DELETE FROM sync_outbox WHERE note_id = ?", id)
                sql.exec("DELETE FROM notes WHERE id = ?", id)
                return@write
            }
            sql.exec("DELETE FROM sync_outbox WHERE note_id = ? AND sent = 0", id)
            sql.exec(
                """
                UPDATE notes SET deleted_at = ?, dirty = 1,
                    base_title = CASE WHEN dirty = 0 THEN title ELSE base_title END,
                    base_content = CASE WHEN dirty = 0 THEN content ELSE base_content END,
                    base_deleted_at = CASE WHEN dirty = 0 THEN deleted_at ELSE base_deleted_at END
                WHERE id = ?
                """,
                clock(),
                id
            )
            enqueue(sql, id, DELETE, current.serverRevision, null)
        }
    }

    /** Rebuilds the search index from the notes. Sync state is untouched. */
    suspend fun reindex(): Int = db.write { sql ->
        sql.exec("DELETE FROM notes_fts")
        val live = sql.query("SELECT id, title, content FROM notes WHERE deleted_at IS NULL") {
            Triple(it.getText(0), it.getText(1), it.getText(2))
        }
        for ((id, title, content) in live) index(sql, id, title, content)
        live.size
    }

    /** Removes every note and all sync state from this device. */
    suspend fun wipe() {
        db.write { sql ->
            sql.exec("DELETE FROM notes_fts")
            sql.exec("DELETE FROM notes")
            sql.exec("DELETE FROM sync_outbox")
            sql.exec("UPDATE sync_state SET cursor = 0, generation = NULL, last_sync_at = NULL, last_error = NULL")
        }
    }

    /** For tests until the sync engine exists: the server has received [operationId]. */
    internal suspend fun markSent(operationId: String) {
        db.write { it.exec("UPDATE sync_outbox SET sent = 1 WHERE operation_id = ?", operationId) }
    }

    /** Unsent and in-flight operations, oldest first. */
    suspend fun outbox(): List<OutboxEntry> = db.read { sql ->
        sql.query(
            """
            SELECT operation_id, note_id, operation_type, base_revision, payload_json, sent
            FROM sync_outbox ORDER BY created_at, rowid
            """
        ) {
            OutboxEntry(
                it.getText(0),
                it.getText(1),
                it.getText(2),
                it.longOrNull(3),
                it.getText(4),
                it.getLong(5) == 1L
            )
        }
    }

    private fun index(sql: Sql, id: String, title: String, content: String) {
        val seq = sql.long("SELECT seq FROM notes WHERE id = ?", id)
        sql.exec("DELETE FROM notes_fts WHERE rowid = ?", seq)
        sql.exec("INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)", seq, title, plainText(content))
    }

    private fun enqueue(sql: Sql, noteId: String, type: String, baseRevision: Long?, payload: Payload?) {
        sql.exec(
            """
            INSERT INTO sync_outbox (operation_id, note_id, operation_type, base_revision, payload_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            Uuid7.generate(),
            noteId,
            type,
            baseRevision,
            payload?.let { json.encodeToString(it) } ?: "{}",
            clock()
        )
    }

    /** The request body an operation will send, minus what the sync engine adds (operationId, baseRevision). */
    @Serializable
    private data class Payload(
        val title: String = "",
        val content: String = "",
        val createdAt: Long? = null,
        val updatedAt: Long? = null
    )

    private companion object {
        const val CREATE = "create"
        const val UPDATE = "update"
        const val DELETE = "delete"

        const val NOTE_COLUMNS =
            "id, title, content, created_at, updated_at, deleted_at, conflict_of, server_revision, dirty"

        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }

        fun note(st: SQLiteStatement) = Note(
            id = st.getText(0),
            title = st.getText(1),
            content = st.getText(2),
            createdAt = st.getLong(3),
            updatedAt = st.getLong(4),
            deletedAt = st.longOrNull(5),
            conflictOf = st.textOrNull(6),
            serverRevision = st.longOrNull(7),
            dirty = st.getLong(8) == 1L
        )
    }
}

data class OutboxEntry(
    val operationId: String,
    val noteId: String,
    val type: String,
    val baseRevision: Long?,
    val payloadJson: String,
    val sent: Boolean
)
