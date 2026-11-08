import SwiftUI

#if DEBUG
enum ScreenshotMode {
    static var isActive: Bool { CommandLine.arguments.contains("--ss") }

    /// `--ss-appearance dark|light`: the simulator's own setting doesn't reach an app a UI test launches.
    static var colorScheme: ColorScheme? {
        switch value(after: "--ss-appearance") {
        case "dark": .dark
        case "light": .light
        default: nil
        }
    }
    static var initialScreen: String { value(after: "--ss-screen") ?? "dashboard" }

    /// `--ss-data qualitative`: the sample history with results that aren't numbers.
    static var showsQualitativeSamples: Bool { value(after: "--ss-data") == "qualitative" }

    /// `--ss-screen unsupported`: the screen hardware without Apple Intelligence gets.
    static var showsUnsupportedDevice: Bool { isActive && initialScreen == "unsupported" }

    /// `--ss-full-sheet`: a sheet that opens at half height opens at its full one. While it's half up
    /// the system scales its layer, and the accessibility audit misjudges all the text in it.
    static var opensSheetsInFull: Bool { isActive && CommandLine.arguments.contains("--ss-full-sheet") }

    /// `--ss-onboarding welcome|disclaimer|health|extras`: stops onboarding at that step.
    static var onboardingStep: String? { value(after: "--ss-onboarding") }

    /// `--ss-data empty`: no reports anywhere, the first-run landing screen.
    /// `--ss-data no-reports`: the History screen is empty, whatever the dashboard shows.
    /// `--ss-data load-failed`: nothing could be read from Apple Health, until Try Again.
    /// `load-failed-stale`: the same, above reports already on screen. `load-failed-history`:
    /// only History's own read fails. `newer-reports`: some reports need a newer version of the
    /// app and one is damaged. `newer-reports-only`: all of them need a newer version.
    private static var data: String? { value(after: "--ss-data") }

    @MainActor private static var hasRetriedLoading = false

    @MainActor static func retryLoading() { hasRetriedLoading = true }

    private static var noticesAboutStoredReports: ReportLoadStatus {
        switch data {
        case "newer-reports": ReportLoadStatus(newerVersion: 2, unreadable: 1)
        case "newer-reports-only": ReportLoadStatus(newerVersion: 2)
        default: ReportLoadStatus()
        }
    }

    /// What the home screen read.
    @MainActor static var home: (reports: [LabReport], status: ReportLoadStatus) {
        let sample = showsQualitativeSamples ? LabReport.sampleHistoryWithQualitative : LabReport.sampleHistory
        if hasRetriedLoading { return (sample, ReportLoadStatus()) }
        switch data {
        case "empty": return ([], ReportLoadStatus())
        case "load-failed": return ([], ReportLoadStatus(failed: true))
        case "load-failed-stale": return (sample, ReportLoadStatus(failed: true))
        case "newer-reports-only": return ([], noticesAboutStoredReports)
        default: return (sample, noticesAboutStoredReports)
        }
    }

    /// What the History screen read.
    @MainActor static var history: (reports: [LabReport], status: ReportLoadStatus) {
        if hasRetriedLoading { return (LabReport.sampleHistory, ReportLoadStatus()) }
        switch data {
        case "no-reports": return ([], ReportLoadStatus())
        case "load-failed-history", "load-failed": return ([], ReportLoadStatus(failed: true))
        case "load-failed-stale": return (LabReport.sampleHistory, ReportLoadStatus(failed: true))
        case "newer-reports-only": return ([], noticesAboutStoredReports)
        default: return (LabReport.sampleHistory, noticesAboutStoredReports)
        }
    }

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
    static let showsUnsupportedDevice = false
    static let opensSheetsInFull = false
    static let colorScheme: ColorScheme? = nil
}
#endif
