#if DEBUG
enum ScreenshotMode {
    static var isActive: Bool { CommandLine.arguments.contains("--ss") }
    static var initialScreen: String { value(after: "--ss-screen") ?? "dashboard" }

    /// `--ss-data qualitative`: the sample history with results that aren't numbers.
    static var showsQualitativeSamples: Bool { value(after: "--ss-data") == "qualitative" }

    /// `--ss-data no-reports`: the History screen is empty, whatever the dashboard shows.
    static var showsNoReportsInHistory: Bool { value(after: "--ss-data") == "no-reports" }

    /// `--ss-import`: imports `StandInReport.text` at launch, read by the stand-in
    /// model (the simulator has no on-device model) — an end-to-end run into review.
    static var importsStandInReport: Bool { isActive && CommandLine.arguments.contains("--ss-import") }

    /// `--ss-no-date`: the stand-in model finds no date in the report.
    static var readsNoDate: Bool { CommandLine.arguments.contains("--ss-no-date") }

    /// The stand-in model `LabParserService` reads with in screenshot mode.
    static var partReader: LabParserService.PartReader? { isActive ? StandInReport.read : nil }

    private static func value(after flag: String) -> String? {
        guard let index = CommandLine.arguments.firstIndex(of: flag),
              CommandLine.arguments.indices.contains(index + 1)
        else { return nil }
        return CommandLine.arguments[index + 1]
    }
}
#else
enum ScreenshotMode {
    static let isActive = false
    static let initialScreen = "dashboard"
}
#endif
