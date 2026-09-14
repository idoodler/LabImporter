import AppIntents
import Foundation

/// Lets Siri and Spotlight's "Search in LabImporter" open the app straight to
/// results for a typed or spoken term — the system's `.system.search`
/// Assistant Schema. Like `StartLabScanIntent`, it never touches a health
/// value itself; it just hands the raw term to the live app (via
/// `SiriActionBridge`), which filters the same tracked values a manual search
/// would show. Gated by `SiriExposurePreferences.canSearch` so it stays inert
/// unless the user has turned Siri access on — "Exposure is opt-in, never
/// implied" applies here too, even though nothing is spoken or returned.
@AssistantIntent(schema: .system.search)
struct SearchLabValuesIntent: ShowInAppSearchResultsIntent {
    static let title: LocalizedStringResource = "Search Lab Values"
    static let description = IntentDescription(
        "Opens LabImporter to search your tracked values."
    )

    @Parameter(title: "Search Text")
    var criteria: StringSearchCriteria

    func perform() async throws -> some IntentResult {
        guard SiriExposurePreferences.current().canSearch else {
            return .result()
        }
        SiriActionBridge.shared.requestSearch(term: criteria.term)
        return .result()
    }
}
