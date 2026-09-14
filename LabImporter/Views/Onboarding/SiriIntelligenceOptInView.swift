import SwiftUI

/// Onboarding step that introduces Siri & Shortcuts support (App Intents —
/// SiriKit's replacement) and asks, up front, whether to turn it on, which
/// values it may read back, and which capabilities to allow. Mirrors
/// `SpotlightOptInView`: the decision is recorded via `onDecision`, which the
/// host persists and uses to clear the onboarding gate. The capability
/// toggles below map straight onto `SiriExposurePreferences`' flags —
/// mirroring `SiriAccessEditor` so the choice made here isn't a black box.
/// Picking "Selected Values" here doesn't list any metric yet — no report has
/// been imported at this point in onboarding — it just opts into the
/// per-metric allow-list, populated later in Settings; nothing is exposed
/// until then.
struct SiriIntelligenceOptInView: View {
    /// Called with the user's choices: the master switch, the value-access
    /// mode, then the capability flags (scan shortcut, on-screen awareness,
    /// knowledge indexing, in-app search) mirrored from the toggles on
    /// screen. The host writes them into `SiriExposurePreferences` and
    /// dismisses the gate.
    let onDecision: (
        _ enabled: Bool,
        _ allowAllValues: Bool,
        _ allowScanShortcut: Bool,
        _ allowOnScreenAwareness: Bool,
        _ allowKnowledgeIndexing: Bool,
        _ allowInAppSearch: Bool
    ) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false
    @State private var allowAllValues = false
    @State private var allowScanShortcut = true
    @State private var allowOnScreenAwareness = false
    @State private var allowKnowledgeIndexing = false
    @State private var allowInAppSearch = true

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
                // Color first, white second: unlike the other onboarding heroes'
                // icons, "waveform.and.mic" renders its dominant mic-body layer
                // in the *first* palette color — putting `.white` there left a
                // mostly-white glyph sitting on a soft, mostly-transparent glow,
                // unreadable against a light-mode background. Purple as the
                // primary layer keeps it legible in both themes.
                Image(systemName: "waveform.and.mic")
                    .font(.system(size: 84, weight: .semibold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(Color.purple.gradient, .white)
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
            BenefitRow(benefit: askBenefit)
                .onboardingRow(appeared: appeared, delay: 0.15, reduceMotion: reduceMotion)

            ValueAccessPicker(allowAllValues: $allowAllValues)
                .onboardingRow(appeared: appeared, delay: 0.19, reduceMotion: reduceMotion)

            CapabilityToggleRow(
                icon: "doc.viewfinder",
                color: LabCategory.bloodGas.color,
                title: "Start a Scan Hands-Free",
                description: "“Scan a lab report in LabImporter” opens the scanner instantly — no health data involved.",
                isOn: $allowScanShortcut
            )
            .onboardingRow(appeared: appeared, delay: 0.23, reduceMotion: reduceMotion)

            CapabilityToggleRow(
                icon: "eye",
                color: .teal,
                title: "On-Screen Awareness",
                description: "While reviewing a report, let Siri see the values shown on screen — useful for hands-free corrections.",
                isOn: $allowOnScreenAwareness
            )
            .onboardingRow(appeared: appeared, delay: 0.31, reduceMotion: reduceMotion)

            CapabilityToggleRow(
                icon: "sparkles",
                color: .orange,
                title: "Add to Siri Suggestions",
                description: "Let Siri suggest values you've allowed — names only, never a reading.",
                isOn: $allowKnowledgeIndexing
            )
            .onboardingRow(appeared: appeared, delay: 0.39, reduceMotion: reduceMotion)

            CapabilityToggleRow(
                icon: "magnifyingglass",
                color: .green,
                title: "Search Your Values",
                description: "Let Siri or Spotlight's search jump straight to a tracked value's trend.",
                isOn: $allowInAppSearch
            )
            .onboardingRow(appeared: appeared, delay: 0.47, reduceMotion: reduceMotion)
        }
        .padding(24)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 28))
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(Color.primary.opacity(0.08), lineWidth: 0.5)
        )
    }

    private var askBenefit: Benefit {
        Benefit(
            icon: "waveform.path.ecg",
            color: LabCategory.cardiac.color,
            title: "Ask Siri Anytime",
            description: """
            Say “Ask LabImporter about my cholesterol” and Siri reads back your latest \
            reading — but only for values you allow.
            """
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
                onDecision(true, allowAllValues, allowScanShortcut, allowOnScreenAwareness, allowKnowledgeIndexing, allowInAppSearch)
            } label: {
                Text("Enable Siri & Shortcuts")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Button {
                onDecision(false, allowAllValues, allowScanShortcut, allowOnScreenAwareness, allowKnowledgeIndexing, allowInAppSearch)
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

// MARK: - Row entrance animation

private extension View {
    /// Shared fade/slide-in used by every row in the benefit card.
    func onboardingRow(appeared: Bool, delay: Double, reduceMotion: Bool) -> some View {
        opacity(appeared ? 1 : 0)
            .offset(y: appeared ? 0 : 20)
            .animation(reduceMotion ? nil : .smooth(duration: 0.6).delay(delay), value: appeared)
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

// MARK: - Capability toggle row

/// Same visual language as `BenefitRow`, but interactive — this is the row
/// type that actually asks the user what to share, one capability at a time.
private struct CapabilityToggleRow: View {
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

// MARK: - Value access picker

/// The choice behind `SiriExposurePreferences.allowAllValues`: read back
/// everything tracked (now and later), or start from nothing and pick values
/// individually in Settings. Same icon+title+description language as
/// `CapabilityToggleRow`, but a segmented choice instead of a toggle since the
/// two options are mutually exclusive.
private struct ValueAccessPicker: View {
    @Binding var allowAllValues: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 18) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [.pink, .pink.opacity(0.7)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 58, height: 58)
                        .shadow(color: .pink.opacity(0.35), radius: 6, x: 0, y: 3)
                    Image(systemName: "checkmark.shield")
                        .font(.title2)
                        .foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text("Value Access")
                        .font(.body.bold())
                    Text(allowAllValues
                         ? "Siri may read back every value you track — including any you add later."
                         : "Choose exactly which values Siri may read back.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Picker("Value Access", selection: $allowAllValues) {
                Text("All Values").tag(true)
                Text("Selected Values").tag(false)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }
}

// MARK: - Preview

#Preview("Light") {
    SiriIntelligenceOptInView { _, _, _, _, _, _ in }
}

#Preview("Dark") {
    SiriIntelligenceOptInView { _, _, _, _, _, _ in }
        .preferredColorScheme(.dark)
}

#Preview("Landscape") {
    SiriIntelligenceOptInView { _, _, _, _, _, _ in }
        .previewInterfaceOrientation(.landscapeLeft)
}
