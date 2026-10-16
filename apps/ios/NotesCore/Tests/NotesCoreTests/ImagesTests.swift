import Foundation
import Synchronization
import Testing

@testable import NotesCore

/// Images (§7.6) against a real notesd: uploaded before their notes, fetched on first view.
@Suite(.serialized, .timeLimit(.minutes(2)))
final class ImagesTests {
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

    static let png = Data(
        base64Encoded:
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")!
    private var png: Data { Self.png }

    private static func fetched(_ c: TestClient, _ id: String) async -> Bool {
        if case .ok = await c.api.getImage(id) { return true }
        return false
    }

    @Test func referencesAreParsedAndWrittenAsOnTheWeb() {
        let id = String(repeating: "ab", count: 32)
        let ref = ImageRef(id: id, width: 1600, height: 1200)
        #expect(ref.markdown() == "![](notesd:image/\(id)?w=1600&h=1200)")
        #expect(ref.markdown(alt: "a *cat* [1]") == "![a \\*cat\\* \\[1\\]](notesd:image/\(id)?w=1600&h=1200)")
        #expect(ImageRef.parse(ref.markdown(alt: "a *cat*")) == ref)
        #expect(ImageRef.alt(ref.markdown(alt: "a *cat*")) == "a *cat*")
        #expect(ImageRef.ids("x ![](notesd:image/\(id)?w=1&h=1) ![b](notesd:image/\(id)?w=2&h=2)") == [id])
        for other in [
            "![a](cat.png)",
            "![a](notesd:image/\(id))",
            "![a](notesd:image/\(id.uppercased())?w=1&h=1)",
            "![a](notesd:image/\(id)?w=0&h=1)",
            "`![a](notesd:image/\(id)?w=1&h=1)`",
        ] {
            #expect(ImageRef.parse(other) == nil, "\(other)")
        }
    }

    @Test func anImageAddedOfflineUploadsBeforeItsNoteAndOpensOnAnotherDevice() async throws {
        let a = try await client()
        let b = try await client()
        a.offline = true
        let id = try await a.images.add(png, type: "image/png")
        try await a.save("n", "Trip", ImageRef(id: id, width: 1, height: 1).markdown() + "\n")
        #expect(await a.sync() == .offline)
        #expect(try await a.images.pending().count == 1)
        // Still on this device while it waits.
        #expect(await a.images.load(id)?.bytes == png)

        a.offline = false
        let uploadedFirst = Counter()
        a.beforeNext(
            { $0.path.hasPrefix("/api/v1/notes") },
            then: {
                if await Self.fetched(b, id) { uploadedFirst.increment() }
            })
        #expect(await a.sync() == .done)
        #expect(uploadedFirst.count == 1, "the image reached the server before the note")
        #expect(try await a.images.pending().isEmpty)
        #expect(a.images.has(id), "kept in the cache after upload")

        #expect(await b.sync() == .done)
        #expect(!b.images.has(id))
        let file = try #require(await b.images.load(id))
        #expect(file.type == "image/png")
        #expect(file.bytes == png)
        b.offline = true
        #expect(await b.images.load(id)?.bytes == png, "cached for offline")
    }

    @Test func anImageAddedWhileANoteGoesUpStillReachesTheServerBeforeItsOwnNote() async throws {
        let a = try await client()
        let b = try await client()
        try await a.save("first", "First", "text\n")
        let added = Mutex<String?>(nil)
        // While the first note's request is out, a picture is added to another note.
        a.beforeNext(
            { $0.path == "/api/v1/notes" },
            then: {
                let id = try! await a.images.add(Self.png, type: "image/png")
                _ = try! await a.save("second", "Second", ImageRef(id: id, width: 1, height: 1).markdown() + "\n")
                added.withLock { $0 = id }
            })
        let uploadedFirst = Counter()
        a.beforeNext(
            { $0.path == "/api/v1/notes" && added.withLock { $0 } != nil },
            then: {
                if await Self.fetched(b, added.withLock { $0! }) { uploadedFirst.increment() }
            })
        #expect(await a.sync() == .done)
        #expect(uploadedFirst.count == 1, "the image reached the server before the note showing it")
    }

    @Test func aLostUploadResponseIsRetriedHarmlessly() async throws {
        let a = try await client()
        let id = try await a.images.add(png, type: "image/png")
        a.loseNextResponse { $0.path.hasPrefix("/api/v1/images") }
        #expect(await a.sync() == .offline)
        #expect(await a.sync() == .done)
        #expect(try await a.images.pending().isEmpty)
        #expect(await try client().images.load(id) != nil)
    }

    @Test func pendingImagesSurviveARestart() async throws {
        let a = try await client()
        a.offline = true
        let id = try await a.images.add(png, type: "image/png")
        await a.restart()
        #expect(try await a.images.pending() == [PendingImage(id: id, type: "image/png")])
        a.offline = false
        #expect(await a.sync() == .done)
        #expect(await try client().images.load(id) != nil)
    }

    @Test func sniffingKnowsTheImageTypesTheServerAccepts() {
        #expect(ImageStore.sniff(png) == "image/png")
        #expect(ImageStore.sniff(Data([0xff, 0xd8, 0xff, 0])) == "image/jpeg")
        #expect(ImageStore.sniff(Data("GIF89a".utf8)) == "image/gif")
        #expect(ImageStore.sniff(Data("RIFF\0\0\0\0WEBPVP8 ".utf8)) == "image/webp")
        #expect(ImageStore.sniff(Data("<svg".utf8)) == nil)
    }
}
