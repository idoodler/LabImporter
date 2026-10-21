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
