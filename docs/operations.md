# Running notesd

Everything notesd stores is in its data directory (`/var/lib/notesd` in
[hosting.md](hosting.md)):

```text
notes.db       notes, account, sessions and settings
notes.db-wal   recent changes not yet folded into notes.db (may not exist)
notes.db-shm   shared memory index for the WAL (may not exist)
```

## Maintenance commands

Run them as the service user with the same data directory. They're safe to
run while the server is up.

```bash
sudo -u notesd notesd check --data-dir /var/lib/notesd
```

| Command | What it does |
| --- | --- |
| `check` | Runs SQLite's integrity check and prints `ok`. Never changes the database. |
| `export --out DIR` | Writes every note to DIR (new or empty) as a Markdown file named after its title. Locked notes are skipped: the server only has them encrypted. Never changes the database. |
| `reindex` | Rebuilds the search index and tags from the notes. Try it if search results look wrong. |
| `reset-password` | Sets a new password (from `NOTESD_PASSWORD` or stdin) and signs out every session. For when you've forgotten it. |
| `create-owner --username NAME` | Creates the account, if there isn't one yet. |
| `version` | Prints the version. |

notesd never deletes or replaces a damaged database by itself. If `check`
reports problems, stop the server and restore from a backup.

## Monitoring

- `GET /healthz` answers 200 while the process runs.
- `GET /readyz` answers 200 when the database is reachable.

Neither needs a login or exposes any data, so they work with Uptime Kuma,
Gatus, or a systemd watchdog script.

## Logs

notesd logs to stderr, so systemd captures it: `journalctl -u notesd`.
Every API request is logged in one line (query strings are left out, so
search terms aren't). `--log-format json` produces JSON for log collectors,
and `--log-level debug` adds detail.
