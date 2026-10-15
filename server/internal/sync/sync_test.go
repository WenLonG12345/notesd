package sync_test

import (
	"context"
	"errors"
	"fmt"
	"math/rand"
	"path/filepath"
	"strings"
	gosync "sync"
	"testing"

	"notes/server/internal/database"
	"notes/server/internal/locks"
	"notes/server/internal/notes"
	notesync "notes/server/internal/sync"
)

var ctx = context.Background()

func setup(t *testing.T) (*database.DB, *notes.Store) {
	t.Helper()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db, notes.NewStore(db)
}

func mustCreate(t *testing.T, s *notes.Store, id string) {
	t.Helper()
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "c-" + id, ID: id, Title: id}); err != nil {
		t.Fatal(err)
	}
}

func TestPagingAndCursor(t *testing.T) {
	db, s := setup(t)
	for i := 0; i < 7; i++ {
		mustCreate(t, s, fmt.Sprintf("n%d", i))
	}

	p, err := notesync.Changes(ctx, db, 0, 3, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(p.Changes) != 3 || !p.HasMore || p.NextCursor != 3 || p.Generation == "" {
		t.Fatalf("page 1: %+v", p)
	}
	gen := p.Generation

	// n1 changes between pages: it moves to the end of the feed.
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u", ID: "n1", BaseRevision: 1, Title: "n1", Content: "edited"}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: "d", ID: "n4", BaseRevision: 1}); err != nil {
		t.Fatal(err)
	}

	p, err = notesync.Changes(ctx, db, p.NextCursor, 3, gen)
	if err != nil {
		t.Fatal(err)
	}
	if ids(p) != "n3,n5,n6" || !p.HasMore || p.NextCursor != 7 {
		t.Fatalf("page 2: %s %+v", ids(p), p)
	}
	p, err = notesync.Changes(ctx, db, p.NextCursor, 3, gen)
	if err != nil {
		t.Fatal(err)
	}
	if ids(p) != "n1,n4" || p.HasMore || p.NextCursor != 9 {
		t.Fatalf("page 3: %s %+v", ids(p), p)
	}
	if p.Changes[0].Content != "edited" || p.Changes[1].DeletedAt == nil {
		t.Fatalf("latest state not returned: %+v", p.Changes)
	}

	// Caught up: empty page, cursor stays at H.
	p, err = notesync.Changes(ctx, db, 9, 3, gen)
	if err != nil || len(p.Changes) != 0 || p.HasMore || p.NextCursor != 9 {
		t.Fatalf("caught up: %+v %v", p, err)
	}
}

func TestCursorJumpsToHighWaterMark(t *testing.T) {
	db, s := setup(t)
	mustCreate(t, s, "a")
	mustCreate(t, s, "b")
	// a's seq 1 is vacated; the only rows are at 2 (b) and 3 (a).
	if _, err := s.Update(ctx, notes.UpdateInput{OperationID: "u", ID: "a", BaseRevision: 1, Title: "a", Content: "x"}); err != nil {
		t.Fatal(err)
	}
	p, err := notesync.Changes(ctx, db, 0, 100, "")
	if err != nil || len(p.Changes) != 2 || p.NextCursor != 3 || p.HasMore {
		t.Fatalf("%+v %v", p, err)
	}
}

func TestRestoreDetection(t *testing.T) {
	db, s := setup(t)
	mustCreate(t, s, "a")
	if _, err := notesync.Changes(ctx, db, 0, 10, "not-the-generation"); !errors.Is(err, notesync.ErrGenerationMismatch) {
		t.Errorf("generation mismatch: %v", err)
	}
	if _, err := notesync.Changes(ctx, db, 50, 10, ""); !errors.Is(err, notesync.ErrCursorAhead) {
		t.Errorf("cursor ahead: %v", err)
	}
}

// replica is a minimal client that only downloads: it applies each page and
// then advances its cursor, as §5.2 requires.
type replica struct {
	cursor int64
	notes  map[string]notes.Note
}

func (r *replica) pull(t *testing.T, db *database.DB, limit int) {
	t.Helper()
	for i := 0; ; i++ {
		if i > 100_000 {
			t.Fatal("pull does not terminate")
		}
		p, err := notesync.Changes(ctx, db, r.cursor, limit, "")
		if err != nil {
			t.Fatal(err)
		}
		for _, n := range p.Changes {
			if old, ok := r.notes[n.ID]; ok && old.Revision > n.Revision {
				t.Fatalf("revision went backwards for %s: %d -> %d", n.ID, old.Revision, n.Revision)
			}
			r.notes[n.ID] = n
		}
		if p.NextCursor < r.cursor {
			t.Fatalf("cursor went backwards: %d -> %d", r.cursor, p.NextCursor)
		}
		r.cursor = p.NextCursor
		if !p.HasMore {
			return
		}
	}
}

// Readers paging through the feed while writers mutate concurrently must
// never skip a committed change: once writers stop, one more pull makes
// every replica identical to the server.
func TestConcurrentWritersNeverSkipChanges(t *testing.T) {
	db, s := setup(t)

	const writers, ops = 4, 150
	stop := make(chan struct{})
	var readers gosync.WaitGroup
	reps := make([]*replica, 3)
	for i := range reps {
		reps[i] = &replica{notes: map[string]notes.Note{}}
		readers.Add(1)
		go func(r *replica, limit int) {
			defer readers.Done()
			for {
				select {
				case <-stop:
					return
				default:
					r.pull(t, db, limit)
				}
			}
		}(reps[i], 1+i*4)
	}

	var wg gosync.WaitGroup
	for w := 0; w < writers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			rng := rand.New(rand.NewSource(int64(w)))
			revs := map[string]int64{}
			var live []string
			for i := 0; i < ops; i++ {
				op := fmt.Sprintf("w%d-%d", w, i)
				switch k := rng.Intn(10); {
				case k < 4 || len(live) == 0:
					id := "note-" + op
					if _, err := s.Create(ctx, notes.CreateInput{OperationID: op, ID: id, Title: id}); err != nil {
						t.Error(err)
						return
					}
					revs[id] = 1
					live = append(live, id)
				case k < 9:
					id := live[rng.Intn(len(live))]
					r, err := s.Update(ctx, notes.UpdateInput{OperationID: op, ID: id, BaseRevision: revs[id], Title: id, Content: op})
					if err != nil {
						t.Error(err)
						return
					}
					revs[id] = r.Revision
				default:
					j := rng.Intn(len(live))
					id := live[j]
					if _, err := s.Delete(ctx, notes.DeleteInput{OperationID: op, ID: id, BaseRevision: revs[id]}); err != nil {
						t.Error(err)
						return
					}
					live = append(live[:j], live[j+1:]...)
				}
			}
		}(w)
	}
	wg.Wait()
	close(stop)
	readers.Wait()

	server := map[string]notes.Note{}
	rows, err := db.R.Query(`SELECT ` + notes.Columns + ` FROM notes`)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		n, err := notes.ScanNote(rows)
		if err != nil {
			t.Fatal(err)
		}
		server[n.ID] = n
	}
	rows.Close()

	for i, r := range reps {
		r.pull(t, db, 7)
		if len(r.notes) != len(server) {
			t.Fatalf("replica %d has %d notes, server %d", i, len(r.notes), len(server))
		}
		for id, want := range server {
			got := r.notes[id]
			if got.Revision != want.Revision || got.Content != want.Content || (got.DeletedAt == nil) != (want.DeletedAt == nil) {
				t.Fatalf("replica %d diverged on %s: got %+v want %+v", i, id, got, want)
			}
		}
	}
}

func ids(p notesync.Page) string {
	s := ""
	for i, n := range p.Changes {
		if i > 0 {
			s += ","
		}
		s += n.ID
	}
	return s
}

func TestLockedNotesCarryTheirImageIDs(t *testing.T) {
	db, s := setup(t)
	id := strings.Repeat("a", 64)
	if _, err := s.Create(ctx, notes.CreateInput{OperationID: "c-l", ID: "l", Title: "L",
		Content: locks.Prefix + "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0", ImageIDs: []string{id}}); err != nil {
		t.Fatal(err)
	}
	mustCreate(t, s, "p")
	page, err := notesync.Changes(ctx, db, 0, 10, "")
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Changes) != 2 || len(page.Changes[0].ImageIDs) != 1 || page.Changes[0].ImageIDs[0] != id || page.Changes[1].ImageIDs != nil {
		t.Fatalf("changes: %+v", page.Changes)
	}
}
