package api

import "testing"

func TestAttachmentBudget(t *testing.T) {
	// Base64 of the attachments plus the bodies must fit Resend's 40 MB.
	for _, c := range []struct{ html, text int }{{0, 0}, {1 << 20, 1 << 20}, {3 << 20, 1 << 20}} {
		b := attachmentBudget(c.html, c.text)
		if total := b/3*4 + c.html + c.text; total > maxEmailBytes-emailOverhead || b < 20<<20 {
			t.Errorf("bodies %d+%d: budget %d makes %d bytes", c.html, c.text, b, total)
		}
	}
	if attachmentBudget(maxEmailBytes, 0) != 0 {
		t.Error("no room left should be no budget")
	}
}
