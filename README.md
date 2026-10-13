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
