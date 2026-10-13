// Command notes-server is the self-hosted notes backend: the JSON API, the
// sync endpoint, full-text search and the embedded web app, in one process
// backed by one SQLite database.
//
// Usage:
//
//	notes-server [serve] [flags]          run the server (default)
//	notes-server create-owner --username NAME
//	notes-server reset-password
//	notes-server reindex                  rebuild the search index
//	notes-server check                    run an integrity check
//	notes-server version
//
// Flags may also be set through environment variables: NOTES_DATA_DIR,
// NOTES_LISTEN, NOTES_PUBLIC_URL, NOTES_LOG_LEVEL, NOTES_LOG_FORMAT,
// NOTES_SETUP_TOKEN. Commands that take a password read it from
// NOTES_PASSWORD or, failing that, from the first line of standard input.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"notes/server/internal/api"
	"notes/server/internal/auth"
	"notes/server/internal/database"
	"notes/server/internal/notes"
	"notes/server/internal/search"
)

// version is set at build time: -ldflags "-X main.version=v1.2.3".
var version = "dev"

func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "notes-server:", err)
		os.Exit(1)
	}
}

type options struct {
	dataDir    string
	listen     string
	publicURL  string
	logLevel   string
	logFormat  string
	setupToken string
	username   string
}

func run(args []string) error {
	cmd := "serve"
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}

	var o options
	fs := flag.NewFlagSet("notes-server "+cmd, flag.ContinueOnError)
	fs.StringVar(&o.dataDir, "data-dir", env("NOTES_DATA_DIR", "./data"), "directory holding notes.db")
	switch cmd {
	case "serve":
		fs.StringVar(&o.listen, "listen", env("NOTES_LISTEN", "127.0.0.1:8080"), "address to listen on")
		fs.StringVar(&o.publicURL, "public-url", env("NOTES_PUBLIC_URL", ""), "externally visible URL, e.g. https://notes.example.com (enables Secure cookies when https)")
		fs.StringVar(&o.logLevel, "log-level", env("NOTES_LOG_LEVEL", "info"), "debug, info, warn or error")
		fs.StringVar(&o.logFormat, "log-format", env("NOTES_LOG_FORMAT", "text"), "text or json")
		fs.StringVar(&o.setupToken, "setup-token", env("NOTES_SETUP_TOKEN", ""), "first-run setup token (generated and logged if empty)")
	case "create-owner":
		fs.StringVar(&o.username, "username", "", "owner username")
	case "reset-password", "reindex", "check":
	case "version":
		fmt.Println("notes-server", version)
		return nil
	case "help", "-h", "--help":
		fmt.Fprintln(os.Stderr, "commands: serve, create-owner, reset-password, reindex, check, version")
		return nil
	default:
		return fmt.Errorf("unknown command %q (commands: serve, create-owner, reset-password, reindex, check, version)", cmd)
	}
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return nil
		}
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	dbPath := filepath.Join(o.dataDir, "notes.db")
	db, err := database.Open(ctx, dbPath)
	if err != nil {
		return fmt.Errorf("open database %s: %w", dbPath, err)
	}
	defer db.Close()

	// check must leave the database untouched.
	if cmd != "check" {
		if _, err := search.RebuildIfNeeded(ctx, db); err != nil {
			return fmt.Errorf("rebuild search index: %w", err)
		}
	}

	switch cmd {
	case "serve":
		return serve(ctx, db, o)
	case "create-owner":
		if o.username == "" {
			return errors.New("--username is required")
		}
		pw, err := readPassword()
		if err != nil {
			return err
		}
		if err := auth.NewService(db).CreateOwner(ctx, o.username, pw); err != nil {
			return err
		}
		fmt.Println("owner account created")
	case "reset-password":
		pw, err := readPassword()
		if err != nil {
			return err
		}
		if err := auth.NewService(db).ResetPassword(ctx, pw); err != nil {
			return err
		}
		fmt.Println("password updated; all sessions signed out")
	case "reindex":
		n, err := search.Rebuild(ctx, db)
		if err != nil {
			return err
		}
		fmt.Printf("search index rebuilt (%d notes)\n", n)
	case "check":
		problems, err := db.IntegrityCheck(ctx)
		if err != nil {
			return err
		}
		if len(problems) > 0 {
			for _, p := range problems {
				fmt.Fprintln(os.Stderr, p)
			}
			return fmt.Errorf("integrity check found %d problem(s); restore from a backup — the database has not been modified", len(problems))
		}
		fmt.Println("ok")
	}
	return nil
}

func serve(ctx context.Context, db *database.DB, o options) error {
	log, err := newLogger(o.logLevel, o.logFormat)
	if err != nil {
		return err
	}
	slog.SetDefault(log)

	var public *url.URL
	if o.publicURL != "" {
		public, err = url.Parse(o.publicURL)
		if err != nil || (public.Scheme != "http" && public.Scheme != "https") || public.Host == "" {
			return fmt.Errorf("--public-url must be an absolute http(s) URL, got %q", o.publicURL)
		}
	}

	authSvc := auth.NewService(db)
	token, err := authSvc.PrepareSetup(ctx, o.setupToken)
	if err != nil {
		return err
	}
	if token != "" {
		log.Warn("no owner account yet: open the web app and enter this setup token, "+
			"or run `notes-server create-owner --username NAME`",
			"setup_token", token)
	}

	srv := &http.Server{
		Handler:           api.New(db, notes.NewStore(db), authSvc, log, api.Config{PublicURL: public}).Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    64 << 10,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}
	ln, err := net.Listen("tcp", o.listen)
	if err != nil {
		return err
	}
	log.Info("notes-server started", "version", version, "listen", ln.Addr().String(),
		"database", db.Path, "schema_version", database.LatestVersion())

	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()

	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	// In-flight requests finish (and their transactions commit or roll back)
	// before the database is closed by the caller.
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Warn("forced shutdown", "error", err)
	}
	return nil
}

func newLogger(level, format string) (*slog.Logger, error) {
	var lv slog.Level
	if err := lv.UnmarshalText([]byte(level)); err != nil {
		return nil, fmt.Errorf("invalid log level %q", level)
	}
	opts := &slog.HandlerOptions{Level: lv}
	switch format {
	case "text":
		return slog.New(slog.NewTextHandler(os.Stderr, opts)), nil
	case "json":
		return slog.New(slog.NewJSONHandler(os.Stderr, opts)), nil
	}
	return nil, fmt.Errorf("invalid log format %q", format)
}

func readPassword() (string, error) {
	if pw, ok := os.LookupEnv("NOTES_PASSWORD"); ok {
		return pw, nil
	}
	fmt.Fprint(os.Stderr, "password (input is not hidden; prefer NOTES_PASSWORD or piping): ")
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && !(errors.Is(err, io.EOF) && line != "") {
		return "", fmt.Errorf("read password: %w", err)
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func env(key, def string) string {
	if v, ok := os.LookupEnv(key); ok && v != "" {
		return v
	}
	return def
}
