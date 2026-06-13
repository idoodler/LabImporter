import Foundation
import Observation

/// Drives a single chat conversation for the UI: owns the message list, the
/// input field, and the streaming send loop against `LabChatService`. Lives on
/// the main actor because it feeds SwiftUI directly; the heavy lifting (the
/// on-device model) runs inside the service actor.
@MainActor
@Observable
final class ChatViewModel {
    let persona: MedicalPersona
    private(set) var messages: [ChatMessage] = []
    var input: String = ""
    private(set) var isResponding = false
    /// A localized error from the last failed turn, surfaced as an inline notice.
    var errorMessage: String?

    private let reports: [LabReport]
    /// The user's self-reported conditions/diagnoses, shared with the specialist.
    private let healthContext: String
    /// Identity of the saved conversation this view model reads from / writes to.
    private let conversationID: UUID
    /// Persists the conversation after each turn (into the history store).
    private let onUpdate: @MainActor (ChatConversation) -> Void
    private let service = LabChatService()
    private let reporter = ChatToolReporter()
    private var sendTask: Task<Void, Never>?
    /// The assistant message currently being produced — data-access events are
    /// attached to it.
    private var currentAssistantID: UUID?
    /// Snapshot reads happen at conversation start, before any reply exists, so
    /// they're buffered here and flushed onto the first assistant message.
    private var bufferedActivities: [ChatToolActivity] = []

    init(
        persona: MedicalPersona,
        reports: [LabReport],
        healthContext: String,
        conversation: ChatConversation,
        onUpdate: @escaping @MainActor (ChatConversation) -> Void
    ) {
        self.persona = persona
        self.reports = reports
        self.healthContext = healthContext
        self.conversationID = conversation.id
        self.onUpdate = onUpdate
        self.messages = conversation.messages
        reporter.setHandler { [weak self] activity in
            Task { @MainActor [weak self] in self?.record(activity) }
        }
    }

    /// Saves the current conversation (title derived from the first user
    /// message) back to the history store.
    private func persist() {
        let firstUser = messages.first(where: { $0.role == .user })?.text ?? ""
        let saved = messages.filter { !($0.role == .assistant && $0.text.isEmpty) }
        onUpdate(ChatConversation(
            id: conversationID,
            personaID: persona.id,
            title: String(firstUser.prefix(80)),
            messages: saved,
            updatedAt: Date()
        ))
    }

    /// Attaches a data-access event to the current reply, or buffers it when one
    /// isn't being produced yet (the start-of-conversation snapshot reads).
    private func record(_ activity: ChatToolActivity) {
        if let id = currentAssistantID, let index = messages.firstIndex(where: { $0.id == id }) {
            if !messages[index].toolActivities.contains(activity) {
                messages[index].toolActivities.append(activity)
            }
        } else if !bufferedActivities.contains(activity) {
            bufferedActivities.append(activity)
        }
    }

    var canSend: Bool {
        !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isResponding
    }

    var isEmpty: Bool { messages.isEmpty }

    /// Spins up the persona's session (prewarming the model) before the first
    /// message so the initial reply isn't gated on a cold start.
    func start() async {
        await service.startConversation(
            persona: persona, reports: reports, healthContext: healthContext, reporter: reporter
        )
    }

    func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isResponding else { return }
        input = ""
        errorMessage = nil
        messages.append(ChatMessage(role: .user, text: text))
        // Carry any buffered start-of-conversation snapshot reads onto this first
        // reply, then route subsequent live tool events to it.
        let placeholder = ChatMessage(role: .assistant, text: "", isComplete: false,
                                      toolActivities: bufferedActivities)
        bufferedActivities.removeAll()
        messages.append(placeholder)
        let assistantID = placeholder.id
        currentAssistantID = assistantID
        isResponding = true

        sendTask = Task { [weak self] in
            guard let self else { return }
            do {
                let final = try await self.service.send(text) { [weak self] partial in
                    Task { @MainActor [weak self] in
                        self?.update(id: assistantID, text: partial, complete: false)
                    }
                }
                let trimmed = final.trimmingCharacters(in: .whitespacesAndNewlines)
                self.update(id: assistantID, text: trimmed, complete: true)
            } catch is CancellationError {
                self.finishOrDrop(id: assistantID)
            } catch {
                self.finishOrDrop(id: assistantID)
                self.errorMessage = error.localizedDescription
            }
            self.currentAssistantID = nil
            self.isResponding = false
            self.persist()
        }
    }

    /// Cancels an in-flight reply (the partial text so far is kept).
    func stop() {
        sendTask?.cancel()
    }

    private func update(id: UUID, text rawText: String, complete: Bool) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        let text = Self.sanitize(rawText)
        // Snapshots arrive cumulatively; guard against a late, shorter one
        // briefly clobbering a longer partial.
        if text.count >= messages[index].text.count || complete {
            messages[index].text = text
        }
        if complete { messages[index].isComplete = true }
    }

    /// The on-device model occasionally leaks a transcript role marker
    /// ("model" / "assistant") as a prefix on its reply. Strip a leading one —
    /// only when it stands alone as the first token — before display.
    private static func sanitize(_ text: String) -> String {
        let lower = text.lowercased()
        for marker in ["model", "assistant"] where lower.hasPrefix(marker) {
            let rest = text[text.index(text.startIndex, offsetBy: marker.count)...]
            if rest.isEmpty || rest.first == "\n" || rest.first == " " {
                return String(rest).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return text
    }

    /// On cancel/error, drop an assistant bubble that never produced text;
    /// otherwise just mark whatever streamed in as complete.
    private func finishOrDrop(id: UUID) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        if messages[index].text.isEmpty {
            messages.remove(at: index)
        } else {
            messages[index].isComplete = true
        }
    }
}
