// Package migrations embeds the ordered SQL schema migrations.
//
// Files are named NNNN_description.sql and applied in lexical order. The
// applied version is tracked in PRAGMA user_version, so a file must never be
// edited once released; add a new one instead.
package migrations

import "embed"

//go:embed *.sql
var FS embed.FS
