#!/bin/sh
# Run from server/ by `mise run dev:server`, which restarts it on change.
set -e
data="${NOTESD_DATA_DIR:-data}"
go build -o bin/notesd-dev ./cmd/notesd

# Fresh data dir: create a known owner so dev skips onboarding.
if [ ! -f "$data/notes.db" ]; then
  NOTESD_PASSWORD=devpassword bin/notesd-dev create-owner --username dev --data-dir "$data"
  echo "dev owner: dev / devpassword"
fi

exec bin/notesd-dev --data-dir "$data" --log-level debug
