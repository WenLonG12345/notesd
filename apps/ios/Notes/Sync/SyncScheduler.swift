import BackgroundTasks
import Network
import NotesCore
import UIKit

/// When to sync (§5.5). In the foreground: on start and return, 5 s after the
/// last local edit, when the network comes back, and right after signing in,
/// retrying failures with backoff (2 s doubling to 5 min, with jitter). In the
/// background: an app refresh task when iOS allows one, and one sync to finish
/// unsent changes when the app leaves the screen. Correctness never depends on
/// any of these running.
@MainActor
final class SyncScheduler {
    static let taskSuffix = ".sync"

    private unowned let graph: AppGraph
    private var foreground = false
    private var started = false
    private var requested = false
    private var waiter: CheckedContinuation<Void, Never>?
    private var afterEdit: Task<Void, Never>?
    private let monitor = NWPathMonitor()

    private static let afterEditDelay: Duration = .seconds(5)
    private static let firstRetry: Double = 2
    private static let maxRetry: Double = 300

    init(graph: AppGraph) {
        self.graph = graph
    }

    static var taskIdentifier: String { (Bundle.main.bundleIdentifier ?? "notesd") + taskSuffix }

    /// Must run before the app finishes launching.
    static func registerBackgroundTask(_ run: @escaping @Sendable () async -> Bool) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier, using: nil) { task in
            let work = Task {
                let ok = await run()
                task.setTaskCompleted(success: ok)
            }
            task.expirationHandler = { work.cancel() }
            Task { @MainActor in scheduleBackgroundRefresh() }
        }
    }

    static func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    func start() {
        guard !started else { return }
        started = true
        Task { await loop() }
        Task {
            for await _ in graph.store.localEdits() {
                afterEdit?.cancel()
                afterEdit = Task {
                    try? await Task.sleep(for: Self.afterEditDelay)
                    if !Task.isCancelled { request() }
                }
            }
        }
        // Every sign-in.
        Task {
            var wasSignedIn = false
            for await account in graph.accounts.account.stream() {
                let signedIn = if case .signedIn = account { true } else { false }
                if signedIn && !wasSignedIn { request() }
                wasSignedIn = signedIn
            }
        }
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in self?.networkChanged(online: online) }
        }
        monitor.start(queue: DispatchQueue(label: "notesd.network"))
    }

    private var online = true

    private func networkChanged(online: Bool) {
        if online && !self.online { request() }
        self.online = online
    }

    func request() {
        if let waiter {
            self.waiter = nil
            waiter.resume()
        } else {
            requested = true
        }
    }

    func enteredForeground() {
        foreground = true
        request()
    }

    /// Leaving the screen: one more sync for changes not sent yet, while iOS lets it run.
    func enteredBackground() {
        foreground = false
        Self.scheduleBackgroundRefresh()
        let engine = graph.engine
        let store = graph.store
        Task {
            guard (try? await store.pendingNotes()) ?? 0 > 0 else { return }
            let id = UIApplication.shared.beginBackgroundTask(withName: "sync")
            _ = await engine.sync()
            UIApplication.shared.endBackgroundTask(id)
        }
    }

    private func next() async {
        if requested {
            requested = false
            return
        }
        await withCheckedContinuation { waiter = $0 }
    }

    /// Waits for a request, or `seconds`, whichever comes first.
    private func next(orAfter seconds: Double) async {
        let timer = Task {
            try? await Task.sleep(for: .seconds(seconds))
            if !Task.isCancelled { request() }
        }
        await next()
        timer.cancel()
    }

    private func loop() async {
        var backoff = Self.firstRetry
        while true {
            await next()
            while true {
                let outcome = await graph.engine.sync().outcome
                if outcome != .offline && outcome != .failed {
                    backoff = Self.firstRetry
                    break
                }
                // In the background the app refresh task retries; here, wait, unless something asks sooner.
                if !foreground { break }
                let wait = backoff + Double.random(in: 0..<(backoff / 2))
                backoff = min(backoff * 2, Self.maxRetry)
                await next(orAfter: wait)
            }
        }
    }
}
