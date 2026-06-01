import SwiftUI
import FoundationModels

/// Determines whether the current device can run LabImporter's on-device AI parsing.
enum DeviceSupport {
    /// LabImporter relies on Apple Intelligence (Foundation Models) for lab value
    /// extraction. The hardware is only truly unsupported when the system model
    /// reports the device itself as ineligible — other unavailable reasons
    /// (Apple Intelligence switched off, model still downloading) are recoverable
    /// on supported hardware, so we don't block the app for those.
    static var isSupported: Bool {
        switch SystemLanguageModel.default.availability {
        case .available:
            return true
        case .unavailable(.deviceNotEligible):
            return false
        case .unavailable:
            return true
        }
    }
}

struct UnsupportedDeviceView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    private var requirements: [Requirement] {
        [
            Requirement(
                icon: "cpu",
                color: LabCategory.bloodGas.color,
                title: "Apple Intelligence Device",
                description: "An iPhone with A17 Pro or an iPad/Mac with Apple silicon (M1 or later) is required."
            ),
            Requirement(
                icon: "arrow.up.circle",
                color: LabCategory.hepatic.color,
                title: "Latest OS",
                description: "Make sure your device is running iOS or iPadOS 26 or later."
            )
        ]
    }

    var body: some View {
        OnboardingScaffold {
            hero
        } card: {
            requirementCard
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
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 88, weight: .semibold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.orange.gradient)
                    .shadow(color: .orange.opacity(0.35), radius: 18, x: 0, y: 8)
            }
            VStack(spacing: 6) {
                Text("Device Not Supported")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Text("LabImporter needs Apple Intelligence to read your lab reports privately on-device.")
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

    // MARK: - Requirement card

    private var requirementCard: some View {
        VStack(alignment: .leading, spacing: 22) {
            ForEach(Array(requirements.enumerated()), id: \.offset) { index, requirement in
                RequirementRow(requirement: requirement)
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
}

// MARK: - Requirement model & row

private struct Requirement {
    let icon: String
    let color: Color
    let title: LocalizedStringKey
    let description: LocalizedStringKey
}

private struct RequirementRow: View {
    let requirement: Requirement

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [requirement.color, requirement.color.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 58, height: 58)
                    .shadow(color: requirement.color.opacity(0.35), radius: 6, x: 0, y: 3)
                Image(systemName: requirement.icon)
                    .font(.title2)
                    .foregroundStyle(.white)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(requirement.title)
                    .font(.body.bold())
                Text(requirement.description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Preview

#Preview {
    UnsupportedDeviceView()
}
