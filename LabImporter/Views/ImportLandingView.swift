import SwiftUI

struct ImportLandingView: View {
    let onScan: () -> Void
    let onPickFile: () -> Void
    let onPaste: () -> Void
    let onManual: () -> Void
    let scannerAvailable: Bool
    let clipboardAvailable: Bool
    let isProcessing: Bool

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            heroSection
            Spacer()
            importCard
                // Keep the card from stretching across an iPad's width — it stays
                // a centered, readable column on large screens.
                .frame(maxWidth: 480)
                .padding(.horizontal, 24)
                .opacity(isProcessing ? 0.4 : 1)
                .allowsHitTesting(!isProcessing)
            Spacer()
                .frame(height: 56)
        }
        // Painted as this column's own background (which reliably renders, unlike
        // one placed behind the split view). `seamless` aligns it to the sidebar's
        // matching wash so the two columns read as one continuous full-window field.
        .background { MorphingCategoryBackground(seamless: true) }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Hero

    private var heroSection: some View {
        VStack(spacing: 20) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [LabCategory.bloodGas.color, LabCategory.endocrine.color],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 110, height: 110)
                    .shadow(color: LabCategory.endocrine.color.opacity(0.35), radius: 24, y: 10)
                Image(systemName: "waveform.path.ecg.rectangle.fill")
                    .font(.system(size: 52))
                    .foregroundStyle(.white)
            }

            VStack(spacing: 8) {
                Text("Lab Importer")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.primary)
                Text("Scan or import your lab report and\nsave it directly to Apple Health.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Import Card

    private var importCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("IMPORT REPORT")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .kerning(1)

            Button(action: onScan) {
                Label("Scan Document", systemImage: "doc.viewfinder")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!scannerAvailable)

            Button(action: onPickFile) {
                Label("Choose File", systemImage: "folder")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)

            Button(action: onPaste) {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(!clipboardAvailable)

            Divider()
                .padding(.vertical, 2)

            Button(action: onManual) {
                Label("Create Report Manually", systemImage: "square.and.pencil")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
        .padding(24)
        // Glass (rather than plain material) so the card matches the floating
        // progress card it morphs into during import — see `LabImportEngine`.
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 28))
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }
}
