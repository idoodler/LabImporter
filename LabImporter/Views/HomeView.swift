import SwiftUI
import VisionKit

struct HomeView: View {
    // Report state
    @State private var reports: [LabReport] = []
    @State private var isLoaded = false

    // Import flow state
    @State private var importEngine = LabImportEngine()
    @State private var labValues: [LabValue] = []
    @State private var parsedReportDate: Date?
    @State private var parsedPatientName: String?
    @State private var parsedAuthorName: String?
    @State private var showReview = false
    @State private var clipboardHasContent = false
    @AppStorage("hasSeenWelcome") private var hasSeenWelcome = false

    var body: some View {
        NavigationStack {
            content
        }
        // Attach the import overlay outside the NavigationStack so the processing
        // HUD's scrim also covers the navigation bar — otherwise the toolbar
        // buttons (history/settings/import) stay tappable behind the HUD.
        .labImport(engine: importEngine)
        .sheet(isPresented: $showReview) {
            NavigationStack {
                ReviewView(
                    labValues: labValues,
                    reportDate: parsedReportDate ?? Date(),
                    extractedPatientName: parsedPatientName,
                    extractedAuthorName: parsedAuthorName
                )
            }
            .interactiveDismissDisabled()
        }
        .task { await loadReports() }
        .onChange(of: showReview) { _, showing in
            if !showing { Task { await loadReports() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            Task { await loadReports() }
        }
        .onAppear {
            refreshClipboardState()
            configureImportEngine()
        }
        .onOpenURL { url in
            Task { await importEngine.processFile(at: url) }
        }
        .fullScreenCover(isPresented: Binding(
            get: { !hasSeenWelcome },
            set: { _ in }
        )) {
            WelcomeView { hasSeenWelcome = true }
        }
    }

    // MARK: - Content routing

    @ViewBuilder
    private var content: some View {
        if !isLoaded {
            ProgressView()
                .scaleEffect(1.4)
        } else if reports.isEmpty {
            ImportLandingView(
                onScan: { importEngine.scan() },
                onPickFile: { importEngine.pickFile() },
                onPaste: { importEngine.paste() },
                onManual: createManually,
                scannerAvailable: VNDocumentCameraViewController.isSupported,
                clipboardAvailable: clipboardHasContent,
                isProcessing: importEngine.isProcessing
            )
        } else {
            DashboardView(
                reports: reports,
                onScan: { importEngine.scan() },
                onPickFile: { importEngine.pickFile() },
                onPaste: { importEngine.paste() },
                onManual: createManually,
                scannerAvailable: VNDocumentCameraViewController.isSupported,
                clipboardAvailable: clipboardHasContent,
                isProcessing: importEngine.isProcessing
            )
        }
    }

    // MARK: - Import engine

    private func configureImportEngine() {
        importEngine.onParsed = { result in
            labValues = result.values
            parsedReportDate = result.reportDate
            parsedPatientName = result.patientName
            parsedAuthorName = result.authorName
            showReview = true
        }
    }

    // MARK: - Report loading

    private func loadReports() async {
        do {
            reports = try await HealthKitService.shared.loadCDADocuments()
        } catch {
            // Silently ignore authorization errors on first launch before user grants access
        }
        isLoaded = true
    }

    // MARK: - Clipboard

    private func refreshClipboardState() {
        let pasteboard = UIPasteboard.general
        clipboardHasContent = pasteboard.hasImages || pasteboard.hasStrings
    }

    // MARK: - Manual creation

    private func createManually() {
        labValues = []
        parsedReportDate = nil
        parsedPatientName = nil
        parsedAuthorName = nil
        showReview = true
    }
}

#Preview {
    HomeView()
}
