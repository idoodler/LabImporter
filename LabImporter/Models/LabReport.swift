import Foundation

struct LabReport: Codable, Identifiable {
    let id: UUID
    let date: Date
    let patientName: String
    let authorName: String
    let entries: [Entry]

    struct Entry: Codable, Identifiable {
        let id: UUID
        let code: String              // LOINC identifier
        let name: String              // Display name captured at import time
        let displayValue: String
        let numericValue: Double?
        let unit: String
        // Reference range printed on the report itself (per-entry).
        let parsedRange: ParsedRange?

        init(
            id: UUID,
            code: String,
            name: String,
            displayValue: String,
            numericValue: Double?,
            unit: String,
            parsedRange: ParsedRange? = nil
        ) {
            self.id = id
            self.code = code
            self.name = name
            self.displayValue = displayValue
            self.numericValue = numericValue
            self.unit = unit
            self.parsedRange = parsedRange
        }

        var resolvedName: String {
            if let entry = LoincDirectory.shared.entry(for: code) {
                return LoincDirectory.shared.displayName(for: entry)
            }
            return name.isEmpty ? code : name
        }
    }
}

extension LabReport {
    var asLabValues: [LabValue] {
        entries.map {
            LabValue(code: $0.code, name: $0.resolvedName,
                     displayValue: $0.displayValue,
                     numericValue: $0.numericValue,
                     unit: $0.unit,
                     parsedRange: $0.parsedRange)
        }
    }
}
