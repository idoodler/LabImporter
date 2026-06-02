import SwiftUI

/// Onboarding step shown between the welcome screen and the Apple Health
/// permission gate. It frames the safety expectations for an app whose values
/// are OCR/AI-extracted — broken into a few digestible points rather than a
/// wall of legal text — and requires an explicit acknowledgement to continue.
struct DisclaimerView: View {
    let onAcknowledge: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    private var points: [Point] {
        [
            Point(
                icon: "sparkles",
                color: LabCategory.endocrine.color,
                title: "Automated, Not Perfect",
                description: "Values are extracted by on-device AI and OCR, which can misread or miss results."
            ),
            Point(
                icon: "checkmark.circle.fill",
                color: LabCategory.hepatic.color,
                title: "Always Verify",
                description: "Check every value against your original lab report before relying on it."
            ),
            Point(
                icon: "cross.case.fill",
                color: LabCategory.cardiac.color,
                title: "Not Medical Advice",
                description: "LabImporter isn't a medical device. For decisions about your health, consult a qualified professional."
            )
        ]
    }

    var body: some View {
        OnboardingScaffold {
            hero
        } card: {
            pointCard
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
            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [Color.orange.opacity(0.45), Color.orange.opacity(0)],
                            center: .center,
                            startRadius: 4,
                            endRadius: 90
                        )
                    )
                    .frame(width: 180, height: 180)
                Image(systemName: "exclamationmark.shield.fill")
                    .font(.system(size: 92, weight: .semibold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.orange.gradient)
                    .shadow(color: .orange.opacity(0.35), radius: 18, x: 0, y: 8)
            }
            VStack(spacing: 6) {
                Text("Before You Start")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Text("A quick note on how to use LabImporter safely.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 24)
        }
        .opacity(appeared ? 1 : 0)
        .offset(y: appeared ? 0 : 16)
    }

    // MARK: - Point card

    private var pointCard: some View {
        VStack(alignment: .leading, spacing: 22) {
            ForEach(Array(points.enumerated()), id: \.offset) { index, point in
                PointRow(point: point)
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
        Button("I Understand", action: onAcknowledge)
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)
            .opacity(appeared ? 1 : 0)
    }
}

// MARK: - Point model & row

private struct Point {
    let icon: String
    let color: Color
    let title: LocalizedStringKey
    let description: LocalizedStringKey
}

private struct PointRow: View {
    let point: Point

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [point.color, point.color.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 58, height: 58)
                    .shadow(color: point.color.opacity(0.35), radius: 6, x: 0, y: 3)
                Image(systemName: point.icon)
                    .font(.title2)
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(point.title)
                    .font(.body.bold())
                Text(point.description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    DisclaimerView { }
}
