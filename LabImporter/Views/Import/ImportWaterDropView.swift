import SwiftUI

// MARK: - Interactive water drop

/// The interactive water drop: a single milky, softly lit blob — modeled on
/// Image Playground's generation drop — that idles with the slow squash-and-
/// stretch of a droplet in zero gravity, elongates along its direction of
/// travel when dragged (anywhere on screen) and springs back when released.
/// Each lab value streamed out of the on-device model arrives as a small
/// droplet that flies in and merges, giving the surface a visible "gulp".
///
/// The drop is deliberately *painted* (gradients, specular, chromatic rim
/// glints) rather than built from backdrop-sampling glass: the reference
/// drop is opaque, and its glassiness is lighting, not refraction. The phase
/// title and live status live inside the drop and carry the accessibility;
/// the drop itself is decoration and hidden from it.
struct ImportWaterDropView: View {
    let phase: ImportPhase
    let statusText: Text
    var parseProgress: ParseProgress?

    @State private var physics = BubblePhysics()
    @GestureState private var fingerLocation: CGPoint?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                drop(in: geo.size)
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
            // so the drop doesn't get mobbed.
            guard newCount > oldCount else { return }
            for _ in 0..<min(newCount - oldCount, 3) { physics.spawnDroplet() }
        }
    }

    private func drop(in size: CGSize) -> some View {
        TimelineView(.animation(minimumInterval: 1 / 40)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            let state = physics.dropState(at: timeline.date, attractor: fingerLocation, in: size)
            ZStack {
                halo(for: state)
                ForEach(state.droplets) { dropletView($0) }
                dropBody(state, time: time)
            }
        }
        .accessibilityHidden(true)
    }

    /// The drop itself: milky body, volume shading, specular, and a slowly
    /// turning chromatic glint on the rim — all clipped to the wobbling shape.
    private func dropBody(_ state: BubblePhysics.DropState, time: TimeInterval) -> some View {
        // Generous headroom around the resting radius so squash-and-stretch
        // never clips against the frame.
        let side = state.radius * 3.2
        let shape = WaterDropShape(
            mode2: state.mode2,
            mode2Angle: state.mode2Angle,
            mode3: state.mode3,
            mode3Phase: state.mode3Phase
        )
        return ZStack {
            shape
                .fill(
                    RadialGradient(
                        colors: [Color(.systemBackground), Color(.systemGray5)],
                        center: UnitPoint(x: 0.42, y: 0.36),
                        startRadius: 0,
                        endRadius: side * 0.55
                    )
                )
            // Darkening toward the lower-trailing rim gives the volume.
            shape
                .fill(
                    RadialGradient(
                        colors: [.clear, .black.opacity(0.10)],
                        center: UnitPoint(x: 0.42, y: 0.34),
                        startRadius: side * 0.18,
                        endRadius: side * 0.52
                    )
                )
            // Soft specular up-left, like the reference drop's window light.
            Ellipse()
                .fill(.white.opacity(0.55))
                .frame(width: state.radius * 0.9, height: state.radius * 0.45)
                .blur(radius: 16)
                .position(x: side / 2 - state.radius * 0.25, y: side / 2 - state.radius * 0.5)
            // The tiny rainbow glints that creep along the reference drop's rim.
            shape
                .stroke(glintGradient(at: time), lineWidth: 2.5)
                .blur(radius: 1)
        }
        .frame(width: side, height: side)
        .shadow(color: .black.opacity(0.12), radius: 28, y: 12)
        .position(state.position)
    }

    /// A big soft ambient halo that travels with the drop.
    private func halo(for state: BubblePhysics.DropState) -> some View {
        RadialGradient(
            colors: [Color.primary.opacity(0.10), .clear],
            center: .center,
            startRadius: state.radius * 0.5,
            endRadius: state.radius * 2.2
        )
        .frame(width: state.radius * 4.4, height: state.radius * 4.4)
        .position(state.position)
    }

    /// An incoming streamed-value droplet: a miniature of the drop's body.
    private func dropletView(_ droplet: BubblePhysics.Droplet) -> some View {
        Circle()
            .fill(
                RadialGradient(
                    colors: [Color(.systemBackground), Color(.systemGray4)],
                    center: UnitPoint(x: 0.38, y: 0.32),
                    startRadius: 0,
                    endRadius: droplet.radius * 1.4
                )
            )
            .frame(width: droplet.radius * 2, height: droplet.radius * 2)
            .shadow(color: .black.opacity(0.10), radius: 8, y: 4)
            .position(droplet.position)
    }

    /// Mostly-transparent angular gradient with two short chromatic slivers,
    /// rotated slowly over time so the glints wander around the rim.
    private func glintGradient(at time: TimeInterval) -> AngularGradient {
        AngularGradient(
            stops: [
                .init(color: .clear, location: 0),
                .init(color: .clear, location: 0.50),
                .init(color: .blue.opacity(0.65), location: 0.54),
                .init(color: .cyan.opacity(0.55), location: 0.57),
                .init(color: .clear, location: 0.61),
                .init(color: .clear, location: 0.84),
                .init(color: .orange.opacity(0.45), location: 0.87),
                .init(color: .pink.opacity(0.45), location: 0.89),
                .init(color: .clear, location: 0.92),
                .init(color: .clear, location: 1)
            ],
            center: .center,
            angle: .radians(time * 0.25)
        )
    }

    /// Title + live status, anchored to the drop's resting place. Fades out
    /// while the user drags the drop away so it doesn't float alone.
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

/// The wobbling outline of the drop: a circle whose radius oscillates with a
/// mode-2 (elliptical squash/stretch) and a mode-3 (triangular ripple) wave —
/// the dominant oscillation modes of a real liquid drop, which is why the
/// result reads as water rather than as a morphing ellipse.
private struct WaterDropShape: Shape {
    var mode2: CGFloat
    var mode2Angle: CGFloat
    var mode3: CGFloat
    var mode3Phase: CGFloat

    func path(in rect: CGRect) -> Path {
        let center = CGPoint(x: rect.midX, y: rect.midY)
        // Resting radius leaves headroom for the deformation (the frame is
        // sized at 3.2× the radius by the caller; 0.625 maps back to 1×).
        let base = min(rect.width, rect.height) / 2 * 0.625
        var path = Path()
        let samples = 96
        for index in 0...samples {
            let theta = CGFloat(index) / CGFloat(samples) * 2 * .pi
            let wave2 = mode2 * cos(2 * (theta - mode2Angle))
            let wave3 = mode3 * cos(3 * theta + mode3Phase)
            let radius = base * (1 + wave2 + wave3)
            let point = CGPoint(x: center.x + cos(theta) * radius, y: center.y + sin(theta) * radius)
            if index == 0 {
                path.move(to: point)
            } else {
                path.addLine(to: point)
            }
        }
        path.closeSubpath()
        return path
    }
}

/// Minimal spring simulation behind `ImportBubbleView`: the drop's center
/// springs toward the finger (or screen center), incoming droplets chase the
/// drop until absorbed, and surface-wobble energy decays over time. A plain
/// class mutated on each `TimelineView` tick — nothing observes it; the
/// timeline drives the redraws.
private final class BubblePhysics {
    struct Droplet: Identifiable {
        let id = UUID()
        var position: CGPoint
        var velocity: CGVector = .zero
        var radius: CGFloat
    }

    /// Everything the renderer needs for one frame of the drop.
    struct DropState {
        var position: CGPoint
        var radius: CGFloat
        var mode2: CGFloat
        var mode2Angle: CGFloat
        var mode3: CGFloat
        var mode3Phase: CGFloat
        var droplets: [Droplet]
    }

    private var position: CGPoint = .zero
    private var velocity: CGVector = .zero
    private var droplets: [Droplet] = []
    /// Extra radius earned by absorbed droplets.
    private var growth: CGFloat = 0
    /// Surface-wobble energy kicked up by merges; decays exponentially.
    private var wobble: CGFloat = 0
    private var lastStepTime: TimeInterval?
    private var bounds: CGSize = .zero
    private var seeded = false

    private static let coreRadius: CGFloat = 118
    private static let maxGrowth: CGFloat = 28
    private static let stiffness: CGFloat = 90
    private static let damping: CGFloat = 7

    /// Advances the simulation to `date` and returns the frame to draw.
    func dropState(at date: Date, attractor: CGPoint?, in size: CGSize) -> DropState {
        bounds = size
        let center = CGPoint(x: size.width / 2, y: size.height / 2)
        if !seeded {
            position = center
            seeded = true
        }
        let time = date.timeIntervalSinceReferenceDate
        let delta = CGFloat(min(time - (lastStepTime ?? time), 1 / 20))
        lastStepTime = time

        if delta > 0 {
            let anchor = clamp(attractor ?? center, in: size)
            integrate(&position, &velocity, toward: anchor, stiffness: Self.stiffness, delta: delta)
            for index in droplets.indices {
                integrate(
                    &droplets[index].position, &droplets[index].velocity,
                    toward: position, stiffness: Self.stiffness / 2, delta: delta
                )
            }
            absorbDroplets()
            wobble *= CGFloat(exp(-2.4 * Double(delta)))
        }
        return makeState(at: time)
    }

    /// Spawns a droplet just outside the screen that springs toward the drop
    /// and merges on contact. No-op before the first frame has run (the
    /// bounds aren't known yet) and capped so droplets can't pile up.
    func spawnDroplet() {
        guard seeded, droplets.count < 8 else { return }
        let center = CGPoint(x: bounds.width / 2, y: bounds.height / 2)
        let angle = Double.random(in: 0..<(2 * .pi))
        let distance = max(bounds.width, bounds.height) / 2 + 60
        droplets.append(Droplet(
            position: CGPoint(x: center.x + cos(angle) * distance, y: center.y + sin(angle) * distance),
            radius: CGFloat.random(in: 13...18)
        ))
    }

    // MARK: Internals

    private func makeState(at time: TimeInterval) -> DropState {
        // A gentle breath so the drop never looks frozen, even untouched.
        let radius = Self.coreRadius + growth + CGFloat(sin(time * 1.2)) * 3

        // The elliptical deformation is the sum of a slowly turning idle
        // wobble and a stretch along the direction of travel. Two cos(2θ)
        // waves sum to a single one, combined here in double-angle space so
        // the transition between idling and dragging is seamless.
        let idleAmp = 0.05 + wobble * 0.4
        let idleAngle = CGFloat(time * 0.35)
        let speed = hypot(velocity.dx, velocity.dy)
        let dragAmp = min(speed / 1500, 0.18)
        let dragAngle = atan2(velocity.dy, velocity.dx)
        let combinedX = idleAmp * cos(2 * idleAngle) + dragAmp * cos(2 * dragAngle)
        let combinedY = idleAmp * sin(2 * idleAngle) + dragAmp * sin(2 * dragAngle)

        return DropState(
            position: position,
            radius: radius,
            mode2: hypot(combinedX, combinedY),
            mode2Angle: atan2(combinedY, combinedX) / 2,
            mode3: 0.03 + wobble * 0.5,
            mode3Phase: CGFloat(time * 1.1),
            droplets: droplets
        )
    }

    /// Semi-implicit Euler with an underdamped spring — enough wobble to feel
    /// like water, settling in under a second.
    private func integrate(
        _ point: inout CGPoint,
        _ velocity: inout CGVector,
        toward target: CGPoint,
        stiffness: CGFloat,
        delta: CGFloat
    ) {
        velocity.dx += ((target.x - point.x) * stiffness - velocity.dx * Self.damping) * delta
        velocity.dy += ((target.y - point.y) * stiffness - velocity.dy * Self.damping) * delta
        point.x += velocity.dx * delta
        point.y += velocity.dy * delta
    }

    private func absorbDroplets() {
        droplets.removeAll { droplet in
            let distance = hypot(droplet.position.x - position.x, droplet.position.y - position.y)
            guard distance < (Self.coreRadius + growth) * 0.6 else { return false }
            growth = min(growth + 3, Self.maxGrowth)
            // Each merge kicks the surface so the drop visibly gulps.
            wobble = min(wobble + 0.12, 0.3)
            return true
        }
    }

    /// Keeps the drop's anchor far enough from the edges that it stays
    /// substantially on screen however hard it's dragged.
    private func clamp(_ point: CGPoint, in size: CGSize) -> CGPoint {
        let inset: CGFloat = 110
        return CGPoint(
            x: min(max(point.x, inset), size.width - inset),
            y: min(max(point.y, inset), size.height - inset)
        )
    }
}

// MARK: - Preview

#Preview("Water drop") {
    ZStack {
        Color(.systemBackground).ignoresSafeArea()
        MorphingCategoryBackground()
        ImportWaterDropView(
            phase: .analyzing,
            statusText: Text(verbatim: "7 values \u{00B7} Kreatinin"),
            parseProgress: ParseProgress(entryCount: 7, latestName: "Kreatinin")
        )
    }
}
