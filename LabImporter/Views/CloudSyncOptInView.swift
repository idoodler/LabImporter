import SwiftUI

/// Onboarding step that forces an explicit, up-front decision about iCloud sync
/// before the user can start adding entries. The choice is persisted in
/// `iCloudSyncEnabled`; either button records that a decision was made via
/// `onDecision`, which the host uses to clear the gate.
struct CloudSyncOptInView: View {
    /// Called with the user's choice (`true` = enable sync). The host persists
    /// the flag and dismisses the gate.
    let onDecision: (Bool) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    private var benefits: [Benefit] {
        [
            Benefit(
                icon: "square.grid.2x2.fill",
                color: LabCategory.endocrine.color,
                title: "Your Dashboard, Everywhere",
                description: "The order you arrange cards in — plus what you pin and hide — follows you to your other devices."
            ),
            Benefit(
                icon: "icloud.fill",
                color: LabCategory.cardiac.color,
                title: "Layout Only",
                description: "Only your card layout syncs. Your lab values stay in Apple Health and never leave it."
            ),
            Benefit(
                icon: "slider.horizontal.3",
                color: LabCategory.hepatic.color,
                title: "Change It Anytime",
                description: "You can turn iCloud sync on or off later in Settings."
            )
        ]
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            hero
            Spacer()
            benefitCard
                .frame(maxWidth: 480)
                .padding(.horizontal, 24)
            Spacer()
            privacyNote
                .frame(maxWidth: 480)
                .padding(.horizontal, 32)
                .padding(.bottom, 16)
            buttons
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
                            colors: [Color.blue.opacity(0.45), Color.blue.opacity(0)],
                            center: .center,
                            startRadius: 4,
                            endRadius: 90
                        )
                    )
                    .frame(width: 180, height: 180)
                Image(systemName: "arrow.triangle.2.circlepath.icloud.fill")
                    .font(.system(size: 90, weight: .semibold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.blue.gradient)
                    .shadow(color: .blue.opacity(0.35), radius: 18, x: 0, y: 8)
            }
            VStack(spacing: 6) {
                Text("Sync Your Dashboard?")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Keep your dashboard layout in sync across your devices with iCloud.")
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
            Text("Synced through your private iCloud account. Your lab data never leaves Apple Health.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .opacity(appeared ? 1 : 0)
    }

    // MARK: - Buttons

    private var buttons: some View {
        VStack(spacing: 12) {
            Button {
                onDecision(true)
            } label: {
                Text("Enable iCloud Sync")
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
        .padding(.horizontal, 24)
        .padding(.bottom, 48)
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

#Preview {
    CloudSyncOptInView { _ in }
}
