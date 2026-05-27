import Foundation
import FoundationModels

// MARK: - Generable types for structured AI output

@Generable
struct AILabReport {
    @Guide(description: "All lab test values found in the text")
    var entries: [AILabEntry]

    @Guide(description: "Report or blood draw date in yyyy-MM-dd format (e.g. 2026-05-06). Empty string if no date is visible.")
    var reportDate: String
}

@Generable
struct AILabEntry {
    @Guide(description: "Lab test code or abbreviation as printed (e.g. KREA, HB-A1C, G-GT)")
    var code: String

    @Guide(description: "Value exactly as printed — use '-' for negative/not-detected results")
    var rawValue: String

    @Guide(description: "Unit of measurement as printed (e.g. mg/dl, %, mmol/mol, U/l, ml/min/1,73m2KOF). Empty string if none.")
    var unit: String
}

// MARK: - Parser

actor LabParserService {

    func parseLabValues(from text: String) async throws -> (values: [LabValue], reportDate: Date?) {
        let entries: [AILabEntry]
        var reportDate: Date?

        if SystemLanguageModel.default.isAvailable {
            let report = try await parseWithFoundationModels(text: text)
            entries = report.entries
            reportDate = parseDate(report.reportDate)
        } else {
            entries = parseWithRegex(text: text)
            reportDate = extractDate(from: text)
        }

        let values = entries.map { entry in
            let normalizedValue = entry.rawValue
                .trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: ",", with: ".")

            let numericValue: Double? = entry.rawValue == "-" ? nil : Double(normalizedValue)

            return LabValue(
                code: entry.code,
                name: LabMapping.displayName(for: entry.code),
                displayValue: entry.rawValue,
                numericValue: numericValue,
                unit: entry.unit,
                healthKitMapping: LabMapping.healthKitMapping(for: entry.code)
            )
        }

        return (values, reportDate)
    }

    // MARK: - Foundation Models path

    private func parseWithFoundationModels(text: String) async throws -> AILabReport {
        let session = LanguageModelSession(
            instructions: """
            You are a medical lab report parser. Extract every lab test entry from the provided text.
            Lab reports follow the pattern: CODE: value unit; CODE2: value2 unit2; ...
            Preserve codes exactly as printed. Use '-' as rawValue when the result is negative or not detected.
            If you can see a report date or blood draw date, return it in yyyy-MM-dd format; otherwise return an empty string.
            """
        )

        let response = try await session.respond(
            to: "Extract all lab values from this text:\n\n\(text)",
            generating: AILabReport.self
        )

        return response.content
    }

    // MARK: - Regex fallback

    // Handles: "CODE: value unit;" or "CODE: - ;" patterns from semicolon-separated German lab reports
    private func parseWithRegex(text: String) -> [AILabEntry] {
        // Split on semicolons to isolate individual entries
        let segments = text.components(separatedBy: ";")

        // Pattern: one or more UPPERCASE letters/digits/hyphens, colon, then value and optional unit
        let entryPattern = /([A-Z][A-Z0-9\-]+)\s*:\s*(-|[\d]+[,\.]?[\d]*)\s*(.*)/

        return segments.compactMap { segment in
            let trimmed = segment.trimmingCharacters(in: .whitespacesAndNewlines)
            guard let match = trimmed.firstMatch(of: entryPattern) else { return nil }

            let code = String(match.1)
            let rawValue = String(match.2)
            let unit = String(match.3).trimmingCharacters(in: .whitespacesAndNewlines)

            return AILabEntry(code: code, rawValue: rawValue, unit: unit)
        }
    }

    // MARK: - Date helpers

    // Scans free text for German (dd.MM.yyyy) or ISO (yyyy-MM-dd) date patterns.
    private func extractDate(from text: String) -> Date? {
        let germanPattern = /\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b/
        if let match = text.firstMatch(of: germanPattern) {
            let day = Int(match.1) ?? 0
            let month = Int(match.2) ?? 0
            let year = Int(match.3) ?? 0
            return makeDate(year: year, month: month, day: day)
        }

        let isoPattern = /\b(\d{4})-(\d{2})-(\d{2})\b/
        if let match = text.firstMatch(of: isoPattern) {
            let year = Int(match.1) ?? 0
            let month = Int(match.2) ?? 0
            let day = Int(match.3) ?? 0
            return makeDate(year: year, month: month, day: day)
        }

        return nil
    }

    private func parseDate(_ string: String) -> Date? {
        guard !string.isEmpty else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.date(from: string)
    }

    private func makeDate(year: Int, month: Int, day: Int) -> Date? {
        guard year > 1900, (1...12).contains(month), (1...31).contains(day) else { return nil }
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        return Calendar.current.date(from: components)
    }
}
