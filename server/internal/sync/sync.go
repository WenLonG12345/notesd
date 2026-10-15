// Package sync serves the incremental change feed (§5.2).
//
// Each note row carries the change sequence of its latest mutation, and the
// global counter in sync_state is incremented in the same transaction as the
// write. A page is read inside one snapshot: the high-water mark H is taken
// from sync_state first, then notes with since < change_seq <= H are
// returned in sequence order. Because sequence allocation and note writes
// commit atomically, every sequence number <= H that still exists in the
// table is visible in that snapshot, so the returned cursor can never skip a
// committed change.
package sync

import (
	"context"
	"database/sql"
	"errors"

	"notes/server/internal/database"
	"notes/server/internal/notes"
)

const (
	DefaultLimit = 100
	MaxLimit     = 500
)

var (
	// ErrGenerationMismatch means the client's cursor belongs to a different
	// database lineage, typically because the server was restored from a
	// backup (§11.4). The client must run a full reconciliation.
	ErrGenerationMismatch = errors.New("sync generation mismatch")
	// ErrCursorAhead means the client's cursor is beyond anything this
	// server has issued, which also indicates a restore.
	ErrCursorAhead = errors.New("cursor is ahead of the server")
)

// Page is one batch of changes.
type Page struct {
	Changes    []notes.Note `json:"changes"`
	NextCursor int64        `json:"nextCursor"`
	HasMore    bool         `json:"hasMore"`
	Generation string       `json:"generation"`
}

// Changes returns notes changed after cursor since, oldest change first, as
// their latest state (tombstones included). generation, if non-empty, must
// match the server's sync generation.
func Changes(ctx context.Context, db *database.DB, since int64, limit int, generation string) (Page, error) {
	if since < 0 {
		since = 0
	}
	if limit <= 0 {
		limit = DefaultLimit
	}
	if limit > MaxLimit {
		limit = MaxLimit
	}

	var page Page
	err := db.ReadTx(ctx, func(tx *sql.Tx) error {
		var high int64
		if err := tx.QueryRowContext(ctx,
			`SELECT current_seq, generation FROM sync_state WHERE id = 1`).Scan(&high, &page.Generation); err != nil {
			return err
		}
		if generation != "" && generation != page.Generation {
			return ErrGenerationMismatch
		}
		if since > high {
			return ErrCursorAhead
		}

		rows, err := tx.QueryContext(ctx, `
			SELECT `+notes.Columns+` FROM notes
			WHERE change_seq > ? AND change_seq <= ?
			ORDER BY change_seq
			LIMIT ?`, since, high, limit+1)
		if err != nil {
			return err
		}
		defer rows.Close()
		page.Changes = make([]notes.Note, 0, min(limit, 64))
		for rows.Next() {
			n, err := notes.ScanNote(rows)
			if err != nil {
				return err
			}
			page.Changes = append(page.Changes, n)
		}
		if err := rows.Err(); err != nil {
			return err
		}

		rows.Close()
		for i := range page.Changes {
			if err := notes.FillImageIDs(ctx, tx, &page.Changes[i]); err != nil {
				return err
			}
		}

		if len(page.Changes) > limit {
			page.Changes = page.Changes[:limit]
			page.HasMore = true
			page.NextCursor = page.Changes[limit-1].ChangeSeq
		} else {
			// Everything up to H is in this response (sequences whose notes
			// changed again later now live at higher sequences <= H, or
			// beyond H and will be fetched next time), so the cursor may
			// jump to H.
			page.NextCursor = high
		}
		return nil
	})
	return page, err
}

// Generation returns the server's current sync generation.
func Generation(ctx context.Context, db *database.DB) (string, error) {
	var g string
	err := db.R.QueryRowContext(ctx, `SELECT generation FROM sync_state WHERE id = 1`).Scan(&g)
	return g, err
}
