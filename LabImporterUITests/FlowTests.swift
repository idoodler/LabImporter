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

    func testTrendsOfferEveryTimeWindow() {
        let app = launch(screen: "trends")
        for window in ["3M", "6M", "1Y", "All"] {
            XCTAssertTrue(app.buttons[window].waitForExistence(timeout: 30), window)
        }
    }
}
