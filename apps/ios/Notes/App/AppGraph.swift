import Foundation
import NotesCore
import Observation
import UIKit

/// The app's long-lived objects, wired by hand. One per process, owned by `NotesApp`.
@MainActor
@Observable
final class AppGraph {
    let database: Database
    let store: NoteStore
    let accounts: AccountStore
    let engine: SyncEngine
    let images: ImageStore
    let locks: LockSession
    let mailer: NoteMailer
    let email: EmailSetup
    @ObservationIgnored private(set) lazy var scheduler = SyncScheduler(graph: self)

    /// The account as the screens show it, kept in step with the store.
    private(set) var account: Account
    private(set) var syncStatus: SyncStatus = .notConnected
    private(set) var lockState: LockSession.State = .none

    /// What the list shows. Kept so the app reopens on it.
    var listFilter: NoteFilter? {
        didSet { Self.defaults.set(try? JSONEncoder().encode(listFilter), forKey: "listFilter") }
    }

    /// "Use without a server" was chosen: start at the list instead of onboarding.
    var onboardingDismissed: Bool {
        didSet { Self.defaults.set(onboardingDismissed, forKey: "onboardingDismissed") }
    }

    var appearance: Appearance {
        didSet { Self.defaults.set(appearance.rawValue, forKey: "appearance") }
    }

    /// Something sync did that the user should hear about, until they've seen it.
    var notice: String?

    /// The note about to open, read before its screen is pushed so it opens with its text.
    @ObservationIgnored private var preloaded: Note?

    /// Reads note `id` ahead of opening it.
    func preload(_ id: String) async {
        preloaded = try? await store.get(id)
    }

    /// The note `preload` read, if it was `id`.
    func opening(_ id: String) -> Note? {
        preloaded?.id == id ? preloaded : nil
    }

    private static let defaults = UserDefaults.standard

    /// UI tests start from nothing: a new database and Keychain item each launch, and no saved preferences.
    static let uiTesting = ProcessInfo.processInfo.arguments.contains("--ui-testing")

    init(
        databasePath: String = AppGraph.defaultDatabasePath(),
        accounts: AccountStore = AccountStore(
            keychain: Keychain(service: uiTesting ? "notesd.account.uitests" : "notesd.account"))
    ) {
        if Self.uiTesting {
            accounts.clear()
            for key in ["listFilter", "onboardingDismissed", "appearance", "openTags"] {
                Self.defaults.removeObject(forKey: key)
            }
        }
        database = Database(path: databasePath)
        store = NoteStore(db: database)
        self.accounts = accounts
        let dirs = Self.imageDirectories()
        images = ImageStore(
            db: database, pendingDir: dirs.pending, cacheDir: dirs.cache, api: { [accounts] in accounts.client() })
        engine = SyncEngine(db: database, api: { [accounts] in accounts.client() }, images: images)
        let locks = LockSession(keys: engine.lockKeys, api: { [accounts] in accounts.client() })
        self.locks = locks
        let loader = ImageLoader(store: images) { sealed in
            locks.key().flatMap { try? LockedNotes.decryptImage($0, sealed) }
        }
        ImageLoader.shared = loader
        mailer = NoteMailer(store: store, engine: engine, api: { [accounts] in accounts.client() })
        email = EmailSetup(api: { [accounts] in accounts.client() })
        account = accounts.account.value
        onboardingDismissed = Self.defaults.bool(forKey: "onboardingDismissed")
        appearance = Appearance(rawValue: Self.defaults.string(forKey: "appearance") ?? "") ?? .system
        listFilter =
            Self.defaults.data(forKey: "listFilter").flatMap { try? JSONDecoder().decode(NoteFilter?.self, from: $0) }
            ?? nil
        Task { [weak self, accounts] in
            for await account in accounts.account.stream() { self?.account = account }
        }
        Task { [weak self, engine, locks] in
            for await status in engine.status.stream() {
                self?.syncStatus = status
                // The keyring came with the cycle: a password changed or reset elsewhere locks here too.
                if case .synced = status { await locks.refresh() }
            }
        }
        Task { [weak self, locks] in
            for await state in locks.state.stream() {
                self?.lockState = state
                // Decrypted pictures go with the key.
                if state != .unlocked { loader.forgetLocked() }
            }
        }
        Task { await locks.refresh() }
        Task { [weak self, engine] in
            for await event in engine.events() { self?.notice = Self.describe(event) }
        }
    }

    /// The database lives in Application Support, readable after the first unlock so background sync can open it.
    nonisolated static func defaultDatabasePath() -> String {
        let fm = FileManager.default
        if ProcessInfo.processInfo.arguments.contains("--ui-testing") {
            return fm.temporaryDirectory.appending(path: "uitest-\(UUID().uuidString).db").path
        }
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(
            at: dir, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return dir.appending(path: "notes.db").path
    }

    /// Pictures waiting to upload live with the database (never purged); fetched ones in Caches, which iOS may clear.
    nonisolated static func imageDirectories() -> (pending: URL, cache: URL) {
        let fm = FileManager.default
        if ProcessInfo.processInfo.arguments.contains("--ui-testing") {
            let root = fm.temporaryDirectory.appending(path: "uitest-images-\(UUID().uuidString)")
            return (root.appending(path: "pending"), root.appending(path: "cache"))
        }
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return (support.appending(path: "images"), caches.appending(path: "images"))
    }

    /// How this phone appears in the server's list of signed-in devices.
    var deviceName: String { UIDevice.current.name }

    /// Ends the session on the server if it answers quickly, then forgets the
    /// tokens. Notes and the server address stay: signing in again resumes.
    func signOut() async {
        if let client = accounts.client() {
            _ = await withTimeout(seconds: 5) { await client.logout() }
        }
        accounts.signOut()
        email.forget()
        await locks.forget()
    }

    /// Back to a fresh install: no notes, no account, onboarding on next launch.
    func removeAllData() async {
        if case .signedIn = account, let client = accounts.client() {
            _ = await withTimeout(seconds: 5) { await client.logout() }
        }
        try? await store.wipe()
        try? await images.wipe()
        accounts.clear()
        email.forget()
        await locks.forget()
        listFilter = nil
        onboardingDismissed = false
    }

    private static func describe(_ event: SyncEvent) -> String {
        switch event {
        case .conflictedCopy(_, _, let title):
            "This note changed on another device too. Your version is kept as “\(title)”."
        case .recoveredCopy(_, let title):
            "A note you edited was deleted on another device. Your edits are kept as “\(title)”."
        case .restored(_, let title): "“\(title)” changed on another device, so it’s back."
        }
    }
}

enum Appearance: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "Automatic"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var style: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }
}

/// `body`'s result, or nil if it takes longer than `seconds`.
func withTimeout<T: Sendable>(seconds: Double, _ body: @escaping @Sendable () async -> T) async -> T? {
    await withTaskGroup(of: T?.self) { group in
        group.addTask { await body() }
        group.addTask {
            try? await Task.sleep(for: .seconds(seconds))
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}
