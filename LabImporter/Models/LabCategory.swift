import LabImporterCore
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
        case .urinalysis:   return Self.urinalysisGold
        case .drug:         return Self.drugRose
        case .other:        return .gray
        }
    }

    // Every other case above uses a named system color; these two are the
    // only categories without a distinct one left in SwiftUI's standard set
    // (all 13 are already spoken for), so they're fixed custom colors
    // instead — deliberately the same in light and dark, named here rather
    // than inlined so they read as palette entries like their neighbors.
    private static let urinalysisGold = Color(red: 0.78, green: 0.66, blue: 0.20)
    private static let drugRose = Color(red: 0.85, green: 0.30, blue: 0.55)

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

    /// An SF Symbol representing the category, used wherever a metric needs a
    /// compact glyph rather than a chart — e.g. the Spotlight search thumbnail.
    /// Picked to read at a glance; the category colour carries the rest.
    var icon: String {
        switch self {
        case .glycemic:     return "cube.fill"
        case .lipids:       return "circle.hexagongrid.fill"
        case .cardiac:      return "heart.fill"
        case .renal:        return "cross.vial.fill"
        case .hepatic:      return "leaf.fill"
        case .endocrine:    return "atom"
        case .electrolytes: return "bolt.fill"
        case .bloodGas:     return "lungs.fill"
        case .hematology:   return "drop.fill"
        case .coagulation:  return "bandage.fill"
        case .nutrition:    return "pills.fill"
        case .microbiology: return "microbe.fill"
        case .urinalysis:   return "drop.triangle.fill"
        case .drug:         return "pills.circle.fill"
        case .other:        return "testtube.2"
        }
    }

    /// Best-effort category for a LOINC code, resolved via the bundled catalog
    /// (memoized in the shared module, which both platforms use).
    static func forCode(_ code: String) -> LabCategory {
        SharedCore.start()
        return LabCategory(sharedName: LabImporterCore.LabCategory.companion.forCode(code: code).name)
    }

    /// Classification rules live in the shared Kotlin module so iOS and Android
    /// colour every metric identically (LabImporterCore `LabCategory`).
    static func category(loincClass: String, component: String) -> LabCategory {
        let shared = LabImporterCore.LabCategory.companion.category(loincClass: loincClass, component: component)
        return LabCategory(sharedName: shared.name)
    }

    /// Maps the shared enum's constant name (e.g. `BLOOD_GAS`) to this case (`bloodGas`).
    init(sharedName: String) {
        let parts = sharedName.lowercased().split(separator: "_")
        let camel = (parts.first.map(String.init) ?? "") + parts.dropFirst().map { $0.capitalized }.joined()
        self = LabCategory(rawValue: camel) ?? .other
    }
}
