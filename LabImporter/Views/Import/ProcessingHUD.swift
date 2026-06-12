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

/// The full-screen import progress screen: the landing screen's visual
/// language brought to life. On the category wash, the hero circle (the same
/// gradient + symbol treatment as `ImportLandingView`) sits inside a progress
/// ring — determinate while a multi-page document is OCR'd, an orbiting arc
/// while the on-device model parses — with the phase title, a live counter of
/// streamed values, and the most recently found value in a glass chip below.
/// A Cancel control fades in at the bottom once the import has run long
/// enough that bailing out is plausibly wanted.
struct ProcessingHUD: View {
    let phase: ImportPhase
    var ocrProgress: OCRPageProgress?
    var parseProgress: ParseProgress?
    var onCancel: (() -> Void)?

    @State private var showsCancel = false

    var body: some View {
        ZStack {
            // Full-bleed opaque background: the HUD reads as its own screen
            // and, being opaque and hit-testable, swallows every touch so the
            // content behind it can't be interacted with while an import runs.
            Color(.systemBackground)
                .ignoresSafeArea()
                .transition(.opacity)
            MorphingCategoryBackground()
                .transition(.opacity)

            ImportProgressView(
                phase: phase,
                ocrProgress: ocrProgress,
                parseProgress: parseProgress,
                statusText: statusText
            )
            .transition(.scale(scale: 0.94).combined(with: .opacity))

            VStack {
                Spacer()
                cancelButton
                    .padding(.bottom, 32)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.3), value: phase)
        .animation(.spring(duration: 0.4), value: parseProgress?.entryCount ?? 0)
        .task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation(.easeInOut(duration: 0.3)) { showsCancel = true }
        }
    }

    // MARK: Pieces

    /// One line of *real* progress: the OCR page being read, or the running
    /// count of values the model has streamed, falling back to the phase's
    /// static description. (The latest value's name gets its own chip.)
    private var statusText: Text {
        if phase == .extractingText, let pages = ocrProgress, pages.total > 1 {
            return Text("Page \(pages.page) of \(pages.total)")
        }
        if phase == .analyzing, let progress = parseProgress, progress.entryCount > 0 {
            return Text("\(progress.entryCount) values")
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
}

// MARK: - Progress content

/// The centered content of the import screen: hero circle in a progress
/// ring, phase title, live status, and the latest streamed value as a chip.
private struct ImportProgressView: View {
    let phase: ImportPhase
    var ocrProgress: OCRPageProgress?
    var parseProgress: ParseProgress?
    let statusText: Text

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isSpinning = false

    private static let heroGradient = LinearGradient(
        colors: [LabCategory.bloodGas.color, LabCategory.endocrine.color],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    /// Real ring progress while a multi-page document is OCR'd; nil means
    /// indeterminate (the on-device model exposes no fraction).
    private var ringFraction: Double? {
        guard phase == .extractingText, let pages = ocrProgress, pages.total > 1 else { return nil }
        return Double(pages.page) / Double(pages.total)
    }

    var body: some View {
        VStack(spacing: 28) {
            hero

            VStack(spacing: 8) {
                Text(phase.title)
                    .font(.title3.weight(.semibold))
                statusText
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .contentTransition(.numericText())
            }
            .multilineTextAlignment(.center)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)

            // Keeps the layout from jumping when the first chip appears.
            latestValueChip
                .frame(height: 36)
        }
        .padding(.horizontal, 32)
        .onAppear { isSpinning = true }
    }

    /// The landing hero circle, alive: same gradient, same glow, with the
    /// phase symbol pulsing inside and the progress ring around it.
    private var hero: some View {
        ZStack {
            Circle()
                .stroke(Color.primary.opacity(0.08), lineWidth: 4)
                .frame(width: 158, height: 158)
            progressRing
                .frame(width: 158, height: 158)
            Circle()
                .fill(Self.heroGradient)
                .frame(width: 110, height: 110)
                .shadow(color: LabCategory.endocrine.color.opacity(0.35), radius: 24, y: 10)
            Image(systemName: phase.systemImage)
                .font(.system(size: 44, weight: .medium))
                .foregroundStyle(.white)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.pulse, options: .repeating, isActive: !reduceMotion)
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var progressRing: some View {
        if let fraction = ringFraction {
            // Determinate: fills with real page progress.
            Circle()
                .trim(from: 0, to: fraction)
                .stroke(ringStyle, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(.easeInOut(duration: 0.4), value: fraction)
        } else if reduceMotion {
            // No fraction and no motion allowed: a calm full brand ring.
            Circle()
                .stroke(ringStyle, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .opacity(0.4)
        } else {
            // Indeterminate: a brand-gradient arc orbiting the hero.
            Circle()
                .trim(from: 0, to: 0.22)
                .stroke(ringStyle, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(isSpinning ? 270 : -90))
                .animation(.linear(duration: 1.4).repeatForever(autoreverses: false), value: isSpinning)
        }
    }

    private var ringStyle: AngularGradient {
        AngularGradient(
            colors: [LabCategory.bloodGas.color, LabCategory.endocrine.color, LabCategory.bloodGas.color],
            center: .center
        )
    }

    /// The most recently streamed value, in a glass chip. The name fills in
    /// token by token as the model writes it — a live "found it" readout.
    @ViewBuilder
    private var latestValueChip: some View {
        if let name = parseProgress?.latestName, !name.isEmpty {
            HStack(spacing: 6) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(LabCategory.hepatic.color)
                Text(name)
                    .lineLimit(1)
                    .contentTransition(.interpolate)
            }
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .glassEffect(.regular, in: Capsule())
            .frame(maxWidth: 300)
            .transition(.scale(scale: 0.9).combined(with: .opacity))
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.updatesFrequently)
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

#Preview("Analyzing — before first value") {
    ProcessingHUD(
        phase: .analyzing,
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
