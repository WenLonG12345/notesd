<p align="center"><img src="apps/web/public/icon-512.png" alt="" width="96" height="96"></p>

<h2 align="center">notesd</h1>
<h3 align="center">Self-hosted notes</h1>

<p align="center">Single-binary, lightweight server (you can drop onto a Raspberry Pi or a cheap VPS).</p> 
<p align="center">Android app that works fully
offline and syncs your notes whenever a connection is available.</p>

<img width="1536" height="1024" alt="Image" src="https://github.com/user-attachments/assets/61603468-28eb-45aa-983a-609850966c15" />

## Features

- **Full-text Search** across titles and bodies. On Android it queries the local
database, so it works offline.

- **Organize with tags.** Add tags like `#work` and `#reading` anyewhere in your note. The sidebar lists all your tags, so you can browse by topic. Tags can be nested with `/` (`#home/garden`).

- **Locked notes.** You can secure notes with end-to-end encryption (AES-256-GCM). The server only stores ciphertext. Unlock with a password, a passkey (web) or biometric (Android).  
  See [Security](#security) for more details.

- **Markdown under the hood.** Notes are stored as Markdown, and you can take them out at any
time.  
  See [Your data](#your-data) for more details.

## Get started

1. **Host the server.** Download the binary for your machine, run it, and put
   HTTPS in front of it. See [docs/hosting.md](docs/hosting.md).
2. **Create your account.** Open the setup link the server prints on first
   start and choose a username and password.
3. **Install the Android app.** Download the APK from
   [Releases](https://github.com/cassiozen/notesd/releases) and connect it to
   your server. See [docs/android.md](docs/android.md).

## Your data

Everything the server knows lives in one SQLite file, using **WAL mode** and **FTS5**.  
SQLite runs inside the server process (a query is a function call instead of a network round trip). On an NVMe
drive a commit takes a fraction of a millisecond.


Notes are stored as Markdown, and you can export them with:

```bash
notesd export --out ~/notes-export
```

Locked notes are left out unless you add `--include-locked`. The command only reads the
database, so it's safe to run while the server is up.


## Security

Regular notes are stored unencrypted on your server. Use disk encryption if that
matters to you, and HTTPS for anything reachable from the internet.
The Android app won't send your password over plain HTTP outside your local network.

**Locked notes** have their body and images encrypted. Be aware that:

- The server still sees the titles, and locked notes can't be searched by their text or emailed.
- **Text from before locking.** When you lock a note, the server scrubs the
  previous text from its database. Backups made earlier still hold it.
- **Images.** A locked note's images are encrypted too. Their plain copies
  from before locking, like images you remove from any note, stay on the
  server for 30 days after the last note showing them stops, then they're
  deleted. A copy another note still shows stays.
- **Forgotten passwords.** If you forget the lock password and have no
  passkey or fingerprint set up, those notes can't be recovered.

The full threat model is in
[docs/architecture.md §10.1](docs/architecture.md#101-locked-notes).


## How it's built

| Part | Stack |
| --- | --- |
| Server | Go, `net/http`, SQLite with WAL and FTS5 |
| Web | React, TypeScript, Tiptap/ProseMirror, Vite; embedded in the server binary |
| Android | Kotlin, Jetpack Compose, a native `EditText` editor, bundled SQLite with FTS5, WorkManager |

A few things that may be interesting if you're reading the code:

- **One spec.** [docs/architecture.md](docs/architecture.md) is the
  specification: data model, sync protocol, conflict rules, Markdown dialect,
  search ranking and the locked-notes design. Changes to any of those start
  there.
- **Shared rules.** The three platforms implement the Markdown round trip,
  plain-text extraction, tag parsing and the locked-notes crypto separately.
  Fixtures in [`shared/`](shared) pin all four, and every platform's tests run
  against them, so the server, the web app and the phone agree.
- **Simple sync.** Each note has a revision, and the server keeps a global
  change sequence. Clients push edits against the revision they started
  from, and pull everything changed since a cursor. There are no CRDTs and
  no WebSockets.
- **No other services.** No Postgres, Redis, queue or search engine: search
  is SQLite's FTS5, and the whole state is one database file.

## Documentation

| Guide | For |
| --- | --- |
| [Hosting](docs/hosting.md) | Installing and configuring the server, HTTPS, systemd |
| [Android](docs/android.md) | Installing the app, connecting, how sync works |
| [Operations](docs/operations.md) | Upgrades, maintenance, monitoring |
| [Development](docs/development.md) | Building from source and working on notesd |
| [Architecture](docs/architecture.md) | How it works: data model, sync protocol, conflicts |
| [Server API](server/README.md) | The HTTP API reference |
