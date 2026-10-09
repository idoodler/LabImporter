import SwiftUI

/// Final mandatory onboarding step: three independent, off-by-default
/// opt-ins — iCloud sync, showing a value's latest reading in search, and
/// Siri & Shortcuts — each a plain toggle rather than its own full-screen
/// yes/no decision. Replaces the three separate screens that used to ask
/// these one at a time (`CloudSyncOptInView`, `SpotlightOptInView`,
/// `SiriIntelligenceOptInView`): none of them gate anything irreversible —
/// every toggle here has an identical twin in Settings (`SettingsView`,
/// `SiriAccessEditor`) — so asking three separate times up front, before the
/// user has imported a single report, was more friction than the choice
/// warranted. Siri's own granular capability toggles (which values, which
/// features) live only in `SiriAccessEditor` now; this screen only sets the
/// master switch, exactly like tapping "Allow Siri Access" in Settings.
struct OnboardingExtrasView: View {
    /// Called once, when the user taps Continue, with the three toggle
    /// states. The host persists them into their respective `@AppStorage`
    /// bindings and clears the onboarding gate.
    let onContinue: (_ iCloudSync: Bool, _ showLatestValueInSearch: Bool, _ siriEnabled: Bool) -> Void
    /// Returns to the Health permission step. `nil` would hide the back
    /// button, but this is never the first step, so the caller always
    /// supplies one.
    var onBack: (() -> Void)?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false
    @State private var iCloudSync = false
    @State private var showLatestValueInSearch = false
    @State private var siriEnabled = false

    var body: some View {
        OnboardingScaffold(step: 4, totalSteps: 4, onBack: onBack) {
            hero
        } card: {
            toggleCard
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
                            colors: [Color.indigo.opacity(0.45), Color.indigo.opacity(0)],
                            center: .center,
                            startRadius: 4,
                            endRadius: 90
                        )
                    )
                    .frame(width: 180, height: 180)
                Image(systemName: "switch.2")
                    .font(.system(size: 84, weight: .semibold))
                    .foregroundStyle(.white, Color.indigo.gradient)
                    .shadow(color: .indigo.opacity(0.35), radius: 18, x: 0, y: 8)
            }
            VStack(spacing: 6) {
                Text("A Few More Things")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                Text("All optional, and all changeable later in Settings.")
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

    // MARK: - Toggle card

    private var toggleCard: some View {
        VStack(alignment: .leading, spacing: 22) {
            // Titles and descriptions below are reused verbatim from
            // `SettingsView`'s identical toggles — the same choice, worded
            // identically wherever it's made.
            ExtraToggleRow(
                icon: "arrow.triangle.2.circlepath",
                color: .blue,
                title: "iCloud Sync",
                description: """
                Sync your dashboard layout — the card order, what you pin and hide, your \
                nicknames, and your reference ranges — across your devices. Your lab values \
                stay in Apple Health.
                """,
                isOn: $iCloudSync
            )
            .onboardingExtraRow(appeared: appeared, delay: 0.15, reduceMotion: reduceMotion)

            ExtraToggleRow(
                icon: "magnifyingglass",
                color: .orange,
                title: "Show Latest Value in Search",
                description: """
                Let each value's most recent reading appear in iOS search results. \
                When off, only the value's name is shown — never a reading.
                """,
                isOn: $showLatestValueInSearch
            )
            .onboardingExtraRow(appeared: appeared, delay: 0.23, reduceMotion: reduceMotion)

            ExtraToggleRow(
                icon: "waveform",
                color: .purple,
                title: "Allow Siri Access",
                description: """
                Let Siri answer questions and start scans for LabImporter. You still choose \
                exactly which values it can read.
                """,
                isOn: $siriEnabled
            )
            .onboardingExtraRow(appeared: appeared, delay: 0.31, reduceMotion: reduceMotion)
        }
        .padding(24)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 28))
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(Color.primary.opacity(0.08), lineWidth: 0.5)
        )
    }

    // MARK: - Footer

    private var footer: some View {
        VStack(spacing: 16) {
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
                .opacity(appeared ? 1 : 0)
            }
            Button {
                onContinue(iCloudSync, showLatestValueInSearch, siriEnabled)
            } label: {
                Text("Continue")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .opacity(appeared ? 1 : 0)
        }
    }

    /// Best-effort EU check (device region, not IP-based) purely to decide
    /// whether to show the regional-rollout footnote for Siri — it never
    /// gates any feature itself, since Apple's own availability check
    /// already governs what's actually offered on device.
    private var isEURegion: Bool {
        guard let region = Locale.current.region?.identifier else { return false }
        let euMembers: Set<String> = [
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
            "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE"
        ]
        return euMembers.contains(region)
    }
}

// MARK: - Row entrance animation

private extension View {
    /// Shared fade/slide-in used by every row in the toggle card.
    func onboardingExtraRow(appeared: Bool, delay: Double, reduceMotion: Bool) -> some View {
        opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 20)
            .animation(reduceMotion ? nil : .smooth(duration: 0.6).delay(delay), value: appeared)
    }
}

// MARK: - ExtraToggleRow

/// A whole-row toggle: icon + title + description as the `Toggle` label, so
/// VoiceOver gets one element exposing the on/off state, and the entire row
/// (not just the switch) is tappable.
private struct ExtraToggleRow: View {
    let icon: String
    let color: Color
    let title: LocalizedStringKey
    let description: LocalizedStringKey
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(alignment: .center, spacing: 18) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [color, color.opacity(0.7)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 58, height: 58)
                        .shadow(color: color.opacity(0.35), radius: 6, x: 0, y: 3)
                    Image(systemName: icon)
                        .font(.title2)
                        .foregroundStyle(.white)
                        .accessibilityHidden(true)
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
        .tint(color)
    }
}

// MARK: - Preview

#Preview {
    OnboardingExtrasView(onContinue: { _, _, _ in }, onBack: {})
}

#Preview("Dark") {
    OnboardingExtrasView(onContinue: { _, _, _ in }, onBack: {})
        .preferredColorScheme(.dark)
}

#Preview("Landscape", traits: .landscapeLeft) {
    OnboardingExtrasView(onContinue: { _, _, _ in }, onBack: {})
}
