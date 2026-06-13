import SwiftUI

/// The conversation screen for one specialist persona. Hosts the message list,
/// an empty state with suggested prompts, and the input bar. The model work
/// runs on-device via `ChatViewModel` → `LabChatService`.
struct ChatView: View {
    @State private var viewModel: ChatViewModel
    @FocusState private var inputFocused: Bool

    @MainActor
    init(
        persona: MedicalPersona,
        reports: [LabReport],
        healthContext: String = "",
        conversation: ChatConversation,
        onUpdate: @escaping @MainActor (ChatConversation) -> Void
    ) {
        _viewModel = State(initialValue: ChatViewModel(
            persona: persona,
            reports: reports,
            healthContext: healthContext,
            conversation: conversation,
            onUpdate: onUpdate
        ))
    }

    private var persona: MedicalPersona { viewModel.persona }

    var body: some View {
        VStack(spacing: 0) {
            conversation
            inputBar
        }
        .background { CategoryBackground(colors: [persona.color]) }
        .navigationTitle(persona.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await viewModel.start() }
    }

    // MARK: - Conversation

    private var conversation: some View {
        ScrollViewReader { proxy in
            ScrollView {
                if viewModel.isEmpty {
                    emptyState
                        .padding(.top, 24)
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(viewModel.messages) { message in
                            ChatMessageBubble(message: message, accent: persona.color)
                                .id(message.id)
                        }
                        if let error = viewModel.errorMessage {
                            errorNotice(error)
                        }
                        Color.clear.frame(height: 1).id(bottomAnchor)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 16)
                }
            }
            .scrollDismissesKeyboard(.immediately)
            .onChange(of: viewModel.messages.last?.text) { _, _ in scrollToBottom(proxy) }
            .onChange(of: viewModel.messages.count) { _, _ in scrollToBottom(proxy) }
        }
    }

    private let bottomAnchor = "chat-bottom"

    private func scrollToBottom(_ proxy: ScrollViewProxy) {
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(bottomAnchor, anchor: .bottom) }
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle()
                    .fill(persona.color.opacity(0.18))
                    .frame(width: 96, height: 96)
                Image(systemName: persona.iconName)
                    .font(.system(size: 42, weight: .semibold))
                    .foregroundStyle(persona.color)
            }
            VStack(spacing: 6) {
                Text(persona.name)
                    .font(.title2.bold())
                Text("Ask anything about your results. Answers come from your own data, on your device.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 32)
            suggestionChips
        }
        .frame(maxWidth: .infinity)
    }

    private var suggestionChips: some View {
        VStack(spacing: 10) {
            ForEach(Array(Self.suggestions.enumerated()), id: \.offset) { _, suggestion in
                Button {
                    viewModel.input = String(localized: suggestion)
                    viewModel.send()
                } label: {
                    Text(suggestion)
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20)
    }

    private static let suggestions: [LocalizedStringResource] = [
        "Summarize my most recent report",
        "What has changed since last time?",
        "Explain my results in simple terms"
    ]

    private func errorNotice(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial))
    }

    // MARK: - Input bar

    private var inputBar: some View {
        VStack(spacing: 6) {
            HStack(alignment: .bottom, spacing: 10) {
                TextField("Ask about your results…", text: $viewModel.input, axis: .vertical)
                    .lineLimit(1...5)
                    .focused($inputFocused)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 22))
                sendOrStopButton
            }
            disclaimer
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 10)
        .background(.bar)
    }

    @ViewBuilder
    private var sendOrStopButton: some View {
        if viewModel.isResponding {
            Button(action: viewModel.stop) {
                Image(systemName: "stop.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(.secondary)
            }
            .accessibilityLabel("Stop")
        } else {
            Button(action: viewModel.send) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 32))
                    .foregroundStyle(viewModel.canSend ? persona.color : Color.secondary.opacity(0.5))
            }
            .disabled(!viewModel.canSend)
            .accessibilityLabel("Send")
        }
    }

    private var disclaimer: some View {
        Text("AI can make mistakes and this isn't medical advice. Always consult a professional.")
            .font(.caption2)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
    }
}

// MARK: - Previews

#Preview("Empty") {
    NavigationStack {
        ChatView(
            persona: MedicalPersona.builtIns[1],
            reports: LabReport.sampleHistory,
            conversation: ChatConversation(personaID: "diabetes"),
            onUpdate: { _ in }
        )
    }
}

#Preview("Conversation") {
    NavigationStack {
        ChatPreviewHarness(messages: ChatMessage.sampleConversation)
    }
}

#Preview("Dark") {
    NavigationStack {
        ChatView(
            persona: MedicalPersona.builtIns[2],
            reports: LabReport.sampleHistory,
            conversation: ChatConversation(personaID: "heart"),
            onUpdate: { _ in }
        )
    }
    .preferredColorScheme(.dark)
}

#if DEBUG
/// A lightweight stand-in that renders a seeded conversation without touching
/// the on-device model, so the populated state is inspectable in previews.
private struct ChatPreviewHarness: View {
    let messages: [ChatMessage]
    private let persona = MedicalPersona.builtIns[1]

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 12) {
                ForEach(messages) { ChatMessageBubble(message: $0, accent: persona.color) }
            }
            .padding(16)
        }
        .background { CategoryBackground(colors: [persona.color]) }
        .navigationTitle(persona.name)
        .navigationBarTitleDisplayMode(.inline)
    }
}
#endif
