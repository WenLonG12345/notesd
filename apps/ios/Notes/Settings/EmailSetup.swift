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

    init(api: @escaping () -> ApiClient?) {
        self.api = api
    }

    /// Re-reads them; they can change from another device. Failures keep what was known.
    func refresh() async {
        guard let client = api() else { return }
        if let value = await client.emailSettings().value { settings = value }
    }

    /// A nil `apiKey` keeps the saved one.
    func save(from: String, apiKey: String?) async -> ApiResult<EmailSettings> {
        guard let client = api() else { return .unauthenticated }
        let result = await client.saveEmailSettings(from: from, apiKey: apiKey)
        if let value = result.value { settings = value }
        return result
    }

    func remove() async -> ApiResult<Void> {
        guard let client = api() else { return .unauthenticated }
        let result = await client.deleteEmailSettings()
        if case .ok = result { settings = EmailSettings() }
        return result
    }

    func forget() {
        settings = nil
    }
}
