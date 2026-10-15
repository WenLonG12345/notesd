# The notesd web app

The web app comes with the server: open your notesd address (for example
`https://notes.example.com`) in any current browser and sign in. Unlike the
Android app, it needs a connection to the server. It keeps unsaved edits
through a brief outage, but nothing is stored in the browser once you close
it.

## Install it as an app

The web app can run in its own window, without browser tabs:

- **Safari (macOS):** File → Add to Dock.
- **Chrome / Edge:** the install icon at the right of the address bar.
- **iPhone / iPad:** Share → Add to Home Screen.

## Writing

- The first line of a note is its title; the rest is the body. Use the
  toolbar for bold, italic, strikethrough, headings, bullet, numbered and
  check lists, quotes and links.
- Shortcuts: ⌘B / ⌘I for bold and italic, ⌘K to add a link, ⌘⇧L for a
  checklist (Ctrl instead of ⌘ outside macOS).
- Notes save automatically about a second and a half after you stop typing.
  The status next to the note shows Unsaved, Saving, Saved, Couldn't save
  or Conflict. If the browser goes offline, saves retry by themselves, and
  the browser warns you before you close a tab with unsaved changes.
- Formatting the editor can't edit, such as code blocks or tables written
  elsewhere, is shown read-only and kept exactly as it was.

## Tags and search

- Write `#tag` anywhere in a note. Use `#multi word tag#` for names with
  spaces and `#parent/child` to nest them. Typing `#` and a letter suggests
  tags you already use.
- ⌘-click (Ctrl-click) a tag to see its notes. The tags sidebar (or
  "‹ Tags" on narrow windows) lists every tag with a count, plus All notes
  and Untagged.
- The search box searches titles and text as you type. Enter opens the
  first result, and Escape clears it.

## When a note changed somewhere else

If a note changes on another device while you're editing it:

- If the two edits touched different parts (title vs. text), they're
  merged and saved without asking.
- If both changed the text, saving pauses and you choose: **Use theirs**
  discards your edits; **Keep mine** saves your version as a separate note,
  "<Title> (Conflicted copy)".
- If the note was deleted elsewhere: **Discard** your edits, or **Keep as
  new note** ("<Title> (Recovered copy)").

Deleting a note shows "Note deleted · Undo" for a few seconds before it's
sent to the server.

## Sharing

The share button on a note offers **Email** (once email is set up),
your system's share sheet where the browser has one, and **Copy as
Markdown**.

## Settings

- **Appearance:** light, dark or follow the system.
- **Password:** changing it signs out every other browser and device.
- **Email:** your [Resend](https://resend.com) API key and sender address.
  They're stored on the server and shared with the Android app. The key is
  never shown again after saving, only its last four characters.
- **Devices:** every signed-in browser and phone, with a button to sign
  each one out.
- **Sign out** of this browser.

Browser sessions last 30 days from the last time you used them.
