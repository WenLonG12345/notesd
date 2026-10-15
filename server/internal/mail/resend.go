package mail

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// DefaultBaseURL is Resend's API.
const DefaultBaseURL = "https://api.resend.com"

// Message is one email.
type Message struct {
	From    string   `json:"from"`
	To      []string `json:"to"`
	Subject string   `json:"subject"`
	HTML    string   `json:"html"`
	Text    string   `json:"text"`
	// Attachments are the note's images, shown inline through their
	// content_id (the HTML refers to them as cid:<content_id>).
	Attachments []Attachment `json:"attachments,omitempty"`
}

// Attachment is one inline image. Content is base64 (standard, padded).
type Attachment struct {
	Filename    string `json:"filename"`
	Content     string `json:"content"`
	ContentType string `json:"content_type"`
	ContentID   string `json:"content_id"`
}

// SendError is Resend refusing or failing a send. Message is Resend's own
// explanation (an unverified domain, a revoked key), shown to the user.
type SendError struct {
	Status  int
	Message string
}

func (e *SendError) Error() string { return fmt.Sprintf("resend: %d %s", e.Status, e.Message) }

// Client calls Resend.
type Client struct {
	BaseURL string
	HTTP    *http.Client
}

// NewClient returns a client for baseURL, or Resend's API if empty.
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = DefaultBaseURL
	}
	return &Client{BaseURL: baseURL, HTTP: &http.Client{Timeout: 20 * time.Second}}
}

// Send sends m with apiKey and returns Resend's email id.
func (c *Client) Send(ctx context.Context, apiKey string, m Message) (string, error) {
	body, err := json.Marshal(m)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/emails", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("Authorization", "Bearer "+apiKey)
	req.Header.Set("Content-Type", "application/json")
	res, err := c.HTTP.Do(req)
	if err != nil {
		return "", &SendError{Message: "couldn't reach Resend"}
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 64<<10))
	if res.StatusCode/100 != 2 {
		var e struct {
			Message string `json:"message"`
		}
		_ = json.Unmarshal(raw, &e)
		if e.Message == "" {
			e.Message = res.Status
		}
		return "", &SendError{Status: res.StatusCode, Message: e.Message}
	}
	var ok struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(raw, &ok); err != nil {
		return "", &SendError{Status: res.StatusCode, Message: "unexpected reply from Resend"}
	}
	return ok.ID, nil
}
