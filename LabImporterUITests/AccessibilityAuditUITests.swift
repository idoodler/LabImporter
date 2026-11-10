import XCTest

/// Runs Apple's own accessibility audit (contrast, hit regions, element descriptions,
/// traits, clipped text) over every screen with the sample data at the largest
/// accessibility text and in light at the standard size, and in dark at the standard size
/// too for the screens whose tinted cards, badges, chips or buttons take other colours there. An issue fails the
/// test unless `AuditExemption` names it, each entry with its reason.
///
/// Dynamic Type is measured, not probed: the audit's probe loses rows that its larger
/// text pushes out of a lazy list and reports them as "partially unsupported", so each
/// text's height is compared between the two sizes instead, on every screen.
///
/// Every finding is printed as an `AUDIT` line, exempt or not, to be read out of the log.
/// `QUALITY_AUDIT_EVIDENCE=<folder>` (`TEST_RUNNER_…` for `xcodebuild`) saves a screenshot of each
/// issue's element there and hands one without an element back to XCTest, which attaches where it
/// is — slow, for looking into a few screens.
///
/// Each test ends with one fixed line, `AUDIT exemptions <test> applied <n> measured <m> unmeasured <k> rules <r>`:
/// every exemption it applied; the ones it measured on that run (contrast on an element named by its full
/// label, whose own pixels measured 4.5:1); all the others, unmeasured, the `r` rules of `AuditExemption.reason`
/// among them (`tools/quality/reports.py audit` reads it).
/// `QUALITY_AUDIT_ALL_PAGES=1` audits every page of every screen in every condition, with no page limit, and
/// clipped text from each page: the weekly run (`quality.yml`); a pull request's stays within `auditedPages`
/// at the largest text and the first page at the standard size, to finish in time.
final class AccessibilityAuditUITests: XCTestCase {
    private enum Size: String {
        case standard = "UICTContentSizeCategoryL"
        case largestAccessibility = "UICTContentSizeCategoryAccessibilityXXXL"
    }

    /// The types Apple documents, without the Dynamic Type probe (replaced by `assertTextsGrow`, see above). Never
    /// `.all`: it also sets bits Apple names no check for, whose findings and time-outs no one could look into.
    private static let auditTypes: XCUIAccessibilityAuditType = [
        .contrast, .elementDetection, .hitRegion, .sufficientElementDescription, .textClipped, .trait,
    ]
    /// The clipped-text check scrolls each text it looks at into view, over the whole list: down, back to the
    /// top, and past the last row, where it leaves the list blank. In one audit with the rest, contrast was
    /// judged on a screen moving under it (black on white failed at 21:1) and issues lost their element; and
    /// paging on from where it left the list audited other pages on every run. So it runs once a screen,
    /// after its pages, and the rest on each page while it's still.
    private static let clippedTypes = XCUIAccessibilityAuditType.textClipped
    private static let stillTypes = auditTypes.subtracting(clippedTypes)

    private static let allPages = ProcessInfo.processInfo.environment["QUALITY_AUDIT_ALL_PAGES"] == "1"

    /// The exemptions this test applied, printed as its `AUDIT exemptions` line.
    private var tally = ExemptionTally()

    override func setUp() {
        continueAfterFailure = true
    }

    @MainActor
    func testEveryScreen() throws {
        defer { printTally() }
        for screen in QualityScreen.selected {
            try XCTContext.runActivity(named: screen.name) { _ in
                var largest: [String: CGFloat] = [:]
                var valueFields = 0
                // Text at the largest size takes pages: where what's below the fold differs, it's audited too.
                let last = try auditPages(
                    of: screen, size: .largestAccessibility, dark: false,
                    limit: Self.allPages ? Int.max : screen.auditedPages, rest: screen.laterPages
                ) { app, _ in
                    largest.merge(try textHeights(in: app)) { first, _ in first }
                    if screen.name == "review-planted-errors" {
                        valueFields += try assertValueFieldsDrawOverNothing(in: app)
                    }
                }
                print("AUDIT screen \(screen.name) pages \(last) of \(Self.allPages ? "all" : String(screen.auditedPages))")
                if screen.name == "review-planted-errors" {
                    XCTAssertGreaterThan(valueFields, 0, "No value field was on screen at the largest size")
                }

                // Every screen at the standard size too: measured for Dynamic Type and audited in light, and
                // again in dark where tinted surfaces take other colours.
                let firstPageOnly = "a pull request's run audits the standard size on its first page; the weekly run, every page"
                try auditPages(of: screen, size: .standard, dark: false, limit: Self.allPages ? Int.max : 1, rest: firstPageOnly) { app, page in
                    guard page == 1 else { return }
                    assertTextsGrow(from: try textHeights(in: app), to: largest, on: screen)
                    if screen.name == "review-planted-errors" {
                        assertEveryValueFieldSpeaksItsTest(in: app)
                    }
                }
                if screen.hasTints {
                    try auditPages(of: screen, size: .standard, dark: true, limit: Self.allPages ? Int.max : 1, rest: firstPageOnly)
                }
            }
        }
    }

    /// Audits [screen] in one condition, page by page up to [limit] (`forEachPage`; [rest] is why a pull request's
    /// run leaves the pages after it alone), running [body] on each page first, and returns the last page's number.
    ///
    /// Clipped text is checked apart (`clippedTypes`): a pull request's run checks it from the last page audited;
    /// the run of every page from every page, each in a fresh launch, since what it reports for the rows a swipe
    /// brings in depends on where it starts.
    @MainActor
    @discardableResult
    private func auditPages(
        of screen: QualityScreen, size: Size, dark: Bool, limit: Int, rest: String?,
        _ body: (XCUIApplication, Int) throws -> Void = { _, _ in }
    ) throws -> Int {
        let condition = "\(screen.name)/\(dark ? "dark" : "light")/\(size == .standard ? "standard" : "ax5")"
        // `--ss-appearance` sets the app's appearance: switching the simulator's costs seconds a screen.
        let app = launch(screen, size: size, dark: dark)
        let last = try forEachPage(of: app, limit: limit, rest: Self.allPages ? nil : rest) { page in
            try body(app, page)
            try audit(app, label: "\(condition)/\(page)", types: Self.stillTypes)
        }
        guard Self.allPages else {
            try audit(app, label: "\(condition)/\(last)", types: Self.clippedTypes)
            app.terminate()
            return last
        }
        app.terminate()
        for start in 1...last {
            let pageApp = launch(screen, size: size, dark: dark)
            let page = try forEachPage(of: pageApp, limit: start, rest: "clipped text is checked from page \(start)") { _ in }
            try audit(pageApp, label: "\(condition)/\(page)", types: Self.clippedTypes)
            pageApp.terminate()
        }
        return last
    }

    private func printTally() {
        let test = name.split(separator: " ").last.map { String($0.dropLast()) } ?? name
        print("AUDIT exemptions \(test) applied \(tally.applied) measured \(tally.measured) unmeasured \(tally.unmeasured) rules \(tally.rules)")
    }

    private func launch(_ screen: QualityScreen, size: Size, dark: Bool) -> XCUIApplication {
        screen.launch(extraArguments: ["-UIPreferredContentSizeCategoryName", size.rawValue, "--ss-appearance", dark ? "dark" : "light"])
    }

    @MainActor
    private func audit(_ app: XCUIApplication, label: String, types: XCUIAccessibilityAuditType) throws {
        let evidence = ProcessInfo.processInfo.environment["QUALITY_AUDIT_EVIDENCE"].map { URL(fileURLWithPath: $0, isDirectory: true) }
        let visible = TextContrast.visibleArea(of: app)
        // Judged after the audit: one that waits for screenshots of its findings runs out of time.
        guard let found = runAudit(of: app, label: label, evidence: evidence, types: types) else { return }
        // An issue without an element can't be measured itself: it's let go only while every text on the
        // screen measures readable, so a faint text the audit can't point to still fails.
        var texts: TextContrast?
        if found.issues.contains(where: { $0.element == nil && $0.issue.auditType == .contrast }) {
            let measured = TextContrast.measure(in: app, visibleArea: visible)
            measured.faint.forEach { print("AUDIT faint \(label) \($0)") }
            texts = measured
        }
        // Where a row's chips fade out at its ends: only asked for when there's a contrast issue it could explain.
        let pointsAtText = found.issues.contains { $0.element != nil && $0.issue.auditType == .contrast }
        let sideways = pointsAtText ? SidewaysRow.all(in: app) : []
        sideways.forEach { print("AUDIT sideways \(label) \($0.frame) content \($0.content) fades left \($0.fadesLeft) right \($0.fadesRight)") }
        var page = AuditExemption.Page(label: label, visibleArea: visible, texts: texts, sidewaysRows: sideways)
        for (issue, pointed) in found.issues {
            // One gone from the screen since (a slow audit) is an issue without an element: asking for its label fails the test.
            let element = pointed.flatMap { $0.exists ? $0 : nil }
            let name = element?.label ?? ""
            let exemption = count(AuditExemption.reason(for: issue, element: element, on: &page), label: label, element: name)
            let frame = element.map { "\($0.frame)" } ?? "no element"
            // What its pixels measure says whether the audit or the colour is wrong.
            let measured = exemption == nil && issue.auditType == .contrast ? element.flatMap(PixelContrast.of) : nil
            let verdict = exemption.map { "exempt (\($0))" } ?? "issue" + (measured.map { String(format: " (measures %.1f:1)", $0) } ?? "")
            print("AUDIT \(verdict) \(label) [\(issue.auditType.rawValue)] \(issue.compactDescription) — \(name) @ \(frame)")
            if let evidence, exemption == nil, let element {
                let file = evidence.appendingPathComponent("\(label.replacingOccurrences(of: "/", with: "-"))-\(name.prefix(24)).png")
                try? FileManager.default.createDirectory(at: evidence, withIntermediateDirectories: true)
                try? element.screenshot().pngRepresentation.write(to: file)
            }
            if exemption == nil, evidence == nil {
                XCTFail("\(label): \(issue.compactDescription): \(name) — \(issue.detailedDescription)")
            }
        }
    }

    /// Counts an exemption the audit applied: measured only for contrast on an element named by its full label
    /// whose own pixels measured 4.5:1 on this run; every other one, a rule's included, unmeasured. One naming its
    /// element that comes back without that measure fails here, so no path through `reason` can skip it.
    private func count(_ granted: AuditExemption.Granted?, label: String, element: String) -> String? {
        guard let granted else { return nil }
        if granted.namesContrastElement {
            guard let ratio = granted.ratio, ratio >= AuditExemption.minimumContrast else {
                XCTFail("\(label): a contrast exemption was applied without its pixels measuring 4.5:1: \(element) (\(granted.reason))")
                return nil
            }
            tally.measured += 1
        } else {
            tally.unmeasured += 1
            if granted.isRule { tally.rules += 1 }
        }
        tally.applied += 1
        return granted.reason
    }

    /// The audit gives up on a long screen ("failed to complete in time"): asked again it gives up
    /// again, so it's asked for one kind of issue at a time. One kind gets two more goes: a page's audit
    /// takes about 3 s, and the ones that gave up (the dashboard's second page at the largest text, Review's
    /// sixth twice in a row in a run of every page) had hung for their whole 30 s, with nothing on screen moving.
    @MainActor
    private func runAudit(
        of app: XCUIApplication, label: String, evidence: URL?, types: XCUIAccessibilityAuditType,
        retries: Int = 2
    ) -> FoundIssues? {
        let found = FoundIssues()
        do {
            try app.performAccessibilityAudit(for: types) { issue in
                found.issues.append((issue, issue.element))
                return evidence == nil || issue.element != nil
            }
        } catch let error as NSError where error.code == -56 && types.rawValue.nonzeroBitCount > 1 {
            // What it found before giving up comes again, from the part that finds it: each type on its own.
            found.issues.removeAll()
            let parts = (0..<UInt64.bitWidth).map { XCUIAccessibilityAuditType(rawValue: 1 << $0) }.filter { types.contains($0) }
            for part in parts {
                guard let issues = runAudit(of: app, label: label, evidence: evidence, types: part) else { return nil }
                found.issues += issues.issues
            }
        } catch let error as NSError where error.code == -56 && retries > 0 {
            print("AUDIT retry \(label) [\(types.rawValue)] after \(error.localizedDescription)")
            waitUntilSettled(app)
            return runAudit(of: app, label: label, evidence: evidence, types: types, retries: retries - 1)
        } catch let error as NSError where error.code == -56 {
            // A type that gave up three times: nothing stands in for it, so the page fails unjudged.
            XCTFail("\(label): Apple's audit for type \(types.rawValue) gave up three times (\(error.localizedDescription)): the page wasn't judged")
            return nil
        } catch {
            XCTFail("\(label): the audit didn't finish: \(error)")
            return nil
        }
        return found
    }
}

/// Audit findings that are false positives, each checked against a screenshot of it,
/// matched by screen and condition, audit type and element. Keep this list short: every
/// entry is a place the audit no longer protects.
private enum AuditExemption {
    private struct Entry {
        /// `screen/appearance/size/page`, as in the AUDIT lines.
        let condition: String
        let type: XCUIAccessibilityAuditType
        /// The element's full label ("" for any); nil for an issue the audit found without an element.
        let element: String?
        /// How many issues of a page it covers, for one nothing re-measures each run: the number its screenshots were checked for.
        var limit = Int.max
        let reason: String
    }

    /// What an exempted element's pixels, and every text on a page whose issue without an element is
    /// exempted, have to measure (`TextContrast.minimum`, 4.5:1 whatever the text's size).
    static let minimumContrast = TextContrast.minimum

    /// An exemption applied: by a rule, or by an entry, with the lowest contrast its pixels measured on this run
    /// (the element's, or for an issue without one every text's on the page). Only one naming its element by its
    /// full label (`namesContrastElement`) counts as measured: "" lets go whichever element the audit points at,
    /// and an issue without one has no pixels of its own, so a faint text beside a dark icon still passes.
    struct Granted {
        let reason: String
        var isRule = false
        var namesContrastElement = false
        var ratio: Double?

        static func rule(_ reason: String) -> Granted { Granted(reason: reason, isRule: true) }
    }

    /// The page being audited, and how many issues each entry has let go on it.
    struct Page {
        let label: String
        let visibleArea: CGRect
        /// Every text's contrast, measured when the audit found a contrast issue without an element; nil otherwise.
        let texts: TextContrast?
        /// The rows on screen that scroll sideways (`edgeFadeScroll`).
        let sidewaysRows: [SidewaysRow]
        fileprivate var used: [Int: Int] = [:]
    }

    // The audit's contrast check fails text whose screenshot shows plain black on white or
    // white on near-black (black "Couldn't Load Your Reports" on white, white "2026" on the dark
    // wash): measured, every one of these is over 4.5:1, and `measuredContrast` checks it again
    // each run (4.5:1, whatever the text's size), so a real regression still fails. An entry names the
    // element by its full label.
    private static let measured = "its pixels measure over 4.5:1; the audit's contrast check fails even black on white here"
    // Without an element the audit can't say which text it means, and its screenshot has no mark. These
    // pages' screenshots were measured text by text (each over 4.5:1; the faintest marks, the list rows'
    // grey disclosure chevrons, are the system's glyphs), and `faintTexts` measures every text again each
    // run: one under 4.5:1 fails the page whatever the audit points to. Its count varies run to run.
    private static let unpointed = "no element to measure; every text on the page measures over 4.5:1"
    // The texts of the rows a swipe brings in at the largest size, read as one element per row: their
    // screenshots show each wrapped in full, cut by nothing but the screen's edge.
    private static let rowTexts = "texts of rows read as one element; the screenshots show none cut off but by the screen's edge"
    // On these pages the audit points at a different row's text each run (a LOINC code, a count, a unit,
    // "Suggested") and on a busy simulator at none: any of them, but each is measured and must be 4.5:1.
    private static let rowText = "a row's text, a different one each run; " + measured
    // The same on the dashboards' later pages at the largest text, a card's name, value or unit ("1.80", "Hemoglobin", "1:160").
    private static let cardText = "a card's text, a different one each run; " + measured
    private static let featureTitle = "a feature's title, a different one each run; " + measured

    private static let entries: [Entry] = [
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "Cholesterol in LDL (calculated)", reason: measured),
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "%", reason: measured),
        // A different card's text on each run ("24", "Cholesterol in HDL"): any, but each still measured.
        Entry(condition: "dashboard/light/ax5", type: .contrast, element: "", reason: measured),
        Entry(condition: "trends/light/ax5", type: .contrast, element: "32", reason: measured),
        Entry(condition: "history-load-failed/light/ax5", type: .contrast, element: "Couldn't Load Your Reports", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "1", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "2", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "4", reason: measured),
        // Rows' LOINC codes, grey on the card.
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "2345-7", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "4548-4", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "report-detail/light/ax5/2", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "report-detail/light/ax5/2", type: .contrast, element: "", reason: rowText),
        Entry(condition: "report-detail/light/ax5/2", type: .textClipped, element: nil, limit: 9, reason: rowTexts),
        Entry(condition: "sort-editor/light/ax5/1", type: .textClipped, element: nil, limit: 4, reason: rowTexts),
        Entry(condition: "settings/light/ax5", type: .contrast, element: nil, reason: unpointed),
        // The build card's version line: follows MARKETING_VERSION, so a version bump renames it here.
        Entry(condition: "settings/light/ax5/1", type: .contrast, element: "Version 2.0.1 (1)", reason: measured),
        // The build card's branch, which is another one on every build.
        Entry(condition: "settings/light/ax5/1", type: .contrast, element: "", limit: 1, reason: measured),
        Entry(condition: "settings/light/ax5/2", type: .contrast, element: "Search", reason: measured),
        // The About section's LOINC release ("2.82"), black on white: measured 21:1 when the audit flagged it.
        Entry(condition: "settings/light/ax5/5", type: .contrast, element: "2.82", reason: measured),
        // Rows the audit flags on one run and not the next, on whichever page they land: "Browse Catalog" measured
        // 21:1 and "About" 18.8:1 when it did, black on white.
        Entry(condition: "settings/light/ax5", type: .contrast, element: "Browse Catalog", reason: measured),
        Entry(condition: "settings/light/ax5", type: .contrast, element: "About", reason: measured),
        Entry(condition: "empty/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        // A category chip's count, grey on the chip's tint: measured 5.6:1 when the audit flagged it.
        Entry(condition: "review/light/ax5", type: .contrast, element: "1", reason: measured),
        Entry(condition: "review-planted-errors/light/ax5/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/ax5/2", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/ax5", type: .contrast, element: "", reason: rowText),
        Entry(condition: "review-planted-errors/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/dark/standard/1", type: .contrast, element: "", reason: rowText),
        Entry(condition: "empty/dark/standard", type: .contrast, element: "Choose File", reason: measured),
        // On the pages of the largest text after the first: white on the blue button, measured 5.6:1.
        Entry(condition: "empty/light/ax5", type: .contrast, element: "Choose File", reason: measured),
        // The same, measured 5.5:1 when the audit flagged it on the second page in one run.
        Entry(condition: "empty/light/ax5", type: .contrast, element: "Create Report Manually", reason: measured),
        Entry(condition: "empty/dark/standard", type: .contrast, element: "Create Report Manually", reason: measured),
        Entry(condition: "onboarding-welcome/dark/standard", type: .contrast,
              element: "Apple Intelligence reads your report privately — nothing leaves your device.", reason: measured),
        Entry(condition: "onboarding-welcome/dark/standard", type: .contrast,
              element: "Reports are stored as clinical documents directly in Apple Health.", reason: measured),
        Entry(condition: "onboarding-welcome/light/ax5", type: .contrast, element: "Import Any Lab Report", reason: measured),
        // The second feature's title, black on the card, measured 16.6:1 on the still screen (while a swipe still
        // moved the page under the audit, the hero's "Welcome to" was failed there instead).
        Entry(condition: "onboarding-welcome/light/ax5/2", type: .contrast, element: "On-Device AI", reason: measured),
        Entry(condition: "unsupported/light/ax5", type: .contrast,
              element: "LabImporter needs Apple Intelligence to read your lab reports privately on-device.", reason: measured),
        // A requirement's title, black on the card, measured 16.7:1.
        Entry(condition: "unsupported/light/ax5", type: .contrast, element: "Latest OS", reason: measured),
        // The pages after those a pull request audits (`QUALITY_AUDIT_ALL_PAGES`): black on white or grey on the
        // card, each measured 6:1 to 17.9:1 when the audit flagged it. Which card it flags varies run to run.
        Entry(condition: "dashboard-qualitative/light/ax5", type: .contrast, element: "", reason: cardText),
        Entry(condition: "dashboard-notices/light/ax5", type: .contrast, element: "", reason: cardText),
        Entry(condition: "dashboard-load-failed-stale/light/ax5", type: .contrast, element: "", reason: cardText),
        Entry(condition: "trends/light/ax5", type: .contrast, element: "25-hydroxyvitamin D3 [Mass/Vol]", reason: measured),
        Entry(condition: "trends/light/ax5", type: .contrast, element: "Vitamins & Minerals", reason: measured),
        Entry(condition: "trends/light/ax5", type: .contrast, element: "Calcidiol in Ser/Plas (MCnc)", reason: measured),
        Entry(condition: "report-detail/light/ax5", type: .contrast, element: "", reason: rowText),
        // Onboarding's feature titles, black on the card, a different one each run ("Always Verify", "Personal Context").
        Entry(condition: "onboarding-welcome/light/ax5", type: .contrast, element: "", reason: featureTitle),
        Entry(condition: "onboarding-disclaimer/light/ax5", type: .contrast, element: "", reason: featureTitle),
        Entry(condition: "onboarding-health/light/ax5", type: .contrast, element: "", reason: featureTitle),
        // Light at the standard size: plain black, grey `.textSecondary` (6:1) or blue on white, each screenshot checked.
        Entry(condition: "dashboard/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard/light/standard/1", type: .contrast, element: "%", reason: measured),
        // Measured 17.8:1 and 6.6:1 when the audit flagged them (black, and grey `.textSecondary`, on the card).
        Entry(condition: "dashboard/light/standard/1", type: .contrast, element: "Cholesterol in LDL (calculated)", reason: measured),
        Entry(condition: "dashboard/light/standard/1", type: .contrast, element: "mg/dL", reason: measured),
        Entry(condition: "dashboard-qualitative/light/standard/1", type: .contrast, element: "Cholesterol in LDL (calculated)", reason: measured),
        Entry(condition: "dashboard-qualitative/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard-qualitative/light/standard/1", type: .contrast, element: "May 28, 2026", reason: measured),
        Entry(condition: "dashboard-qualitative/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "dashboard-notices/light/standard/1", type: .contrast,
              element: "Reports saved by a newer version of LabImporter: 2. They're safe. Update the app to see them.", reason: measured),
        Entry(condition: "dashboard-notices/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        // The same cards as dashboard-qualitative's: since "TSH Qn" reads "TSH", the audit reports one node it can't point to here too.
        Entry(condition: "dashboard-notices/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "dashboard-load-failed-stale/light/standard/1", type: .contrast, element: "Try Again", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/standard/1", type: .contrast, element: "Cholesterol in LDL (calculated)", reason: measured),
        // What it finds is the x axis's dates: the first and last readings', which VoiceOver reads on those
        // points and in the chart's Audio Graph. Drawn without the axis labels, the chart raises nothing.
        Entry(
            condition: "trends/light/standard/1", type: .elementDetection, element: nil, limit: 1,
            reason: "the chart's x-axis dates, which are its first and last points' labels"
        ),
        Entry(condition: "history/light/standard/1", type: .contrast, element: "2026", reason: measured),
        Entry(condition: "history/light/standard/1", type: .contrast, element: "2025", reason: measured),
        Entry(condition: "history/light/standard/1", type: .contrast, element: "13 values", reason: measured),
        Entry(condition: "history/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "history-load-failed/light/standard/1", type: .contrast,
              element: "Your saved reports weren't changed, but they can't be read right now.", reason: measured),
        Entry(condition: "report-detail/light/standard/1", type: .contrast, element: "1", reason: measured),
        Entry(condition: "report-detail/light/standard/1", type: .contrast, element: "2", reason: measured),
        Entry(condition: "report-detail/light/standard/1", type: .contrast, element: "2093-3", reason: measured),
        Entry(condition: "report-detail/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review/light/standard/1", type: .contrast, element: "", reason: rowText),
        Entry(condition: "review-planted-errors/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/standard/1", type: .contrast, element: "", reason: rowText),
        Entry(condition: "empty/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "empty/light/standard/1", type: .contrast, element: "Choose File", reason: measured),
        Entry(condition: "empty/light/standard/1", type: .contrast, element: "Create Report Manually", reason: measured),
        Entry(condition: "onboarding-welcome/light/standard/1", type: .contrast,
              element: "Apple Intelligence reads your report privately — nothing leaves your device.", reason: measured),
        Entry(condition: "onboarding-welcome/light/standard/1", type: .contrast, element: "Saved to Apple Health", reason: measured),
        Entry(condition: "onboarding-welcome/light/standard/1", type: .contrast,
              element: "Reports are stored as clinical documents directly in Apple Health.", reason: measured),
        Entry(condition: "onboarding-disclaimer/light/standard/1", type: .contrast,
              element: "Values are extracted by on-device AI and OCR, which can misread or miss results.", reason: measured),
        Entry(condition: "onboarding-disclaimer/light/standard/1", type: .contrast,
              element: "Check every value against your original lab report before relying on it.", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast, element: "Save Reports to Health", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast,
              element: "Imported lab values are written as clinical documents into Apple Health.", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast,
              element: "Past reports are loaded back from Apple Health so you can review and share them.", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast,
              element: "Date of birth and biological sex help label your reports and contextualize values.", reason: measured),
        Entry(condition: "unsupported/light/standard/1", type: .contrast,
              element: "An iPhone with A17 Pro or an iPad with Apple silicon (M1 or later) is required.", reason: measured),
    ]

    /// Why the audit lets [issue] go, or nil. The caller (`count`) fails a contrast exemption naming its element
    /// that comes back without a ratio of 4.5:1, so measuring isn't up to this function alone; every rule here
    /// counts as unmeasured, and `static_metrics.py` counts them against the baseline's `rules`.
    static func reason(
        for issue: XCUIAccessibilityAuditIssue, element: XCUIElement?, on page: inout Page
    ) -> Granted? {
        let label = page.label
        if let element, issue.auditType == .contrast {
            if !page.visibleArea.contains(element.frame) {
                return .rule("partly scrolled under a bar or off screen, so the pixels measured aren't the element's")
            }
            if !element.isEnabled { return .rule("WCAG 1.4.3 excepts a control that's disabled") }
            // Only an end with content hidden past it fades: a chip resting at the row's start is drawn crisp and still counts.
            if page.sidewaysRows.contains(where: { $0.fades(element.frame) }) {
                return .rule("faded out or cut off at the end of a row that scrolls sideways; scrolled in, it's drawn in full")
            }
        }
        let name = element?.label
        // An entry that has let go all it may on this page hands on to the next that matches.
        guard let index = entries.indices.first(where: { index in
            let entry = entries[index]
            return (label + "/").hasPrefix(entry.condition + "/") && entry.type == issue.auditType
                && matches(entry.element, name) && page.used[index, default: 0] < entry.limit
        }) else { return nil }
        let entry = entries[index]
        // A contrast entry for an element lets it go only as long as its pixels measure readable, on every run. Only
        // one naming the element counts as measured and stays out of the baseline; "" (any) is listed there.
        if entry.type == .contrast, entry.element != nil {
            guard let element, let ratio = PixelContrast.of(element), ratio >= minimumContrast else { return nil }
            page.used[index, default: 0] += 1
            return Granted(
                reason: "\(entry.reason) (\(String(format: "%.1f", ratio)):1)", namesContrastElement: entry.element?.isEmpty == false, ratio: ratio
            )
        }
        // One without an element, only while every text on the page measures readable.
        var ratio: Double?
        if entry.type == .contrast {
            guard let lowest = page.texts?.lowest, lowest >= minimumContrast else { return nil }
            ratio = lowest
        }
        page.used[index, default: 0] += 1
        return Granted(reason: entry.reason, ratio: ratio)
    }

    /// An entry's element against the issue's: its full label, never the start of one ("" any element,
    /// nil an issue without one). A prefix would let go every label that starts with it.
    static func matches(_ entryElement: String?, _ name: String?) -> Bool {
        guard let entryElement else { return name == nil }
        return entryElement.isEmpty ? name != nil : name == entryElement
    }
}

/// The exemptions one test applied: all, measured on that run, the rest unmeasured, and of those a rule's.
private struct ExemptionTally {
    var applied = 0
    var measured = 0
    var unmeasured = 0
    var rules = 0
}

/// What an audit found, handed out of its handler, which runs on the same thread.
/// An issue holds its element weakly, and it's gone once the audit returns.
private final class FoundIssues: @unchecked Sendable {
    var issues: [(issue: XCUIAccessibilityAuditIssue, element: XCUIElement?)] = []
}
