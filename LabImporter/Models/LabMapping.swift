import Foundation

// LOINC is the canonical identity for every lab value in the app: the parser
// resolves printed report codes to LOINC, and storage and display names all key
// off the LOINC code. Resolution is fully data-driven — the parser matches the
// AI's test name against the bundled catalog (`LoincDirectory`) in the report's
// language (see `LabParserService.resolveLoinc`) — so this type holds no curated
// abbreviation tables or clinical data; it only adapts catalog lookups for
// display and CDA export.
enum LabMapping {

    // MARK: - LOINC lookups

    // Display name for a LOINC code: a user's custom name (set in Sort &
    // Visibility) wins, then the localized catalog name, finally the raw code
    // (e.g. an as-yet-unmapped value). The custom-name override is cosmetic only —
    // CDA export keeps the standard LOINC English display (see `loincCode(for:)`).
    static func displayName(for code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        if let custom = LabDisplayPreferences.current().customName(for: trimmed) {
            return custom
        }
        return LoincDirectory.shared.term(for: trimmed)?.name ?? code
    }

    // The catalog (non-overridden) display name for a code, ignoring any custom
    // alias — used where the *default* name must be shown regardless of the user's
    // rename (the rename field's placeholder, the row subtitle under a custom name,
    // and the canonical name in CDA narrative export).
    static func catalogName(for code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        return LoincDirectory.shared.term(for: trimmed)?.name ?? trimmed
    }

    // The loinc.org details page for a code, e.g. https://loinc.org/2160-0/.
    // Returns nil for anything not shaped like a LOINC number.
    static func loincURL(for code: String) -> URL? {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard trimmed.range(of: #"^\d+-\d$"#, options: .regularExpression) != nil else { return nil }
        return URL(string: "https://loinc.org/\(trimmed)/")
    }

    // Validates that `code` is a real LOINC code and returns it with an English
    // display name for CDA export. Returns nil for unmapped/unknown codes, which
    // is how the UI decides a value cannot yet be saved to Health.
    static func loincCode(for code: String) -> (loinc: String, display: String)? {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard let term = LoincDirectory.shared.term(for: trimmed) else { return nil }
        return (term.code, term.englishName)
    }
}
