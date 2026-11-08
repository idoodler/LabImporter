import UIKit
import XCTest

/// Saves every screen, in every condition, as `<screen>-<condition>-<page>.png` into
/// the folder named by `QUALITY_SCREENSHOT_DIR` — evidence for a person to look at.
/// Pass it to `xcodebuild` as `TEST_RUNNER_QUALITY_SCREENSHOT_DIR=<folder>`; without
/// it the test skips, so a normal run stays fast. A screenshot that is blank or nearly
/// one colour fails the test: it is no evidence.
final class ScreenshotMatrixUITests: XCTestCase {
    private struct Condition: Sendable {
        let name: String
        var isDark = false
        var arguments: [String] = []
    }

    private static let conditions = [
        Condition(name: "light"),
        Condition(name: "dark", isDark: true, arguments: ["--ss-appearance", "dark"]),
        Condition(name: "largest-text", arguments: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]),
        Condition(name: "german", arguments: ["-AppleLanguages", "(de)", "-AppleLocale", "de_DE"]),
    ]

    override func tearDown() {
        XCUIDevice.shared.appearance = .light
    }

    func testEveryScreenInEveryCondition() throws {
        guard let path = ProcessInfo.processInfo.environment["QUALITY_SCREENSHOT_DIR"], !path.isEmpty else {
            throw XCTSkip("QUALITY_SCREENSHOT_DIR isn't set")
        }
        let folder = URL(fileURLWithPath: path, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let only = ProcessInfo.processInfo.environment["QUALITY_CONDITIONS"]?.split(separator: ",").map(String.init)
        for condition in Self.conditions where only?.contains(condition.name) ?? true {
            XCUIDevice.shared.appearance = condition.isDark ? .dark : .light
            for screen in QualityScreen.selected {
                let app = screen.launch(extraArguments: condition.arguments)
                try forEachPage(of: app, limit: condition.name == "largest-text" ? 4 : 2) { page in
                    let name = "\(screen.name)-\(condition.name)-\(page)"
                    try drawnScreenshot(of: app, name: name).pngRepresentation.write(to: folder.appendingPathComponent("\(name).png"))
                }
                app.terminate()
            }
        }
    }

    /// The screen as drawn: a blank one is taken again a moment later, and one that stays blank fails.
    private func drawnScreenshot(of app: XCUIApplication, name: String) throws -> XCUIScreenshot {
        var screenshot = app.screenshot()
        for _ in 0..<Self.blankRetries where Self.isBlank(screenshot.image) {
            Thread.sleep(forTimeInterval: 1)
            screenshot = app.screenshot()
        }
        let share = Self.dominantColourShare(screenshot.image)
        guard share <= Self.blankShare else {
            throw MatrixError.blank("\(name) is blank: \(Int((share * 100).rounded()))% of it is one colour")
        }
        return screenshot
    }

    func testAFlatPictureIsBlankAndAScreenIsNot() {
        func picture(_ draw: (CGContext) -> Void) -> UIImage {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            return UIGraphicsImageRenderer(size: CGSize(width: 200, height: 400), format: format).image { context in
                UIColor(white: 0.98, alpha: 1).setFill()
                context.fill(CGRect(x: 0, y: 0, width: 200, height: 400))
                draw(context.cgContext)
            }
        }
        XCTAssertTrue(Self.isBlank(picture { _ in }))
        // A line of text on a plain background is still nearly one colour.
        XCTAssertTrue(Self.isBlank(picture { $0.setFillColor(UIColor.black.cgColor); $0.fill(CGRect(x: 10, y: 10, width: 180, height: 10)) }))
        // A card on the background, as the plainest screens draw.
        XCTAssertFalse(Self.isBlank(picture { $0.setFillColor(UIColor.lightGray.cgColor); $0.fill(CGRect(x: 10, y: 30, width: 180, height: 90)) }))
    }

    private enum MatrixError: Error, CustomStringConvertible {
        case blank(String)

        var description: String {
            switch self {
            case .blank(let message): message
            }
        }
    }

    private static let blankRetries = 3

    /// More than this share in one colour is a blank screenshot.
    private static let blankShare = 0.95

    private static func isBlank(_ image: UIImage) -> Bool {
        dominantColourShare(image) > blankShare
    }

    /// The share of `image` (every 4th pixel each way) in its most common colour, at 4 bits a channel.
    private static func dominantColourShare(_ image: UIImage) -> Double {
        guard let cgImage = image.cgImage else { return 1 }
        let width = cgImage.width, height = cgImage.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return 1 }
        var counts: [UInt32: Int] = [:]
        var total = 0
        for row in stride(from: 0, to: height, by: 4) {
            for column in stride(from: 0, to: width, by: 4) {
                let offset = (row * width + column) * 4
                let colour = UInt32(bytes[offset] >> 4) << 12 | UInt32(bytes[offset + 1] >> 4) << 8
                    | UInt32(bytes[offset + 2] >> 4) << 4 | UInt32(bytes[offset + 3] >> 4)
                counts[colour, default: 0] += 1
                total += 1
            }
        }
        return Double(counts.values.max() ?? 0) / Double(max(total, 1))
    }
}
