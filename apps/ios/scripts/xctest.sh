#!/bin/sh
# xcodebuild test with a watchdog: xcodebuild sometimes stays up after the
# tests have run. Once the log has been quiet for a minute after tests ran,
# it's stopped. Passes when tests ran and none failed.
#   usage: xctest.sh LOG XCODEBUILD_ARGS...
log=$1
shift
xcodebuild "$@" >"$log" 2>&1 &
pid=$!
while kill -0 $pid 2>/dev/null; do
  sleep 5
  if grep -qE "Test [Cc]ase .*(passed|failed)" "$log"; then
    age=$(( $(date +%s) - $(stat -f %m "$log") ))
    [ "$age" -gt 60 ] && kill $pid 2>/dev/null
  fi
done
# XCTest reports per test case; Swift Testing per run (✔ / ✘).
# AUDIT: what an accessibility audit found (FlowTests.audit prints it), so CI says which element.
grep -E "^/.*error:|^AUDIT |Test [Cc]ase .*(passed|failed)|Test run with|✘" "$log"
grep -qE "Test [Cc]ase .*passed|✔ Test run" "$log" && ! grep -qE "Test [Cc]ase .*failed|error:|✘" "$log"
