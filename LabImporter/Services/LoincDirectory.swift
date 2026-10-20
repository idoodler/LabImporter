import Foundation
import LabImporterCore

// A common laboratory LOINC term resolved for the active app language.
struct LoincTerm: Identifiable, Sendable {
    let code: String         // LOINC_NUM, e.g. "2160-0"
    let name: String          // localized display name
    let englishName: String   // English name (used for stable CDA display)
    let description: String?  // localized description, if distinct from the name
    let ucum: String          // EXAMPLE_UCUM_UNITS (may be empty)
    let rank: Int             // COMMON_TEST_RANK (lower = more commonly ordered)

    var id: String { code }
}

extension LoincTerm {
    init(shared term: LabImporterCore.LoincTerm) {
        self.init(code: term.code, name: term.name, englishName: term.englishName,
                  description: term.details, ucum: term.ucum, rank: Int(term.rank))
    }
}

// The full structured attributes of a LOINC term, as shown on a loinc.org
// details page. The headline name/description are localized; the six-part name
// and other attributes are LOINC's standard English values.
struct LoincDetail: Sendable {
    let code: String
    let name: String          // localized
    let description: String?  // localized
    let component: String
    let property: String
    let timing: String        // time aspect
    let system: String
    let scale: String
    let method: String
    let loincClass: String
    let status: String
    let longName: String
    let shortName: String
    let ucum: String
}

// Read-only access to the bundled LOINC catalog (loinc.db, produced by
// tools/build_loinc_resource.py). The catalog itself — SQLite + FTS5 search,
// language resolution — is implemented once in the shared module
// (`LabImporterCore.LoincDirectory`, also used by Android); this type keeps the
// app's Swift-native API on top of it.
final class LoincDirectory: Sendable {
    static let shared = LoincDirectory()

    private var core: LabImporterCore.LoincDirectory? { SharedCore.loinc }

    var version: String { core?.version ?? "" }
    var count: Int { Int(core?.count ?? 0) }
    var license: String { core?.license ?? "" }
    /// The app-language keys the catalog carries localized labels for.
    var languages: [String] { core?.languages ?? [] }

    // Maps a free-form/BCP-47 language tag (e.g. the parser's detected report
    // language) to a language the catalog carries labels for, falling back to
    // the app's language. English is always searched alongside.
    func resolvedLanguage(for tag: String) -> String {
        core?.resolvedLanguage(tag: tag) ?? "en"
    }

    func term(for code: String) -> LoincTerm? {
        core?.term(code: code).map(LoincTerm.init(shared:))
    }

    func isKnownLoinc(_ code: String) -> Bool {
        core?.isKnownLoinc(code: code) ?? false
    }

    // LOINC class + component for a code, used to derive a display category/color.
    func classification(for code: String) -> (loincClass: String, component: String)? {
        guard let pair = core?.classification(code: code),
              let loincClass = pair.first as? String, let component = pair.second as? String else { return nil }
        return (loincClass, component)
    }

    // Full structured attributes for a code (for the term detail screen).
    func detail(for code: String) -> LoincDetail? {
        guard let detail = core?.detail(code: code) else { return nil }
        return LoincDetail(
            code: detail.code, name: detail.name, description: detail.details,
            component: detail.component, property: detail.property, timing: detail.timing,
            system: detail.system, scale: detail.scale, method: detail.method,
            loincClass: detail.loincClass, status: detail.status, longName: detail.longName,
            shortName: detail.shortName, ucum: detail.ucum
        )
    }

    // Searches the catalog in the app's UI language (plus English). An empty
    // query lists the most commonly ordered terms.
    func search(_ query: String, limit: Int = 80, offset: Int = 0) -> [LoincTerm] {
        guard let core else { return [] }
        return search(query, language: core.language, limit: limit, offset: offset)
    }

    // Searches the catalog in an explicit language (plus English).
    func search(_ query: String, language: String, limit: Int = 80, offset: Int = 0) -> [LoincTerm] {
        guard let core else { return [] }
        return core.search(query: query, language: language, limit: Int32(limit), offset: Int32(offset))
            .map(LoincTerm.init(shared:))
    }
}
