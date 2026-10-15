# notesd

Your own notes server. One small program with a SQLite database runs on
your server, a Raspberry Pi or a VPS. It serves a web app, and it syncs with an
Android app that works fully offline. There's no cloud account and nothing
else to run.

- **Android app** with its own copy of your notes: write, search and tag
  on a plane, and it syncs when you're back online.
- **Web app** built into the server: open it in any browser, or add it to
  your Dock.
- **Rich text that stays Markdown:** bold, headings, lists, checklists,
  links and quotes, stored as plain Markdown you can always take with you.
- **Full-text search** and `#tags`, including `#nested/tags`.
- **Edits are never lost:** concurrent edits merge when they can, and
  otherwise you get both versions.
- **Email a note** through your own [Resend](https://resend.com) account.

notesd is for one person on several devices. It has no sharing or
collaboration.

## Get started

1. **Host the server.** Download one binary, run it, and put HTTPS in front
   of it. See [docs/hosting.md](docs/hosting.md).
2. **Create your account.** Open the setup link the server prints on first
   start and choose a username and password.
3. **Install the Android app.** Download the APK from
   [Releases](https://github.com/cassiozen/notesd/releases) and connect it to
   your server. See [docs/android.md](docs/android.md).

## Platforms

| Platform | Status |
| --- | --- |
| Server | Linux x86-64 and ARM64 (including Raspberry Pi), macOS on Apple silicon |
| Web | Built into the server; current Chrome, Safari, Firefox and Edge |
| Android | Android 9 or later |
| iOS | Not started |

## Security

Your notes are stored unencrypted on your server, and the server can read
them (there's no end-to-end encryption). Use disk encryption if that
matters to you, and use HTTPS for anything reachable from the internet.
The Android app won't send your password over plain HTTP outside your
local network.

## Documentation

| Guide | For |
| --- | --- |
| [Hosting](docs/hosting.md) | Installing and configuring the server, HTTPS, systemd |
| [Android](docs/android.md) | Installing the app, connecting, how sync works |
| [Web](docs/web.md) | Using the web app |
| [Operations](docs/operations.md) | Upgrades, backups, maintenance, monitoring |
| [Development](docs/development.md) | Building from source and working on notesd |
| [Architecture](docs/architecture.md) | How it works: data model, sync protocol, conflicts |
