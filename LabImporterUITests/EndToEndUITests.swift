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

    func testReviewFlagsAValueTheReportDoesNotPrintAndShowsTheValuesFirst() {
        let app = launch(["--ss-import"])
        let firstValue = app.textFields["review-value-field"].firstMatch
        XCTAssertTrue(firstValue.waitForExistence(timeout: 60), "The review sheet opens once the report is read")
        // Nothing scrolls here, but the field exists before the sheet has finished sliding in.
        wait(for: [expectation(for: NSPredicate(format: "isHittable == true"), evaluatedWith: firstValue)], timeout: 10)
        let save = app.buttons["Save to Health Records"]
        XCTAssertLessThanOrEqual(firstValue.frame.maxY, save.frame.minY, "…above the pinned Save bar, not behind it")
        XCTAssertFalse(app.textFields["Full Name (optional)"].exists, "Patient and lab are folded away")

        XCTAssertTrue(app.descendants(matching: .any)["review-values-to-check"].exists, "The header counts what to compare")
        XCTAssertFalse(app.descendants(matching: .any)["review-date-missing"].exists, "This report prints its date")

        // The stand-in reads glucose 92 as 922. Nothing blocks saving it.
        let misread = field("922", in: app)
        reveal(misread, in: app)
        let badge = app.descendants(matching: .any)["review-compare-badge"]
        XCTAssertTrue(badge.exists, "The misread value carries the badge")
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "review-compare-badge").count, 1)
        XCTAssertTrue(save.isEnabled)

        // Correcting it clears the badge and the count.
        misread.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        app.typeText("5") // Wherever the caret landed, it's another number now.
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: badge)
        wait(for: [gone], timeout: 10)
        XCTAssertFalse(app.descendants(matching: .any)["review-values-to-check"].exists)
    }

    func testPatientAndLabFoldAwayAfterTheValuesWithTheNamesFoundInView() {
        let app = launch(["--ss-import"])
        let details = app.buttons["review-details"]
        XCTAssertTrue(app.textFields["review-value-field"].firstMatch.waitForExistence(timeout: 60))
        reveal(details, in: app)
        XCTAssertTrue(app.staticTexts["Detected in report: \"Jane Roe\""].exists, "Offered without opening them")
        XCTAssertFalse(app.textFields["Full Name (optional)"].exists)

        details.tap()
        XCTAssertTrue(app.textFields["Full Name (optional)"].waitForExistence(timeout: 10))
    }

    func testReviewSaysWhenTheReportPrintsNoDate() {
        let app = launch(["--ss-import", "--ss-no-date"])
        let notice = app.descendants(matching: .any)["review-date-missing"]
        XCTAssertTrue(notice.waitForExistence(timeout: 60), "The date row says none was found")
    }

    func testAManualReportFromTheEmptyHistoryHasNoDateToMiss() {
        let app = launch(["--ss-screen", "history", "--ss-data", "no-reports"])
        let menu = app.buttons["Import Report"]
        XCTAssertTrue(menu.waitForExistence(timeout: 60), "The empty History offers an import")
        menu.tap()
        app.buttons["Create Report Manually"].tap()
        XCTAssertTrue(app.buttons["Save to Health Records"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.descendants(matching: .any)["review-date-missing"].exists, "A report typed by hand has no date to find")
    }

    func testAResultThatCannotBeSavedIsSaidOnItsRowAndInTheHeader() {
        let app = launch(["--ss-import"])
        let firstValue = app.textFields["review-value-field"].firstMatch
        XCTAssertTrue(firstValue.waitForExistence(timeout: 60))
        firstValue.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        app.typeText(".1.1") // Wherever the caret landed, it's no number now.
        XCTAssertTrue(app.descendants(matching: .any)["review-correct-badge"].waitForExistence(timeout: 10))
        reveal(app.descendants(matching: .any)["review-values-to-correct"], in: app)
    }

    func testClosingTheCodePickerLeavesASuggestionUnconfirmed() {
        let app = launch(["--ss-import"])
        XCTAssertTrue(app.textFields["review-value-field"].firstMatch.waitForExistence(timeout: 60))
        // The first row's suggestion. Rows come and go as the list lays out, so it's followed by its own button.
        let confirmButtons = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "review-confirm-"))
        let confirm = app.buttons[confirmButtons.firstMatch.identifier]
        XCTAssertTrue(confirm.exists)

        app.buttons["review-code-button"].firstMatch.tap()
        // The search field takes the keyboard right away and hides the picker's navigation bar. The button
        // that ends the search, right beside the field, has no element to query, so it's tapped by position.
        let search = app.searchFields.matching(NSPredicate(format: "placeholderValue == %@", "Search lab tests")).firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 0.5)).withOffset(CGVector(dx: 35, dy: 0)).tap()
        let picker = app.navigationBars["Lab Test"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        picker.buttons["Close"].tap()
        wait(for: [expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: search)], timeout: 10)
        XCTAssertTrue(confirm.exists, "Closing without choosing confirms nothing")

        confirm.tap()
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: confirm)
        wait(for: [gone], timeout: 10)
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
