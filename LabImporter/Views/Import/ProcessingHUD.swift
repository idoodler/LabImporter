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

/// The full-screen import overlay: a dimming scrim, the interactive water
/// bubble (`ImportBubbleView`), live status text fed by real progress (OCR
/// pages, streamed values), and a Cancel control that fades in once the
/// import has run long enough that bailing out is plausibly wanted.
///
/// Falls back to a static glass card when Reduce Motion is enabled or the
/// device is already running hot — the bubble animates on the GPU while the
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
            // Dimming scrim: focuses attention on the bubble and, being an
            // opaque hit-testable shape, swallows every touch so the content
            // behind the HUD can't be interacted with while an import runs.
            Rectangle()
                .fill(.black.opacity(0.28))
                .ignoresSafeArea()
                .transition(.opacity)

            VStack(spacing: 24) {
                if usesStaticCard {
                    staticCard
                } else {
                    ImportBubbleView(phase: phase, parseProgress: parseProgress)
                    statusBlock
                }
                cancelButton
            }
            .transition(.scale(scale: 0.92).combined(with: .opacity))
        }
        // Fill the whole display and ignore the safe area so the bubble is
        // centered in the device's screen rather than in the host view's
        // safe-area content region (which would push it below the nav bar).
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

    private var statusBlock: some View {
        VStack(spacing: 6) {
            Text(phase.title)
                .font(.headline)
            statusText
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .contentTransition(.numericText())
                .lineLimit(2)
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 32)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }

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

    /// Reduce Motion / thermal fallback: the bubble's information without its
    /// animation, in the same glass language as the rest of the import flow.
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

// MARK: - Interactive water bubble

/// The interactive "water bubble" shown while an import runs: a gooey metaball
/// blob that idles with a slow wobble, stretches toward the user's finger and
/// springs back when released. Each lab value streamed out of the on-device
/// model arrives as a droplet that flies in and merges into the blob, so the
/// bubble literally grows with the report. Purely decorative — everything the
/// user needs is in the status text — so it's hidden from accessibility.
private struct ImportBubbleView: View {
    let phase: ImportPhase
    var parseProgress: ParseProgress?

    @State private var physics = BubblePhysics()
    @GestureState private var fingerLocation: CGPoint?

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 40)) { timeline in
            blob(at: timeline.date)
        }
        .frame(width: 320, height: 260)
        .gesture(
            DragGesture(minimumDistance: 0)
                .updating($fingerLocation) { value, state, _ in state = value.location }
        )
        .onChange(of: parseProgress?.entryCount ?? 0) { oldCount, newCount in
            // A burst of entries can arrive in one snapshot — cap the droplets
            // so the blob doesn't get mobbed.
            guard newCount > oldCount else { return }
            for _ in 0..<min(newCount - oldCount, 3) { physics.spawnDroplet() }
        }
        .accessibilityHidden(true)
    }

    private func blob(at date: Date) -> some View {
        LinearGradient(
            colors: [LabCategory.bloodGas.color, LabCategory.endocrine.color],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
        .mask { metaballCanvas(at: date) }
        .overlay {
            Image(systemName: phase.systemImage)
                .font(.system(size: 36, weight: .semibold))
                .foregroundStyle(.white)
                .symbolEffect(.pulse, options: .repeating)
                .contentTransition(.symbolEffect(.replace))
                // The icon is anchored to the bubble's resting place; fade it
                // out while the user drags the blob away so it doesn't float
                // alone in empty space.
                .opacity(fingerLocation == nil ? 1 : 0)
                .animation(.easeOut(duration: 0.2), value: fingerLocation == nil)
        }
        .shadow(color: LabCategory.endocrine.color.opacity(0.35), radius: 24, y: 10)
    }

    private func metaballCanvas(at date: Date) -> some View {
        Canvas { context, size in
            physics.step(to: date, attractor: fingerLocation, in: size)
            // The classic gooey-blob recipe: heavy blur smears the circles
            // into each other, then the alpha threshold snaps the smear back
            // to a hard, fused outline — circles merge like water droplets.
            context.addFilter(.alphaThreshold(min: 0.5, color: .white))
            context.addFilter(.blur(radius: 14))
            context.drawLayer { layer in
                for ball in physics.balls {
                    layer.fill(Path(ellipseIn: ball.frame), with: .color(.white))
                }
            }
        }
    }
}

/// Minimal spring integrator behind `ImportBubbleView`: a core ball, a few
/// orbiting satellites that give the blob its idle undulation, and transient
/// droplets that chase the core until absorbed. A plain class mutated from
/// inside the `Canvas` renderer on each `TimelineView` tick — nothing observes
/// it; the timeline drives the redraws.
private final class BubblePhysics {
    struct Ball {
        var position: CGPoint
        var velocity: CGVector = .zero
        var radius: CGFloat
        /// Polar offset of this ball's resting place around the blob core,
        /// advanced over time for the idle wobble. Zero for the core itself.
        var orbitRadius: CGFloat = 0
        var orbitSpeed: Double = 0
        var orbitPhase: Double = 0
        var isDroplet = false

        var frame: CGRect {
            CGRect(x: position.x - radius, y: position.y - radius, width: 2 * radius, height: 2 * radius)
        }
    }

    private(set) var balls: [Ball] = []
    private var lastStepTime: TimeInterval?
    private var bounds: CGSize = .zero
    /// Extra core radius earned by absorbed droplets.
    private var growth: CGFloat = 0

    private static let coreRadius: CGFloat = 52
    private static let maxGrowth: CGFloat = 14
    private static let stiffness: CGFloat = 90
    private static let damping: CGFloat = 7

    func step(to date: Date, attractor: CGPoint?, in size: CGSize) {
        bounds = size
        if balls.isEmpty { seed(in: size) }
        let time = date.timeIntervalSinceReferenceDate
        let delta = CGFloat(min(time - (lastStepTime ?? time), 1 / 20))
        lastStepTime = time
        guard delta > 0 else { return }

        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let anchor = clamp(attractor ?? center, in: size)
        // A gentle breath so the bubble never looks frozen, even untouched.
        balls[0].radius = Self.coreRadius + growth + CGFloat(sin(time * 1.4)) * 2

        for index in balls.indices {
            let target = restingPlace(for: balls[index], around: index == 0 ? anchor : balls[0].position, at: time)
            integrate(&balls[index], toward: target, delta: delta)
        }
        absorbDroplets()
    }

    /// Spawns a droplet just outside the canvas that springs toward the blob
    /// and merges on contact. No-op before the first frame has run (the
    /// canvas size isn't known yet) and capped so droplets can't pile up.
    func spawnDroplet() {
        guard bounds != .zero, balls.count < 12 else { return }
        let center = CGPoint(x: bounds.width / 2, y: bounds.height / 2)
        let angle = Double.random(in: 0..<(2 * .pi))
        let distance = max(bounds.width, bounds.height) / 2 + 24
        var droplet = Ball(
            position: CGPoint(x: center.x + cos(angle) * distance, y: center.y + sin(angle) * distance),
            radius: CGFloat.random(in: 9...13)
        )
        droplet.isDroplet = true
        balls.append(droplet)
    }

    // MARK: Internals

    private func seed(in size: CGSize) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        balls = [Ball(position: center, radius: Self.coreRadius)]
        let orbitSpeeds: [Double] = [0.7, -0.5, 0.9]
        for index in 0..<3 {
            balls.append(Ball(
                position: center,
                radius: 26 + CGFloat(index) * 4,
                orbitRadius: 18 + CGFloat(index) * 5,
                orbitSpeed: orbitSpeeds[index],
                orbitPhase: Double(index) * 2.1
            ))
        }
    }

    private func restingPlace(for ball: Ball, around center: CGPoint, at time: TimeInterval) -> CGPoint {
        guard !ball.isDroplet else { return center }  // droplets chase the core
        let angle = ball.orbitPhase + time * ball.orbitSpeed
        return CGPoint(
            x: center.x + cos(angle) * ball.orbitRadius,
            y: center.y + sin(angle) * ball.orbitRadius
        )
    }

    /// Semi-implicit Euler with an underdamped spring — enough wobble to feel
    /// like water, settling in under a second.
    private func integrate(_ ball: inout Ball, toward target: CGPoint, delta: CGFloat) {
        let stiffness = ball.isDroplet ? Self.stiffness / 2 : Self.stiffness
        ball.velocity.dx += ((target.x - ball.position.x) * stiffness - ball.velocity.dx * Self.damping) * delta
        ball.velocity.dy += ((target.y - ball.position.y) * stiffness - ball.velocity.dy * Self.damping) * delta
        ball.position.x += ball.velocity.dx * delta
        ball.position.y += ball.velocity.dy * delta
    }

    private func absorbDroplets() {
        let core = balls[0]
        balls.removeAll { ball in
            guard ball.isDroplet else { return false }
            let distance = hypot(ball.position.x - core.position.x, ball.position.y - core.position.y)
            guard distance < core.radius * 0.7 else { return false }
            growth = min(growth + 1.5, Self.maxGrowth)
            return true
        }
    }

    private func clamp(_ point: CGPoint, in size: CGSize) -> CGPoint {
        let inset: CGFloat = 30
        return CGPoint(
            x: min(max(point.x, inset), size.width - inset),
            y: min(max(point.y, inset), size.height - inset)
        )
    }
}

// MARK: - Previews

#Preview("Reading — page progress") {
    Color(.systemGroupedBackground)
        .ignoresSafeArea()
        .overlay {
            ProcessingHUD(
                phase: .extractingText,
                ocrProgress: OCRPageProgress(page: 2, total: 5),
                onCancel: {}
            )
        }
}

#Preview("Analyzing — streaming values") {
    Color(.systemGroupedBackground)
        .ignoresSafeArea()
        .overlay {
            ProcessingHUD(
                phase: .analyzing,
                parseProgress: ParseProgress(entryCount: 7, latestName: "Kreatinin"),
                onCancel: {}
            )
        }
}

#Preview("Analyzing — Dark") {
    Color(.systemGroupedBackground)
        .ignoresSafeArea()
        .overlay {
            ProcessingHUD(
                phase: .analyzing,
                parseProgress: ParseProgress(entryCount: 12, latestName: "HbA1c"),
                onCancel: {}
            )
        }
        .preferredColorScheme(.dark)
}

#Preview("Reduced Motion / thermal fallback") {
    Color(.systemGroupedBackground)
        .ignoresSafeArea()
        .overlay {
            ProcessingHUD(
                phase: .analyzing,
                parseProgress: ParseProgress(entryCount: 3, latestName: "Ferritin"),
                onCancel: {},
                forcesStaticCard: true
            )
        }
}
