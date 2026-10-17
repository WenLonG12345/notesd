-- Images added on this device that the server doesn't have yet (§7.6). The file is in the
-- pending images directory; sync uploads it before any note operation, so no device sees
-- a reference before its bytes exist.
CREATE TABLE pending_images (
    id           TEXT    PRIMARY KEY, -- lowercase hex SHA-256 of the bytes
    content_type TEXT    NOT NULL,
    created_at   INTEGER NOT NULL
) WITHOUT ROWID;
