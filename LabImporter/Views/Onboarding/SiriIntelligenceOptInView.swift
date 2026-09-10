import SwiftUI

/// Onboarding step that introduces Siri & Shortcuts support (App Intents —
/// SiriKit's replacement) and asks, up front, whether to turn it on at all.
/// Mirrors `SpotlightOptInView`: the decision is recorded via `onDecision`,
/// which the host persists and uses to clear the onboarding gate. Turning it
/// on only enables the scan shortcut (no health data); which values Siri may
/// read back stays a separate, per-metric opt-in made later in Settings
/// (`SiriAccessEditor`) — nothing is exposed just because onboarding says yes.
struct SiriIntelligenceOptInView: View {
    /// Called with the user's choice (`true` = turn on Siri & Shortcuts). The
    /// host stores the preference and dismisses the gate.
    let onDecision: (Bool) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    private var benefits: [Benefit] {
        [
            Benefit(
                icon: "waveform.path.ecg",
                color: LabCategory.cardiac.color,
                title: "Ask Siri Anytime",
                description: """
                Say “Ask LabImporter about my cholesterol” and Siri reads back your latest \
                reading — but only for values you allow.
                """
            ),
            Benefit(
                icon: "doc.viewfinder",
                color: LabCategory.bloodGas.color,
                title: "Start a Scan Hands-Free",
                description: "“Scan a lab report in LabImporter” opens the scanner instantly — no health data involved."
            ),
            Benefit(
                icon: "hand.raised.fill",
                color: LabCategory.hepatic.color,
                title: "You Choose What Siri Knows",
                description: "Nothing is shared until you allow it. Pick exactly which values Siri can access anytime in Settings."
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
                            colors: [Color.purple.opacity(0.45), Color.purple.opacity(0)],
                            center: .center,
                            startRadius: 4,
                            endRadius: 90
                        )
                    )
                    .frame(width: 180, height: 180)
                Image(systemName: "waveform.and.mic")
                    .font(.system(size: 84, weight: .semibold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.purple.gradient)
                    .shadow(color: .purple.opacity(0.35), radius: 18, x: 0, y: 8)
            }
            VStack(spacing: 6) {
                Text("Ask Siri About Your Values")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Let Siri answer questions and start scans — entirely on-device.")
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
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "lock.shield.fill")
                    .font(.footnote)
                    .foregroundStyle(.tint)
                Text("Siri runs the on-device model, the same one used to scan your reports. Nothing leaves this device.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if isEURegion {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "info.circle")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text("Some Siri intelligence features may arrive later in the EU under regional requirements.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .opacity(appeared ? 1 : 0)
    }

    /// Best-effort EU check (device region, not IP-based) purely to decide
    /// whether to show the regional-rollout footnote — it never gates any
    /// feature itself, since Apple's own availability check already governs
    /// what's actually offered on device.
    private var isEURegion: Bool {
        guard let region = Locale.current.region?.identifier else { return false }
        let euMembers: Set<String> = [
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"
        ]
        return euMembers.contains(region)
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
                Text("Enable Siri & Shortcuts")
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
    SiriIntelligenceOptInView { _ in }
}

#Preview("Dark") {
    SiriIntelligenceOptInView { _ in }
        .preferredColorScheme(.dark)
}

#Preview("Landscape") {
    SiriIntelligenceOptInView { _ in }
        .previewInterfaceOrientation(.landscapeLeft)
}
