import Foundation
import Synchronization

/// A value sent to every current listener, each with its own small buffer: a
/// slow listener misses old values rather than holding anyone up.
public final class Broadcast<T: Sendable>: Sendable {
    private let listeners = Mutex<[UUID: AsyncStream<T>.Continuation]>([:])
    private let buffer: Int

    public init(buffer: Int = 1) {
        self.buffer = buffer
    }

    public func stream() -> AsyncStream<T> {
        let (stream, continuation) = AsyncStream.makeStream(of: T.self, bufferingPolicy: .bufferingNewest(buffer))
        let id = UUID()
        listeners.withLock { $0[id] = continuation }
        continuation.onTermination = { [weak self] _ in
            _ = self?.listeners.withLock { $0.removeValue(forKey: id) }
        }
        return stream
    }

    public func send(_ value: T) {
        listeners.withLock { listeners in
            for continuation in listeners.values { continuation.yield(value) }
        }
    }
}
