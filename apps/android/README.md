# Notes for Android

The native Android client: offline-first, with its own SQLite database and full-text index, a native rich-text editor, and background sync with notes-server. Design and protocol are in [`docs/architecture.md`](../../docs/architecture.md); the build plan is in [`docs/android-app-plan.md`](../../docs/android-app-plan.md).

## Setup

- [mise](https://mise.jdx.dev) installs the pinned JDK (21) and Go from the repo's `mise.toml`.
- The Android SDK with platform 37 and build tools. Point Gradle at it with `ANDROID_HOME` or `apps/android/local.properties` (`sdk.dir=…`).
- No emulator is needed for the tests; the app runs on any device or emulator with Android 9 (API 28) or later.

## Everyday tasks

Run from the repository root:

| Task | What it does |
| --- | --- |
| `mise run build:android` | Debug APK in `app/build/outputs/apk/debug` |
| `mise run test:android` | All JVM and Robolectric tests, including sync against a real server |
| `mise run lint:android` | ktlint and Android lint (warnings fail the build) |
| `mise run screenshots:android` | Renders every screen to `app/build/screenshots` (light, dark, 200% text, two-pane, foldable) |

Install on a connected device with `cd apps/android && mise exec -- ./gradlew :app:installDebug`.

## Running against a dev server

The app talks to notes-server over HTTPS, or plain HTTP to an address only reachable on your network (`10.x`, `172.16–31.x`, `192.168.x`, loopback, link-local, `localhost`, `*.local`); anything else must use HTTPS, so tokens never cross the internet in the clear.

- **Phone on your Wi-Fi:** `NOTES_LISTEN=0.0.0.0:8080 mise run dev:server`, then connect to `http://<your computer's LAN address>:8080`.
- **Emulator:** `mise run dev:server` and connect to `http://10.0.2.2:8080`, the emulator's alias for the host machine.

On first run, onboarding asks for the server, then either the setup token from the server's log (a new server) or a username and password. "Use without a server" skips it; you can connect later in Settings.

## How the tests run

- `:core` tests are plain JVM: the Markdown codec against `shared/markdown-fixtures`, the store, the API client against a mock server, and sync.
- Sync tests use the real server. Gradle builds `server/` with Go once (`buildNotesServer`); each test starts it on a free port with a temporary data directory, reads the address and setup token from its log, and drives two or more clients through every scenario in the architecture doc's §12.1. `RandomSyncTest` replays random edit, delete, offline and retry sequences; run more seeds with `./gradlew :core:test -Pnotes.seeds=1,2,3`.
- `:app` tests run on Robolectric: the editor's spans and behaviours, the screens (onboarding, list, editor, sync changes arriving in an open note), and an accessibility audit that checks every control on every screen has a label and a 48dp target.

## Layout

```text
apps/android/
├── core/   plain Kotlin, no Android: document model, Markdown codec, database and search,
│           API client, sync engine
└── app/    the Android app
    ├── editor/    the EditText-based editor: spans, normaliser, formatting bar
    ├── notes/     list, editor screen, navigation (one pane, or two from 600dp)
    ├── account/   onboarding, token storage sealed with an Android Keystore key
    ├── settings/  settings and appearance
    ├── sync/      foreground scheduling and WorkManager background sync
    └── ui/        design tokens, type, icons and shared components
```

## Design

The app follows the web app's design system, not Material defaults:

- **Colours:** `ui/Tokens.kt` holds the web's `tokens.css` values converted from oklch to sRGB, with the oklch source beside each value.
- **Typefaces:** Instrument Sans, Instrument Serif and JetBrains Mono, bundled so the app looks the same offline (SIL Open Font License; see `licenses/`).
- **Glass surfaces:** blurred on Android 12+ with [Haze](https://github.com/chrisbanes/haze), translucent on earlier versions.
- **Motion:** stops when the system's "Remove animations" is on.

To check a design change, run `mise run screenshots:android` and look at the PNGs.
