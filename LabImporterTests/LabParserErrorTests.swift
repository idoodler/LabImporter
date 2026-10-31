import FoundationModels
import Testing
@testable import LabImporter

/// What the user is told when Apple Intelligence can't read a report — never
/// an opaque system error like "GenerationError -1".
struct LabParserErrorTests {
    private let context = LanguageModelSession.GenerationError.Context(debugDescription: "test")

    @Test func availabilityStatesBecomeActionableMessages() {
        #expect(LabParserError(SystemLanguageModel.Availability.available) == nil)
        #expect(LabParserError(.unavailable(.appleIntelligenceNotEnabled)) == .appleIntelligenceOff)
        #expect(LabParserError(.unavailable(.modelNotReady)) == .modelNotReady)
    }

    @Test func generationErrorsMapToTheUsersSituation() {
        #expect(LabParserError(.exceededContextWindowSize(context)) == .documentTooLong)
        #expect(LabParserError(.assetsUnavailable(context)) == .modelNotReady)
        #expect(LabParserError(.rateLimited(context)) == .busy)
        #expect(LabParserError(.concurrentRequests(context)) == .busy)
        #expect(LabParserError(.guardrailViolation(context)) == .contentBlocked)
        #expect(LabParserError(.unsupportedLanguageOrLocale(context)) == .unsupportedLanguageOrLocale)
        #expect(LabParserError(.decodingFailure(context)) == .generationFailed)
    }

    @Test func otherErrorsPassThroughUnchanged() {
        #expect(LabParserError.from(LabParserError.busy) as? LabParserError == .busy)
        #expect(LabParserError.from(LanguageModelSession.GenerationError.exceededContextWindowSize(context))
            as? LabParserError == .documentTooLong)
        #expect(LabParserError.from(CancellationError()) is CancellationError)
    }

    #if compiler(>=6.4)
    /// iOS 27 throws these instead of `GenerationError` to apps built with its SDK.
    @Test func languageModelErrorsMapToTheUsersSituation() throws {
        guard #available(iOS 27, *) else { return }
        let tooLong = LanguageModelError.contextSizeExceeded(.init(contextSize: 4096, tokenCount: 5000, debugDescription: "test"))
        #expect(LabParserError.from(tooLong) as? LabParserError == .documentTooLong)
        #expect(LabParserError(.rateLimited(.init(resetDate: nil, debugDescription: "test"))) == .busy)
        #expect(LabParserError(.timeout(.init(debugDescription: "test"))) == .busy)
        #expect(LabParserError(.guardrailViolation(.init(debugDescription: "test"))) == .contentBlocked)
        #expect(LabParserError(.unsupportedLanguageOrLocale(.init(languageCode: "xx", debugDescription: "test")))
            == .unsupportedLanguageOrLocale)
        #expect(LabParserError.from(SystemLanguageModel.Error.assetsUnavailable(.init(debugDescription: "test")))
            as? LabParserError == .modelNotReady)
        #expect(LabParserError.from(LanguageModelSession.Error.concurrentRequests) as? LabParserError == .busy)
    }
    #endif

    @Test func everyCaseHasAReadableMessage() {
        let all: [LabParserError] = [
            .appleIntelligenceOff, .modelNotReady, .busy, .unsupportedLanguageOrLocale,
            .documentTooLong, .contentBlocked, .generationFailed
        ]
        for error in all {
            let message = error.errorDescription ?? ""
            #expect(!message.isEmpty && !message.contains("GenerationError"), "\(error)")
        }
    }
}
