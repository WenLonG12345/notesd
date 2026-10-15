# Developing notesd

A monorepo: the Go server, the web app it embeds, the Android app, and
fixtures they share. How it all fits together is in
[architecture.md](architecture.md); each component's README covers its
internals.

```text
server/         Go backend: API, sync, search, embedded web app  → server/README.md
apps/web/       React + Tiptap web app, built into the server     → apps/web/README.md
apps/android/   Kotlin app: core/ (plain Kotlin) and app/ (UI)    → apps/android/README.md
apps/ios/       not started
shared/         fixtures every platform is tested against (Markdown → text, tags, round trips)
design-system/  the visual prototype the web and Android apps follow
scripts/        dev helpers
mise.toml       pinned toolchain and every task below
```

## Toolchain

Everything is pinned in `mise.toml` and run with [mise](https://mise.jdx.dev)
(`brew install mise`): Go, Node, pnpm, watchexec, golangci-lint and the JDK.

```bash
mise install
```

For Android you also need the Android SDK with platform 37 and its build
tools (the easiest way is Android Studio). `mise.toml` points `ANDROID_HOME`
at Android Studio's default location on macOS; set `ANDROID_HOME` or
`apps/android/local.properties` (`sdk.dir=…`) if yours is elsewhere.

The repo root is a pnpm workspace (`apps/web` for now), with one lockfile.

## Server and web app

```bash
mise run dev        # Go server (rebuilt on change) + Vite: http://localhost:5173
mise run check      # lint, typecheck and test, Go and web
mise run build      # server/bin/notesd with the web app embedded
```

`mise run dev` keeps its database in `server/data`. On a fresh data dir it
creates the owner `dev` / `devpassword`, so there's no onboarding;
`mise run dev:reset` deletes it. `NOTESD_DATA_DIR`, `NOTESD_LISTEN` and
`NOTESD_SERVER` (Vite's proxy target) override the defaults, for example to
run a second copy next to the first.

To try the real first-run onboarding, run the server by hand on an empty
data dir (`go run ./cmd/notesd --data-dir /tmp/empty` in `server/`); it logs
a setup link.

## Android

```bash
mise run build:android        # debug APK in apps/android/app/build/outputs/apk/debug
mise run test:android         # JVM and Robolectric tests, including sync against a real server
mise run lint:android         # ktlint and Android lint
mise run screenshots:android  # every screen rendered to apps/android/app/build/screenshots
```

Install on a connected device with
`cd apps/android && mise exec -- ./gradlew :app:installDebug`.

To point the app at your dev server:

- **Phone on your Wi-Fi:** `NOTESD_LISTEN=0.0.0.0:8080 mise run dev:server`,
  then connect to `http://<your computer's LAN address>:8080`.
- **Emulator:** `mise run dev:server` and connect to `http://10.0.2.2:8080`,
  the emulator's address for the host.

Debug builds have the same application ID as release builds but a
different signing key, so they can't be installed over each other:
uninstall one before installing the other.

## All tasks

| Task | What it does |
| --- | --- |
| `dev` | `dev:server` + `dev:web` |
| `dev:server` | Go server on :8080, rebuilt and restarted on `.go`/`.sql` changes |
| `dev:web` | Vite dev server, proxying the API to :8080 |
| `dev:reset` | Delete the dev database |
| `check` | `lint`, `typecheck` and `test` (Go and web) |
| `lint:go`, `lint:web`, `typecheck` | One check at a time |
| `test:go`, `test:web` | Go tests (with the race detector), Vitest |
| `test:android`, `lint:android`, `screenshots:android` | Android checks |
| `build:web` | Web app into `server/internal/web/dist` |
| `build` | `server/bin/notesd` with the web app embedded |
| `build:android` | Debug APK |
| `release` | Server binaries for linux/amd64, linux/arm64 and darwin/arm64 in `server/bin` |
| `release:android` | Release APK in `server/bin` ([releasing.md](releasing.md)) |
| `clean` | Remove `server/bin` |

CI (`.github/workflows/ci.yml`) runs `check` and `test:android` on every
push to `main` and every pull request.

## Conventions

- `docs/architecture.md` is the specification. Change it along with any
  change to the protocol, data model or document format.
- `server/README.md` is the authoritative API reference.
- Rules every platform must agree on (plain-text extraction, tags, Markdown
  round trips) are pinned by fixtures in `shared/markdown-fixtures/`, and
  each platform's tests run against them. Change the fixture first.
