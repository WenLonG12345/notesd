#!/bin/sh
# Prints the id of an iPhone simulator for xcodebuild: a booted one if there
# is one, else the first available on the newest iOS. By id, because two
# runtimes can hold devices with the same name.
xcrun simctl list devices available -j | python3 -c '
import json, sys
runtimes = json.load(sys.stdin)["devices"]
phones = [(rt, d) for rt, ds in runtimes.items() if ".iOS-" in rt for d in ds if d["name"].startswith("iPhone")]
booted = [d for _, d in phones if d["state"] == "Booted"]
if booted:
    print(booted[0]["udid"])
elif phones:
    newest = max(rt for rt, _ in phones)
    print(next(d["udid"] for rt, d in phones if rt == newest))
else:
    sys.exit("no iPhone simulator: install one with xcodebuild -downloadPlatform iOS")
'
