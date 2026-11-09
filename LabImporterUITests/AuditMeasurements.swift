import CoreGraphics
import UIKit
import XCTest
// The XCUI types live here since Xcode 26; XCTest only re-exports them (`swiftlint analyze` wants the real module).
import XCUIAutomation

// What `AccessibilityAuditUITests` measures itself rather than taking the audit's word for.

/// A row that scrolls sideways, and which of its ends `edgeFadeScroll` fades: those with content past them.
struct SidewaysRow {
    /// How wide `edgeFadeScroll` fades a row's end.
    private static let fadeWidth: CGFloat = 24

    let frame: CGRect
    /// Where its content lies, scrolled in or not.
    let content: CGRect
    let fadesLeft: Bool
    let fadesRight: Bool

    /// A scroll view whose content is wider than it; nil for anything else.
    @MainActor init?(_ element: XCUIElementSnapshot) {
        let content = element.children.map(\.frame).reduce(CGRect.null) { $0.union($1) }
        guard element.elementType == .scrollView, content.width > element.frame.width + 1 else { return nil }
        frame = element.frame
        self.content = content
        // Measured from the content, not from which end is "leading": the same in right-to-left.
        fadesLeft = content.minX < frame.minX - 1
        fadesRight = content.maxX > frame.maxX + 1
    }

    /// Whether something at `element` reaches into an end that's faded now, or lies past it, clipped away.
    func fades(_ element: CGRect) -> Bool {
        (frame.minY...frame.maxY).contains(element.midY)
            && ((fadesLeft && element.minX < frame.minX + Self.fadeWidth - 1) || (fadesRight && element.maxX > frame.maxX - Self.fadeWidth + 1))
    }

    /// The row without its faded ends.
    var unfaded: CGRect {
        let left = fadesLeft ? Self.fadeWidth : 0
        let right = fadesRight ? Self.fadeWidth : 0
        return CGRect(x: frame.minX + left, y: frame.minY, width: frame.width - left - right, height: frame.height)
    }

    /// Every such row on screen.
    @MainActor
    static func all(in app: XCUIApplication) -> [SidewaysRow] {
        guard let snapshot = try? app.snapshot() else { return [] }
        var rows: [SidewaysRow] = []
        func visit(_ element: XCUIElementSnapshot) {
            if let row = SidewaysRow(element) { rows.append(row) }
            element.children.forEach(visit)
        }
        visit(snapshot)
        return rows
    }
}

/// Every text, button and field on a screen, measured by its pixels (`PixelContrast`).
struct TextContrast {
    /// WCAG 1.4.3's 4.5:1, for every text: its 3:1 for large text isn't used, since an element's frame
    /// says how tall it is (a two-line paragraph is 24 pt too), not how large its type is.
    static let minimum = 4.5

    /// The lowest ratio of any text on the page; nil when nothing could be measured.
    let lowest: Double?
    /// Each text under `minimum`, as "label ratio:1 @ frame".
    let faint: [String]

    /// Every text, button and field in `visibleArea`, measured. Disabled controls are left out.
    @MainActor
    static func measure(in app: XCUIApplication, visibleArea: CGRect) -> TextContrast {
        guard let snapshot = try? app.snapshot(), let image = app.screenshot().image.cgImage else {
            return TextContrast(lowest: nil, faint: ["no screenshot"])
        }
        let scale = CGFloat(image.width) / app.windows.firstMatch.frame.width
        var faint: [String] = []
        var lowest: Double?
        func visit(_ element: XCUIElementSnapshot, within area: CGRect) {
            let kinds: [XCUIElement.ElementType] = [.staticText, .button, .textField, .link, .cell]
            let frame = element.frame
            if kinds.contains(element.elementType), element.isEnabled, !element.label.isEmpty,
               frame.width >= 4, frame.height >= 4, area.contains(frame),
               let crop = image.cropping(to: CGRect(x: frame.minX * scale, y: frame.minY * scale, width: frame.width * scale, height: frame.height * scale)),
               let ratio = PixelContrast.of(crop) {
                lowest = min(lowest ?? ratio, ratio)
                if ratio < minimum { faint.append("\(element.label.prefix(40)) \(String(format: "%.1f", ratio)):1 @ \(frame)") }
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
        return TextContrast(lowest: lowest, faint: faint)
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

enum PixelContrast {
    /// The element's pixels, measured as `of(_:)` measures an image.
    static func of(_ element: XCUIElement) -> Double? {
        element.screenshot().image.cgImage.flatMap(of)
    }

    /// WCAG contrast between the image's background (its median pixel) and its ink, the
    /// percentile furthest from it: what a person's eye gets.
    static func of(_ image: CGImage) -> Double? {
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
}

/// What the audit checks on each page besides Apple's audit.
extension AccessibilityAuditUITests {
    /// The audit can't tell a label that's there from one that says which of twenty rows it is.
    func assertEveryValueFieldSpeaksItsTest(in app: XCUIApplication) {
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
    func assertValueFieldsDrawOverNothing(in app: XCUIApplication) throws -> Int {
        var fields: [CGRect] = []
        var texts: [(label: String, frame: CGRect)] = []
        func collect(_ element: XCUIElementSnapshot) {
            // A field scrolled up under the navigation bar passes behind its title (seen on the later pages).
            guard element.elementType != .navigationBar else { return }
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
    func assertTextsGrow(from standard: [String: CGFloat], to largest: [String: CGFloat], on screen: QualityScreen) {
        XCTAssertFalse(standard.isEmpty, screen.name)
        for (text, height) in standard {
            guard let grown = largest[text] else { continue }
            print("AUDIT scale \(screen.name) \(text): \(height) -> \(grown)")
            XCTAssertGreaterThan(grown, height * 1.2, "\(screen.name): \"\(text)\" doesn't grow with Dynamic Type")
        }
    }

    @MainActor
    func textHeights(in app: XCUIApplication) throws -> [String: CGFloat] {
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
}
