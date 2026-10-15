package main

import (
	"bufio"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/term"

	"notes/server/internal/database"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
)

const maxFileNameBytes = 100

// exported counts what an export wrote and what it left out.
type exported struct {
	Written    int
	Locked     int // skipped: no key given
	Unreadable int // locked, but the key didn't open them
}

func liveNotes(ctx context.Context, db *database.DB) ([]notes.Note, error) {
	var all []notes.Note
	err := db.ReadTx(ctx, func(tx *sql.Tx) error {
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
		return nil, fmt.Errorf("read notes: %w", err)
	}
	return all, nil
}

// writeExport writes each note to dir as Markdown, one file each. Locked
// notes are decrypted with key, or skipped without one.
func writeExport(dir string, all []notes.Note, key []byte) (exported, error) {
	var res exported
	if err := checkExportDir(dir); err != nil {
		return res, err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return res, err
	}
	// Keyed case-insensitively: macOS and Windows filesystems are.
	taken := map[string]bool{}
	for _, n := range all {
		content := n.Content
		if locks.IsLocked(content) {
			if key == nil {
				res.Locked++
				continue
			}
			plain, err := locks.Decrypt(key, content)
			if err != nil {
				res.Unreadable++
				continue
			}
			content = plain
		}
		path := filepath.Join(dir, uniqueName(fileName(n.Title), taken))
		if err := os.WriteFile(path, []byte(markdown(n.Title, content)), 0o600); err != nil {
			return res, err
		}
		t := time.UnixMilli(n.UpdatedAt)
		if err := os.Chtimes(path, t, t); err != nil {
			return res, err
		}
		res.Written++
	}
	return res, nil
}

// checkExportDir accepts a missing or empty dir, so an export never
// overwrites files or mixes with an earlier one.
func checkExportDir(dir string) error {
	entries, err := os.ReadDir(dir)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if len(entries) > 0 {
		return fmt.Errorf("%s is not empty; export into a new or empty directory", dir)
	}
	return nil
}

// askLockPassword has the owner confirm that locked notes will be written as
// plain text, then reads the lock password. Typed every time, never from the
// environment, so a script or a leaked variable can't decrypt the notes.
func askLockPassword(dir string, locked int, stdin *os.File, stderr io.Writer) (string, error) {
	fd := int(stdin.Fd())
	if !term.IsTerminal(fd) {
		return "", errors.New("--include-locked needs a terminal to type the lock password at")
	}
	fmt.Fprintf(stderr, "%d locked notes, currently encrypted, will be written to %s as plain text.\n"+
		"Anyone who can read that folder can read them. Continue? [y/N] ", locked, dir)
	answer, err := bufio.NewReader(stdin).ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return "", err
	}
	if a := strings.ToLower(strings.TrimSpace(answer)); a != "y" && a != "yes" {
		return "", errors.New("export cancelled; nothing was written")
	}
	fmt.Fprint(stderr, "lock password: ")
	raw, err := term.ReadPassword(fd)
	fmt.Fprintln(stderr)
	if err != nil {
		return "", fmt.Errorf("read lock password: %w", err)
	}
	return string(raw), nil
}

// lockKey opens the locked-notes key with the lock password.
func lockKey(ctx context.Context, db *database.DB, password string) ([]byte, error) {
	keys, err := locks.NewStore(db).List(ctx)
	if err != nil {
		return nil, err
	}
	key, err := locks.UnlockWithPassword(keys, password)
	if errors.Is(err, locks.ErrNoPassword) {
		return nil, errors.New("there is no lock password on this server to open locked notes with")
	}
	return key, err
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
