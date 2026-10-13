package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"

	"notes/server/internal/auth"
	"notes/server/internal/notes"
	notesync "notes/server/internal/sync"
)

// Request body limit. Content is capped at 1 MiB, and JSON escaping can
// inflate it.
const maxBodyBytes = 4 << 20

// errorBody is the shape of every error response.
type errorBody struct {
	Error   string `json:"error"`
	Message string `json:"message,omitempty"`
	Field   string `json:"field,omitempty"`
	// Current is the server's latest version of the note on conflicts.
	Current *notes.Note `json:"current,omitempty"`
	// Generation is the server's sync generation on generation mismatches.
	Generation string `json:"generation,omitempty"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if v == nil {
		return
	}
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorBody{Error: code, Message: message})
}

// decode reads a JSON request body into v. Unknown fields are ignored so
// newer clients keep working against older servers.
func decode(w http.ResponseWriter, r *http.Request, v any) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			return &notes.ValidationError{Field: "body", Message: "request body too large"}
		}
		if errors.Is(err, io.EOF) {
			return &notes.ValidationError{Field: "body", Message: "request body is required"}
		}
		return &notes.ValidationError{Field: "body", Message: "invalid JSON: " + err.Error()}
	}
	if dec.More() {
		return &notes.ValidationError{Field: "body", Message: "unexpected data after JSON object"}
	}
	return nil
}

// writeErr maps domain errors to HTTP responses.
func (s *Server) writeErr(w http.ResponseWriter, r *http.Request, err error) {
	var verr *notes.ValidationError
	var aerr *auth.InputError
	var conflict *notes.ConflictError
	switch {
	case errors.As(err, &verr):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: verr.Message, Field: verr.Field})
	case errors.As(err, &aerr):
		writeJSON(w, http.StatusBadRequest, errorBody{Error: "invalid_request", Message: aerr.Message, Field: aerr.Field})
	case errors.As(err, &conflict):
		cur := conflict.Current
		writeJSON(w, http.StatusConflict, errorBody{Error: conflict.Code, Current: &cur})
	case errors.Is(err, notes.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "note not found")
	case errors.Is(err, notes.ErrOperationReuse):
		writeError(w, http.StatusUnprocessableEntity, "operation_id_reused", err.Error())
	case errors.Is(err, notesync.ErrCursorAhead):
		writeError(w, http.StatusConflict, "cursor_ahead",
			"the cursor is ahead of this server; run a full reconciliation")
	case errors.Is(err, auth.ErrUnauthenticated):
		writeError(w, http.StatusUnauthorized, "unauthenticated", "authentication required")
	case errors.Is(err, auth.ErrInvalidCredentials):
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid username or password")
	case errors.Is(err, auth.ErrBadSetupToken):
		writeError(w, http.StatusForbidden, "invalid_setup_token", "invalid setup token")
	case errors.Is(err, auth.ErrOwnerExists):
		writeError(w, http.StatusConflict, "already_set_up", "the owner account already exists")
	case errors.Is(err, auth.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "session not found")
	case errors.Is(err, r.Context().Err()) && r.Context().Err() != nil:
		// The client went away; nobody will read this.
		writeError(w, 499, "client_closed_request", "")
	default:
		s.log.ErrorContext(r.Context(), "request failed",
			"method", r.Method, "path", r.URL.Path, "error", err)
		writeError(w, http.StatusInternalServerError, "internal_error", "internal server error")
	}
}

// queryInt parses an optional non-negative integer query parameter.
func queryInt(r *http.Request, name string, def int64) (int64, error) {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def, nil
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n < 0 {
		return 0, &notes.ValidationError{Field: name, Message: fmt.Sprintf("%s must be a non-negative integer", name)}
	}
	return n, nil
}
