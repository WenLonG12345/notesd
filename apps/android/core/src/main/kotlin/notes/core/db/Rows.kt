package notes.core.db

import androidx.sqlite.SQLiteStatement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import notes.core.text.Uuid7
import notes.core.text.plainText

// Row access shared by NoteStore (local edits) and SyncStore (the sync engine). Callers hold a write transaction.

internal const val CREATE = "create"
internal const val UPDATE = "update"
internal const val DELETE = "delete"

internal const val NOTE_COLUMNS =
    "id, title, content, created_at, updated_at, deleted_at, conflict_of, server_revision, dirty, base_title, base_content"

internal fun noteRow(st: SQLiteStatement) = Note(
    id = st.getText(0),
    title = st.getText(1),
    content = st.getText(2),
    createdAt = st.getLong(3),
    updatedAt = st.getLong(4),
    deletedAt = st.longOrNull(5),
    conflictOf = st.textOrNull(6),
    serverRevision = st.longOrNull(7),
    dirty = st.getLong(8) == 1L,
    baseTitle = st.textOrNull(9),
    baseContent = st.textOrNull(10)
)

internal fun Sql.note(id: String): Note? = one("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", id, row = ::noteRow)

internal fun Sql.index(id: String, title: String, content: String) {
    val seq = long("SELECT seq FROM notes WHERE id = ?", id)
    exec("DELETE FROM notes_fts WHERE rowid = ?", seq)
    exec("INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)", seq, title, plainText(content))
}

internal fun Sql.unindex(id: String) {
    exec("DELETE FROM notes_fts WHERE rowid = (SELECT seq FROM notes WHERE id = ?)", id)
}

/** The request body an operation will send, minus what is fixed at send time (operationId, baseRevision). */
@Serializable
internal data class Payload(
    val title: String = "",
    val content: String = "",
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val conflictOf: String? = null
)

internal val outboxJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

internal fun Sql.enqueue(noteId: String, type: String, payload: Payload?, now: Long) {
    exec(
        """
        INSERT INTO sync_outbox (operation_id, note_id, operation_type, base_revision, payload_json, created_at)
        VALUES (?, ?, ?, NULL, ?, ?)
        """,
        Uuid7.generate(),
        noteId,
        type,
        payload?.let { outboxJson.encodeToString(it) } ?: "{}",
        now
    )
}

internal class PendingOp(val operationId: String, val type: String, val payload: Payload)

/** The newest operation for [noteId] that hasn't been sent: the one later edits coalesce into. */
internal fun Sql.unsentOp(noteId: String): PendingOp? = one(
    """
    SELECT operation_id, operation_type, payload_json FROM sync_outbox
    WHERE note_id = ? AND sent = 0 ORDER BY created_at DESC, rowid DESC LIMIT 1
    """,
    noteId
) { PendingOp(it.getText(0), it.getText(1), outboxJson.decodeFromString(it.getText(2))) }

/**
 * Makes sure the server will receive [title]/[content] for [noteId]: rewrites the
 * unsent create or update, or queues a new update (a sent payload is never changed).
 */
internal fun Sql.queueText(noteId: String, title: String, content: String, now: Long) {
    val pending = unsentOp(noteId)
    if (pending != null && pending.type != DELETE) {
        val payload = pending.payload.copy(title = title, content = content, updatedAt = now)
        exec(
            "UPDATE sync_outbox SET payload_json = ? WHERE operation_id = ?",
            outboxJson.encodeToString(payload),
            pending.operationId
        )
    } else {
        enqueue(noteId, UPDATE, Payload(title, content, updatedAt = now), now)
    }
}

/**
 * A new note holding [title]/[content], queued for upload. Conflicted and
 * recovered copies are made this way, so no text is ever dropped.
 */
internal fun Sql.insertCopy(title: String, content: String, conflictOf: String?, now: Long): String {
    val id = Uuid7.generate()
    exec(
        "INSERT INTO notes (id, title, content, created_at, updated_at, conflict_of, dirty) VALUES (?, ?, ?, ?, ?, ?, 1)",
        id,
        title,
        content,
        now,
        now,
        conflictOf
    )
    index(id, title, content)
    enqueue(id, CREATE, Payload(title, content, createdAt = now, updatedAt = now, conflictOf = conflictOf), now)
    return id
}

internal fun copyTitle(title: String, suffix: String) = title.trim().ifEmpty { "Untitled" } + suffix

internal const val CONFLICTED = " (Conflicted copy)"
internal const val RECOVERED = " (Recovered copy)"
