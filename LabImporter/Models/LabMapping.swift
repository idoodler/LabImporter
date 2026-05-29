import Foundation

// Maps German lab report codes to human-readable names, LOINC codes, and reference ranges.
//
// The curated tables below cover the common German abbreviations that the AI
// parser emits (KREA, HB-A1C, …) and carry the hand-tuned reference ranges and
// localized display names. For any code that is already a LOINC number — e.g.
// one chosen from the catalog in CodePickerSheet — the lookups fall back to
// LoincDirectory (the full ~18k common-lab LOINC catalog bundled at build time).
enum LabMapping {

    static var allKnownCodes: [(code: String, name: String)] {
        [
            ("BZ",       String(localized: "Blood Glucose")),
            ("KREA",     String(localized: "Creatinine")),
            ("MDRD",     String(localized: "eGFR (MDRD)")),
            ("CKD-EPI",  String(localized: "eGFR (CKD-EPI)")),
            ("CHOL",     String(localized: "Total Cholesterol")),
            ("HDL",      String(localized: "HDL Cholesterol")),
            ("NONHDL",   String(localized: "Non-HDL Cholesterol")),
            ("LDL",      String(localized: "LDL Cholesterol")),
            ("TRIG",     String(localized: "Triglycerides")),
            ("GPT",      String(localized: "GPT (ALT)")),
            ("G-GT",     String(localized: "Gamma-GT (GGT)")),
            ("HB-A1C",   String(localized: "HbA1c (%)")),
            ("HB-A1",    String(localized: "HbA1 (mmol/mol)")),
            ("TSH",      String(localized: "TSH (Thyroid)")),
            ("DIABOL",   String(localized: "Diabetes Screening")),
        ]
    }

    // swiftlint:disable:next cyclomatic_complexity
    static func displayName(for code: String) -> String {
        switch code.uppercased() {
        case "DIABOL", "DIAB0L":        return String(localized: "Diabetes Screening")
        case "KREA", "CREATININE":      return String(localized: "Creatinine")
        case "MDRD", "EGFR":            return String(localized: "eGFR (MDRD)")
        case "CHOL", "TC":              return String(localized: "Total Cholesterol")
        case "HDL":                     return String(localized: "HDL Cholesterol")
        case "NONHDL", "NON-HDL":       return String(localized: "Non-HDL Cholesterol")
        case "LDL":                     return String(localized: "LDL Cholesterol")
        case "TRIG", "TG":              return String(localized: "Triglycerides")
        case "GPT", "ALT":              return String(localized: "GPT (ALT)")
        case "G-GT", "GGT", "GGTP":    return String(localized: "Gamma-GT (GGT)")
        case "HB-A1C", "HBAIC", "HBA1C", "HBA1C%": return String(localized: "HbA1c (%)")
        case "HB-A1", "HBA1":          return String(localized: "HbA1 (mmol/mol)")
        case "TSH-0", "TSH":           return String(localized: "TSH (Thyroid)")
        case "BZ", "GLUCOSE", "GLU":   return String(localized: "Blood Glucose")
        case "KREA-GFR", "CKD-EPI":    return String(localized: "eGFR (CKD-EPI)")
        default:
            // Fall back to the full LOINC catalog for raw LOINC codes (e.g. "2160-0").
            if let term = LoincDirectory.shared.term(for: code) { return term.name }
            return code
        }
    }

    // LOINC codes for CDA export — covers all recognised lab codes.
    // swiftlint:disable:next cyclomatic_complexity
    static func loincCode(for code: String) -> (loinc: String, display: String)? {
        switch code.uppercased() {
        case "BZ", "GLUCOSE", "GLU", "BLOOD-GLUCOSE":
            return ("2345-7", "Glucose [Mass/volume] in Serum or Plasma")
        case "KREA", "CREATININE":
            return ("2160-0", "Creatinine [Mass/volume] in Serum or Plasma")
        case "MDRD", "EGFR", "KREA-GFR":
            return ("33914-3", "GFR/BSA pred MDRD")
        case "CKD-EPI":
            return ("62238-1", "GFR/BSA pred CKD-EPI")
        case "CHOL", "TC":
            return ("2093-3", "Cholesterol [Mass/volume] in Serum or Plasma")
        case "HDL":
            return ("2085-9", "Cholesterol in HDL [Mass/volume] in Serum or Plasma")
        case "NONHDL", "NON-HDL":
            return ("43396-1", "Cholesterol non HDL [Mass/volume] in Serum or Plasma")
        case "LDL":
            return ("2089-1", "Cholesterol in LDL [Mass/volume] in Serum or Plasma")
        case "TRIG", "TG":
            return ("2571-8", "Triglyceride [Mass/volume] in Serum or Plasma")
        case "GPT", "ALT":
            return ("1742-6", "Alanine aminotransferase [Enzymatic activity/volume] in Serum or Plasma")
        case "G-GT", "GGT", "GGTP":
            return ("2324-2", "Gamma glutamyl transferase [Enzymatic activity/volume] in Serum or Plasma")
        case "HB-A1C", "HBAIC", "HBA1C", "HBA1C%":
            return ("4548-4", "Hemoglobin A1c/Hemoglobin.total in Blood")
        case "HB-A1", "HBA1":
            return ("59261-8", "Hemoglobin A1c/Hemoglobin.total in Blood by IFCC protocol")
        case "TSH-0", "TSH":
            return ("3016-3", "Thyrotropin [Units/volume] in Serum or Plasma")
        case "DIABOL", "DIAB0L":
            return ("14647-2", "Glucose [Mass/volume] in Serum or Plasma --fasting")
        default:
            // Codes picked straight from the LOINC catalog are already LOINC numbers.
            if let term = LoincDirectory.shared.term(for: code) {
                return (term.code, term.englishName)
            }
            return nil
        }
    }

    // Maps a LOINC code back to an internal lab code for reference-range lookups.
    static func internalCode(forLoinc loinc: String) -> String? {
        let candidates = ["BZ", "KREA", "MDRD", "CKD-EPI", "CHOL", "HDL", "NONHDL", "LDL",
                          "TRIG", "GPT", "G-GT", "HB-A1C", "HB-A1", "TSH", "DIABOL", "EGFR"]
        return candidates.first { loincCode(for: $0)?.loinc == loinc }
    }

    // Standard clinical reference ranges.
    // Borderline values fall between normal and clearly abnormal.
    // swiftlint:disable:next cyclomatic_complexity
    static func referenceRange(for code: String) -> ReferenceRange? {
        switch code.uppercased() {
        case "BZ", "GLUCOSE", "GLU", "BLOOD-GLUCOSE":
            return ReferenceRange(normalLow: 70, normalHigh: 100, borderlineLow: nil, borderlineHigh: 125)
        case "HB-A1C", "HBAIC", "HBA1C", "HBA1C%":
            return ReferenceRange(normalLow: nil, normalHigh: 5.7, borderlineLow: nil, borderlineHigh: 6.4)
        case "CHOL", "TC":
            return ReferenceRange(normalLow: nil, normalHigh: 200, borderlineLow: nil, borderlineHigh: 239)
        case "LDL":
            return ReferenceRange(normalLow: nil, normalHigh: 100, borderlineLow: nil, borderlineHigh: 159)
        case "HDL":
            return ReferenceRange(normalLow: 40, normalHigh: nil, borderlineLow: nil, borderlineHigh: nil)
        case "TRIG", "TG":
            return ReferenceRange(normalLow: nil, normalHigh: 150, borderlineLow: nil, borderlineHigh: 199)
        case "KREA", "CREATININE":
            return ReferenceRange(normalLow: 0.5, normalHigh: 1.2, borderlineLow: nil, borderlineHigh: nil)
        case "GPT", "ALT":
            return ReferenceRange(normalLow: nil, normalHigh: 40, borderlineLow: nil, borderlineHigh: nil)
        case "G-GT", "GGT", "GGTP":
            return ReferenceRange(normalLow: nil, normalHigh: 55, borderlineLow: nil, borderlineHigh: nil)
        case "TSH-0", "TSH":
            return ReferenceRange(normalLow: 0.4, normalHigh: 4.0, borderlineLow: nil, borderlineHigh: nil)
        case "MDRD", "EGFR", "KREA-GFR", "CKD-EPI":
            return ReferenceRange(normalLow: 90, normalHigh: nil, borderlineLow: 60, borderlineHigh: nil)
        default:
            return nil
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
