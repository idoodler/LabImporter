import SwiftUI

/// Onboarding step that introduces system-wide Spotlight search for tracked lab
/// values and asks, up front, whether to also surface each value's latest
/// reading there. Mirrors `CloudSyncOptInView`: the choice is recorded via
/// `onDecision`, which the host persists (into the search preference) and uses
/// to clear the onboarding gate.
struct SpotlightOptInView: View {
    /// Called with the user's choice (`true` = also show the latest reading in
    /// search). The host stores the preference and dismisses the gate.
    let onDecision: (Bool) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    private var benefits: [Benefit] {
        [
            Benefit(
                icon: "magnifyingglass",
                color: LabCategory.endocrine.color,
                title: "Find Labs from Search",
                description: "Swipe down on the Home Screen, type a value's name, and open its trend in a tap."
            ),
            Benefit(
                icon: "lock.shield.fill",
                color: LabCategory.cardiac.color,
                title: "Names, Not Numbers",
                description: "By default only the names of the values you track are searchable — never a reading."
            ),
            Benefit(
                icon: "chart.line.uptrend.xyaxis",
                color: LabCategory.hepatic.color,
                title: "Show Your Latest Reading",
                description: """
                Optionally surface each value's most recent reading in search too. \
                Change it anytime in Settings.
                """
            )
        ]
    }

    var body: some View {
        OnboardingScaffold {
            hero
        } card: {
            benefitCard
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
                Image(systemName: "sparkle.magnifyingglass")
                    .font(.system(size: 84, weight: .semibold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.orange.gradient)
                    .shadow(color: .orange.opacity(0.35), radius: 18, x: 0, y: 8)
            }
            VStack(spacing: 6) {
                Text("Search Your Lab Values")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Find any value you track straight from iOS search.")
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

    // MARK: - Benefit card

    private var benefitCard: some View {
        VStack(alignment: .leading, spacing: 22) {
            ForEach(Array(benefits.enumerated()), id: \.offset) { index, benefit in
                BenefitRow(benefit: benefit)
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

    // MARK: - Privacy note

    private var privacyNote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock.shield.fill")
                .font(.footnote)
                .foregroundStyle(.tint)
            Text("Search entries stay on this device. Your lab values never leave Apple Health.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .opacity(appeared ? 1 : 0)
    }

    // MARK: - Footer

    private var footer: some View {
        VStack(spacing: 16) {
            privacyNote
            buttons
        }
    }

    private var buttons: some View {
        VStack(spacing: 12) {
            Button {
                onDecision(true)
            } label: {
                Text("Show Latest Values")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Button {
                onDecision(false)
            } label: {
                Text("Not Now")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .opacity(appeared ? 1 : 0)
    }
}

// MARK: - Benefit model & row

private struct Benefit {
    let icon: String
    let color: Color
    let title: LocalizedStringKey
    let description: LocalizedStringKey
}

private struct BenefitRow: View {
    let benefit: Benefit

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [benefit.color, benefit.color.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 58, height: 58)
                    .shadow(color: benefit.color.opacity(0.35), radius: 6, x: 0, y: 3)
                Image(systemName: benefit.icon)
                    .font(.title2)
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(benefit.title)
                    .font(.body.bold())
                Text(benefit.description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Preview

#Preview("Light") {
    SpotlightOptInView { _ in }
}

#Preview("Dark") {
    SpotlightOptInView { _ in }
        .preferredColorScheme(.dark)
}

#Preview("Landscape") {
    SpotlightOptInView { _ in }
        .previewInterfaceOrientation(.landscapeLeft)
}
