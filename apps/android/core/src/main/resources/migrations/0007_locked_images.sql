-- The images in the locked body this device last encrypted for each note
-- (architecture §7.6). The server can't read a locked body, so its saves
-- list the images; sync sends bodies it can't decrypt (merges, copies), and
-- finds their images here by the body's hash.
CREATE TABLE IF NOT EXISTS locked_images (
    note_id     TEXT PRIMARY KEY,
    content_sha TEXT NOT NULL,   -- lowercase hex SHA-256 of the envelope
    image_ids   TEXT NOT NULL    -- JSON array of image ids
);
CREATE INDEX IF NOT EXISTS idx_locked_images_sha ON locked_images(content_sha);
