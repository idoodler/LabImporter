import SwiftUI
import PhotosUI

struct HomeView: View {
    // Report state
    @State private var reports: [LabReport] = []
    @State private var isLoaded = false

    // Import flow state
    @State private var photosPickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var isProcessing = false
    @State private var labValues: [LabValue] = []
    @State private var parsedReportDate: Date?
    @State private var parsedPatientName: String?
    @State private var parsedAuthorName: String?
    @State private var showReview = false
    @State private var errorMessage: String?
    @State private var clipboardHasContent = false

    private let ocrService = OCRService()
    private let parserService = LabParserService()

    var body: some View {
        NavigationStack {
            content
                .sheet(isPresented: $showCamera) {
                    CameraView { image in
                        Task { await processImage(image) }
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
        .onChange(of: photosPickerItem) { _, item in
            guard let item else { return }
            photosPickerItem = nil
            Task { await loadAndProcess(item: item) }
        }
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
    }

    // MARK: - Content routing

    @ViewBuilder
    private var content: some View {
        if !isLoaded {
            ProgressView()
                .scaleEffect(1.4)
        } else if reports.isEmpty {
            ImportLandingView(
                photosPickerItem: $photosPickerItem,
                onCamera: { showCamera = true },
                onPaste: pasteFromClipboard,
                onManual: createManually,
                clipboardAvailable: clipboardHasContent,
                isProcessing: isProcessing
            )
        } else {
            DashboardView(
                reports: reports,
                photosPickerItem: $photosPickerItem,
                onCamera: { showCamera = true },
                onPaste: pasteFromClipboard,
                onManual: createManually,
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
            Task { await processImage(image) }
        } else if let text = pasteboard.string, !text.isEmpty {
            Task { await processText(text) }
        }
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
        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing { url.stopAccessingSecurityScopedResource() }
        }
        guard let data = try? Data(contentsOf: url),
              let image = UIImage(data: data) else {
            errorMessage = String(localized: "Could not open the shared image.")
            return
        }
        await processImage(image)
    }

    // MARK: - Processing

    private func loadAndProcess(item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data)
        else {
            errorMessage = String(localized: "Could not load the selected image.")
            return
        }
        await processImage(image)
    }

    private func processImage(_ image: UIImage) async {
        isProcessing = true
        defer { isProcessing = false }

        do {
            let text = try await ocrService.extractText(from: image)
            let result = try await parserService.parseLabValues(from: text)

            if result.values.isEmpty {
                errorMessage = String(localized: "No lab values were found in this image. Make sure the report is clearly visible.")
                return
            }

            labValues = result.values
            parsedReportDate = result.reportDate
            parsedPatientName = result.patientName
            parsedAuthorName = result.authorName
            showReview = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func processText(_ text: String) async {
        isProcessing = true
        defer { isProcessing = false }

        do {
            let result = try await parserService.parseLabValues(from: text)

            if result.values.isEmpty {
                errorMessage = String(localized: "No lab values were found in the clipboard text.")
                return
            }

            labValues = result.values
            parsedReportDate = result.reportDate
            parsedPatientName = result.patientName
            parsedAuthorName = result.authorName
            showReview = true
        } catch {
            errorMessage = error.localizedDescription
        }
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
