import HealthKit
import SwiftUI
import UIKit

struct HealthPermissionView: View {
    let onGranted: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false
    @State private var isRequesting = false
    @State private var showDeniedAlert = false

    private var benefits: [Benefit] {
        [
            Benefit(
                icon: "square.and.arrow.down.on.square.fill",
                color: LabCategory.cardiac.color,
                title: "Save Reports to Health",
                description: "Imported lab values are written as clinical CDA records into Apple Health."
            ),
            Benefit(
                icon: "doc.text.magnifyingglass",
                color: LabCategory.endocrine.color,
                title: "Read Your History",
                description: "Past reports are loaded back from Apple Health so you can review and share them."
            ),
            Benefit(
                icon: "person.text.rectangle.fill",
                color: LabCategory.hepatic.color,
                title: "Personal Context",
                description: "Date of birth and biological sex help label your reports and contextualize values."
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
            continueButton
        }
        .background { MorphingCategoryBackground() }
        .onAppear {
            guard !reduceMotion else { appeared = true; return }
            withAnimation(.smooth(duration: 0.7)) { appeared = true }
        }
        .alert("Apple Health Access Required", isPresented: $showDeniedAlert) {
            Button("Open Settings") { openSettings() }
            Button("Try Again") { Task { await requestAccess() } }
        } message: {
            Text("LabImporter needs full Apple Health access. Open Settings, then tap Apple Health to turn on every category for LabImporter.")
        }
    }

    // MARK: - Hero

    private var hero: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle()
                    .fill(
                        RadialGradient(
                            colors: [Color.pink.opacity(0.45), Color.pink.opacity(0)],
                            center: .center,
                            startRadius: 4,
                            endRadius: 90
                        )
                    )
                    .frame(width: 180, height: 180)
                Image(systemName: "heart.text.square.fill")
                    .font(.system(size: 96, weight: .semibold))
                    .symbolRenderingMode(.palette)
                    .foregroundStyle(.white, Color.pink.gradient)
                    .shadow(color: .pink.opacity(0.35), radius: 18, x: 0, y: 8)
            }
            VStack(spacing: 6) {
                Text("Connect to Apple Health")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                    .lineLimit(nil)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
                Text("LabImporter stores everything in Apple Health — so it needs your permission first.")
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
            Text("Everything runs on-device. LabImporter never transmits your lab data.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .opacity(appeared ? 1 : 0)
    }

    // MARK: - Button

    private var continueButton: some View {
        Button {
            Task { await requestAccess() }
        } label: {
            HStack(spacing: 10) {
                if isRequesting {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                }
                Text(isRequesting ? "Requesting Access…" : "Allow Apple Health Access")
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(isRequesting)
        .padding(.horizontal, 24)
        .padding(.bottom, 48)
        .opacity(appeared ? 1 : 0)
    }

    // MARK: - Actions

    private func requestAccess() async {
        isRequesting = true
        defer { isRequesting = false }
        do {
            let granted = try await HealthKitService.shared.requestInitialAuthorization()
            if granted {
                onGranted()
            } else {
                showDeniedAlert = true
            }
        } catch {
            showDeniedAlert = true
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
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
    HealthPermissionView { }
}
