import Foundation
import FoundationModels
import LabImporterCore

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

    // swiftlint:disable:next line_length
    @Guide(description: "Result as printed, without a high/low flag (H, L, *), also when it isn't a number (e.g. 0,80, Negativ, not detected, 1:160, <0.5). Use '-' only when no result is printed")
    var rawValue: String

    @Guide(description: "Unit of measurement as printed (e.g. mg/dl, %, mmol/mol, U/l, ml/min/1,73m2KOF). Empty string if none.")
    var unit: String
}

extension AILabReport {
    /// The same fields, as the shared module's type.
    var shared: LabImporterCore.AiLabReport {
        LabImporterCore.AiLabReport(
            entries: entries.map { LabImporterCore.AiLabEntry(code: $0.code, name: $0.name, rawValue: $0.rawValue, unit: $0.unit) },
            reportDate: reportDate, patientName: patientName, authorName: authorName, reportLanguage: reportLanguage
        )
    }
}

// MARK: - Result

struct ParseResult {
    let values: [LabValue]
    let reportDate: Date?
    let patientName: String?
    let authorName: String?
}

/// Live progress of a streaming parse, surfaced by the processing HUD: how
/// many entries the model has emitted so far and the most recent test name.
/// The name may still be mid-generation — it fills in token by token, which
/// the HUD shows as a pleasant "typing" effect.
struct ParseProgress: Equatable, Sendable {
    var entryCount: Int
    var latestName: String?
}

// MARK: - Errors

/// Errors surfaced to the user when the on-device model can't parse a report.
///
/// Foundation Models throws `LanguageModelSession.GenerationError` (iOS 27:
/// `LanguageModelError`), whose `localizedDescription` is a terse, English-only system string (e.g. "An
/// unsupported language or locale was used"). Leaking that verbatim into the
/// UI is confusing — especially in a non-English app — so we translate the
/// cases we care about into clear, localized, actionable messages.
enum LabParserError: LocalizedError, Equatable {
    /// Apple Intelligence is switched off in Settings.
    case appleIntelligenceOff
    /// The model is still being prepared (downloading) or its assets are missing.
    case modelNotReady
    /// Too many requests right now (rate limit or another request in flight).
    case busy
    /// The on-device model couldn't classify the report's language as supported.
    case unsupportedLanguageOrLocale
    /// The document was too large for the model's context window.
    case documentTooLong
    /// The model's safety guardrails blocked the request.
    case contentBlocked
    /// Any other generation failure (the system's own text is English-only and
    /// opaque, e.g. "GenerationError -1", so the user gets a clear message instead).
    case generationFailed

    var errorDescription: String? {
        switch self {
        case .appleIntelligenceOff:
            return String(localized: """
            Turn on Apple Intelligence in Settings to let LabImporter read your lab reports on this device.
            """)
        case .modelNotReady:
            return String(localized: """
            Apple Intelligence is still getting ready on this device. Try again once it has finished downloading.
            """)
        case .busy:
            return String(localized: "The on-device AI is busy right now. Wait a moment and try again.")
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
        case .contentBlocked, .generationFailed:
            return String(localized: """
            The on-device AI couldn't process this document. \
            Make sure it's a lab report and try again.
            """)
        }
    }

    /// Why the system model can't be used right now, or nil when it's available.
    /// (Ineligible hardware never gets here: the app shows "Device Not Supported".)
    init?(_ availability: SystemLanguageModel.Availability) {
        switch availability {
        case .available:
            return nil
        case .unavailable(.appleIntelligenceNotEnabled):
            self = .appleIntelligenceOff
        case .unavailable:
            self = .modelNotReady
        }
    }

    /// The user-facing error for anything a session throws: Foundation Models'
    /// own errors become a `LabParserError`, anything else (cancellation)
    /// passes through. iOS 27 throws `LanguageModelError` and friends instead
    /// of `GenerationError` to apps built with its SDK.
    static func from(_ error: Error) -> Error {
        if let error = error as? LabParserError { return error }
        if let error = error as? LanguageModelSession.GenerationError { return LabParserError(error) }
        #if compiler(>=6.4)
        if #available(iOS 27, *) {
            if let error = error as? LanguageModelError { return LabParserError(error) }
            if error is SystemLanguageModel.Error { return LabParserError.modelNotReady }
            if error is LanguageModelSession.Error { return LabParserError.busy }
            if error is GeneratedContent.ParsingError { return LabParserError.generationFailed }
        }
        #endif
        return error
    }

    /// Maps a Foundation Models generation error to a user-facing `LabParserError`.
    init(_ generationError: LanguageModelSession.GenerationError) {
        switch generationError {
        case .unsupportedLanguageOrLocale:
            self = .unsupportedLanguageOrLocale
        case .exceededContextWindowSize:
            self = .documentTooLong
        case .guardrailViolation, .refusal:
            self = .contentBlocked
        case .assetsUnavailable:
            self = .modelNotReady
        case .rateLimited, .concurrentRequests:
            self = .busy
        default:
            self = .generationFailed
        }
    }

    #if compiler(>=6.4)
    /// Maps an iOS 27 language model error to a user-facing `LabParserError`.
    @available(iOS 27, *)
    init(_ error: LanguageModelError) {
        switch error {
        case .contextSizeExceeded:
            self = .documentTooLong
        case .unsupportedLanguageOrLocale:
            self = .unsupportedLanguageOrLocale
        case .guardrailViolation, .refusal:
            self = .contentBlocked
        case .rateLimited, .timeout:
            self = .busy
        default:
            self = .generationFailed
        }
    }
    #endif
}

// MARK: - Parser

actor LabParserService {

    /// Reads one part of a report into the model's structured reply, reporting
    /// streamed progress. Foundation Models in the app; tests (and the DEBUG
    /// screenshot mode) hand in a stand-in, since a simulator has no model.
    typealias PartReader = @Sendable (
        _ part: String,
        _ onProgress: @escaping @Sendable (ParseProgress) -> Void
    ) async throws -> AILabReport

    /// The stand-in, when there is one; nil reads with Foundation Models.
    private let partReader: PartReader?

    init(partReader: PartReader? = nil) {
        #if DEBUG
        self.partReader = partReader ?? ScreenshotMode.partReader
        #else
        self.partReader = partReader
        #endif
    }

    /// A session created (and prewarmed) ahead of the next parse so the model
    /// loads while the user is still scanning or while OCR runs, instead of
    /// paying that startup cost serialized after it.
    private var preparedSession: LanguageModelSession?

    func prewarm() {
        guard partReader == nil, preparedSession == nil else { return }
        let session = Self.makeSession()
        session.prewarm()
        preparedSession = session
    }

    /// Parses lab values out of `text`. `onProgress` is invoked as the model
    /// streams entries, with the running count and latest test name.
    func parseLabValues(
        from text: String,
        onProgress: (@Sendable (ParseProgress) -> Void)? = nil
    ) async throws -> ParseResult {
        // Say why the model can't be used (Apple Intelligence off, still
        // downloading) instead of letting the session fail with an opaque error.
        if partReader == nil, let unavailable = LabParserError(SystemLanguageModel.default.availability) {
            throw unavailable
        }
        let report = try await parseInParts(text: text, onProgress: onProgress)
        // Number parsing, LOINC resolution (in the report's own language), date
        // validation against the text and de-duplication are shared with Android so both apps turn
        // the same model output into the same values (LabImporterCore.LabParsePipeline).
        SharedCore.start()
        let result = LabParsePipeline.shared.postProcess(report: report, sourceText: text)
        return ParseResult(
            values: result.values.map(LabValue.init(shared:)),
            reportDate: result.reportDate.map(Date.init(shared:)),
            patientName: result.patientName,
            authorName: result.authorName
        )
    }

    // MARK: - Foundation Models path

    private static func makeSession() -> LanguageModelSession {
        // The instructions are shared with Android's Gemini Nano path
        // (LabImporterCore `LabParsePrompt`), so both platforms extract alike.
        LanguageModelSession(instructions: LabParsePrompt.shared.instructions)
    }

    /// Consumes the prewarmed session if one is waiting, else creates a fresh
    /// one. Sessions are single-use per part so transcripts never accumulate
    /// across parts or imports.
    private func takeSession() -> LanguageModelSession {
        defer { preparedSession = nil }
        return preparedSession ?? Self.makeSession()
    }

    /// Reads the report part by part (LabImporterCore `LabParseChunks`, shared
    /// with Android): the model's context has to hold the instructions, the
    /// schema, the text and every entry read from it, so a long report never
    /// fits whole. A part the model still finds too long is read in halves.
    /// Each part gets a fresh session; progress counts on across parts.
    private func parseInParts(
        text: String,
        onProgress: (@Sendable (ParseProgress) -> Void)?
    ) async throws -> LabImporterCore.AiLabReport {
        SharedCore.start()
        let chunks = LabParseChunks.shared
        var pending = chunks.parts(text: text)
        var replies: [LabImporterCore.AiLabReport] = []
        var entriesBefore = 0
        var latestName: String?
        while !pending.isEmpty {
            let part = pending.removeFirst()
            let offset = entriesBefore
            let previousName = latestName
            let reply: AILabReport
            do {
                let report: @Sendable (ParseProgress) -> Void = { progress in
                    onProgress?(ParseProgress(
                        entryCount: offset + progress.entryCount,
                        latestName: progress.latestName ?? previousName
                    ))
                }
                if let partReader {
                    reply = try await partReader(part, report)
                } else {
                    reply = try await parseWithFoundationModels(text: part, onProgress: report)
                }
            } catch LabParserError.documentTooLong {
                guard let halves = chunks.smaller(part: part) else { throw LabParserError.documentTooLong }
                pending.insert(contentsOf: halves, at: 0)
                continue
            }
            replies.append(reply.shared)
            entriesBefore += reply.entries.count
            latestName = reply.entries.last?.name ?? latestName
        }
        return chunks.merge(reports: replies)
    }

    private func parseWithFoundationModels(
        text: String,
        onProgress: (@Sendable (ParseProgress) -> Void)?
    ) async throws -> AILabReport {
        let session = takeSession()

        // Foundation Models detects the language of the *prompt* and refuses
        // (`unsupportedLanguageOrLocale`) if it can't classify it as a supported
        // language. A terse report — mostly codes, numbers and units, e.g.
        // "KREA: 0.80 mg/dl; GPT: 59 U/l; HB-A1C: 6.5 %" — carries almost no
        // natural-language signal, so detection can fail even on a device whose
        // language is fully supported. Wrapping the report in a few sentences of
        // plain English gives the detector a reliable anchor without changing
        // what we ask it to extract (names stay in the report's own language).
        let prompt = LabParsePrompt.shared.prompt(reportText: text)

        do {
            // Stream the structured response instead of awaiting it whole:
            // every snapshot carries the entries generated so far, which the
            // processing HUD surfaces as live progress while the model works.
            var lastSnapshot: AILabReport.PartiallyGenerated?
            var reported = ParseProgress(entryCount: 0, latestName: nil)
            let stream = session.streamResponse(to: prompt, generating: AILabReport.self)
            for try await snapshot in stream {
                try Task.checkCancellation()
                lastSnapshot = snapshot.content
                guard let onProgress else { continue }
                let entries = snapshot.content.entries ?? []
                let progress = ParseProgress(
                    entryCount: entries.count,
                    // Hold the previous name while a new entry's name hasn't
                    // started generating yet, so the HUD never flashes empty.
                    latestName: entries.last?.name ?? reported.latestName
                )
                if progress != reported {
                    reported = progress
                    onProgress(progress)
                }
            }
            return materialize(lastSnapshot)
        } catch {
            // Translate the model's terse, English-only system error into a
            // clear, localized message before it reaches the UI alert.
            throw LabParserError.from(error)
        }
    }

    /// Builds the final report from the last streamed snapshot. The stream's
    /// final snapshot is the complete generation, so the defaults only paper
    /// over a stream that ended early — which then surfaces to the user as
    /// "no values found" rather than a crash on a half-built entry.
    private func materialize(_ partial: AILabReport.PartiallyGenerated?) -> AILabReport {
        let entries = (partial?.entries ?? []).compactMap { entry -> AILabEntry? in
            let code = entry.code ?? ""
            let name = entry.name ?? ""
            guard !code.isEmpty || !name.isEmpty else { return nil }
            return AILabEntry(code: code, name: name, rawValue: entry.rawValue ?? "", unit: entry.unit ?? "")
        }
        return AILabReport(
            entries: entries,
            reportDate: partial?.reportDate ?? "",
            patientName: partial?.patientName ?? "",
            authorName: partial?.authorName ?? "",
            reportLanguage: partial?.reportLanguage ?? ""
        )
    }
}
