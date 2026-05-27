import SwiftUI
import PhotosUI

struct HomeView: View {
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
            VStack(spacing: 0) {
                Spacer()
                heroSection
                Spacer()
                actionButtons
            }
            .padding()
            .navigationTitle("Lab Importer")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { refreshClipboardState() }
            .onChange(of: photosPickerItem) { _, item in
                guard let item else { return }
                Task { await loadAndProcess(item: item) }
            }
            .sheet(isPresented: $showCamera) {
                CameraView { image in
                    Task { await processImage(image) }
                }
            }
            .navigationDestination(isPresented: $showReview) {
                ReviewView(labValues: labValues, reportDate: parsedReportDate ?? Date())
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
    }

    // MARK: - Sections

    private var heroSection: some View {
        VStack(spacing: 24) {
            ZStack {
                Circle()
                    .fill(.blue.opacity(0.1))
                    .frame(width: 120, height: 120)
                Image(systemName: "waveform.path.ecg.rectangle.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(.blue)
            }

            VStack(spacing: 8) {
                Text("Lab Report Importer")
                    .font(.largeTitle.bold())

                Text("Photograph your lab report and import\nthe values directly into Apple Health\nusing on-device AI.")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var actionButtons: some View {
        VStack(spacing: 12) {
            PhotosPicker(selection: $photosPickerItem, matching: .images) {
                Label("Choose from Photos", systemImage: "photo.on.rectangle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .tint(.blue)

            Button {
                showCamera = true
            } label: {
                Label("Take a Photo", systemImage: "camera")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(!UIImagePickerController.isSourceTypeAvailable(.camera))

            Button {
                pasteFromClipboard()
            } label: {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(!clipboardHasContent)
        }
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
