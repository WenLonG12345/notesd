package images_test

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"path/filepath"
	"testing"

	"notes/server/internal/database"
	"notes/server/internal/images"
)

func TestCheckFindsDamagedImages(t *testing.T) {
	ctx := context.Background()
	db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := images.NewStore(db)

	gif := []byte("GIF89a\x01\x00\x01\x00")
	sum := sha256.Sum256(gif)
	id := hex.EncodeToString(sum[:])
	if _, created, err := s.Put(ctx, id, "image/gif", gif); err != nil || !created {
		t.Fatalf("put: %v %v", created, err)
	}
	if bad, err := s.Check(ctx); err != nil || len(bad) != 0 {
		t.Fatalf("intact: %v %v", bad, err)
	}
	if _, err := db.W.ExecContext(ctx, `UPDATE images SET data = ? WHERE id = ?`, []byte("GIF89a\x02"), id); err != nil {
		t.Fatal(err)
	}
	if bad, err := s.Check(ctx); err != nil || len(bad) != 1 || bad[0] != id {
		t.Fatalf("damaged: %v %v", bad, err)
	}
}
