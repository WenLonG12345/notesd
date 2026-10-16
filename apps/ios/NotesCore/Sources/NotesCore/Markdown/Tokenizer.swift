import Foundation
import JavaScriptCore

/// markdown-it, run in JavaScriptCore with the web app's own tokenizer rules
/// (Resources/markdown.js), so a note parses here exactly as it does on the
/// web: same CommonMark corner cases, link normalisation and raw-run sources.
///
/// One context for the process, created on first use. A JSContext isn't safe to
/// call from two threads at once, so calls take turns.
final class Tokenizer: @unchecked Sendable {
    static let shared = Tokenizer()

    private let lock = NSLock()
    private var function: JSValue?
    private let decoder = JSONDecoder()

    /// The token tree for normalised Markdown, or nil if the tokenizer failed.
    func tokens(_ source: String) -> [Token]? {
        lock.lock()
        defer { lock.unlock() }
        guard let fn = function ?? load(),
            let json = fn.call(withArguments: [source]), json.isString,
            let data = json.toString().data(using: .utf8)
        else { return nil }
        return try? decoder.decode([Token].self, from: data)
    }

    private func load() -> JSValue? {
        guard let context = JSContext() else { return nil }
        for name in ["markdown-it.min", "markdown"] {
            guard let url = Bundle.module.url(forResource: name, withExtension: "js"),
                let script = try? String(contentsOf: url, encoding: .utf8)
            else { return nil }
            context.evaluateScript(script, withSourceURL: url)
            if context.exception != nil { return nil }
        }
        let fn = context.objectForKeyedSubscript("notesdTokens")
        guard let fn, !fn.isUndefined else { return nil }
        function = fn
        return fn
    }
}
