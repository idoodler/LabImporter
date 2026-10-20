import Foundation
import LabImporterCore

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
    /// Editable values for the review/edit sheet.
    var asLabValues: [LabValue] {
        shared.asLabValues.map(LabValue.init(shared:))
    }

    /// The clinical category that the most values belong to (ties: canonical
    /// order). The report's representative color, so the list row and the
    /// detail screen agree. Shared rule.
    var dominantCategory: LabCategory? {
        shared.dominantCategory.map { LabCategory(sharedName: $0.name) }
    }
}

extension Array where Element == LabReport {
    /// The most recent numeric reading for `code` across every report, or `nil`.
    /// Used by Siri, Spotlight and the metric search. Shared rule.
    func latestEntry(for code: String) -> (entry: LabReport.Entry, date: Date)? {
        guard let pair = LabReportKt.latestEntry(map(\.shared), code: code),
              let entry = pair.first, let date = pair.second else { return nil }
        return (LabReport.Entry(shared: entry), Date(shared: date))
    }

    /// Distinct LOINC codes with at least one numeric reading, across every
    /// report — the universe Siri exposure can be granted over.
    var distinctNumericCodes: Set<String> {
        LabReportKt.distinctNumericCodes(map(\.shared))
    }
}
