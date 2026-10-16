import Foundation

/// What the user typed as their server, made into a base URL, or why it can't be one.
public enum ServerAddress: Equatable, Sendable {
    case valid(URL)
    case invalid(String)

    /// Accepts a bare host ("notes.example.com", "192.168.1.5:8080") or a
    /// full URL. Without a scheme, private addresses get http and the rest
    /// https. Plain http is refused outside private addresses: tokens
    /// would cross the internet unencrypted.
    public static func parse(_ input: String) -> ServerAddress {
        let bad = ServerAddress.invalid("That doesn't look like a server address.")
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .invalid("Enter your server's address.") }
        if trimmed.unicodeScalars.contains(where: { $0.properties.isWhitespace }) { return bad }
        let hasScheme = trimmed.firstMatch(of: /^[a-zA-Z][a-zA-Z0-9+.\-]*:\/\//) != nil
        let candidate: String
        if hasScheme {
            candidate = trimmed
        } else {
            var host = String(trimmed.prefix { $0 != "/" }.prefix { $0 != ":" })
            if host.hasPrefix("[") && host.hasSuffix("]") { host = String(host.dropFirst().dropLast()) }
            candidate = (isPrivate(host) ? "http" : "https") + "://" + trimmed
        }
        guard let parts = URLComponents(string: candidate),
            let scheme = parts.scheme?.lowercased(), scheme == "http" || scheme == "https",
            let rawHost = parts.host, !rawHost.isEmpty, isValidHost(rawHost)
        else { return bad }
        if parts.query != nil || parts.fragment != nil || parts.user != nil { return bad }
        let host = rawHost.lowercased()

        // "example.com/notes/" and "example.com/notes" are the same server.
        var segments = parts.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false).dropFirst()
            .map(String.init)
        while segments.last == "" { segments.removeLast() }
        let path = "/" + segments.joined(separator: "/")

        let defaultPort = scheme == "https" ? 443 : 80
        let port = parts.port.flatMap { $0 == defaultPort ? nil : $0 }
        let hostPart = host.contains(":") ? "[\(host)]" : host
        guard let url = URL(string: "\(scheme)://\(hostPart)\(port.map { ":\($0)" } ?? "")\(path)") else { return bad }
        if scheme == "http" && !isPrivate(host) {
            return .invalid("Use https:// for a server outside your local network.")
        }
        return .valid(url)
    }

    private static func isValidHost(_ host: String) -> Bool {
        let h = host.hasPrefix("[") && host.hasSuffix("]") ? String(host.dropFirst().dropLast()) : host
        if h.contains(":") { return h.allSatisfy { $0.isHexDigit || $0 == ":" || $0 == "." } }
        return h.unicodeScalars.allSatisfy { c in
            c == "." || c == "-" || c == "_" || isAsciiLetterOrDigit(c)
                || (c.value >= 0x80 && c.properties.isAlphabetic)
        } && !h.hasPrefix(".") && !h.contains("..")
    }

    /// Loopback, private and link-local addresses, and mDNS names: reachable only on this network.
    public static func isPrivate(_ host: String) -> Bool {
        var h = host.lowercased()
        if h.hasPrefix("[") && h.hasSuffix("]") { h = String(h.dropFirst().dropLast()) }
        if h == "localhost" || h.hasSuffix(".local") { return true }
        let parts = h.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count == 4 {
            var v4: [Int] = []
            for part in parts {
                guard let n = Int(part) else { return false }
                v4.append(n)
            }
            if v4.contains(where: { !(0...255).contains($0) }) { return false }
            let (a, b) = (v4[0], v4[1])
            return a == 10 || a == 127 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168)
                || (a == 169 && b == 254)
        }
        if h.contains(":") {
            return h == "::1" || ["fc", "fd", "fe8", "fe9", "fea", "feb"].contains { h.hasPrefix($0) }
        }
        return false
    }
}
