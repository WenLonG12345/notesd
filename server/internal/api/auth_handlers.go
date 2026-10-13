package api

import (
	"errors"
	"math"
	"net/http"
	"strconv"
	"time"

	"notes/server/internal/auth"
)

type sessionResponse struct {
	Session          auth.Session `json:"session"`
	Username         string       `json:"username"`
	CSRFToken        string       `json:"csrfToken,omitempty"`
	AccessToken      string       `json:"accessToken,omitempty"`
	AccessExpiresAt  int64        `json:"accessExpiresAt,omitempty"`
	RefreshToken     string       `json:"refreshToken,omitempty"`
	RefreshExpiresAt int64        `json:"refreshExpiresAt,omitempty"`
}

// GET /api/v1/auth/status — lets the web app decide between the setup and
// login screens.
func (s *Server) authStatus(w http.ResponseWriter, r *http.Request) {
	has, err := s.auth.HasOwner(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"setupRequired": !has})
}

// POST /api/v1/auth/setup — first-run owner creation, guarded by the setup
// token printed in the server log.
func (s *Server) setup(w http.ResponseWriter, r *http.Request) {
	key := "setup:" + clientKey(r)
	if !s.allow(w, key) {
		return
	}
	var req struct {
		SetupToken string `json:"setupToken"`
		Username   string `json:"username"`
		Password   string `json:"password"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if err := s.auth.Setup(r.Context(), req.SetupToken, req.Username, req.Password); err != nil {
		if errors.Is(err, auth.ErrBadSetupToken) {
			s.limiter.Fail(key)
		}
		s.writeErr(w, r, err)
		return
	}
	s.limiter.Reset(key)
	s.log.Info("owner account created", "username", req.Username)
	writeJSON(w, http.StatusCreated, map[string]string{"status": "created"})
}

// POST /api/v1/auth/login
//
//	{"username": "...", "password": "...", "client": "web" | "device", "deviceName": "..."}
//
// Web clients receive the session cookie and a CSRF token; devices receive
// access and refresh tokens.
func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	key := "login:" + clientKey(r)
	if !s.allow(w, key) {
		return
	}
	var req struct {
		Username   string `json:"username"`
		Password   string `json:"password"`
		Client     string `json:"client"`
		DeviceName string `json:"deviceName"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if req.Client == "" {
		req.Client = auth.KindWeb
	}
	name := req.DeviceName
	if name == "" {
		name = r.UserAgent()
	}
	creds, err := s.auth.Login(r.Context(), req.Username, req.Password, req.Client, name)
	if err != nil {
		if errors.Is(err, auth.ErrInvalidCredentials) {
			s.limiter.Fail(key)
			s.log.Warn("failed login", "remote", clientKey(r))
		}
		s.writeErr(w, r, err)
		return
	}
	s.limiter.Reset(key)

	resp := sessionResponse{Session: creds.Session, Username: req.Username}
	if u, err := s.auth.Username(r.Context()); err == nil {
		resp.Username = u
	}
	if creds.Session.Kind == auth.KindWeb {
		s.setSessionCookie(w, creds.AccessToken, time.UnixMilli(creds.AccessExpiresAt))
		resp.CSRFToken = creds.CSRFToken
	} else {
		resp.AccessToken = creds.AccessToken
		resp.AccessExpiresAt = creds.AccessExpiresAt
		resp.RefreshToken = creds.RefreshToken
		resp.RefreshExpiresAt = creds.RefreshExpiresAt
	}
	writeJSON(w, http.StatusOK, resp)
}

// POST /api/v1/auth/refresh {"refreshToken": "..."} — devices only.
func (s *Server) refresh(w http.ResponseWriter, r *http.Request) {
	var req struct {
		RefreshToken string `json:"refreshToken"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	creds, err := s.auth.Refresh(r.Context(), req.RefreshToken)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, sessionResponse{
		Session:          creds.Session,
		AccessToken:      creds.AccessToken,
		AccessExpiresAt:  creds.AccessExpiresAt,
		RefreshToken:     creds.RefreshToken,
		RefreshExpiresAt: creds.RefreshExpiresAt,
	})
}

// POST /api/v1/auth/logout
func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	sess := sessionFrom(r.Context())
	if err := s.auth.Logout(r.Context(), sess.ID); err != nil && !errors.Is(err, auth.ErrNotFound) {
		s.writeErr(w, r, err)
		return
	}
	if sess.Kind == auth.KindWeb {
		s.setSessionCookie(w, "", time.Unix(0, 0))
	}
	w.WriteHeader(http.StatusNoContent)
}

// GET /api/v1/auth/session — the current session; for browsers it also
// returns the CSRF token, which the web app keeps only in memory.
func (s *Server) currentSession(w http.ResponseWriter, r *http.Request) {
	sess := sessionFrom(r.Context())
	u, err := s.auth.Username(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, sessionResponse{Session: sess, Username: u, CSRFToken: sess.CSRFToken})
}

// POST /api/v1/auth/password {"currentPassword": "...", "newPassword": "..."}
// Signs out every other session.
func (s *Server) changePassword(w http.ResponseWriter, r *http.Request) {
	key := "password:" + clientKey(r)
	if !s.allow(w, key) {
		return
	}
	var req struct {
		CurrentPassword string `json:"currentPassword"`
		NewPassword     string `json:"newPassword"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	sess := sessionFrom(r.Context())
	if err := s.auth.ChangePassword(r.Context(), req.CurrentPassword, req.NewPassword, sess.ID); err != nil {
		if errors.Is(err, auth.ErrInvalidCredentials) {
			s.limiter.Fail(key)
		}
		s.writeErr(w, r, err)
		return
	}
	s.log.Info("password changed; other sessions revoked")
	w.WriteHeader(http.StatusNoContent)
}

// GET /api/v1/auth/sessions
func (s *Server) listSessions(w http.ResponseWriter, r *http.Request) {
	list, err := s.auth.Sessions(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": list, "currentId": sessionFrom(r.Context()).ID})
}

// DELETE /api/v1/auth/sessions/{id}
func (s *Server) revokeSession(w http.ResponseWriter, r *http.Request) {
	if err := s.auth.Revoke(r.Context(), r.PathValue("id")); err != nil {
		s.writeErr(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) allow(w http.ResponseWriter, key string) bool {
	ok, wait := s.limiter.Allow(key)
	if !ok {
		w.Header().Set("Retry-After", strconv.Itoa(int(math.Ceil(wait.Seconds()))))
		writeError(w, http.StatusTooManyRequests, "rate_limited", "too many failed attempts; try again later")
	}
	return ok
}

func (s *Server) setSessionCookie(w http.ResponseWriter, value string, expires time.Time) {
	c := &http.Cookie{
		Name:     s.cookieName(),
		Value:    value,
		Path:     "/",
		Expires:  expires,
		HttpOnly: true,
		Secure:   s.secureCookies(),
		SameSite: http.SameSiteLaxMode,
	}
	if value == "" {
		c.MaxAge = -1
	}
	http.SetCookie(w, c)
}
