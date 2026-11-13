import AppIntents
import Foundation

/// Lets Siri read back the most recent reading for one lab value — but only
/// for a metric the user has explicitly allowed in Settings
/// (`SiriAccessEditor`). `LabMetricEntityQuery` already restricts which
/// metrics Siri can even offer as this intent's parameter; `perform()`
/// re-checks the same preference before touching Health, so a value can never
/// be spoken just because it was once allowed and later revoked.
struct AskLabValueIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask About a Lab Value"
    static let description = IntentDescription(
        "Reads back your most recent reading for a value you've allowed to be accessed this way."
    )

    /// A lab value is never spoken from a locked phone: Siri asks to unlock it first. (HealthKit's
    /// store is also closed while locked, which would read as "no reading yet".)
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresAuthentication

    @Parameter(title: "Lab Value")
    var metric: LabMetricEntity

    static var parameterSummary: some ParameterSummary {
        Summary("What's my \(\.$metric)?")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        switch await Self.answer(for: metric.id, prefs: SiriExposurePreferences.current(), store: HealthKitService.shared) {
        case .notAllowed:
            return .result(dialog: "Siri access for \(metric.name) isn't turned on yet. Allow it in LabImporter's Settings.")
        case .couldNotRead:
            return .result(dialog: IntentDialog(SiriHealthRead.failureMessage))
        case .noReading:
            return .result(dialog: "I don't have a reading for \(metric.name) yet.")
        case .latest(let valueText):
            return .result(dialog: "Your latest \(metric.name) was \(valueText).")
        }
    }

    /// What Siri answers about `code`, apart from the wording.
    enum Answer: Equatable {
        case notAllowed
        /// Apple Health couldn't be read: not the same as having no reading.
        case couldNotRead
        case noReading
        /// The latest reading with its unit.
        case latest(String)
    }

    static func answer(for code: String, prefs: SiriExposurePreferences, store: any ReportDocumentStore) async -> Answer {
        guard prefs.isValueExposed(code) else { return .notAllowed }
        let reports: [LabReport]
        do {
            reports = try await SiriHealthRead.reports(from: store)
        } catch {
            return .couldNotRead
        }
        guard let latest = reports.latestEntry(for: code) else { return .noReading }
        let unit = latest.entry.unit
        return .latest(unit.isEmpty ? latest.entry.displayValue : "\(latest.entry.displayValue) \(unit)")
    }
}
