import Foundation
import NotesCore
import Observation

/// Server address → (setup token → new account, when the server has none) or
/// sign in. With `lockedServer` it's "sign in again": straight to sign-in on
/// the server these notes belong to. A port of Android's OnboardingViewModel.
@MainActor
@Observable
final class OnboardingModel {
    enum Step { case server, token, account, signIn }
    enum Field { case server, token, username, password, confirm }

    private(set) var step: Step
    var server: String
    var token = ""
    var username: String
    var password = ""
    var confirm = ""
    private(set) var busy = false
    /// About the whole step, shown above its button.
    private(set) var error: String?
    private(set) var fieldErrors: [Field: String] = [:]
    private(set) var done = false

    let lockedServer: String?
    private let accounts: AccountStore
    private let deviceName: String
    private var url: URL?

    init(accounts: AccountStore, deviceName: String, lockedServer: String? = nil, username: String = "") {
        self.accounts = accounts
        self.deviceName = deviceName
        self.lockedServer = lockedServer
        self.username = username
        server = lockedServer ?? ""
        step = lockedServer == nil ? .server : .signIn
        url = lockedServer.flatMap(URL.init(string:))
    }

    func clearErrors() {
        error = nil
        fieldErrors = [:]
    }

    /// Back within the flow. False when there's no earlier step to go to.
    func back() -> Bool {
        let previous: Step? =
            switch step {
            case .server: nil
            case .token: .server
            case .account: .token
            case .signIn: lockedServer == nil ? .server : nil
            }
        guard let previous else { return false }
        step = previous
        password = ""
        confirm = ""
        clearErrors()
        return true
    }

    func submitServer() {
        run {
            guard case .valid(let url) = ServerAddress.parse(self.server) else {
                if case .invalid(let message) = ServerAddress.parse(self.server) { self.fail([.server: message]) }
                return
            }
            let api = ApiClient(baseURL: url)
            switch await api.health() {
            case .ok: break
            case .unreachable:
                return self.fail(
                    message: "Can’t reach a server at \(url.host() ?? self.server). Check the address, and that this "
                        + "iPhone is on the same network if the server is at home.")
            case let other: return self.fail(message: Self.message(other))
            }
            switch await api.status() {
            case .ok(let status):
                self.url = url
                self.step = status.setupRequired ? .token : .signIn
            case let other: self.fail(message: Self.message(other))
            }
        }
    }

    func submitToken() {
        if token.trimmed.isEmpty { return fail([.token: "Paste the setup token from the server log."]) }
        step = .account
    }

    func submitAccount() {
        run {
            var errors: [Field: String] = [:]
            if self.username.trimmed.isEmpty { errors[.username] = "Choose a username." }
            if self.password.count < 8 {
                errors[.password] = "Use at least 8 characters."
            } else if self.confirm != self.password {
                errors[.confirm] = "The passwords don’t match."
            }
            if !errors.isEmpty { return self.fail(errors) }
            guard let url = self.url else { return }
            let api = ApiClient(baseURL: url)
            let username = self.username.trimmed
            switch await api.setup(setupToken: self.token.trimmed, username: username, password: self.password) {
            case .ok: break
            case .rejected(_, "invalid_setup_token", _, _):
                self.step = .token
                return self.fail([.token: "That token didn’t match. Copy it from the server log again."])
            case .rejected(_, "invalid_request", let message, let field?):
                let message = message.prefix(1).uppercased() + message.dropFirst() + "."
                return self.fail([field == "username" ? .username : .password: message])
            case .conflict:
                self.step = .signIn
                self.password = ""
                self.confirm = ""
                return self.fail(message: "This server already has an account. Sign in instead.")
            case let other:
                return self.fail(message: Self.message(other))
            }
            switch await api.login(username: username, password: self.password, deviceName: self.deviceName) {
            case .ok(let signIn): self.finish(signIn)
            case let other:
                // The account exists now; plain sign-in takes it from here.
                self.step = .signIn
                self.password = ""
                self.confirm = ""
                self.fail(message: Self.message(other))
            }
        }
    }

    func submitSignIn() {
        run {
            if self.username.trimmed.isEmpty || self.password.isEmpty {
                return self.fail(message: "Enter your username and password.")
            }
            guard let url = self.url else { return }
            let result = await ApiClient(baseURL: url)
                .login(username: self.username.trimmed, password: self.password, deviceName: self.deviceName)
            switch result {
            case .ok(let signIn): self.finish(signIn)
            case .rejected(_, "invalid_credentials", _, _): self.fail(message: "Wrong username or password.")
            case let other: self.fail(message: Self.message(other))
            }
        }
    }

    private func finish(_ signIn: SignIn) {
        guard let url else { return }
        accounts.signedIn(server: url.absoluteString, signIn)
        password = ""
        confirm = ""
        done = true
    }

    private func run(_ block: @escaping @MainActor () async -> Void) {
        if busy { return }
        busy = true
        clearErrors()
        Task {
            await block()
            busy = false
        }
    }

    private func fail(_ fields: [Field: String] = [:], message: String? = nil) {
        error = message
        fieldErrors = fields
    }

    private static func message<T>(_ r: ApiResult<T>) -> String {
        switch r {
        case .unreachable: "Can’t reach the server. Check your connection and try again."
        case .rateLimited: "Too many attempts. Wait a moment and try again."
        case .rejected(let status, let error, _, _) where error == ApiClient.unexpectedResponse || status == 404:
            "There’s no notes server at that address."
        case .rejected(let status, _, let message, _):
            message.isEmpty ? "The server refused that (error \(status))." : message
        case .conflict(_, let message, _): message
        default: "Something went wrong. Try again."
        }
    }
}
