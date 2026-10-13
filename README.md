# Notes

A self-hosted, offline-first notes app: one Go binary with SQLite on the
server, native iOS and Android apps that work fully offline, and a web app
embedded in the server.

- [docs/architecture.md](docs/architecture.md) — the architecture
  specification
- [server/](server/) — the backend (roadmap step 2: done, see its README)
- [apps/web/](apps/web/) — the web app, embedded in the server binary
  (roadmap step 3: done, see its README)
- `apps/ios`, `apps/android` — not started
- `shared/` — specifications and fixtures shared across platforms
  (`markdown-fixtures/plaintext.json` pins search-text extraction)

## Development

Tooling is pinned and run with [mise](https://mise.jdx.dev) (`brew install
mise`): Go, Node, pnpm, watchexec and golangci-lint, all from `mise.toml`.

```bash
mise install        # toolchain
mise run dev        # Go server (rebuilt on change) + Vite: http://localhost:5173
mise run check      # lint, typecheck and test, Go and web
mise run build      # server/bin/notes-server with the web app embedded
mise tasks          # everything else
```

`mise run dev` keeps its database in `server/data`. On a fresh data dir it
creates the owner `dev` / `devpassword`, so there's no onboarding;
`mise run dev:reset` deletes it. `NOTES_DATA_DIR`, `NOTES_LISTEN` and
`NOTES_SERVER` (Vite's proxy target) override the defaults, e.g. to run a
second copy next to the first.

The repo root is a pnpm workspace (`apps/web` for now), with one lockfile.
