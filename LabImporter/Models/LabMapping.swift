import Foundation

// LOINC is the canonical identity for every lab value in the app: the parser
// resolves printed report codes to LOINC, and storage and display names all key
// off the LOINC code. The German abbreviations that lab reports actually print
// (KREA, HB-A1C, …) only appear here as *import-time* resolver inputs — see
// `loinc(forPrinted:)`. Names come entirely from the bundled catalog
// (`LoincDirectory`); this type holds no curated clinical data.
enum LabMapping {

    // MARK: - LOINC lookups

    // Localized display name for a LOINC code from the catalog; falls back to the
    // raw code (e.g. an as-yet-unmapped value).
    static func displayName(for code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        return LoincDirectory.shared.term(for: trimmed)?.name ?? code
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

    // MARK: - Import resolution (printed report code -> LOINC)

    // Maps the codes/abbreviations a lab report actually prints to LOINC, so the
    // rest of the app only ever deals in LOINC. Returns nil when the printed code
    // is neither a known abbreviation nor an existing LOINC code, in which case
    // the user maps it manually in the review sheet.
    // swiftlint:disable:next cyclomatic_complexity
    static func loinc(forPrinted printed: String) -> String? {
        switch printed.uppercased().trimmingCharacters(in: .whitespaces) {
        case "DIABOL", "DIAB0L":            return "1558-6"
        case "KREA", "CREATININE":          return "2160-0"
        case "MDRD", "EGFR":                return "77147-7"
        case "KREA-GFR", "CKD-EPI":         return "62238-1"
        case "CHOL", "TC":                  return "2093-3"
        case "HDL":                          return "2085-9"
        case "NONHDL", "NON-HDL":           return "43396-1"
        case "LDL":                          return "2089-1"
        case "TRIG", "TG":                  return "2571-8"
        case "GPT", "ALT":                  return "1742-6"
        case "G-GT", "GGT", "GGTP":         return "2324-2"
        case "HB-A1C", "HBAIC", "HBA1C", "HBA1C%": return "4548-4"
        case "HB-A1", "HBA1":               return "59261-8"
        case "TSH-0", "TSH":                return "3016-3"
        case "BZ", "GLUCOSE", "GLU":        return "2345-7"
        default:
            // Already a LOINC code (e.g. pasted in, or chosen from the catalog)?
            let trimmed = printed.trimmingCharacters(in: .whitespaces)
            return LoincDirectory.shared.isKnownLoinc(trimmed) ? trimmed : nil
        }
    }
}
