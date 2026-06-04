import SwiftUI

struct WelcomeView: View {
    let onDismiss: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    private var features: [Feature] {
        [
            Feature(
                icon: "camera.viewfinder",
                color: LabCategory.bloodGas.color,
                title: "Import Any Lab Report",
                description: "Photograph, paste, or scan a report to extract your values."
            ),
            Feature(
                icon: "sparkles",
                color: LabCategory.endocrine.color,
                title: "On-Device AI",
                description: "Apple Intelligence reads your report privately — nothing leaves your device."
            ),
            Feature(
                icon: "heart.text.square.fill",
                color: LabCategory.cardiac.color,
                title: "Saved to Apple Health",
                description: "Reports are stored as clinical CDA records directly in Apple Health."
            ),
            Feature(
                icon: "chart.line.uptrend.xyaxis",
                color: LabCategory.hepatic.color,
                title: "Track Your Trends",
                description: "See how your values change over time with interactive charts."
            )
        ]
    }

    var body: some View {
        OnboardingScaffold {
            hero
        } card: {
            featureCard
        } footer: {
            footer
        }
        .background { MorphingCategoryBackground() }
        .onAppear {
            guard !reduceMotion else { appeared = true; return }
            withAnimation(.smooth(duration: 0.7)) { appeared = true }
        }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(spacing: 20) {
            appIcon
            VStack(spacing: 4) {
                Text("Welcome to")
                    .font(.title3)
                    .foregroundStyle(.secondary)
                Text(verbatim: "LabImporter")
                    .font(.largeTitle.bold())
            }
        }
        .opacity(appeared ? 1 : 0)
        .offset(y: appeared ? 0 : 16)
    }

    @ViewBuilder
    private var appIcon: some View {
        // Use the color-scheme-adaptive `AppIconPreview` image set so Dark Mode
        // shows the dark artwork — an app-icon set's dark variant isn't reachable
        // via `UIImage(named:)`.
        if UIImage(named: "AppIconPreview") != nil {
            Image("AppIconPreview")
                .resizable()
                .frame(width: 110, height: 110)
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(Color.white.opacity(0.25), lineWidth: 0.5)
                )
                .shadow(color: .black.opacity(0.18), radius: 24, x: 0, y: 10)
        }
    }

    // MARK: - Feature card

    private var featureCard: some View {
        VStack(alignment: .leading, spacing: 22) {
            ForEach(Array(features.enumerated()), id: \.offset) { index, feature in
                FeatureRow(feature: feature)
                    .opacity(appeared ? 1 : 0)
                    .offset(y: appeared ? 0 : 20)
                    .animation(
                        reduceMotion ? nil : .smooth(duration: 0.6).delay(0.15 + Double(index) * 0.08),
                        value: appeared
                    )
            }
        }
        .padding(24)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 28))
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(Color.primary.opacity(0.08), lineWidth: 0.5)
        )
    }

    // MARK: - Footer

    private var footer: some View {
        Button("Get Started", action: onDismiss)
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
            .opacity(appeared ? 1 : 0)
    }
}

// MARK: - Feature model & row

private struct Feature {
    let icon: String
    let color: Color
    let title: LocalizedStringKey
    let description: LocalizedStringKey
}

private struct FeatureRow: View {
    let feature: Feature

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [feature.color, feature.color.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 58, height: 58)
                    .shadow(color: feature.color.opacity(0.35), radius: 6, x: 0, y: 3)
                Image(systemName: feature.icon)
                    .font(.title2)
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(feature.title)
                    .font(.body.bold())
                Text(feature.description)
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
