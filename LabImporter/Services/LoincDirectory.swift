import Foundation

// A common laboratory LOINC term resolved for the active app language.
//
// The bundled loinc_common.json (produced by tools/build_loinc_resource.py)
// carries a name + description in every language the app ships; at load time we
// collapse each term to the user's language with an English fallback so we don't
// keep all ~13 translations resident in memory.
struct LoincTerm: Identifiable, Sendable {
    let code: String         // LOINC_NUM, e.g. "2160-0"
    let name: String          // localized display name
    let englishName: String   // English name (used for stable CDA display)
    let description: String?  // localized description, if distinct from the name
    let ucum: String          // EXAMPLE_UCUM_UNITS (may be empty)
    let rank: Int             // COMMON_TEST_RANK (lower = more commonly ordered)

    var id: String { code }
}

// Read-only, in-memory index over the bundled common-LOINC catalog.
//
// Loaded lazily and exactly once via `static let shared`; the instance is fully
// immutable afterwards, so it is safely `Sendable` and can be queried
// synchronously from any isolation domain (the @MainActor views, the
// HealthKitService actor, CDAExportService, …).
final class LoincDirectory: Sendable {
    static let shared = LoincDirectory()

    let version: String
    private let byCode: [String: LoincTerm]
    private let ranked: [LoincTerm] // sorted by ascending rank (most common first)

    private init() {
        guard
            let url = Bundle.main.url(forResource: "loinc_common", withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let payload = try? JSONDecoder().decode(LoincPayload.self, from: data)
        else {
            self.version = ""
            self.byCode = [:]
            self.ranked = []
            return
        }
        let language = Bundle.main.preferredLocalizations.first ?? "en"
        let terms = payload.entries.map { $0.resolved(for: language) }
        self.version = payload.version
        self.ranked = terms
        self.byCode = Dictionary(terms.map { ($0.code, $0) }, uniquingKeysWith: { first, _ in first })
    }

    var isEmpty: Bool { ranked.isEmpty }
    var count: Int { ranked.count }

    // Exact LOINC-code lookup, e.g. "2160-0".
    func term(for code: String) -> LoincTerm? {
        byCode[code.trimmingCharacters(in: .whitespaces)]
    }

    func isKnownLoinc(_ code: String) -> Bool {
        byCode[code.trimmingCharacters(in: .whitespaces)] != nil
    }

    // Substring search across code, name and description, most-common first.
    // `nonisolated` + value-type results make this safe to call off the main actor.
    func search(_ query: String, limit: Int = 80) -> [LoincTerm] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return Array(ranked.prefix(limit)) }
        var results: [LoincTerm] = []
        results.reserveCapacity(limit)
        for term in ranked where term.matches(needle) {
            results.append(term)
            if results.count >= limit { break }
        }
        return results
    }
}

// MARK: - Raw decoding (matches loinc_common.json from build_loinc_resource.py)

private struct LoincPayload: Decodable {
    let version: String
    let entries: [LoincRawEntry]
}

private struct LoincRawEntry: Decodable {
    let code: String
    let ucum: String
    let rank: Int
    let names: [String: String]
    let descriptions: [String: String]?

    enum CodingKeys: String, CodingKey {
        case code = "c", ucum = "u", rank = "r", names = "n", descriptions = "d"
    }

    func resolved(for language: String) -> LoincTerm {
        let english = names["en"] ?? names.values.first ?? code
        let name = names[language] ?? english
        let description = descriptions?[language] ?? descriptions?["en"]
        return LoincTerm(
            code: code,
            name: name,
            englishName: english,
            description: description == name ? nil : description,
            ucum: ucum,
            rank: rank
        )
    }
}

private extension LoincTerm {
    func matches(_ needle: String) -> Bool {
        code.localizedCaseInsensitiveContains(needle)
            || name.localizedCaseInsensitiveContains(needle)
            || englishName.localizedCaseInsensitiveContains(needle)
            || (description?.localizedCaseInsensitiveContains(needle) ?? false)
    }
}
