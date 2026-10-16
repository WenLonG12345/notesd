import NotesCore
import SwiftUI

/// Where the stack can go from the list.
enum Route: Hashable {
    /// A note by id; a new note gets its id up front and exists once it has text.
    case note(id: String, startTag: String? = nil, isNew: Bool = false)
    case tags
    case settings
}

/// The notes, newest first, in date sections; search as you type; the
/// filter chosen under Tags. Deleting is undoable for a few seconds.
struct NotesListView: View {
    @Environment(AppGraph.self) private var graph
    @Binding var path: [Route]

    @State private var notes: [NoteSummary] = []
    @State private var hits: [SearchHit]?
    @State private var query = ""
    @State private var pendingDeletes: [String: Task<Void, Never>] = [:]
    @State private var pendingNotes = 0

    var body: some View {
        List {
            if let notice = graph.notice {
                Notice(text: notice) { graph.notice = nil }
                    .listRowSeparator(.hidden)
            }
            if case .signedOut = graph.account {
                SignedOutBanner { path.append(.settings) }
                    .listRowSeparator(.hidden)
            }
            if let hits {
                if hits.isEmpty {
                    Text("No notes match “\(query)”.").font(.notesBody).foregroundStyle(Color.ink3)
                        .listRowBackground(Color.clear)
                }
                ForEach(hits) { hit in
                    NavigationLink(value: Route.note(id: hit.id)) { SearchRow(hit: hit) }
                        .listRowBackground(Color.clear)
                }
            } else {
                let visible = notes.filter { pendingDeletes[$0.id] == nil }
                if visible.isEmpty {
                    EmptyList(filtered: graph.listFilter != nil)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
                ForEach(groupByDate(visible, updatedAt: \.updatedAt), id: \.label) { group in
                    Section {
                        ForEach(group.items) { note in
                            NavigationLink(value: Route.note(id: note.id)) { NoteRow(note: note) }
                                .listRowBackground(Color.clear)
                                .swipeActions {
                                    Button("Delete", systemImage: "trash", role: .destructive) { delete(note) }
                                }
                        }
                    } header: {
                        Text(group.label).font(.notesLabel).foregroundStyle(Color.ink3).textCase(nil)
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(Color.notesBg)
        .navigationTitle(title)
        .toolbarTitleDisplayMode(.inlineLarge)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search")
        .refreshable { _ = await graph.engine.sync() }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    path.append(.tags)
                } label: {
                    Label("Tags", systemImage: "sidebar.left")
                }
            }
            ToolbarItem(placement: .bottomBar) { SyncLabel(status: graph.syncStatus, pending: pendingNotes) }
                .sharedBackgroundVisibility(.hidden)
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button {
                    newNote()
                } label: {
                    Label("New note", systemImage: "square.and.pencil")
                }
                .keyboardShortcut("n", modifiers: .command)
            }
        }
        .safeAreaInset(edge: .bottom) { undoBar }
        .task(id: graph.listFilter) { await watch() }
        .task(id: query) { await search() }
        .onDisappear { commitDeletes() }
    }

    private var title: String {
        switch graph.listFilter {
        case nil: "Notes"
        case .untagged: "Untagged"
        case .locked: "Locked"
        case .tag(_, let name): "#" + name
        }
    }

    @ViewBuilder private var undoBar: some View {
        if !pendingDeletes.isEmpty {
            HStack {
                Text(pendingDeletes.count == 1 ? "Note deleted" : "\(pendingDeletes.count) notes deleted")
                    .font(.notesBody)
                Spacer()
                Button("Undo") { undoDeletes() }.font(.notesButton)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .glassEffect(.regular, in: .capsule)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private func watch() async {
        // Subscribed before the first read, so a change landing in between isn't missed.
        let changes = graph.store.changes()
        await reload()
        for await _ in changes { await reload() }
    }

    private func reload() async {
        notes = (try? await graph.store.list(graph.listFilter)) ?? notes
        pendingNotes = (try? await graph.store.pendingNotes()) ?? 0
        if !query.isEmpty { hits = try? await graph.store.search(query, filter: graph.listFilter) }
    }

    private func search() async {
        if query.trimmed.isEmpty {
            hits = nil
            return
        }
        try? await Task.sleep(for: .milliseconds(120))
        if Task.isCancelled { return }
        hits = (try? await graph.store.search(query, filter: graph.listFilter)) ?? []
    }

    private func newNote() {
        var tag: String?
        if case .tag(_, let name) = graph.listFilter { tag = name }
        path.append(.note(id: Uuid7.generate(), startTag: tag, isNew: true))
    }

    /// The note is only hidden until the undo window ends; deleting at once and
    /// restoring on undo would race sync, which could send the delete first.
    private func delete(_ note: NoteSummary) {
        let store = graph.store
        withAnimation {
            pendingDeletes[note.id] = Task {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled else { return }
                try? await store.delete(note.id)
                pendingDeletes[note.id] = nil
            }
        }
    }

    private func undoDeletes() {
        withAnimation {
            for task in pendingDeletes.values { task.cancel() }
            pendingDeletes = [:]
        }
    }

    private func commitDeletes() {
        let store = graph.store
        for (id, task) in pendingDeletes {
            task.cancel()
            Task { try? await store.delete(id) }
        }
        pendingDeletes = [:]
    }
}

private struct NoteRow: View {
    let note: NoteSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(note.title.isEmpty ? "Untitled" : note.title)
                .font(.notesRowTitle)
                .foregroundStyle(note.title.isEmpty ? Color.ink3 : Color.ink)
                .lineLimit(1)
            HStack(spacing: 6) {
                Text(listTime(note.updatedAt)).foregroundStyle(Color.ink2)
                if note.locked {
                    Label("Locked", systemImage: "lock.fill").labelStyle(.iconOnly).foregroundStyle(Color.ink3)
                } else {
                    Text(note.preview.split(separator: "\n").first.map(String.init) ?? "No additional text")
                        .foregroundStyle(Color.ink3)
                        .lineLimit(1)
                }
            }
            .font(.notesRowMeta)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

private struct SearchRow: View {
    let hit: SearchHit

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(hit.title.isEmpty ? "Untitled" : hit.title).font(.notesRowTitle).foregroundStyle(Color.ink).lineLimit(
                1)
            if !hit.excerpt.isEmpty {
                Text(highlighted(hit.excerpt)).font(.notesRowMeta).foregroundStyle(Color.ink2).lineLimit(2)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    /// The excerpt with its matches marked, as the web's <mark>.
    private func highlighted(_ excerpt: String) -> AttributedString {
        var out = AttributedString()
        var marked = false
        for piece in excerpt.split(
            omittingEmptySubsequences: false, whereSeparator: { $0 == "\u{E000}" || $0 == "\u{E001}" })
        {
            var run = AttributedString(String(piece))
            if marked {
                run.backgroundColor = Color.accent.opacity(0.35)
                run.foregroundColor = Color.ink
            }
            out += run
            marked.toggle()
        }
        return out
    }
}

private struct EmptyList: View {
    let filtered: Bool

    var body: some View {
        VStack(spacing: 8) {
            Text(filtered ? "No notes here" : "No notes yet").font(.notesTitle).foregroundStyle(Color.ink)
            Text(filtered ? "Notes with this filter show up here." : "Tap the pencil to write your first note.")
                .font(.notesBody).foregroundStyle(Color.ink3)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 60)
    }
}

/// What sync is doing, in a word or two.
struct SyncLabel: View {
    let status: SyncStatus
    let pending: Int

    var body: some View {
        // In a bar, where text doesn't grow: long-press shows it large instead.
        Text(text).font(.notesSmall).foregroundStyle(Color.ink2)
            .accessibilityShowsLargeContentViewer()
    }

    private var text: String {
        switch status {
        case .notConnected: "On this iPhone only"
        case .syncing: "Syncing…"
        case .synced: pending > 0 ? "\(pending) not synced" : "Synced"
        case .offline: pending > 0 ? "Offline · \(pending) not synced" : "Offline"
        case .signedOut: "Signed out"
        case .failed: "Sync problem"
        }
    }
}

struct Notice: View {
    let text: String
    let dismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text(text).font(.notesSmall).foregroundStyle(Color.warnInk)
            Spacer(minLength: 0)
            Button("Dismiss", systemImage: "xmark", action: dismiss).labelStyle(.iconOnly).foregroundStyle(
                Color.warnInk)
        }
        .padding(12)
        .background(Color.warnBg, in: .rect(cornerRadius: 12))
    }
}

private struct SignedOutBanner: View {
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack {
                Text("Signed out. Notes stay on this iPhone; sign in to sync them.").font(.notesSmall)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
            }
            .foregroundStyle(Color.warnInk)
            .padding(12)
            .background(Color.warnBg, in: .rect(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}
