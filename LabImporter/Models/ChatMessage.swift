import Foundation

/// A piece of the user's data a specialist accessed to answer — surfaced in the
/// chat for transparency (both the snapshot read at conversation start and any
/// follow-up tool calls map to one of these).
enum ChatToolActivity: String, Identifiable, Equatable, Sendable, Codable, CaseIterable {
    case latestLabs
    case labHistory
    case vitals
    case profile

    var id: String { rawValue }

    var label: String {
        switch self {
        case .latestLabs: return String(localized: "Read your latest lab results")
        case .labHistory: return String(localized: "Looked up a test's history")
        case .vitals:     return String(localized: "Read vitals from Apple Health")
        case .profile:    return String(localized: "Read your age and sex")
        }
    }

    var icon: String {
        switch self {
        case .latestLabs: return "doc.text.magnifyingglass"
        case .labHistory: return "chart.line.uptrend.xyaxis"
        case .vitals:     return "heart.text.square"
        case .profile:    return "person.crop.circle"
        }
    }
}

/// A single message in an AI chat conversation. Conversations live only in
/// memory for the duration of the chat — like the rest of the app there is no
/// database, and nothing about the conversation is written to Health or iCloud.
struct ChatMessage: Identifiable, Equatable, Sendable, Codable {
    enum Role: String, Sendable, Codable {
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
    /// The data the specialist read to produce this reply (assistant only),
    /// shown as transparency captions. Deduplicated, in access order.
    var toolActivities: [ChatToolActivity]

    init(
        id: UUID = UUID(),
        role: Role,
        text: String,
        isComplete: Bool = true,
        toolActivities: [ChatToolActivity] = []
    ) {
        self.id = id
        self.role = role
        self.text = text
        self.isComplete = isComplete
        self.toolActivities = toolActivities
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
                """,
                toolActivities: [.latestLabs, .labHistory, .vitals]
            ),
            ChatMessage(role: .user, text: "Is that dangerous?")
        ]
    }
}
#endif
