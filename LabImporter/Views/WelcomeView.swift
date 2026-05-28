import SwiftUI

struct WelcomeView: View {
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            // App icon + title
            VStack(spacing: 20) {
                if let icon = UIImage(named: "AppIcon") {
                    Image(uiImage: icon)
                        .resizable()
                        .frame(width: 110, height: 110)
                        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                        .shadow(color: .black.opacity(0.14), radius: 20, x: 0, y: 8)
                }
                VStack(spacing: 4) {
                    Text("Welcome to")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                    Text("LabImporter")
                        .font(.largeTitle.bold())
                }
            }

            Spacer()

            // Feature list
            VStack(alignment: .leading, spacing: 28) {
                FeatureRow(
                    icon: "camera.viewfinder",
                    color: .blue,
                    title: "Import Any Lab Report",
                    description: "Photograph, paste, or scan a report to extract your values."
                )
                FeatureRow(
                    icon: "sparkles",
                    color: .purple,
                    title: "On-Device AI",
                    description: "Apple Intelligence reads your report privately — nothing leaves your device."
                )
                FeatureRow(
                    icon: "heart.text.square.fill",
                    color: .red,
                    title: "Saved to Apple Health",
                    description: "Reports are stored as clinical CDA records directly in Apple Health."
                )
                FeatureRow(
                    icon: "chart.line.uptrend.xyaxis",
                    color: .green,
                    title: "Track Your Trends",
                    description: "See how your values change over time with interactive charts."
                )
            }
            .padding(.horizontal, 32)

            Spacer()

            Button("Get Started", action: onDismiss)
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.bottom, 48)
        }
    }
}

// MARK: - Feature row

private struct FeatureRow: View {
    let icon: String
    let color: Color
    let title: String
    let description: String

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(color.opacity(0.13))
                    .frame(width: 58, height: 58)
                Image(systemName: icon)
                    .font(.title2)
                    .foregroundStyle(color)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body.bold())
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    WelcomeView { }
}
