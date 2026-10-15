-- Which images each live note shows (architecture §7.6), so images no note
-- uses can be deleted. Plain notes' rows come from their content; locked
-- notes' from the image ids their clients send, which the server can't
-- recompute. image_id isn't a foreign key: a note may show a missing image.
CREATE TABLE note_images (
    note_seq INTEGER NOT NULL REFERENCES notes(seq),
    image_id TEXT    NOT NULL,
    PRIMARY KEY (note_seq, image_id)
) WITHOUT ROWID;
CREATE INDEX idx_note_images_image ON note_images(image_id);

-- When the image was last left without a note: uploaded, or dropped by a
-- note. It is deleted once unreferenced for 30 days. NULL for images from
-- before references were tracked, which are never deleted until a note
-- shows them and drops them.
ALTER TABLE images ADD COLUMN released_at INTEGER;

-- Rebuild derived data at startup, which fills note_images for plain notes.
INSERT OR IGNORE INTO rebuild_index (id) VALUES (1);
