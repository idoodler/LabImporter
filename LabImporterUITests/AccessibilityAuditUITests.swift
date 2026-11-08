import XCTest

/// Runs Apple's own accessibility audit (contrast, hit regions, element descriptions,
/// traits, clipped text) over every screen with the sample data at the largest
/// accessibility text, and again in dark at the standard size for the screens whose
/// tinted cards, badges, chips or buttons take other colours there. An issue fails the
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

                // Every screen at the standard size too, for Dynamic Type. Audited there in dark, where
                // tinted surfaces take other colours; the others in light are measured only.
                let standardApp = launch(screen, size: .standard, dark: screen.hasTints)
                assertTextsGrow(from: try textHeights(in: standardApp), to: largest, on: screen)
                if screen.name == "review-planted-errors" {
                    assertEveryValueFieldSpeaksItsTest(in: standardApp)
                }
                if screen.hasTints {
                    try audit(standardApp, of: screen, label: "\(screen.name)/dark/standard/1")
                }
                standardApp.terminate()
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
        var page = AuditExemption.Page(label: label, visibleArea: visible, everyTextReadable: faint?.isEmpty ?? true)
        for (issue, element) in found.issues {
            let exemption = AuditExemption.reason(for: issue, element: element, on: &page)
            let name = element?.label ?? ""
            let frame = element.map { "\($0.frame)" } ?? "no element"
            let verdict = exemption.map { "exempt (\($0))" } ?? "issue"
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
    /// again, so it's asked for one kind of issue at a time.
    private func runAudit(
        of app: XCUIApplication, label: String, evidence: URL?, types: XCUIAccessibilityAuditType = AccessibilityAuditUITests.auditTypes
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
        let reason: String
    }

    /// The page being audited, and how many issues each entry has let go on it.
    struct Page {
        let label: String
        let visibleArea: CGRect
        /// Whether `faintTexts` found every text readable.
        let everyTextReadable: Bool
        fileprivate var used: [Int: Int] = [:]
    }

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

    private static let entries: [Entry] = [
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "Cholesterol in LDL Calc", reason: measured),
        Entry(condition: "dashboard/dark/standard/1", type: .contrast, element: "%", reason: measured),
        // A different card's text on each run ("24", "Cholesterol in HDL"): any, but each still measured.
        Entry(condition: "dashboard/light/ax5", type: .contrast, element: "", reason: measured),
        Entry(condition: "dashboard-qualitative/light/ax5", type: .contrast, element: "188", reason: measured),
        Entry(condition: "dashboard-notices/light/ax5", type: .contrast, element: "25-hydroxyvitamin D3", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/ax5", type: .contrast, element: "ALT", reason: measured),
        Entry(condition: "dashboard-load-failed-stale/light/ax5", type: .contrast, element: "Cholesterol", reason: measured),
        Entry(condition: "trends/light/ax5", type: .contrast, element: "32", reason: measured),
        Entry(condition: "history-load-failed/light/ax5", type: .contrast, element: "Couldn't Load Your Reports", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "1", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "2", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: "4", reason: measured),
        Entry(condition: "report-detail/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "report-detail/light/ax5/2", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "report-detail/light/ax5/2", type: .textClipped, element: nil, limit: 9, reason: rowTexts),
        Entry(condition: "sort-editor/light/ax5/1", type: .textClipped, element: nil, limit: 4, reason: rowTexts),
        Entry(condition: "settings/light/ax5", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "empty/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/ax5/1", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/light/ax5/2", type: .contrast, element: nil, reason: unpointed),
        Entry(condition: "review-planted-errors/dark/standard/1", type: .contrast, element: nil, reason: unpointed),
        // The checkbox, LOINC code, Confirm and value field of the rows on screen: each draws smaller than
        // 44 pt and takes taps 44 pt tall, reaching over the row's spacing (`tapTargetButton`, `valueField`).
        // At the largest size they're taller than 44 pt and reach over nothing, and the audit finds none there.
        Entry(
            condition: "review-planted-errors/dark/standard/1", type: .hitRegion, element: nil, limit: 5,
            reason: "a row control whose 44 pt tap target reaches over the row's spacing"
        ),
        Entry(condition: "empty/dark/standard", type: .contrast, element: "Choose File", reason: measured),
        Entry(condition: "empty/dark/standard", type: .contrast, element: "Create Report Manually", reason: measured),
        Entry(condition: "onboarding-welcome/dark/standard", type: .contrast, element: "Apple Intelligence reads your report", reason: measured),
        Entry(condition: "onboarding-welcome/dark/standard", type: .contrast, element: "Reports are stored as clinical documents", reason: measured),
        Entry(condition: "onboarding-welcome/light/ax5", type: .contrast, element: "Import Any Lab Report", reason: measured),
        Entry(condition: "unsupported/light/ax5", type: .contrast, element: "LabImporter needs Apple Intelligence", reason: measured),
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
        }
        let name = element?.label
        guard let index = entries.firstIndex(where: { entry in
            (label + "/").hasPrefix(entry.condition + "/") && entry.type == issue.auditType
                && (entry.element.map { name?.hasPrefix($0) ?? false } ?? (name == nil))
        }) else { return nil }
        let entry = entries[index]
        guard page.used[index, default: 0] < entry.limit else { return nil }
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
               let ratio = contrast(of: crop) {
                let needed = frame.height >= 24 ? 3.0 : 4.5
                if ratio < needed { faint.append("\(element.label.prefix(40)) \(String(format: "%.1f", ratio)):1 @ \(frame)") }
            }
            // A row that scrolls sideways fades its ends out (`edgeFadeScroll`): what's there is scrolled in to be read.
            var inner = area
            if element.elementType == .scrollView, element.children.map(\.frame).reduce(CGRect.null, { $0.union($1) }).width > frame.width + 1 {
                inner = area.intersection(frame.insetBy(dx: 24, dy: 0))
            }
            element.children.forEach { visit($0, within: inner) }
        }
        visit(snapshot, within: visibleArea)
        return faint
    }

    /// WCAG contrast between the element's background (its median pixel) and its ink, the
    /// percentile furthest from it: what a person's eye gets.
    private static func measuredContrast(of element: XCUIElement) -> Double? {
        element.screenshot().image.cgImage.flatMap(contrast(of:))
    }

    private static func contrast(of image: CGImage) -> Double? {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else { return nil }
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn: Void? = pixels.withUnsafeMutableBytes { buffer in
            CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
            )?.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        guard drawn != nil else { return nil }
        let luminances = stride(from: 0, to: pixels.count, by: 4).map { index in
            relativeLuminance(red: pixels[index], green: pixels[index + 1], blue: pixels[index + 2])
        }.sorted()
        let background = luminances[luminances.count / 2]
        let darkest = luminances[luminances.count / 500]
        let lightest = luminances[luminances.count - 1 - luminances.count / 500]
        return max(ratio(background, darkest), ratio(background, lightest))
    }

    private static func ratio(_ first: Double, _ second: Double) -> Double {
        (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private static func relativeLuminance(red: UInt8, green: UInt8, blue: UInt8) -> Double {
        func linear(_ value: UInt8) -> Double {
            let channel = Double(value) / 255
            return channel <= 0.03928 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
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
