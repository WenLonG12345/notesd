package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
)

var ctx = context.Background()

// newDB returns a fresh database and a function that adds a note, each a second after the last.
func newDB(t *testing.T) (*database.DB, func(id, title, content string)) {
	t.Helper()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s := notes.NewStore(db)
	at := int64(1_700_000_000_000)
	return db, func(id, title, content string) {
		t.Helper()
		at += 1000
		ts := at
		if _, err := s.Create(ctx, notes.CreateInput{OperationID: "op-" + id, ID: id, Title: title, Content: content, CreatedAt: &ts, UpdatedAt: &ts}); err != nil {
			t.Fatal(err)
		}
	}
}

func TestExportNotes(t *testing.T) {
	db, create := newDB(t)
	s := notes.NewStore(db)
	create("a", "Groceries", "- milk #home\n")
	create("b", "groceries", "second\n")
	create("c", "  ", "no title\n")
	create("d", "a/b: c?", "odd name\n")
	create("e", "Bank", locks.Prefix+"c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0")
	create("f", "Gone", "deleted\n")
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "del-f", ID: "f", BaseRevision: 1}); err != nil {
		t.Fatal(err)
	}

	dir := filepath.Join(t.TempDir(), "out")
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	res, err := writeExport(dir, all, nil)
	if err != nil {
		t.Fatal(err)
	}
	if res != (exported{Written: 4, Locked: 1}) {
		t.Fatalf("result = %+v", res)
	}

	entries, _ := os.ReadDir(dir)
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	slices.Sort(names)
	if want := []string{"Groceries.md", "Untitled.md", "a b c.md", "groceries (2).md"}; !slices.Equal(names, want) {
		t.Fatalf("files = %q, want %q", names, want)
	}
	for name, want := range map[string]string{
		"Groceries.md":     "# Groceries\n\n- milk #home\n",
		"groceries (2).md": "# groceries\n\nsecond\n",
		"Untitled.md":      "no title\n",
	} {
		got, _ := os.ReadFile(filepath.Join(dir, name))
		if string(got) != want {
			t.Errorf("%s = %q, want %q", name, got, want)
		}
	}
	info, _ := os.Stat(filepath.Join(dir, "Groceries.md"))
	if !info.ModTime().Equal(time.UnixMilli(1_700_000_001_000)) {
		t.Errorf("mtime = %v", info.ModTime())
	}

	if _, err := writeExport(dir, all, nil); err == nil || !strings.Contains(err.Error(), "not empty") {
		t.Fatalf("second export into the same dir: %v", err)
	}
}

func TestFileName(t *testing.T) {
	for title, want := range map[string]string{
		"Plain":                       "Plain",
		"":                            "Untitled",
		"...":                         "Untitled",
		".hidden":                     "hidden",
		"trailing. ":                  "trailing",
		"tab\tand\nnewline":           "tab and newline",
		`<all>|"bad"*chars?`:          "all bad chars",
		strings.Repeat("é", 60):       strings.Repeat("é", 50),
		strings.Repeat("x", 99) + "é": strings.Repeat("x", 99),
	} {
		if got := fileName(title); got != want {
			t.Errorf("fileName(%q) = %q, want %q", title, got, want)
		}
	}
}

// The shared crypto fixtures: a keyring whose password entry opens with
// fixturePassword, and envelopes sealed with the key it holds.
const fixturePassword = "correct horse battery staple"

type cryptoFixtures struct {
	KDF []struct {
		Password, Salt string
		Iterations     int
	}
	Wraps     []struct{ Kind, Wrapped string }
	Envelopes []struct{ Name, Plaintext, Envelope string }
	Invalid   []struct{ Name, Envelope string }
}

func loadCryptoFixtures(t *testing.T) cryptoFixtures {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f cryptoFixtures
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	if f.KDF[0].Password != fixturePassword || f.Wraps[0].Kind != "password" {
		t.Fatal("fixtures changed shape")
	}
	return f
}

func TestExportLockedNotes(t *testing.T) {
	f := loadCryptoFixtures(t)
	db, create := newDB(t)
	envelope := func(name string) string {
		for _, e := range f.Envelopes {
			if e.Name == name {
				return e.Envelope
			}
		}
		for _, e := range f.Invalid {
			if e.Name == name {
				return e.Envelope
			}
		}
		t.Fatalf("no fixture %q", name)
		return ""
	}
	create("a", "Bank", envelope("markdown"))
	create("b", "Broken", envelope("tampered tag"))
	create("c", "Open", "plain\n")
	all, err := liveNotes(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	stdin, _, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer stdin.Close()

	// The password is typed, so a pipe is refused.
	if _, err := askLockPassword("out", 2, stdin, io.Discard); err == nil || !strings.Contains(err.Error(), "terminal") {
		t.Fatalf("not a terminal: %v", err)
	}

	if _, err := lockKey(ctx, db, fixturePassword); err == nil || !strings.Contains(err.Error(), "no lock password") {
		t.Fatalf("no keyring: %v", err)
	}
	k := f.KDF[0]
	if _, err := db.W.ExecContext(ctx, `INSERT INTO lock_keys (id, kind, salt, iterations, wrapped, created_at)
		VALUES ('password', 'password', ?, ?, ?, 0)`, k.Salt, k.Iterations, f.Wraps[0].Wrapped); err != nil {
		t.Fatal(err)
	}
	if _, err := lockKey(ctx, db, "wrong"); !errors.Is(err, locks.ErrWrongPassword) {
		t.Fatalf("wrong password: %v", err)
	}
	key, err := lockKey(ctx, db, fixturePassword)
	if err != nil {
		t.Fatal(err)
	}

	dir := filepath.Join(t.TempDir(), "out")
	res, err := writeExport(dir, all, key)
	if err != nil {
		t.Fatal(err)
	}
	if res != (exported{Written: 2, Unreadable: 1}) {
		t.Fatalf("result = %+v", res)
	}
	got, _ := os.ReadFile(filepath.Join(dir, "Bank.md"))
	if want := "# Bank\n\n" + f.Envelopes[1].Plaintext; f.Envelopes[1].Name != "markdown" || string(got) != want {
		t.Fatalf("Bank.md = %q, want %q", got, want)
	}
}
