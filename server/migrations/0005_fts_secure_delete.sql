-- Locked notes (§10.1): when a note is locked or deleted, its old words
-- must leave the search index. secure_delete zeroes ordinary rows, but FTS5
-- keeps its own segment blobs and only marks entries deleted until a merge;
-- its secure-delete option removes them from the blobs at once.
INSERT INTO notes_fts (notes_fts, rank) VALUES ('secure-delete', 1);

-- Rebuilding (delete everything, reinsert, optimize) leaves no segment that
-- still holds text indexed before this.
INSERT OR IGNORE INTO rebuild_index (id) VALUES (1);
