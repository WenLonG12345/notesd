import Foundation
import Testing

@testable import NotesCore

/// Emailing through a real notesd, with Resend faked.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class NoteMailerTests {
    let resend: FakeResend
    let server: TestServer
    private var clients: [TestClient] = []

    init() async throws {
        resend = try FakeResend()
        server = try await TestServer.start(resendURL: resend.url)
    }

    private func client() async throws -> TestClient {
        let c = TestClient(server)
        clients.append(c)
        return try await c.signIn()
    }

    private func mailer(_ c: TestClient) -> NoteMailer {
        NoteMailer(store: c.notes, engine: c.engine, api: { c.api })
    }

    private func configure(_ c: TestClient) async throws {
        let saved = await c.api.saveEmailSettings(from: "Me <notes@example.com>", apiKey: "re_123456789")
        #expect(saved.value?.configured == true)
    }

    /// The plain-text part of the last email notesd sent.
    private func sentText() throws -> String {
        let body = try #require(resend.bodies.last)
        let object = try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any]
        return try #require(object?["text"] as? String)
    }

    @Test func sendsTheTextAsItIsHereSyncingItFirst() async throws {
        let a = try await client()
        try await configure(a)
        #expect(await a.api.emailSettings().value?.keyHint == "6789")
        try await a.save("n", "Plans", "Bike\n")
        resend.accepted()

        #expect(await mailer(a).send("n", to: ["sam@example.org"]) == .sent)
        #expect(try sentText() == "# Plans\n\nBike\n")
        #expect(try await a.notes.pendingNotes() == 0)
    }

    @Test func aChangeFromAnotherDeviceIsSyncedInThenSent() async throws {
        let a = try await client()
        let b = try await client()
        try await configure(a)
        try await a.save("n", "Plans", "Bike\n")
        #expect(await a.sync() == .done)
        #expect(await b.sync() == .done)
        try await b.save("n", "Plans", "Bike and park\n")
        #expect(await b.sync() == .done)
        resend.accepted()

        #expect(await mailer(a).send("n", to: ["sam@example.org"]) == .sent)
        #expect(try sentText() == "# Plans\n\nBike and park\n")
    }

    @Test func offlineNothingIsSent() async throws {
        let a = try await client()
        try await configure(a)
        try await a.save("n", "Plans", "Bike\n")
        a.offline = true
        #expect(await mailer(a).send("n", to: ["sam@example.org"]) == .offline)
        #expect(resend.bodies.isEmpty)
    }

    @Test func refusalsSayWhy() async throws {
        let a = try await client()
        try await a.save("n", "Plans", "Bike\n")
        #expect(
            await mailer(a).send("n", to: ["sam@example.org"])
                == .failed("Add a Resend API key in Settings to send email."))

        try await configure(a)
        #expect(await mailer(a).send("n", to: ["nope"]) == .failed("Nope isn't an email address.", field: "to"))
        resend.enqueue(
            403, #"{"statusCode":403,"name":"validation_error","message":"The example.com domain is not verified."}"#)
        #expect(
            await mailer(a).send("n", to: ["sam@example.org"])
                == .failed("Resend didn't send it: The example.com domain is not verified."))
    }

    @Test func aLockedNoteIsRefused() async throws {
        let a = try await client()
        try await configure(a)
        try await a.save("n", "Plans", LockedNotes.prefix + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        let outcome = await mailer(a).send("n", to: ["sam@example.org"])
        guard case .failed = outcome else {
            Issue.record("sent a locked note: \(outcome)")
            return
        }
        #expect(resend.bodies.isEmpty)
    }
}
