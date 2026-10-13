# Web app plan (roadmap step 3)

Build the React web client for the notes server: a guided first-run
onboarding, login, and the full notes interface (list, editor, search,
settings). Everything lives in `apps/web` and is embedded into the Go binary
at build time. Read `docs/architecture.md` (the spec) and `server/README.md`
(what the API actually does) before starting.

## 1. What already exists

### Repository

```text
notes/
├── docs/architecture.md      the spec; §2.5, §3.1, §7, §8, §9, §10 matter here
├── server/                   Go backend, done and tested (roadmap step 2)
│   ├── cmd/notes-server      CLI; `go run ./cmd/notes-server` serves on 127.0.0.1:8080
│   ├── internal/api          routes, auth middleware, CSRF, handlers
│   ├── internal/web          serves the embedded web build from internal/web/dist
│   ├── internal/web/dist     Vite build output (gitignored except .gitkeep)
│   ├── dev.sh                `./dev.sh test ./...` etc. (uses local toolchain if present)
│   └── README.md             full API reference
├── apps/web/package.json     written, nothing else yet
├── shared/markdown-fixtures/plaintext.json   Markdown → plain text rules (search)
└── .tools/                   offline Go toolchain + module cache (gitignored)
```

### Server facts the web app must respect

- **Base URL**: same origin. The Go binary serves `/api/v1/*`, `/healthz`,
  `/readyz`, and the web app for every other path (SPA fallback to
  `index.html`, so client-side routes work on reload). Files under
  `/assets/` are served with a one-year immutable cache; Vite's
  fingerprinted output must land there (it does by default).
- **Content-Security-Policy** on web pages:
  `default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`.
  So: no inline `<script>`, no external fonts/CDNs, no `eval`. Vite's
  production build is fine; the dev server is used behind Vite's own origin
  and is not subject to it. Self-host any web font as a file under `src/`.
- **Web auth model** (`"client": "web"` on login):
  - The server sets an HttpOnly session cookie (`notes_session`, or
    `__Host-notes_session` when `--public-url` is https). The browser sends
    it automatically; the app never sees it.
  - Login and `GET /api/v1/auth/session` return `csrfToken`. Keep it **in
    memory only** (React state / module variable), never in localStorage.
    Send it as `X-CSRF-Token` on every non-GET request. Missing/wrong →
    `403 csrf_failed`.
  - Session cookie expires after 30 days, sliding. A `401 unauthenticated`
    on any call means "go to the login screen".
  - Failed logins: 10 per 15 min per IP → `429` with `Retry-After`.
- **First run**: `GET /api/v1/auth/status` → `{"setupRequired": true}` until
  the owner account exists. The server prints a `setup_token=…` line in its
  log at startup. `POST /api/v1/auth/setup {setupToken, username, password}`
  creates the account (password ≥ 8 chars). Wrong token → `403
  invalid_setup_token`. After setup, `setupRequired` is false and the token
  stops working.
- **Note model** (`GET /api/v1/notes/{id}`):
  `{id, title, content, revision, changeSeq, createdAt, updatedAt, deletedAt, conflictOf?}`.
  `content` is Markdown. Timestamps are Unix **milliseconds**. `revision`
  is the optimistic-concurrency token.
- **Mutations** all require a client-generated `operationId` (unique string,
  ≤128 chars of `[A-Za-z0-9_.:-]`; use `crypto.randomUUID()`) and answer
  `{id, status, revision, changeSeq, updatedAt, deletedAt?}`:
  - `POST /api/v1/notes {operationId, id, title, content}` → 201. The
    **client generates the note id** (`crypto.randomUUID()` is fine; the
    spec recommends UUIDv7 but any `[A-Za-z0-9_-]{1,64}` works).
  - `PUT /api/v1/notes/{id} {operationId, baseRevision, title, content}` →
    200, `status` = `updated` or `unchanged` (identical content; revision
    does not change). **Both title and content are required** (full
    replacement).
  - `DELETE /api/v1/notes/{id} {operationId, baseRevision}` → 200.
  - Retrying the same `operationId` + same payload returns the original
    response with header `Idempotent-Replayed: true` (safe after a lost
    response). Same id + different payload → `422 operation_id_reused`, so
    **mint a new operationId for every distinct save**.
  - Conflicts are `409` with `{"error": code, "current": {…latest note…}}`:
    - `revision_conflict`: our `baseRevision` is stale (edited elsewhere).
    - `note_deleted`: the note was deleted elsewhere; `current` is the
      tombstone.
    - `note_exists`: create with an id already in use.
- **List**: `GET /api/v1/notes?limit=50&cursor=…` →
  `{notes: [{id, title, preview, revision, createdAt, updatedAt}], nextCursor, hasMore}`.
  Newest `updatedAt` first. `preview` is the first ~160 chars of plain text.
  Deleted notes are excluded.
- **Search**: `GET /api/v1/search?q=…&limit=30&offset=0` →
  `{results: [{id, title, excerpt, updatedAt}]}`. `excerpt` is **HTML-safe**:
  the note text is already escaped and matches are wrapped in
  `<mark>…</mark>`, so render it with `dangerouslySetInnerHTML` (that is the
  one place this is allowed). Prefix matching is built in; any input is
  safe (no FTS syntax errors ever surface).
- **Other auth endpoints**: `POST /auth/logout` (204), `POST /auth/password
  {currentPassword, newPassword}` (204; signs out every other session),
  `GET /auth/sessions` → `{sessions: [{id, kind, name, createdAt,
  lastUsedAt}], currentId}`, `DELETE /auth/sessions/{id}` (204).
- **Error shape**: `{error, message?, field?, current?}` with sensible HTTP
  codes (400 invalid_request with `field`, 401, 403, 404, 409, 422, 429, 500).

### Not in scope for the web app

Offline mode, a local database, a sync engine, conflict *merging* (the web
app only has to *handle* conflicts, see §5), the `/sync` endpoint (that is
for native apps).

## 2. Stack and project setup

`apps/web/package.json` and `tsconfig.json` are already written:
React 19, **TypeScript 7** (the Go-based compiler, now published as the
normal `typescript` package; `strict` and `esnext` are its defaults and
6.0's deprecations such as `baseUrl` are hard errors, so keep `tsconfig`
minimal and never add deprecated options), Vite 8, Tiptap 3 (ProseMirror),
`prosemirror-markdown` + `markdown-it` for Markdown ⇄ document conversion,
Vitest. The package manager is **pnpm** (`packageManager` field pins it;
`corepack enable` or `npm i -g pnpm` if it is missing). Node is only a
build tool.

Dependencies use `^` ranges; `pnpm-lock.yaml` is what pins them.

Type checking is `pnpm typecheck`: `tsc --noEmit` for `src`, plus
`tsconfig.node.json` for `vite.config.ts` (Node types stay out of browser
code; no `-b`, no project references); Vite does
the transpiling. Lint is `pnpm lint` (oxlint, `.oxlintrc.json`):
typescript-eslint cannot load TypeScript 7, and oxlint needs no TS API. If TypeScript 7 rejects a type from a dependency, fix it
with a local `.d.ts` shim, not by downgrading.

Setup tasks:

1. `cd apps/web && pnpm install`.
2. `vite.config.ts`:
   - `build.outDir = '../../server/internal/web/dist'`, `emptyOutDir: true`.
   - `server.proxy = { '/api': 'http://127.0.0.1:8080', '/healthz': …, '/readyz': … }`
     so `pnpm dev` (Vite on :5173) talks to a locally running Go server.
     Cookies work because the proxy keeps the same origin from the
     browser's point of view. Note the server rejects mutations whose
     `Origin` host differs from the request `Host`; through the proxy both
     are `localhost:5173`, so this is fine.
3. Dev loop: terminal 1 `cd server && go run ./cmd/notes-server`, terminal 2
   `cd apps/web && pnpm dev`, open http://localhost:5173.
4. Production check: `pnpm build`, then `cd server && go build
   ./cmd/notes-server && ./notes-server` and open http://127.0.0.1:8080 —
   the Go binary must serve the real app (verify the `/assets/*.js` paths
   resolve and client-side routes survive a reload).
5. Add a `web` target to `server/Makefile` that runs `pnpm install
   --frozen-lockfile && pnpm build` in `apps/web` before `build`, and
   document in `server/README.md` that `dist/` is build output.
   `server/internal/web/dist/*` is gitignored except `.gitkeep` (the Go
   `//go:embed all:dist` needs the directory to exist; a Vite plugin
   restores it after `emptyOutDir`). Without a build, the server serves the
   embedded `internal/web/placeholder.html` with status 503.

## 3. Source layout

```text
apps/web/src/
├── main.tsx                 mount, router
├── app.tsx                  top-level: auth gate → onboarding | login | shell
├── api/
│   ├── client.ts            fetch wrapper: JSON, CSRF header, 401 → auth state, typed errors
│   ├── types.ts             Note, ListItem, SearchResult, ApiError, conflict shapes
│   ├── auth.ts              status/setup/login/logout/session/password/sessions
│   └── notes.ts             list/get/create/update/delete/search
├── auth/
│   ├── auth-context.tsx     {status: 'loading'|'setup'|'signed-out'|'signed-in', username, csrfToken}
│   ├── onboarding.tsx       guided first-run flow (§4)
│   └── login.tsx
├── notes/
│   ├── shell.tsx            two-pane layout: sidebar (list/search) + editor
│   ├── note-list.tsx        list with cursor pagination, selection, previews
│   ├── search.tsx           search box + results with <mark> excerpts
│   ├── note-view.tsx        loads a note, owns the save state machine (§5)
│   ├── empty.tsx            empty states (no notes yet / nothing selected)
│   └── use-autosave.ts      debounce + per-note serialized saving
├── editor/
│   ├── editor.tsx           Tiptap editor + toolbar
│   ├── extensions.ts        the allowed set (§7.1 of the spec), nothing more
│   ├── markdown.ts          parse(markdown) → ProseMirror doc; serialize(doc) → markdown
│   └── markdown.test.ts     round-trip tests against shared fixtures
├── settings/
│   └── settings.tsx         change password, active sessions, sign out
├── ui/                      small primitives: button, field, dialog, toast, status pill
└── styles/                  tokens.css, base.css, (component css modules)
```

Routes (client side, via `history` API or a tiny router; no need for a
routing library): `/` (notes), `/notes/:id`, `/settings`. Onboarding and
login are not routes: the auth gate renders them whenever the auth status
says so, then returns to wherever the user was.

## 4. Guided onboarding and login

### Auth gate (`app.tsx`)

On load, call `GET /api/v1/auth/status`:

- `setupRequired: true` → render **onboarding**.
- otherwise call `GET /api/v1/auth/session`:
  - 200 → signed in; store `username` and `csrfToken` in memory; render shell.
  - 401 → render **login**.

Any later `401` from the API flips the state to signed-out (show login with
the message "Your session ended. Sign in again."; keep unsaved editor
contents in memory so a successful login can retry the save).

### Onboarding (first run)

A single-column, centered, step-by-step flow. Steps:

1. **Welcome** — one short paragraph: this server has no account yet; you
   will create the owner account now. Button "Set up my account".
2. **Setup token** — explain where it comes from ("The server printed a
   setup token in its log when it started. Paste it here."), with the
   exact log line shown as an example
   (`setup_token=THQvj2U_aMzG…`), and a note that
   `notes-server create-owner` is the alternative. Input + "Continue".
   Validate on the next step (the API validates token + credentials
   together), so keep the token in state.
3. **Account** — username, password, confirm password. Client-side checks:
   password ≥ 8 chars, both match. Button "Create account" →
   `POST /auth/setup`. Errors: `403 invalid_setup_token` → jump back to
   step 2 with the message "That token didn't match. Copy it from the
   server log again."; `400` with `field` → show inline under that field;
   `409 already_set_up` → go to login.
4. **Done** — after a successful setup, immediately `POST /auth/login`
   with the same credentials (`client: "web"`) so the user lands in the
   app signed in, with one empty state inviting them to create a first
   note. Do not make them type the password twice.

Progress is implicit (step N of 3 shown as a small indicator). Back buttons
on steps 2–3. Everything keyboard-accessible; Enter submits.

### Login

Username + password, "Sign in". `401 invalid_credentials` → "Wrong username
or password." `429` → "Too many attempts. Try again in N seconds" using
`Retry-After`. No "forgot password" link (there is no email); say instead
"Forgot it? Run `notes-server reset-password` on the server." in small text.

## 5. Notes interface

### Layout

Two panes on desktop: a 19–21rem sidebar (search box on top, note list
below, "New note" button) and the editor filling the rest. On narrow
screens (< 48em) the panes stack: list screen ↔ editor screen with a back
button. Settings opens as a route in the editor pane.

### Note list

- `GET /notes?limit=50` on mount; "Load more" / infinite scroll via
  `nextCursor` while `hasMore`.
- Each row: title (or "Untitled" in muted style when empty), one line of
  `preview`, relative time from `updatedAt` ("just now", "3 min", "Tue",
  "12 Mar"). Selected row highlighted.
- Keep the list in client state and update it in place after saves
  (title/preview/updatedAt, move to top) and deletes, rather than refetching
  on every change. Refetch on window focus if the list is older than
  ~30 s (other devices may have synced).
- "New note": create locally with a fresh `crypto.randomUUID()`, empty
  title/content, select it, focus the title. Do **not** POST yet — POST on
  the first autosave that has content (an abandoned empty note never hits
  the server). If the user navigates away from an empty, never-saved note,
  drop it. Don't push `/notes/:id` to the URL until the first POST
  succeeds, so a reload never points at a note the server doesn't have.

### Editor (Tiptap)

Allowed extensions, exactly the §7.1 subset: Document, Paragraph, Text,
HardBreak, Bold, Italic, Strike, Heading (levels 1–3), BulletList,
OrderedList, ListItem, TaskList, TaskItem (nested: true), Link
(openOnClick: false, autolink, validate http/https/mailto), Blockquote,
UndoRedo (Tiptap 3's name for History), Placeholder. Explicitly
**exclude** Code, CodeBlock, HorizontalRule, Image, Table, Underline,
Highlight, etc. Tiptap 3's StarterKit also bundles Link, Underline,
ListKeymap and TrailingNode: turn off what isn't allowed and configure Link
through StarterKit rather than adding it twice. TaskList/TaskItem live in
`@tiptap/extension-list` in v3.

Title is a separate plain `<input>` above the editor (not part of the
document). Content is the Tiptap document.

Toolbar: bold, italic, strikethrough, H1/H2/H3, bullet list, numbered list,
checklist, blockquote, link (dialog with URL input; Cmd/Ctrl-K). Keyboard
shortcuts come from the extensions. Show active state per button.

### Markdown conversion (`editor/markdown.ts`)

- Parse with `markdown-it` (`html: false`, `linkify: false`, `breaks:
  false`) + the task-list syntax `- [ ]` / `- [x]`, into a ProseMirror doc
  using `prosemirror-markdown`'s `MarkdownParser` with a token map covering
  only the allowed nodes/marks. Unknown constructs (code fences, tables,
  images, raw HTML, code spans, horizontal rules) must not be silently
  dropped: keep them as a `rawBlock` node / `rawInline` atom holding their
  exact source, shown read-only and written back verbatim (spec §7.1).
  Literal text is not enough: the serializer would escape it and fold its
  line breaks, corrupting the note on first save.
- Serialize with `MarkdownSerializer`, deterministic per §7.4: `**bold**`,
  `*italic*`, `~~strike~~`, `- ` bullets, `1.` ordered, `- [ ]`/`- [x]`
  tasks, `# ` headings, `> ` quotes, `[text](url)`, `\` + newline for
  hard breaks (§7.4), one blank line between blocks, no trailing whitespace, file
  ends with a single `\n` (or is empty).
- Tests (`markdown.test.ts`): every case in
  `shared/markdown-fixtures/plaintext.json` must parse without throwing and
  re-serialize to a string that parses to an equal document (semantic
  round-trip, not byte equality). Add a second fixture file
  `shared/markdown-fixtures/roundtrip.json` of canonical app-produced
  Markdown that **must** round-trip byte-for-byte; the native editors will
  use it too.
- The editor works on the ProseMirror document; serialize to Markdown only
  when saving, never per keystroke (§7.2).

### Autosave and the save state machine (`note-view.tsx`, `use-autosave.ts`)

Per open note, keep: `serverRevision`, `lastSavedTitle/Content`,
`dirty`, `saveState: 'saved'|'unsaved'|'saving'|'failed'|'conflict'`.

- On edit: mark `unsaved`, debounce 1.5 s (reset on each change). Also
  flush immediately on: switching notes, `visibilitychange` → hidden,
  `beforeunload` (use `fetch` with `keepalive: true` when the body is
  ≤ 60 KB, the browser's keepalive limit being 64 KB; warn with the native
  prompt whenever a save is still pending or too large for keepalive).
- Save: serialize, skip if equal to last saved, mint a new `operationId`,
  `PUT` with `baseRevision = serverRevision` (or `POST` if never created).
  Saves for one note are **serialized** (await the in-flight one; if the
  content changed meanwhile, save again afterwards with a new operationId
  and the revision the previous response returned). Different notes save
  independently.
- On success: update `serverRevision`, `lastSaved*`, `saved`; update the
  list row.
- Network failure / 5xx: `failed`, retry with backoff (2 s, 4 s, … max 30
  s) **reusing the same operationId and payload** so a lost response
  replays safely. Show "Couldn't save — retrying" with a manual "Retry now".
- `409 revision_conflict`: the note changed on another device. Do not
  overwrite and do not lose the local edit. First try the §6.1 field
  merge (base = `lastSaved*`): a field changed on one side only takes that
  side's value; identical changes are resolved; re-save against
  `current.revision` and repeat if it moves again. Only when both sides
  changed the content differently, show a conflict banner with the two
  choices the spec allows for the web client:
  - "Keep mine": save the local version as a **new note** titled
    `<Title> (Conflicted copy)` via `POST` with `conflictOf: <id>`, then
    reload the original note from `current`.
  - "Use theirs": discard local edits and load `current`.
- `409 note_deleted`: show "This note was deleted on another device" with
  "Keep as new note" (POST as `<Title> (Recovered copy)`) or "Discard".
- `401`: keep everything in memory, show login, retry after sign-in.
- Status pill in the editor header shows the state: Saved / Unsaved /
  Saving… / Couldn't save / Conflict. No toasts for routine saves.

### Delete

"Delete note" in a note menu → confirm → `DELETE` with the current
revision → remove from list, select the next note. `409` on delete means
it changed elsewhere: reload it and ask again.

### Search

Search box in the sidebar; while it has ≥ 1 character the list is replaced
by results. Debounce 150 ms, abort the previous request
(`AbortController`), ignore stale responses. Rows show title and the
excerpt with `<mark>` highlighted (styled, not default yellow). Escape
clears. Empty results → "No notes match “…”".

### Settings

Route `/settings`: username (read-only), change password form, active
sessions list (kind, name, last used; "Sign out" per session, the current
one labelled), and "Sign out" for this browser. After changing the
password the server has signed out every *other* session — say so.

### Connection status

Poll nothing. Derive "offline" only from failed requests
(`TypeError` from fetch / `navigator.onLine === false`); show a quiet
banner "No connection to the server — changes will be saved when it's
back" and retry pending saves on `online` event / focus.

## 6. Design direction

Not a template dashboard. One memorable thing, everything else quiet.

- **Typography**: one self-hosted variable sans with real italics and
  tabular numerals (e.g. Inter is the safe choice; a more characterful
  option such as "Instrument Sans" or "Geist" is welcome). Body 1rem /
  1.55, editor content 1.0625rem / 1.6 with a 68ch max line length, H1 1.6em,
  H2 1.3em, H3 1.1em, tight letter-spacing on headings. The note title
  input is set in the same face at ~1.8em, not a different font.
- **Color**: near-white paper `#FAFAF8` / ink `#1A1A1A`; sidebar a shade
  darker `#F2F1ED`; one accent for selection, links and the checked task
  box — pick a saturated blue-teal around `#1F6F8B` (not purple, not
  terracotta); muted text `#6B6B66`; hairline `#E4E2DC`. Dark mode via
  `prefers-color-scheme` with the same roles re-mapped (paper `#161615`,
  ink `#ECEAE4`). Define everything as CSS variables in `tokens.css`.
- **Layout**: left-aligned; generous editor padding (3.5rem top, 3rem sides
  on desktop); the sidebar separated by a hairline, not a shadow; selected
  row uses a soft accent tint, no cards, no drop shadows anywhere.
- **The one memorable thing**: the save status is a small live
  indicator in the editor header — a dot that breathes while saving and
  settles when saved, with the state word next to it. Everything else is
  still.
- **Motion**: only in response to actions (pane switch on mobile, dialog
  open, status change); respect `prefers-reduced-motion`.
- **Copy**: sentence case, plain verbs ("New note", "Delete note", "Sign
  in", "Save changes"), errors say what happened and what to do.
- **Accessibility**: visible focus rings, buttons have labels, list is a
  `listbox`/`option` or links, editor toolbar buttons have `aria-pressed`,
  dialogs trap focus, color contrast ≥ 4.5:1.

## 7. Order of work

1. Project setup, config, proxy, blank page builds into the Go binary and
   is served by it. Commit.
2. API client + auth context + auth gate; onboarding and login screens,
   tested end to end against a fresh `server/data` (delete it to re-run
   first-run).
3. Shell + note list + empty states; create/select/delete without the rich
   editor (plain textarea), with autosave and the state machine. Verify
   conflict handling by editing the same note with curl.
4. Markdown parse/serialize + tests; swap the textarea for Tiptap; toolbar;
   links; task lists.
5. Search; settings; connection banner; mobile layout.
6. Polish pass against §6; keyboard audit; dark mode; `pnpm build` →
   Go binary → manual run-through of every screen from first run.
7. Update `server/README.md` (build steps) and add `apps/web/README.md`
   (dev loop, how the build is embedded, conflict behaviour).

## 8. Definition of done (from the roadmap)

Notes can be managed entirely in a browser served by the single Go binary
with no separate frontend process: first-run setup, sign in, create, edit
with formatting, autosave, search, delete, handle a conflict without losing
text, change password, sign out. `pnpm build` output is what the binary
serves. `pnpm lint`, `pnpm typecheck`, `pnpm test` and `go test ./...` pass.
