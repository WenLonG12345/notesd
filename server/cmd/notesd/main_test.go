package main

import (
	"context"
	"io"
	"log/slog"
	"net"
	"net/url"
	"path/filepath"
	"testing"

	"notes/server/internal/auth"
	"notes/server/internal/database"
)

func TestAppURL(t *testing.T) {
	tcp := func(ip string, port int) net.Addr { return &net.TCPAddr{IP: net.ParseIP(ip), Port: port} }
	public, _ := url.Parse("https://notes.example.com/")
	under, _ := url.Parse("https://example.com/notes")
	for _, c := range []struct {
		public *url.URL
		addr   net.Addr
		want   string
	}{
		{nil, tcp("127.0.0.1", 8080), "http://127.0.0.1:8080/"},
		{nil, tcp("0.0.0.0", 8080), "http://localhost:8080/"},
		{nil, tcp("::", 9000), "http://localhost:9000/"},
		{nil, tcp("::1", 8080), "http://[::1]:8080/"},
		{public, tcp("127.0.0.1", 8080), "https://notes.example.com/"},
		{under, tcp("127.0.0.1", 8080), "https://example.com/notes/"},
	} {
		if got := appURL(c.public, c.addr); got != c.want {
			t.Errorf("appURL(%v, %v) = %q, want %q", c.public, c.addr, got, c.want)
		}
	}
}

func TestOwnerFromEnv(t *testing.T) {
	ctx := context.Background()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	newAuth := func(t *testing.T) *auth.Service {
		db, err := database.Open(ctx, filepath.Join(t.TempDir(), "notes.db"))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { db.Close() })
		return auth.NewService(db)
	}

	t.Run("unset leaves setup open", func(t *testing.T) {
		t.Setenv("NOTESD_OWNER_USERNAME", "")
		t.Setenv("NOTESD_OWNER_PASSWORD", "")
		if has, err := ownerFromEnv(ctx, newAuth(t), log); err != nil || has {
			t.Fatalf("got %v, %v; want no owner", has, err)
		}
	})
	t.Run("creates the owner once", func(t *testing.T) {
		a := newAuth(t)
		t.Setenv("NOTESD_OWNER_USERNAME", "owner")
		t.Setenv("NOTESD_OWNER_PASSWORD", "correct horse battery")
		if has, err := ownerFromEnv(ctx, a, log); err != nil || !has {
			t.Fatalf("got %v, %v; want an owner", has, err)
		}
		// A later start with other values keeps the first account.
		t.Setenv("NOTESD_OWNER_PASSWORD", "something else entirely")
		if has, err := ownerFromEnv(ctx, a, log); err != nil || !has {
			t.Fatalf("second start: got %v, %v", has, err)
		}
		if err := a.CheckPassword(ctx, "correct horse battery"); err != nil {
			t.Fatalf("login with the first password: %v", err)
		}
	})
	t.Run("one without the other is an error", func(t *testing.T) {
		t.Setenv("NOTESD_OWNER_USERNAME", "owner")
		t.Setenv("NOTESD_OWNER_PASSWORD", "")
		if _, err := ownerFromEnv(ctx, newAuth(t), log); err == nil {
			t.Fatal("want an error")
		}
	})
	t.Run("a weak password stops the start", func(t *testing.T) {
		t.Setenv("NOTESD_OWNER_USERNAME", "owner")
		t.Setenv("NOTESD_OWNER_PASSWORD", "short")
		if _, err := ownerFromEnv(ctx, newAuth(t), log); err == nil {
			t.Fatal("want an error")
		}
	})
}
