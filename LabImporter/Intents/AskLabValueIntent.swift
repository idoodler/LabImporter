import AppIntents
import Foundation

/// Lets Siri read back the most recent reading for one lab value — but only
/// for a metric the user has explicitly allowed in Settings
/// (`SiriAccessEditor`). `LabMetricEntityQuery` already restricts which
/// metrics Siri can even offer as this intent's parameter; `perform()`
/// re-checks the same preference before touching Health, so a value can never
/// be spoken just because it was once allowed and later revoked.
struct AskLabValueIntent: AppIntent {
    static var title: LocalizedStringResource = "Ask About a Lab Value"
    static var description = IntentDescription(
        "Reads back your most recent reading for a value you've allowed Siri to access."
    )

    @Parameter(title: "Lab Value")
    var metric: LabMetricEntity

    static var parameterSummary: some ParameterSummary {
        Summary("What's my \(\.$metric)?")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let prefs = SiriExposurePreferences.current()
        guard prefs.isValueExposed(metric.id) else {
            return .result(dialog: "Siri access for \(metric.name) isn't turned on yet. Allow it in LabImporter's Settings.")
        }

        let reports = (try? await HealthKitService.shared.loadCDADocuments()) ?? []
        guard let latest = reports.latestEntry(for: metric.id) else {
            return .result(dialog: "I don't have a reading for \(metric.name) yet.")
        }

        let unit = latest.entry.unit
        let valueText = unit.isEmpty ? latest.entry.displayValue : "\(latest.entry.displayValue) \(unit)"
        return .result(dialog: "Your latest \(metric.name) was \(valueText).")
    }
}
