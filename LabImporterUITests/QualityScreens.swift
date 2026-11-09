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

        /// The marker in `language`'s wording too (the screenshot matrix runs in German), from the app's String Catalog.
        func element(in app: XCUIApplication, language: String? = nil) -> XCUIElement {
            let translated = language.flatMap { Self.translation(of: label, in: $0) } ?? label
            let predicate = NSPredicate(format: "identifier IN %@ OR label IN %@", [label, translated], [label, translated])
            return app.descendants(matching: type).matching(predicate).firstMatch
        }

        /// The simulator reads the Mac's files, so the catalog is read where the sources are.
        private static func translation(of key: String, in language: String) -> String? {
            let catalog = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
                .appendingPathComponent("LabImporter/Localizable.xcstrings")
            guard let data = try? Data(contentsOf: catalog),
                  let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let strings = root["strings"] as? [String: Any],
                  let entry = strings[key] as? [String: Any],
                  let localizations = entry["localizations"] as? [String: Any],
                  let localization = localizations[language] as? [String: Any],
                  let unit = localization["stringUnit"] as? [String: Any] else { return nil }
            return unit["value"] as? String
        }
    }

    let name: String
    let shows: Marker
    var arguments: [String] = []
    /// What to tap once the app has launched, for a screen that is a tap further.
    var tapping: String?
    /// Audited at the standard size in dark too, not only in light: one screen for each kind of tinted
    /// surface — cards, badges and chips, Review's warnings, onboarding's tinted icons, buttons.
    /// Dark uses the same colour tokens everywhere else, and a run of every screen in both takes too long for CI.
    var hasTints = false
    /// How many pages of the largest text the audit looks at: more where what's below the fold differs.
    /// Listed per screen in `tools/quality/accessibility_exemptions_baseline.json` ("pages"), which may only go up.
    var auditedPages = 1
    /// Why the pages after `auditedPages` are left alone, for a screen that has more: what they show
    /// the audited ones show already. Without one the screen must end by then, or the audit fails.
    /// A pull request's run only: the weekly one (`QUALITY_AUDIT_ALL_PAGES=1`) audits every page.
    var laterPages: String?

    // Why a screen's later pages at the largest text are left alone (pages counted on the iPhone 17 Pro
    // simulator): an audit of every page of every screen takes far longer than CI's runs allow.
    private static let moreCards = "more metric cards like the audited ones (9 to 12 pages)"
    private static let moreRows = "more rows like the audited ones, the same kinds of text and controls"
    private static let moreOnboarding = "more of the same onboarding page, in rows styled like the audited ones (5 to 8 pages)"

    static let all: [QualityScreen] = [
        QualityScreen(name: "dashboard", shows: .bar("Lab Results"), hasTints: true, auditedPages: 2, laterPages: moreCards),
        QualityScreen(name: "dashboard-qualitative", shows: .bar("Lab Results"), arguments: ["--ss-data", "qualitative"], laterPages: moreCards),
        QualityScreen(
            name: "dashboard-notices", shows: .text("Update LabImporter to See All Reports"), arguments: ["--ss-data", "newer-reports"], laterPages: moreCards
        ),
        QualityScreen(
            name: "dashboard-load-failed-stale", shows: .text("Couldn't Load Your Reports"), arguments: ["--ss-data", "load-failed-stale"],
            laterPages: moreCards
        ),
        // At full height: while a sheet is half up the system scales its layer, and the audit misjudges its text.
        QualityScreen(
            name: "trends", shows: .button("3M"), arguments: ["--ss-screen", "trends", "--ss-full-sheet"],
            laterPages: "the chart's readings and the metric's details below it, in rows like the audited ones (9 pages)"
        ),
        QualityScreen(name: "history", shows: .bar("Reports"), arguments: ["--ss-screen", "history"], laterPages: moreRows),
        QualityScreen(
            name: "history-empty", shows: .text("No Reports Yet"), arguments: ["--ss-data", "no-reports", "--ss-screen", "history"], auditedPages: 2
        ),
        QualityScreen(
            name: "history-load-failed", shows: .text("Couldn't Load Your Reports"), arguments: ["--ss-data", "load-failed-history", "--ss-screen", "history"],
            auditedPages: 3
        ),
        QualityScreen(
            name: "report-detail", shows: .button("More"), arguments: ["--ss-screen", "history"], tapping: "history-report", hasTints: true, auditedPages: 2,
            laterPages: moreRows
        ),
        // The build card at the end of Settings is many pages down at the largest text.
        QualityScreen(name: "settings", shows: .bar("Settings"), arguments: ["--ss-screen", "settings"], auditedPages: 14),
        QualityScreen(
            name: "sort-editor", shows: .bar("Sort & Visibility"), arguments: ["--ss-screen", "settings"], tapping: "settings-sort", laterPages: moreRows
        ),
        QualityScreen(name: "review", shows: .bar("Lab Report"), arguments: ["--ss-screen", "review", "--ss-data", "empty"], laterPages: moreRows),
        // Review over the landing screen rather than the dashboard: with every card's elements behind
        // it in the tree, the audit runs out of time on this sheet.
        // The stand-in model misreads a value and copies a section heading, so everything Review flags is on screen.
        // At the largest text its values begin on the third page.
        QualityScreen(
            name: "review-planted-errors", shows: .bar("Lab Report"), arguments: ["--ss-import", "--ss-data", "empty"], hasTints: true, auditedPages: 3,
            laterPages: moreRows
        ),
        QualityScreen(
            name: "pdf", shows: .bar("Export PDF"), arguments: ["--ss-screen", "pdf"],
            laterPages: "more of the export's options and its page preview (7 pages)"
        ),
        QualityScreen(name: "empty", shows: .text("IMPORT REPORT"), arguments: ["--ss-data", "empty"], hasTints: true, auditedPages: 3),
        QualityScreen(name: "load-failed", shows: .text("Couldn't Load Your Reports"), arguments: ["--ss-data", "load-failed"]),
        QualityScreen(
            name: "onboarding-welcome", shows: .text("Welcome to"), arguments: ["--ss-onboarding", "welcome"], hasTints: true, auditedPages: 2,
            laterPages: moreOnboarding
        ),
        QualityScreen(
            name: "onboarding-disclaimer", shows: .text("Before You Start"), arguments: ["--ss-onboarding", "disclaimer"], laterPages: moreOnboarding
        ),
        QualityScreen(
            name: "onboarding-health", shows: .text("Connect to Apple Health"), arguments: ["--ss-onboarding", "health"], laterPages: moreOnboarding
        ),
        QualityScreen(
            name: "onboarding-extras", shows: .text("A Few More Things"), arguments: ["--ss-onboarding", "extras"], laterPages: moreOnboarding
        ),
        QualityScreen(name: "unsupported", shows: .text("Device Not Supported"), arguments: ["--ss-screen", "unsupported"], auditedPages: 3),
    ]

    /// `QUALITY_SCREENS=a,b` (`TEST_RUNNER_QUALITY_SCREENS` for `xcodebuild`) narrows a run to those screens while fixing them.
    static var selected: [QualityScreen] {
        guard let names = ProcessInfo.processInfo.environment["QUALITY_SCREENS"]?.split(separator: ","), !names.isEmpty else { return all }
        return all.filter { names.contains(Substring($0.name)) }
    }

    func launch(extraArguments: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ss"] + arguments + extraArguments
        // "-AppleLanguages (de)": the language the markers are shown in.
        let language = extraArguments.firstIndex(of: "-AppleLanguages").flatMap { extraArguments.indices.contains($0 + 1) ? $0 + 1 : nil }
            .map { extraArguments[$0].trimmingCharacters(in: CharacterSet(charactersIn: "()")) }
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), name)
        if name == "review-planted-errors" {
            // The header's count of values to check, at the top at every text size: at the largest, the
            // wrapped category chips push the date row off the first screen, and a list only builds what it shows.
            let read = app.descendants(matching: .any)["review-values-to-check"].firstMatch
            XCTAssertTrue(read.waitForExistence(timeout: 60), "The report is read")
        }
        if tapping == nil {
            XCTAssertTrue(shows.element(in: app, language: language).waitForExistence(timeout: 30), "\(name) shows \(shows.label)")
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
            XCTAssertTrue(shows.element(in: app, language: language).waitForExistence(timeout: 15), "\(name) shows \(shows.label)")
            waitUntilSettled(app)
        }
        return app
    }
}

/// Screens fade or slide in and lists decelerate: only what's finally on screen says anything.
/// Returns that screen's screenshot.
@discardableResult
func waitUntilSettled(_ app: XCUIApplication) -> Data {
    var previous = app.screenshot().pngRepresentation
    for _ in 0..<15 {
        Thread.sleep(forTimeInterval: 0.7)
        let current = app.screenshot().pngRepresentation
        if current == previous { return current }
        previous = current
    }
    return previous
}

/// Scrolls the screen a page at a time, running `body` on each, until it stops changing, and returns
/// the last page's number: page n is where n - 1 drags of the same length from the top leave the
/// screen, on every run. `body` mustn't scroll: a page that moved under it fails, the last one too.
///
/// `limit` is how many pages the screen may have: reaching it while the screen still scrolls fails, so
/// no page goes unlooked-at without a word. `rest` is the stated reason the pages after `limit` may be
/// left alone: then they are, and the log says so.
@discardableResult
func forEachPage(
    of app: XCUIApplication, limit: Int, rest: String? = nil, file: StaticString = #filePath, line: UInt = #line,
    _ body: (Int) throws -> Void
) rethrows -> Int {
    let pager = Pager(app)
    var shown = screenContent(waitUntilSettled(app))
    var page = 1
    while true {
        print("AUDIT page \(page) \(contentDigest(shown))")
        try body(page)
        if !sameContent(screenContent(waitUntilSettled(app)), shown) {
            XCTFail("Page \(page) moved while it was looked at: the pages after it aren't the same on every run", file: file, line: line)
        }
        if page == limit, let rest {
            print("AUDIT pages after \(limit) left alone: \(rest)")
            return page
        }
        let next = pager.next(from: shown)
        // The end of the screen.
        if sameContent(next, shown) { return page }
        if page == limit {
            XCTFail("The screen still scrolls after its last page, \(limit): raise its number of pages", file: file, line: line)
            return page
        }
        shown = next
        page += 1
    }
}

/// What the screen shows, as pixels, without what changes on its own: the status bar's clock, and
/// the scroll indicator, which can still be showing or have faded when the screen settles.
func screenContent(_ png: Data) -> Data {
    guard let image = UIImage(data: png)?.cgImage,
          let crop = image.cropping(to: CGRect(x: 0, y: image.height / 12, width: image.width * 19 / 20, height: image.height - image.height / 12)),
          let context = CGContext(
            data: nil, width: crop.width, height: crop.height, bitsPerComponent: 8, bytesPerRow: crop.width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
          ) else { return png }
    context.draw(crop, in: CGRect(x: 0, y: 0, width: crop.width, height: crop.height))
    guard let pixels = context.data else { return png }
    return Data(bytes: pixels, count: crop.height * crop.width * 4)
}

/// Whether two `screenContent`s show the same: a drag that moves nothing can still shift a glass button's
/// shading by one level in a few pixels (Home's settings button on load-failed, every other run), which
/// would read as a page more. Anything that scrolls moves text, far more than that.
func sameContent(_ lhs: Data, _ rhs: Data) -> Bool {
    if lhs == rhs { return true }
    guard lhs.count == rhs.count else { return false }
    return lhs.withUnsafeBytes { left in
        rhs.withUnsafeBytes { right in
            let left = left.bindMemory(to: UInt8.self), right = right.bindMemory(to: UInt8.self)
            return !zip(left, right).contains { abs(Int($0) - Int($1)) > 8 }
        }
    }
}

/// A short fingerprint of a page, logged so runs can be compared page by page.
private func contentDigest(_ content: Data) -> String {
    var hash: UInt64 = 0xcbf2_9ce4_8422_2325
    // Every 97th byte is plenty to tell pages apart.
    for index in stride(from: 0, to: content.count, by: 97) {
        hash = (hash ^ UInt64(content[index])) &* 0x100_0000_01b3
    }
    return String(hash, radix: 16)
}

/// Pages a screen by drags held still before they let go, so the list stops where the finger does. A
/// swipe flings it, and how far it coasts differs from run to run: the same Settings text began the
/// second page 163 pt apart, so every run audited other pages than the last. Every drag is the same,
/// measured once: a large title that collapses on the first one would make the next ones longer.
private struct Pager {
    let app: XCUIApplication
    let lower: XCUICoordinate
    let upper: XCUICoordinate

    init(_ app: XCUIApplication) {
        self.app = app
        let window = app.windows.firstMatch
        let frame = window.frame
        // From above Review's action bar (or the home indicator) to under the navigation bar: what's between them is the page.
        var top = frame.minY
        var bottom = frame.maxY
        let bar = app.navigationBars.firstMatch
        if bar.exists { top = bar.frame.maxY }
        let actionBar = app.otherElements["review-action-bar"]
        if actionBar.exists { bottom = actionBar.frame.minY }
        let origin = window.coordinate(withNormalizedOffset: .zero)
        // Left of the rows' switches and fields.
        let column = frame.width * 0.3
        lower = origin.withOffset(CGVector(dx: column, dy: bottom - 100 - frame.minY))
        upper = origin.withOffset(CGVector(dx: column, dy: top + 40 - frame.minY))
    }

    func drag() {
        lower.press(forDuration: 0.1, thenDragTo: upper, withVelocity: .default, thenHoldForDuration: 0.3)
    }

    /// A page further down from [shown], returning what's on screen then: the same at the end. A drag
    /// right after a cold launch once left the dashboard where it was, and its second page went
    /// unaudited: only a second drag that moves nothing either is the end of the screen.
    func next(from shown: Data) -> Data {
        var now = shown
        for _ in 0..<2 where sameContent(now, shown) {
            drag()
            now = screenContent(waitUntilSettled(app))
        }
        return now
    }
}
