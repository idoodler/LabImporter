import SwiftUI

/// Thin wrapper around `UIActivityViewController` used to share exported files
/// (CDA XML, PDF) from the app via the system share sheet.
struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // On iPad the activity controller is presented as a popover and requires a
        // non-nil source or it traps. Anchoring to its own view (which is in the
        // window once SwiftUI presents it) centers the popover and keeps it valid.
        if let popover = controller.popoverPresentationController {
            popover.sourceView = controller.view
            popover.permittedArrowDirections = []
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
        if let popover = uiViewController.popoverPresentationController,
           let source = popover.sourceView {
            popover.sourceRect = CGRect(x: source.bounds.midX, y: source.bounds.midY, width: 0, height: 0)
        }
    }
}
