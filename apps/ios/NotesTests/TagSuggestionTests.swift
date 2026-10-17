import NotesCore
import Testing

@testable import Notes

struct TagSuggestionTests {
    let all = [
        TagCount(key: "home", name: "Home", count: 3),
        TagCount(key: "home/repairs", name: "Home/Repairs", count: 1),
        TagCount(key: "work", name: "Work", count: 5),
        TagCount(key: "homework", name: "homework", count: 1),
    ]

    @Test func wholeNamePrefixesComeFirstAndTheTypedTagIsSkipped() {
        #expect(suggestTags(all, query: "ho").map(\.key) == ["home", "home/repairs", "homework"])
        #expect(suggestTags(all, query: "rep").map(\.key) == ["home/repairs"])
        #expect(suggestTags(all, query: "work").map(\.key) == ["homework"])
        #expect(suggestTags(all, query: "") == [])
    }

    @Test func queryIsCaseInsensitive() {
        #expect(suggestTags(all, query: "HO").map(\.key) == ["home", "home/repairs", "homework"])
    }

    @Test func mostUsedFirstThenAlphabeticalAtMostEight() {
        let tags = (0..<10).map { TagCount(key: "t\($0)", name: "t\($0)", count: $0 % 2) }
        #expect(suggestTags(tags, query: "t").map(\.key) == ["t1", "t3", "t5", "t7", "t9", "t0", "t2", "t4"])
    }
}
