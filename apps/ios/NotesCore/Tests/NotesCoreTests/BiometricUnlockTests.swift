import Foundation
import Synchronization
import Testing

@testable import NotesCore

/// Keeps the key in memory, as the Keychain item would; matches unless told otherwise.
final class FakeBiometrics: Biometrics {
    private struct State {
        var kept: Data?
        var cancelNext = false
        var failNext = false
        var asked = 0
    }

    private let state = Mutex(State())

    var cancelNext: Bool {
        get { state.withLock { $0.cancelNext } }
        set { state.withLock { $0.cancelNext = newValue } }
    }

    var failNext: Bool {
        get { state.withLock { $0.failNext } }
        set { state.withLock { $0.failNext = newValue } }
    }

    /// How many times the biometric was asked for.
    var asked: Int { state.withLock { $0.asked } }

    /// Face ID enrolled again: what was kept can't be read.
    func invalidate() { state.withLock { $0.kept = nil } }

    func available() -> Bool { true }

    func seal(_ key: Data) async -> BiometricResult {
        state.withLock { s in
            s.asked += 1
            if s.cancelNext {
                s.cancelNext = false
                return .cancelled
            }
            if s.failNext {
                s.failNext = false
                return .failed
            }
            s.kept = key
            return .ok(Data())
        }
    }

    func open() async -> BiometricResult {
        state.withLock { s in
            s.asked += 1
            if s.cancelNext {
                s.cancelNext = false
                return .cancelled
            }
            guard let kept = s.kept else { return .invalidated }
            return .ok(kept)
        }
    }

    func forget() { state.withLock { $0.kept = nil } }
}

@MainActor
struct BiometricUnlockTests {
    let dir = FileManager.default.temporaryDirectory.appending(path: "notes-biometric-\(UUID().uuidString)")
    let keys: LockKeyStore
    let session: LockSession
    let biometrics = FakeBiometrics()
    let defaults: UserDefaults
    let created = Keyring.create("lock pass 123", iterations: 10_000)

    init() async throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        keys = LockKeyStore(db: Database(path: dir.appending(path: "notes.db").path))
        session = LockSession(keys: keys, api: { nil }, iterations: 10_000)
        defaults = UserDefaults(suiteName: "notes-biometric-\(UUID().uuidString)")!
        try await keys.replace([created.entry])
        await session.refresh()
    }

    private func unlock(answer: Bool = true) -> BiometricUnlock {
        BiometricUnlock(locks: session, biometrics: biometrics, defaults: defaults) { answer }
    }

    @Test func aDeviceKeptKeyUnlocksOnlyWhileThePasswordEntryItWasKeptAgainstStands() async throws {
        let bound = try #require(try await session.passwordEntry())
        #expect(await session.unlockWithKey(created.key, boundTo: bound))
        #expect(session.state.value == .unlocked)
        #expect(session.keyEntry() == bound)
        await session.lock()

        try await keys.replace([Keyring.create("another one", iterations: 10_000).entry])
        #expect(!(await session.unlockWithKey(created.key, boundTo: bound)))
        let now = try #require(try await session.passwordEntry())
        #expect(!(await session.unlockWithKey(Data(count: 5), boundTo: now)))
        #expect(session.state.value == .locked)
    }

    @Test func theFirstPasswordTypedOffersFaceIDWhichUnlocksNextTime() async throws {
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        await face.offer()?.value
        #expect(face.enabled)

        await session.lock()
        #expect(session.state.value == .locked)
        #expect(await face.unlock() == .done)
        #expect(session.state.value == .unlocked)
        #expect(session.key() == created.key)
        // On already: not offered again.
        #expect(face.offer() == nil)
    }

    @Test func notNowIsRemembered() async throws {
        let face = unlock(answer: false)
        #expect(await session.unlock("lock pass 123"))
        await face.offer()?.value
        #expect(!face.enabled)
        #expect(biometrics.asked == 0)
        #expect(face.offer() == nil)
    }

    @Test func dismissingTheFaceIDPromptIsRememberedToo() async throws {
        biometrics.cancelNext = true
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        await face.offer()?.value
        #expect(!face.enabled)
        #expect(face.offer() == nil)
    }

    @Test func aPromptThatFailedRatherThanBeingDeclinedIsOfferedAgain() async throws {
        biometrics.failNext = true
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        await face.offer()?.value
        #expect(!face.enabled)
        await face.offer()?.value
        #expect(face.enabled)
    }

    @Test func aNewPasswordElsewhereTurnsItOffAndItIsOfferedAgain() async throws {
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        #expect(await face.enable() == .done)
        await session.lock()
        try await keys.replace([Keyring.create("another one", iterations: 10_000).entry])

        #expect(await face.unlock() == .stale)
        #expect(!face.enabled)
        #expect(session.state.value != .unlocked)
        #expect(await session.unlock("another one"))
        await face.offer()?.value
        #expect(face.enabled)
    }

    @Test func faceIDEnrolledAgainIsStale() async throws {
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        #expect(await face.enable() == .done)
        await session.lock()
        biometrics.invalidate()
        #expect(await face.unlock() == .stale)
        #expect(!face.enabled)
        #expect(session.state.value == .locked)
    }

    @Test func turningItOnNeedsLockedNotesUnlocked() async throws {
        let face = unlock()
        #expect(await face.enable() == .needsUnlock)
        #expect(!face.enabled)
    }

    @Test func aPasswordChangedHereKeepsItOn() async throws {
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        #expect(await face.enable() == .done)
        // As LockSession.changePassword does: the same key under a new password entry.
        let rewrapped = try await session.passwordEntry()
        try await keys.replace([Keyring.passwordEntry(created.key, "new pass 456", iterations: 10_000)])
        #expect(await session.unlock("new pass 456"))
        face.passwordChanged(rewrapped: rewrapped)
        #expect(face.enabled)

        await session.lock()
        #expect(await face.unlock() == .done)
        #expect(session.key() == created.key)
    }

    @Test func aPasswordChangedAgainstAnOlderKeyringTurnsItOff() async throws {
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        #expect(await face.enable() == .done)
        let other = Keyring.create("another one", iterations: 10_000)
        try await keys.replace([other.entry])
        #expect(await session.unlock("another one"))
        face.passwordChanged(rewrapped: other.entry.wrapped)
        #expect(!face.enabled)
    }

    @Test func turnedOffInSettingsIsNotOfferedAgainButSigningOutClearsThat() async throws {
        let face = unlock()
        #expect(await session.unlock("lock pass 123"))
        #expect(await face.enable() == .done)
        face.turnOff()
        #expect(!face.enabled)
        #expect(face.offer() == nil)

        face.disable()
        await face.offer()?.value
        #expect(face.enabled)
    }

    @Test func theQuestionWaitsForAnAnswer() async throws {
        let face = BiometricUnlock(locks: session, biometrics: biometrics, defaults: defaults)
        #expect(await session.unlock("lock pass 123"))
        let offer = try #require(face.offer())
        while !face.asking { await Task.yield() }
        face.answer(true)
        await offer.value
        #expect(!face.asking)
        #expect(face.enabled)
    }
}
