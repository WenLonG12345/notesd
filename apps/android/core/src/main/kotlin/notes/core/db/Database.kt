package notes.core.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The local SQLite database: one writer connection (transactions are
 * `BEGIN IMMEDIATE`, one at a time) and one read-only connection, so reads
 * never wait for a write in WAL mode. Connections open lazily on the first
 * call, off the caller's thread. The bundled driver ships its own SQLite with
 * FTS5, so search behaves the same on every device and on the JVM.
 */
class Database(private val path: String, private val io: CoroutineDispatcher = Dispatchers.IO) : AutoCloseable {
    private val writeLock = Mutex()
    private val readLock = Mutex()
    private var writer: SQLiteConnection? = null
    private var reader: SQLiteConnection? = null

    private val changesFlow =
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits after every committed write. Observers re-query; at this scale that's cheap. */
    val changes: SharedFlow<Unit> = changesFlow

    suspend fun <T> write(block: (Sql) -> T): T {
        val result = writeLock.withLock {
            withContext(io) {
                val conn = writerConnection()
                conn.execSQL("BEGIN IMMEDIATE")
                try {
                    block(Sql(conn)).also { conn.execSQL("COMMIT") }
                } catch (e: Throwable) {
                    conn.execSQL("ROLLBACK")
                    throw e
                }
            }
        }
        changesFlow.tryEmit(Unit)
        return result
    }

    suspend fun <T> read(block: (Sql) -> T): T = readLock.withLock {
        withContext(io) {
            val conn = readerConnection()
            // One snapshot for the whole block, even if it runs several queries.
            conn.execSQL("BEGIN")
            try {
                block(Sql(conn))
            } finally {
                conn.execSQL("COMMIT")
            }
        }
    }

    private fun writerConnection(): SQLiteConnection = writer ?: run {
        val conn = driver.open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
        conn.execSQL("PRAGMA journal_mode = WAL")
        conn.execSQL("PRAGMA synchronous = NORMAL")
        conn.execSQL("PRAGMA busy_timeout = 5000")
        Migrations.apply(conn)
        rebuildIfAsked(conn)
        writer = conn
        conn
    }

    /** A migration that changes derived data (search text, tags) asks for a rebuild. */
    private fun rebuildIfAsked(conn: SQLiteConnection) {
        val sql = Sql(conn)
        if (sql.long("SELECT count(*) FROM rebuild_index") == 0L) return
        conn.execSQL("BEGIN IMMEDIATE")
        try {
            sql.rebuildIndex()
            conn.execSQL("COMMIT")
        } catch (e: Throwable) {
            conn.execSQL("ROLLBACK")
            throw e
        }
    }

    private suspend fun readerConnection(): SQLiteConnection = reader ?: run {
        // The writer creates the file and the schema; a read-only connection can't.
        writeLock.withLock { writerConnection() }
        val conn = driver.open(path, SQLITE_OPEN_READONLY)
        conn.execSQL("PRAGMA busy_timeout = 5000")
        reader = conn
        conn
    }

    override fun close() {
        reader?.close()
        writer?.close()
        reader = null
        writer = null
    }

    private companion object {
        val driver = BundledSQLiteDriver()
    }
}

/** Thin helpers over a connection: positional `?` arguments, rows mapped by the caller. */
class Sql(private val conn: SQLiteConnection) {
    fun exec(sql: String, vararg args: Any?) {
        statement(sql, args).use { it.step() }
    }

    fun <T> query(sql: String, vararg args: Any?, row: (SQLiteStatement) -> T): List<T> =
        statement(sql, args).use { st ->
            buildList { while (st.step()) add(row(st)) }
        }

    fun <T> one(sql: String, vararg args: Any?, row: (SQLiteStatement) -> T): T? =
        statement(sql, args).use { st -> if (st.step()) row(st) else null }

    fun long(sql: String, vararg args: Any?): Long = one(sql, *args) { it.getLong(0) } ?: 0

    private fun statement(sql: String, args: Array<out Any?>): SQLiteStatement {
        val st = conn.prepare(sql)
        try {
            args.forEachIndexed { i, arg ->
                val index = i + 1
                when (arg) {
                    null -> st.bindNull(index)
                    is String -> st.bindText(index, arg)
                    is Long -> st.bindLong(index, arg)
                    is Int -> st.bindLong(index, arg.toLong())
                    is Boolean -> st.bindLong(index, if (arg) 1 else 0)
                    is Double -> st.bindDouble(index, arg)
                    else -> error("Can't bind ${arg::class.simpleName}")
                }
            }
        } catch (e: Throwable) {
            st.close()
            throw e
        }
        return st
    }
}

fun SQLiteStatement.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)

fun SQLiteStatement.textOrNull(column: Int): String? = if (isNull(column)) null else getText(column)
