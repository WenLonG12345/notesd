import NotesCore
import SwiftUI

/// All notes, Untagged, Locked and the tag tree with counts. Choosing one
/// filters the list and search; Settings opens from here.
struct TagsView: View {
    @Environment(AppGraph.self) private var graph
    @Binding var path: [Route]

    @State private var tags: [TagCount] = []
    @State private var lockedCount = 0
    @State private var open: Set<String> = Self.savedOpen

    var body: some View {
        List {
            Section {
                filterRow("All notes", systemImage: "note.text", filter: nil)
                filterRow("Untagged", systemImage: "number.square", filter: .untagged)
                if lockedCount > 0 {
                    filterRow("Locked", systemImage: "lock", filter: .locked, count: lockedCount)
                }
            }
            if !tags.isEmpty {
                Section {
                    ForEach(visibleTags, id: \.key) { tag in
                        tagRow(tag)
                    }
                } header: {
                    SectionHeader("Tags")
                }
            }
            Section {
                Button {
                    path.append(.settings)
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
                .foregroundStyle(Color.ink)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.notesBg)
        .navigationTitle("Tags")
        .task {
            await reload()
            for await _ in graph.store.changes() { await reload() }
        }
    }

    private func reload() async {
        tags = (try? await graph.store.tags()) ?? tags
        lockedCount = (try? await graph.store.lockedCount()) ?? 0
    }

    /// Tags whose every parent is open.
    private var visibleTags: [TagCount] {
        tags.filter { tag in
            let levels = tag.key.split(separator: "/")
            return (1..<max(levels.count, 1)).allSatisfy { open.contains(levels.prefix($0).joined(separator: "/")) }
        }
    }

    private func hasChildren(_ tag: TagCount) -> Bool {
        tags.contains { $0.key.hasPrefix(tag.key + "/") }
    }

    private func filterRow(_ title: String, systemImage: String, filter: NoteFilter?, count: Int? = nil) -> some View {
        Button {
            choose(filter)
        } label: {
            HStack {
                Label(title, systemImage: systemImage)
                Spacer()
                if let count { Text("\(count)").foregroundStyle(Color.ink3) }
                if graph.listFilter == filter { Image(systemName: "checkmark").foregroundStyle(Color.notesTint) }
            }
        }
        .foregroundStyle(Color.ink)
        .accessibilityAddTraits(graph.listFilter == filter ? .isSelected : [])
    }

    private func tagRow(_ tag: TagCount) -> some View {
        let depth = tag.key.split(separator: "/").count - 1
        let leaf = tag.name.split(separator: "/").last.map(String.init) ?? tag.name
        let filter = NoteFilter.tag(key: tag.key, name: tag.name)
        return HStack(spacing: 8) {
            if hasChildren(tag) {
                Button {
                    toggle(tag.key)
                } label: {
                    Image(systemName: open.contains(tag.key) ? "chevron.down" : "chevron.right")
                        .font(.system(size: 12, weight: .semibold))
                        .frame(width: 24, height: 24)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(open.contains(tag.key) ? "Collapse \(leaf)" : "Expand \(leaf)")
            } else {
                Color.clear.frame(width: 24, height: 24)
            }
            Button {
                choose(filter)
            } label: {
                HStack {
                    Text("#" + leaf).font(.notesBody)
                    Spacer()
                    Text("\(tag.count)").font(.notesSmall).foregroundStyle(Color.ink3)
                    if graph.listFilter == filter {
                        Image(systemName: "checkmark").foregroundStyle(Color.notesTint)
                    }
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(graph.listFilter == filter ? .isSelected : [])
        }
        .padding(.leading, CGFloat(depth) * 18)
        .foregroundStyle(Color.ink)
    }

    private func choose(_ filter: NoteFilter?) {
        graph.listFilter = filter
        if path.last == .tags { path.removeLast() }
    }

    private func toggle(_ key: String) {
        if open.contains(key) { open.remove(key) } else { open.insert(key) }
        UserDefaults.standard.set(Array(open), forKey: "openTags")
    }

    private static var savedOpen: Set<String> {
        Set(UserDefaults.standard.stringArray(forKey: "openTags") ?? [])
    }
}
