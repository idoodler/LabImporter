import SwiftUI

/// The Chat tab's root: the user's saved conversations (newest first), a way to
/// start a new one, and the health-profile entry. Tapping a conversation reopens
/// it; swiping deletes it.
struct ConversationListView: View {
    let conversations: [ChatConversation]
    /// Resolves a stored `personaID` to its specialist (built-in or custom).
    let resolvePersona: (String) -> MedicalPersona?
    let healthContext: String
    let onNew: () -> Void
    let onOpen: (ChatConversation) -> Void
    let onDelete: (ChatConversation) -> Void
    let onEditProfile: () -> Void

    var body: some View {
        List {
            Section("About You") {
                healthProfileRow
            }
            Section("Conversations") {
                if conversations.isEmpty {
                    Text("No conversations yet")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(conversations) { conversation in
                        row(for: conversation)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) { onDelete(conversation) } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                    }
                }
            }
        }
        .navigationTitle("Chat")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: onNew) {
                    Image(systemName: "square.and.pencil")
                }
                .accessibilityLabel("New Conversation")
            }
        }
    }

    private func row(for conversation: ChatConversation) -> some View {
        let persona = resolvePersona(conversation.personaID)
        let color = persona?.color ?? LabCategory.other.color
        return Button {
            onOpen(conversation)
        } label: {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(color.gradient)
                        .frame(width: 44, height: 44)
                    Image(systemName: persona?.iconName ?? "bubble.left.and.text.bubble.right")
                        .font(.title3)
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(persona?.name ?? String(localized: "Specialist"))
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(conversation.title.isEmpty
                         ? Text(conversation.updatedAt, format: .relative(presentation: .named))
                         : Text(conversation.title))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private var healthProfileRow: some View {
        Button(action: onEditProfile) {
            HStack(spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(LabCategory.endocrine.color.gradient)
                        .frame(width: 44, height: 44)
                    Image(systemName: "person.text.rectangle.fill")
                        .font(.title3)
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("Your Health Profile")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    (healthContext.isEmpty
                        ? Text("Add conditions like your diabetes type for tailored answers")
                        : Text(healthContext))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Previews

#if DEBUG
private extension ChatConversation {
    static var samples: [ChatConversation] {
        [
            ChatConversation(personaID: "diabetes", title: "How has my HbA1c changed?",
                             messages: ChatMessage.sampleConversation,
                             updatedAt: Date().addingTimeInterval(-3600)),
            ChatConversation(personaID: "heart", title: "Is my cholesterol okay?",
                             messages: ChatMessage.sampleConversation,
                             updatedAt: Date().addingTimeInterval(-86_400))
        ]
    }
}
#endif

#Preview("Populated") {
    NavigationStack {
        ConversationListView(
            conversations: ChatConversation.samples,
            resolvePersona: { id in MedicalPersona.builtIns.first { $0.id == id } },
            healthContext: "Type 1 diabetes since 2015",
            onNew: {}, onOpen: { _ in }, onDelete: { _ in }, onEditProfile: {}
        )
    }
}

#Preview("Empty · Dark") {
    NavigationStack {
        ConversationListView(
            conversations: [],
            resolvePersona: { _ in nil },
            healthContext: "",
            onNew: {}, onOpen: { _ in }, onDelete: { _ in }, onEditProfile: {}
        )
    }
    .preferredColorScheme(.dark)
}
