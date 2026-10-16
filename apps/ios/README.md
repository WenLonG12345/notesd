# notesd for iOS

The native iPhone client: offline-first, with its own SQLite database and
full-text index, a native rich-text editor, and sync with notesd. Design and
protocol are in [`docs/architecture.md`](../../docs/architecture.md); the
Android app (`apps/android`) is the reference this one ports from, file for
file where it can.

This README is for working on the app. To install and use it, see
[`docs/ios.md`](../../docs/ios.md).

## Setup

- Xcode 26 or later (`xcode-select -s /Applications/Xcode.app`), with an iPhone
  simulator (`xcodebuild -downloadPlatform iOS`).
- [mise](https://mise.jdx.dev) for Go, which the tests use to build a server.
- To run on a phone: `Config/Local.xcconfig` (copy the `.example`) with your
  team and a bundle ID prefix of your own. Simulator builds don't need it.

## Everyday tasks

Run from the repository root:

| Task | What it does |
| --- | --- |
| `mise run test:ios:core` | NotesCore tests on the Mac: shared fixtures, store, API client, and every sync scenario against a real notesd |
| `mise run test:ios` | The editor tests, then the UI flows against a real notesd, on a simulator |
| `mise run lint:ios` | swift-format (`.swift-format`), warnings fail |
| `mise run build:ios` | Debug build for a simulator |

`NOTESD_SEEDS=1,2,3` runs `RandomSyncTests` with other seeds.

## Layout

```text
apps/ios/
├── NotesCore/    Swift package, no UIKit: document model, Markdown codec, plain text and tags,
│                 database and search, API client, sync engine. Builds for macOS too.
├── Notes/        the app
│   ├── Editor/   the UITextView (TextKit 2) editor: attributes, codec, normaliser, formatting bar
│   ├── Notes/    list, tags, note screen and its model
│   ├── Account/  onboarding, the account in the Keychain
│   ├── Sync/     when to sync: foreground triggers, background refresh
│   ├── Settings/
│   └── UI/       design tokens and type
├── NotesTests/   editor tests, hosted in the app
├── NotesUITests/ end-to-end flows and accessibility audits
├── Config/       xcconfig and Info.plist
└── scripts/      test helpers: a server for the UI tests, a simulator, a watchdog for xcodebuild
```

`Notes.xcodeproj` uses folder-synchronized groups: a file added to one of
those folders is in its target, with no project edit.

## How it differs from Android

- **Markdown parsing** runs the web app's own tokenizer, markdown-it 14.3.2
  (`NotesCore/Sources/NotesCore/Resources`), in JavaScriptCore, so a note
  parses exactly as it does on the web. Android matches it with
  commonmark-java plus workarounds. The serializer is a Swift port of
  Android's, itself a port of the web's. `markdown.js` is the tokenizer half
  of `apps/web/src/editor/markdown.ts`; keep the two in step.
- **SQLite** is the system library, which has FTS5 on iOS and macOS (a test
  checks). One schema file, `0001_init.sql`, starts where Android's
  migrations end.
- **The editor** keeps structure in attributes rather than spans: each
  block's characters and its "\n" carry one `BlockAttr`, and the text always
  ends with a "\n". Hard breaks are U+2028, which keeps TextKit's paragraph
  (one paragraph per block). Raw Markdown runs (code, tables, images) are
  attachments holding their source, so they're atomic and saved byte for
  byte. `BlockLayoutFragment` draws list markers, quote bars and tag pills.
- **Undo**: the platform undoes typing; formatting and other editor commands
  register a snapshot, so structure comes back with the text.

## Tests

- `NotesCore` uses Swift Testing on the Mac. Sync tests start a real notesd
  per suite (`TestServer`), built from `server/` or taken from
  `NOTESD_SERVER_BIN`, and drive clients whose network can drop requests or
  lose responses (`FaultyTransport`).
- `NotesTests` run in the app on a simulator: every shared fixture
  round-trips through the editor, plus the Enter, Backspace, formatting,
  link, tag and undo rules.
- `NotesUITests` launch the app with `--ui-testing` (a fresh database and
  Keychain item), sign in to the server `scripts/with-server.sh` starts, and
  run the accessibility audit on every screen. Screenshots stay in the test
  results.

## Not built yet

Images, locked notes (crypto, keyring, Face ID), email and iPad layouts. The
data layer already keeps what they need from sync (`locked_images`), and the
editor shows image references as read-only placeholders.
