import XCTest

/// End to end through the app's UI on the simulator. The DEBUG screenshot mode
/// (`--ss`) passes the Apple Intelligence check a simulator can't, and
/// `--ss-import` reads a Function Health–style report with the stand-in model
/// (`StandInReport`), so everything after the model runs for real.
final class EndToEndUITests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(_ arguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ss"] + arguments
        app.launch()
        return app
    }

    /// Scrolls the frontmost list — down, then back up — until `element` is on screen.
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        for _ in 0..<8 where !element.isHittable {
            app.swipeUp()
        }
        for _ in 0..<16 where !element.isHittable {
            app.swipeDown()
        }
        XCTAssertTrue(
            element.isHittable,
            "\(element) never came into view; fields: \(app.textFields.allElementsBoundByIndex.map { $0.value as? String ?? "?" })",
            file: file, line: line
        )
    }

    private func field(_ value: String, in app: XCUIApplication) -> XCUIElement {
        app.textFields.matching(NSPredicate(format: "value == %@", value)).firstMatch
    }

    func testAnImportedReportReachesReviewReadyToSave() {
        let app = launch(["--ss-import"])
        let save = app.buttons["Save to Health Records"]
        XCTAssertTrue(save.waitForExistence(timeout: 60), "The review sheet opens once the report is read")
        XCTAssertTrue(save.isEnabled, "Values with a LOINC code are ready to save")

        // Labels and flags are gone; a result that isn't a number is kept as it was printed.
        for value in ["5.1", "24", "263", "1.9", "4.5"] {
            reveal(field(value, in: app), in: app)
        }
        reveal(field("<1.0", in: app), in: app)
        XCTAssertFalse(field("In Range· 5.1", in: app).exists)
    }

    func testResultsThatAreNotNumbersOpenTheirHistory() {
        let app = launch(["--ss-data", "qualitative"])
        XCTAssertTrue(app.navigationBars["Lab Results"].waitForExistence(timeout: 30))
        // The hepatitis B card shows its latest result ("Reactive"); tapping it lists every result.
        let card = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Reactive")).firstMatch
        reveal(card, in: app)
        card.tap()
        let history = app.descendants(matching: .any)["result-history"]
        XCTAssertTrue(history.waitForExistence(timeout: 10), "Results that aren't numbers open as a list, not a chart")
        let earlier = history.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "Non-reactive"))
        XCTAssertGreaterThanOrEqual(earlier.count, 3, "Every earlier result is listed")
        XCTAssertTrue(history.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Reactive")).firstMatch.exists,
                      "The latest result leads")
        app.buttons["Close"].tap()
        XCTAssertTrue(app.navigationBars["Lab Results"].waitForExistence(timeout: 10))
    }
}
