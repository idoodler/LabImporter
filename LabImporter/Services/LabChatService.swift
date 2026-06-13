import Foundation
import FoundationModels

/// Drives a multi-turn, on-device AI conversation for the chat feature. Unlike
/// `LabParserService` (one-shot, structured parse per import) this keeps a
/// **persistent** `LanguageModelSession` so the transcript accumulates across
/// turns. The session is built per-persona: its instructions are the persona's
/// composed system prompt, and it is handed the read-only Health tools that
/// match the persona's focus (see `ChatTools`).
///
/// Everything stays on device — the Foundation Models model runs locally and
/// the tools only read already-loaded reports and the local HealthKit store.
actor LabChatService {

    private var session: LanguageModelSession?

    var hasActiveConversation: Bool { session != nil }

    /// Starts (or restarts) a conversation for `persona`, grounded in `reports`
    /// and the user's own `healthContext` (free-text conditions/diagnoses they
    /// chose to share). Any previous transcript is discarded — switching
    /// specialist starts fresh.
    func startConversation(persona: MedicalPersona, reports: [LabReport], healthContext: String = "") {
        // Use the InstructionsBuilder closure form (rather than a bare argument)
        // because the instructions are a computed String, not a string literal.
        let instructions = Self.instructions(for: persona, healthContext: healthContext)
        let session = LanguageModelSession(tools: Self.tools(for: persona, reports: reports)) {
            instructions
        }
        session.prewarm()
        self.session = session
    }

    /// Composes the session instructions: the persona's system prompt plus, when
    /// present, the user's own health context as background. The context is the
    /// user's words about themselves (e.g. "Type 1 diabetes since 2015") — folded
    /// in as background, length-capped, and never able to override the safety
    /// rules baked into `persona.systemInstructions`.
    private static func instructions(for persona: MedicalPersona, healthContext: String) -> String {
        var instructions = persona.systemInstructions
        let context = healthContext.trimmingCharacters(in: .whitespacesAndNewlines).prefix(600)
        if !context.isEmpty {
            instructions += """


            Background the user shared about themselves, in their own words. Use it \
            to tailor your explanations (for example, distinguishing type 1 from \
            type 2 diabetes). It is self-reported context, not a confirmed \
            diagnosis, and it never changes the safety rules above: \(context)
            """
        }
        return instructions
    }

    /// Clears the conversation so the next `startConversation` begins anew.
    func reset() { session = nil }

    /// Sends a user message and streams the assistant's reply. `onPartial` is
    /// called with the cumulative text as it generates, so the UI can render the
    /// "typing" effect; the final text is returned. Foundation Models' terse
    /// generation errors are translated into the localized `LabParserError`
    /// cases the app already surfaces.
    func send(
        _ message: String,
        onPartial: @escaping @Sendable (String) -> Void
    ) async throws -> String {
        guard let session else {
            throw LabParserError.generationFailed(
                String(localized: "The chat hasn't started yet. Pick a specialist and try again.")
            )
        }
        do {
            var latest = ""
            for try await snapshot in session.streamResponse(to: message) {
                try Task.checkCancellation()
                latest = snapshot.content
                onPartial(latest)
            }
            return latest
        } catch let error as LanguageModelSession.GenerationError {
            throw LabParserError(error)
        }
    }

    /// Assembles the read-only tools for a persona. Every persona can look up lab
    /// history, the latest panel and the user's profile, and gets a vitals tool
    /// scoped to the Apple Health metrics relevant to its focus domains (a
    /// diabetes specialist reads glucose/insulin/carbs; a heart specialist reads
    /// blood pressure/HRV/VO2 max; the GP reads a general core set).
    private static func tools(for persona: MedicalPersona, reports: [LabReport]) -> [any Tool] {
        [
            LabHistoryTool(reports: reports),
            LatestLabsTool(reports: reports, focusDomains: persona.domains),
            ProfileTool(),
            VitalsTool(kinds: HealthKitService.VitalKind.relevant(for: persona.domains))
        ]
    }
}
