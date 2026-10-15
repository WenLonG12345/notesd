package notes.core.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import notes.core.api.ApiClient
import notes.core.api.ApiResult
import notes.core.api.MutationResult
import notes.core.api.RemoteNote
import notes.core.db.Database
import notes.core.db.LockKeyStore
import notes.core.db.Operation
import notes.core.db.SyncEvent
import notes.core.db.SyncStore
import notes.core.images.ImageStore

/** How a cycle ended; the scheduler retries [Offline] and [Failed] with backoff. */
enum class SyncOutcome { Done, NotConnected, Offline, SignedOut, Failed }

sealed interface SyncStatus {
    data object NotConnected : SyncStatus

    data object Syncing : SyncStatus

    data class Synced(val at: Long) : SyncStatus

    data object Offline : SyncStatus

    data object SignedOut : SyncStatus

    data class Failed(val message: String) : SyncStatus
}

/**
 * One sync cycle: push the outbox, pull the feed, then push again whatever
 * the pull queued (conflicted and recovered copies). One cycle at a time;
 * calling [sync] while one runs waits for it and runs another. Decisions use
 * the server's revisions and cursor only, never the device clock.
 */
class SyncEngine(
    db: Database,
    private val api: () -> ApiClient?,
    private val pageSize: Int = 100,
    clock: () -> Long = System::currentTimeMillis,
    /** Images added here, uploaded before the notes that reference them (§7.6). */
    private val images: ImageStore? = null
) {
    val store = SyncStore(db, clock)

    /** The locked-notes keyring as last synced, for unlocking offline (§10.1). */
    val lockKeys = LockKeyStore(db)
    private val lock = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.NotConnected)
    val status: StateFlow<SyncStatus> = _status

    private val _events =
        MutableSharedFlow<SyncEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<SyncEvent> = _events

    suspend fun sync(): SyncOutcome = lock.withLock {
        val client = api()
        if (client == null) {
            _status.value = SyncStatus.NotConnected
            return@withLock SyncOutcome.NotConnected
        }
        _status.value = SyncStatus.Syncing
        val failure = cycle(client)
        store.finished(failure?.message)
        val outcome = failure?.outcome ?: SyncOutcome.Done
        _status.value = when (outcome) {
            SyncOutcome.Done -> SyncStatus.Synced(store.state().lastSyncAt ?: 0)
            SyncOutcome.Offline -> SyncStatus.Offline
            SyncOutcome.SignedOut -> SyncStatus.SignedOut
            SyncOutcome.NotConnected -> SyncStatus.NotConnected
            SyncOutcome.Failed -> SyncStatus.Failed(failure?.message ?: "Sync failed")
        }
        outcome
    }

    private class Stop(val outcome: SyncOutcome, val message: String?)

    private suspend fun cycle(api: ApiClient): Stop? {
        push(api)?.let { return it }
        pull(api)?.let { return it }
        keyring(api)?.let { return it }
        return if (store.nextOperation() != null) push(api) else null
    }

    /**
     * The keyring is tiny and has no change feed: fetched whole every cycle.
     * A change made on this device while the fetch was out is newer; the
     * fetched copy is dropped, and the next cycle brings the server's.
     */
    private suspend fun keyring(api: ApiClient): Stop? {
        val since = lockKeys.generation()
        return when (val result = api.lockKeys()) {
            is ApiResult.Ok -> {
                lockKeys.replaceIfUnchanged(result.value, since)
                null
            }

            else -> stop(result)
        }
    }

    private suspend fun push(api: ApiClient): Stop? {
        images?.let { store ->
            when (val r = store.upload(api)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Rejected -> return Stop(SyncOutcome.Failed, "The server had a problem (${r.status}).")
                else -> return stop(r)
            }
        }
        while (true) {
            val next = store.nextOperation() ?: return null
            val op = store.prepare(next) ?: continue
            val result = send(api, op)
            when (result) {
                is ApiResult.Ok -> store.sent(op, result.value)

                is ApiResult.Conflict -> {
                    val current = result.current?.let { decodeNote(it.toString()) }
                    if (current == null) store.rejected(op, result.message) else emit(store.conflict(op, current))
                }

                is ApiResult.Rejected -> {
                    if (result.status >=
                        500
                    ) {
                        return Stop(SyncOutcome.Failed, "The server had a problem (${result.status}).")
                    }
                    store.rejected(op, result.message.ifEmpty { result.error })
                }

                else -> return stop(result)
            }
        }
    }

    private suspend fun send(api: ApiClient, op: Operation): ApiResult<MutationResult> = when (op.type) {
        "create" -> api.createNote(
            buildJsonObject {
                put("operationId", op.operationId)
                put("id", op.noteId)
                put("title", op.title)
                put("content", op.content)
                op.createdAt?.let { put("createdAt", it) }
                op.updatedAt?.let { put("updatedAt", it) }
                op.conflictOf?.let { put("conflictOf", it) }
            }
        )

        "update" -> api.updateNote(
            op.noteId,
            buildJsonObject {
                put("operationId", op.operationId)
                put("baseRevision", op.baseRevision)
                put("title", op.title)
                put("content", op.content)
                op.updatedAt?.let { put("updatedAt", it) }
            }
        )

        else -> api.deleteNote(
            op.noteId,
            buildJsonObject {
                put("operationId", op.operationId)
                put("baseRevision", op.baseRevision)
            }
        )
    }

    private suspend fun pull(api: ApiClient): Stop? {
        var restarted = false
        while (true) {
            val state = store.state()
            when (val result = api.sync(state.cursor, pageSize, state.generation)) {
                is ApiResult.Ok -> {
                    emit(store.applyPage(result.value))
                    if (!result.value.hasMore) return null
                }

                is ApiResult.Conflict if result.code == "generation_mismatch" || result.code == "cursor_ahead" -> {
                    // Restored from a backup. Once per cycle: a second time means something else is wrong.
                    if (restarted) return Stop(SyncOutcome.Failed, result.message)
                    restarted = true
                    store.startFullSync()
                }

                is ApiResult.Rejected if result.status < 500 -> return Stop(
                    SyncOutcome.Failed,
                    result.message.ifEmpty {
                        result.error
                    }
                )

                else -> return stop(result)
            }
        }
    }

    private fun stop(result: ApiResult<*>): Stop = when (result) {
        ApiResult.Unauthenticated -> Stop(SyncOutcome.SignedOut, null)
        is ApiResult.Unreachable -> Stop(SyncOutcome.Offline, null)
        is ApiResult.RateLimited -> Stop(SyncOutcome.Offline, null)
        is ApiResult.Rejected -> Stop(SyncOutcome.Failed, "The server had a problem (${result.status}).")
        is ApiResult.Conflict -> Stop(SyncOutcome.Failed, result.message)
        is ApiResult.Ok -> error("not a failure")
    }

    private fun emit(events: List<SyncEvent>) {
        for (e in events) _events.tryEmit(e)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun decodeNote(s: String): RemoteNote? = try {
            json.decodeFromString<RemoteNote>(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
