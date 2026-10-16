import Foundation

/// The outcome of one request. Transport failures are values too: callers decide what offline means.
public enum ApiResult<T: Sendable>: Sendable {
    case ok(T)
    /// 409: the server's current state is in `current` (a note, for note conflicts), as JSON.
    case conflict(code: String, message: String, current: Data?)
    /// No usable session: never signed in, or the session was revoked.
    case unauthenticated
    case rateLimited(retryAfterSeconds: Int64)
    /// Any other error answer, e.g. a wrong password (401 on login) or invalid input (400).
    case rejected(status: Int, error: String, message: String, field: String?)
    case unreachable(String)

    /// A non-ok result, retyped: none of them carry a value.
    func failure<U>() -> ApiResult<U> {
        switch self {
        case .ok: preconditionFailure("not a failure")
        case .conflict(let code, let message, let current): .conflict(code: code, message: message, current: current)
        case .unauthenticated: .unauthenticated
        case .rateLimited(let seconds): .rateLimited(retryAfterSeconds: seconds)
        case .rejected(let status, let error, let message, let field):
            .rejected(status: status, error: error, message: message, field: field)
        case .unreachable(let cause): .unreachable(cause)
        }
    }

    public var value: T? {
        if case .ok(let value) = self { return value }
        return nil
    }
}

/// Device tokens. `accessExpiresAt` is Unix ms.
public struct Tokens: Hashable, Sendable, Codable {
    public var access: String
    public var accessExpiresAt: Int64
    public var refresh: String

    public init(access: String, accessExpiresAt: Int64, refresh: String) {
        self.access = access
        self.accessExpiresAt = accessExpiresAt
        self.refresh = refresh
    }
}

/// Where the client keeps the session's tokens; implemented by the app with the Keychain.
public protocol Credentials: Sendable {
    func tokens() async -> Tokens?
    /// A refresh succeeded.
    func refreshed(_ tokens: Tokens) async
    /// The server refused the refresh token: the session is over. Local notes are not this protocol's business.
    func revoked() async
}

public struct AuthStatus: Decodable, Sendable {
    public var setupRequired: Bool
}

public struct Session: Decodable, Sendable, Hashable {
    public var id: String
    public var kind: String
    public var name: String
    public var createdAt: Int64
    public var lastUsedAt: Int64
}

public struct SignIn: Decodable, Sendable {
    public var session: Session?
    public var username: String
    public var accessToken: String
    public var accessExpiresAt: Int64
    public var refreshToken: String
    public var refreshExpiresAt: Int64

    public var tokens: Tokens { Tokens(access: accessToken, accessExpiresAt: accessExpiresAt, refresh: refreshToken) }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        session = try c.decodeIfPresent(Session.self, forKey: .session)
        username = try c.decodeIfPresent(String.self, forKey: .username) ?? ""
        accessToken = try c.decodeIfPresent(String.self, forKey: .accessToken) ?? ""
        accessExpiresAt = try c.decodeIfPresent(Int64.self, forKey: .accessExpiresAt) ?? 0
        refreshToken = try c.decodeIfPresent(String.self, forKey: .refreshToken) ?? ""
        refreshExpiresAt = try c.decodeIfPresent(Int64.self, forKey: .refreshExpiresAt) ?? 0
    }

    private enum CodingKeys: String, CodingKey {
        case session, username, accessToken, accessExpiresAt, refreshToken, refreshExpiresAt
    }
}

struct ErrorBody: Decodable {
    var error = ""
    var message = ""
    var field: String?

    init() {}

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        error = try c.decodeIfPresent(String.self, forKey: .error) ?? ""
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
        field = try c.decodeIfPresent(String.self, forKey: .field)
    }

    private enum CodingKeys: String, CodingKey { case error, message, field }
}

/// A note as the server has it. A tombstone has `deletedAt` set and empty title and content.
public struct RemoteNote: Codable, Sendable, Hashable {
    public var id: String
    public var title: String
    public var content: String
    public var revision: Int64
    public var changeSeq: Int64
    public var createdAt: Int64
    public var updatedAt: Int64
    public var deletedAt: Int64?
    public var conflictOf: String?
    /// A locked note's images as the server records them (§7.6); absent for a plain note.
    public var imageIds: [String]?

    public init(
        id: String, title: String = "", content: String = "", revision: Int64, changeSeq: Int64 = 0,
        createdAt: Int64 = 0, updatedAt: Int64 = 0, deletedAt: Int64? = nil, conflictOf: String? = nil,
        imageIds: [String]? = nil
    ) {
        self.id = id
        self.title = title
        self.content = content
        self.revision = revision
        self.changeSeq = changeSeq
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
        self.conflictOf = conflictOf
        self.imageIds = imageIds
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        content = try c.decodeIfPresent(String.self, forKey: .content) ?? ""
        revision = try c.decode(Int64.self, forKey: .revision)
        changeSeq = try c.decodeIfPresent(Int64.self, forKey: .changeSeq) ?? 0
        createdAt = try c.decodeIfPresent(Int64.self, forKey: .createdAt) ?? 0
        updatedAt = try c.decodeIfPresent(Int64.self, forKey: .updatedAt) ?? 0
        deletedAt = try c.decodeIfPresent(Int64.self, forKey: .deletedAt)
        conflictOf = try c.decodeIfPresent(String.self, forKey: .conflictOf)
        imageIds = try c.decodeIfPresent([String].self, forKey: .imageIds)
    }

    private enum CodingKeys: String, CodingKey {
        case id, title, content, revision, changeSeq, createdAt, updatedAt, deletedAt, conflictOf, imageIds
    }
}

/// The answer to a create, update or delete.
public struct MutationResult: Decodable, Sendable, Hashable {
    public var id: String
    public var status: String
    public var revision: Int64
    public var changeSeq: Int64
    public var updatedAt: Int64
    public var deletedAt: Int64?

    public init(id: String, status: String, revision: Int64, changeSeq: Int64, updatedAt: Int64, deletedAt: Int64?) {
        self.id = id
        self.status = status
        self.revision = revision
        self.changeSeq = changeSeq
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
    }
}

/// One page of the change feed: latest state per note, ascending changeSeq.
public struct SyncPage: Decodable, Sendable {
    public var changes: [RemoteNote]
    public var nextCursor: Int64
    public var hasMore: Bool
    public var generation: String

    public init(changes: [RemoteNote], nextCursor: Int64, hasMore: Bool, generation: String) {
        self.changes = changes
        self.nextCursor = nextCursor
        self.hasMore = hasMore
        self.generation = generation
    }
}

/// JSON as this package writes it: stable key order, "/" unescaped.
let jsonEncoder: JSONEncoder = {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return encoder
}()
