/// Locked notes (architecture §10.1). This build can't open them: it only
/// recognises the envelope, so their bodies stay out of search, previews and
/// tags, and the editor leaves them alone.
public enum LockedNotes {
    public static let prefix = "notesd:locked:v1:"

    /// Whether `content` is an encrypted body. Byte-exact, as on the server.
    public static func isLocked(_ content: String) -> Bool {
        content.utf8.starts(with: prefix.utf8)
    }
}
