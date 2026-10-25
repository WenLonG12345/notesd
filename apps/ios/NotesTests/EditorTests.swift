import Foundation
import NotesCore
import Testing
import UIKit

@testable import Notes

/// The shared Markdown fixtures, read from the repository (the simulator sees the Mac's files).
enum Fixtures {
    struct Case: Decodable {
        var name: String
        var markdown: String
    }

    private struct Suite: Decodable {
        var cases: [Case]
    }

    static let roundtrip = load("roundtrip.json")
    static let plaintext = load("plaintext.json")

    private static func load(_ name: String) -> [Case] {
        var url = URL(fileURLWithPath: #filePath)
        // EditorTests.swift → NotesTests → ios → apps → repository root
        for _ in 0..<4 { url.deleteLastPathComponent() }
        let data = try! Data(contentsOf: url.appending(path: "shared/markdown-fixtures/\(name)"))
        return try! JSONDecoder().decode(Suite.self, from: data).cases
    }
}

@MainActor
struct EditorTests {
    let view = NoteTextView()

    private func load(_ markdown: String, title: String = "Title") {
        view.setDocument(Document.forNote(title: title, body: Markdown.parse(markdown)))
    }

    private var markdown: String { Markdown.serialize(view.document()) }

    /// Puts the cursor after the first occurrence of `text`.
    private func cursor(after text: String) {
        let r = (view.textStorage.string as NSString).range(of: text)
        view.selectedRange = NSRange(location: NSMaxRange(r), length: 0)
    }

    private func cursor(before text: String) {
        let r = (view.textStorage.string as NSString).range(of: text)
        view.selectedRange = NSRange(location: r.location, length: 0)
    }

    private func select(_ text: String) {
        view.selectedRange = (view.textStorage.string as NSString).range(of: text)
    }

    @Test func codeFencesShowTheirCodeAndSaveTheirSource() {
        let md = "```go\nfunc main() {}\n```\n\n```python\nx = 1\n```\n\n```\nplain\n```\n\n| a |\n| - |\n"
        load(md)
        var labels: [String?] = []
        view.textStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: view.textStorage.length)) {
            value, _, _ in
            if let raw = value as? RawAttachment { labels.append(raw.accessibilityLabel) }
        }
        #expect(labels == ["go code: func main() {}", "python code: x = 1", "Code: plain", nil])
        #expect(markdown == md)
    }

    @Test func fixturesSurviveTheEditor() {
        var failures: [String] = []
        for c in Fixtures.roundtrip {
            load(c.markdown)
            let doc = view.document()
            if markdown != c.markdown || doc.title != "Title" {
                failures.append("\(c.name): \(c.markdown.debugDescription) became \(markdown.debugDescription)")
            }
        }
        #expect(failures == [])
    }

    @Test func anyMarkdownSurvivesTheEditorSemantically() {
        var failures: [String] = []
        for c in Fixtures.plaintext + Fixtures.roundtrip {
            let doc = Document.forNote(title: "Title", body: Markdown.parse(c.markdown))
            view.setDocument(doc)
            if view.document() != doc { failures.append("\(c.name): \(doc) became \(view.document())") }
        }
        #expect(failures == [])
    }

    @Test func titleIsTheFirstLine() {
        load("Body\n", title: "My note")
        #expect(view.document().title == "My note")
        cursor(after: "My note")
        view.insertText("!")
        #expect(view.document().title == "My note!")
        #expect(markdown == "Body\n")
    }

    @Test func enterInAListStartsTheNextItem() {
        load("- one\n")
        cursor(after: "one")
        view.insertText("\n")
        view.insertText("two")
        #expect(markdown == "- one\n- two\n")
    }

    @Test func enterOnAnEmptyItemLeavesTheList() {
        load("- one\n")
        cursor(after: "one")
        view.insertText("\n")
        view.insertText("\n")
        view.insertText("after")
        #expect(markdown == "- one\n\nafter\n")
    }

    @Test func enterAtTheStartOfAHeadingKeepsItAHeading() {
        load("## Plans\n")
        cursor(before: "Plans")
        view.insertText("\n")
        #expect(markdown == "## Plans\n")
        #expect(view.document().blocks.count == 3)
    }

    @Test func enterAfterAHeadingIsAParagraph() {
        load("# Head\n")
        cursor(after: "Head")
        view.insertText("\n")
        view.insertText("text")
        #expect(markdown == "# Head\n\ntext\n")
    }

    @Test func backspaceAtTheStartOfAnItemRemovesTheMarkerFirst() {
        load("- one\n- two\n")
        cursor(before: "two")
        view.deleteBackward()
        #expect(markdown == "- one\n\ntwo\n")
        view.deleteBackward()
        #expect(markdown == "- onetwo\n")
    }

    @Test func boldTogglesOnASelection() {
        load("make this bold\n")
        select("this")
        view.toggleBold()
        #expect(markdown == "make **this** bold\n")
        view.toggleBold()
        #expect(markdown == "make this bold\n")
    }

    @Test func boldWithNoSelectionAppliesToWhatIsTypedNext() {
        load("a\n")
        cursor(after: "a")
        view.toggleBold()
        view.insertText(" b")
        #expect(markdown == "a **b**\n")
    }

    @Test func typingAtTheEndOfBoldContinuesIt() {
        load("**bold**\n")
        cursor(after: "bold")
        view.insertText("er")
        #expect(markdown == "**bolder**\n")
    }

    @Test func listsHeadingsAndQuotesToggle() {
        load("one\n\ntwo\n")
        select("one\ntwo")
        view.toggleList(.task)
        #expect(markdown == "- [ ] one\n- [ ] two\n")
        view.toggleList(.ordered)
        #expect(markdown == "1. one\n2. two\n")
        view.toggleList(.ordered)
        #expect(markdown == "one\n\ntwo\n")
        cursor(after: "one")
        view.toggleHeading(2)
        #expect(markdown == "## one\n\ntwo\n")
        view.toggleQuote()
        #expect(markdown == "> ## one\n\ntwo\n")
    }

    @Test func eachQuoteKnowsWhereItStartsAndEnds() {
        load("> one\n\n> two\n>\n> > three\n\nafter\n")
        func edges(_ text: String) -> QuoteEdges? {
            let at = (view.textStorage.string as NSString).range(of: text).location
            return (view.textStorage.attribute(.notesBlock, at: at, effectiveRange: nil) as? BlockAttr)?.quoteEdges
        }
        #expect(edges("one") == QuoteEdges(starts: 1, ends: 1))
        #expect(edges("two") == QuoteEdges(starts: 1, ends: 0))
        // The nested quote ends with the one around it.
        #expect(edges("three") == QuoteEdges(starts: 1, ends: 2))
        #expect(edges("after") == QuoteEdges())
        #expect(markdown == "> one\n\n> two\n>\n> > three\n\nafter\n")
    }

    @Test func indentNestsUnderThePreviousItem() {
        load("- a\n- b\n")
        cursor(after: "b")
        view.indent()
        #expect(markdown == "- a\n  - b\n")
        view.outdent()
        #expect(markdown == "- a\n- b\n")
    }

    @Test func rawTextIsKeptAsWrittenAndTypingBesideItStartsALine() {
        let md =
            "Before\n\n```js\nlet x = 1\n```\n\nSee `code` and ![a](notesd:image/\(String(repeating: "a", count: 64))?w=1&h=2)\n"
        load(md)
        #expect(markdown == md)
        cursor(after: "Before")
        view.insertText(" more")
        #expect(markdown == md.replacingOccurrences(of: "Before", with: "Before more"))
    }

    @Test func typingBeforeARawBlockStartsAParagraphAboveIt() {
        let md = "```js\nlet x = 1\n```\n"
        load(md)
        cursor(before: "\u{FFFC}")
        view.insertText("a")
        view.insertText("b")
        #expect(markdown == "ab\n\n" + md)
    }

    @Test func tagsAreMarked() {
        load("Buy #groceries and #summer trip# now\n")
        var names: [String] = []
        view.textStorage.enumerateAttribute(.notesTag, in: NSRange(location: 0, length: view.textStorage.length)) {
            value, _, _ in
            if let name = value as? String { names.append(name) }
        }
        #expect(names == ["groceries", "summer trip"])
    }

    @Test func hardBreaksStayInTheirParagraph() {
        load("one\\\ntwo\n")
        #expect(markdown == "one\\\ntwo\n")
        cursor(after: "two")
        view.insertHardBreak()
        view.insertText("three")
        #expect(markdown == "one\\\ntwo\\\nthree\n")
    }

    @Test func undoRestoresAFormattingChange() {
        load("plain\n")
        select("plain")
        #expect(view.undoManager != nil)
        view.toggleBold()
        #expect(markdown == "**plain**\n")
        view.undoManager?.undo()
        #expect(markdown == "plain\n")
    }

    @Test func linksWrapTheSelection() {
        load("see docs\n")
        select("docs")
        view.setLink("example.com/docs")
        #expect(markdown == "see [docs](https://example.com/docs)\n")
        cursor(after: "do")
        view.removeLink()
        #expect(markdown == "see docs\n")
    }
}
