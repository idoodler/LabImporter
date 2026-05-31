import SwiftUI
import UniformTypeIdentifiers
import VisionKit

/// Shared driver for the "known" import methods — scan, file, paste — that turn a
/// document or clipboard content into parsed `LabValue`s via OCR + the on-device AI.
///
/// Both the home screen (creating a brand-new report) and the review/edit sheet
/// (adding more values to an already-open report) use this. Callers hold one as
/// `@State`, set `onParsed`, attach `.labImport(engine:)`, and call `scan()` /
/// `pickFile()` / `paste()` from their own buttons. The engine owns the scanner,
/// file importer, error alert and processing HUD so each call site stays small.
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

    /// The stage the current import is in, surfaced by the processing HUD as a
    /// description + coarse progress. There is no fine-grained progress to report
    /// (OCR and the on-device model are opaque), so this advances by phase.
    private(set) var phase: ImportPhase = .extractingText

    fileprivate var errorMessage: String?
    fileprivate var showScanner = false
    fileprivate var showFileImporter = false

    private let ocrService = OCRService()
    private let parserService = LabParserService()

    // MARK: - Method entry points

    func scan() {
        guard VNDocumentCameraViewController.isSupported else {
            errorMessage = String(localized: "Document scanning isn't available on this device.")
            return
        }
        showScanner = true
    }

    func pickFile() {
        showFileImporter = true
    }

    func paste() {
        let pasteboard = UIPasteboard.general
        if let image = pasteboard.image {
            Task { await processImages([image]) }
        } else if let text = pasteboard.string, !text.isEmpty {
            Task { await processText(text) }
        }
    }

    // MARK: - Processing

    func processFile(at url: URL) async {
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
            await processPDF(at: url)
        } else if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
            await processImages([image])
        } else {
            errorMessage = String(localized: "Could not load the selected file.")
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

    func processImages(_ images: [UIImage]) async {
        guard !images.isEmpty else { return }
        phase = .extractingText
        isProcessing = true
        defer { isProcessing = false }

        do {
            let text = try await ocrService.extractText(from: images)
            try await handleExtractedText(text)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func processText(_ text: String) async {
        phase = .analyzing
        isProcessing = true
        defer { isProcessing = false }

        do {
            try await handleExtractedText(text)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func processPDF(at url: URL) async {
        phase = .extractingText
        isProcessing = true
        defer { isProcessing = false }

        do {
            let text = try await ocrService.extractText(fromPDFAt: url)
            try await handleExtractedText(text)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func handleExtractedText(_ text: String) async throws {
        // Text is in hand; the remaining (and longest) work is the AI parse.
        phase = .analyzing
        let result = try await parserService.parseLabValues(from: text)
        if result.values.isEmpty {
            errorMessage = emptyMessage
            return
        }
        onParsed?(result)
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
                        Task { await engine.processImages(images) }
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
                    Task { await engine.processFile(at: url) }
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
            // The processing HUD is hosted in its own UIWindow above the key
            // window rather than as a SwiftUI `.overlay`. An overlay composites
            // *inside* the navigation container's SwiftUI layer, but the toolbar
            // buttons live in UIKit's `UINavigationBar`, which sits above that
            // layer — so an overlay scrim can't reliably block them (the buttons
            // still highlight and, worse, cancel taps). A dedicated window is
            // genuinely on top of everything, navigation bar included, and
            // swallows all touches for the duration of the import.
            .background(
                ProcessingHUDPresenter(isProcessing: engine.isProcessing, phase: engine.phase)
            )
    }
}

// MARK: - HUD window presentation

/// Invisible SwiftUI anchor whose coordinator owns a top-level `UIWindow` that
/// hosts the processing HUD. Placed via `.background` so it occupies no layout
/// space; all it does is forward `isProcessing` / `phase` into the coordinator,
/// which shows or tears down the window.
private struct ProcessingHUDPresenter: UIViewRepresentable {
    var isProcessing: Bool
    var phase: ImportPhase

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.isHidden = true
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.update(isProcessing: isProcessing, phase: phase, anchor: uiView)
    }

    static func dismantleUIView(_ uiView: UIView, coordinator: Coordinator) {
        coordinator.tearDownNow()
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    @MainActor
    final class Coordinator {
        private var window: UIWindow?
        private let model = HUDModel()
        private var dismissTask: Task<Void, Never>?

        func update(isProcessing: Bool, phase: ImportPhase, anchor: UIView) {
            model.phase = phase

            if isProcessing {
                dismissTask?.cancel()
                dismissTask = nil
                presentIfNeeded(from: anchor)
                model.isActive = true
            } else {
                guard window != nil else { return }
                model.isActive = false
                // Defer teardown so the card/scrim can animate out with the
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

/// Observable backing for the windowed HUD. Living in a persistent hosting
/// controller (rather than re-creating the view) lets phase changes and the
/// show/hide transition animate naturally inside SwiftUI.
@MainActor
@Observable
private final class HUDModel {
    var phase: ImportPhase = .extractingText
    var isActive = false
}

private struct ProcessingHUDHost: View {
    @Bindable var model: HUDModel

    var body: some View {
        ZStack {
            if model.isActive {
                ProcessingHUD(phase: model.phase)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.isActive)
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

/// The coarse stages an import passes through, used to label the processing HUD
/// and drive its progress bar. OCR and the on-device model expose no real
/// progress, so each phase maps to an indicative fraction rather than a measured
/// one — enough to show motion and tell the user what's happening.
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

    var fraction: Double {
        switch self {
        case .extractingText: 0.45
        case .analyzing: 0.9
        }
    }
}

// MARK: - Processing HUD

struct ProcessingHUD: View {
    let phase: ImportPhase

    var body: some View {
        ZStack {
            // Dimming scrim: focuses attention on the card and, being an opaque
            // hit-testable shape, swallows every touch so the content behind the
            // HUD can't be interacted with while an import is in flight.
            Rectangle()
                .fill(.black.opacity(0.28))
                .ignoresSafeArea()
                .transition(.opacity)

            VStack(spacing: 18) {
                Image(systemName: phase.systemImage)
                    .font(.system(size: 40, weight: .semibold))
                    .foregroundStyle(.tint)
                    .symbolEffect(.pulse, options: .repeating)
                    .contentTransition(.symbolEffect(.replace))

                VStack(spacing: 6) {
                    Text(phase.title)
                        .font(.headline)
                    Text(phase.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)

                ProgressView(value: phase.fraction)
                    .progressViewStyle(.linear)
                    .tint(.accentColor)
                    .frame(width: 200)
                    .animation(.easeInOut(duration: 0.5), value: phase.fraction)
            }
            .padding(28)
            .frame(maxWidth: 280)
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 28))
            .transition(.scale(scale: 0.92).combined(with: .opacity))
        }
        // Fill the whole display and ignore the safe area so the card is centered
        // in the device's screen rather than in the host view's safe-area content
        // region (which would push it below the navigation bar).
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.3), value: phase)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

#Preview("Reading") {
    Color(.systemGroupedBackground)
        .overlay { ProcessingHUD(phase: .extractingText) }
}

#Preview("Analyzing") {
    Color(.systemGroupedBackground)
        .overlay { ProcessingHUD(phase: .analyzing) }
}
