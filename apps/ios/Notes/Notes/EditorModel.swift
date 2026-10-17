import Foundation
import NotesCore
import Observation

enum SaveStatus { case blank, unsaved, saved }

/// The note a conflicted copy was split from.
struct Original: Equatable {
    var id: String
    var title: String
}

/// One open note (a port of Android's EditorViewModel). Saves outlive the
/// screen, so a save started as it closes still lands; they're serialised, so
/// an older text never overwrites a newer one. A note that doesn't exist yet
/// is created by its first non-blank save.
///
/// A new note can start with a tag (`startTag`); with nothing more typed it
/// still counts as blank.
///
/// Sync can change the note while it's open. With no unsaved typing, the change
/// goes straight into the editor (`incoming`). Otherwise the next save passes
/// the stored version the typing grew from, and the store merges the two.
///
/// A locked note (§10.1) is stored encrypted. Its text is in memory only while
/// `locks` is unlocked: `document` is nil otherwise, and `needsUnlock` says
/// the screen should ask for the lock password. Unchanged text is saved as the
/// envelope it was stored as, never encrypted again.
@MainActor
@Observable
final class EditorModel {
    let id: String
    private let store: NoteStore
    private let images: ImageStore?
    private let locks: LockSession?

    /// The text to show; nil while loading, and while a locked note is locked.
    private(set) var document: Document?
    /// Text that changed underneath the editor (sync, or a merge on save): show it, keeping the cursor.
    private(set) var incoming: (document: Document, version: Int)?
    private(set) var status: SaveStatus = .blank
    private(set) var original: Original?
    /// The note was deleted elsewhere; any unsaved typing was kept as a recovered copy.
    private(set) var gone = false
    /// Its body is encrypted (or is about to be, once saved).
    private(set) var locked = false
    /// Locked with a key the keyring no longer has (it was reset), or it won't open: it can't be read.
    private(set) var unreadable = false
    private(set) var lockState: LockSession.State = .none
    private(set) var title = ""
    /// Every tag, for completing one being typed.
    private(set) var tags: [TagCount] = []
    /// The store had no such note when it was opened: a new note.
    private(set) var isNew = false
    /// A picture couldn't be added, or a lock change couldn't move the pictures: shown until dismissed.
    var imageProblem: String?
    /// The editor's text as it is now, for saving before locked notes lock.
    @ObservationIgnored var currentDocument: (() -> Document?)?

    /// A locked note while locked notes are locked: ask for the password instead of showing the text.
    var needsUnlock: Bool { locked && lockState != .unlocked }

    /// The note has been read (or is new) and is showing.
    private var ready = false
    private var lastSave: Task<Void, Never>?
    private var edits = 0
    private var saved = 0
    private var saving = 0
    private var incomingVersion = 0
    /// The stored title and content the editor's text grew from; nil until the note exists.
    private var known: (title: String, content: String)?
    /// The plain text the stored envelope holds: unchanged text is saved as the same envelope.
    private var sealed: (plain: String, envelope: String)?
    /// A locked note's edit that came too late to encrypt; saved on the next unlock.
    private var stranded: Document?
    /// What a new note starts with; unchanged, it isn't stored.
    private let blankContent: String
    @ObservationIgnored private var watcher: Task<Void, Never>?
    @ObservationIgnored private var unhook: (@Sendable () -> Void)?

    /// `initial`: the note as already read, so the screen opens with its text;
    /// `fresh`: a new note, which shows at once without looking in the store.
    init(
        store: NoteStore, images: ImageStore? = nil, locks: LockSession? = nil, id: String, startTag: String? = nil,
        initial: Note? = nil, fresh: Bool = false
    ) {
        self.store = store
        self.images = images
        self.locks = locks
        self.id = id
        blankContent = startTag.map { tagText($0) + "\n" } ?? ""
        lockState = locks?.state.value ?? .none
        if let initial {
            apply(initial)
        } else if fresh {
            apply(nil)
        }
        watcher = Task { [weak self] in await self?.load() }
        // What's typed is saved (encrypted) before the key goes; a save that fails keeps the key.
        unhook = locks?.onBeforeLock { [weak self] in await self?.saveBeforeLock() ?? true }
    }

    deinit {
        MainActor.assumeIsolated {
            watcher?.cancel()
            unhook?()
        }
    }

    private func load() async {
        // Subscribed before the first read, so a change landing in between isn't missed.
        let changes = store.changes()
        if ready {
            // Opened with the note already read: catch up with anything since.
            await refresh()
        } else {
            apply(try? await store.get(id))
        }
        tags = (try? await store.tags()) ?? []
        if let locks {
            let states = locks.state.stream()
            Task { [weak self] in
                for await state in states {
                    guard let self else { return }
                    self.lockChanged(state)
                }
            }
        }
        for await _ in changes {
            await refresh()
            tags = (try? await store.tags()) ?? tags
        }
    }

    /// Shows `note` as opened; nil for a new note.
    private func apply(_ note: Note?) {
        ready = true
        isNew = note == nil
        known = note.map { ($0.title, $0.content) }
        locked = note?.locked ?? false
        title = note?.title ?? ""
        if let note {
            document = parse(note)
            show(note)
        } else {
            document = Document.forNote(title: "", body: blankContent.isEmpty ? .empty : Markdown.parse(blankContent))
        }
    }

    private func lockChanged(_ state: LockSession.State) {
        lockState = state
        guard locked else { return }
        if state == .unlocked {
            if document == nil, let known {
                document = parse(title: known.title, content: known.content)
            }
            if let doc = stranded {
                stranded = nil
                edited()
                save(doc)
            }
        } else {
            // The plain text leaves memory; the screen shows the unlock prompt, or,
            // with no lock password left (a reset), that the note can't be opened.
            document = nil
            sealed = nil
            unreadable = state == .none
        }
    }

    func edited() {
        edits += 1
        status = .unsaved
        locks?.touch()
    }

    /// Locks or unlocks the note: saved like an edit. Locking needs the session
    /// unlocked (the screen asks for the password or creates one first).
    func setLocked(_ doc: Document, _ on: Bool) {
        if locked == on { return }
        locked = on
        sealed = nil
        edited()
        save(doc)
    }

    /// Stores `doc` if anything changed since the last save. Call with the editor's current document.
    func save(_ doc: Document) {
        if edits == saved { return }
        let edit = edits
        saved = edit
        saving += 1
        document = doc
        let previous = lastSave
        lastSave = Task {
            await previous?.value
            await self.store(doc, edit)
            self.saving -= 1
        }
    }

    /// Waits for the saves started so far; they run in order, so the last one is enough.
    func flushed() async {
        await lastSave?.value
    }

    private func saveBeforeLock() async -> Bool {
        if !needsUnlock, let doc = currentDocument?() { save(doc) }
        await flushed()
        return stranded == nil
    }

    private func store(_ doc: Document, _ edit: Int) async {
        let title = doc.title ?? ""
        let plain = Markdown.serialize(doc)
        let basedOn = known
        if basedOn == nil && isBlank(title) && plain.trimmed == blankContent.trimmed {
            if edit == edits { status = .blank }
            return
        }
        let content: String
        if !locked {
            content = plain
        } else if let key = locks?.key() {
            content = sealed.flatMap { $0.plain == plain ? $0.envelope : nil } ?? LockedNotes.encrypt(key, plain)
        } else {
            // Locked before this save could encrypt: it waits for the next unlock.
            stranded = doc
            return
        }
        // The server can't read a locked body's images: the save lists them (§7.6).
        // From the body being saved, not the lock flag, which may have changed while it encrypted.
        let imageIds = LockedNotes.isLocked(content) ? ImageRef.ids(plain) : nil
        let note: Note?
        do {
            note = try await store.save(id, title: title, content: content, basedOn: basedOn, imageIds: imageIds)
        } catch {
            return
        }
        guard let note else {
            if let basedOn, !(basedOn.title == title && basedOn.content == content) {
                // Deleted elsewhere while typed into here: keep the typing.
                _ = try? await store.recover(title: title, content: content, imageIds: imageIds)
                gone = true
            } else if edit == edits {
                status = .blank
            }
            return
        }
        self.title = note.title
        if locked && note.content == content { sealed = (plain, content) }
        if !(note.title == title && note.content == content) && edit == edits {
            // Merged with a sync change: the editor shows the result.
            known = (note.title, note.content)
            locked = note.locked
            // Nil while locked: unlocking then loads the stored body, not the text from before.
            let doc = parse(note)
            document = doc
            if let doc { push(doc) }
        } else {
            // If typing continued, the next save merges from what this one sent.
            known = (title, content)
        }
        if edit == edits { show(note) }
    }

    private func refresh() async {
        if edits != saved || saving > 0 { return }
        guard let note = try? await store.get(id) else { return }
        if note.deletedAt != nil {
            gone = true
            return
        }
        title = note.title
        if let before = known, !(before.title == note.title && before.content == note.content) {
            known = (note.title, note.content)
            locked = note.locked
            let doc = parse(note)
            document = doc
            if let doc { push(doc) }
        }
        show(note)
    }

    private func push(_ doc: Document) {
        incomingVersion += 1
        incoming = (doc, incomingVersion)
    }

    private func show(_ note: Note) {
        if edits == saved { status = .saved }
        guard let originalId = note.conflictOf, original?.id != originalId else { return }
        Task {
            if let o = try? await store.get(originalId), o.deletedAt == nil {
                original = Original(id: o.id, title: o.title)
            }
        }
    }

    private func parse(_ note: Note) -> Document? { parse(title: note.title, content: note.content) }

    /// The note as the editor shows it; nil for a locked note while locked notes are locked.
    private func parse(title: String, content: String) -> Document? {
        guard LockedNotes.isLocked(content) else {
            unreadable = false
            return Document.forNote(title: title, body: Markdown.parse(content))
        }
        guard let key = locks?.key() else {
            // No lock password at all (reset): nothing can open it. Otherwise the screen asks to unlock.
            unreadable = locks?.state.value == LockSession.State.none
            return nil
        }
        guard let plain = try? LockedNotes.decrypt(key, content) else {
            unreadable = true
            return nil
        }
        sealed = (plain, content)
        unreadable = false
        return Document.forNote(title: title, body: Markdown.parse(plain))
    }

    /// Prepares and stores a picked or pasted picture (encrypted if the note is
    /// locked); its Markdown to insert, or nil (with `imageProblem` set) if it couldn't be used.
    func addImage(_ data: Data) async -> String? {
        guard let images else { return nil }
        do {
            let prepared = try await Task.detached(priority: .userInitiated) { try PrepareImage.prepare(data) }.value
            var bytes = prepared.bytes
            var type = prepared.type
            if locked {
                guard let key = locks?.key() else {
                    imageProblem = "Unlock locked notes to add pictures to this note."
                    return nil
                }
                bytes = LockedNotes.encryptImage(key, bytes)
                type = lockedImageType
            }
            let id = try await images.add(bytes, type: type)
            return ImageRef(id: id, width: prepared.width, height: prepared.height).markdown()
        } catch let error as ImageError {
            imageProblem = error.message
        } catch {
            imageProblem = "That picture couldn’t be saved on this iPhone."
        }
        return nil
    }

    /// Before a lock change: stores the pictures with ids `ids` again,
    /// encrypted for `locked` or plain without; old id → new id. Nil, with
    /// `imageProblem` set, when one isn't on this phone and can't be
    /// downloaded: the lock stays as it is.
    func moveImages(_ ids: Set<String>, locked: Bool) async -> [String: String]? {
        guard let images, !ids.isEmpty else { return [:] }
        guard let key = locks?.key(), let moved = await images.move(ids, locked: locked, key: key) else {
            imageProblem =
                "A picture in this note couldn’t be moved with the lock. If it isn’t on this iPhone yet, connect and try again."
            return nil
        }
        return moved
    }

    func delete() async {
        await flushed()
        try? await store.delete(id)
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

/// Empty or only whitespace.
func isBlank(_ s: String) -> Bool { s.trimmed.isEmpty }
