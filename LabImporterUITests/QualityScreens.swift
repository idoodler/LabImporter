import XCTest

/// Every screen the accessibility audit and the screenshot matrix look at: the
/// DEBUG screenshot mode opens most of them at launch, a few are one tap further.
struct QualityScreen: Sendable {
    /// What only this screen shows. Sheets open on their own after launch: without it, one that
    /// didn't open would leave the screen underneath to be checked under this one's name.
    struct Marker: Sendable {
        let type: XCUIElement.ElementType
        let label: String

        static func bar(_ title: String) -> Marker { Marker(type: .navigationBar, label: title) }
        static func text(_ text: String) -> Marker { Marker(type: .staticText, label: text) }
        static func button(_ label: String) -> Marker { Marker(type: .button, label: label) }

        func element(in app: XCUIApplication) -> XCUIElement {
            app.descendants(matching: type).matching(NSPredicate(format: "identifier == %@ OR label == %@", label, label)).firstMatch
        }
    }

    let name: String
    let shows: Marker
    var arguments: [String] = []
    /// What to tap once the app has launched, for a screen that is a tap further.
    var tapping: String?
    /// Audited at the standard size too, in dark (the others are only measured there, in light): one screen for each kind of tinted
    /// surface — cards, badges and chips, Review's warnings, onboarding's tinted icons, buttons.
    /// Dark uses the same colour tokens everywhere else, and a run of every screen in both takes too long for CI.
    var hasTints = false
    /// How many pages of the largest text the audit looks at: more where what's below the fold differs.
    var auditedPages = 1

    static let all: [QualityScreen] = [
        QualityScreen(name: "dashboard", shows: .bar("Lab Results"), hasTints: true, auditedPages: 2),
        QualityScreen(name: "dashboard-qualitative", shows: .bar("Lab Results"), arguments: ["--ss-data", "qualitative"]),
        QualityScreen(name: "dashboard-notices", shows: .text("Update LabImporter to See All Reports"), arguments: ["--ss-data", "newer-reports"]),
        QualityScreen(name: "dashboard-load-failed-stale", shows: .text("Couldn't Load Your Reports"), arguments: ["--ss-data", "load-failed-stale"]),
        // At full height: while a sheet is half up the system scales its layer, and the audit misjudges its text.
        QualityScreen(name: "trends", shows: .button("3M"), arguments: ["--ss-screen", "trends", "--ss-full-sheet"]),
        QualityScreen(name: "history", shows: .bar("Reports"), arguments: ["--ss-screen", "history"]),
        QualityScreen(name: "history-empty", shows: .text("No Reports Yet"), arguments: ["--ss-data", "no-reports", "--ss-screen", "history"]),
        QualityScreen(
            name: "history-load-failed", shows: .text("Couldn't Load Your Reports"), arguments: ["--ss-data", "load-failed-history", "--ss-screen", "history"]
        ),
        QualityScreen(
            name: "report-detail", shows: .button("More"), arguments: ["--ss-screen", "history"], tapping: "history-report", hasTints: true, auditedPages: 2
        ),
        // The build card at the end of Settings is several pages down at the largest text.
        QualityScreen(name: "settings", shows: .bar("Settings"), arguments: ["--ss-screen", "settings"], auditedPages: 12),
        QualityScreen(name: "sort-editor", shows: .bar("Sort & Visibility"), arguments: ["--ss-screen", "settings"], tapping: "settings-sort"),
        QualityScreen(name: "review", shows: .bar("Lab Report"), arguments: ["--ss-screen", "review", "--ss-data", "empty"]),
        // Review over the landing screen rather than the dashboard: with every card's elements behind
        // it in the tree, the audit runs out of time on this sheet.
        // The stand-in model misreads a value and copies a section heading, so everything Review flags is on screen.
        QualityScreen(
            name: "review-planted-errors", shows: .bar("Lab Report"), arguments: ["--ss-import", "--ss-data", "empty"], hasTints: true, auditedPages: 2
        ),
        QualityScreen(name: "pdf", shows: .bar("Export PDF"), arguments: ["--ss-screen", "pdf"]),
        QualityScreen(name: "empty", shows: .text("IMPORT REPORT"), arguments: ["--ss-data", "empty"], hasTints: true),
        QualityScreen(name: "load-failed", shows: .text("Couldn't Load Your Reports"), arguments: ["--ss-data", "load-failed"]),
        QualityScreen(name: "onboarding-welcome", shows: .text("Welcome to"), arguments: ["--ss-onboarding", "welcome"], hasTints: true, auditedPages: 2),
        QualityScreen(name: "onboarding-disclaimer", shows: .text("Before You Start"), arguments: ["--ss-onboarding", "disclaimer"]),
        QualityScreen(name: "onboarding-health", shows: .text("Connect to Apple Health"), arguments: ["--ss-onboarding", "health"]),
        QualityScreen(name: "onboarding-extras", shows: .text("A Few More Things"), arguments: ["--ss-onboarding", "extras"]),
        QualityScreen(name: "unsupported", shows: .text("Device Not Supported"), arguments: ["--ss-screen", "unsupported"]),
    ]

    /// `QUALITY_SCREENS=a,b` (`TEST_RUNNER_QUALITY_SCREENS` for `xcodebuild`) narrows a run to those screens while fixing them.
    static var selected: [QualityScreen] {
        guard let names = ProcessInfo.processInfo.environment["QUALITY_SCREENS"]?.split(separator: ","), !names.isEmpty else { return all }
        return all.filter { names.contains(Substring($0.name)) }
    }

    func launch(extraArguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ss"] + arguments + extraArguments
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), name)
        if name == "review-planted-errors" {
            XCTAssertTrue(app.datePickers.firstMatch.waitForExistence(timeout: 60), "The report is read")
        }
        if tapping == nil {
            XCTAssertTrue(shows.element(in: app).waitForExistence(timeout: 30), "\(name) shows \(shows.label)")
        }
        waitUntilSettled(app)
        if let tapping {
            let element = app.descendants(matching: .any)[tapping].firstMatch
            // At the largest text it can be a screen or two down, and a list only builds what it shows.
            for _ in 0..<4 where !element.waitForExistence(timeout: 3) {
                app.swipeUp()
            }
            XCTAssertTrue(element.waitForExistence(timeout: 10), tapping)
            element.tap()
            XCTAssertTrue(shows.element(in: app).waitForExistence(timeout: 15), "\(name) shows \(shows.label)")
            waitUntilSettled(app)
        }
        return app
    }
}

/// Screens fade or slide in and lists decelerate: only what's finally on screen says anything.
func waitUntilSettled(_ app: XCUIApplication) {
    var previous = app.screenshot().pngRepresentation
    for _ in 0..<15 {
        Thread.sleep(forTimeInterval: 0.7)
        let current = app.screenshot().pngRepresentation
        if current == previous { return }
        previous = current
    }
}

/// Scrolls the screen a page at a time, running `body` on each, until it stops changing.
func forEachPage(of app: XCUIApplication, limit: Int, _ body: (Int) throws -> Void) rethrows {
    for page in 1...limit {
        try body(page)
        guard page < limit else { return }
        let before = app.screenshot().pngRepresentation
        app.swipeUp()
        waitUntilSettled(app)
        if app.screenshot().pngRepresentation == before { return }
    }
}
