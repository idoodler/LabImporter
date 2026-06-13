import SwiftUI

/// Top-level host for the AI chat feature, presented modally from the dashboard.
/// Coordinates the three states — first-run intro, specialist picker, and the
/// conversation — plus persona persistence. Health-data permission isn't
/// requested here: the chat tools ask for access on demand the first time a
/// specialist actually reaches for that data (see `ChatTools`).
struct ChatContainerView: View {
    let reports: [LabReport]

    @Environment(\.dismiss) private var dismiss
    @AppStorage(PersonaStore.storageKey) private var store = PersonaStore()
    /// Gates the one-time chat intro. Defaults `false` so existing installs see
    /// it the first time they open the chat after updating.
    @AppStorage("hasSeenChatIntro") private var hasSeenChatIntro = false
    /// The user's self-reported conditions/diagnoses, shared with every
    /// specialist as background (e.g. their diabetes type). Local to the device.
    @AppStorage("userHealthContext") private var healthContext = ""

    @State private var activePersona: MedicalPersona?
    @State private var personaToEdit: MedicalPersona?
    @State private var isCreatingPersona = false
    @State private var isEditingProfile = false
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
        .sheet(isPresented: $isEditingProfile) {
            HealthProfileEditor(healthContext: $healthContext)
        }
        // Health-data access is requested on demand by the chat tools the moment
        // a specialist first reaches for it (see ChatTools) — not eagerly here —
        // so the system permission sheet only appears when it's actually needed.
        .onAppear(perform: restoreSelectionOnce)
    }

    @ViewBuilder
    private var content: some View {
        if let persona = activePersona {
            ChatView(persona: persona, reports: reports, healthContext: healthContext) {
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
                healthContext: healthContext,
                onSelect: select,
                onCreate: { isCreatingPersona = true },
                onEdit: { personaToEdit = $0 },
                onDelete: { store.delete(id: $0.id) },
                onEditProfile: { isEditingProfile = true }
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
