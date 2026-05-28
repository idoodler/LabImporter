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

    func extractText(from images: [UIImage]) async throws -> String {
        guard !images.isEmpty else { throw OCRError.invalidImage }

        var pageTexts: [String] = []
        for image in images {
            let text = try await recognizeText(in: image)
            if !text.isEmpty {
                pageTexts.append(text)
            }
        }

        let joined = pageTexts.joined(separator: "\n\n")
        if joined.isEmpty { throw OCRError.noTextFound }
        return joined
    }

    func extractText(fromPDFAt url: URL) async throws -> String {
        guard let document = PDFDocument(url: url) else { throw OCRError.unreadablePDF }

        var embeddedText: [String] = []
        var renderedImages: [UIImage] = []

        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            if let text = page.string?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
                embeddedText.append(text)
            } else if let image = render(page: page) {
                renderedImages.append(image)
            }
        }

        let embedded = embeddedText.joined(separator: "\n\n")
        if !renderedImages.isEmpty {
            let recognized = try await extractText(from: renderedImages)
            return [embedded, recognized].filter { !$0.isEmpty }.joined(separator: "\n\n")
        }

        if embedded.isEmpty { throw OCRError.noTextFound }
        return embedded
    }

    // MARK: - Private

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
            request.recognitionLanguages = ["de-DE", "en-US"]
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
