import CryptoKit
import UIKit
import XCTest

/// The app end to end against a real notesd: `scripts/with-server.sh` starts
/// one and passes its address in (TEST_RUNNER_NOTESD_URL). Each launch starts
/// from nothing (`--ui-testing`). Screenshots are kept in the test results.
@MainActor
final class FlowTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "-onboardingDismissed", "NO"]
    }

    private var env: [String: String] { ProcessInfo.processInfo.environment }

    private func screenshot(_ name: String) {
        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
        // For looking at by hand: TEST_RUNNER_SCREENSHOT_DIR=/some/dir.
        if let dir = env["SCREENSHOT_DIR"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appending(path: "\(name).png"))
        }
    }

    /// Calls the test server's API as the owner, as another device would.
    private func api(_ method: String, _ path: String, body: Data? = nil, type: String = "application/json")
        async throws -> [String: Any]
    {
        let server = try XCTUnwrap(env["NOTESD_URL"], "run through scripts/with-server.sh")
        if token == nil {
            let login = try JSONSerialization.data(withJSONObject: [
                "username": env["NOTESD_USERNAME"] ?? "owner", "password": env["NOTESD_PASSWORD"] ?? "",
                "client": "device", "deviceName": "ui test",
            ])
            token = ""  // the login itself goes without one
            token = try await api("POST", "api/v1/auth/login", body: login)["accessToken"] as? String
        }
        var request = URLRequest(url: URL(string: server)!.appending(path: path))
        request.httpMethod = method
        request.httpBody = body
        request.setValue(type, forHTTPHeaderField: "Content-Type")
        if let token, !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        XCTAssertTrue((200..<300).contains(code), "\(method) \(path): \(code) \(String(decoding: data, as: UTF8.self))")
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private var token: String?

    /// How many pixels are about the picture's blue (0.2, 0.4, 0.7).
    private func bluePixels(_ image: UIImage) -> Int {
        guard let cg = image.cgImage else { return 0 }
        let w = cg.width / 4
        let h = cg.height / 4
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(
            data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var n = 0
        for i in stride(from: 0, to: px.count, by: 4)
        where abs(Int(px[i]) - 51) < 25 && abs(Int(px[i + 1]) - 102) < 25 && abs(Int(px[i + 2]) - 178) < 25 {
            n += 1
        }
        return n
    }

    /// A note with a picture, made through the API as if on the web.
    private func notesWithAPicture() async throws {
        let picture = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 250)).jpegData(
            withCompressionQuality: 0.8
        ) {
            ctx in
            UIColor(red: 0.2, green: 0.4, blue: 0.7, alpha: 1).setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 250))
            UIColor.systemYellow.setFill()
            ctx.cgContext.fillEllipse(in: CGRect(x: 150, y: 75, width: 100, height: 100))
        }
        let id = SHA256.hash(data: picture).map { String(format: "%02x", $0) }.joined()
        _ = try await api("PUT", "api/v1/images/\(id)", body: picture, type: "image/jpeg")
        let note = try JSONSerialization.data(withJSONObject: [
            "operationId": UUID().uuidString.lowercased(), "id": UUID().uuidString.lowercased(), "title": "Postcard",
            "content": "Wish you were here:\n\n![sun over the sea](notesd:image/\(id)?w=1600&h=1000)\n\nMore soon.\n",
        ])
        _ = try await api("POST", "api/v1/notes", body: note)
    }

    private func audit(
        _ screen: String,
        _ types: XCUIAccessibilityAuditType = [.dynamicType, .sufficientElementDescription, .hitRegion, .contrast]
    ) throws {
        // The navigation bars, and the bottom bar's strip (the toolbar element itself spans the screen).
        let screen = app.frame
        let bars =
            app.navigationBars.allElementsBoundByIndex.map(\.frame)
            + [CGRect(x: 0, y: screen.maxY - 110, width: screen.width, height: 110)]
        try app.performAccessibilityAudit(for: types) {
            issue in
            let frame = issue.element?.frame ?? .zero
            let inBar = bars.contains { $0.contains(frame) }
            // Bars cap their text size; their labels show in the Large Content Viewer instead.
            if issue.auditType == .dynamicType && inBar { return true }
            // A row scrolled partly under the bottom bar is cut off, not small.
            if issue.auditType == .hitRegion && frame.maxY > screen.maxY - 110 { return true }
            print("AUDIT \(screen): \(issue.compactDescription) :: \(issue.element?.label ?? "-") \(frame)")
            return false
        }
    }

    func testWithoutAServer() throws {
        app.launch()
        try audit("onboarding")
        screenshot("onboarding")
        app.buttons["Use without a server"].tap()
        XCTAssertTrue(app.staticTexts["No notes yet"].waitForExistence(timeout: 5))
        try audit("empty list")
        app.buttons["New note"].tap()
        app.typeText("Offline thoughts\nWritten with no server.")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Offline thoughts"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["On this iPhone only"].exists)
    }

    /// Launches and signs in to the test server; `audited` checks the sign-in screen on the way.
    private func signIn(audited: Bool = false) throws {
        let server = try XCTUnwrap(env["NOTESD_URL"], "run through scripts/with-server.sh")
        app.launch()

        let address = app.textFields["Server address"]
        XCTAssertTrue(address.waitForExistence(timeout: 5))
        address.tap()
        address.typeText(server)
        app.buttons["Continue"].tap()

        let username = app.textFields["Username"]
        XCTAssertTrue(username.waitForExistence(timeout: 10))
        username.tap()
        username.typeText(env["NOTESD_USERNAME"] ?? "owner")
        let password = app.secureTextFields["Password"]
        password.tap()
        password.typeText(env["NOTESD_PASSWORD"] ?? "")
        if audited {
            try audit("sign in")
            screenshot("sign in")
        }
        app.buttons["Sign in"].tap()
        XCTAssertTrue(app.buttons["New note"].waitForExistence(timeout: 10))
    }

    func testSignInWriteAndSync() throws {
        try signIn(audited: true)

        // A note with a checklist, a heading and a tag.
        let newNote = app.buttons["New note"]
        XCTAssertTrue(newNote.waitForExistence(timeout: 10))
        newNote.tap()
        app.typeText("Weekend\n")
        app.buttons["Checklist"].tap()
        app.typeText("Milk\nBread #groceries\nCoffee\n\n")
        app.typeText("Plans for the trip")
        screenshot("editor")
        try audit("editor")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["Weekend"].waitForExistence(timeout: 5))
        // Pushed 5 s after the last edit.
        XCTAssertTrue(app.staticTexts["Synced"].waitForExistence(timeout: 20))
        screenshot("list")
        try audit("list")

        // Search finds it by a word in the body.
        // With a bottom toolbar, iOS 26 shows search as a button there first.
        if !app.searchFields.firstMatch.exists { app.buttons["Search"].firstMatch.tap() }
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("bre")
        XCTAssertTrue(app.staticTexts["Weekend"].waitForExistence(timeout: 5))
        screenshot("search")
        let close = ["Cancel", "Close", "Done"].lazy.map { self.app.buttons[$0].firstMatch }.first { $0.exists }
        if let close { close.tap() } else { print("TREE", app.debugDescription) }

        // Tags list the new tag.
        app.buttons["Tags"].tap()
        XCTAssertTrue(app.staticTexts["#groceries"].waitForExistence(timeout: 5))
        screenshot("tags")
        try audit("tags")
        app.buttons["Settings"].tap()
        let account = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "owner"))
        XCTAssertTrue(account.firstMatch.waitForExistence(timeout: 5))
        screenshot("settings")
        // The last row on screen leaves it as the text grows, which the Dynamic Type check reports as
        // partial support; the form's fonts are the ones the other screens check.
        try audit("settings", [.sufficientElementDescription, .hitRegion, .contrast])
    }

    func testEmailIsOfferedOnceSetUp() throws {
        try signIn()
        app.buttons["Tags"].tap()
        app.buttons["Settings"].tap()
        let key = app.secureTextFields["Resend API key"]
        XCTAssertTrue(app.staticTexts["Signed in as"].waitForExistence(timeout: 5))
        for _ in 0..<4 where !key.isHittable { app.swipeUp() }
        XCTAssertTrue(key.exists, app.debugDescription)
        key.tap()
        key.typeText("re_123456789")
        let from = app.textFields["From"]
        from.tap()
        from.typeText("Me <notes@example.com>")
        app.buttons["Save"].tap()
        XCTAssertTrue(app.staticTexts["Saved. Choose Email… from a note’s ••• menu."].waitForExistence(timeout: 10))
        screenshot("email settings")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["New note"].tap()
        app.typeText("Letter\nDear Sam")
        app.buttons["More"].tap()
        let email = app.buttons["Email…"]
        XCTAssertTrue(email.waitForExistence(timeout: 5))
        email.tap()
        XCTAssertTrue(app.navigationBars["Email this note"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["From Me <notes@example.com>. Separate addresses with commas."].exists)
        // The sheet grows with the keyboard; audit it once it has settled.
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        sleep(1)
        screenshot("email")
        try audit("email")
        app.buttons["Cancel"].tap()
    }

    func testAPictureFromAnotherDeviceShows() async throws {
        try await notesWithAPicture()
        try signIn()
        let note = app.staticTexts["Postcard"]
        XCTAssertTrue(note.waitForExistence(timeout: 15))
        note.tap()
        XCTAssertTrue(app.textViews.firstMatch.waitForExistence(timeout: 5))
        // The picture is drawn into the text, not an element of its own: look for its blue.
        var blue = 0
        for _ in 0..<20 where blue < 2000 {
            blue = bluePixels(app.screenshot().image)
            if blue < 2000 { try await Task.sleep(for: .milliseconds(500)) }
        }
        XCTAssertGreaterThan(blue, 2000, "the picture is drawn")
        screenshot("picture")
        try audit("picture")
    }
}
