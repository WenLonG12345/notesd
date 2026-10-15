package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"notes/server/internal/database"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
)

const maxFileNameBytes = 100

// exportNotes writes every live note to dir as Markdown, one file each.
// Locked notes are skipped: the server holds only their ciphertext.
func exportNotes(ctx context.Context, db *database.DB, dir string) (written, locked int, err error) {
	if err := prepareExportDir(dir); err != nil {
		return 0, 0, err
	}
	var all []notes.Note
	err = db.ReadTx(ctx, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT `+notes.Columns+` FROM notes
			WHERE deleted_at IS NULL ORDER BY created_at, id`)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			n, err := notes.ScanNote(rows)
			if err != nil {
				return err
			}
			all = append(all, n)
		}
		return rows.Err()
	})
	if err != nil {
		return 0, 0, fmt.Errorf("read notes: %w", err)
	}

	// Keyed case-insensitively: macOS and Windows filesystems are.
	taken := map[string]bool{}
	for _, n := range all {
		if locks.IsLocked(n.Content) {
			locked++
			continue
		}
		name := uniqueName(fileName(n.Title), taken)
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, []byte(markdown(n.Title, n.Content)), 0o600); err != nil {
			return written, locked, err
		}
		t := time.UnixMilli(n.UpdatedAt)
		if err := os.Chtimes(path, t, t); err != nil {
			return written, locked, err
		}
		written++
	}
	return written, locked, nil
}

// prepareExportDir creates dir, or accepts it if it exists and is empty, so
// an export never overwrites files or mixes with an earlier one.
func prepareExportDir(dir string) error {
	entries, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return os.MkdirAll(dir, 0o700)
	}
	if err != nil {
		return err
	}
	if len(entries) > 0 {
		return fmt.Errorf("%s is not empty; export into a new or empty directory", dir)
	}
	return nil
}

// markdown matches the web app's Copy as Markdown: the title as a heading, then the body.
func markdown(title, content string) string {
	if t := strings.TrimSpace(title); t != "" {
		return "# " + t + "\n\n" + content
	}
	return content
}

// fileName turns a title into a name safe on Linux, macOS and Windows, without the extension.
func fileName(title string) string {
	s := strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || strings.ContainsRune(`/\:*?"<>|`, r) {
			return ' '
		}
		return r
	}, title)
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > maxFileNameBytes {
		s = s[:maxFileNameBytes]
		for !utf8.ValidString(s) {
			s = s[:len(s)-1]
		}
	}
	// Leading dots hide the file; Windows drops trailing dots and spaces.
	s = strings.TrimLeft(s, ".")
	s = strings.TrimRight(s, ". ")
	if s == "" {
		return "Untitled"
	}
	return s
}

func uniqueName(base string, taken map[string]bool) string {
	name := base + ".md"
	for i := 2; taken[strings.ToLower(name)]; i++ {
		name = fmt.Sprintf("%s (%d).md", base, i)
	}
	taken[strings.ToLower(name)] = true
	return name
}
