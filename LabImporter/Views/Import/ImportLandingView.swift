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
    /// Opens Settings through the host's shared navigation state (so it
    /// survives a layout swap). `nil` falls back to a local sheet, e.g. in
    /// previews.
    var onShowSettings: (() -> Void)?
    /// Reports exist that can't be listed: this isn't a first run, so they replace the welcome.
    var status = ReportLoadStatus()

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var showSettings = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // Scrolls once large text no longer fits, and fills the screen until then.
        GeometryReader { proxy in
            ScrollView {
                content
                    .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        // Painted as this view's own content background — exactly like the
        // Dashboard's CategoryBackground — so it reliably fills the screen
        // (iPhone) or the detail pane (iPad).
        .background { MorphingCategoryBackground() }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        // On the iPad sidebar detail (`showsLibraryToolbarItems == false`)
        // there's nothing to put in this bar — the sidebar already exposes
        // Settings — so hide it outright instead of showing an empty inline
        // bar above the hero.
        .toolbar(showsLibraryToolbarItems ? .visible : .hidden, for: .navigationBar)
        .toolbar {
            if showsLibraryToolbarItems {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        if let onShowSettings { onShowSettings() } else { showSettings = true }
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

    private var content: some View {
        VStack(spacing: 0) {
            Spacer()
            if status.hasNotice {
                ReportStatusCards(status: status, onTryAgain: {})
                    .frame(maxWidth: 480)
                    .padding(.horizontal, 24)
            } else {
                heroSection
            }
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
                    .decorativeIcon(size: 52)
                    .foregroundStyle(.white)
            }

            VStack(spacing: 8) {
                Text("Lab Importer")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.primary)
                Text("Scan or import your lab report and\nsave it directly to Apple Health.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.textSecondary)
            }
        }
    }

    // MARK: - Import Card

    private var importCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("IMPORT REPORT")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.textSecondary)
                .kerning(1)

            Button(action: onScan) {
                Label("Scan Document", systemImage: "doc.viewfinder")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.prominentFill)
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
        // A capsule around three lines of text is nearly a circle.
        .buttonBorderShape(dynamicTypeSize.isAccessibilitySize ? .roundedRectangle(radius: 20) : .automatic)
        // At accessibility sizes the icons took the width "Document" needs, and it broke into "Docu-ment".
        .modifier(TitleOnlyAtAccessibilitySizes())
        .padding(dynamicTypeSize.isAccessibilitySize ? 12 : 24)
        .cardSurface(cornerRadius: 28)
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

#Preview("Largest Text") {
    NavigationStack {
        ImportLandingView(
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: true,
            isProcessing: false
        )
    }
    .dynamicTypeSize(.accessibility5)
}

#Preview("Reports Need an Update") {
    NavigationStack {
        ImportLandingView(
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: true,
            isProcessing: false,
            status: ReportLoadStatus(newerVersion: 2)
        )
    }
}

#Preview("Reports Need an Update · Dark, Damaged Too") {
    NavigationStack {
        ImportLandingView(
            onScan: {}, onPickFile: {}, onPaste: {}, onManual: {},
            scannerAvailable: true,
            clipboardAvailable: true,
            isProcessing: false,
            status: ReportLoadStatus(newerVersion: 1, unreadable: 1)
        )
    }
    .preferredColorScheme(.dark)
}

/// The labels' titles alone where the text is too large for title and icon on a line.
/// Elsewhere the system's own style, which VoiceOver reads as one button.
private struct TitleOnlyAtAccessibilitySizes: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            content.labelStyle(.titleOnly)
        } else {
            content
        }
    }
}
