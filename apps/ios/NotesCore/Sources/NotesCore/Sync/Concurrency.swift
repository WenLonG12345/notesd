import Foundation
import Synchronization

/// A first-come, first-served lock for async code: holders may suspend while
/// they hold it, and waiters wait without blocking a thread.
public actor AsyncLock {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init() {}

    public func lock() async {
        if !locked {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    public func unlock() {
        if waiters.isEmpty {
            locked = false
        } else {
            waiters.removeFirst().resume()
        }
    }

    public nonisolated func withLock<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        await lock()
        do {
            let value = try await body()
            await unlock()
            return value
        } catch {
            await unlock()
            throw error
        }
    }
}

/// A current value that listeners can follow: each stream starts with the
/// value as it is, then every change.
public final class StateCell<T: Sendable>: Sendable {
    private struct State {
        var value: T
        var listeners: [UUID: AsyncStream<T>.Continuation] = [:]
    }

    private let state: Mutex<State>

    public init(_ value: T) {
        state = Mutex(State(value: value))
    }

    public var value: T { state.withLock { $0.value } }

    public func set(_ value: T) {
        state.withLock { state in
            state.value = value
            for listener in state.listeners.values { listener.yield(value) }
        }
    }

    public func stream() -> AsyncStream<T> {
        let (stream, continuation) = AsyncStream.makeStream(of: T.self, bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        state.withLock { state in
            continuation.yield(state.value)
            state.listeners[id] = continuation
        }
        continuation.onTermination = { [weak self] _ in
            _ = self?.state.withLock { $0.listeners.removeValue(forKey: id) }
        }
        return stream
    }
}
