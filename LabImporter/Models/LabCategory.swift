import SwiftUI

// A coarse clinical grouping derived from a LOINC term's class + component,
// used to give each metric a consistent color in charts.
//
// LOINC's CLASS field alone is too coarse for everyday panels — glucose, lipids,
// kidney, liver and thyroid markers are all class "CHEM" — so for chemistry we
// refine by component keywords. This is heuristic and meant to be easy to tune:
// adjust the rules in `category(loincClass:component:)` and the colors below.
enum LabCategory: String, CaseIterable, Sendable {
    case glycemic
    case lipids
    case cardiac
    case renal
    case hepatic
    case endocrine
    case electrolytes
    case bloodGas
    case hematology
    case coagulation
    case nutrition
    case microbiology
    case urinalysis
    case drug
    case other

    var color: Color {
        switch self {
        case .glycemic:     return .orange
        case .lipids:       return .yellow
        case .cardiac:      return .red
        case .renal:        return .brown
        case .hepatic:      return .green
        case .endocrine:    return .purple
        case .electrolytes: return .teal
        case .bloodGas:     return .blue
        case .hematology:   return .pink
        case .coagulation:  return .indigo
        case .nutrition:    return .mint
        case .microbiology: return .cyan
        case .urinalysis:   return Color(red: 0.78, green: 0.66, blue: 0.20)
        case .drug:         return Color(red: 0.85, green: 0.30, blue: 0.55)
        case .other:        return .gray
        }
    }

    /// Human-readable, localized name for the category — used as section headers
    /// when lab values are grouped by clinical panel.
    var displayName: String {
        switch self {
        case .glycemic:     return String(localized: "Glucose")
        case .lipids:       return String(localized: "Lipids")
        case .cardiac:      return String(localized: "Cardiac")
        case .renal:        return String(localized: "Kidney")
        case .hepatic:      return String(localized: "Liver")
        case .endocrine:    return String(localized: "Hormones")
        case .electrolytes: return String(localized: "Electrolytes")
        case .bloodGas:     return String(localized: "Blood Gas")
        case .hematology:   return String(localized: "Blood Count")
        case .coagulation:  return String(localized: "Coagulation")
        case .nutrition:    return String(localized: "Vitamins & Minerals")
        case .microbiology: return String(localized: "Microbiology")
        case .urinalysis:   return String(localized: "Urinalysis")
        case .drug:         return String(localized: "Medication")
        case .other:        return String(localized: "Other")
        }
    }

    /// Best-effort category for a LOINC code, resolved via the bundled catalog.
    static func forCode(_ code: String) -> LabCategory {
        guard let info = LoincDirectory.shared.classification(for: code) else { return .other }
        return category(loincClass: info.loincClass, component: info.component)
    }

    // swiftlint:disable:next cyclomatic_complexity
    static func category(loincClass: String, component: String) -> LabCategory {
        let cls = loincClass.uppercased()
        // Classes that stand on their own, no component refinement needed.
        if cls.hasPrefix("HEM/BC") || cls == "CELLMARK" { return .hematology }
        if cls.hasPrefix("COAG") { return .coagulation }
        if cls.hasPrefix("UA") { return .urinalysis }
        if cls.hasPrefix("DRUG") { return .drug }
        if cls.hasPrefix("MICRO") || cls.hasPrefix("ABXBACT") || cls.hasPrefix("SERO") { return .microbiology }

        // Chemistry (and anything else): refine by component keywords.
        let comp = component.lowercased()
        func has(_ needles: String...) -> Bool { needles.contains { comp.contains($0) } }

        if comp == "ph" || has("oxygen", "carbon dioxide", "po2", "pco2", "base excess") { return .bloodGas }
        if has("cholesterol", "triglyceride", "lipoprotein", "hdl", "ldl") { return .lipids }
        if has("glucose", "hemoglobin a1c", "hba1c", "fructosamine") { return .glycemic }
        if has("troponin", "natriuretic peptide", "creatine kinase", "ck-mb") { return .cardiac }
        if has("creatinine", "urea", "cystatin", "glomerular filtration") { return .renal }
        if has("aminotransferase", "gamma glutamyl", "bilirubin", "alkaline phosphatase", "albumin") { return .hepatic }
        if has("thyrotropin", "thyroxine", "triiodothyronine", "cortisol", "testosterone",
               "estradiol", "insulin", "prolactin", "parathyrin") { return .endocrine }
        if has("sodium", "potassium", "chloride", "calcium", "magnesium", "phosphate") { return .electrolytes }
        if has("vitamin", "folate", "cobalamin", "ferritin", "iron", "25-hydroxy",
               "calcidiol", "calcitriol", "transferrin") { return .nutrition }

        return .other
    }
}
