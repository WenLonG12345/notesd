package images_test

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"notes/server/internal/database"
	"notes/server/internal/images"
)

func TestRefs(t *testing.T) {
	a, b := strings.Repeat("a", 64), strings.Repeat("b", 64)
	ref := func(id string) string { return "notesd:image/" + id + "?w=4&h=3" }
	md := "![one](" + ref(a) + ")\n\nText ![](" + ref(b) + ") and ![again](" + ref(a) + ")\n\n" +
		"`![code](" + ref(strings.Repeat("c", 64)) + ")`\n\n```\n![fenced](" + ref(strings.Repeat("d", 64)) + ")\n```\n\n" +
		"![other](cat.png) [a link](" + ref(strings.Repeat("e", 64)) + ") ![no size](notesd:image/" + strings.Repeat("f", 64) + ")\n"
	if got, want := images.Refs(md), []string{a, b}; !slices.Equal(got, want) {
		t.Fatalf("Refs = %q, want %q", got, want)
	}
	if got := images.Refs(""); got != nil {
		t.Fatalf("Refs of nothing = %q", got)
	}
}

// collector is a database with stored images and a movable clock.
type collector struct {
	t     *testing.T
	db    *database.DB
	store *images.Store
	now   time.Time
}

func newCollector(t *testing.T) *collector {
	t.Helper()
	db, err := database.Open(context.Background(), filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	c := &collector{t: t, db: db, store: images.NewStore(db), now: time.UnixMilli(1_700_000_000_000)}
	c.store.SetClock(func() time.Time { return c.now })
	return c
}

func (c *collector) put(b byte) string {
	c.t.Helper()
	data := []byte{0xFF, 0xD8, 0xFF, b}
	sum := sha256.Sum256(data)
	id := hex.EncodeToString(sum[:])
	if _, _, err := c.store.Put(ctx, id, "image/jpeg", data); err != nil {
		c.t.Fatal(err)
	}
	return id
}

// note stands in for a note at seq showing ids, as a note write records it.
func (c *collector) note(seq int64, ids ...string) {
	c.t.Helper()
	err := c.db.WriteTx(ctx, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO notes (seq, id, title, content, revision, change_seq, created_at, updated_at)
			VALUES (?, ?, '', '', 1, ?, 0, 0)`, seq, "n"+string(rune('0'+seq)), seq); err != nil {
			return err
		}
		return images.SetRefs(ctx, tx, seq, ids, c.now.UnixMilli())
	})
	if err != nil {
		c.t.Fatal(err)
	}
}

func (c *collector) collect(dryRun bool) int {
	c.t.Helper()
	n, _, err := c.store.Collect(ctx, c.now.Add(-images.Grace), dryRun)
	if err != nil {
		c.t.Fatal(err)
	}
	return n
}

func (c *collector) stored(id string) bool {
	_, _, err := c.store.Get(ctx, id)
	return err == nil
}

var ctx = context.Background()

func TestCollectWaitsForGrace(t *testing.T) {
	c := newCollector(t)
	shown, dropped, abandoned := c.put(1), c.put(2), c.put(3)
	c.note(1, shown, dropped)

	c.now = c.now.Add(10 * 24 * time.Hour)
	c.note(1, shown) // dropped is released now; abandoned was at upload
	c.now = c.now.Add(25 * 24 * time.Hour)
	if n := c.collect(false); n != 1 || c.stored(abandoned) || !c.stored(dropped) {
		t.Fatalf("after 35 days: collected %d; abandoned stored %v, dropped stored %v", n, c.stored(abandoned), c.stored(dropped))
	}

	c.now = c.now.Add(6 * 24 * time.Hour)
	if n := c.collect(true); n != 1 || !c.stored(dropped) {
		t.Fatalf("dry run: %d, still stored %v", n, c.stored(dropped))
	}
	if n := c.collect(false); n != 1 || c.stored(dropped) {
		t.Fatalf("dropped 31 days ago: collected %d", n)
	}
	if !c.stored(shown) {
		t.Fatal("a shown image was deleted")
	}
}

func TestCollectSparesSharedAndReuploadedImages(t *testing.T) {
	c := newCollector(t)
	shared, again := c.put(1), c.put(2)
	c.note(1, shared, again)
	c.note(2, shared)
	c.note(1) // both released by note 1; note 2 still shows shared

	c.now = c.now.Add(29 * 24 * time.Hour)
	// The picture is added again: the client re-uploads before saving.
	if _, created, err := c.store.Put(ctx, again, "image/jpeg", []byte{0xFF, 0xD8, 0xFF, 2}); err != nil || created {
		t.Fatalf("re-upload: %v %v", created, err)
	}
	c.now = c.now.Add(2 * 24 * time.Hour)
	if n := c.collect(false); n != 0 {
		t.Fatalf("collected %d", n)
	}
	if !c.stored(shared) || !c.stored(again) {
		t.Fatal("a shown or re-uploaded image was deleted")
	}
}

func TestCollectKeepsImagesFromBeforeTracking(t *testing.T) {
	c := newCollector(t)
	old := c.put(1)
	locked := c.put(2)
	if _, err := c.db.W.ExecContext(ctx, `UPDATE images SET released_at = NULL`); err != nil {
		t.Fatal(err)
	}
	if _, err := c.db.W.ExecContext(ctx, `UPDATE images SET content_type = ? WHERE id = ?`, images.Locked, locked); err != nil {
		t.Fatal(err)
	}
	c.now = c.now.Add(365 * 24 * time.Hour)
	if n := c.collect(false); n != 0 {
		t.Fatalf("collected %d untracked images", n)
	}

	// The backfill releases a plain one no note shows; a locked one waits for its note.
	if err := c.db.WriteTx(ctx, func(tx *sql.Tx) error { return images.ReleaseUntracked(ctx, tx, c.now.UnixMilli()) }); err != nil {
		t.Fatal(err)
	}
	c.now = c.now.Add(31 * 24 * time.Hour)
	if n := c.collect(false); n != 1 || c.stored(old) || !c.stored(locked) {
		t.Fatalf("collected %d; plain stored %v, locked stored %v", n, c.stored(old), c.stored(locked))
	}
}

func TestCollectInBatches(t *testing.T) {
	c := newCollector(t)
	for i := range 250 {
		c.put(byte(i))
	}
	c.now = c.now.Add(31 * 24 * time.Hour)
	if n := c.collect(false); n != 250 {
		t.Fatalf("collected %d of 250", n)
	}
}

func TestMissing(t *testing.T) {
	c := newCollector(t)
	stored := c.put(1)
	gone, goneLocked, onlyRecorded := strings.Repeat("0", 64), strings.Repeat("1", 64), strings.Repeat("2", 64)
	ref := func(id string) string { return "![](notesd:image/" + id + "?w=4&h=3)\n" }
	add := func(seq int64, content string) {
		t.Helper()
		if _, err := c.db.W.ExecContext(ctx, `INSERT INTO notes (seq, id, title, content, revision, change_seq, created_at, updated_at)
			VALUES (?, ?, '', ?, 1, ?, 0, 0)`, seq, "n"+string(rune('0'+seq)), content, seq); err != nil {
			t.Fatal(err)
		}
	}
	// A plain note is read from its content, even before the backfill has recorded it.
	add(1, ref(stored)+ref(gone))
	// A locked note only has its record.
	add(2, "notesd:locked:v1:c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0")
	c.note(2, stored, goneLocked)
	// A stale record for a plain note isn't what it shows.
	c.note(1, onlyRecorded)
	if got, err := c.store.Missing(ctx); err != nil || !slices.Equal(got, []string{gone, goneLocked}) {
		t.Fatalf("Missing = %q, %v", got, err)
	}
}
