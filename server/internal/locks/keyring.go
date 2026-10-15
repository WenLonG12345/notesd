package locks

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"regexp"
	"time"

	"notes/server/internal/database"
)

// Key kinds.
const (
	KindPassword = "password"
	KindRecovery = "recovery"
	KindPasskey  = "passkey"
)

// Key is one wrapped copy of the locked-notes key.
type Key struct {
	ID           string `json:"id"`
	Kind         string `json:"kind"`
	CredentialID string `json:"credentialId,omitempty"`
	Salt         string `json:"salt"`
	Iterations   int    `json:"iterations"`
	Wrapped      string `json:"wrapped"`
	CreatedAt    int64  `json:"createdAt"`
}

// PutInput creates or replaces one entry. Verifier proves the caller holds
// the locked-notes key; on the first entry it sets the verifier instead.
type PutInput struct {
	Kind         string `json:"kind"`
	CredentialID string `json:"credentialId"`
	Salt         string `json:"salt"`
	Iterations   int    `json:"iterations"`
	Wrapped      string `json:"wrapped"`
	Verifier     string `json:"verifier"`
}

// Errors.
var (
	ErrBadVerifier = errors.New("verifier does not match the keyring")
	ErrNotFound    = errors.New("lock key not found")
	ErrNoPassword  = errors.New("the keyring needs a password entry first")
	ErrLastKey     = errors.New("the password entry can't be removed")
)

// InputError reports an invalid request.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

var (
	idPattern  = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	b64Pattern = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
)

// Bounds on keyring fields, as decoded bytes (maxField: as sent).
const (
	wrappedLen  = 12 + 32 + 16 // nonce ‖ key ‖ tag
	minSaltLen  = 16
	maxField    = 1024
	minPBKDF2   = 10_000
	verifierLen = 32
)

// Store persists the keyring.
type Store struct {
	db  *database.DB
	now func() time.Time
}

// NewStore returns a Store using the system clock.
func NewStore(db *database.DB) *Store { return &Store{db: db, now: time.Now} }

// List returns the keyring, password entry first.
func (s *Store) List(ctx context.Context) ([]Key, error) {
	rows, err := s.db.R.QueryContext(ctx, `
		SELECT id, kind, COALESCE(credential_id, ''), salt, iterations, wrapped, created_at
		FROM lock_keys ORDER BY kind != 'password', created_at, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	keys := []Key{}
	for rows.Next() {
		var k Key
		if err := rows.Scan(&k.ID, &k.Kind, &k.CredentialID, &k.Salt, &k.Iterations, &k.Wrapped, &k.CreatedAt); err != nil {
			return nil, err
		}
		keys = append(keys, k)
	}
	return keys, rows.Err()
}

// Put creates or replaces the entry id. The password and recovery entries
// have those ids; passkeys use any other id.
func (s *Store) Put(ctx context.Context, id string, in PutInput) (Key, error) {
	if err := validate(id, in); err != nil {
		return Key{}, err
	}
	verifier, err := decodeVerifier(in.Verifier)
	if err != nil {
		return Key{}, err
	}
	k := Key{ID: id, Kind: in.Kind, CredentialID: in.CredentialID, Salt: in.Salt,
		Iterations: in.Iterations, Wrapped: in.Wrapped, CreatedAt: s.now().UnixMilli()}
	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		stored, err := verifierHash(ctx, tx)
		switch {
		case errors.Is(err, sql.ErrNoRows):
			// First entry: it must be the password, and it sets the verifier.
			if in.Kind != KindPassword {
				return ErrNoPassword
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO lock_state (id, verifier_hash) VALUES (1, ?)`, hash(verifier)); err != nil {
				return err
			}
		case err != nil:
			return err
		case subtle.ConstantTimeCompare([]byte(stored), []byte(hash(verifier))) != 1:
			return ErrBadVerifier
		}
		_, err = tx.ExecContext(ctx, `
			INSERT INTO lock_keys (id, kind, credential_id, salt, iterations, wrapped, created_at)
			VALUES (?, ?, NULLIF(?, ''), ?, ?, ?, ?)
			ON CONFLICT (id) DO UPDATE SET kind = excluded.kind, credential_id = excluded.credential_id,
				salt = excluded.salt, iterations = excluded.iterations, wrapped = excluded.wrapped,
				created_at = excluded.created_at`,
			k.ID, k.Kind, k.CredentialID, k.Salt, k.Iterations, k.Wrapped, k.CreatedAt)
		return err
	})
	return k, err
}

// Delete removes a recovery or passkey entry.
func (s *Store) Delete(ctx context.Context, id, verifierB64 string) error {
	if id == KindPassword {
		return ErrLastKey
	}
	verifier, err := decodeVerifier(verifierB64)
	if err != nil {
		return err
	}
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		stored, err := verifierHash(ctx, tx)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrNotFound
		}
		if err != nil {
			return err
		}
		if subtle.ConstantTimeCompare([]byte(stored), []byte(hash(verifier))) != 1 {
			return ErrBadVerifier
		}
		res, err := tx.ExecContext(ctx, `DELETE FROM lock_keys WHERE id = ?`, id)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		return nil
	})
}

// Reset forgets the whole keyring, for a forgotten lock password with no
// recovery code: notes locked so far stay unreadable for good. The caller
// checks the account password.
func (s *Store) Reset(ctx context.Context) error {
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `DELETE FROM lock_keys; DELETE FROM lock_state`)
		return err
	})
}

func verifierHash(ctx context.Context, tx *sql.Tx) (string, error) {
	var h string
	err := tx.QueryRowContext(ctx, `SELECT verifier_hash FROM lock_state WHERE id = 1`).Scan(&h)
	return h, err
}

func hash(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// decode strictly reads unpadded base64url, as clients do: the pattern
// rules out the CR and LF that Go's decoder would skip, and Strict the
// stray bits a last partial character can carry.
func decode(s string) ([]byte, bool) {
	if len(s) > maxField || !b64Pattern.MatchString(s) {
		return nil, false
	}
	b, err := base64.RawURLEncoding.Strict().DecodeString(s)
	return b, err == nil
}

func decodeVerifier(v string) ([]byte, error) {
	b, ok := decode(v)
	if !ok || len(b) != verifierLen {
		return nil, &InputError{"verifier", "must be 32 bytes, base64url"}
	}
	return b, nil
}

func validate(id string, in PutInput) error {
	switch in.Kind {
	case KindPassword, KindRecovery:
		if id != in.Kind {
			return &InputError{"id", "must be " + in.Kind}
		}
		if in.Iterations < minPBKDF2 || in.Iterations > 10_000_000 {
			return &InputError{"iterations", "out of range"}
		}
		if in.CredentialID != "" {
			return &InputError{"credentialId", "only passkeys have one"}
		}
	case KindPasskey:
		if id == KindPassword || id == KindRecovery || !idPattern.MatchString(id) {
			return &InputError{"id", "must be 1-64 characters of [A-Za-z0-9_-], not password or recovery"}
		}
		if in.Iterations != 0 {
			return &InputError{"iterations", "must be 0 for passkeys"}
		}
		if cred, ok := decode(in.CredentialID); !ok || len(cred) == 0 {
			return &InputError{"credentialId", "required, base64url"}
		}
	default:
		return &InputError{"kind", "must be password, recovery or passkey"}
	}
	if salt, ok := decode(in.Salt); !ok || len(salt) < minSaltLen {
		return &InputError{"salt", "must be at least 16 bytes, base64url"}
	}
	if wrapped, ok := decode(in.Wrapped); !ok || len(wrapped) != wrappedLen {
		return &InputError{"wrapped", "must be a wrapped 32-byte key"}
	}
	return nil
}
