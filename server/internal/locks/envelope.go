// Package locks covers locked notes (§10.1): recognizing their encrypted
// content and storing the keyring that clients unlock them with. The server
// never holds a key; it only keeps locked bodies out of search, tags,
// previews and email, and stores wrapped keys it cannot open. The exception
// is the export command, given the lock password by the owner (open.go).
package locks

import (
	"encoding/base64"
	"strings"
)

// Prefix starts a locked note's content; the rest is base64url(nonce ‖
// ciphertext ‖ tag) of the Markdown body.
const Prefix = "notesd:locked:v1:"

// minSealed is a 12-byte nonce plus a 16-byte GCM tag around an empty body.
const minSealed = 12 + 16

// EnvelopeLen is the length of the locked content for a plain body of n bytes.
func EnvelopeLen(n int) int {
	return len(Prefix) + base64.RawURLEncoding.EncodedLen(minSealed+n)
}

// IsLocked reports whether content is (or claims to be) a locked body.
func IsLocked(content string) bool { return strings.HasPrefix(content, Prefix) }

// ValidEnvelope reports whether locked content is well formed: one line of
// unpadded base64url long enough to hold a nonce and tag. It cannot check
// the ciphertext itself, but it stops an edit made on the encrypted text
// (a typed character, an added line) from destroying the note.
func ValidEnvelope(content string) bool {
	body, ok := strings.CutPrefix(content, Prefix)
	// Go's decoder skips CR and LF even in strict mode; the clients' don't.
	if !ok || strings.ContainsAny(body, "\r\n") {
		return false
	}
	raw, err := base64.RawURLEncoding.Strict().DecodeString(body)
	return err == nil && len(raw) >= minSealed
}
