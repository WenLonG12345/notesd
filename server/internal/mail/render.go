package mail

import (
	"bytes"
	"html/template"
	"regexp"
	"strings"

	"github.com/yuin/goldmark"
	"github.com/yuin/goldmark/extension"
)

// Raw HTML in a note is escaped: goldmark drops it unless WithUnsafe is set.
var md = goldmark.New(goldmark.WithExtensions(extension.GFM))

// Email clients strip form controls, so task-list checkboxes become glyphs.
var checkboxes = strings.NewReplacer(
	`<input checked="" disabled="" type="checkbox"> `, "☑&nbsp;",
	`<input disabled="" type="checkbox"> `, "☐&nbsp;",
)

var page = template.Must(template.New("email").Parse(`<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>{{.Title}}</title></head>
<body style="margin:0;padding:24px 16px;background:#f2f4f5;">
<div style="max-width:640px;margin:0 auto;font:16px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;color:#1d2022;">
{{if .Title}}<h1 style="font:600 28px/1.25 Georgia,'Times New Roman',serif;margin:0 0 16px;">{{.Title}}</h1>{{end}}
{{.Body}}
</div></body></html>
`))

// Subject is the email subject for a note.
func Subject(title string) string {
	if t := strings.TrimSpace(title); t != "" {
		return t
	}
	return "Untitled note"
}

// Render returns a note as an HTML email body and its plain-text part (the
// note as one Markdown file, title as a heading).
func Render(title, content string) (html, text string, err error) {
	title = strings.TrimSpace(title)
	var body bytes.Buffer
	if err := md.Convert([]byte(content), &body); err != nil {
		return "", "", err
	}
	var out bytes.Buffer
	err = page.Execute(&out, struct {
		Title string
		Body  template.HTML
	}{title, template.HTML(checkboxes.Replace(body.String()))}) // #nosec G203 -- goldmark output, raw HTML escaped
	if err != nil {
		return "", "", err
	}
	text = content
	if title != "" {
		text = "# " + title + "\n\n" + content
	}
	return out.String(), text, nil
}

// appImage matches an image the apps added (architecture §7.6) as goldmark
// renders it: src (the whole reference, & escaped), then alt, already
// HTML-escaped. Any other notesd:image/ source is left as it is.
var appImage = regexp.MustCompile(`<img src="notesd:image/([0-9a-f]{64})\?w=[1-9]\d{0,5}&amp;h=[1-9]\d{0,5}" alt="([^"]*)"[^>]*>`)

// ImageIDs lists the app images html shows, each once, in order.
func ImageIDs(html string) []string {
	var ids []string
	seen := map[string]bool{}
	for _, m := range appImage.FindAllStringSubmatch(html, -1) {
		if !seen[m[1]] {
			seen[m[1]] = true
			ids = append(ids, m[1])
		}
	}
	return ids
}

// InlineImages points the app images in html at their attachments
// (cid:<id>). An image that isn't attached (missing, too large to send, or
// encrypted) is replaced by its alt text.
func InlineImages(html string, attached map[string]bool) string {
	return appImage.ReplaceAllStringFunc(html, func(tag string) string {
		m := appImage.FindStringSubmatch(tag)
		if !attached[m[1]] {
			return m[2]
		}
		return `<img src="cid:` + m[1] + `" alt="` + m[2] + `" style="max-width:100%;height:auto;">`
	})
}
