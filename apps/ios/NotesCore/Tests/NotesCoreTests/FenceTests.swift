import Foundation
import Testing

@testable import NotesCore

struct FenceTests {
    @Test func fencesSplitAsInTheSharedFixtures() {
        let failures = Fixtures.fences.compactMap { c -> String? in
            let got = Fence.parse(c.source).map { Fixtures.FenceCase.Shown(lang: $0.lang, code: $0.code) }
            return got == c.fence
                ? nil : "\(c.name): expected \(String(describing: c.fence)) got \(String(describing: got))"
        }
        #expect(failures == [])
    }

    @Test func highlightFindsKeywordsStringsNumbersAndComments() {
        let code = "func main() { // hi\n\tx := \"a\\\"b\" + 42\n}"
        let units = Array(code.utf16)
        let got = Fence.highlight(code, lang: "Go").map {
            (String(decoding: units[$0.start..<$0.end], as: UTF16.self), $0.token)
        }
        #expect(got.map(\.0) == ["func", "// hi", "\"a\\\"b\"", "42"])
        #expect(got.map(\.1) == [.keyword, .comment, .string, .number])
        #expect(Fence.highlight(code, lang: "brainfuck") == [])
        #expect(Fence.highlight(code, lang: "") == [])
    }
}
