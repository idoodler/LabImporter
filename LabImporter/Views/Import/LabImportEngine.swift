import SwiftUI
import UniformTypeIdentifiers
import VisionKit

/// Page-by-page progress of the OCR stage, shown by the processing HUD while
/// a multi-page scan or PDF is being read.
struct OCRPageProgress: Equatable {
    var page: Int
    var total: Int
}

/// A user-facing import failure whose message is already localized.
private struct ImportFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Shared driver for the "known" import methods — scan, file, paste — that turn a
/// document or clipboard content into parsed `LabValue`s via OCR + the on-device AI.
///
/// Both the home screen (creating a brand-new report) and the review/edit sheet
/// (adding more values to an already-open report) use this. Callers hold one as
/// `@State`, set `onParsed`, attach `.labImport(engine:)`, and call `scan()` /
/// `pickFile()` / `paste()` from their own buttons. The engine owns the scanner,
/// file importer, error alert and processing HUD so each call site stays small.
///
/// Each import runs as a single cancellable task; `cancelImport()` (wired to the
/// HUD's Cancel button) stops it at the next OCR-page or parse-token checkpoint.
@MainActor
@Observable
final class LabImportEngine {
    /// Invoked on the main actor with the parsed result of a successful import.
    /// Home replaces its working set with `result.values`; review appends to it.
    var onParsed: ((ParseResult) -> Void)?

    /// Shown when an import yields no usable values.
    var emptyMessage = String(
        localized: "No lab values were found in this document. Make sure the report is clearly visible."
    )

    private(set) var isProcessing = false

    /// The stage the current import is in, surfaced by the processing HUD.
    private(set) var phase: ImportPhase = .extractingText

    /// Live page progress while OCR runs (multi-page scans and PDFs).
    private(set) var ocrProgress: OCRPageProgress?

    /// Live progress of the streaming AI parse — entries found so far.
    private(set) var parseProgress: ParseProgress?

    /// Incremented after each successful parse; drives the success haptic.
    private(set) var completedImports = 0

    fileprivate var errorMessage: String?
    fileprivate var showScanner = false
    fileprivate var showFileImporter = false

    private let ocrService = OCRService()
    private let parserService = LabParserService()
    private var importTask: Task<Void, Never>?

    // MARK: - Method entry points

    func scan() {
        guard VNDocumentCameraViewController.isSupported else {
            errorMessage = String(localized: "Document scanning isn't available on this device.")
            return
        }
        prewarmParser()
        showScanner = true
    }

    func pickFile() {
        prewarmParser()
        showFileImporter = true
    }

    func paste() {
        let pasteboard = UIPasteboard.general
        if let image = pasteboard.image {
            processImages([image])
        } else if let text = pasteboard.string, !text.isEmpty {
            processText(text)
        }
    }

    /// Stops the in-flight import. The HUD dismisses immediately; the
    /// underlying OCR/parse observes the cancellation at its next checkpoint
    /// (between OCR pages, or between streamed parse snapshots).
    func cancelImport() {
        importTask?.cancel()
        isProcessing = false
    }

    // MARK: - Processing

    func processFile(at url: URL) {
        startImport(phase: .extractingText) { engine in
            try await engine.importFile(at: url)
        }
    }

    func processImages(_ images: [UIImage]) {
        guard !images.isEmpty else { return }
        startImport(phase: .extractingText) { engine in
            let text = try await engine.ocrService.extractText(from: images, onPageProgress: engine.pageProgressHandler)
            try await engine.handleExtractedText(text)
        }
    }

    func processText(_ text: String) {
        startImport(phase: .analyzing) { engine in
            try await engine.handleExtractedText(text)
        }
    }

    /// Runs `operation` as the single current import task, owning the HUD
    /// lifecycle and error reporting. A cancelled import dismisses quietly.
    private func startImport(
        phase: ImportPhase,
        operation: @escaping @MainActor (LabImportEngine) async throws -> Void
    ) {
        importTask?.cancel()
        prewarmParser()
        self.phase = phase
        ocrProgress = nil
        parseProgress = nil
        isProcessing = true
        importTask = Task { [weak self] in
            guard let self else { return }
            defer { isProcessing = false }
            do {
                try await operation(self)
            } catch {
                guard !(error is CancellationError), !Task.isCancelled else { return }
                errorMessage = error.localizedDescription
            }
        }
    }

    private func importFile(at url: URL) async throws {
        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing { url.stopAccessingSecurityScopedResource() }
            // Files handed over via "Copy to App" (share sheet) are duplicated
            // into our Documents/Inbox; once parsed they're dead weight, so we
            // remove them. Files opened in place (the user's own document in
            // Files) live outside our container and are left untouched.
            removeInboxCopy(at: url)
        }

        let isPDF = url.pathExtension.lowercased() == "pdf"
            || (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType?.conforms(to: .pdf)) == true

        if isPDF {
            let text = try await ocrService.extractText(fromPDFAt: url, onPageProgress: pageProgressHandler)
            try await handleExtractedText(text)
        } else if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
            let text = try await ocrService.extractText(from: [image], onPageProgress: pageProgressHandler)
            try await handleExtractedText(text)
        } else {
            throw ImportFailure(message: String(localized: "Could not load the selected file."))
        }
    }

    /// Deletes `url` only if it sits inside this app's `Documents/Inbox`, the
    /// drop box iOS uses when another app copies a document to us. In-place URLs
    /// (security-scoped references to the user's own files) are never deleted.
    private func removeInboxCopy(at url: URL) {
        guard url.isFileURL,
              let inbox = try? FileManager.default.url(
                for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: false
              ).appendingPathComponent("Inbox") else { return }
        guard url.standardizedFileURL.path.hasPrefix(inbox.standardizedFileURL.path) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    private func handleExtractedText(_ text: String) async throws {
        // Text is in hand; the remaining (and longest) work is the AI parse.
        phase = .analyzing
        let result = try await parserService.parseLabValues(from: text) { [weak self] progress in
            Task { @MainActor in self?.parseProgress = progress }
        }
        // A late result after the user cancelled must not pop the review sheet.
        try Task.checkCancellation()
        if result.values.isEmpty {
            errorMessage = emptyMessage
            return
        }
        completedImports += 1
        onParsed?(result)
    }

    /// Forwards OCR page progress onto the main actor for the HUD.
    private var pageProgressHandler: @Sendable (Int, Int) -> Void {
        { [weak self] page, total in
            Task { @MainActor in self?.ocrProgress = OCRPageProgress(page: page, total: total) }
        }
    }

    /// Loads the language model in parallel with scanning/picking/OCR so the
    /// parse phase doesn't pay the model's startup cost on top of its own.
    private func prewarmParser() {
        let parser = parserService
        Task { await parser.prewarm() }
    }

    fileprivate func reportError(_ message: String) {
        errorMessage = message
    }
}

// MARK: - View wiring

private struct LabImportModifier: ViewModifier {
    @Bindable var engine: LabImportEngine

    func body(content: Content) -> some View {
        content
            .fullScreenCover(isPresented: $engine.showScanner) {
                DocumentScannerView(
                    onComplete: { images in
                        engine.processImages(images)
                    },
                    onError: { error in
                        engine.reportError(error.localizedDescription)
                    }
                )
                .ignoresSafeArea()
            }
            .fileImporter(
                isPresented: $engine.showFileImporter,
                allowedContentTypes: [.pdf, .image],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    guard let url = urls.first else { return }
                    engine.processFile(at: url)
                case .failure:
                    engine.reportError(String(localized: "Could not load the selected file."))
                }
            }
            .alert("Error", isPresented: Binding(
                get: { engine.errorMessage != nil },
                set: { if !$0 { engine.errorMessage = nil } }
            )) {
                Button("OK") { engine.errorMessage = nil }
            } message: {
                Text(engine.errorMessage ?? "")
            }
            // Success/failure haptics for the pipeline — completion is
            // otherwise only visible as a sheet or alert appearing.
            .sensoryFeedback(.success, trigger: engine.completedImports)
            .sensoryFeedback(.error, trigger: engine.errorMessage) { _, newValue in newValue != nil }
            // The processing HUD is hosted in its own UIWindow above the key
            // window rather than as a SwiftUI `.overlay`. An overlay composites
            // *inside* the navigation container's SwiftUI layer, but the toolbar
            // buttons live in UIKit's `UINavigationBar`, which sits above that
            // layer — so an overlay scrim can't reliably block them (the buttons
            // still highlight and, worse, cancel taps). A dedicated window is
            // genuinely on top of everything, navigation bar included, and
            // swallows all touches for the duration of the import.
            .background(
                ProcessingHUDPresenter(
                    isProcessing: engine.isProcessing,
                    phase: engine.phase,
                    ocrProgress: engine.ocrProgress,
                    parseProgress: engine.parseProgress,
                    onCancel: { engine.cancelImport() }
                )
            )
    }
}

// MARK: - HUD window presentation

/// Invisible SwiftUI anchor whose coordinator owns a top-level `UIWindow` that
/// hosts the processing HUD. Placed via `.background` so it occupies no layout
/// space; all it does is forward the engine's HUD state into the coordinator,
/// which shows or tears down the window.
private struct ProcessingHUDPresenter: UIViewRepresentable {
    var isProcessing: Bool
    var phase: ImportPhase
    var ocrProgress: OCRPageProgress?
    var parseProgress: ParseProgress?
    var onCancel: () -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.isHidden = true
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.update(from: self, anchor: uiView)
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.tearDownNow()
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    @MainActor
    final class Coordinator {
        private var window: UIWindow?
        private let model = ProcessingHUDModel()
        private var dismissTask: Task<Void, Never>?

        func update(from presenter: ProcessingHUDPresenter, anchor: UIView) {
            model.phase = presenter.phase
            model.ocrProgress = presenter.ocrProgress
            model.parseProgress = presenter.parseProgress
            model.onCancel = presenter.onCancel

            if presenter.isProcessing {
                dismissTask?.cancel()
                dismissTask = nil
                presentIfNeeded(from: anchor)
                model.isActive = true
            } else {
                guard window != nil else { return }
                model.isActive = false
                // Defer teardown so the bubble/scrim can animate out with the
                // same transition they animate in with.
                dismissTask?.cancel()
                dismissTask = Task { [weak self] in
                    try? await Task.sleep(for: .milliseconds(350))
                    guard !Task.isCancelled else { return }
                    self?.tearDownNow()
                }
            }
        }

        func tearDownNow() {
            dismissTask?.cancel()
            dismissTask = nil
            window?.isHidden = true
            window = nil
        }

        private func presentIfNeeded(from anchor: UIView) {
            guard window == nil else { return }
            guard let scene = anchor.window?.windowScene ?? Self.activeWindowScene else { return }

            let host = UIHostingController(rootView: ProcessingHUDHost(model: model))
            host.view.backgroundColor = .clear

            let window = UIWindow(windowScene: scene)
            // Above alerts so nothing in the app — navigation bar included —
            // can sit on top of or receive touches through the HUD.
            window.windowLevel = .alert + 1
            window.backgroundColor = .clear
            window.rootViewController = host
            window.isHidden = false  // shown, but not made key — we don't steal first responder
            self.window = window
        }

        private static var activeWindowScene: UIWindowScene? {
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first { $0.activationState == .foregroundActive }
        }
    }
}

extension View {
    /// Wires up the scanner, file importer, error alert and processing HUD that
    /// back a `LabImportEngine`. Pair with `engine.scan()` / `pickFile()` /
    /// `paste()` from your own controls.
    func labImport(engine: LabImportEngine) -> some View {
        modifier(LabImportModifier(engine: engine))
    }
}

// MARK: - Import phases

/// The coarse stages an import passes through, used to label the processing
/// HUD. Within a stage the HUD shows live detail — OCR page progress and the
/// streamed count of values the on-device model has found so far.
enum ImportPhase: CaseIterable {
    case extractingText
    case analyzing

    var title: LocalizedStringKey {
        switch self {
        case .extractingText: "Reading document…"
        case .analyzing: "Analyzing lab report…"
        }
    }

    var detail: LocalizedStringKey {
        switch self {
        case .extractingText: "Extracting text on device"
        case .analyzing: "Using on-device AI"
        }
    }

    var systemImage: String {
        switch self {
        case .extractingText: "doc.text.viewfinder"
        case .analyzing: "sparkles"
        }
    }
}
