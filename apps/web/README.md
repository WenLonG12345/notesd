# notesd web app

The browser client for [notesd](../../server/README.md): React 19,
TypeScript, Vite 8 and Tiptap 3. It builds into the server's
`internal/web/dist` and is embedded in the Go binary, so a deployment is just
that one binary; there is no separate frontend process. The plan it follows
is [docs/web-app-plan.md](../../docs/web-app-plan.md), and the design is the
prototype in [design-system/](../../design-system/).

## Dev loop

From the repo root, `mise run dev` starts the API server (rebuilt on Go
changes) and Vite at http://localhost:5173. See the root README.

Vite proxies `/api`, `/healthz` and `/readyz` to `http://127.0.0.1:8080`.
Set `NOTESD_SERVER` to point it elsewhere. The proxy keeps the browser's
`Host` (`changeOrigin: false`). If you change that, every save fails with
403: the server rejects a mutation whose `Origin` host doesn't match `Host`.

A fresh dev database gets the owner `dev` / `devpassword`. To test the real
first-run onboarding, run the server by hand on an empty data dir
(`go run ./cmd/notesd --data-dir /tmp/empty` in `server/`); it logs a
setup link.

## Checks

`mise run check` at the root runs these together with the Go checks.

```bash
pnpm lint         # oxlint, warnings are errors
pnpm typecheck    # app, and config/test files (tsconfig.node.json)
pnpm test         # Vitest
```

Most tests are integration tests: they render the whole `<App/>` in jsdom
against a real notesd. The suite builds the server once per run
(`go build`, so Go must be on `PATH`), and each test starts its own instance
with an empty data directory. Conflicts are tested the real way, by editing
the same note through the API as a second client. A few things jsdom can't
do (moving focus inside ProseMirror, Backspace at a block boundary, layout)
are checked by hand in a browser.

## Build and embedding

```bash
pnpm build        # typecheck, then vite build into ../../server/internal/web/dist
```

The server embeds that directory with `//go:embed` and serves it, sending
every unknown path to `index.html` so client routes (`/notes/:id`,
`/settings`) survive a reload. `dist/` is gitignored except for a `.gitkeep`
that the embed needs; the build puts it back after emptying the directory.
A server built without the web app still runs and answers web pages with a
503 "web app not built" placeholder.

`mise run build` at the root runs this, then builds the server binary.

Fonts are self-hosted through `@fontsource`; the server's Content Security
Policy doesn't allow font CDNs. The bundle is one chunk of about 790 KB
(265 KB gzipped), mostly the editor. It could be split if load time ever
matters.

## Source layout

```text
src/
  api/        fetch wrapper (CSRF token, typed errors, offline detection), endpoints
  auth/       auth gate state, onboarding, sign in
  notes/      split view: tags panel, list, editor pane, store, saver, search, tag extractor
  editor/     Tiptap setup, Markdown ⇄ document, toolbar and text styles, tag styling and suggestions
  settings/   appearance, password, signed-in devices, sign out
  ui/         design-system primitives
  styles/     tokens (light and dark), base styles, fonts
test/         integration tests and the test server harness
```

## Editing

The editor follows the Apple Notes pattern: one document for the whole note,
with the first line as the title. The document is `title block+`. The title
line is plain text with no formatting and is saved to the note's `title`
field; the rest is serialized to Markdown as `content`. The stored note, the
sync protocol and the per-field merge are therefore the same as on every
other client.

Formatting is limited to the spec's dialect (§7.1): bold, italic,
strikethrough, three heading levels, bulleted, numbered and task lists,
quotes and links (http, https and mailto). Markdown outside it, such as code,
tables and images, is shown read-only and written back byte for byte, so
opening and saving a note never changes text written on another device.
Output is canonical (`**bold**`, `-` bullets, one task syntax), and the
round trip is pinned by `shared/markdown-fixtures/roundtrip.json`.

## Tags

Tags are text in the note (`#tag`, `#multi word#`, `#parent/child`; spec
§7.5), so they need nothing from the editor but styling. `src/editor/tags.ts`
finds them in each line with the shared extractor (`src/notes/tags.ts`,
pinned by `shared/markdown-fixtures/tags.json`), skipping link text and code,
and marks them with a decoration: they stay plain, editable text.
⌘-click (Ctrl+click elsewhere) shows the notes with that tag. Typing `#`
and a letter suggests existing tags; a name with spaces is inserted closed.

The tag tree (All notes, Untagged, tags with counts) comes from `GET /tags`
and is refetched after saves. From 1100px wide it's a sidebar beside the list,
which can be hidden; narrower it's a screen of its own at `/tags`, reached
from the list's "‹ Tags", in the list's place. Settings open from it. The
list last shown is remembered per browser. Choosing a tag (or Untagged) reloads the list and scopes search
through the server's `tag` and `untagged` filters. A new note made while a
tag is selected starts with that tag, and is still dropped if nobody types.
Renaming and deleting tags aren't built yet.

## Saving and conflicts

Each open note has a saver (`src/notes/saver.ts`) that works independently
of React, so saves outlive the editor.

- **Autosave.** 1.5 s after the last change, the note is serialized and
  sent. Saves for a note are serialized: one request in flight, and edits
  made meanwhile go out afterwards against the revision it returned. The
  status pill shows Unsaved, Saving, Saved, Couldn't save or Conflict.
- **Retries.** A network failure or 5xx retries with backoff (2 s doubling
  to 30 s), and again on focus or when the browser comes back online. A
  retry reuses the request's `operationId`, so a save whose response was
  lost is replayed by the server instead of being applied twice. A 4xx that
  won't fix itself (for example a note that's too large) is reported, not
  retried.
- **Session ended.** A 401 keeps every edit in memory and shows a sign-in
  dialog over the open note; the waiting saves go out after sign-in.
- **Closing the tab.** Pending saves are sent with `keepalive`, and the
  browser asks before leaving while anything is unsaved.
- **Edited on another device** (`409 revision_conflict`). The title and the
  content are merged separately, against the last version this browser
  saved (§6.1). Content is compared in canonical Markdown, so another
  client's formatting style doesn't count as a change. If only one side
  changed a field, that side wins, and the merge is saved without asking.
- **Both sides changed the same field.** Nothing is saved until the user
  picks: "Use theirs" drops the local edits; "Keep mine" saves the local
  version as a new note, "<Title> (Conflicted copy)", linked to the original
  by `conflictOf`, and shows the server's version in the original.
- **Deleted on another device** (`409 note_deleted`). "Discard" drops the
  edits; "Keep as new note" saves them as "<Title> (Recovered copy)".
- **Deleting.** The note disappears at once and "Note deleted · Undo" shows
  for 5 s. The `DELETE` is sent when that passes, or with `keepalive` if the
  page unloads first. If the note was edited elsewhere in the meantime, the
  server refuses the stale delete and the note comes back.

New notes exist only in the browser until their first save with content, and
go into the URL only once the server has them. Leaving a new note nobody
typed into drops it without a request.

## Not built yet

- Pinning: it needs a field on the server that syncs like the others.
- Naming the other device in the conflict banner: the server doesn't record
  which session made a change.
