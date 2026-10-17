#!/bin/sh
# Runs a command with a fresh notesd on a free local port, its owner account
# created. The UI tests read the address and account from TEST_RUNNER_*
# variables, which xcodebuild passes on to the test runner.
#   usage: with-server.sh NOTESD_BINARY COMMAND...
set -eu
bin=$1
shift
data=$(mktemp -d)
log="$data/log"
# Email goes nowhere: port 9 on loopback refuses, so a test can't reach the real Resend.
# Log flags pinned: the lines below are parsed, so inherited NOTESD_LOG_* mustn't reshape them.
"$bin" --data-dir "$data" --listen 127.0.0.1:0 --resend-url http://127.0.0.1:9 \
  --log-level info --log-format text >"$log" 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null; rm -rf "$data"' EXIT
for _ in $(seq 50); do
  grep -q 'listen=' "$log" && break
  sleep 0.1
done
addr=$(sed -n 's/.*listen=\([^ ]*\).*/\1/p' "$log" | head -1)
token=$(sed -n 's/.*setup_token=\([^ ]*\).*/\1/p' "$log" | head -1)
curl -fsS -X POST "http://$addr/api/v1/auth/setup" -H 'Content-Type: application/json' \
  -d "{\"setupToken\":\"$token\",\"username\":\"owner\",\"password\":\"correct horse\"}" >/dev/null
export TEST_RUNNER_NOTESD_URL="http://$addr"
export TEST_RUNNER_NOTESD_USERNAME=owner
export TEST_RUNNER_NOTESD_PASSWORD="correct horse"
"$@"
