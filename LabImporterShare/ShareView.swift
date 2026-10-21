import SwiftUI

/// What the extension is doing, observed by `ShareView`.
@MainActor
@Observable
final class ShareModel {
    enum Failure: Equatable {
        /// Nothing textual was shared.
        case noText
        /// The App Group inbox couldn't be written.
        case unavailable
    }

    enum State: Equatable {
        case receiving
        /// Waiting in the inbox; LabImporter imports it when opened.
        case saved
        case failed(Failure)
    }

    var state: State

    init(state: State = .receiving) {
        self.state = state
    }
}

struct ShareView: View {
    let model: ShareModel
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                switch model.state {
                case .receiving:
                    ProgressView()
                        .controlSize(.large)
                case .saved:
                    message(
                        symbol: "checkmark.circle.fill", tint: .green,
                        title: "Ready in LabImporter",
                        text: "Open LabImporter to review the values. Nothing is saved to Apple Health until you confirm."
                    )
                case .failed(.noText):
                    message(
                        symbol: "text.badge.xmark", tint: .orange,
                        title: "No Text Found",
                        text: "Share the text of a lab report, for example selected in Mail or a patient portal."
                    )
                case .failed(.unavailable):
                    message(
                        symbol: "exclamationmark.triangle.fill", tint: .orange,
                        title: "Couldn't Hand Over the Text",
                        text: "Copy the text instead and use Paste from Clipboard in LabImporter."
                    )
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("LabImporter")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDone)
                        .disabled(model.state == .receiving)
                }
            }
        }
    }

    private func message(symbol: String, tint: Color, title: LocalizedStringKey, text: LocalizedStringKey) -> some View {
        VStack(spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 56))
                .foregroundStyle(tint.gradient)
                .accessibilityHidden(true)
            Text(title)
                .font(.title2.bold())
                .multilineTextAlignment(.center)
            Text(text)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: 420)
    }
}

// MARK: - Previews

#Preview("Receiving") {
    ShareView(model: ShareModel()) {}
}

#Preview("Saved") {
    ShareView(model: ShareModel(state: .saved)) {}
}

#Preview("Saved · Dark") {
    ShareView(model: ShareModel(state: .saved)) {}
        .preferredColorScheme(.dark)
}

#Preview("No Text") {
    ShareView(model: ShareModel(state: .failed(.noText))) {}
}

#Preview("Unavailable · RTL") {
    ShareView(model: ShareModel(state: .failed(.unavailable))) {}
        .environment(\.layoutDirection, .rightToLeft)
}
