import Foundation
import Testing

@testable import NotesCore

/// Locked notes (§10.1) through a real notesd: the keyring syncs, and sync merges envelopes without the key.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class LockedSyncTests {
    let server: TestServer
    private var clients: [TestClient] = []

    init() async throws {
        server = try await TestServer.start()
    }

    private func client() async throws -> TestClient {
        let c = TestClient(server)
        clients.append(c)
        return try await c.signIn()
    }

    private func syncAll(_ cs: TestClient...) async {
        for _ in 0..<2 {
            for c in cs { #expect(await c.sync() == .done) }
        }
    }

    /// Device `a` creates the lock password; the key it holds.
    private func setUp(_ a: TestClient) async throws -> Data {
        let created = Keyring.create("lock pass 123", iterations: 10_000)
        #expect(await a.api.putLockKey(created.entry, verifier: created.verifier).value != nil)
        return created.key
    }

    @Test func theKeyringSyncsSoAnotherDeviceUnlocksOffline() async throws {
        let a = try await client()
        let b = try await client()
        let key = try await setUp(a)
        try await a.save("n", "Bank", LockedNotes.encrypt(key, "PIN 1234\n"))
        await syncAll(a, b)

        b.offline = true
        let unlocked = try Keyring.unlock(try await b.engine.lockKeys.keys(), "lock pass 123")
        #expect(try LockedNotes.decrypt(unlocked, try #require(try await b.note("n")).content) == "PIN 1234\n")
        let summary = try #require(try await b.notes.list().first)
        #expect(summary.locked)
        #expect(summary.preview == "")
        #expect(try await b.notes.search("1234").isEmpty)
    }

    @Test func aLockedNoteEditedOnBothDevicesKeepsBothStillLocked() async throws {
        let a = try await client()
        let b = try await client()
        let key = try await setUp(a)
        try await a.save("n", "Bank", LockedNotes.encrypt(key, "base\n"))
        await syncAll(a, b)

        try await a.save("n", "Bank", LockedNotes.encrypt(key, "from a\n"))
        try await b.save("n", "Bank", LockedNotes.encrypt(key, "from b\n"))
        await syncAll(a, b)

        // Sync merges without the key: both bodies survive, each one still encrypted.
        var bodies = Set<String>()
        for n in try await a.live() { bodies.insert(try LockedNotes.decrypt(key, n.content)) }
        #expect(bodies == ["from a\n", "from b\n"])
        #expect(try await a.live().allSatisfy { LockedNotes.isLocked($0.content) })
        #expect(try await a.live() == b.live())
    }

    @Test func aTitleEditMergesWithALockedBodyEdit() async throws {
        let a = try await client()
        let b = try await client()
        let key = try await setUp(a)
        try await a.save("n", "Bank", LockedNotes.encrypt(key, "base\n"))
        await syncAll(a, b)

        try await a.save("n", "Bank (old)", try #require(try await a.note("n")).content)
        let body = LockedNotes.encrypt(key, "new body\n")
        try await b.save("n", "Bank", body)
        await syncAll(a, b)

        let merged = try #require(try await a.note("n"))
        #expect(merged.title == "Bank (old)")
        #expect(merged.content == body)
        #expect(try await a.live().count == 1)
    }

    @Test func aKeyringFetchedBeforeAChangeMadeOnThisDeviceDoesntOverwriteIt() async throws {
        let a = try await client()
        _ = try await setUp(a)
        await syncAll(a)
        let server = try await a.engine.lockKeys.keys()
        #expect(!server.isEmpty)
        // A setup or new password on this device lands while the cycle's fetch is out.
        let local = Keyring.create("newer", iterations: 10_000).entry
        let store = a.engine.lockKeys
        a.beforeNext({ $0.method == "GET" && $0.path == "/api/v1/locks" }, then: { try? await store.replace([local]) })
        #expect(await a.sync() == .done)
        #expect(try await a.engine.lockKeys.keys() == [local])

        // The next cycle takes the server's again.
        #expect(await a.sync() == .done)
        #expect(try await a.engine.lockKeys.keys() == server)
    }

    @Test func setupNewPasswordAndResetGoThroughTheServer() async throws {
        let a = try await client()
        let b = try await client()
        let sa = LockSession(keys: a.engine.lockKeys, api: { a.api }, iterations: 10_000)
        let sb = LockSession(keys: b.engine.lockKeys, api: { b.api }, iterations: 10_000)
        #expect(await sa.setup("lock pass 123") == .done)
        #expect(sa.state.value == .unlocked)
        let key = try #require(sa.key())

        // b takes the keyring on its next sync and unlocks with the same key.
        #expect(await b.sync() == .done)
        await sb.refresh()
        #expect(sb.state.value == .locked)
        #expect(await sb.unlock("lock pass 123"))
        #expect(sb.key() == key)

        #expect(await sa.changePassword(current: "nope", next: "new pass 456") == .wrongPassword)
        #expect(await sa.changePassword(current: "lock pass 123", next: "new pass 456") == .done)
        #expect(sa.key() == key, "the key stays; only its wrapping changes")
        #expect(await b.sync() == .done)
        await sb.refresh()
        #expect(sb.state.value == .locked, "a new password from elsewhere locks")
        #expect(!(await sb.unlock("lock pass 123")))
        #expect(await sb.unlock("new pass 456"))

        #expect(await sb.reset(accountPassword: "wrong") == .wrongPassword)
        #expect(await sb.reset(accountPassword: TestServer.password) == .done)
        #expect(sb.state.value == .none)
        #expect(await a.sync() == .done)
        await sa.refresh()
        #expect(sa.state.value == .none)
        #expect(sa.key() == nil)
    }
}
