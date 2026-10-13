package search

import (
	"context"
	"database/sql"
	"html"
	"strings"
	"unicode"

	"notes/server/internal/database"
)

// Per-column BM25 weights (§8.2).
const (
	titleWeight   = 10.0
	contentWeight = 1.0
	maxTerms      = 16
	MaxLimit      = 50
)

// Sentinels marking highlights inside snippet() output. They are private-use
// code points, so after HTML-escaping the excerpt they can be swapped for
// <mark> tags without letting note text inject markup.
const (
	markOpen  = ""
	markClose = ""
)

// Result is one search hit. Excerpt is HTML-safe: note text is escaped and
// matches are wrapped in <mark>…</mark>.
type Result struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Excerpt   string `json:"excerpt"`
	UpdatedAt int64  `json:"updatedAt"`
}

// BuildQuery turns free-form user input into an FTS5 MATCH expression. Each
// word becomes a quoted prefix term, so FTS5 operators and syntax characters
// in the input are inert and malformed input can never produce a query
// error. Terms are ANDed. It returns "" when the input has no searchable
// words.
func BuildQuery(input string) string {
	words := strings.FieldsFunc(input, func(r rune) bool {
		return !(unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.Is(unicode.Mn, r))
	})
	if len(words) > maxTerms {
		words = words[:maxTerms]
	}
	terms := make([]string, 0, len(words))
	for _, w := range words {
		// Words contain only letters, digits and combining marks, so they
		// cannot contain a double quote; quoting makes keywords like OR and
		// NEAR literal.
		terms = append(terms, `"`+w+`"*`)
	}
	return strings.Join(terms, " ")
}

// Search runs a ranked full-text search over live notes.
func Search(ctx context.Context, db *database.DB, input string, limit, offset int) ([]Result, error) {
	match := BuildQuery(input)
	if match == "" {
		return []Result{}, nil
	}
	if limit <= 0 || limit > MaxLimit {
		limit = MaxLimit
	}
	if offset < 0 {
		offset = 0
	}
	rows, err := db.R.QueryContext(ctx, `
		SELECT n.id, n.title, n.updated_at,
		       snippet(notes_fts, 1, ?, ?, '…', 20)
		FROM notes_fts
		JOIN notes n ON n.seq = notes_fts.rowid
		WHERE notes_fts MATCH ?
		  AND n.deleted_at IS NULL
		ORDER BY bm25(notes_fts, ?, ?), n.updated_at DESC
		LIMIT ? OFFSET ?`,
		markOpen, markClose, match, titleWeight, contentWeight, limit, offset)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	results := []Result{}
	for rows.Next() {
		var r Result
		var excerpt sql.NullString
		if err := rows.Scan(&r.ID, &r.Title, &r.UpdatedAt, &excerpt); err != nil {
			return nil, err
		}
		r.Excerpt = renderExcerpt(excerpt.String)
		results = append(results, r)
	}
	return results, rows.Err()
}

func renderExcerpt(s string) string {
	s = html.EscapeString(s)
	s = strings.ReplaceAll(s, markOpen, "<mark>")
	return strings.ReplaceAll(s, markClose, "</mark>")
}

// IndexNote replaces the index entry for the note stored at rowid. It must run
// in the same transaction as the write to notes.
func IndexNote(ctx context.Context, tx *sql.Tx, rowid int64, title, markdown string) error {
	if err := UnindexNote(ctx, tx, rowid); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx,
		`INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)`,
		rowid, title, PlainText(markdown))
	return err
}

// UnindexNote removes the index entry for rowid, if any.
func UnindexNote(ctx context.Context, tx *sql.Tx, rowid int64) error {
	_, err := tx.ExecContext(ctx, `DELETE FROM notes_fts WHERE rowid = ?`, rowid)
	return err
}

// Rebuild recreates the whole index from the notes table without touching
// sync state (§8.1).
func Rebuild(ctx context.Context, db *database.DB) (int, error) {
	n := 0
	err := db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM notes_fts`); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, `SELECT seq, title, content FROM notes WHERE deleted_at IS NULL`)
		if err != nil {
			return err
		}
		type doc struct {
			seq            int64
			title, content string
		}
		var docs []doc
		for rows.Next() {
			var d doc
			if err := rows.Scan(&d.seq, &d.title, &d.content); err != nil {
				rows.Close()
				return err
			}
			docs = append(docs, d)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, d := range docs {
			if _, err := tx.ExecContext(ctx,
				`INSERT INTO notes_fts (rowid, title, body_text) VALUES (?, ?, ?)`,
				d.seq, d.title, PlainText(d.content)); err != nil {
				return err
			}
		}
		n = len(docs)
		_, err = tx.ExecContext(ctx, `INSERT INTO notes_fts (notes_fts) VALUES ('optimize')`)
		return err
	})
	return n, err
}
