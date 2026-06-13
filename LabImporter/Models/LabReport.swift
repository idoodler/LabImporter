import Foundation

struct LabReport: Codable, Identifiable, Sendable {
    let id: UUID
    let date: Date
    let patientName: String
    let authorName: String
    let entries: [Entry]

    struct Entry: Codable, Identifiable, Sendable {
        let id: UUID
        let code: String
        let name: String
        let displayValue: String
        let numericValue: Double?
        let unit: String

        var resolvedName: String {
            let mapped = LabMapping.displayName(for: code)
            return mapped == code ? name : mapped
        }
    }
}

extension LabReport {
    var asLabValues: [LabValue] {
        entries.map {
            LabValue(code: $0.code, name: $0.resolvedName,
                     displayValue: $0.displayValue,
                     numericValue: $0.numericValue,
                     unit: $0.unit)
        }
    }

    /// The clinical category that the most values belong to. Ties break by the
    /// canonical `LabCategory` order. Single source of truth for the report's
    /// representative color, so the list row and the detail screen agree.
    var dominantCategory: LabCategory? {
        var counts: [LabCategory: Int] = [:]
        for entry in entries {
            counts[LabCategory.forCode(entry.code), default: 0] += 1
        }
        return LabCategory.allCases
            .filter { counts[$0] != nil }
            .max { (counts[$0] ?? 0) < (counts[$1] ?? 0) }
    }
}
