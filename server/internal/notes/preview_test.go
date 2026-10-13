package notes

import (
	"strings"
	"testing"
)

func TestPreview(t *testing.T) {
	long := strings.Repeat("word ", 40)
	tests := []struct {
		name string
		in   string
		more bool
		want string
	}{
		{"one line per block", "Title\n\nFirst  item\nSecond\titem", false, "Title\nFirst item\nSecond item"},
		{"blank and space-only lines dropped", "\n  \nA\n\n\n   \nB\n", false, "A\nB"},
		{"cut at the limit", long, false, strings.TrimSpace(long[:160]) + "…"},
		{"short but more was cut off before", "A\nB", true, "A\nB…"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := preview(tt.in, tt.more); got != tt.want {
				t.Errorf("preview(%q) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}
