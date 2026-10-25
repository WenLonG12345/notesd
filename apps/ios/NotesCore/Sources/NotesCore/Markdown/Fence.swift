import Foundation

/// A raw block that is a fenced code block, split for display: the language
/// from the info string and the code without its fence lines (spec §7.1,
/// shared/markdown-fixtures/fences.json). Display only: the block still saves
/// its source unchanged.
public struct Fence: Equatable, Sendable {
    public var lang: String
    public var code: String

    public enum Token: Sendable { case comment, string, number, keyword }

    /// A colored run of the code, in UTF-16 offsets (NSRange).
    public struct Run: Equatable, Sendable {
        public var start: Int
        public var end: Int
        public var token: Token
    }

    public init(lang: String, code: String) {
        self.lang = lang
        self.code = code
    }

    public static func parse(_ source: String) -> Fence? {
        let lines = source.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        guard let m = lines[0].wholeMatch(of: /( {0,3})(`{3,}|~{3,})(.*)/) else { return nil }
        let indent = m.1.count
        let marker = String(m.2)
        let info = String(m.3)
        // A backtick fence's info string can't hold a backtick (CommonMark §4.5).
        if marker.first == "`" && info.contains("`") { return nil }
        var body = Array(lines.dropFirst())
        if let last = body.last, isClose(last, marker) { body.removeLast() }
        // Content lines lose up to as many leading spaces as the opening fence had.
        let code = body.map { line in
            String(line.dropFirst(min(indent, line.prefix(while: { $0 == " " }).count)))
        }
        let lang = info.split(whereSeparator: \.isWhitespace).first.map(String.init) ?? ""
        return Fence(lang: lang, code: code.joined(separator: "\n"))
    }

    private static func isClose(_ line: String, _ marker: String) -> Bool {
        let indent = line.prefix(while: { $0 == " " }).count
        guard indent <= 3 else { return false }
        let rest = line.dropFirst(indent)
        let run = rest.prefix(while: { $0 == marker.first })
        return run.count >= marker.count && rest.dropFirst(run.count).allSatisfy(\.isWhitespace)
    }

    /// The colored runs of `code` in `lang`: comments, strings, numbers and
    /// keywords. Empty for a language it doesn't know, which stays plain.
    // ponytail: a keyword scanner, not a grammar (the web uses highlight.js), the
    // same as Android's Fence.kt. Python's triple quotes and nested comments come
    // out partly wrong; a real highlighter would mean a bundled grammar set.
    public static func highlight(_ code: String, lang: String) -> [Run] {
        guard let spec = languages[lang.lowercased()] else { return [] }
        let s = Array(code.utf16)
        var out: [Run] = []
        var i = 0
        func starts(_ p: [UInt16], _ at: Int) -> Bool {
            at + p.count <= s.count && Array(s[at..<at + p.count]) == p
        }
        func find(_ p: [UInt16], from: Int) -> Int? {
            var j = from
            while j + p.count <= s.count {
                if starts(p, j) { return j }
                j += 1
            }
            return nil
        }
        // ASCII letters, digits and _, and anything past ASCII.
        func word(_ at: Int) -> Bool {
            guard at < s.count else { return false }
            let c = s[at]
            return (97...122).contains(c) || (65...90).contains(c) || (48...57).contains(c) || c == 95 || c >= 0x80
        }
        let newline: UInt16 = 10
        let backslash: UInt16 = 92
        let backtick: UInt16 = 96
        let dot: UInt16 = 46
        while i < s.count {
            let start = i
            let c = s[i]
            if let block = spec.block, starts(block.open, i) {
                i = find(block.close, from: i + block.open.count).map { $0 + block.close.count } ?? s.count
                out.append(Run(start: start, end: i, token: .comment))
            } else if spec.line.contains(where: { starts($0, i) }) {
                i = find([newline], from: i) ?? s.count
                out.append(Run(start: start, end: i, token: .comment))
            } else if spec.quotes.contains(c) {
                i += 1
                while i < s.count && s[i] != c && (s[i] != newline || c == backtick) {
                    i += s[i] == backslash ? 2 : 1
                }
                i = min(i + 1, s.count)
                out.append(Run(start: start, end: i, token: .string))
            } else if (48...57).contains(c) {
                while word(i) || (i < s.count && s[i] == dot) { i += 1 }
                out.append(Run(start: start, end: i, token: .number))
            } else if word(i) {
                while word(i) { i += 1 }
                if spec.keywords.contains(String(decoding: s[start..<i], as: UTF16.self)) {
                    out.append(Run(start: start, end: i, token: .keyword))
                }
            } else {
                i += 1
            }
        }
        return out
    }

    private struct Spec {
        var keywords: Set<String>
        var line: [[UInt16]] = [Array("//".utf16)]
        var block: (open: [UInt16], close: [UInt16])? = (Array("/*".utf16), Array("*/".utf16))
        var quotes: Set<UInt16> = Set("\"'".utf16)

        init(_ keywords: String, line: [String] = ["//"], block: Bool = true, quotes: String = "\"'") {
            self.keywords = Set(keywords.split(separator: " ").map(String.init))
            self.line = line.map { Array($0.utf16) }
            if !block { self.block = nil }
            self.quotes = Set(quotes.utf16)
        }
    }

    private static let cLike =
        "break case const continue default do else enum for goto if return sizeof static "
        + "struct switch typedef union void while true false"

    private static let languages: [String: Spec] = {
        var out: [String: Spec] = [:]
        func add(_ names: String, _ spec: Spec) {
            for name in names.split(separator: " ") { out[String(name)] = spec }
        }
        add(
            "go golang",
            Spec(
                "break case chan const continue default defer else fallthrough for func go goto if "
                    + "import interface map package range return select struct switch type var nil true false",
                quotes: "\"'`"))
        add(
            "js javascript jsx mjs ts typescript tsx",
            Spec(
                "as async await break case catch class const continue default delete do else export "
                    + "extends false finally for from function if import in instanceof interface let new "
                    + "null of return static super switch this throw true try type typeof undefined var "
                    + "void while yield",
                quotes: "\"'`"))
        add(
            "kotlin kt kts",
            Spec(
                "as break class companion const continue data do else enum false for fun if import in "
                    + "interface is null object override package private public return sealed super this "
                    + "throw true try typealias val var when while"))
        add(
            "swift",
            Spec(
                "actor as async await break case catch class continue default defer do else enum "
                    + "extension false for func guard if import in init let nil private protocol public "
                    + "return self static struct super switch throw throws true try var where while"))
        add(
            "java",
            Spec(
                "abstract boolean break case catch char class continue default do double else enum "
                    + "extends false final finally float for if implements import int interface long new "
                    + "null package private protected public return static super switch this throw throws "
                    + "true try void while"))
        add("c h", Spec(cLike + " char double extern float int long short signed unsigned NULL"))
        add(
            "cpp c++ cc hpp",
            Spec(
                cLike + " auto bool catch char class delete double float int long namespace new nullptr "
                    + "private public template this throw try using virtual"))
        add(
            "cs csharp",
            Spec(
                "abstract as async await bool break case catch class const continue default do else "
                    + "enum false finally for foreach if in int interface namespace new null override "
                    + "private public return static string struct this throw true try using var void while"))
        add(
            "rust rs",
            Spec(
                "as async await break const continue crate else enum false fn for if impl in let loop "
                    + "match mod move mut pub ref return self Self static struct trait true type use where while"
            ))
        add(
            "py python",
            Spec(
                "and as assert async await break class continue def del elif else except False finally "
                    + "for from global if import in is lambda None nonlocal not or pass raise return True "
                    + "try while with yield",
                line: ["#"], block: false))
        add(
            "rb ruby",
            Spec(
                "begin break case class def do else elsif end ensure false for if in module next nil "
                    + "not or rescue return self super then true unless until when while yield",
                line: ["#"], block: false))
        add(
            "sh bash zsh shell console",
            Spec(
                "case do done elif else esac export fi for function if in local return then until while",
                line: ["#"], block: false))
        add(
            "sql",
            Spec(
                "and as by create delete desc drop from group having in index insert into join key "
                    + "left limit not null on or order primary select set table update values where "
                    + "AND AS BY CREATE DELETE DESC DROP FROM GROUP HAVING IN INDEX INSERT INTO JOIN KEY "
                    + "LEFT LIMIT NOT NULL ON OR ORDER PRIMARY SELECT SET TABLE UPDATE VALUES WHERE",
                line: ["--"]))
        add("json", Spec("true false null", line: [], block: false, quotes: "\""))
        add("yaml yml", Spec("true false null yes no", line: ["#"], block: false))
        return out
    }()
}
