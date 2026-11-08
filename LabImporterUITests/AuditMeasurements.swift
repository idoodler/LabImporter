import CoreGraphics
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

enum PixelContrast {
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
