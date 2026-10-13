package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"testing/fstest"
)

func TestServeIndexFallsBackToPlaceholder(t *testing.T) {
	rec := httptest.NewRecorder()
	serveIndex(rec, httptest.NewRequest(http.MethodGet, "/", nil), fstest.MapFS{})
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), "make web build") {
		t.Fatalf("body is not the placeholder: %q", rec.Body.String())
	}
}

func TestServeIndexServesBuild(t *testing.T) {
	root := fstest.MapFS{"index.html": {Data: []byte("<!doctype html>app")}}
	rec := httptest.NewRecorder()
	serveIndex(rec, httptest.NewRequest(http.MethodGet, "/notes/x", nil), root)
	if rec.Code != http.StatusOK || rec.Body.String() != "<!doctype html>app" {
		t.Fatalf("got %d %q", rec.Code, rec.Body.String())
	}
}
