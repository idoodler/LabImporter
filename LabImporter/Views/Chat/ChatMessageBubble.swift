import SwiftUI

/// A single chat bubble. User messages sit on the trailing edge in a tinted
/// capsule; assistant messages sit on the leading edge on glass. While an
/// assistant reply is still streaming and empty, an animated typing indicator
/// stands in for the text.
struct ChatMessageBubble: View {
    let message: ChatMessage
    /// The active persona's accent, used to tint the assistant's typing dots and
    /// the user's bubble.
    let accent: Color

    private var isUser: Bool { message.role == .user }

    var body: some View {
        HStack {
            if isUser { Spacer(minLength: 40) }
            bubble
            if !isUser { Spacer(minLength: 40) }
        }
    }

    @ViewBuilder
    private var bubble: some View {
        if !isUser && message.text.isEmpty && !message.isComplete {
            TypingIndicator(accent: accent)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 20))
        } else {
            Text(message.text)
                .font(.body)
                .foregroundStyle(isUser ? Color.white : Color.primary)
                .textSelection(.enabled)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .background(bubbleBackground)
        }
    }

    @ViewBuilder
    private var bubbleBackground: some View {
        if isUser {
            RoundedRectangle(cornerRadius: 20)
                .fill(
                    LinearGradient(
                        colors: [accent, accent.opacity(0.78)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        } else {
            RoundedRectangle(cornerRadius: 20)
                .fill(.ultraThinMaterial)
                .overlay(
                    RoundedRectangle(cornerRadius: 20)
                        .stroke(Color.primary.opacity(0.06), lineWidth: 0.5)
                )
        }
    }
}

// MARK: - Typing indicator

private struct TypingIndicator: View {
    let accent: Color
    @State private var phase = 0.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3, id: \.self) { index in
                Circle()
                    .fill(accent.opacity(0.7))
                    .frame(width: 7, height: 7)
                    .scaleEffect(scale(for: index))
            }
        }
        .accessibilityLabel(Text("Assistant is typing"))
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) {
                phase = 1
            }
        }
    }

    private func scale(for index: Int) -> CGFloat {
        guard !reduceMotion else { return 1 }
        // Stagger the three dots so they pulse in sequence.
        let offset = Double(index) * 0.25
        let wave = sin((phase + offset) * .pi * 2)
        return 1 + 0.35 * CGFloat(max(0, wave))
    }
}

// MARK: - Previews

#Preview("Conversation") {
    ScrollView {
        VStack(spacing: 12) {
            ForEach(ChatMessage.sampleConversation) { message in
                ChatMessageBubble(message: message, accent: LabCategory.glycemic.color)
            }
            ChatMessageBubble(
                message: ChatMessage(role: .assistant, text: "", isComplete: false),
                accent: LabCategory.glycemic.color
            )
        }
        .padding()
    }
}

#Preview("Dark") {
    VStack(spacing: 12) {
        ChatMessageBubble(
            message: ChatMessage(role: .user, text: "Is my cholesterol okay?"),
            accent: LabCategory.cardiac.color
        )
        ChatMessageBubble(
            message: ChatMessage(role: .assistant, text: "Your LDL is 110 mg/dL in your latest report."),
            accent: LabCategory.cardiac.color
        )
    }
    .padding()
    .preferredColorScheme(.dark)
}
