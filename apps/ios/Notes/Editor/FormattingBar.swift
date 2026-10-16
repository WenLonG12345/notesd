import NotesCore
import SwiftUI

/// The bar above the keyboard: marks, headings, lists, quote, link and
/// indentation, or the tags that complete the one being typed. With text
/// selected it shows marks only; in the title, nothing applies.
struct FormattingBar: View {
    let controller: EditorController
    let tags: [TagCount]

    private var state: FormatState { controller.format }

    var body: some View {
        Group {
            if let tag = state.tag, !suggestions(tag).isEmpty {
                tagStrip(tag)
            } else {
                buttons
            }
        }
        .frame(height: 44)
        .padding(.horizontal, 8)
        .glassEffect(.regular, in: .capsule)
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    private var buttons: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 2) {
                button("bold", "Bold", on: state.bold) { $0.toggleBold() }
                button("italic", "Italic", on: state.italic) { $0.toggleItalic() }
                button("strikethrough", "Strikethrough", on: state.strike) { $0.toggleStrike() }
                button("link", "Link", on: state.link) { _ in controller.editLink() }
                if !state.selecting {
                    divider
                    Menu {
                        ForEach(1...3, id: \.self) { level in
                            Button("Heading \(level)") { controller.run { $0.toggleHeading(level) } }
                        }
                    } label: {
                        icon("textformat.size", on: state.heading > 0)
                    }
                    .accessibilityLabel("Heading")
                    button("list.bullet", "Bulleted list", on: state.list == .bullet) { $0.toggleList(.bullet) }
                    button("list.number", "Numbered list", on: state.list == .ordered) { $0.toggleList(.ordered) }
                    button("checklist", "Checklist", on: state.list == .task) { $0.toggleList(.task) }
                    button("text.quote", "Quote", on: state.quote) { $0.toggleQuote() }
                    divider
                    button("decrease.indent", "Outdent", enabled: state.canOutdent) { $0.outdent() }
                    button("increase.indent", "Indent", enabled: state.canIndent) { $0.indent() }
                }
            }
        }
        .disabled(state.inTitle)
        .opacity(state.inTitle ? 0.4 : 1)
    }

    private var divider: some View {
        Rectangle().fill(Color.hair).frame(width: 1, height: 20).padding(.horizontal, 4)
    }

    private func icon(_ symbol: String, on: Bool) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 16, weight: .medium))
            .foregroundStyle(on ? Color.onAccent : Color.ink2)
            .frame(width: 40, height: 36)
            .background(on ? Color.accent : .clear, in: .rect(cornerRadius: 10))
    }

    private func button(
        _ symbol: String, _ label: String, on: Bool = false, enabled: Bool = true,
        _ action: @escaping (NoteTextView) -> Void
    ) -> some View {
        Button {
            controller.run(action)
        } label: {
            icon(symbol, on: on)
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func suggestions(_ tag: TagAtCursor) -> [TagCount] {
        guard let query = tag.query?.lowercased() else { return [] }
        return tags.filter { $0.key.hasPrefix(query) && $0.key != query }.prefix(12).map { $0 }
    }

    private func tagStrip(_ tag: TagAtCursor) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(suggestions(tag), id: \.key) { suggestion in
                    Button {
                        controller.run { $0.insertTag(tag.range, name: suggestion.name) }
                    } label: {
                        Text("#" + suggestion.name)
                            .font(.notesLabel)
                            .foregroundStyle(Color.ink)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color.accent.opacity(0.3), in: .capsule)
                    }
                    .accessibilityLabel("Tag \(suggestion.name)")
                }
            }
            .padding(.horizontal, 4)
        }
    }
}
