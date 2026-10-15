package main

import (
	"context"
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

func TestExportNotes(t *testing.T) {
	ctx := context.Background()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s := notes.NewStore(db)
	at := int64(1_700_000_000_000)
	create := func(id, title, content string) {
		t.Helper()
		at += 1000
		ts := at
		if _, err := s.Create(ctx, notes.CreateInput{OperationID: "op-" + id, ID: id, Title: title, Content: content, CreatedAt: &ts, UpdatedAt: &ts}); err != nil {
			t.Fatal(err)
		}
	}
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
	written, locked, err := exportNotes(ctx, db, dir)
	if err != nil {
		t.Fatal(err)
	}
	if written != 4 || locked != 1 {
		t.Fatalf("written, locked = %d, %d", written, locked)
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

	if _, _, err := exportNotes(ctx, db, dir); err == nil || !strings.Contains(err.Error(), "not empty") {
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
