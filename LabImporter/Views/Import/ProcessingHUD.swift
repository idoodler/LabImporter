import SwiftUI

// MARK: - Windowed model

/// Observable backing for the windowed HUD. Living in a persistent hosting
/// controller (rather than re-creating the view) lets phase changes and the
/// show/hide transition animate naturally inside SwiftUI.
@MainActor
@Observable
final class ProcessingHUDModel {
    var phase: ImportPhase = .extractingText
    var isActive = false
    var ocrProgress: OCRPageProgress?
    var parseProgress: ParseProgress?
    var onCancel: (() -> Void)?
}

/// Root view of the HUD window — keeps a stable identity across updates so
/// phase changes and the show/hide transition animate inside SwiftUI.
struct ProcessingHUDHost: View {
    @Bindable var model: ProcessingHUDModel

    var body: some View {
        ZStack {
            if model.isActive {
                ProcessingHUD(
                    phase: model.phase,
                    ocrProgress: model.ocrProgress,
                    parseProgress: model.parseProgress,
                    onCancel: model.onCancel
                )
            }
        }
        .animation(.easeInOut(duration: 0.25), value: model.isActive)
    }
}

// MARK: - Processing HUD

/// The import screen shown while OCR + the on-device parse run. Styled after
/// Image Playground's generation view: a full-bleed background with one large
/// water drop wobbling in the middle — the phase title and live progress
/// (OCR pages, streamed values) sit below the drop and are refracted through
/// it when it passes over them, and a Cancel control fades in at the bottom
/// once the import has run long enough that bailing out is plausibly wanted.
///
/// Falls back to a static glass card when Reduce Motion is enabled or the
/// device is already running hot — the drop animates on the GPU while the
/// language model is busy, and that's a luxury, not a requirement.
struct ProcessingHUD: View {
    let phase: ImportPhase
    var ocrProgress: OCRPageProgress?
    var parseProgress: ParseProgress?
    var onCancel: (() -> Void)?
    /// Forces the static-card variant (used by previews); at runtime the card
    /// is chosen by Reduce Motion and thermal state.
    var forcesStaticCard = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isThermallyThrottled = ProcessInfo.processInfo.thermalState.isThrottling
    @State private var showsCancel = false

    private var usesStaticCard: Bool { forcesStaticCard || reduceMotion || isThermallyThrottled }

    var body: some View {
        ZStack {
            // Full-bleed opaque background: the HUD reads as its own screen —
            // like Image Playground's generation view — and, being opaque and
            // hit-testable, swallows every touch so the content behind it
            // can't be interacted with while an import runs.
            Color(.systemBackground)
                .ignoresSafeArea()
                .transition(.opacity)

            if usesStaticCard {
                // The drop view brings its own wash (it has to live inside the
                // refracted layer); the static path adds it here instead.
                MorphingCategoryBackground()
                    .transition(.opacity)
                staticCard
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
            } else {
                ImportWaterDropView(phase: phase, statusText: statusText, parseProgress: parseProgress)
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
            }

            VStack {
                Spacer()
                cancelButton
                    .padding(.bottom, 32)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.3), value: phase)
        .task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation(.easeInOut(duration: 0.3)) { showsCancel = true }
        }
        .onReceive(
            NotificationCenter.default
                .publisher(for: ProcessInfo.thermalStateDidChangeNotification)
                .receive(on: DispatchQueue.main)
        ) { _ in
            isThermallyThrottled = ProcessInfo.processInfo.thermalState.isThrottling
        }
    }

    // MARK: Pieces

    /// One line of *real* progress: the OCR page being read, or the running
    /// count of values the model has streamed (plus the latest test name),
    /// falling back to the phase's static description.
    private var statusText: Text {
        if phase == .extractingText, let pages = ocrProgress, pages.total > 1 {
            return Text("Page \(pages.page) of \(pages.total)")
        }
        if phase == .analyzing, let progress = parseProgress, progress.entryCount > 0 {
            let count = Text("\(progress.entryCount) values")
            if let name = progress.latestName, !name.isEmpty {
                return count + Text(verbatim: " · \(name)")
            }
            return count
        }
        return Text(phase.detail)
    }

    private var cancelButton: some View {
        Button(role: .cancel) {
            onCancel?()
        } label: {
            Text("Cancel")
                .padding(.horizontal, 12)
        }
        .buttonStyle(.glass)
        .opacity(showsCancel ? 1 : 0)
        .allowsHitTesting(showsCancel)
    }

    /// Reduce Motion / thermal fallback: the drop's information without its
    /// animation, in the same glass language.
    private var staticCard: some View {
        VStack(spacing: 18) {
            Image(systemName: phase.systemImage)
                .font(.system(size: 40, weight: .semibold))
                .foregroundStyle(.tint)
                .contentTransition(.symbolEffect(.replace))

            VStack(spacing: 6) {
                Text(phase.title)
                    .font(.headline)
                statusText
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .contentTransition(.numericText())
            }
            .multilineTextAlignment(.center)
        }
        .padding(28)
        .frame(maxWidth: 280)
        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 28))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

private extension ProcessInfo.ThermalState {
    /// Whether the device is hot enough that decorative GPU work should stop.
    /// (`ThermalState` isn't `Comparable`, hence the explicit case check.)
    var isThrottling: Bool {
        switch self {
        case .serious, .critical: return true
        default: return false
        }
    }
}

// MARK: - Previews

#Preview("Reading — page progress") {
    ProcessingHUD(
        phase: .extractingText,
        ocrProgress: OCRPageProgress(page: 2, total: 5),
        onCancel: {}
    )
}

#Preview("Analyzing — streaming values") {
    ProcessingHUD(
        phase: .analyzing,
        parseProgress: ParseProgress(entryCount: 7, latestName: "Kreatinin"),
        onCancel: {}
    )
}

#Preview("Analyzing — Dark") {
    ProcessingHUD(
        phase: .analyzing,
        parseProgress: ParseProgress(entryCount: 12, latestName: "HbA1c"),
        onCancel: {}
    )
    .preferredColorScheme(.dark)
}

#Preview("Reduced Motion / thermal fallback") {
    ProcessingHUD(
        phase: .analyzing,
        parseProgress: ParseProgress(entryCount: 3, latestName: "Ferritin"),
        onCancel: {},
        forcesStaticCard: true
    )
}
