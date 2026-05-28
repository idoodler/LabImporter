import SwiftUI
import UniformTypeIdentifiers
import VisionKit

struct HomeView: View {
    // Report state
    @State private var reports: [LabReport] = []
    @State private var isLoaded = false

    // Import flow state
    @State private var showScanner = false
    @State private var showFileImporter = false
    @State private var isProcessing = false
    @State private var labValues: [LabValue] = []
    @State private var parsedReportDate: Date?
    @State private var parsedPatientName: String?
    @State private var parsedAuthorName: String?
    @State private var showReview = false
    @State private var errorMessage: String?
    @State private var clipboardHasContent = false
    @AppStorage("hasSeenWelcome") private var hasSeenWelcome = false

    private let ocrService = OCRService()
    private let parserService = LabParserService()

    var body: some View {
        NavigationStack {
            content
                .fullScreenCover(isPresented: $showScanner) {
                    DocumentScannerView(
                        onComplete: { images in
                            Task { await processImages(images) }
                        },
                        onError: { error in
                            errorMessage = error.localizedDescription
                        }
                    )
                    .ignoresSafeArea()
                }
                .fileImporter(
                    isPresented: $showFileImporter,
                    allowedContentTypes: [.pdf, .image],
                    allowsMultipleSelection: false
                ) { result in
                    switch result {
                    case .success(let urls):
                        guard let url = urls.first else { return }
                        Task { await processFile(at: url) }
                    case .failure:
                        errorMessage = String(localized: "Could not load the selected file.")
                    }
                }
                .alert("Error", isPresented: .constant(errorMessage != nil)) {
                    Button("OK") { errorMessage = nil }
                } message: {
                    Text(errorMessage ?? "")
                }
        }
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
        .overlay {
            if isProcessing { ProcessingHUD() }
        }
        .task { await loadReports() }
        .onChange(of: showReview) { _, showing in
            if !showing { Task { await loadReports() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            Task { await loadReports() }
        }
        .onAppear { refreshClipboardState() }
        .onOpenURL { url in
            Task { await processSharedURL(url) }
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
                onScan: openScanner,
                onPickFile: { showFileImporter = true },
                onPaste: pasteFromClipboard,
                onManual: createManually,
                scannerAvailable: VNDocumentCameraViewController.isSupported,
                clipboardAvailable: clipboardHasContent,
                isProcessing: isProcessing
            )
        } else {
            DashboardView(
                reports: reports,
                onScan: openScanner,
                onPickFile: { showFileImporter = true },
                onPaste: pasteFromClipboard,
                onManual: createManually,
                scannerAvailable: VNDocumentCameraViewController.isSupported,
                clipboardAvailable: clipboardHasContent,
                isProcessing: isProcessing
            )
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

    private func pasteFromClipboard() {
        let pasteboard = UIPasteboard.general
        if let image = pasteboard.image {
            Task { await processImages([image]) }
        } else if let text = pasteboard.string, !text.isEmpty {
            Task { await processText(text) }
        }
    }

    // MARK: - Scanner

    private func openScanner() {
        guard VNDocumentCameraViewController.isSupported else {
            errorMessage = String(localized: "Document scanning isn't available on this device.")
            return
        }
        showScanner = true
    }

    // MARK: - Manual creation

    private func createManually() {
        labValues = []
        parsedReportDate = nil
        parsedPatientName = nil
        parsedAuthorName = nil
        showReview = true
    }

    // MARK: - Shared URL (share sheet / open with)

    private func processSharedURL(_ url: URL) async {
        await processFile(at: url)
    }

    private func processFile(at url: URL) async {
        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing { url.stopAccessingSecurityScopedResource() }
        }

        let isPDF = url.pathExtension.lowercased() == "pdf"
            || (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType?.conforms(to: .pdf)) == true

        if isPDF {
            await processPDF(at: url)
        } else if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
            await processImages([image])
        } else {
            errorMessage = String(localized: "Could not load the selected file.")
        }
    }

    // MARK: - Processing

    private func processPDF(at url: URL) async {
        isProcessing = true
        defer { isProcessing = false }

        do {
            let text = try await ocrService.extractText(fromPDFAt: url)
            try await handleExtractedText(text, emptyMessage: documentEmptyMessage)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func processImages(_ images: [UIImage]) async {
        guard !images.isEmpty else { return }
        isProcessing = true
        defer { isProcessing = false }

        do {
            let text = try await ocrService.extractText(from: images)
            try await handleExtractedText(text, emptyMessage: documentEmptyMessage)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private var documentEmptyMessage: String {
        String(localized: "No lab values were found in this document. Make sure the report is clearly visible.")
    }

    private func processText(_ text: String) async {
        isProcessing = true
        defer { isProcessing = false }

        do {
            try await handleExtractedText(text, emptyMessage: String(localized: "No lab values were found in the clipboard text."))
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func handleExtractedText(_ text: String, emptyMessage: String) async throws {
        let result = try await parserService.parseLabValues(from: text)

        if result.values.isEmpty {
            errorMessage = emptyMessage
            return
        }

        labValues = result.values
        parsedReportDate = result.reportDate
        parsedPatientName = result.patientName
        parsedAuthorName = result.authorName
        showReview = true
    }
}

// MARK: - Processing HUD

private struct ProcessingHUD: View {
    var body: some View {
        Color.clear
            .ignoresSafeArea()
            .overlay {
                VStack(spacing: 16) {
                    ProgressView()
                        .controlSize(.large)
                    Text("Analyzing lab report…")
                        .font(.headline)
                    Text("Using on-device AI")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(32)
                .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 24))
            }
            .transition(.opacity.animation(.easeInOut(duration: 0.2)))
    }
}

#Preview {
    HomeView()
}
