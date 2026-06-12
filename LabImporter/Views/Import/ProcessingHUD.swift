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
/// Image Playground's generation view: a full-bleed plain background with one
/// large Liquid Glass orb in the middle — the phase title and live progress
/// (OCR pages, streamed values) sit *inside* the orb, and a Cancel control
/// fades in at the bottom once the import has run long enough that bailing
/// out is plausibly wanted.
///
/// Falls back to a static glass card when Reduce Motion is enabled or the
/// device is already running hot — the orb animates on the GPU while the
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
                staticCard
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
            } else {
                ImportBubbleView(phase: phase, statusText: statusText, parseProgress: parseProgress)
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

    /// Reduce Motion / thermal fallback: the orb's information without its
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

// MARK: - Interactive Liquid Glass bubble

/// The interactive water bubble: one large Liquid Glass orb that idles with a
/// slow wobble, stretches toward the user's finger anywhere on screen and
/// springs back when released. Every ball of the little physics simulation is
/// a real `.glassEffect` circle inside a `GlassEffectContainer`, whose native
/// shape blending merges them like water — so each lab value streamed out of
/// the on-device model arrives as a glass droplet that fuses into the orb,
/// growing it. The phase title and live status live inside the orb (and carry
/// the accessibility); the glass itself is decoration and hidden from it.
private struct ImportBubbleView: View {
    let phase: ImportPhase
    let statusText: Text
    var parseProgress: ParseProgress?

    @State private var physics = BubblePhysics()
    @GestureState private var fingerLocation: CGPoint?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                halo(in: geo.size)
                orb(in: geo.size)
                textBlock
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
            }
        }
        .ignoresSafeArea()
        .contentShape(Rectangle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .updating($fingerLocation) { value, state, _ in state = value.location }
        )
        .onChange(of: parseProgress?.entryCount ?? 0) { oldCount, newCount in
            // A burst of entries can arrive in one snapshot — cap the droplets
            // so the orb doesn't get mobbed.
            guard newCount > oldCount else { return }
            for _ in 0..<min(newCount - oldCount, 3) { physics.spawnDroplet() }
        }
    }

    private func orb(in size: CGSize) -> some View {
        TimelineView(.animation(minimumInterval: 1 / 40)) { timeline in
            // The container's spacing is the distance at which neighboring
            // glass shapes start flowing into each other — Liquid Glass does
            // the gooey merging natively, no raster trickery needed.
            GlassEffectContainer(spacing: 48) {
                ZStack {
                    ForEach(physics.balls(at: timeline.date, attractor: fingerLocation, in: size)) { ball in
                        Circle()
                            .frame(width: ball.radius * 2, height: ball.radius * 2)
                            .glassEffect(.regular, in: .circle)
                            .position(ball.position)
                    }
                }
                .frame(width: size.width, height: size.height)
            }
        }
        .accessibilityHidden(true)
    }

    /// A faint ambient halo behind the orb so the glass lifts off the plain
    /// background even when nothing colorful is behind it to refract.
    private func halo(in size: CGSize) -> some View {
        RadialGradient(
            colors: [Color.primary.opacity(0.07), .clear],
            center: .center,
            startRadius: 0,
            endRadius: 300
        )
        .frame(width: 600, height: 600)
        .position(x: size.width / 2, y: size.height / 2)
        .accessibilityHidden(true)
    }

    /// Title + live status, anchored to the orb's resting place. Fades out
    /// while the user drags the orb away so it doesn't float alone.
    private var textBlock: some View {
        VStack(spacing: 8) {
            Text(phase.title)
                .font(.title3.weight(.semibold))
            statusText
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .contentTransition(.numericText())
                .lineLimit(2)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: 220)
        .opacity(fingerLocation == nil ? 1 : 0)
        .animation(.easeOut(duration: 0.2), value: fingerLocation == nil)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

/// Minimal spring integrator behind `ImportBubbleView`: a core ball, a few
/// orbiting satellites that give the orb its idle undulation, and transient
/// droplets that chase the core until absorbed. A plain class mutated on each
/// `TimelineView` tick — nothing observes it; the timeline drives the redraws.
private final class BubblePhysics {
    struct Ball: Identifiable {
        let id = UUID()
        var position: CGPoint
        var velocity: CGVector = .zero
        var radius: CGFloat
        /// Polar offset of this ball's resting place around the orb core,
        /// advanced over time for the idle wobble. Zero for the core itself.
        var orbitRadius: CGFloat = 0
        var orbitSpeed: Double = 0
        var orbitPhase: Double = 0
        var isDroplet = false
    }

    private var ballStore: [Ball] = []
    private var lastStepTime: TimeInterval?
    private var bounds: CGSize = .zero
    /// Extra core radius earned by absorbed droplets.
    private var growth: CGFloat = 0

    private static let coreRadius: CGFloat = 118
    private static let maxGrowth: CGFloat = 28
    private static let stiffness: CGFloat = 90
    private static let damping: CGFloat = 7

    /// Advances the simulation to `date` and returns the balls to draw.
    func balls(at date: Date, attractor: CGPoint?, in size: CGSize) -> [Ball] {
        step(to: date, attractor: attractor, in: size)
        return ballStore
    }

    /// Spawns a droplet just outside the screen that springs toward the orb
    /// and merges on contact. No-op before the first frame has run (the
    /// bounds aren't known yet) and capped so droplets can't pile up.
    func spawnDroplet() {
        guard bounds != .zero, ballStore.count < 12 else { return }
        let center = CGPoint(x: bounds.width / 2, y: bounds.height / 2)
        let angle = Double.random(in: 0..<(2 * .pi))
        let distance = max(bounds.width, bounds.height) / 2 + 60
        var droplet = Ball(
            position: CGPoint(x: center.x + cos(angle) * distance, y: center.y + sin(angle) * distance),
            radius: CGFloat.random(in: 16...22)
        )
        droplet.isDroplet = true
        ballStore.append(droplet)
    }

    // MARK: Internals

    private func step(to date: Date, attractor: CGPoint?, in size: CGSize) {
        bounds = size
        if ballStore.isEmpty { seed(in: size) }
        let time = date.timeIntervalSinceReferenceDate
        let delta = CGFloat(min(time - (lastStepTime ?? time), 1 / 20))
        lastStepTime = time
        guard delta > 0 else { return }

        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        let anchor = clamp(attractor ?? center, in: size)
        // A gentle breath so the orb never looks frozen, even untouched.
        ballStore[0].radius = Self.coreRadius + growth + CGFloat(sin(time * 1.4)) * 4

        for index in ballStore.indices {
            let target = restingPlace(
                for: ballStore[index],
                around: index == 0 ? anchor : ballStore[0].position,
                at: time
            )
            integrate(&ballStore[index], toward: target, delta: delta)
        }
        absorbDroplets()
    }

    private func seed(in size: CGSize) {
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        ballStore = [Ball(position: center, radius: Self.coreRadius)]
        let orbitSpeeds: [Double] = [0.7, -0.5, 0.9]
        for index in 0..<3 {
            ballStore.append(Ball(
                position: center,
                radius: 52 + CGFloat(index) * 8,
                orbitRadius: 34 + CGFloat(index) * 10,
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
        let core = ballStore[0]
        ballStore.removeAll { ball in
            guard ball.isDroplet else { return false }
            let distance = hypot(ball.position.x - core.position.x, ball.position.y - core.position.y)
            guard distance < core.radius * 0.6 else { return false }
            growth = min(growth + 3, Self.maxGrowth)
            return true
        }
    }

    /// Keeps the orb's anchor far enough from the edges that the core stays
    /// substantially on screen however hard it's dragged.
    private func clamp(_ point: CGPoint, in size: CGSize) -> CGPoint {
        let inset: CGFloat = 110
        return CGPoint(
            x: min(max(point.x, inset), size.width - inset),
            y: min(max(point.y, inset), size.height - inset)
        )
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
