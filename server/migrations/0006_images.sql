-- Images notes reference (architecture §7.6), keyed by the SHA-256 of their
-- bytes. Kept in the database rather than beside it, so one backup holds
-- notes and images consistently.
CREATE TABLE images (
    id           TEXT    PRIMARY KEY,   -- lowercase hex SHA-256 of data
    content_type TEXT    NOT NULL,      -- image/*, or application/vnd.notesd.locked
    size         INTEGER NOT NULL,
    data         BLOB    NOT NULL,
    created_at   INTEGER NOT NULL
);
