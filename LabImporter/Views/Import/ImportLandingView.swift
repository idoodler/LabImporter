import SwiftUI

struct ImportLandingView: View {
    let onScan: () -> Void
    let onPickFile: () -> Void
    let onPaste: () -> Void
    let onManual: () -> Void
    let scannerAvailable: Bool
    let clipboardAvailable: Bool
    let isProcessing: Bool
    /// When the landing view is hosted as the detail pane of the iPad sidebar,
    /// the sidebar already exposes Settings so this view hides its own toolbar
    /// button to avoid duplicating it. Defaults to `true` so the standalone
    /// (iPhone) presentation still reaches Settings before any reports exist.
    var showsLibraryToolbarItems = true

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var showSettings = false

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
        // Fill the whole pane so the background covers it. A VStack otherwise
        // hugs its content width (~480pt here), which on iPad left the wash as a
        // narrow centered strip — the Dashboard avoids this only because its
        // background sits on a greedy ScrollView.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Painted as this view's own content background — exactly like the
        // Dashboard's CategoryBackground — so it reliably fills the screen
        // (iPhone) or the detail pane (iPad).
        .background { MorphingCategoryBackground() }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if showsLibraryToolbarItems {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel("Settings")
                }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView(prefs: $prefs, allCodes: [])
        }
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
        // Glass (rather than plain material) so the card matches the glass
        // chrome of the import flow's processing HUD — see `ProcessingHUD`.
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 28))
        .overlay(
            RoundedRectangle(cornerRadius: 28)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ImportLandingView(
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: true,
            isProcessing: false
        )
    }
}
