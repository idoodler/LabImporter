import Foundation
import FoundationModels

// MARK: - Generable types for structured AI output

@Generable
struct AILabReport {
    @Guide(description: "All lab test values found in the text")
    var entries: [AILabEntry]

    @Guide(description: "Report or blood draw date in yyyy-MM-dd format (e.g. 2026-05-06). Empty string if no date is visible.")
    var reportDate: String

    @Guide(description: "Patient full name as printed on the report. Empty string if not visible.")
    var patientName: String

    @Guide(description: "Lab or doctor name as printed on the report. Empty string if not visible.")
    var authorName: String

    @Guide(description: "BCP-47 language code of the report text, e.g. 'de' or 'en'. Empty string if unsure.")
    var reportLanguage: String
}

@Generable
struct AILabEntry {
    @Guide(description: "Lab test code or abbreviation as printed (e.g. KREA, HB-A1C, G-GT)")
    var code: String

    // swiftlint:disable:next line_length
    @Guide(description: "The test's standard name in the report's own language — do NOT translate (e.g. for a German report 'Kreatinin', 'Ferritin', 'Vitamin D 25-Hydroxy', 'Thyreotropin'). Used to look up the standard LOINC code in that language.")
    var name: String

    @Guide(description: "Value exactly as printed — use '-' for negative/not-detected results")
    var rawValue: String

    @Guide(description: "Unit of measurement as printed (e.g. mg/dl, %, mmol/mol, U/l, ml/min/1,73m2KOF). Empty string if none.")
    var unit: String
}

// MARK: - Result

struct ParseResult {
    let values: [LabValue]
    let reportDate: Date?
    let patientName: String?
    let authorName: String?
}

// MARK: - Parser

actor LabParserService {

    func parseLabValues(from text: String) async throws -> ParseResult {
        let report = try await parseWithFoundationModels(text: text)
        let entries = report.entries
        let reportDate = parseDate(report.reportDate)
        let patientName = report.patientName.isEmpty ? nil : report.patientName
        let authorName = report.authorName.isEmpty ? nil : report.authorName

        let values = entries.map { entry in
            let normalizedValue = entry.rawValue
                .trimmingCharacters(in: .whitespaces)
                .replacingOccurrences(of: ",", with: ".")

            let numericValue: Double? = entry.rawValue == "-" ? nil : Double(normalizedValue)

            // Resolve the printed code to LOINC, the app's canonical identity.
            let (code, suggested) = resolveLoinc(printed: entry.code, name: entry.name, language: report.reportLanguage)

            return LabValue(
                code: code,
                name: LabMapping.displayName(for: code),
                displayValue: entry.rawValue,
                numericValue: numericValue,
                unit: entry.unit,
                isSuggestedCode: suggested
            )
        }

        return ParseResult(values: values, reportDate: reportDate, patientName: patientName, authorName: authorName)
    }

    // Resolves a parsed entry to a LOINC code entirely from the bundled catalog.
    // An already-valid LOINC code (pasted in or printed verbatim) is canonical and
    // returned as-is; otherwise the AI's test name is matched against the catalog
    // in the report's own language (with English as a fallback) and the top hit is
    // returned as a *suggestion* the user confirms. Unresolved entries keep the
    // printed text so they can be mapped manually in the review sheet.
    private func resolveLoinc(printed: String, name: String, language: String) -> (code: String, suggested: Bool) {
        let directory = LoincDirectory.shared
        let trimmedPrinted = printed.trimmingCharacters(in: .whitespaces)
        if directory.isKnownLoinc(trimmedPrinted) {
            return (trimmedPrinted, false)
        }
        let resolvedLanguage = directory.resolvedLanguage(for: language)
        for query in [name, printed] {
            let trimmed = query.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty else { continue }
            if let match = directory.search(trimmed, language: resolvedLanguage, limit: 1).first {
                return (match.code, true)
            }
        }
        return (printed, false)
    }

    // MARK: - Foundation Models path

    private func parseWithFoundationModels(text: String) async throws -> AILabReport {
        let session = LanguageModelSession(
            instructions: """
            You are a medical lab report parser. Extract every lab test entry from the provided text.
            Lab reports follow the pattern: CODE: value unit; CODE2: value2 unit2; ...
            Preserve codes exactly as printed. For each entry, also give the test's standard name in the
            report's own language (do NOT translate it) so it can be matched to a coding system in that language.
            Detect the language of the report and return it as a BCP-47 code (e.g. 'de', 'en') in reportLanguage.
            Use '-' as rawValue when the result is negative or not detected.
            If you can see a report date or blood draw date, return it in yyyy-MM-dd format; otherwise return an empty string.
            Extract the patient's full name if visible; otherwise return an empty string for patientName.
            Extract the lab or doctor name if visible; otherwise return an empty string for authorName.
            """
        )

        let response = try await session.respond(
            to: "Extract all lab values from this text:\n\n\(text)",
            generating: AILabReport.self
        )

        return response.content
    }

    // MARK: - Date helpers

    private func parseDate(_ string: String) -> Date? {
        guard !string.isEmpty else { return nil }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.date(from: string)
    }
}
