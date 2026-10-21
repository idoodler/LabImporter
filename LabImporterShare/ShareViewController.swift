import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The share extension: receives text — a lab report selected in Mail or a
/// patient portal, say — and hands it to LabImporter through the shared inbox
/// (`ShareInbox`). PDFs and images reach the app directly via "Open in
/// LabImporter", so this extension only accepts text.
final class ShareViewController: UIViewController {
    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        let host = UIHostingController(rootView: ShareView(model: model) { [weak self] in self?.finish() })
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
        Task { await receive() }
    }

    private func receive() async {
        let items = extensionContext?.inputItems.compactMap { $0 as? NSExtensionItem } ?? []
        let text = await SharedText.load(from: items)
        guard !text.isEmpty else {
            model.state = .failed(.noText)
            return
        }
        guard let inbox = ShareInbox.shared, let item = try? inbox.store(text) else {
            model.state = .failed(.unavailable)
            return
        }
        // Documented API, but the system may decline it for share extensions;
        // then the app picks the text up the next time it opens.
        let opened = await extensionContext?.open(ShareInbox.url(for: item)) ?? false
        if opened {
            finish()
        } else {
            model.state = .saved
        }
    }

    private func finish() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}

/// The text of shared items: the selection a host app attaches as content
/// text plus any plain-text attachments, without repeats.
@MainActor
enum SharedText {
    static func load(from items: [NSExtensionItem]) async -> String {
        var pieces: [String] = []
        for item in items {
            if let content = item.attributedContentText?.string { pieces.append(content) }
            for provider in item.attachments ?? [] where provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                if let text = await text(from: provider) { pieces.append(text) }
            }
        }
        var seen = Set<String>()
        return pieces
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
            .joined(separator: "\n\n")
    }

    private static func text(from provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { continuation in
            _ = provider.loadObject(ofClass: String.self) { string, _ in
                continuation.resume(returning: string)
            }
        }
    }
}
