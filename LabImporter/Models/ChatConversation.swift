import Foundation

/// One saved AI-chat conversation: the messages exchanged with a specialist,
/// plus enough metadata to list and reopen it. Persisted (and optionally iCloud
/// synced) via `ChatHistoryStore`.
struct ChatConversation: Identifiable, Codable, Equatable, Sendable {
    let id: UUID
    /// The specialist this conversation is with (built-in slug or custom UUID).
    var personaID: String
    /// Short title derived from the first user message (empty until one is sent).
    var title: String
    var messages: [ChatMessage]
    var updatedAt: Date

    init(id: UUID = UUID(), personaID: String, title: String = "", messages: [ChatMessage] = [], updatedAt: Date = Date()) {
        self.id = id
        self.personaID = personaID
        self.title = title
        self.messages = messages
        self.updatedAt = updatedAt
    }

    /// True for a conversation the user hasn't actually written in yet — used to
    /// avoid persisting empty shells and to filter them out of the list.
    var isEmpty: Bool { messages.allSatisfy { $0.role == .assistant } }
}

/// Persisted set of saved conversations, newest first. `RawRepresentable` over
/// JSON so it lives in `@AppStorage`; its key is in `CloudSyncService.syncedKeys`
/// so — when the user enables iCloud sync — chat history roams across their
/// devices like the dashboard layout and their custom specialists. Uses the
/// same separate `Payload` type as `LabDisplayPreferences` to avoid the
/// Codable + RawRepresentable recursion trap.
///
/// History is **capped** (`maxConversations`) because the iCloud key-value store
/// has a hard ~1 MB total quota; keeping only the most recent conversations
/// keeps the synced blob comfortably small.
struct ChatHistoryStore: RawRepresentable, Equatable {
    var conversations: [ChatConversation] = []

    /// Most conversations to retain. Oldest beyond this are dropped so the
    /// synced blob stays well under the iCloud KVS quota.
    static let maxConversations = 25

    init() {}

    init?(rawValue: String) {
        guard let data = rawValue.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(Payload.self, from: data)
        else { self = ChatHistoryStore(); return }
        conversations = decoded.conversations ?? []
    }

    var rawValue: String {
        let payload = Payload(conversations: conversations)
        return (try? JSONEncoder().encode(payload)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    /// Conversations that have real content, newest first.
    var sorted: [ChatConversation] {
        conversations.filter { !$0.isEmpty }.sorted { $0.updatedAt > $1.updatedAt }
    }

    func conversation(id: UUID) -> ChatConversation? {
        conversations.first { $0.id == id }
    }

    /// Inserts or replaces a conversation, then trims to the cap (newest kept).
    mutating func upsert(_ conversation: ChatConversation) {
        if let index = conversations.firstIndex(where: { $0.id == conversation.id }) {
            conversations[index] = conversation
        } else {
            conversations.append(conversation)
        }
        let kept = conversations.sorted { $0.updatedAt > $1.updatedAt }.prefix(Self.maxConversations)
        conversations = Array(kept)
    }

    mutating func delete(id: UUID) {
        conversations.removeAll { $0.id == id }
    }

    // Separate Codable type breaks the Codable + RawRepresentable encoding cycle
    // (see `LabDisplayPreferences.Payload`). Optional for forward/backward compat.
    private struct Payload: Codable {
        var conversations: [ChatConversation]?
    }
}

extension ChatHistoryStore {
    /// The `@AppStorage` key chat history is persisted under (and synced when on).
    static let storageKey = "chatHistory"
}
