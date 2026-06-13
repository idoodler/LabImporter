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
    private let service = LabChatService()
    private var sendTask: Task<Void, Never>?

    init(persona: MedicalPersona, reports: [LabReport]) {
        self.persona = persona
        self.reports = reports
    }

    var canSend: Bool {
        !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isResponding
    }

    var isEmpty: Bool { messages.isEmpty }

    /// Spins up the persona's session (prewarming the model) before the first
    /// message so the initial reply isn't gated on a cold start.
    func start() async {
        await service.startConversation(persona: persona, reports: reports)
    }

    func send() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isResponding else { return }
        input = ""
        errorMessage = nil
        messages.append(ChatMessage(role: .user, text: text))
        let placeholder = ChatMessage(role: .assistant, text: "", isComplete: false)
        messages.append(placeholder)
        let assistantID = placeholder.id
        isResponding = true

        sendTask = Task { [weak self] in
            guard let self else { return }
            do {
                let final = try await self.service.send(text) { [weak self] partial in
                    Task { @MainActor [weak self] in
                        self?.update(id: assistantID, text: partial, complete: false)
                    }
                }
                self.update(id: assistantID, text: final, complete: true)
            } catch is CancellationError {
                self.finishOrDrop(id: assistantID)
            } catch {
                self.finishOrDrop(id: assistantID)
                self.errorMessage = error.localizedDescription
            }
            self.isResponding = false
        }
    }

    /// Cancels an in-flight reply (the partial text so far is kept).
    func stop() {
        sendTask?.cancel()
    }

    /// Clears the conversation and starts a fresh session with the same persona.
    func newConversation() {
        sendTask?.cancel()
        messages.removeAll()
        errorMessage = nil
        Task { await service.reset(); await start() }
    }

    private func update(id: UUID, text: String, complete: Bool) {
        guard let index = messages.firstIndex(where: { $0.id == id }) else { return }
        // Snapshots arrive cumulatively; guard against a late, shorter one
        // briefly clobbering a longer partial.
        if text.count >= messages[index].text.count || complete {
            messages[index].text = text
        }
        if complete { messages[index].isComplete = true }
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
