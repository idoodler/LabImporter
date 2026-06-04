#if DEBUG
enum ScreenshotMode {
    static var isActive: Bool { CommandLine.arguments.contains("--ss") }
    static var initialScreen: String {
        guard let idx = CommandLine.arguments.firstIndex(of: "--ss-screen"),
              CommandLine.arguments.indices.contains(idx + 1)
        else { return "dashboard" }
        return CommandLine.arguments[idx + 1]
    }
}
#else
enum ScreenshotMode {
    static let isActive = false
    static let initialScreen = "dashboard"
}
#endif
