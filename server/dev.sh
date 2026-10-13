#!/bin/sh
# Runs the go command with the project-local toolchain and module cache in
# ../.tools (for environments without network access), e.g.:
#   ./dev.sh test -race ./...
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -x "$ROOT/.tools/go/bin/go" ] && [ "$(uname -s)" = Linux ]; then
  export PATH="$ROOT/.tools/go/bin:$PATH" GOMODCACHE="$ROOT/.tools/gomod" \
         GOPROXY=off GOTOOLCHAIN=local GOFLAGS=-mod=mod
fi
exec go "$@"
