package locks

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestEnvelopeFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "crypto-fixtures", "locked.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Envelopes []struct{ Name, Envelope string } `json:"envelopes"`
		Invalid   []struct{ Name, Envelope string } `json:"invalid"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	for _, c := range f.Envelopes {
		if !IsLocked(c.Envelope) || !ValidEnvelope(c.Envelope) {
			t.Errorf("%s: not accepted", c.Name)
		}
	}
	// The server can't check ciphertext, only shape: these must be refused.
	shape := map[string]bool{"too short": true, "not base64url": true, "line break": true}
	for _, c := range f.Invalid {
		if shape[c.Name] && ValidEnvelope(c.Envelope) {
			t.Errorf("%s: accepted", c.Name)
		}
	}
}

func TestValidEnvelope(t *testing.T) {
	good := Prefix + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 28 bytes
	for in, want := range map[string]bool{
		good:                           true,
		good + "x":                     false, // a typed character breaks the base64
		good + "\n\nmore text":         false,
		good + "==":                    false, // padded
		good[:30] + "\r\n" + good[30:]: false, // Go's decoder alone would skip these
		"plain " + good:                false,
		Prefix:                         false,
	} {
		if got := ValidEnvelope(in); got != want {
			t.Errorf("ValidEnvelope(%q) = %v, want %v", in, got, want)
		}
	}
}
