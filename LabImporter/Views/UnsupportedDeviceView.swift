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
    var body: some View {
        OnboardingScaffold {
            VStack(spacing: 20) {
                ZStack {
                    Circle()
                        .fill(Color.orange.opacity(0.13))
                        .frame(width: 110, height: 110)
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(.orange)
                }

                VStack(spacing: 8) {
                    Text("Device Not Supported")
                        .font(.largeTitle.bold())
                        .multilineTextAlignment(.center)
                    Text("LabImporter needs Apple Intelligence to read your lab reports privately on-device.")
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, 32)
            }
        } card: {
            VStack(alignment: .leading, spacing: 24) {
                RequirementRow(
                    icon: "cpu",
                    color: .blue,
                    title: "Apple Intelligence Device",
                    description: "An iPhone with A17 Pro or an iPad/Mac with Apple silicon (M1 or later) is required."
                )
                RequirementRow(
                    icon: "arrow.up.circle",
                    color: .green,
                    title: "Latest OS",
                    description: "Make sure your device is running iOS or iPadOS 26 or later."
                )
            }
        }
    }
}

// MARK: - Requirement row

private struct RequirementRow: View {
    let icon: String
    let color: Color
    let title: LocalizedStringKey
    let description: LocalizedStringKey

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
    UnsupportedDeviceView()
}
