<p align="center"><img src="apps/web/public/icon-512.png" alt="notesd icon" width="128" height="128"></p>

# notesd

Self-hosted notes server + web and Android native app.

Server is a single binary + a SQLite database: runs on
a Raspberry Pi or a VPS.

The Android app is offline-first: Notes are synced with the server automatically, and whenever there is Internet available.


Other features: 

- **Rich text that stays Markdown:** bold, headings, lists, checklists,
  links and quotes, stored as plain Markdown.
- **Full-text search** and `#tags`, including `#nested/tags`.
- **Edits are never lost:** concurrent edits merge when they can, and
  otherwise you get both versions.


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
| [Operations](docs/operations.md) | Upgrades, maintenance, monitoring |
| [Development](docs/development.md) | Building from source and working on notesd |
| [Architecture](docs/architecture.md) | How it works: data model, sync protocol, conflicts |
