import Foundation

// LOINC is the canonical identity for every lab value in the app: the parser
// resolves printed report codes to LOINC, and storage, reference ranges and
// display names all key off the LOINC code. The German abbreviations that lab
// reports actually print (KREA, HB-A1C, …) only appear here as *import-time*
// resolver inputs — see `loinc(forPrinted:)`.
//
// Names come entirely from the bundled catalog (`LoincDirectory`). The only
// thing curated here is the set of clinical reference ranges, which LOINC does
// not provide and which drive the dashboard status colors.
enum LabMapping {

    // MARK: - Reference ranges (LOINC-keyed)

    // Clinical normal/borderline bands keyed by LOINC code. LOINC carries no
    // reference ranges, so these are hand-tuned; every code here exists in the
    // bundled catalog (so it also has a name). Codes without a range simply get
    // no dashboard status — that is fine.
    static let referenceRanges: [String: ReferenceRange] = [
        "2345-7": ReferenceRange(normalLow: 70, normalHigh: 100, borderlineLow: nil, borderlineHigh: 125),
        "2160-0": ReferenceRange(normalLow: 0.5, normalHigh: 1.2, borderlineLow: nil, borderlineHigh: nil),
        "77147-7": ReferenceRange(normalLow: 90, normalHigh: nil, borderlineLow: 60, borderlineHigh: nil),
        "62238-1": ReferenceRange(normalLow: 90, normalHigh: nil, borderlineLow: 60, borderlineHigh: nil),
        "2093-3": ReferenceRange(normalLow: nil, normalHigh: 200, borderlineLow: nil, borderlineHigh: 239),
        "2085-9": ReferenceRange(normalLow: 40, normalHigh: nil, borderlineLow: nil, borderlineHigh: nil),
        "2089-1": ReferenceRange(normalLow: nil, normalHigh: 100, borderlineLow: nil, borderlineHigh: 159),
        "2571-8": ReferenceRange(normalLow: nil, normalHigh: 150, borderlineLow: nil, borderlineHigh: 199),
        "1742-6": ReferenceRange(normalLow: nil, normalHigh: 40, borderlineLow: nil, borderlineHigh: nil),
        "2324-2": ReferenceRange(normalLow: nil, normalHigh: 55, borderlineLow: nil, borderlineHigh: nil),
        "4548-4": ReferenceRange(normalLow: nil, normalHigh: 5.7, borderlineLow: nil, borderlineHigh: 6.4),
        "3016-3": ReferenceRange(normalLow: 0.4, normalHigh: 4.0, borderlineLow: nil, borderlineHigh: nil),
    ]

    // MARK: - LOINC lookups

    // Localized display name for a LOINC code from the catalog; falls back to the
    // raw code (e.g. an as-yet-unmapped value).
    static func displayName(for code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        return LoincDirectory.shared.term(for: trimmed)?.name ?? code
    }

    // Clinical reference range for a LOINC code, if one is curated.
    static func referenceRange(for code: String) -> ReferenceRange? {
        referenceRanges[code.trimmingCharacters(in: .whitespaces)]
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

// MARK: - Reference range types

enum RangeStatus: Equatable {
    case normal, borderline, abnormal
}

struct ReferenceRange {
    let normalLow: Double?       // nil = no lower bound
    let normalHigh: Double?      // nil = no upper bound
    let borderlineLow: Double?   // low boundary of borderline zone (e.g. eGFR 60–89)
    let borderlineHigh: Double?  // high boundary of borderline zone (e.g. HbA1c 5.7–6.4)

    func status(for value: Double) -> RangeStatus {
        if let low = normalLow, value < low {
            if let bLow = borderlineLow, value >= bLow { return .borderline }
            return .abnormal
        }
        if let high = normalHigh, value > high {
            if let bHigh = borderlineHigh, value <= bHigh { return .borderline }
            return .abnormal
        }
        return .normal
    }
}
