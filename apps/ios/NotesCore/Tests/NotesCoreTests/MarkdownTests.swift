import Testing

@testable import NotesCore

struct MarkdownFixtureTests {
    @Test func canonicalMarkdownRoundTripsByteForByte() {
        let failures = Fixtures.roundtrip.compactMap { c -> String? in
            let out = Markdown.serialize(Markdown.parse(c.markdown))
            return out.same(as: c.markdown) ? nil : "\(c.name): expected \(c.markdown.quoted) got \(out.quoted)"
        }
        #expect(failures == [])
    }

    @Test func anyMarkdownRoundTripsSemantically() {
        let failures = (Fixtures.plaintext + Fixtures.roundtrip).compactMap { c -> String? in
            let doc = Markdown.parse(c.markdown)
            let again = Markdown.parse(Markdown.serialize(doc))
            return again == doc ? nil : "\(c.name): \(doc) became \(again)"
        }
        #expect(failures == [])
    }

    @Test func canonicalIsIdempotent() {
        let messy = [
            "* item\n* other\n",
            "__bold__ and _it_\n",
            "trailing spaces   \nnext\n",
            "a\r\nb\r\n\r\n- c\r\n",
            "+ plus\n+ list\n",
            "1) one\n2) two\n",
            "Setext\n======\n",
            "#### deep heading\n",
            "> quote\nlazy line\n",
            "- [X] Upper\n- [ ] lower\n",
            "  - indented\n",
            "line  \nhard\n",
            "***\n",
            "<https://example.com>\n",
            "[ref]\n\n[ref]: https://example.com\n",
        ]
        let inputs = Fixtures.roundtrip.map(\.markdown) + Fixtures.plaintext.map(\.markdown) + messy
        let failures = inputs.compactMap { md -> String? in
            let once = Markdown.canonical(md)
            let twice = Markdown.canonical(once)
            return once.same(as: twice) ? nil : "\(md.quoted): \(once.quoted) then \(twice.quoted)"
        }
        #expect(failures == [])
    }

    @Test func parseNeverFails() {
        var r = SeededRandom(seed: 7)
        let alphabet = "ab *_~`[]()<>!#>-+.1)\\\n\t |:x@é".unicodeScalars.map { String($0) }
        for _ in 0..<5000 {
            let s = (0..<r.int(40)).map { _ in r.pick(alphabet) }.joined()
            _ = Markdown.canonical(s)
        }
    }
}

/// parse(serialize(doc)) == doc for random documents an editor can produce (`DocGen`).
struct MarkdownPropertyTests {
    @Test func randomDocumentsSurviveARoundTrip() {
        var failures: [String] = []
        for seed in UInt64(1)...3000 {
            var gen = DocGen(seed: seed)
            let doc = gen.document()
            let back = Markdown.parse(Markdown.serialize(doc))
            if back != doc && failures.count < 5 {
                let i = doc.blocks.indices.first { doc.blocks[$0] != back.blocks[safe: $0] } ?? 0
                let block = Markdown.serialize(Document([doc.blocks[i]]))
                failures.append(
                    "seed \(seed) block \(i)\n\(block.quoted)\n\(doc.blocks[i])\n\(String(describing: back.blocks[safe: i]))"
                )
            }
        }
        #expect(failures == [])
    }
}

/// Inputs where commonmark-java (Android) needed work to match the web's markdown-it.
/// Here markdown-it itself parses, so these hold by construction; they stay as a
/// guard on the tokenizer rules in Resources/markdown.js. Expected values are the
/// web codec's output.
struct MarkdownCompatTests {
    @Test func canonicalOutputMatchesTheWebCodec() {
        let cases: [(String, String)] = [
            ("***[**b**](https://q)\"#***b******b***", "[***b***](https://q)*\"#**b***b\\*\\*\\*\n"),
            ("~~a~~~~b~~", "~~ab~~\n"),
            ("~~~`` info\ncode\n~~~", "~~~`` info\ncode\n~~~\n"),
            ("```~ info\ncode\n```", "```~ info\ncode\n```\n"),
            ("![x](javascript:y)", "!\\[x\\](javascript:y)\n"),
            ("[x](javascript:alert(1)) z", "\\[x\\](javascript:alert(1)) z\n"),
            ("- [x] \nnext line", "- [x]  next line\n"),
            ("**a **b** c**", "**a b** c\n"),
            ("__[**b**](https://q)>__", "[**b**](https://q)>\n"),
            ("    code\n    \n\n- after", "    code\n\n- after\n"),
            ("Para\n```\ncode\n```", "Para\n\n```\ncode\n```\n"),
            ("word\n***", "word\n\n***\n"),
            ("a `` b", "a \\`\\` b\n"),
            ("x <https://a.b> y", "x <https://a.b> y\n"),
            ("[a](<https://ex.com/ä b>)", "[a](https://ex.com/%C3%A4%20b)\n"),
            ("[a](https://ä.example/ö)", "[a](https://xn--4ca.example/%C3%B6)\n"),
            ("<div>\n*hi*\n</div>", "<div> *hi* </div>\n"),
            ("1) x", "1. x\n"),
            ("+ a", "- a\n"),
            ("#### four", "### four\n"),
            ("Setext\n===", "# Setext\n"),
            ("** a **", "\\*\\* a \\*\\*\n"),
            ("a_b_c _d_", "a_b_c *d*\n"),
            ("&amp; &copy;", "& ©\n"),
            ("[ref]\n\n[ref]: https://x.y", "[ref](https://x.y)\n"),
            ("- # h", "-\n\n  # h\n"),
            ("- [ ] a\n- b", "- \\[ \\] a\n- b\n"),
            ("about ~5", "about \\~5\n"),
            ("foo![x](y)", "foo![x](y)\n"),
        ]
        let failures = cases.compactMap { md, expected -> String? in
            let got = Markdown.canonical(md)
            return got.same(as: expected) ? nil : "\(md.quoted): expected \(expected.quoted) got \(got.quoted)"
        }
        #expect(failures == [])
    }

    @Test func parseBuildsTheWebDocumentTree() {
        let md = "# Head\n\n- [x] **Done** [it](https://x.y \"T\")\n  1. nested\n\n> quote\\\n> `code`\n"
        let link = Mark.link(href: "https://x.y", title: "T")
        let expected = Document([
            .heading(level: 1, [.text("Head")]),
            .taskList([
                TaskItem(
                    checked: true,
                    [
                        .paragraph([.text("Done", marks: [.bold]), .text(" "), .text("it", marks: [link])]),
                        .orderedList(start: 1, [ListItem([.paragraph([.text("nested")])])]),
                    ])
            ]),
            .blockquote([.paragraph([.text("quote"), .hardBreak(), .rawInline("`code`")])]),
        ])
        #expect(Markdown.parse(md) == expected)
    }

    @Test func emptyInputIsOneEmptyParagraph() {
        #expect(Markdown.parse("") == Document.empty)
        #expect(Markdown.serialize(Document.empty) == "")
    }

    @Test func titleLineIsSkippedWhenSerializing() {
        let doc = Document.forNote(title: "My\nnote", body: Markdown.parse("Body\n"))
        #expect(doc.title == "My note")
        #expect(Markdown.serialize(doc) == "Body\n")
    }

    @Test func rawRunsKeepTheirExactSource() {
        let md =
            "> ```js\n> let x = 1\n>\n> ```\n\nSee ![a *b*](notesd:image/\(String(repeating: "a", count: 64))?w=1&h=2) and `c`\n"
        #expect(Markdown.canonical(md).same(as: md))
    }
}
