import Foundation

// Maps German lab report codes to human-readable names, LOINC codes, and reference ranges.
enum LabMapping {

    static let allKnownCodes: [(code: String, name: String)] = [
        ("BZ", "Blood Glucose"),
        ("KREA", "Creatinine"),
        ("MDRD", "eGFR (MDRD)"),
        ("CKD-EPI", "eGFR (CKD-EPI)"),
        ("CHOL", "Total Cholesterol"),
        ("HDL", "HDL Cholesterol"),
        ("NONHDL", "Non-HDL Cholesterol"),
        ("LDL", "LDL Cholesterol"),
        ("TRIG", "Triglycerides"),
        ("GPT", "GPT (ALT)"),
        ("G-GT", "Gamma-GT (GGT)"),
        ("HB-A1C", "HbA1c (%)"),
        ("HB-A1", "HbA1 (mmol/mol)"),
        ("TSH", "TSH (Thyroid)"),
        ("DIABOL", "Diabetes Screening"),
    ]

    // swiftlint:disable:next cyclomatic_complexity
    static func displayName(for code: String) -> String {
        switch code.uppercased() {
        case "DIABOL", "DIAB0L":        return "Diabetes Screening"
        case "KREA", "CREATININE":      return "Creatinine"
        case "MDRD", "EGFR":            return "eGFR (MDRD)"
        case "CHOL", "TC":              return "Total Cholesterol"
        case "HDL":                     return "HDL Cholesterol"
        case "NONHDL", "NON-HDL":       return "Non-HDL Cholesterol"
        case "LDL":                     return "LDL Cholesterol"
        case "TRIG", "TG":              return "Triglycerides"
        case "GPT", "ALT":              return "GPT (ALT)"
        case "G-GT", "GGT", "GGTP":    return "Gamma-GT (GGT)"
        case "HB-A1C", "HBAIC", "HBA1C", "HBA1C%": return "HbA1c (%)"
        case "HB-A1", "HBA1":          return "HbA1 (mmol/mol)"
        case "TSH-0", "TSH":           return "TSH (Thyroid)"
        case "BZ", "GLUCOSE", "GLU":   return "Blood Glucose"
        case "KREA-GFR", "CKD-EPI":    return "eGFR (CKD-EPI)"
        default:                        return code
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
