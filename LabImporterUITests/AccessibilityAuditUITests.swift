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
final class AccessibilityAuditUITests: XCTestCase {
    private enum Size: String {
        case standard = "UICTContentSizeCategoryL"
        case largestAccessibility = "UICTContentSizeCategoryAccessibilityXXXL"
    }

    /// The probe is replaced by `assertTextsGrow`, see above.
    private static let auditTypes = XCUIAccessibilityAuditType.all.subtracting(.dynamicType)

    override func setUp() {
        continueAfterFailure = true
    }

    @MainActor
    func testEveryScreen() throws {
        for screen in QualityScreen.selected {
            try XCTContext.runActivity(named: screen.name) { _ in
                // `--ss-appearance` sets the app's appearance: switching the simulator's costs seconds a screen.
                let app = launch(screen, size: .largestAccessibility, dark: false)
                var largest: [String: CGFloat] = [:]
                var valueFields = 0
                // Text at the largest size takes pages: where what's below the fold differs, it's audited too.
                try forEachPage(of: app, limit: screen.auditedPages) { page in
                    largest.merge(try textHeights(in: app)) { first, _ in first }
                    if screen.name == "review-planted-errors" {
                        valueFields += try assertValueFieldsDrawOverNothing(in: app)
                    }
                    try audit(app, of: screen, label: "\(screen.name)/light/ax5/\(page)")
                }
                app.terminate()
                if screen.name == "review-planted-errors" {
                    XCTAssertGreaterThan(valueFields, 0, "No value field was on screen at the largest size")
                }

                // Every screen at the standard size too: measured for Dynamic Type and audited in light, and
                // again in dark where tinted surfaces take other colours.
                let standardApp = launch(screen, size: .standard, dark: false)
                assertTextsGrow(from: try textHeights(in: standardApp), to: largest, on: screen)
                if screen.name == "review-planted-errors" {
                    assertEveryValueFieldSpeaksItsTest(in: standardApp)
                }
                try audit(standardApp, of: screen, label: "\(screen.name)/light/standard/1")
                standardApp.terminate()
                if screen.hasTints {
                    let darkApp = launch(screen, size: .standard, dark: true)
                    try audit(darkApp, of: screen, label: "\(screen.name)/dark/standard/1")
                    darkApp.terminate()
                }
            }
        }
    }

    private func launch(_ screen: QualityScreen, size: Size, dark: Bool) -> XCUIApplication {
        screen.launch(extraArguments: ["-UIPreferredContentSizeCategoryName", size.rawValue, "--ss-appearance", dark ? "dark" : "light"])
    }

    /// The audit can't tell a label that's there from one that says which of twenty rows it is.
    private func assertEveryValueFieldSpeaksItsTest(in app: XCUIApplication) {
        let fields = app.textFields.matching(identifier: "review-value-field").allElementsBoundByIndex
        XCTAssertFalse(fields.isEmpty)
        for field in fields {
            XCTAssertNotEqual(field.label, "Value")
            XCTAssertFalse(field.label.isEmpty)
        }
        XCTAssertTrue(fields.contains { $0.label == "Glucose" }, "\(fields.map { $0.label })")
    }

    /// A field whose tap target reaches over its row draws over the unit stacked under it once its
    /// text is taller than the target: at the largest size no other text may lie inside a field.
    /// Returns how many fields it looked at.
    @MainActor
    private func assertValueFieldsDrawOverNothing(in app: XCUIApplication) throws -> Int {
        var fields: [CGRect] = []
        var texts: [(label: String, frame: CGRect)] = []
        func collect(_ element: XCUIElementSnapshot) {
            if element.elementType == .textField, element.identifier == "review-value-field" {
                fields.append(element.frame)
            } else if element.elementType == .staticText, !element.label.isEmpty {
                texts.append((element.label, element.frame))
            }
            element.children.forEach(collect)
        }
        collect(try app.snapshot())
        for field in fields {
            for text in texts {
                let overlap = field.intersection(text.frame)
                let drawsOver = !overlap.isNull && overlap.height > 1 && overlap.width > 1
                XCTAssertFalse(drawsOver, "A value field draws over \"\(text.label)\": \(field) / \(text.frame)")
            }
        }
        return fields.count
    }

    /// Fixed-size text would stay the same height at the largest size; wrapped text only grows more.
    private func assertTextsGrow(from standard: [String: CGFloat], to largest: [String: CGFloat], on screen: QualityScreen) {
        XCTAssertFalse(standard.isEmpty, screen.name)
        for (text, height) in standard {
            guard let grown = largest[text] else { continue }
            print("AUDIT scale \(screen.name) \(text): \(height) -> \(grown)")
            XCTAssertGreaterThan(grown, height * 1.2, "\(screen.name): \"\(text)\" doesn't grow with Dynamic Type")
        }
    }

    @MainActor
    private func textHeights(in app: XCUIApplication) throws -> [String: CGFloat] {
        var heights: [String: CGFloat] = [:]
        // One snapshot of the tree: asking every element for its frame takes minutes a screen.
        func collect(_ element: XCUIElementSnapshot) {
            // A navigation bar's title is the system's to draw.
            guard element.elementType != .navigationBar else { return }
            if element.elementType == .staticText, !element.label.isEmpty {
                heights[element.label] = element.frame.height
            }
            element.children.forEach(collect)
        }
        collect(try app.snapshot())
        return heights
    }

    @MainActor
    private func audit(_ app: XCUIApplication, of screen: QualityScreen, label: String) throws {
        let evidence = ProcessInfo.processInfo.environment["QUALITY_AUDIT_EVIDENCE"].map { URL(fileURLWithPath: $0, isDirectory: true) }
        let visible = AuditExemption.visibleArea(of: app)
        // Judged after the audit: one that waits for screenshots of its findings runs out of time.
        guard let found = runAudit(of: app, label: label, evidence: evidence) else { return }
        // An issue without an element can't be measured itself: it's let go only while every text on the
        // screen measures readable, so a faint text the audit can't point to still fails.
        var faint: [String]?
        if found.issues.contains(where: { $0.element == nil && $0.issue.auditType == .contrast }) {
            let measured = AuditExemption.faintTexts(in: app, visibleArea: visible)
            measured.forEach { print("AUDIT faint \(label) \($0)") }
            faint = measured
        }
        // Where a row's chips fade out at its ends: only asked for when there's a contrast issue it could explain.
        let pointsAtText = found.issues.contains { $0.element != nil && $0.issue.auditType == .contrast }
        let sideways = pointsAtText ? SidewaysRow.all(in: app) : []
        sideways.forEach { print("AUDIT sideways \(label) \($0.frame) content \($0.content) fades left \($0.fadesLeft) right \($0.fadesRight)") }
        // What issues without an element are budgeted by: only counted when there's one.
        let unpointedHits = found.issues.contains { $0.element == nil && $0.issue.auditType == .hitRegion }
        let onScreen = unpointedHits ? AuditExemption.countOnScreen(in: app) : [:]
        onScreen.forEach { print("AUDIT on screen \(label) \($0.key): \($0.value)") }
        var page = AuditExemption.Page(
            label: label, visibleArea: visible, everyTextReadable: faint?.isEmpty ?? true, sidewaysRows: sideways, onScreen: onScreen
        )
        for (issue, element) in found.issues {
            let exemption = AuditExemption.reason(for: issue, element: element, on: &page)
            let name = element?.label ?? ""
            let frame = element.map { "\($0.frame)" } ?? "no element"
            // What its pixels measure says whether the audit or the colour is wrong.
            let measured = exemption == nil && issue.auditType == .contrast ? element.flatMap(AuditExemption.measuredContrast(of:)) : nil
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

    /// The audit gives up on a long screen ("failed to complete in time"): asked again it gives up
    /// again, so it's asked for one kind of issue at a time. One kind gets one more go: a page's audit
    /// takes about 3 s, and the one that once gave up (the dashboard's second page at the largest text)
    /// had hung for its whole 30 s, with nothing on screen still moving.
    private func runAudit(
        of app: XCUIApplication, label: String, evidence: URL?, types: XCUIAccessibilityAuditType = AccessibilityAuditUITests.auditTypes,
        isRetry: Bool = false
    ) -> FoundIssues? {
        let found = FoundIssues()
        do {
            try app.performAccessibilityAudit(for: types) { issue in
                found.issues.append((issue, issue.element))
                return evidence == nil || issue.element != nil
            }
        } catch let error as NSError where error.code == -56 && types.rawValue.nonzeroBitCount > 1 {
            // What it found before giving up comes again, from the part that finds it.
            found.issues.removeAll()
            for bit in 0..<UInt64.bitWidth where types.rawValue & (1 << bit) != 0 {
                guard let part = runAudit(of: app, label: label, evidence: evidence, types: XCUIAccessibilityAuditType(rawValue: 1 << bit)) else {
                    return nil
                }
                found.issues += part.issues
            }
        } catch let error as NSError where error.code == -56 && !isRetry {
            print("AUDIT retry \(label) [\(types.rawValue)] after \(error.localizedDescription)")
            waitUntilSettled(app)
            return runAudit(of: app, label: label, evidence: evidence, types: types, isRetry: true)
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
        /// The start of the element's label ("" for any); nil for an issue the audit found without an element.
        let element: String?
        /// How many issues of a page it covers, for one nothing re-measures each run: the number its screenshots were checked for.
        var limit = Int.max
        /// The identifier of what each issue comes from, when that's countable: then the limit is how many of
        /// those are on screen on this device and page, not a number calibrated on another simulator.
        var perElement: String?
        let reason: String
    }

    /// The page being audited, and how many issues each entry has let go on it.
    struct Page {
        let label: String
        let visibleArea: CGRect
        /// Whether `faintTexts` found every text readable.
        let everyTextReadable: Bool
        /// The rows on screen that scroll sideways (`edgeFadeScroll`).
        let sidewaysRows: [SidewaysRow]
        /// How many elements with each `Entry.perElement` identifier are on screen.
        let onScreen: [String: Int]
        fileprivate var used: [Int: Int] = [:]
    }

    /// Every Review value row has one LOINC code button (a row without a value has no value field)
    /// and one swipe-to-delete button.
    static let valueRow = "review-code-button"
    static let compareBadgeID = "review-compare-badge"

    // The audit's contrast check fails text whose screenshot shows plain black on white or
    // white on near-black (black "Couldn't Load Your Reports" on white, white "2026" on the dark
    // wash): measured, every one of these is over 4.5:1, and `measuredContrast` checks it again
    // each run, so a real regression still fails.
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
    // A Review row's Delete, which a swipe brings in: the system keeps its button folded to nothing until
    // then, and it opens as tall as the row. Built without the swipe action (or with `onDelete`) the rows
    // raise none; every row control on screen measures 44 pt or more. Its count is the rows on screen.
    private static let swipeDelete = "a row's swipe-to-delete button, folded away until the row is swiped open"
    // Review's "Compare with report" badge, 17 pt tall: text with no action (hidden from accessibility, it
    // raises nothing; as static text or combined it still does), and the LOINC code's and Confirm's targets
    // above it keep clear of it (`badgeSpacing`).
    private static let compareBadge = "the \"Compare with report\" badge, which is text, not a control"

    private static let entries: [Entry] = [
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "Cholesterol in LDL Calc", reason: measured),
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "%", reason: measured),
        // A different card's text on each run ("24", "Cholesterol in HDL"): any, but each still measured.
        Entry(condition: "dashboard/light/ax5", type: .contrast, element: "", reason: measured),
        Entry(condition: "dashboard-qualitative/light/ax5", type: .contrast, element: "188", reason: measured),
        Entry(condition: "dashboard-notices/light/ax5", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard-notices/light/ax5", type: .contrast, element: "Update LabImporter to See All Reports", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/ax5", type: .contrast, element: "ALT", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/ax5", type: .contrast, element: "Cholesterol", reason: measured),
        Entry(condition: "trends/light/ax5", type: .contrast, element: "32", reason: measured),
        Entry(condition: "history-load-failed/light/ax5", type: .contrast, element: "Couldn't Load Your Reports", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "1", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "2", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "4", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "report-detail/light/ax5/2", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "report-detail/light/ax5/2", type: .contrast, element: "", reason: rowText),
        Entry(condition: "report-detail/light/ax5/2", type: .textClipped, element: nil, limit: 9, reason: rowTexts),
        Entry(condition: "sort-editor/light/ax5/1", type: .textClipped, element: nil, limit: 4, reason: rowTexts),
        Entry(condition: "settings/light/ax5", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "settings/light/ax5/1", type: .contrast, element: "Version", reason: measured),
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
        Entry(condition: "review-planted-errors/light/ax5/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/ax5/2", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/ax5", type: .contrast, element: "", reason: rowText),
        Entry(condition: "review-planted-errors/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/dark/standard/1", type: .contrast, element: "", reason: rowText),
        Entry(condition: "review-planted-errors/dark/standard/1", type: .hitRegion, element: nil, perElement: valueRow, reason: swipeDelete),
        Entry(condition: "review-planted-errors/dark/standard/1", type: .hitRegion, element: nil, perElement: compareBadgeID, reason: compareBadge),
        Entry(condition: "empty/dark/standard", type: .contrast, element: "Choose File", reason: measured),
        Entry(condition: "empty/dark/standard", type: .contrast, element: "Create Report Manually", reason: measured),
        Entry(condition: "onboarding-welcome/dark/standard", type: .contrast, element: "Apple Intelligence reads your report", reason: measured),
        Entry(condition: "onboarding-welcome/dark/standard", type: .contrast, element: "Reports are stored as clinical documents", reason: measured),
        Entry(condition: "onboarding-welcome/light/ax5", type: .contrast, element: "Import Any Lab Report", reason: measured),
        Entry(condition: "onboarding-welcome/light/ax5/2", type: .contrast, element: "Welcome to", reason: measured),
        Entry(condition: "onboarding-welcome/light/ax5/2", type: .contrast, element: "LabImporter", reason: measured),
        Entry(condition: "unsupported/light/ax5", type: .contrast, element: "LabImporter needs Apple Intelligence", reason: measured),
        // Light at the standard size: plain black, grey `.textSecondary` (6:1) or blue on white, each screenshot checked.
        Entry(condition: "dashboard/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard/light/standard/1", type: .contrast, element: "%", reason: measured),
        Entry(condition: "dashboard-qualitative/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard-qualitative/light/standard/1", type: .contrast, element: "May 28, 2026", reason: measured),
        Entry(condition: "dashboard-qualitative/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "dashboard-notices/light/standard/1", type: .contrast, element: "Reports saved by a newer version", reason: measured),
        Entry(condition: "dashboard-notices/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        // The same cards as dashboard-qualitative's: since "TSH Qn" reads "TSH", the audit reports one node it can't point to here too.
        Entry(condition: "dashboard-notices/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "dashboard-load-failed-stale/light/standard/1", type: .contrast, element: "Try Again", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/standard/1", type: .contrast, element: "Cholesterol in LDL Calc", reason: measured),
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
        Entry(condition: "history-load-failed/light/standard/1", type: .contrast, element: "Your saved reports weren't changed", reason: measured),
        Entry(condition: "report-detail/light/standard/1", type: .contrast, element: "1", reason: measured),
        Entry(condition: "report-detail/light/standard/1", type: .contrast, element: "2", reason: measured),
        Entry(condition: "report-detail/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review/light/standard/1", type: .contrast, element: "", reason: rowText),
        Entry(condition: "review/light/standard/1", type: .hitRegion, element: nil, perElement: valueRow, reason: swipeDelete),
        Entry(condition: "review-planted-errors/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/standard/1", type: .contrast, element: "", reason: rowText),
        Entry(condition: "review-planted-errors/light/standard/1", type: .hitRegion, element: nil, perElement: valueRow, reason: swipeDelete),
        Entry(condition: "review-planted-errors/light/standard/1", type: .hitRegion, element: nil, perElement: compareBadgeID, reason: compareBadge),
        Entry(condition: "empty/light/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "empty/light/standard/1", type: .contrast, element: "Choose File", reason: measured),
        Entry(condition: "empty/light/standard/1", type: .contrast, element: "Create Report Manually", reason: measured),
        Entry(condition: "onboarding-welcome/light/standard/1", type: .contrast, element: "Apple Intelligence reads your report", reason: measured),
        Entry(condition: "onboarding-welcome/light/standard/1", type: .contrast, element: "Saved to Apple Health", reason: measured),
        Entry(condition: "onboarding-welcome/light/standard/1", type: .contrast, element: "Reports are stored as clinical documents", reason: measured),
        Entry(condition: "onboarding-disclaimer/light/standard/1", type: .contrast, element: "Values are extracted by on-device AI", reason: measured),
        Entry(condition: "onboarding-disclaimer/light/standard/1", type: .contrast, element: "Check every value against", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast, element: "Save Reports to Health", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast, element: "Imported lab values are written", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast, element: "Past reports are loaded back", reason: measured),
        Entry(condition: "onboarding-health/light/standard/1", type: .contrast, element: "Date of birth and biological sex", reason: measured),
        Entry(condition: "unsupported/light/standard/1", type: .contrast, element: "An iPhone with A17 Pro", reason: measured),
    ]

    static func reason(
        for issue: XCUIAccessibilityAuditIssue, element: XCUIElement?, on page: inout Page
    ) -> String? {
        let label = page.label
        if let element, issue.auditType == .contrast {
            if !page.visibleArea.contains(element.frame) {
                return "partly scrolled under a bar or off screen, so the pixels measured aren't the element's"
            }
            if !element.isEnabled { return "WCAG 1.4.3 excepts a control that's disabled" }
            // Only an end with content hidden past it fades: a chip resting at the row's start is drawn crisp and still counts.
            if page.sidewaysRows.contains(where: { $0.fades(element.frame) }) {
                return "faded out or cut off at the end of a row that scrolls sideways; scrolled in, it's drawn in full"
            }
        }
        let name = element?.label
        // An entry that has let go all it may on this page hands on to the next that matches.
        guard let index = entries.indices.first(where: { index in
            let entry = entries[index]
            return (label + "/").hasPrefix(entry.condition + "/") && entry.type == issue.auditType
                && (entry.element.map { name?.hasPrefix($0) ?? false } ?? (name == nil))
                && page.used[index, default: 0] < entry.perElement.map { page.onScreen[$0, default: 0] } ?? entry.limit
        }) else { return nil }
        let entry = entries[index]
        if let element, entry.type == .contrast {
            guard let ratio = measuredContrast(of: element), ratio >= 4.5 else { return nil }
            page.used[index, default: 0] += 1
            return "\(entry.reason) (\(String(format: "%.1f", ratio)):1)"
        }
        if element == nil, entry.type == .contrast, !page.everyTextReadable { return nil }
        page.used[index, default: 0] += 1
        return entry.reason
    }

    /// Every text, button and field on screen whose pixels measure under 4.5:1 (3:1 for large text,
    /// 24 pt and taller), as "label ratio:1". Disabled controls and what a bar covers are left out.
    @MainActor
    static func faintTexts(in app: XCUIApplication, visibleArea: CGRect) -> [String] {
        guard let snapshot = try? app.snapshot(), let image = app.screenshot().image.cgImage else { return ["no screenshot"] }
        let scale = CGFloat(image.width) / app.windows.firstMatch.frame.width
        var faint: [String] = []
        func visit(_ element: XCUIElementSnapshot, within area: CGRect) {
            let kinds: [XCUIElement.ElementType] = [.staticText, .button, .textField, .link, .cell]
            let frame = element.frame
            if kinds.contains(element.elementType), element.isEnabled, !element.label.isEmpty,
               frame.width >= 4, frame.height >= 4, area.contains(frame),
               let crop = image.cropping(to: CGRect(x: frame.minX * scale, y: frame.minY * scale, width: frame.width * scale, height: frame.height * scale)),
               let ratio = PixelContrast.of(crop) {
                let needed = frame.height >= 24 ? 3.0 : 4.5
                if ratio < needed { faint.append("\(element.label.prefix(40)) \(String(format: "%.1f", ratio)):1 @ \(frame)") }
            }
            // A row that scrolls sideways fades out the ends that have more past them (`edgeFadeScroll`):
            // what's there is scrolled in to be read. An end with nothing past it is crisp and measured.
            var inner = area
            if let row = SidewaysRow(element) {
                inner = area.intersection(row.unfaded)
            }
            element.children.forEach { visit($0, within: inner) }
        }
        visit(snapshot, within: visibleArea)
        return faint
    }

    /// How many of the elements entries budget by (`Entry.perElement`) reach into the window: a row
    /// partly on screen counts, as its folded button may be too.
    @MainActor
    static func countOnScreen(in app: XCUIApplication) -> [String: Int] {
        guard let snapshot = try? app.snapshot() else { return [:] }
        let window = app.windows.firstMatch.frame
        let counted = Set(entries.compactMap(\.perElement))
        var counts: [String: Int] = [:]
        func visit(_ element: XCUIElementSnapshot) {
            if counted.contains(element.identifier), element.frame.intersection(window).height > 1 {
                counts[element.identifier, default: 0] += 1
            }
            element.children.forEach(visit)
        }
        visit(snapshot)
        return counts
    }

    /// WCAG contrast between the element's background (its median pixel) and its ink, the
    /// percentile furthest from it: what a person's eye gets.
    static func measuredContrast(of element: XCUIElement) -> Double? {
        element.screenshot().image.cgImage.flatMap(PixelContrast.of)
    }

    /// Between the navigation bar and Review's pinned action bar: content scrolled under their glass is blurred on purpose.
    static func visibleArea(of app: XCUIApplication) -> CGRect {
        var area = app.windows.firstMatch.frame
        let bar = app.navigationBars.firstMatch
        if bar.exists {
            let top = bar.frame.maxY
            area = CGRect(x: area.minX, y: top, width: area.width, height: area.maxY - top)
        }
        let actionBar = app.otherElements["review-action-bar"]
        if actionBar.exists {
            // Its material starts fading in a little above it.
            area.size.height = actionBar.frame.minY - 24 - area.minY
        }
        return area
    }
}

/// What an audit found, handed out of its handler, which runs on the same thread.
/// An issue holds its element weakly, and it's gone once the audit returns.
private final class FoundIssues: @unchecked Sendable {
    var issues: [(issue: XCUIAccessibilityAuditIssue, element: XCUIElement?)] = []
}
