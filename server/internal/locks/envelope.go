// Package locks covers locked notes (§10.1): recognizing their encrypted
// content and storing the keyring that clients unlock them with. The server
// never holds a key; it only keeps locked bodies out of search, tags,
// previews and email, and stores wrapped keys it cannot open.
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

// IsLocked reports whether content is (or claims to be) a locked body.
func IsLocked(content string) bool { return strings.HasPrefix(content, Prefix) }

// ValidEnvelope reports whether locked content is well formed: one line of
// unpadded base64url long enough to hold a nonce and tag. It cannot check
// the ciphertext itself, but it stops an edit made on the encrypted text
// (a typed character, an added line) from destroying the note.
func ValidEnvelope(content string) bool {
	body, ok := strings.CutPrefix(content, Prefix)
	if !ok {
		return false
	}
	raw, err := base64.RawURLEncoding.Strict().DecodeString(body)
	return err == nil && len(raw) >= minSealed
}
