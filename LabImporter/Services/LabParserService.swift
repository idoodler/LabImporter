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
    @Guide(description: "Lab test code or abbreviation exactly as printed (e.g. KREA, HB-A1C, MALB-U, GADAAK)")
    var code: String

    // swiftlint:disable:next line_length
    @Guide(description: "The test's full standard name in the report's own language — do NOT translate it, but DO expand terse lab-software mnemonics into the complete clinical term so it can be matched to a coding system. German practice software (e.g. PatMed) prints cryptic codes; expand them, never echo the raw mnemonic: KREA→Kreatinin, HARNS→Harnsäure, MALB-U→Albumin im Urin, MDRD→glomeruläre Filtrationsrate (eGFR), HB-A1C→HbA1c, HB-A1→HbA1c (IFCC), C-PEPT→C-Peptid, GADAAK→Glutamatdecarboxylase-Antikörper, ICEA→Inselzell-Antikörper, ICEA2→IA-2-Antikörper, NONHDL→Non-HDL-Cholesterin, TSH-0→Thyreotropin. For names already spelled out (e.g. 'Ferritin', 'Vitamin D 25-Hydroxy'), keep them as printed.")
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

// MARK: - Errors

/// Errors surfaced to the user when the on-device model can't parse a report.
///
/// Foundation Models throws `LanguageModelSession.GenerationError`, whose
/// `localizedDescription` is a terse, English-only system string (e.g. "An
/// unsupported language or locale was used"). Leaking that verbatim into the
/// UI is confusing — especially in a non-English app — so we translate the
/// cases we care about into clear, localized, actionable messages.
enum LabParserError: LocalizedError {
    /// The on-device model couldn't classify the report's language as supported.
    case unsupportedLanguageOrLocale
    /// The document was too large for the model's context window.
    case documentTooLong
    /// The model's safety guardrails blocked the request.
    case contentBlocked
    /// Any other generation failure — carries the system description as-is.
    case generationFailed(String)

    var errorDescription: String? {
        switch self {
        case .unsupportedLanguageOrLocale:
            return String(localized: """
            The on-device AI couldn't recognize the language of this report. This can happen \
            with very short reports, or ones made up mostly of codes and numbers. Try importing \
            or scanning the full report so there's more text to read, then try again.
            """)
        case .documentTooLong:
            return String(localized: """
            This report is too long for the on-device AI to read at once. \
            Try importing fewer pages, or one report at a time.
            """)
        case .contentBlocked:
            return String(localized: """
            The on-device AI couldn't process this document. \
            Make sure it's a lab report and try again.
            """)
        case .generationFailed(let detail):
            return detail
        }
    }

    /// Maps a Foundation Models generation error to a user-facing `LabParserError`.
    init(_ generationError: LanguageModelSession.GenerationError) {
        switch generationError {
        case .unsupportedLanguageOrLocale:
            self = .unsupportedLanguageOrLocale
        case .exceededContextWindowSize:
            self = .documentTooLong
        case .guardrailViolation:
            self = .contentBlocked
        default:
            self = .generationFailed(generationError.localizedDescription)
        }
    }
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
            Preserve codes exactly as printed in `code`. For each entry, also give the test's standard name in the
            report's own language (do NOT translate it) so it can be matched to a coding system in that language.
            German practice software (e.g. PatMed) prints terse vendor mnemonics like KREA, MALB-U, MDRD, HB-A1C,
            C-PEPT, GADAAK or TSH-0 — keep the mnemonic in `code`, but always expand it to its full standard
            clinical name in `name`; never leave `name` as the raw mnemonic.
            Do NOT emit specimen, material, or annotation labels as entries — a "value" of 'Serum', 'EDTA-Plasma',
            'Vollblut' or a standalone method tag (e.g. 'JDF-E.') is not a measured test and must be skipped.
            Detect the language of the report and return it as a BCP-47 code (e.g. 'de', 'en') in reportLanguage.
            Use '-' as rawValue when the result is negative or not detected.
            If you can see a report date or blood draw date, return it in yyyy-MM-dd format; otherwise return an empty string.
            Extract the patient's full name if visible; otherwise return an empty string for patientName.
            Extract the lab or doctor name if visible; otherwise return an empty string for authorName.
            """
        )

        // Foundation Models detects the language of the *prompt* and refuses
        // (`unsupportedLanguageOrLocale`) if it can't classify it as a supported
        // language. A terse report — mostly codes, numbers and units, e.g.
        // "KREA: 0.80 mg/dl; GPT: 59 U/l; HB-A1C: 6.5 %" — carries almost no
        // natural-language signal, so detection can fail even on a device whose
        // language is fully supported. Wrapping the report in a few sentences of
        // plain English gives the detector a reliable anchor without changing
        // what we ask it to extract (names stay in the report's own language).
        let prompt = """
        You are reading a laboratory test report. The report text below may be very short \
        and consist mostly of abbreviated test codes, numbers and units. Read it carefully \
        and extract every laboratory test value it contains. Here is the report text:

        \(text)
        """

        do {
            let response = try await session.respond(
                to: prompt,
                generating: AILabReport.self
            )
            return response.content
        } catch let error as LanguageModelSession.GenerationError {
            // Translate the model's terse, English-only system error into a
            // clear, localized message before it reaches the UI alert.
            throw LabParserError(error)
        }
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
