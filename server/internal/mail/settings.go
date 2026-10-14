// Package mail emails notes through Resend (https://resend.com): the sender
// settings, rendering a note's Markdown to HTML, and the API call.
package mail

import (
	"context"
	"database/sql"
	"errors"
	"net/mail"
	"strings"

	"notes/server/internal/database"
)

const (
	keyAPIKey = "resend_api_key"
	keyFrom   = "email_from"
)

// ErrNotConfigured means no Resend key or sender has been saved.
var ErrNotConfigured = errors.New("email is not set up")

// InputError reports an invalid setting or recipient.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

// Settings is what sending needs. The key never leaves the server; clients
// see KeyHint.
type Settings struct {
	APIKey string
	From   string
}

// KeyHint is the key's last four characters, enough to tell keys apart.
func (s Settings) KeyHint() string {
	if len(s.APIKey) < 4 {
		return ""
	}
	return s.APIKey[len(s.APIKey)-4:]
}

// Load returns the saved settings, or ErrNotConfigured.
func Load(ctx context.Context, db *database.DB) (Settings, error) {
	var s Settings
	rows, err := db.R.QueryContext(ctx, `SELECT key, value FROM settings WHERE key IN (?, ?)`, keyAPIKey, keyFrom)
	if err != nil {
		return s, err
	}
	defer rows.Close()
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return s, err
		}
		switch k {
		case keyAPIKey:
			s.APIKey = v
		case keyFrom:
			s.From = v
		}
	}
	if err := rows.Err(); err != nil {
		return s, err
	}
	if s.APIKey == "" || s.From == "" {
		return Settings{}, ErrNotConfigured
	}
	return s, nil
}

// Save stores the sender and, unless apiKey is empty, a new key. The first
// save must include a key.
func Save(ctx context.Context, db *database.DB, apiKey, from string) error {
	apiKey = strings.TrimSpace(apiKey)
	from = strings.TrimSpace(from)
	if _, err := mail.ParseAddress(from); err != nil {
		return &InputError{Field: "from", Message: "enter an address like you@example.com or Name <you@example.com>"}
	}
	if apiKey != "" && (!strings.HasPrefix(apiKey, "re_") || len(apiKey) < 8 || strings.ContainsAny(apiKey, " \t\n")) {
		return &InputError{Field: "apiKey", Message: "a Resend API key starts with re_"}
	}
	return db.WriteTx(ctx, func(tx *sql.Tx) error {
		if apiKey == "" {
			var n int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM settings WHERE key = ?`, keyAPIKey).Scan(&n); err != nil {
				return err
			}
			if n == 0 {
				return &InputError{Field: "apiKey", Message: "enter your Resend API key"}
			}
		} else if err := put(ctx, tx, keyAPIKey, apiKey); err != nil {
			return err
		}
		return put(ctx, tx, keyFrom, from)
	})
}

// Clear removes the key and sender.
func Clear(ctx context.Context, db *database.DB) error {
	return db.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM settings WHERE key IN (?, ?)`, keyAPIKey, keyFrom)
		return err
	})
}

func put(ctx context.Context, tx *sql.Tx, k, v string) error {
	_, err := tx.ExecContext(ctx,
		`INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`, k, v)
	return err
}

// MaxRecipients bounds one send; this is for sharing a note, not a mailing list.
const MaxRecipients = 10

// Recipients validates and normalises addresses to their bare form.
func Recipients(to []string) ([]string, error) {
	var out []string
	for _, a := range to {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		addr, err := mail.ParseAddress(a)
		if err != nil {
			return nil, &InputError{Field: "to", Message: a + " isn't an email address"}
		}
		out = append(out, addr.Address)
	}
	if len(out) == 0 {
		return nil, &InputError{Field: "to", Message: "add at least one recipient"}
	}
	if len(out) > MaxRecipients {
		return nil, &InputError{Field: "to", Message: "send to at most 10 people at once"}
	}
	return out, nil
}
