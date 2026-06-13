import SwiftUI

/// A reusable navigation-bar entry point for the AI chat. It owns its own
/// presentation state, so a host screen adds the chat with a single modifier
/// without growing its own body — handy because the dashboard/home screens sit
/// right at their length limits. Presented as a full-screen cover.
struct ChatEntryButtonModifier: ViewModifier {
    let reports: [LabReport]
    var placement: ToolbarItemPlacement = .topBarTrailing
    @State private var showChat = false

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: placement) {
                    Button { showChat = true } label: {
                        Image(systemName: "bubble.left.and.text.bubble.right")
                    }
                    .accessibilityLabel("Health Assistant")
                }
            }
            .fullScreenCover(isPresented: $showChat) {
                ChatContainerView(reports: reports)
            }
    }
}

extension View {
    /// Adds a "Health Assistant" toolbar button that opens the on-device AI chat.
    func chatEntryPoint(
        reports: [LabReport],
        placement: ToolbarItemPlacement = .topBarTrailing
    ) -> some View {
        modifier(ChatEntryButtonModifier(reports: reports, placement: placement))
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        Text(verbatim: "Dashboard")
            .navigationTitle("Lab Results")
            .chatEntryPoint(reports: LabReport.sampleHistory)
    }
}
