-- 0001_init: notes, sync state, idempotency records, search index, auth.
--
-- Deviation from docs/architecture.md §4.1: notes has an explicit
-- INTEGER PRIMARY KEY (`seq`) and a UNIQUE `id`. The FTS index is keyed by the
-- notes rowid; an implicit rowid may be renumbered by VACUUM, an INTEGER
-- PRIMARY KEY never is.

CREATE TABLE notes (
    seq         INTEGER PRIMARY KEY,         -- stable rowid, joins notes_fts
    id          TEXT    NOT NULL UNIQUE,     -- client-generated (UUIDv7 recommended)
    title       TEXT    NOT NULL DEFAULT '',
    content     TEXT    NOT NULL DEFAULT '', -- Markdown
    revision    INTEGER NOT NULL DEFAULT 1,
    change_seq  INTEGER NOT NULL,            -- server-assigned, from sync_state.current_seq
    created_at  INTEGER NOT NULL,            -- Unix ms, UTC; informational
    updated_at  INTEGER NOT NULL,            -- Unix ms, UTC; informational
    deleted_at  INTEGER,                     -- tombstone marker
    conflict_of TEXT                         -- optional: id of the note this is a conflict copy of
);
CREATE UNIQUE INDEX idx_notes_change_seq ON notes(change_seq);
CREATE INDEX idx_notes_updated_at ON notes(updated_at DESC, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE sync_state (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    current_seq INTEGER NOT NULL DEFAULT 0,
    generation  TEXT    NOT NULL             -- §11.4 sync generation ID
);
INSERT INTO sync_state (id, current_seq, generation)
VALUES (1, 0, lower(hex(randomblob(16))));

CREATE TABLE applied_operations (
    operation_id  TEXT PRIMARY KEY,
    request_hash  TEXT    NOT NULL,
    response_json TEXT    NOT NULL,          -- {"status": <http status>, "body": <response>}
    applied_at    INTEGER NOT NULL
);

CREATE VIRTUAL TABLE notes_fts USING fts5(
    title,
    body_text,
    tokenize = 'unicode61 remove_diacritics 2'
);

-- Single owner account (§10).
CREATE TABLE users (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    username      TEXT    NOT NULL,
    password_hash TEXT    NOT NULL,          -- PHC-encoded Argon2id
    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL
);

-- One row per signed-in browser or device. Only SHA-256 hashes of bearer
-- secrets are stored.
CREATE TABLE sessions (
    id                 TEXT PRIMARY KEY,     -- random public identifier
    kind               TEXT    NOT NULL CHECK (kind IN ('web', 'device')),
    name               TEXT    NOT NULL DEFAULT '',
    access_hash        TEXT    NOT NULL UNIQUE,
    access_expires_at  INTEGER NOT NULL,
    refresh_hash       TEXT UNIQUE,          -- devices only
    refresh_expires_at INTEGER,
    csrf_token         TEXT,                 -- web only
    created_at         INTEGER NOT NULL,
    last_used_at       INTEGER NOT NULL
);
