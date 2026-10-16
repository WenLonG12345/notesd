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

    func testSignInWriteAndSync() throws {
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
        try audit("sign in")
        screenshot("sign in")
        app.buttons["Sign in"].tap()

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
}
