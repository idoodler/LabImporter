import Foundation

struct LabReport: Codable, Identifiable {
    let id: UUID
    let date: Date
    let patientName: String
    let authorName: String
    let entries: [Entry]

    struct Entry: Codable, Identifiable {
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

extension Array where Element == LabReport {
    /// The most recent numeric reading for `code` across every report, or
    /// `nil` if none exists. Mirrors `SpotlightIndexService`'s "latest metric"
    /// reduction for a single code; used by the Siri value-query intent.
    func latestEntry(for code: String) -> (entry: LabReport.Entry, date: Date)? {
        var latest: (entry: LabReport.Entry, date: Date)?
        for report in self {
            for entry in report.entries where entry.code == code && entry.numericValue != nil {
                if let current = latest, current.date >= report.date { continue }
                latest = (entry, report.date)
            }
        }
        return latest
    }

    /// Distinct LOINC codes with at least one numeric reading, across every
    /// report — the universe Siri exposure can be granted over.
    var distinctNumericCodes: Set<String> {
        var codes = Set<String>()
        for report in self {
            for entry in report.entries where entry.numericValue != nil {
                codes.insert(entry.code)
            }
        }
        return codes
    }
}
