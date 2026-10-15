package notes.core.db

import notes.core.api.LockKey

/** This device's copy of the locked-notes keyring, so locked notes open offline (§10.1). */
class LockKeyStore(private val db: Database) {
    suspend fun keys(): List<LockKey> = db.read { sql ->
        sql.query(
            """
            SELECT id, kind, credential_id, salt, iterations, wrapped, created_at FROM lock_keys
            ORDER BY kind != 'password', created_at, id
            """
        ) {
            LockKey(
                it.getText(0),
                it.getText(1),
                it.textOrNull(2),
                it.getText(3),
                it.getLong(4).toInt(),
                it.getText(5),
                it.getLong(6)
            )
        }
    }

    /** Replaces the copy with the server's keyring. */
    suspend fun replace(keys: List<LockKey>) = db.write { sql ->
        sql.exec("DELETE FROM lock_keys")
        for (k in keys) {
            sql.exec(
                "INSERT INTO lock_keys (id, kind, credential_id, salt, iterations, wrapped, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                k.id,
                k.kind,
                k.credentialId,
                k.salt,
                k.iterations,
                k.wrapped,
                k.createdAt
            )
        }
    }
}
