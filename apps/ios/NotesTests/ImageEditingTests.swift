import Foundation
import NotesCore
import Testing
import UIKit

@testable import Notes

/// Pictures in the editor (architecture §7.6): image runs drawn as pictures, atomic, kept as their source.
@MainActor
struct ImageEditingTests {
    let view = NoteTextView()
    let a = ImageRef(id: String(repeating: "a", count: 64), width: 1600, height: 1200)

    private func load(_ markdown: String) {
        view.setDocument(Document.forNote(title: "T", body: Markdown.parse(markdown)))
        view.selectedRange = NSRange(location: view.textStorage.length - 1, length: 0)
    }

    private var markdown: String { Markdown.serialize(view.document()) }

    private var pictures: [RawAttachment] {
        var out: [RawAttachment] = []
        view.textStorage.enumerateAttribute(
            .attachment, in: NSRange(location: 0, length: view.textStorage.length)
        ) { value, _, _ in
            if let raw = value as? RawAttachment, raw.picture != nil { out.append(raw) }
        }
        return out
    }

    @Test func anImageRunIsDrawnAsAPictureAndSavedAsWritten() {
        let body = "Before\n\n\(a.markdown(alt: "a dot"))\n\nAfter\n"
        load(body)
        #expect(pictures.map(\.picture) == [a])
        #expect(markdown == body)
        // VoiceOver reads the alt text, not the Markdown.
        #expect(pictures.first?.accessibilityLabel == "Picture: a dot")
        // Laid out at its own size in points, shrunk to fit the line, keeping its shape.
        let bounds = pictures[0].attachmentBounds(
            for: [.font: UIFont.systemFont(ofSize: 17)], location: view.textLayoutManager!.documentRange.location,
            textContainer: nil, proposedLineFragment: CGRect(x: 0, y: 0, width: 360, height: 20), position: .zero)
        #expect(bounds.width == 360 && bounds.height == 270)
    }

    @Test func aMadeUpTallSizeIsCappedKeepingItsShape() {
        let tall = ImageRef(id: String(repeating: "b", count: 64), width: 999, height: 999_999)
        load("\(tall.markdown())\n")
        let bounds = pictures[0].attachmentBounds(
            for: [.font: UIFont.systemFont(ofSize: 17)], location: view.textLayoutManager!.documentRange.location,
            textContainer: nil, proposedLineFragment: CGRect(x: 0, y: 0, width: 360, height: 20), position: .zero)
        #expect(bounds.height == 1440)
        #expect(bounds.width >= 1 && bounds.width < 2)
    }

    @Test func aLinkToAnImageIsntOneOfTheNotesPictures() {
        load("[see](notesd:image/\(a.id)?w=10&h=10) and \(a.markdown())\n")
        #expect(view.imageIds() == [a.id])
        load("[see](notesd:image/\(a.id)?w=10&h=10)\n")
        #expect(view.imageIds().isEmpty)
    }

    @Test func otherImagesAndCodeStayPlainRawRuns() {
        load("![a](cat.png) and `\(a.markdown())`\n")
        #expect(pictures.isEmpty)
    }

    @Test func anAddedPictureGoesOnItsOwnLineAfterTheCursorsLine() {
        load("Hello\n\nLater\n")
        let hello = (view.textStorage.string as NSString).range(of: "Hello")
        view.selectedRange = NSRange(location: hello.location + 2, length: 0)
        view.insertImage(a.markdown())
        #expect(markdown == "Hello\n\n\(a.markdown())\n\nLater\n")
        #expect(pictures.count == 1)
    }

    @Test func anAddedPictureFillsTheEmptyLineTheCursorIsOn() {
        load("Hello\n")
        view.insertText("\n")
        view.insertImage(a.markdown())
        #expect(markdown == "Hello\n\n\(a.markdown())\n")
    }

    @Test func aPictureAddedInTheTitleGoesFirstInTheBody() {
        load("Body\n")
        view.selectedRange = NSRange(location: 1, length: 0)
        view.insertImage(a.markdown())
        #expect(markdown == "\(a.markdown())\n\nBody\n")
        #expect(view.document().title == "T")
    }

    @Test func backspaceAfterAPictureDeletesAllOfIt() {
        load("Before\n\n\(a.markdown())\n")
        view.deleteBackward()
        #expect(!markdown.contains("notesd:image"))
        #expect(pictures.isEmpty)
    }

    @Test func undoTakesAnAddedPictureOut() {
        load("Hello\n")
        view.insertImage(a.markdown())
        #expect(pictures.count == 1)
        view.undoManager?.undo()
        #expect(markdown == "Hello\n")
    }
}
