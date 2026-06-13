import SwiftUI

/// Top-level host for the AI chat tab. Coordinates the first-run intro and the
/// navigation between the saved-conversation list, the specialist picker, and a
/// conversation. Persona definitions and chat history persist via `@AppStorage`
/// (and roam through `CloudSyncService` when iCloud sync is on). Health-data
/// permission isn't requested here — the chat tools ask on demand (see
/// `ChatTools`).
struct ChatContainerView: View {
    let reports: [LabReport]

    @AppStorage(PersonaStore.storageKey) private var store = PersonaStore()
    @AppStorage(ChatHistoryStore.storageKey) private var history = ChatHistoryStore()
    /// Gates the one-time chat intro. Defaults `false` so existing installs see
    /// it the first time they open the chat after updating.
    @AppStorage("hasSeenChatIntro") private var hasSeenChatIntro = false
    /// The user's self-reported conditions/diagnoses, shared with every
    /// specialist as background (e.g. their diabetes type). Local to the device.
    @AppStorage("userHealthContext") private var healthContext = ""

    @State private var path: [ChatRoute] = []
    @State private var personaToEdit: MedicalPersona?
    @State private var isCreatingPersona = false
    @State private var isEditingProfile = false

    private enum ChatRoute: Hashable {
        case picker
        case conversation(UUID)
    }

    var body: some View {
        NavigationStack(path: $path) {
            ConversationListView(
                conversations: history.sorted,
                resolvePersona: { store.persona(id: $0) },
                healthContext: healthContext,
                onNew: { path.append(.picker) },
                onOpen: { path.append(.conversation($0.id)) },
                onDelete: { history.delete(id: $0.id) },
                onEditProfile: { isEditingProfile = true }
            )
            .navigationDestination(for: ChatRoute.self) { route in
                destination(for: route)
            }
        }
        .fullScreenCover(isPresented: Binding(
            get: { !hasSeenChatIntro },
            set: { _ in }
        )) {
            ChatIntroView {
                withAnimation(.smooth(duration: 0.35)) { hasSeenChatIntro = true }
            }
        }
        .sheet(isPresented: $isCreatingPersona) {
            PersonaEditorView(existing: nil) { store.upsert($0) }
        }
        .sheet(item: $personaToEdit) { persona in
            PersonaEditorView(existing: persona) { store.upsert($0) }
        }
        .sheet(isPresented: $isEditingProfile) {
            HealthProfileEditor(healthContext: $healthContext)
        }
    }

    @ViewBuilder
    private func destination(for route: ChatRoute) -> some View {
        switch route {
        case .picker:
            PersonaPickerView(
                builtIns: MedicalPersona.builtIns,
                custom: store.customPersonas,
                selectedID: store.selectedID,
                healthContext: healthContext,
                onSelect: startConversation,
                onCreate: { isCreatingPersona = true },
                onEdit: { personaToEdit = $0 },
                onDelete: { store.delete(id: $0.id) },
                onEditProfile: { isEditingProfile = true }
            )
        case .conversation(let id):
            if let conversation = history.conversation(id: id),
               let persona = store.persona(id: conversation.personaID) {
                ChatView(
                    persona: persona,
                    reports: reports,
                    healthContext: healthContext,
                    conversation: conversation,
                    onUpdate: { history.upsert($0) }
                )
            } else {
                ContentUnavailableView(
                    "Conversation unavailable",
                    systemImage: "exclamationmark.bubble"
                )
            }
        }
    }

    /// Creates a fresh conversation with the chosen specialist and opens it,
    /// replacing the picker in the stack so Back returns to the list.
    private func startConversation(with persona: MedicalPersona) {
        history.conversations.removeAll { $0.isEmpty }
        let conversation = ChatConversation(personaID: persona.id)
        history.upsert(conversation)
        store.selectedID = persona.id
        path = [.conversation(conversation.id)]
    }
}

// MARK: - Preview

#Preview {
    ChatContainerView(reports: LabReport.sampleHistory)
}
