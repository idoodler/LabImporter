import AppIntents
import Foundation

/// A lab metric Siri can ask about or suggest — one LOINC code the user has
/// data for. Carries no reading itself; `AskLabValueIntent` fetches that
/// separately and re-checks exposure before speaking it, so merely resolving
/// or suggesting an entity never surfaces a value.
///
/// Deliberately **not** `IndexedEntity`: donating to the on-device system
/// index would also surface these in Spotlight, duplicating
/// `SpotlightIndexService` (the dedicated, always-on Spotlight feature).
/// This entity stays scoped to Siri's own proactive suggestions, gated by
/// `SiriExposurePreferences.isCodeAllowed(_:)` (either every value, or only
/// `allowedCodes`) and, for the suggestion feed specifically,
/// `allowKnowledgeIndexing`. Its query conforms to `EntityStringQuery` so a
/// spoken/typed name resolves directly — without it Siri can't match free
/// text to this parameter at all. See `LabMetricEntityQuery` for exactly how
/// each control gates the entity.
struct LabMetricEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: "Lab Value")
    }

    static let defaultQuery = LabMetricEntityQuery()

    /// The LOINC code — stable across renames and re-imports.
    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }
}

struct LabMetricEntityQuery: EntityStringQuery {
    /// Resolves specific codes, e.g. when Siri already has a parameter value
    /// from a previously built Shortcut. Filtered by whichever access mode is
    /// active — every value, or just the per-metric opt-in — via
    /// `isValueExposed`, so a metric the user allowed keeps working even after
    /// they turn off proactive knowledge-graph suggestions.
    func entities(for identifiers: [String]) async throws -> [LabMetricEntity] {
        let prefs = SiriExposurePreferences.current()
        guard prefs.isEnabled else { return [] }
        let allowed = Set(identifiers).filter(prefs.isValueExposed)
        guard !allowed.isEmpty else { return [] }
        return allowed.map { LabMetricEntity(id: $0, name: LabMapping.displayName(for: $0)) }
    }

    /// Resolves a spoken/typed name (e.g. "HbA1c") straight to its entity —
    /// without this, Siri has no way to match free text to `$metric` at all
    /// and silently reinterprets the whole utterance as something else (it was
    /// falling through to `SearchLabValuesIntent`, opening the app, instead of
    /// `AskLabValueIntent` answering in place). Same case/diacritic-insensitive
    /// substring match as `MetricSearchResultsView`, scoped to exposed codes.
    func entities(matching string: String) async throws -> [LabMetricEntity] {
        let prefs = SiriExposurePreferences.current()
        guard prefs.isEnabled else { return [] }
        let reports = (try? await HealthKitService.shared.loadCDADocuments()) ?? []
        let needle = string.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
        return reports.distinctNumericCodes
            .filter(prefs.isValueExposed)
            .map { ($0, LabMapping.displayName(for: $0)) }
            .filter { $0.1.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).contains(needle) }
            .map { LabMetricEntity(id: $0.0, name: $0.1) }
    }

    /// Offered to Siri for autocomplete when asking `AskLabValueIntent`, and
    /// as Siri's own proactive suggestions (not Spotlight — this entity isn't
    /// `IndexedEntity`). Prioritizes whatever's currently visible in the
    /// Review sheet ("on-screen awareness") when that's enabled, then adds
    /// every knowledge-indexed metric the user actually has data for —
    /// every tracked code while `allowAllValues` is on, else just `allowedCodes`.
    func suggestedEntities() async throws -> [LabMetricEntity] {
        let prefs = SiriExposurePreferences.current()
        guard prefs.isEnabled else { return [] }

        var codes: [String] = []
        if prefs.allowOnScreenAwareness {
            codes += SiriOnScreenContext.shared.visibleCodes.filter(prefs.isValueExposed)
        }
        if prefs.allowKnowledgeIndexing {
            let reports = (try? await HealthKitService.shared.loadCDADocuments()) ?? []
            codes += reports.distinctNumericCodes.filter(prefs.isKnowledgeIndexed)
        }

        var seen = Set<String>()
        let unique = codes.filter { seen.insert($0).inserted }
        return unique.map { LabMetricEntity(id: $0, name: LabMapping.displayName(for: $0)) }
    }
}
