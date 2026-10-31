import Vision
import UIKit
import PDFKit

actor OCRService {

    enum OCRError: LocalizedError {
        case invalidImage
        case noTextFound
        case unreadablePDF

        var errorDescription: String? {
            switch self {
            case .invalidImage: return "Could not process the selected image."
            case .noTextFound: return "No text was found in the document."
            case .unreadablePDF: return "Could not read the selected PDF."
            }
        }
    }

    func extractText(from image: UIImage) async throws -> String {
        try await extractText(from: [image])
    }

    /// Recognizes text page by page. `onPageProgress` is invoked just before
    /// each page is processed with `(page, total)` so callers can surface real
    /// OCR progress; cancellation is observed between pages.
    func extractText(
        from images: [UIImage],
        onPageProgress: (@Sendable (_ page: Int, _ total: Int) -> Void)? = nil
    ) async throws -> String {
        guard !images.isEmpty else { throw OCRError.invalidImage }

        let joined = try await recognizePages(images, onPageProgress: onPageProgress)
            .filter { !$0.isEmpty }
            .joined(separator: "\n\n")
        if joined.isEmpty { throw OCRError.noTextFound }
        return joined
    }

    /// Extracts a PDF's text, in page order, OCR-ing only the pages whose
    /// embedded text can't be used: none at all, or text drawn with
    /// private-use characters (`hasPrivateUseCharacters`). `onPageProgress`
    /// reports progress across those OCR'd pages (embedded text is
    /// effectively instant and isn't counted).
    func extractText(
        fromPDFAt url: URL,
        onPageProgress: (@Sendable (_ page: Int, _ total: Int) -> Void)? = nil
    ) async throws -> String {
        guard let document = PDFDocument(url: url) else { throw OCRError.unreadablePDF }

        /// Each page's embedded text, or the index of its render in `renderedImages`.
        enum PageText { case embedded(String), rendered(Int) }
        var pages: [PageText] = []
        var renderedImages: [UIImage] = []

        for index in 0..<document.pageCount {
            try Task.checkCancellation()
            guard let page = document.page(at: index) else { continue }
            let text = page.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !text.isEmpty, !Self.hasPrivateUseCharacters(text) {
                pages.append(.embedded(text))
            } else if let image = render(page: page) {
                pages.append(.rendered(renderedImages.count))
                renderedImages.append(image)
            } else if !text.isEmpty {
                pages.append(.embedded(text))
            }
        }

        let recognized = try await recognizePages(renderedImages, onPageProgress: onPageProgress)
        let joined = pages.map { page in
            switch page {
            case .embedded(let text): return text
            case .rendered(let index): return recognized[index]
            }
        }
        .filter { !$0.isEmpty }
        .joined(separator: "\n\n")
        if joined.isEmpty { throw OCRError.noTextFound }
        return joined
    }

    /// Whether a page's embedded text uses Unicode private-use code points.
    /// Some PDFs' fonts draw ordinary symbols with them — Function Health's
    /// draw "<", "(", ")", "-" and ":" that way — so PDFKit hands back code
    /// points no reader can interpret, and "<1.0 mcg/dL" would arrive as an
    /// exact 1.0. OCR reads the drawn glyphs instead.
    static func hasPrivateUseCharacters(_ text: String) -> Bool {
        text.unicodeScalars.contains { scalar in
            (0xE000...0xF8FF).contains(scalar.value) || scalar.value >= 0xF0000
        }
    }

    // MARK: - Private

    /// Each image's recognized text, in order; cancellation is observed between pages.
    private func recognizePages(
        _ images: [UIImage],
        onPageProgress: (@Sendable (_ page: Int, _ total: Int) -> Void)?
    ) async throws -> [String] {
        var texts: [String] = []
        for (index, image) in images.enumerated() {
            try Task.checkCancellation()
            onPageProgress?(index + 1, images.count)
            texts.append(try await recognizeText(in: image))
        }
        return texts
    }

    private func recognizeText(in image: UIImage) async throws -> String {
        guard let cgImage = image.cgImage else { throw OCRError.invalidImage }

        return try await withCheckedThrowingContinuation { continuation in
            let request = VNRecognizeTextRequest { request, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }

                let observations = request.results as? [VNRecognizedTextObservation] ?? []
                let lines = observations.compactMap { obs -> String? in
                    obs.topCandidates(1).first?.string
                }

                continuation.resume(returning: lines.joined(separator: " "))
            }

            request.recognitionLevel = .accurate
            request.recognitionLanguages = Self.recognitionLanguages(for: request)
            request.usesLanguageCorrection = true
            request.minimumTextHeight = 0.01

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            do {
                try handler.perform([request])
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    /// Recognition languages to hand to Vision: the app's current UI language
    /// first (reports are typically in the language the user runs the app in),
    /// with English kept as a fallback for Latin-script text. Filtered to the
    /// codes the current Vision revision can actually recognize so we never pass
    /// an unsupported code; falls back to English if Vision reports nothing.
    private static func recognitionLanguages(for request: VNRecognizeTextRequest) -> [String] {
        let supported = (try? request.supportedRecognitionLanguages()) ?? []
        guard !supported.isEmpty else { return ["en-US"] }

        // The app's effective language (its preferred localization), then English.
        let desired = (Bundle.main.preferredLocalizations + ["en"])
        var ordered: [String] = []

        for identifier in desired {
            let base = Locale(identifier: identifier).language.languageCode?.identifier
            // Prefer an exact code, then a script/region-qualified match, then
            // any code sharing the same base language (e.g. "de" → "de-DE").
            let match = supported.first { $0.caseInsensitiveCompare(identifier) == .orderedSame }
                ?? supported.first { $0.hasPrefix(identifier) }
                ?? supported.first { Locale(identifier: $0).language.languageCode?.identifier == base }
            if let match, !ordered.contains(match) {
                ordered.append(match)
            }
        }

        return ordered.isEmpty ? supported : ordered
    }

    private func render(page: PDFPage) -> UIImage? {
        let bounds = page.bounds(for: .mediaBox)
        guard bounds.width > 0, bounds.height > 0 else { return nil }

        let format = UIGraphicsImageRendererFormat()
        format.scale = 2.0
        format.opaque = true

        let renderer = UIGraphicsImageRenderer(size: bounds.size, format: format)
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: bounds.size))
            ctx.cgContext.translateBy(x: 0, y: bounds.height)
            ctx.cgContext.scaleBy(x: 1, y: -1)
            page.draw(with: .mediaBox, to: ctx.cgContext)
        }
    }
}
