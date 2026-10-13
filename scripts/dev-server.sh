#!/bin/sh
# Run from server/ by `mise run dev:server`, which restarts it on change.
set -e
data="${NOTES_DATA_DIR:-data}"
go build -o bin/notes-dev ./cmd/notes-server

# Fresh data dir: create a known owner so dev skips the setup-token onboarding.
if [ ! -f "$data/notes.db" ]; then
  NOTES_PASSWORD=devpassword bin/notes-dev create-owner --username dev --data-dir "$data"
  echo "dev owner: dev / devpassword"
fi

exec bin/notes-dev --data-dir "$data" --log-level debug
