import XCTest

/// Saves every screen, in every condition, as `<screen>-<condition>-<page>.png` into
/// the folder named by `QUALITY_SCREENSHOT_DIR` — evidence for a person to look at.
/// Pass it to `xcodebuild` as `TEST_RUNNER_QUALITY_SCREENSHOT_DIR=<folder>`; without
/// it the test skips, so a normal run stays fast.
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
                    let file = folder.appendingPathComponent("\(screen.name)-\(condition.name)-\(page).png")
                    try app.screenshot().pngRepresentation.write(to: file)
                }
                app.terminate()
            }
        }
    }
}
