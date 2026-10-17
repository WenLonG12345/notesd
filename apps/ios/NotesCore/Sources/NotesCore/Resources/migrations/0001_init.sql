-- The local replica (architecture §4.3): Android's schema through its 0007,
-- in one file. pending_images (0002) and lock_keys (0003) were added with images and locked notes.

CREATE TABLE notes (
    seq               INTEGER PRIMARY KEY, -- stable rowid, keys notes_fts (as on the server)
    id                TEXT NOT NULL UNIQUE,
    title             TEXT NOT NULL DEFAULT '',
    content           TEXT NOT NULL DEFAULT '',
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,
    deleted_at        INTEGER,
    conflict_of       TEXT,
    server_revision   INTEGER,             -- NULL until the server has the note
    server_change_seq INTEGER,
    dirty             INTEGER NOT NULL DEFAULT 0,
    base_title        TEXT,                -- last synced version, kept while dirty
    base_content      TEXT,
    base_deleted_at   INTEGER,
    full_sync_seen    INTEGER NOT NULL DEFAULT 0 -- reached by the current full reconciliation
);
CREATE INDEX idx_notes_list ON notes(updated_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_notes_dirty ON notes(dirty) WHERE dirty = 1;

CREATE TABLE sync_outbox (
    operation_id   TEXT PRIMARY KEY,
    note_id        TEXT NOT NULL,
    operation_type TEXT NOT NULL,          -- create | update | delete
    base_revision  INTEGER,
    payload_json   TEXT NOT NULL,
    created_at     INTEGER NOT NULL,
    sent           INTEGER NOT NULL DEFAULT 0 -- frozen once sent: a retry must resend the same payload
);
CREATE INDEX idx_outbox_note ON sync_outbox(note_id, created_at);

CREATE TABLE sync_state (
    id           INTEGER PRIMARY KEY CHECK (id = 1),
    cursor       INTEGER NOT NULL DEFAULT 0,
    generation   TEXT,                     -- NULL before the first sync
    last_sync_at INTEGER,
    last_error   TEXT,
    full_sync    INTEGER NOT NULL DEFAULT 0 -- a full reconciliation is in progress (§11.4)
);
INSERT INTO sync_state (id) VALUES (1);

CREATE VIRTUAL TABLE notes_fts USING fts5(
    title, body_text,
    tokenize = 'unicode61 remove_diacritics 2'
);

-- Tags in live notes (§7.5), kept with the search index.
CREATE TABLE note_tags (
    note_seq INTEGER NOT NULL REFERENCES notes(seq),
    key      TEXT NOT NULL, -- lowercased name
    name     TEXT NOT NULL, -- as first written in the note
    PRIMARY KEY (note_seq, key)
) WITHOUT ROWID;
CREATE INDEX idx_note_tags_key ON note_tags(key, note_seq);

-- A row asks for the search index and tags to be rebuilt at the next open.
CREATE TABLE rebuild_index (id INTEGER PRIMARY KEY CHECK (id = 1));

-- The images each recent locked body shows (§7.6), from the server, so a copy of it lists them.
CREATE TABLE locked_images (
    note_id     TEXT NOT NULL,
    content_sha TEXT NOT NULL,   -- lowercase hex SHA-256 of the envelope
    image_ids   TEXT NOT NULL,   -- JSON array of image ids
    PRIMARY KEY (note_id, content_sha)
);
CREATE INDEX idx_locked_images_sha ON locked_images(content_sha);
