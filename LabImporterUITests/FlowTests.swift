import XCTest

/// Walks the main screens with sample data (DEBUG `--ss` screenshot mode, which
/// also bypasses the Apple Intelligence check that a simulator can't pass).
final class FlowTests: XCTestCase {
    override func setUp() {
        continueAfterFailure = false
    }

    private func launch(screen: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ss"] + (screen.map { ["--ss-screen", $0] } ?? [])
        app.launch()
        return app
    }

    func testDashboardShowsTheSampleReports() {
        let app = launch()
        XCTAssertTrue(app.navigationBars["Lab Results"].waitForExistence(timeout: 30))
    }

    func testSettingsOfferTheTransferBetweenIPhoneAndAndroid() {
        let app = launch(screen: "settings")
        let importButton = app.buttons["Import Reports"]
        XCTAssertTrue(importButton.waitForExistence(timeout: 30))
        XCTAssertTrue(importButton.isEnabled)
        let exportButton = app.buttons["Export All Reports"]
        XCTAssertTrue(exportButton.exists)
        XCTAssertTrue(exportButton.isEnabled, "There are reports to export")
    }

    private func launch(data: String, screen: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ss", "--ss-data", data] + (screen.map { ["--ss-screen", $0] } ?? [])
        app.launch()
        return app
    }

    func testAFailedLoadIsSaidInsteadOfLookingLikeNoReports() {
        let app = launch(data: "load-failed")
        XCTAssertTrue(app.staticTexts["Couldn't Load Your Reports"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.staticTexts["IMPORT REPORT"].exists, "The first-run screen would say the reports are gone")
        XCTAssertFalse(app.navigationBars["Lab Results"].exists)

        app.buttons["Try Again"].tap()

        XCTAssertTrue(app.navigationBars["Lab Results"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["Couldn't Load Your Reports"].exists)
    }

    func testAFailedLoadStaysAboveTheReportsOnScreenUntilARetryWorks() {
        let app = launch(data: "load-failed-stale")
        XCTAssertTrue(app.navigationBars["Lab Results"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Couldn't Load Your Reports"].exists)

        app.buttons["Try Again"].tap()

        XCTAssertTrue(app.staticTexts["Couldn't Load Your Reports"].waitForNonExistence(timeout: 5))
    }

    func testAFailedLoadInHistoryIsNotAnEmptyList() {
        let app = launch(data: "load-failed-history", screen: "history")
        XCTAssertTrue(app.staticTexts["Couldn't Load Your Reports"].waitForExistence(timeout: 60))
        XCTAssertFalse(app.staticTexts["No Reports Yet"].exists)

        app.buttons["Try Again"].tap()

        XCTAssertTrue(app.staticTexts["Couldn't Load Your Reports"].waitForNonExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["No Reports Yet"].exists, "The reports came back")
    }

    func testReportsFromANewerVersionAskForAnUpdateEvenWithNothingElseToShow() {
        let app = launch(data: "newer-reports-only")
        XCTAssertTrue(app.staticTexts["Update LabImporter to See All Reports"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["IMPORT REPORT"].exists, "A new report can still be imported")
    }

    func testReportsFromANewerVersionAskForAnUpdateAboveTheOthers() {
        let app = launch(data: "newer-reports")
        XCTAssertTrue(app.navigationBars["Lab Results"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["Update LabImporter to See All Reports"].exists)
        XCTAssertTrue(app.staticTexts["Some Reports Can't Be Read"].exists)
    }

    func testTrendsOfferEveryTimeWindow() {
        let app = launch(screen: "trends")
        for window in ["3M", "6M", "1Y", "All"] {
            XCTAssertTrue(app.buttons[window].waitForExistence(timeout: 30), window)
        }
    }
}
