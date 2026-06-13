import Foundation

/// A single message in an AI chat conversation. Conversations live only in
/// memory for the duration of the chat — like the rest of the app there is no
/// database, and nothing about the conversation is written to Health or iCloud.
struct ChatMessage: Identifiable, Equatable, Sendable {
    enum Role: Sendable {
        case user
        case assistant
    }

    let id: UUID
    let role: Role
    /// The message text. For a streaming assistant reply this grows token by
    /// token while `isComplete` is false.
    var text: String
    /// False while an assistant reply is still streaming in; always true for
    /// user messages.
    var isComplete: Bool

    init(id: UUID = UUID(), role: Role, text: String, isComplete: Bool = true) {
        self.id = id
        self.role = role
        self.text = text
        self.isComplete = isComplete
    }
}

#if DEBUG
extension ChatMessage {
    /// A short sample exchange for `ChatView` previews.
    static var sampleConversation: [ChatMessage] {
        [
            ChatMessage(role: .user, text: "How has my HbA1c changed over the last year?"),
            ChatMessage(
                role: .assistant,
                text: """
                Your HbA1c has edged up a little: it was 5.4% in your oldest report and \
                reads 5.7% in the most recent one. That's still in the range your reports \
                list, but the upward drift is worth mentioning to your doctor at your next \
                visit.
                """
            ),
            ChatMessage(role: .user, text: "Is that dangerous?")
        ]
    }
}
#endif
