import SwiftUI

/// Top-level host for the AI chat feature, presented modally from the dashboard.
/// Coordinates the three states — first-run intro, specialist picker, and the
/// conversation — plus persona persistence and the lazy Health-data permission
/// request (made only once the user has seen the intro, never at app launch).
struct ChatContainerView: View {
    let reports: [LabReport]

    @Environment(\.dismiss) private var dismiss
    @AppStorage(PersonaStore.storageKey) private var store = PersonaStore()
    /// Gates the one-time chat intro. Defaults `false` so existing installs see
    /// it the first time they open the chat after updating.
    @AppStorage("hasSeenChatIntro") private var hasSeenChatIntro = false

    @State private var activePersona: MedicalPersona?
    @State private var personaToEdit: MedicalPersona?
    @State private var isCreatingPersona = false
    @State private var didRestore = false

    var body: some View {
        NavigationStack {
            content
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Done") { dismiss() }
                    }
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
        // Request vitals/glucose access only after the intro is acknowledged, so
        // the system sheet never appears before the explainer.
        .task(id: hasSeenChatIntro) {
            guard hasSeenChatIntro else { return }
            await HealthKitService.shared.requestVitalsAuthorization()
        }
        .onAppear(perform: restoreSelectionOnce)
    }

    @ViewBuilder
    private var content: some View {
        if let persona = activePersona {
            ChatView(persona: persona, reports: reports) {
                activePersona = nil
            }
            // Re-identify per persona so switching specialist rebuilds the
            // view model (fresh session, empty transcript).
            .id(persona.id)
        } else {
            PersonaPickerView(
                builtIns: MedicalPersona.builtIns,
                custom: store.customPersonas,
                selectedID: store.selectedID,
                onSelect: select,
                onCreate: { isCreatingPersona = true },
                onEdit: { personaToEdit = $0 },
                onDelete: { store.delete(id: $0.id) }
            )
        }
    }

    /// On first appearance, reopen the conversation the user last had (if its
    /// persona still resolves), otherwise land on the picker.
    private func restoreSelectionOnce() {
        guard !didRestore else { return }
        didRestore = true
        if let id = store.selectedID, let persona = store.persona(id: id) {
            activePersona = persona
        }
    }

    private func select(_ persona: MedicalPersona) {
        store.selectedID = persona.id
        activePersona = persona
    }
}

// MARK: - Preview

#Preview {
    ChatContainerView(reports: LabReport.sampleHistory)
}
