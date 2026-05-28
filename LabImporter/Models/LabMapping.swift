import Foundation

// Parser-side translation table from German lab-report shorthand (e.g. "BZ",
// "KREA", "G-GT") to LOINC codes. Used by LabParserService when the AI or
// regex pipeline yields a shorthand string so the resulting LabValue can be
// stored under its canonical LOINC identifier.
//
// Everything else about a code at runtime — display name, multilingual label,
// component, etc. — comes from LoincDirectory. This file is intentionally tiny.
enum LabMapping {

    // swiftlint:disable:next cyclomatic_complexity
    static func loinc(forShorthand shorthand: String) -> String? {
        switch shorthand.uppercased() {
        case "BZ", "GLUCOSE", "GLU", "BLOOD-GLUCOSE":      return "2345-7"
        case "KREA", "CREATININE":                          return "2160-0"
        case "MDRD", "EGFR", "KREA-GFR":                    return "33914-3"
        case "CKD-EPI":                                     return "62238-1"
        case "CHOL", "TC":                                  return "2093-3"
        case "HDL":                                         return "2085-9"
        case "NONHDL", "NON-HDL":                           return "43396-1"
        case "LDL":                                         return "2089-1"
        case "TRIG", "TG":                                  return "2571-8"
        case "GPT", "ALT":                                  return "1742-6"
        case "G-GT", "GGT", "GGTP":                         return "2324-2"
        case "HB-A1C", "HBAIC", "HBA1C", "HBA1C%":          return "4548-4"
        case "HB-A1", "HBA1":                               return "59261-8"
        case "TSH-0", "TSH":                                return "3016-3"
        case "DIABOL", "DIAB0L":                            return "14647-2"
        default:                                            return nil
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

    init(normalLow: Double?, normalHigh: Double?, borderlineLow: Double?, borderlineHigh: Double?) {
        self.normalLow = normalLow
        self.normalHigh = normalHigh
        self.borderlineLow = borderlineLow
        self.borderlineHigh = borderlineHigh
    }

    init(parsed: ParsedRange) {
        self.init(
            normalLow: parsed.normalLow,
            normalHigh: parsed.normalHigh,
            borderlineLow: parsed.borderlineLow,
            borderlineHigh: parsed.borderlineHigh
        )
    }

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

    var normalSummary: String {
        let formatter: (Double) -> String = { ReferenceRange.format($0) }
        switch (normalLow, normalHigh) {
        case let (low?, high?): return "\(formatter(low))–\(formatter(high))"
        case let (low?, nil):   return "≥ \(formatter(low))"
        case let (nil, high?):  return "≤ \(formatter(high))"
        case (nil, nil):        return "—"
        }
    }

    private static func format(_ value: Double) -> String {
        if value == value.rounded() { return String(Int(value)) }
        return String(format: "%g", value)
    }
}
