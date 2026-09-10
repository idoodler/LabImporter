import AppIntents
import Foundation

/// A lab metric Siri can ask about or suggest — one LOINC code the user has
/// data for. Carries no reading itself; `AskLabValueIntent` fetches that
/// separately and re-checks exposure before speaking it, so merely resolving
/// or suggesting an entity never surfaces a value.
///
/// Conforms to `IndexedEntity` so metrics the user allows can be donated to
/// the on-device Siri/Spotlight knowledge graph — "teach Siri about my
/// data" — while staying opt-in per metric via
/// `SiriExposurePreferences.allowedCodes`, and, for the proactive
/// knowledge-graph donation specifically, `allowKnowledgeIndexing`. See
/// `LabMetricEntityQuery` for exactly how each control gates the entity.
struct LabMetricEntity: AppEntity, IndexedEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: "Lab Value")
    }

    static var defaultQuery = LabMetricEntityQuery()

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
    /// donated to the on-device knowledge graph so Siri can suggest a tracked
    /// value proactively. Prioritizes whatever's currently visible in the
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
