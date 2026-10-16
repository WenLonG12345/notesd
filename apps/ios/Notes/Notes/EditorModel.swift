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
/// A locked note (§10.1) can't be opened in this version: it shows its title
/// and nothing can be edited, so this device never writes over its envelope.
@MainActor
@Observable
final class EditorModel {
    let id: String
    private let store: NoteStore

    /// The text to show, nil while loading.
    private(set) var document: Document?
    /// Text that changed underneath the editor (sync, or a merge on save): show it, keeping the cursor.
    private(set) var incoming: (document: Document, version: Int)?
    private(set) var status: SaveStatus = .blank
    private(set) var original: Original?
    /// The note was deleted elsewhere; any unsaved typing was kept as a recovered copy.
    private(set) var gone = false
    /// Its body is encrypted: shown read-only, without the text.
    private(set) var locked = false
    private(set) var title = ""
    /// Every tag, for completing one being typed.
    private(set) var tags: [TagCount] = []
    /// The store had no such note when it was opened: a new note.
    private(set) var isNew = false

    private var lastSave: Task<Void, Never>?
    private var edits = 0
    private var saved = 0
    private var saving = 0
    private var incomingVersion = 0
    /// The stored title and content the editor's text grew from; nil until the note exists.
    private var known: (title: String, content: String)?
    /// What a new note starts with; unchanged, it isn't stored.
    private let blankContent: String
    private var watcher: Task<Void, Never>?

    init(store: NoteStore, id: String, startTag: String? = nil) {
        self.store = store
        self.id = id
        blankContent = startTag.map { tagText($0) + "\n" } ?? ""
        watcher = Task { [weak self] in await self?.load() }
    }

    deinit {
        MainActor.assumeIsolated { watcher?.cancel() }
    }

    private func load() async {
        // Subscribed before the first read, so a change landing in between isn't missed.
        let changes = store.changes()
        let note = try? await store.get(id)
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
        tags = (try? await store.tags()) ?? []
        for await _ in changes {
            await refresh()
            tags = (try? await store.tags()) ?? tags
        }
    }

    func edited() {
        edits += 1
        status = .unsaved
    }

    /// Stores `doc` if anything changed since the last save. Call with the editor's current document.
    func save(_ doc: Document) {
        if edits == saved || locked { return }
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

    private func store(_ doc: Document, _ edit: Int) async {
        let title = doc.title ?? ""
        let content = Markdown.serialize(doc)
        let basedOn = known
        if basedOn == nil && isBlank(title) && content.trimmed == blankContent.trimmed {
            if edit == edits { status = .blank }
            return
        }
        let note: Note?
        do {
            note = try await store.save(id, title: title, content: content, basedOn: basedOn)
        } catch {
            return
        }
        guard let note else {
            if let basedOn, !(basedOn.title == title && basedOn.content == content) {
                // Deleted elsewhere while typed into here: keep the typing.
                _ = try? await store.recover(title: title, content: content)
                gone = true
            } else if edit == edits {
                status = .blank
            }
            return
        }
        self.title = note.title
        let typed = (title: title, content: content)
        if !(note.title == title && note.content == content) && edit == edits {
            // Merged with a sync change: the editor shows the result.
            known = (note.title, note.content)
            locked = note.locked
            if let doc = parse(note) { push(doc) }
        } else {
            // If typing continued, the next save merges from what this one sent.
            known = typed
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
            if let doc = parse(note) { push(doc) }
        }
        show(note)
    }

    private func push(_ doc: Document) {
        document = doc
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

    /// The note as the editor shows it; nil for a locked note, which this version can't open.
    private func parse(_ note: Note) -> Document? {
        if note.locked { return nil }
        return Document.forNote(title: note.title, body: Markdown.parse(note.content))
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
