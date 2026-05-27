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
    @State private var showReview = false
    @State private var errorMessage: String?
    @State private var clipboardHasContent = false

    private let ocrService = OCRService()
    private let parserService = LabParserService()

    var body: some View {
        NavigationStack {
            content
                .navigationDestination(isPresented: $showReview) {
                    ReviewView(labValues: labValues, reportDate: parsedReportDate ?? Date())
                }
                .sheet(isPresented: $showCamera) {
                    CameraView { image in
                        Task { await processImage(image) }
                    }
                }
                .overlay {
                    if isProcessing { ProcessingView() }
                }
                .alert("Error", isPresented: .constant(errorMessage != nil)) {
                    Button("OK") { errorMessage = nil }
                } message: {
                    Text(errorMessage ?? "")
                }
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
                clipboardAvailable: clipboardHasContent
            )
        } else {
            DashboardView(
                reports: reports,
                photosPickerItem: $photosPickerItem,
                onCamera: { showCamera = true },
                onPaste: pasteFromClipboard,
                clipboardAvailable: clipboardHasContent
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

    // MARK: - Processing

    private func loadAndProcess(item: PhotosPickerItem) async {
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data)
        else {
            errorMessage = "Could not load the selected image."
            return
        }
        await processImage(image)
    }

    private func processImage(_ image: UIImage) async {
        isProcessing = true
        defer { isProcessing = false }

        do {
            let text = try await ocrService.extractText(from: image)
            let (values, parsedDate) = try await parserService.parseLabValues(from: text)

            if values.isEmpty {
                errorMessage = "No lab values were found in this image. Make sure the report is clearly visible."
                return
            }

            labValues = values
            parsedReportDate = parsedDate
            showReview = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func processText(_ text: String) async {
        isProcessing = true
        defer { isProcessing = false }

        do {
            let (values, parsedDate) = try await parserService.parseLabValues(from: text)

            if values.isEmpty {
                errorMessage = "No lab values were found in the clipboard text."
                return
            }

            labValues = values
            parsedReportDate = parsedDate
            showReview = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

#Preview {
    HomeView()
}
