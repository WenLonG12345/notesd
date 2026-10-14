package api_test

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"notes/server/internal/api"
)

// fakeResend records sends and answers with status (200 = accepted).
type fakeResend struct {
	srv    *httptest.Server
	status int
	sent   []map[string]any
	auth   []string
}

func newFakeResend(t *testing.T) *fakeResend {
	f := &fakeResend{status: 200}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" || r.URL.Path != "/emails" {
			http.NotFound(w, r)
			return
		}
		var m map[string]any
		_ = json.NewDecoder(r.Body).Decode(&m)
		f.auth = append(f.auth, r.Header.Get("Authorization"))
		w.Header().Set("Content-Type", "application/json")
		if f.status != 200 {
			w.WriteHeader(f.status)
			_, _ = w.Write([]byte(`{"statusCode":403,"name":"validation_error","message":"The example.com domain is not verified."}`))
			return
		}
		f.sent = append(f.sent, m)
		_, _ = w.Write([]byte(`{"id":"email-1"}`))
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func TestEmailSettings(t *testing.T) {
	e := newEnv(t)
	e.setupOwner()
	c, _ := e.deviceClient()

	if r := c.do("GET", "/api/v1/settings/email", nil); r.status != 200 || r.body["configured"] != false {
		t.Fatalf("initial: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/settings/email", map[string]any{"from": "me@example.com"}); r.status != 400 || r.body["field"] != "apiKey" {
		t.Fatalf("first save without key: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/settings/email", map[string]any{"apiKey": "sk_nope", "from": "me@example.com"}); r.status != 400 || r.body["field"] != "apiKey" {
		t.Fatalf("bad key: %d %s", r.status, r.raw)
	}
	if r := c.do("PUT", "/api/v1/settings/email", map[string]any{"apiKey": "re_123456789", "from": "nope"}); r.status != 400 || r.body["field"] != "from" {
		t.Fatalf("bad from: %d %s", r.status, r.raw)
	}

	r := c.do("PUT", "/api/v1/settings/email", map[string]any{"apiKey": "re_123456789", "from": "Me <me@example.com>"})
	if r.status != 200 || r.body["configured"] != true || r.body["keyHint"] != "6789" || r.body["from"] != "Me <me@example.com>" {
		t.Fatalf("save: %d %s", r.status, r.raw)
	}
	if strings.Contains(string(r.raw), "re_123456789") {
		t.Fatalf("key returned: %s", r.raw)
	}

	// Changing only the sender keeps the key.
	r = c.do("PUT", "/api/v1/settings/email", map[string]any{"from": "notes@example.com"})
	if r.status != 200 || r.body["keyHint"] != "6789" || r.body["from"] != "notes@example.com" {
		t.Fatalf("change from: %d %s", r.status, r.raw)
	}

	if r := c.do("DELETE", "/api/v1/settings/email", nil); r.status != 204 {
		t.Fatalf("delete: %d %s", r.status, r.raw)
	}
	if r := c.do("GET", "/api/v1/settings/email", nil); r.body["configured"] != false {
		t.Fatalf("after delete: %s", r.raw)
	}
}

func TestEmailNote(t *testing.T) {
	resend := newFakeResend(t)
	e := newEnvWith(t, api.Config{ResendURL: resend.srv.URL})
	e.setupOwner()
	c, _ := e.deviceClient()

	c.do("POST", "/api/v1/notes", map[string]any{
		"operationId": "c1", "id": "n1", "title": "Weekend", "content": "- [ ] Bread\n- [x] Coffee\n\n<script>alert(1)</script>",
	})
	send := map[string]any{"to": []string{"Sam <sam@example.org>", " kim@example.org "}, "revision": 1}

	if r := c.do("POST", "/api/v1/notes/n1/email", send); r.status != 409 || r.body["error"] != "email_not_configured" {
		t.Fatalf("not configured: %d %s", r.status, r.raw)
	}
	c.do("PUT", "/api/v1/settings/email", map[string]any{"apiKey": "re_123456789", "from": "Me <me@example.com>"})

	for _, bad := range []map[string]any{
		{"to": []string{}, "revision": 1},
		{"to": []string{"not an address"}, "revision": 1},
		{"to": strings.Split(strings.Repeat("a@b.c,", 11), ",")[:11], "revision": 1},
	} {
		if r := c.do("POST", "/api/v1/notes/n1/email", bad); r.status != 400 || r.body["field"] != "to" {
			t.Fatalf("bad recipients %v: %d %s", bad["to"], r.status, r.raw)
		}
	}
	if r := c.do("POST", "/api/v1/notes/n1/email", map[string]any{"to": []string{"a@b.c"}, "revision": 2}); r.status != 409 || r.body["error"] != "stale_revision" {
		t.Fatalf("stale: %d %s", r.status, r.raw)
	}
	if r := c.do("POST", "/api/v1/notes/missing/email", send); r.status != 404 {
		t.Fatalf("missing: %d %s", r.status, r.raw)
	}

	r := c.do("POST", "/api/v1/notes/n1/email", send)
	if r.status != 200 || r.body["id"] != "email-1" || len(resend.sent) != 1 {
		t.Fatalf("send: %d %s", r.status, r.raw)
	}
	m := resend.sent[0]
	if resend.auth[0] != "Bearer re_123456789" || m["from"] != "Me <me@example.com>" || m["subject"] != "Weekend" {
		t.Fatalf("payload: %v %v", resend.auth, m)
	}
	if to := m["to"].([]any); len(to) != 2 || to[0] != "sam@example.org" || to[1] != "kim@example.org" {
		t.Fatalf("to: %v", m["to"])
	}
	html, text := m["html"].(string), m["text"].(string)
	if !strings.Contains(html, "☐&nbsp;Bread") || !strings.Contains(html, "☑&nbsp;Coffee") || strings.Contains(html, "<script") {
		t.Fatalf("html: %s", html)
	}
	if !strings.HasPrefix(text, "# Weekend\n\n- [ ] Bread") {
		t.Fatalf("text: %q", text)
	}

	resend.status = 403
	if r := c.do("POST", "/api/v1/notes/n1/email", send); r.status != 502 || r.body["error"] != "email_failed" ||
		r.body["message"] != "The example.com domain is not verified." {
		t.Fatalf("resend error: %d %s", r.status, r.raw)
	}

	c.do("DELETE", "/api/v1/notes/n1", map[string]any{"operationId": "d1", "baseRevision": 1})
	resend.status = 200
	if r := c.do("POST", "/api/v1/notes/n1/email", map[string]any{"to": []string{"a@b.c"}, "revision": 2}); r.status != 404 {
		t.Fatalf("deleted: %d %s", r.status, r.raw)
	}
}
