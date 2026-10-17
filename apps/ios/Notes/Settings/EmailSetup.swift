import NotesCore
import Observation

/// The server's email settings as last seen: whether a note's menu offers
/// Email, and what Settings shows. Nil while unknown (never connected, not
/// loaded yet, signed out).
@MainActor
@Observable
final class EmailSetup {
    private(set) var settings: EmailSettings?
    @ObservationIgnored private let api: () -> ApiClient?
    /// Moves on with each `forget`: an answer to a request made before it is for an account that's gone.
    @ObservationIgnored private var generation = 0

    init(api: @escaping () -> ApiClient?) {
        self.api = api
    }

    /// Re-reads them; they can change from another device. Failures keep what was known.
    func refresh() async {
        guard let client = api() else { return }
        let asked = generation
        if let value = await client.emailSettings().value, asked == generation { settings = value }
    }

    /// A nil `apiKey` keeps the saved one.
    func save(from: String, apiKey: String?) async -> ApiResult<EmailSettings> {
        guard let client = api() else { return .unauthenticated }
        let asked = generation
        let result = await client.saveEmailSettings(from: from, apiKey: apiKey)
        if let value = result.value, asked == generation { settings = value }
        return result
    }

    func remove() async -> ApiResult<Void> {
        guard let client = api() else { return .unauthenticated }
        let asked = generation
        let result = await client.deleteEmailSettings()
        if case .ok = result, asked == generation { settings = EmailSettings() }
        return result
    }

    func forget() {
        generation += 1
        settings = nil
    }
}
