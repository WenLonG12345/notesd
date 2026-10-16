import Foundation

/// How a cycle ended; the scheduler retries `offline` and `failed` with backoff.
public enum SyncOutcome: Hashable, Sendable { case done, notConnected, offline, signedOut, failed }

public enum SyncStatus: Hashable, Sendable {
    case notConnected
    case syncing
    case synced(at: Int64)
    case offline
    case signedOut
    case failed(String)
}

/// One sync cycle's outcome and what it told the user.
public struct SyncReport: Sendable {
    public var outcome: SyncOutcome
    public var events: [SyncEvent]
}

/// One sync cycle: push the outbox, pull the feed, then push again whatever
/// the pull queued (conflicted and recovered copies). One cycle at a time;
/// calling `sync()` while one runs waits for it and runs another. Decisions use
/// the server's revisions and cursor only, never the device clock.
public final class SyncEngine: Sendable {
    public let store: SyncStore
    private let api: @Sendable () async -> ApiClient?
    private let pageSize: Int
    private let lock = AsyncLock()
    private let eventFeed = Broadcast<SyncEvent>(buffer: 16)

    public let status = StateCell<SyncStatus>(.notConnected)

    public init(
        db: Database, api: @escaping @Sendable () async -> ApiClient?, pageSize: Int = 100,
        clock: @escaping Clock = systemClock
    ) {
        store = SyncStore(db: db, clock: clock)
        self.api = api
        self.pageSize = pageSize
    }

    /// What sync did that the user should hear about, as it happens.
    public func events() -> AsyncStream<SyncEvent> { eventFeed.stream() }

    @discardableResult
    public func sync() async -> SyncReport {
        await lock.withLock {
            guard let client = await self.api() else {
                self.status.set(.notConnected)
                return SyncReport(outcome: .notConnected, events: [])
            }
            self.status.set(.syncing)
            var events: [SyncEvent] = []
            var stop: Stop?
            do {
                stop = try await self.cycle(client, &events)
            } catch {
                stop = Stop(outcome: .failed, message: "This device's notes couldn't be updated (\(error)).")
            }
            try? await self.store.finished(error: stop?.message)
            let outcome = stop?.outcome ?? .done
            switch outcome {
            case .done: self.status.set(.synced(at: (try? await self.store.state().lastSyncAt) ?? 0))
            case .offline: self.status.set(.offline)
            case .signedOut: self.status.set(.signedOut)
            case .notConnected: self.status.set(.notConnected)
            case .failed: self.status.set(.failed(stop?.message ?? "Sync failed"))
            }
            return SyncReport(outcome: outcome, events: events)
        }
    }

    private struct Stop {
        var outcome: SyncOutcome
        var message: String?
    }

    private func cycle(_ api: ApiClient, _ events: inout [SyncEvent]) async throws -> Stop? {
        if let stop = try await push(api, &events) { return stop }
        if let stop = try await pull(api, &events) { return stop }
        return try await store.nextOperation() != nil ? try await push(api, &events) : nil
    }

    private func push(_ api: ApiClient, _ events: inout [SyncEvent]) async throws -> Stop? {
        while true {
            guard let next = try await store.nextOperation() else { return nil }
            guard let op = try await store.prepare(next) else { continue }
            switch await send(api, op) {
            case .ok(let result):
                try await store.sent(op, result)
            case .conflict(_, let message, let current):
                if let current, let note = try? JSONDecoder().decode(RemoteNote.self, from: current) {
                    emit(try await store.conflict(op, current: note), into: &events)
                } else {
                    try await store.rejected(op, message: message)
                }
            case .rejected(let status, let error, let message, _):
                if status >= 500 { return Stop(outcome: .failed, message: "The server had a problem (\(status)).") }
                try await store.rejected(op, message: message.isEmpty ? error : message)
            case let other:
                return stop(other)
            }
        }
    }

    private func send(_ api: ApiClient, _ op: Operation) async -> ApiResult<MutationResult> {
        let p = op.payload
        switch op.type {
        case createOp:
            return await api.createNote(
                NoteRequest(
                    operationId: op.operationId, id: op.noteId, title: p.title, content: p.content,
                    createdAt: p.createdAt, updatedAt: p.updatedAt, conflictOf: p.conflictOf, imageIds: p.imageIds))
        case updateOp:
            return await api.updateNote(
                op.noteId,
                NoteRequest(
                    operationId: op.operationId, baseRevision: op.baseRevision, title: p.title, content: p.content,
                    updatedAt: p.updatedAt, imageIds: p.imageIds))
        default:
            return await api.deleteNote(
                op.noteId, NoteRequest(operationId: op.operationId, baseRevision: op.baseRevision))
        }
    }

    private func pull(_ api: ApiClient, _ events: inout [SyncEvent]) async throws -> Stop? {
        var restarted = false
        while true {
            let state = try await store.state()
            switch await api.sync(since: state.cursor, limit: pageSize, generation: state.generation) {
            case .ok(let page):
                emit(try await store.applyPage(page), into: &events)
                if !page.hasMore { return nil }
            case .conflict(let code, let message, _) where code == "generation_mismatch" || code == "cursor_ahead":
                // Restored from a backup. Once per cycle: a second time means something else is wrong.
                if restarted { return Stop(outcome: .failed, message: message) }
                restarted = true
                try await store.startFullSync()
            case .rejected(let status, let error, let message, _) where status < 500:
                return Stop(outcome: .failed, message: message.isEmpty ? error : message)
            case let other:
                return stop(other)
            }
        }
    }

    private func stop<T>(_ result: ApiResult<T>) -> Stop {
        switch result {
        case .unauthenticated: Stop(outcome: .signedOut)
        case .unreachable, .rateLimited: Stop(outcome: .offline)
        case .rejected(let status, _, _, _): Stop(outcome: .failed, message: "The server had a problem (\(status)).")
        case .conflict(_, let message, _): Stop(outcome: .failed, message: message)
        case .ok: preconditionFailure("not a failure")
        }
    }

    private func emit(_ new: [SyncEvent], into events: inout [SyncEvent]) {
        for event in new {
            events.append(event)
            eventFeed.send(event)
        }
    }
}
