import SwiftUI

/// A metric whose detail should be presented, wrapped so it can drive a
/// `.sheet(item:)`.
struct MetricDetailRequest: Identifiable {
    let code: String
    var id: String { code }
}

/// Coordinates opening a metric's trend detail from a Spotlight deep link when
/// the app isn't on its start screen.
///
/// The detail is a root-level sheet, so it can't be presented while another
/// sheet is up — and a Review editor may hold *unsaved work*. Editors register
/// here (`editorAppeared`/`editorDisappeared`); a deep link asks any open editor
/// to close, which surfaces the editor's own discard confirmation, and the
/// detail is only presented once no editor remains open. At that point the app
/// also pops back to its root so the request reliably lands on the dashboard.
@MainActor
@Observable
final class SearchPresentationCoordinator {
    /// Drives the trend-detail sheet at the app root. Set only once the UI is
    /// clear, so the presentation can't collide with another sheet.
    var presentedCode: String?

    /// Bumped to pop every navigation stack back to its root (which also rebuilds
    /// — and so dismisses — any non-editor transient sheet hosted inside it).
    private(set) var navResetToken = 0

    /// Number of Review editors currently on screen.
    private(set) var openEditors = 0

    /// Bumped to ask every open editor to attempt to close (each honoring its own
    /// unsaved-changes confirmation).
    private(set) var closeRequest = 0

    /// The metric queued while editors finish closing, or `nil` when idle.
    private var pendingCode: String?
    private var awaitingEditors = false

    /// Request the detail for `code`. Presents immediately when nothing is in the
    /// way, otherwise asks open editors to close first and waits for them.
    func open(_ code: String) {
        pendingCode = code
        if openEditors > 0 {
            awaitingEditors = true
            closeRequest += 1
        } else {
            finalize()
        }
    }

    func editorAppeared() { openEditors += 1 }

    func editorDisappeared() {
        openEditors = max(0, openEditors - 1)
        guard openEditors == 0, awaitingEditors else { return }
        awaitingEditors = false
        finalize()
    }

    /// Clears the queue once the detail is on screen, so later editor activity
    /// doesn't re-present it.
    func didPresentDetail() { pendingCode = nil }

    /// Navigates back to the root and presents the queued detail once the
    /// dismissals settle, so the root sheet doesn't collide with a sheet that's
    /// still animating away.
    private func finalize() {
        guard let code = pendingCode else { return }
        presentedCode = nil
        navResetToken += 1
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(0.35))
            guard let self, self.pendingCode == code else { return }
            self.presentedCode = code
        }
    }
}

// MARK: - Editor registration

extension View {
    /// Registers a Review editor with the deep-link coordinator: it counts as open
    /// while on screen and runs `onClose` when a deep link asks it to step aside
    /// (so its own unsaved-changes confirmation can intervene). A no-op when no
    /// coordinator is in the environment (e.g. previews).
    func registersAsSearchEditor(onClose: @escaping () -> Void) -> some View {
        modifier(SearchEditorRegistration(onClose: onClose))
    }
}

private struct SearchEditorRegistration: ViewModifier {
    @Environment(SearchPresentationCoordinator.self) private var coordinator: SearchPresentationCoordinator?
    let onClose: () -> Void

    func body(content: Content) -> some View {
        content
            .onAppear { coordinator?.editorAppeared() }
            .onDisappear { coordinator?.editorDisappeared() }
            .onChange(of: coordinator?.closeRequest) { _, _ in onClose() }
    }
}
