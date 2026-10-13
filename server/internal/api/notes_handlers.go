package api

import (
	"errors"
	"net/http"

	"notes/server/internal/notes"
	"notes/server/internal/search"
	notesync "notes/server/internal/sync"
)

// GET /api/v1/notes?limit=50&cursor=...
func (s *Server) listNotes(w http.ResponseWriter, r *http.Request) {
	limit, err := queryInt(r, "limit", notes.DefaultListSize)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	items, next, err := s.notes.List(r.Context(), int(min(limit, notes.MaxListLimit)), r.URL.Query().Get("cursor"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	total, err := s.notes.Count(r.Context())
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"notes": items, "nextCursor": next, "hasMore": next != "", "total": total})
}

// GET /api/v1/notes/{id} — a deleted note answers 410 with its tombstone.
func (s *Server) getNote(w http.ResponseWriter, r *http.Request) {
	n, err := s.notes.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if n.DeletedAt != nil {
		writeJSON(w, http.StatusGone, errorBody{Error: notes.CodeNoteDeleted, Current: &n})
		return
	}
	writeJSON(w, http.StatusOK, n)
}

// POST /api/v1/notes
//
//	{"operationId": "...", "id": "...", "title": "...", "content": "...",
//	 "createdAt": 0, "updatedAt": 0, "conflictOf": "..."}
func (s *Server) createNote(w http.ResponseWriter, r *http.Request) {
	var in notes.CreateInput
	if err := decode(w, r, &in); err != nil {
		s.writeErr(w, r, err)
		return
	}
	res, err := s.notes.Create(r.Context(), in)
	s.writeMutation(w, r, res, err)
}

// PUT /api/v1/notes/{id}
//
//	{"operationId": "...", "baseRevision": 5, "title": "...", "content": "...", "updatedAt": 0}
//
// Title and content are both required: a PUT replaces the whole document.
func (s *Server) updateNote(w http.ResponseWriter, r *http.Request) {
	var req struct {
		OperationID  string  `json:"operationId"`
		BaseRevision int64   `json:"baseRevision"`
		Title        *string `json:"title"`
		Content      *string `json:"content"`
		UpdatedAt    *int64  `json:"updatedAt"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	if req.Title == nil || req.Content == nil {
		s.writeErr(w, r, &notes.ValidationError{Field: "body", Message: "title and content are both required"})
		return
	}
	res, err := s.notes.Update(r.Context(), notes.UpdateInput{
		OperationID:  req.OperationID,
		ID:           r.PathValue("id"),
		BaseRevision: req.BaseRevision,
		Title:        *req.Title,
		Content:      *req.Content,
		UpdatedAt:    req.UpdatedAt,
	})
	s.writeMutation(w, r, res, err)
}

// DELETE /api/v1/notes/{id}  {"operationId": "...", "baseRevision": 5}
func (s *Server) deleteNote(w http.ResponseWriter, r *http.Request) {
	var req struct {
		OperationID  string `json:"operationId"`
		BaseRevision int64  `json:"baseRevision"`
	}
	if err := decode(w, r, &req); err != nil {
		s.writeErr(w, r, err)
		return
	}
	res, err := s.notes.Delete(r.Context(), notes.DeleteInput{
		OperationID:  req.OperationID,
		ID:           r.PathValue("id"),
		BaseRevision: req.BaseRevision,
	})
	s.writeMutation(w, r, res, err)
}

func (s *Server) writeMutation(w http.ResponseWriter, r *http.Request, res notes.Result, err error) {
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if res.Replayed {
		w.Header().Set("Idempotent-Replayed", "true")
	}
	writeJSON(w, res.HTTPStatus, res)
}

// GET /api/v1/sync?since=100&limit=100&generation=...
func (s *Server) sync(w http.ResponseWriter, r *http.Request) {
	since, err := queryInt(r, "since", 0)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	limit, err := queryInt(r, "limit", notesync.DefaultLimit)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	page, err := notesync.Changes(r.Context(), s.db, since, int(min(limit, notesync.MaxLimit)),
		r.URL.Query().Get("generation"))
	if errors.Is(err, notesync.ErrGenerationMismatch) {
		gen, gerr := notesync.Generation(r.Context(), s.db)
		if gerr != nil {
			s.writeErr(w, r, gerr)
			return
		}
		writeJSON(w, http.StatusConflict, errorBody{
			Error:      "generation_mismatch",
			Message:    "the server's data was replaced (e.g. restored from backup); run a full reconciliation",
			Generation: gen,
		})
		return
	}
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, page)
}

// GET /api/v1/search?q=...&limit=30&offset=0
func (s *Server) search(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	if len(q) > 500 {
		q = q[:500]
	}
	limit, err := queryInt(r, "limit", 30)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	offset, err := queryInt(r, "offset", 0)
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	if offset > 10_000 {
		offset = 10_000
	}
	results, err := search.Search(r.Context(), s.db, q, int(min(limit, search.MaxLimit)), int(offset))
	if err != nil {
		s.writeErr(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}
