// Package auth implements the single-owner account and sessions (§10).
//
// Browsers get a "web" session: one secret carried in an HttpOnly cookie with
// a sliding 30-day expiry, plus a CSRF token the web app must echo on
// mutations. Native apps get a "device" session: a short-lived access token
// and a long-lived refresh token, both sent as bearer tokens. Refresh tokens
// are not rotated, so a refresh whose response is lost can simply be retried;
// they are revoked by deleting the session.
package auth

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"notes/server/internal/database"
)

const (
	KindWeb    = "web"
	KindDevice = "device"

	WebSessionTTL     = 30 * 24 * time.Hour
	DeviceAccessTTL   = time.Hour
	DeviceRefreshTTL  = 180 * 24 * time.Hour
	touchInterval     = time.Hour // how often a used session's expiry is extended
	maxUsernameLen    = 64
	maxSessionNameLen = 100
)

var (
	ErrInvalidCredentials = errors.New("invalid username or password")
	ErrUnauthenticated    = errors.New("not authenticated")
	ErrOwnerExists        = errors.New("an owner account already exists")
	ErrNoOwner            = errors.New("no owner account exists yet")
	ErrBadSetupToken      = errors.New("invalid setup token")
	ErrNotFound           = errors.New("session not found")
)

// InputError reports an invalid username or password.
type InputError struct{ Field, Message string }

func (e *InputError) Error() string { return e.Field + ": " + e.Message }

// Session is an authenticated browser or device.
type Session struct {
	ID         string `json:"id"`
	Kind       string `json:"kind"`
	Name       string `json:"name"`
	CreatedAt  int64  `json:"createdAt"`
	LastUsedAt int64  `json:"lastUsedAt"`
	CSRFToken  string `json:"-"`
	// Renewed is set by Authenticate when it extended a web session's
	// expiry to ExpiresAt, so the cookie should be re-issued.
	Renewed   bool  `json:"-"`
	ExpiresAt int64 `json:"-"`
}

// Credentials are the secrets issued at login. Fields that do not apply to
// the session kind are empty.
type Credentials struct {
	Session          Session
	AccessToken      string // cookie value for web, bearer for devices
	AccessExpiresAt  int64
	RefreshToken     string // devices only
	RefreshExpiresAt int64  // devices only
	CSRFToken        string // web only
}

// Service manages the owner account and sessions.
type Service struct {
	db  *database.DB
	now func() time.Time

	mu         sync.Mutex
	setupToken string
}

// NewService returns an auth service using the system clock.
func NewService(db *database.DB) *Service {
	return &Service{db: db, now: time.Now}
}

// SetClock overrides the clock, for tests.
func (s *Service) SetClock(now func() time.Time) { s.now = now }

// HasOwner reports whether the owner account has been created.
func (s *Service) HasOwner(ctx context.Context) (bool, error) {
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM users`).Scan(&n)
	return n > 0, err
}

// PrepareSetup arranges first-run setup through the API. If no owner exists
// it installs token (or generates one when token is empty) and returns it;
// the operator reads it from the server log, which is what stops a stranger
// from claiming a fresh instance. It returns "" once an owner exists.
func (s *Service) PrepareSetup(ctx context.Context, token string) (string, error) {
	has, err := s.HasOwner(ctx)
	if err != nil || has {
		return "", err
	}
	if token == "" {
		token = NewToken()
	}
	s.mu.Lock()
	s.setupToken = token
	s.mu.Unlock()
	return token, nil
}

// Setup creates the owner account using the setup token.
func (s *Service) Setup(ctx context.Context, setupToken, username, password string) error {
	s.mu.Lock()
	want := s.setupToken
	s.mu.Unlock()
	if want == "" {
		if has, _ := s.HasOwner(ctx); has {
			return ErrOwnerExists
		}
		return ErrBadSetupToken
	}
	if !ConstantTimeEqual(setupToken, want) {
		return ErrBadSetupToken
	}
	if err := s.CreateOwner(ctx, username, password); err != nil {
		return err
	}
	s.mu.Lock()
	s.setupToken = ""
	s.mu.Unlock()
	return nil
}

// CreateOwner creates the owner account. It is used by Setup and by the
// command line, which is trusted because it needs access to the data
// directory.
func (s *Service) CreateOwner(ctx context.Context, username, password string) error {
	username = strings.TrimSpace(username)
	if err := validateUsername(username); err != nil {
		return err
	}
	if err := validatePassword(password); err != nil {
		return err
	}
	hash, err := HashPassword(password)
	if err != nil {
		return err
	}
	now := s.now().UnixMilli()
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		var n int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM users`).Scan(&n); err != nil {
			return err
		}
		if n > 0 {
			return ErrOwnerExists
		}
		_, err := tx.ExecContext(ctx,
			`INSERT INTO users (id, username, password_hash, created_at, updated_at) VALUES (1, ?, ?, ?, ?)`,
			username, hash, now, now)
		return err
	})
}

// ResetPassword sets a new password without the old one and signs out every
// session. Only the command line calls it.
func (s *Service) ResetPassword(ctx context.Context, password string) error {
	return s.setPassword(ctx, password, "")
}

// ChangePassword verifies the current password, sets a new one and signs out
// every session except keepSessionID.
func (s *Service) ChangePassword(ctx context.Context, current, next, keepSessionID string) error {
	_, hash, err := s.owner(ctx)
	if err != nil {
		return err
	}
	ok, err := VerifyPassword(current, hash)
	if err != nil {
		return err
	}
	if !ok {
		return ErrInvalidCredentials
	}
	return s.setPassword(ctx, next, keepSessionID)
}

func (s *Service) setPassword(ctx context.Context, password, keepSessionID string) error {
	if err := validatePassword(password); err != nil {
		return err
	}
	hash, err := HashPassword(password)
	if err != nil {
		return err
	}
	return s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `UPDATE users SET password_hash = ?, updated_at = ? WHERE id = 1`,
			hash, s.now().UnixMilli())
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNoOwner
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM sessions WHERE id != ?`, keepSessionID)
		return err
	})
}

func (s *Service) owner(ctx context.Context) (username, hash string, err error) {
	err = s.db.R.QueryRowContext(ctx, `SELECT username, password_hash FROM users WHERE id = 1`).Scan(&username, &hash)
	if errors.Is(err, sql.ErrNoRows) {
		err = ErrNoOwner
	}
	return
}

// Login verifies the owner's credentials and opens a session of the given
// kind. name labels the session in the session list (e.g. "Pixel 8").
func (s *Service) Login(ctx context.Context, username, password, kind, name string) (Credentials, error) {
	if kind != KindWeb && kind != KindDevice {
		return Credentials{}, &InputError{"client", `must be "web" or "device"`}
	}
	owner, hash, err := s.owner(ctx)
	if errors.Is(err, ErrNoOwner) {
		_, _ = VerifyPassword(password, dummyHash)
		return Credentials{}, ErrInvalidCredentials
	}
	if err != nil {
		return Credentials{}, err
	}
	if len(password) > MaxPasswordLen {
		return Credentials{}, ErrInvalidCredentials
	}
	userOK := strings.EqualFold(strings.TrimSpace(username), owner)
	if !userOK {
		hash = dummyHash
	}
	ok, err := VerifyPassword(password, hash)
	if err != nil {
		return Credentials{}, err
	}
	if !ok || !userOK {
		return Credentials{}, ErrInvalidCredentials
	}

	now := s.now()
	c := Credentials{
		Session: Session{
			ID: NewToken()[:22], Kind: kind, Name: truncate(name, maxSessionNameLen),
			CreatedAt: now.UnixMilli(), LastUsedAt: now.UnixMilli(),
		},
		AccessToken: NewToken(),
	}
	var refreshHash, csrf sql.NullString
	var refreshExp sql.NullInt64
	if kind == KindWeb {
		c.AccessExpiresAt = now.Add(WebSessionTTL).UnixMilli()
		c.CSRFToken = NewToken()
		c.Session.CSRFToken = c.CSRFToken
		csrf = sql.NullString{String: c.CSRFToken, Valid: true}
	} else {
		c.AccessExpiresAt = now.Add(DeviceAccessTTL).UnixMilli()
		c.RefreshToken = NewToken()
		c.RefreshExpiresAt = now.Add(DeviceRefreshTTL).UnixMilli()
		refreshHash = sql.NullString{String: HashToken(c.RefreshToken), Valid: true}
		refreshExp = sql.NullInt64{Int64: c.RefreshExpiresAt, Valid: true}
	}

	err = s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		// Opportunistically clear expired sessions; there are only ever a
		// handful.
		if _, err := tx.ExecContext(ctx, `
			DELETE FROM sessions
			WHERE (kind = 'web' AND access_expires_at < ?)
			   OR (kind = 'device' AND refresh_expires_at < ?)`,
			now.UnixMilli(), now.UnixMilli()); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `
			INSERT INTO sessions (id, kind, name, access_hash, access_expires_at,
			                      refresh_hash, refresh_expires_at, csrf_token, created_at, last_used_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			c.Session.ID, kind, c.Session.Name, HashToken(c.AccessToken), c.AccessExpiresAt,
			refreshHash, refreshExp, csrf, c.Session.CreatedAt, c.Session.LastUsedAt)
		return err
	})
	if err != nil {
		return Credentials{}, err
	}
	return c, nil
}

// Authenticate resolves an access token of the expected kind to its session.
// Web sessions slide: each use (at most hourly) pushes expiry out again.
func (s *Service) Authenticate(ctx context.Context, accessToken, kind string) (Session, error) {
	if accessToken == "" {
		return Session{}, ErrUnauthenticated
	}
	var sess Session
	var exp int64
	var csrf sql.NullString
	err := s.db.R.QueryRowContext(ctx, `
		SELECT id, kind, name, created_at, last_used_at, access_expires_at, csrf_token
		FROM sessions WHERE access_hash = ?`, HashToken(accessToken)).Scan(
		&sess.ID, &sess.Kind, &sess.Name, &sess.CreatedAt, &sess.LastUsedAt, &exp, &csrf)
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrUnauthenticated
	}
	if err != nil {
		return Session{}, err
	}
	now := s.now()
	if sess.Kind != kind || exp <= now.UnixMilli() {
		return Session{}, ErrUnauthenticated
	}
	sess.CSRFToken = csrf.String
	sess.ExpiresAt = exp

	if now.UnixMilli()-sess.LastUsedAt >= touchInterval.Milliseconds() {
		sess.LastUsedAt = now.UnixMilli()
		newExp := exp
		if kind == KindWeb {
			newExp = now.Add(WebSessionTTL).UnixMilli()
		}
		// Best effort: a failed touch must not fail the request.
		if _, err := s.db.W.ExecContext(ctx,
			`UPDATE sessions SET last_used_at = ?, access_expires_at = ? WHERE id = ?`,
			sess.LastUsedAt, newExp, sess.ID); err == nil && newExp != exp {
			sess.ExpiresAt = newExp
			sess.Renewed = true
		}
	}
	return sess, nil
}

// Refresh issues a new access token for a device session and extends the
// refresh token's lifetime. The refresh token itself is unchanged.
func (s *Service) Refresh(ctx context.Context, refreshToken string) (Credentials, error) {
	if refreshToken == "" {
		return Credentials{}, ErrUnauthenticated
	}
	now := s.now()
	c := Credentials{
		AccessToken:      NewToken(),
		AccessExpiresAt:  now.Add(DeviceAccessTTL).UnixMilli(),
		RefreshToken:     refreshToken,
		RefreshExpiresAt: now.Add(DeviceRefreshTTL).UnixMilli(),
	}
	err := s.db.WriteTx(ctx, func(tx *sql.Tx) error {
		err := tx.QueryRowContext(ctx, `
			UPDATE sessions
			SET access_hash = ?, access_expires_at = ?, refresh_expires_at = ?, last_used_at = ?
			WHERE refresh_hash = ? AND kind = 'device' AND refresh_expires_at > ?
			RETURNING id, kind, name, created_at, last_used_at`,
			HashToken(c.AccessToken), c.AccessExpiresAt, c.RefreshExpiresAt, now.UnixMilli(),
			HashToken(refreshToken), now.UnixMilli()).Scan(
			&c.Session.ID, &c.Session.Kind, &c.Session.Name, &c.Session.CreatedAt, &c.Session.LastUsedAt)
		if errors.Is(err, sql.ErrNoRows) {
			return ErrUnauthenticated
		}
		return err
	})
	if err != nil {
		return Credentials{}, err
	}
	return c, nil
}

// Logout ends a session.
func (s *Service) Logout(ctx context.Context, sessionID string) error {
	return s.Revoke(ctx, sessionID)
}

// Sessions lists active sessions, most recently used first.
func (s *Service) Sessions(ctx context.Context) ([]Session, error) {
	now := s.now().UnixMilli()
	rows, err := s.db.R.QueryContext(ctx, `
		SELECT id, kind, name, created_at, last_used_at FROM sessions
		WHERE (kind = 'web' AND access_expires_at > ?) OR (kind = 'device' AND refresh_expires_at > ?)
		ORDER BY last_used_at DESC`, now, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Session{}
	for rows.Next() {
		var ss Session
		if err := rows.Scan(&ss.ID, &ss.Kind, &ss.Name, &ss.CreatedAt, &ss.LastUsedAt); err != nil {
			return nil, err
		}
		out = append(out, ss)
	}
	return out, rows.Err()
}

// Revoke deletes a session, signing that browser or device out.
func (s *Service) Revoke(ctx context.Context, sessionID string) error {
	res, err := s.db.W.ExecContext(ctx, `DELETE FROM sessions WHERE id = ?`, sessionID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// Username returns the owner's username.
func (s *Service) Username(ctx context.Context) (string, error) {
	u, _, err := s.owner(ctx)
	return u, err
}

func validateUsername(u string) error {
	if u == "" || utf8.RuneCountInString(u) > maxUsernameLen || strings.ContainsAny(u, "\x00\r\n\t") {
		return &InputError{"username", "must be 1-64 characters without control characters"}
	}
	return nil
}

func validatePassword(p string) error {
	if utf8.RuneCountInString(p) < MinPasswordLen || len(p) > MaxPasswordLen {
		return &InputError{"password", "must be at least 8 characters (and at most 1024 bytes)"}
	}
	return nil
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}
