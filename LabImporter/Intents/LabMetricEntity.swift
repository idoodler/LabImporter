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
/// `SiriExposurePreferences.allowedCodes` and, for the suggestion feed
/// specifically, `allowKnowledgeIndexing`. See `LabMetricEntityQuery` for
/// exactly how each control gates the entity.
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

struct LabMetricEntityQuery: EntityQuery {
    /// Resolves specific codes, e.g. when Siri already has a parameter value
    /// from a previously built Shortcut. Filtered only by the per-metric
    /// opt-in — a metric the user allowed must keep working even after they
    /// turn off proactive knowledge-graph suggestions.
    func entities(for identifiers: [String]) async throws -> [LabMetricEntity] {
        let prefs = SiriExposurePreferences.current()
        guard prefs.isEnabled else { return [] }
        let allowed = Set(identifiers).intersection(prefs.allowedSet)
        guard !allowed.isEmpty else { return [] }
        return allowed.map { LabMetricEntity(id: $0, name: LabMapping.displayName(for: $0)) }
    }

    /// Offered to Siri for autocomplete when asking `AskLabValueIntent`, and
    /// as Siri's own proactive suggestions (not Spotlight — this entity isn't
    /// `IndexedEntity`). Prioritizes whatever's currently visible in the
    /// Review sheet ("on-screen awareness") when that's enabled, then adds
    /// every allowed, knowledge-indexed metric the user actually has data for.
    func suggestedEntities() async throws -> [LabMetricEntity] {
        let prefs = SiriExposurePreferences.current()
        guard prefs.isEnabled else { return [] }

        var codes: [String] = []
        if prefs.allowOnScreenAwareness {
            codes += SiriOnScreenContext.shared.visibleCodes.filter(prefs.isValueExposed)
        }
        if prefs.allowKnowledgeIndexing {
            let reports = (try? await HealthKitService.shared.loadCDADocuments()) ?? []
            let tracked = reports.distinctNumericCodes
            codes += prefs.allowedCodes.filter { prefs.isKnowledgeIndexed($0) && tracked.contains($0) }
        }

        var seen = Set<String>()
        let unique = codes.filter { seen.insert($0).inserted }
        return unique.map { LabMetricEntity(id: $0, name: LabMapping.displayName(for: $0)) }
    }
}
