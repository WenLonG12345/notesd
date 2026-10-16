# notesd on iPhone

The iPhone app keeps a full copy of your notes on the phone. Writing,
search and tags all work offline, and the app syncs with your notesd server
whenever it can. It runs on iOS 26 or later.

It does what the web and Android apps do, with two exceptions: locked
notes open with the lock password only (no Face ID yet), and there's no
iPad layout.

## Install

notesd isn't on the App Store. You build it with Xcode on a Mac and install
it on your iPhone with your Apple ID. A free Apple ID works.

1. Install Xcode from the Mac App Store, open it once and sign in with your
   Apple ID (Xcode → Settings → Accounts).
2. Get the code: `git clone https://github.com/cassiozen/notesd`.
3. In `apps/ios/Config`, copy `Local.xcconfig.example` to `Local.xcconfig`.
   Set `DEVELOPMENT_TEAM` to your team ID (Xcode → Settings → Accounts →
   your Apple ID) and `BUNDLE_ID_PREFIX` to something only you use, such as
   `com.yourname`.
4. Connect the iPhone with a cable, open `apps/ios/Notes.xcodeproj`, pick
   the iPhone at the top of the window and press Run (⌘R).
5. The first time, the iPhone asks you to trust the developer: Settings →
   General → VPN & Device Management → your Apple ID → Trust. It also asks
   you to turn on Developer Mode.

**With a free Apple ID, the app stops opening after 7 days.** Run it from
Xcode again to renew it. Your notes and sign-in are kept, as they are when
you install a newer version over it. Deleting the app deletes the notes on
the phone, so sync before you do.

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
`*.local`), so your password never crosses the internet unencrypted. For a
server on your network, iOS asks once whether notesd may find devices on
your local network. Allow it, or the app can't reach the server.

**Use without a server** skips this step: notes stay on the phone, and you
can connect later in Settings (‹ Tags → Settings). Notes you wrote before
connecting upload when you connect.

The app stays signed in as long as you use it at least every six months. If
the session ends (for example after you sign out that device from the web
app), your notes stay on the phone and the list offers to sign in again.

## Notes, tags and search

- The first line of a note is its title. Format text with the bar above the
  keyboard: bold, italic, strikethrough, links, headings, bullet, numbered
  and check lists, quotes, and indentation. With a hardware keyboard, ⌘B,
  ⌘I, ⇧⌘X, Tab and ⇧Tab work too, and ⇧Return starts a new line within a
  paragraph.
- Write `#tag` anywhere in a note to tag it. Use `#multi word tag#` for
  names with spaces and `#parent/child` to nest them. "Tags" (top left of
  the list) filters by tag. While you type a tag, the bar suggests ones you
  already use.
- Search covers titles and text, works offline, and finds words as you type
  them.
- Swipe a note left to delete it. Undo appears for a few seconds.

## Sync

The app syncs when it opens, a few seconds after you stop typing, when the
network comes back, and when you pull down on the list. iOS also lets it
sync now and then in the background, but on its own schedule. The list's
bottom bar says what's happening: Synced, Offline, or how many notes are
waiting to upload.

If the same note was changed on two devices before they synced, nothing is
lost. When both changed different parts (one the title, the other the
text), the changes are combined. When both changed the text, this phone's
version becomes a copy titled "(Conflicted copy)" that links back to the
original.

## Pictures

Add a picture with the photo button on the bar above the keyboard, or paste
one. It goes on a line of its own, below the line you're on. Pictures are
resized (at most 2048 pixels on the long side) and their location and other
metadata removed before they're stored. They upload with the next sync, so
you can add them offline too. Pictures from your other devices download the
first time you open their note, and stay on the phone for when you're
offline. Backspace after a picture removes it.

## Locked notes

A locked note is encrypted on the phone; the server can't read it. Its title
stays readable, so it shows in the list and in search by title.

- **Lock a note** from its ••• menu. The first time, on any of your devices,
  you create the lock password; every locked note uses it.
- **Open a locked note** by typing the lock password. Locked notes stay open
  until you leave the app, choose **Lock now**, or don't use the app for
  five minutes.
- **Remove the lock** from the ••• menu of an open locked note.
- **Settings → Locked notes** changes the lock password, or resets it if you
  forgot it. Resetting needs your account password, and notes locked with
  the old password can never be opened again.

While a locked note is open, the app covers it in the app switcher. iOS
doesn't let apps block screenshots, so the iPhone app can't do what Android
does there. Face ID unlock isn't there yet.

## Email

notesd can email a note through [Resend](https://resend.com). In
**Settings → Email**, paste a Resend API key with sending access and the
address emails come from (on a domain you verified with Resend). Then a
note's ••• menu has **Email…**. The server sends the note as it is after a
sync, as formatted HTML with the Markdown as plain text. Locked notes can't
be emailed.
