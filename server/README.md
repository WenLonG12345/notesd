# notesd

The backend from [docs/architecture.md](../docs/architecture.md): one Go binary
serving the JSON API, the sync feed, full-text search and the embedded web
app, backed by one SQLite database. Roadmap step 2 (backend foundation)
and the server half of step 3 (the web app, in [apps/web](../apps/web/README.md)).

## Build and test

Requires the toolchain in the root `mise.toml` (see the root README). No
cgo: the SQLite driver is `modernc.org/sqlite` (pure Go, FTS5 included), so
cross-compiling for a Raspberry Pi is a plain `GOARCH` switch.

| Task (`mise run …`) | What it does |
| --- | --- |
| `build:web` | `pnpm build` in `apps/web`, writing `internal/web/dist` |
| `build` | `build:web`, then `./bin/notesd` |
| `release` | `build:web`, then linux/amd64 and linux/arm64 binaries in `./bin` |
| `test:go` | the Go tests with the race detector |
| `lint:go` | golangci-lint (`.golangci.yml`) |
| `clean` | removes `./bin` |

The web app is embedded at compile time, so a plain `go build` embeds
whatever is in `internal/web/dist`. That directory is build output and is
gitignored except for a `.gitkeep` (`//go:embed` needs the directory to
exist). A binary built without the web app still works; web pages show a
"web app not built" placeholder with status 503. For the dev loop, see the
root README.

## Run

```bash
./bin/notesd --data-dir /var/lib/notesd --listen 127.0.0.1:8080 \
    --public-url https://notes.example.com
```

On first start with no account, the server logs a **setup link**
(`setup_url`) and the **setup token** in it. Open the link to create the
owner account in the web app, or paste the token into its onboarding (or
`POST /api/v1/auth/setup`). The token changes on every start unless
`--setup-token` sets it, e.g. so Docker users never need the log. The link
uses `--public-url` when set, otherwise the listen address. Alternatively create it from the shell, which needs access to the
data directory and so is trusted:

```bash
NOTESD_PASSWORD='…' notesd create-owner --username me --data-dir /var/lib/notesd
```

| Command | Purpose |
| --- | --- |
| `serve` (default) | Run the server |
| `create-owner --username NAME` | Create the owner account |
| `reset-password` | Set a new password and sign out every session |
| `reindex` | Rebuild the search index and tags from the notes table (also done automatically at startup when a migration asks for it) |
| `check` | `PRAGMA integrity_check`; never modifies the database |
| `version` | Print the version |

| Flag | Env | Default |
| --- | --- | --- |
| `--data-dir` | `NOTESD_DATA_DIR` | `./data` |
| `--listen` | `NOTESD_LISTEN` | `127.0.0.1:8080` |
| `--public-url` | `NOTESD_PUBLIC_URL` | unset; an `https://` URL makes cookies `Secure` |
| `--log-level` | `NOTESD_LOG_LEVEL` | `info` |
| `--log-format` | `NOTESD_LOG_FORMAT` | `text` (`json` also available) |
| `--setup-token` | `NOTESD_SETUP_TOKEN` | generated and logged |

Passwords for CLI commands come from `NOTESD_PASSWORD` or the first line of
stdin. On memory-constrained hosts, set `GOMEMLIMIT` (e.g. `GOMEMLIMIT=64MiB`).

## API

All endpoints are under `/api/v1` and speak JSON. Errors look like
`{"error": "code", "message": "…", "field": "…", "current": {…}}`.

**Authentication.** Browsers log in with `"client": "web"` and get an
HttpOnly session cookie (30-day sliding expiry) plus a `csrfToken`, which must
be sent as `X-CSRF-Token` on every non-GET request; cross-origin `Origin`
headers are rejected. Native apps log in with `"client": "device"` and get an
`accessToken` (1 hour) and `refreshToken` (180 days, sliding, not rotated, so
a lost refresh response is safe to retry), sent as `Authorization: Bearer`.
Failed logins are limited to 10 per 15 minutes per client address.

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/auth/status` | `{"setupRequired": bool}` |
| POST | `/auth/setup` | `{setupToken, username, password}` |
| POST | `/auth/login` | `{username, password, client, deviceName}` |
| POST | `/auth/refresh` | `{refreshToken}` → new `accessToken` |
| POST | `/auth/logout` | |
| GET | `/auth/session` | current session, username, and `csrfToken` for browsers |
| POST | `/auth/password` | `{currentPassword, newPassword}`; signs out other sessions |
| GET | `/auth/sessions` | signed-in browsers and devices |
| DELETE | `/auth/sessions/{id}` | revoke one |
| GET | `/notes?limit=&cursor=&tag=&untagged=` | live notes, newest first, with `preview` (plain text, one line per block, ≤160 chars); `nextCursor` is opaque; `total` counts the matching live notes. `tag` (with or without `#`) keeps notes with that tag or one nested under it; `untagged=true` keeps notes without tags |
| POST | `/notes` | `{operationId, id, title, content, createdAt?, updatedAt?, conflictOf?}` → 201 |
| GET | `/notes/{id}` | 410 with the tombstone in `current` if deleted |
| PUT | `/notes/{id}` | `{operationId, baseRevision, title, content, updatedAt?}`; both fields required |
| DELETE | `/notes/{id}` | `{operationId, baseRevision}` |
| GET | `/sync?since=&limit=&generation=` | `{changes, nextCursor, hasMore, generation}` |
| GET | `/search?q=&limit=&offset=&tag=&untagged=` | `{results: [{id, title, excerpt, updatedAt}]}`; filters as for `/notes` |
| GET | `/tags` | `{tags: [{key, name, count}]}` sorted by key; parents of nested tags included, `count` is distinct notes including nested tags |

`/healthz` (process up) and `/readyz` (database reachable) sit outside
`/api/v1`. Every other path serves the web app, falling back to `index.html`.

**Mutations** answer `{id, status, revision, changeSeq, updatedAt, deletedAt?}`
where `status` is `created`, `updated`, `unchanged` (identical content, no new
revision), `deleted` or `already_deleted`. Conflicts are `409` with the
server's latest note in `current`:

- `revision_conflict`: `baseRevision` is stale (also for stale deletions, so a
  deletion never discards a newer edit)
- `note_deleted`: the note is a tombstone; the edit should become a
  "Recovered copy"
- `note_exists`: create with an ID already in use, including deleted IDs

**Idempotency.** `operationId` is required on every mutation. A retry with the
same ID and payload returns the original response with
`Idempotent-Replayed: true`, even if the note has changed since; the same ID
with a different payload is `422 operation_id_reused`. Rejected requests
record nothing, and since revisions only grow, a stale request stays
rejected.

**Sync.** Pass the last `nextCursor` as `since`, and the stored `generation`.
`409 generation_mismatch` or `409 cursor_ahead` means the server was
restored from a backup and the client must run a full reconciliation (§11.4).

**Search.** Input is split into words, each quoted as a prefix term, so FTS5
syntax in user input is inert and never errors. `excerpt` is HTML-escaped
note text with matches wrapped in `<mark>`.

## Layout

```text
cmd/notesd   CLI, configuration, graceful shutdown
internal/database  connection pools, pragmas, migration runner, integrity check
internal/notes     create/update/delete, revisions, tombstones, idempotency, listing
internal/sync      change feed
internal/search    Markdown → plain text and tags, FTS5 query building, search, tag listing, reindex
internal/auth      Argon2id, sessions, setup token, login rate limiting
internal/api       routing, auth/CSRF middleware, handlers, gzip, logging
internal/web       embedded web app (apps/web builds into internal/web/dist)
migrations         numbered SQL files, tracked with PRAGMA user_version
```

## Implementation notes and deviations from the spec

- **`notes` has an `INTEGER PRIMARY KEY` (`seq`) and a `UNIQUE` text `id`.**
  The spec keys the FTS index on the notes rowid; an implicit rowid can be
  renumbered by `VACUUM`, which would silently desynchronize search.
- **`sync_state.generation`** is created now (random at first migration) and
  returned on every sync page, so clients store it from day one. Changing it
  on restore belongs to the backup/restore work in step 6.
- **Tombstones clear title and content.** If the 30-day Trash (§6.5) is
  adopted, deletion will need to keep content until the purge.
- **Writes are serialized in Go** through a one-connection pool using
  `BEGIN IMMEDIATE`, so concurrent requests queue instead of failing with
  `SQLITE_BUSY`. Reads use a separate `query_only` pool and never block on
  writes (WAL).
- **Client timestamps** (`createdAt`, `updatedAt`) are accepted so offline
  edits show when they happened, but they are informational only; values
  more than a day in the future are replaced with the server clock.
- **Endpoints added beyond §9:** `auth/status`, `auth/setup`, `auth/session`,
  `auth/password`, `auth/sessions`. §10 requires first-run protection,
  password changes and revocable sessions, and the web app needs a way to
  get its CSRF token back after a reload.
- **Plain-text extraction rules** are pinned by
  `shared/markdown-fixtures/plaintext.json`, which the iOS and Android
  extractors should also be tested against.
- **Not yet built:** `backup` (step 6), YAML config file (flags and env only
  for now), OpenAPI document in `shared/api/`.
