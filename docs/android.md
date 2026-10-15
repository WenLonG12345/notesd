# notesd on Android

The Android app keeps a full copy of your notes on the phone. Writing,
search and tags all work offline, and the app syncs with your notesd server
whenever it can. It runs on Android 9 or later, on phones, tablets and
foldables.

## Install

notesd isn't on the Play Store. Install the APK from GitHub:

1. On the phone, open the
   [latest release](https://github.com/cassiozen/notesd/releases/latest) and
   download `notesd-<version>.apk`.
2. Open the download. If Android asks, allow your browser (or file manager)
   to install unknown apps, then tap **Install**.

**Updates:** download the newer APK and install it over the existing app;
your notes and sign-in are kept. Every release is signed with the same key,
so Android accepts it as an update. An APK you built yourself is signed
with a different key, so to switch between the two you have to uninstall
first, which deletes the notes on the phone. Sync before you uninstall.

To build the app yourself, see [development.md](development.md#android).

## Connect to your server

On first launch, the app asks for your server:

1. Enter the **server address**, for example `notes.example.com` or
   `http://192.168.1.20:8080`.
2. Then either:
   - **Sign in** with your username and password, or
   - on a **new server** that has no account yet, paste the **setup token**
     from the server log and create the account from the phone (see
     [hosting.md](hosting.md#3-create-your-account)).

The app only connects over **HTTPS**, or over plain HTTP to an address on
your own network (`192.168.x`, `10.x`, `172.16–31.x`, `localhost`,
`*.local`), so your password never crosses the internet unencrypted.

**Use without a server** skips this step: notes stay on the phone, and you
can connect later in Settings. Notes you wrote before connecting upload
when you connect.

The app stays signed in for as long as you use it at least every six
months. If the session ends (for example after you sign out that device
from the web app), your notes stay on the phone and the app asks you to
sign in again. Nothing is lost.

## How sync works

You never need to press a sync button. The app syncs when it opens, when
it comes back to the foreground, shortly after you stop typing, when the
network comes back, and every so often in the background (whenever Android
allows it). Only changed notes are transferred.

Edits on two devices at the same time are never silently lost:

- **Different parts changed** (say, the title on your phone and the text on
  the web): both changes are merged automatically.
- **The same text changed on both:** the server keeps the other device's
  version, and your edit becomes a new note titled
  "<Title> (Conflicted copy)". A banner on the copy links to the original,
  so you can combine them by hand.
- **Edited here, deleted elsewhere:** your edit becomes a new note titled
  "<Title> (Recovered copy)".
- **Deleted here, edited elsewhere:** the note comes back with the newer
  text, and the app tells you it was changed elsewhere.

## Notes, tags and search

- The first line of a note is its title. Format text with the bar above the
  keyboard: bold, italic, strikethrough, headings, bullet, numbered and
  check lists, quotes and links.
- Write `#tag` anywhere in a note to tag it. Use `#multi word tag#` for
  names with spaces and `#parent/child` to nest them. "‹ Tags" above the
  list (or the sidebar, on wide screens) filters by tag. While you type a
  tag, the app suggests ones you already use.
- Search covers titles and text, works offline, and finds words as you
  type them.
- Formatting the app can't edit, such as code blocks or tables written
  elsewhere, is shown read-only and kept exactly as it was.

## Email a note

With email set up (Settings → Email: your [Resend](https://resend.com) API
key and sender address, shared with the web app), share a note and choose
**Email**. The app syncs first, so the email matches what's on screen.
Sending needs a connection.

## Settings

- **Appearance:** light, dark or follow the system.
- **Server:** the connected server and account, and signing out; or
  connecting to a server if you started without one.
- **Email:** the Resend key and sender (when connected).
- **Data → Remove all data from this device:** deletes every note on this
  phone and signs you out. Notes on the server aren't touched, but notes
  that haven't synced yet are lost.

## Troubleshooting

| Problem | What to do |
| --- | --- |
| "There's no notes server at that address" | Check the address in a browser on the phone; you should see the notesd web app. |
| "Can't reach the server" | Check the phone's connection. For a home server, make sure the phone is on the same Wi-Fi or VPN. |
| An `http://` address is refused | The app only allows HTTP to private addresses. Put HTTPS in front of the server ([hosting.md](hosting.md#4-https-with-a-reverse-proxy)). |
| A certificate error | The app uses the system's trusted certificates. Use a real certificate (Let's Encrypt via Caddy or certbot, or Tailscale), not a self-signed one. |
| "Too many attempts" | Ten failed sign-ins in 15 minutes pause sign-in; wait and try again. |
| "Session ended" | Sign in again. Notes edited in the meantime upload afterwards. |
| Changes don't appear on other devices | Open the app so it syncs; background sync can be delayed by battery saver. |
