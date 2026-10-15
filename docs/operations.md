# Running notesd

Everything notesd stores is in its data directory (`/var/lib/notesd` in
[hosting.md](hosting.md)):

```text
notes.db       notes, account, sessions and settings
notes.db-wal   recent changes not yet folded into notes.db (may not exist)
notes.db-shm   shared memory index for the WAL (may not exist)
```

## Upgrading

Install the new binary over the old one and restart:

```bash
sudo install -m 755 notesd-linux-amd64 /usr/local/bin/notesd
sudo systemctl restart notesd
```

Database migrations run automatically on startup. If a migration changes
how notes are indexed, the search index is rebuilt during startup too.
**Take a backup before upgrading.** Migrations only go forward: an older
binary refuses to open a database a newer one has migrated, so going back
means restoring the backup from before the upgrade.

## Backups

Your phones are not a backup: a note deleted on one device is deleted
everywhere.

A built-in `notesd backup` command is planned but not built yet. Until
then, use one of these methods. **Don't** copy `notes.db` alone while the
server is running: recent changes may only be in `notes.db-wal`, and a
copy taken mid-write can be inconsistent.

**Stop, copy, start** (a few seconds of downtime):

```bash
sudo systemctl stop notesd
sudo cp -a /var/lib/notesd /backups/notesd-$(date +%F)
sudo systemctl start notesd
```

**Online, with the `sqlite3` tool** (no downtime; makes a consistent
single-file copy):

```bash
sudo -u notesd sqlite3 /var/lib/notesd/notes.db ".backup '/var/lib/notesd/backup.db'"
```

Then copy the backup off the machine with restic, Borg, rclone or your NAS
tools, and run it on a timer. Check that you can actually open a backup:
`notesd check --data-dir <a copy of the backup directory>`.

### Restoring

Stop notesd, replace the data directory's contents with the backup (a
`.backup` copy goes in as `notes.db`, with no `-wal`/`-shm` files), make sure
the `notesd` user owns it, and start notesd.

**Caveat:** after restoring an *older* backup, devices may hold changes
that are newer than the server's copy. Automatic recovery for this is not
finished yet. Before restoring, make sure each phone has synced if you can,
and afterwards check notes you edited after the backup was taken.

## Maintenance commands

Run them as the service user with the same data directory. They're safe to
run while the server is up.

```bash
sudo -u notesd notesd check --data-dir /var/lib/notesd
```

| Command | What it does |
| --- | --- |
| `check` | Runs SQLite's integrity check and prints `ok`. Never changes the database. |
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

## Signing out devices

Sign out a lost phone or browser in the web app under Settings → Devices.
Changing your password signs out every other session. From the shell,
`reset-password` signs out everything.
