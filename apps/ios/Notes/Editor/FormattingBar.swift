import NotesCore
import SwiftUI

/// The bar above the keyboard, as on Android: headings, lists, quote, picture
/// and indentation while writing; marks and link once text is selected; or
/// the tags that complete the one being typed. In the title, nothing applies.
struct FormattingBar: View {
    let controller: EditorController
    let tags: [TagCount]

    private var state: FormatState { controller.format }

    var body: some View {
        Group {
            if let tag = state.tag, !suggestions(tag).isEmpty {
                tagStrip(tag)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 2) {
                        if state.selecting { selectionTools } else { writingTools }
                    }
                    .padding(.horizontal, 2)
                    .animation(.snappy(duration: 0.2), value: state.canIndent || state.canOutdent)
                }
                .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
                .disabled(state.inTitle)
                .opacity(state.inTitle ? 0.4 : 1)
                .id(state.selecting)
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.15), value: state.selecting)
        .frame(height: 48)
        .padding(.horizontal, 6)
        .glassEffect(.regular, in: .capsule)
        .padding(.horizontal, 8)
        .padding(.bottom, 4)
    }

    @ViewBuilder private var writingTools: some View {
        glyph("H1", "Heading 1", font: .custom("InstrumentSerif-Regular", size: 22), on: state.heading == 1) {
            $0.toggleHeading(1)
        }
        glyph("H2", "Heading 2", font: .custom("InstrumentSerif-Regular", size: 19), on: state.heading == 2) {
            $0.toggleHeading(2)
        }
        glyph(
            "H3", "Heading 3", font: .custom("InstrumentSans-Regular", size: 15).weight(.semibold),
            on: state.heading == 3
        ) { $0.toggleHeading(3) }
        divider
        button("list.bullet", "Bulleted list", on: state.list == .bullet) { $0.toggleList(.bullet) }
        button("list.number", "Numbered list", on: state.list == .ordered) { $0.toggleList(.ordered) }
        button("checklist", "Checklist", on: state.list == .task) { $0.toggleList(.task) }
        button("text.quote", "Quote", on: state.quote) { $0.toggleQuote() }
        button("photo", "Add picture") { _ in controller.pickingImage = true }
        if state.canIndent || state.canOutdent {
            divider
            button("decrease.indent", "Outdent", enabled: state.canOutdent) { $0.outdent() }
            button("increase.indent", "Indent", enabled: state.canIndent) { $0.indent() }
        }
    }

    @ViewBuilder private var selectionTools: some View {
        glyph("B", "Bold", font: .custom("InstrumentSans-Regular", size: 18).weight(.bold), on: state.bold) {
            $0.toggleBold()
        }
        glyph("I", "Italic", font: .custom("InstrumentSerif-Italic", size: 22), on: state.italic) {
            $0.toggleItalic()
        }
        glyph("S", "Strikethrough", font: .custom("InstrumentSans-Regular", size: 18), strike: true, on: state.strike) {
            $0.toggleStrike()
        }
        divider
        button("link", "Link", on: state.link) { _ in controller.editLink() }
    }

    private var divider: some View {
        Rectangle().fill(Color.hairStrong).frame(width: 1, height: 22).padding(.horizontal, 5)
    }

    /// A 44-point tool, filled while on (web: .tool[aria-pressed]).
    private func tool(on: Bool, @ViewBuilder _ label: () -> some View) -> some View {
        label()
            .foregroundStyle(on ? Color.onAccent : Color.ink)
            .frame(width: 44, height: 40)
            .background(on ? Color.accent : .clear, in: .rect(cornerRadius: 12, style: .continuous))
            .contentShape(.rect)
    }

    private func glyph(
        _ text: String, _ label: String, font: Font, strike: Bool = false, on: Bool,
        _ action: @escaping (NoteTextView) -> Void
    ) -> some View {
        Button {
            controller.run(action)
        } label: {
            tool(on: on) { Text(text).font(font).strikethrough(strike).fixedSize() }
        }
        .buttonStyle(PressScaleStyle(scale: 0.88))
        .accessibilityLabel(label)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func button(
        _ symbol: String, _ label: String, on: Bool = false, enabled: Bool = true,
        _ action: @escaping (NoteTextView) -> Void
    ) -> some View {
        Button {
            controller.run(action)
        } label: {
            tool(on: on) { Image(systemName: symbol).font(.system(size: 17, weight: .medium)) }
        }
        .buttonStyle(PressScaleStyle(scale: 0.88))
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
