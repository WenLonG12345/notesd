-- The locked-notes keyring (docs/architecture.md §10.1), mirrored from the server on every sync so locked
-- notes open offline. Every entry is wrapped; nothing here opens without the lock password.
CREATE TABLE lock_keys (
    id            TEXT    PRIMARY KEY,
    kind          TEXT    NOT NULL,
    credential_id TEXT,
    salt          TEXT    NOT NULL,
    iterations    INTEGER NOT NULL,
    wrapped       TEXT    NOT NULL,
    created_at    INTEGER NOT NULL
) WITHOUT ROWID;
