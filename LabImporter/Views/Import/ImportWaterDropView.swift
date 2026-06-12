import SwiftUI

// MARK: - Interactive water drop

/// The interactive water drop: a transparent lens of "liquid" — modeled on
/// Image Playground's generation drop — that refracts whatever sits behind it
/// through the `waterDrop` Metal shader. The title/status block sits behind
/// the drop and is read through the lens: magnified and bent, chromatically
/// fringed at the rim, warping as the drop wobbles or is dragged across it.
///
/// The drop idles with the slow squash-and-stretch of a droplet in zero
/// gravity (mode-2 + mode-3 surface waves), elongates along its direction of
/// travel when dragged (anywhere on screen) and springs back when released.
/// Each lab value streamed out of the on-device model arrives as a small
/// droplet that flies in and merges, kicking a visible wobble into the
/// surface. The text block carries the accessibility; the optics are
/// decoration and hidden from it.
struct ImportWaterDropView: View {
    let phase: ImportPhase
    let statusText: Text
    var parseProgress: ParseProgress?

    @State private var physics = BubblePhysics()
    @GestureState private var fingerLocation: CGPoint?

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation(minimumInterval: 1 / 40)) { timeline in
                let time = timeline.date.timeIntervalSinceReferenceDate
                let state = physics.dropState(at: timeline.date, attractor: fingerLocation, in: geo.size)
                ZStack {
                    refractingContent(in: geo.size)
                        // Flattened so the shader bends wash and text as one image.
                        .compositingGroup()
                        .layerEffect(dropShader(for: state), maxSampleOffset: CGSize(width: 90, height: 90))
                    dropOverlays(state, time: time)
                }
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

    // MARK: Refracted layer

    /// Everything the drop can refract: the app's category wash and the
    /// title/status block sitting *behind* the drop's resting place, so the
    /// text is read through the lens — magnified at rest, smeared and fringed
    /// while the drop wobbles, and revealed plain when it's dragged away.
    private func refractingContent(in size: CGSize) -> some View {
        ZStack {
            MorphingCategoryBackground()
            textBlock
                .position(x: size.width / 2, y: size.height / 2)
        }
        .frame(width: size.width, height: size.height)
    }

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
        .frame(maxWidth: 260)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private func dropShader(for state: BubblePhysics.DropState) -> Shader {
        ShaderLibrary.waterDrop(
            .float2(state.position),
            .float(state.radius),
            .float2(state.mode2, state.mode2Angle),
            .float2(state.mode3, state.mode3Phase),
            .float(0.15)
        )
    }

    // MARK: Painted lighting

    /// The lighting the lens can't produce from refraction alone: a whisper
    /// of frost, volume shading, a soft specular, the wandering chromatic rim
    /// glints, incoming droplets, and an ambient halo grounding the drop.
    private func dropOverlays(_ state: BubblePhysics.DropState, time: TimeInterval) -> some View {
        let side = state.radius * 3.2
        let shape = WaterDropShape(
            mode2: state.mode2,
            mode2Angle: state.mode2Angle,
            mode3: state.mode3,
            mode3Phase: state.mode3Phase
        )
        return ZStack {
            halo(for: state)
            ForEach(state.droplets) { dropletView($0, time: time) }
            ZStack {
                // A whisper of the hero gradient inside the glass — the same
                // blue→purple as the landing hero circle — so the drop is
                // unmistakably this app's, while staying clearly transparent.
                shape
                    .fill(Self.heroGradient.opacity(0.12))
                shape
                    .fill(
                        RadialGradient(
                            colors: [.clear, .black.opacity(0.04)],
                            center: UnitPoint(x: 0.42, y: 0.34),
                            startRadius: side * 0.18,
                            endRadius: side * 0.52
                        )
                    )
                Ellipse()
                    .fill(.white.opacity(0.28))
                    .frame(width: state.radius * 0.9, height: state.radius * 0.45)
                    .blur(radius: 16)
                    .position(x: side / 2 - state.radius * 0.25, y: side / 2 - state.radius * 0.5)
                // Constant brand-colored rim, with the wandering glints on top.
                shape
                    .stroke(Self.heroGradient.opacity(0.30), lineWidth: 1.2)
                shape
                    .stroke(glintGradient(at: time), lineWidth: 2)
                    .blur(radius: 1)
            }
            .frame(width: side, height: side)
            .shadow(color: LabCategory.endocrine.color.opacity(0.22), radius: 24, y: 10)
            .position(state.position)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// The landing hero's gradient — the drop's brand tint.
    private static let heroGradient = LinearGradient(
        colors: [LabCategory.bloodGas.color, LabCategory.endocrine.color],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    /// A soft ambient halo in the hero's purple that travels with the drop —
    /// the same glow the landing hero circle casts.
    private func halo(for state: BubblePhysics.DropState) -> some View {
        RadialGradient(
            colors: [LabCategory.endocrine.color.opacity(0.10), .clear],
            center: .center,
            startRadius: state.radius * 0.5,
            endRadius: state.radius * 2.2
        )
        .frame(width: state.radius * 4.4, height: state.radius * 4.4)
        .position(state.position)
    }

    /// An incoming streamed-value droplet: a small bead in the brand blue,
    /// with a falling drop's squash-and-stretch — elongated along its
    /// direction of travel, thinned across it (area-conserving), and gently
    /// breathing so it never reads as a rigid token.
    private func dropletView(_ droplet: BubblePhysics.Droplet, time: TimeInterval) -> some View {
        let speed = hypot(droplet.velocity.dx, droplet.velocity.dy)
        let stretch = 1 + min(speed / 900, 0.55)
        let breathe = 1 + 0.06 * sin(time * 3.2 + droplet.phase)
        return Ellipse()
            .fill(LabCategory.bloodGas.color.opacity(0.16))
            .overlay(Ellipse().strokeBorder(LabCategory.bloodGas.color.opacity(0.45), lineWidth: 1))
            .frame(
                width: droplet.radius * 2 * stretch * breathe,
                height: droplet.radius * 2 / stretch * breathe
            )
            .rotationEffect(.radians(atan2(droplet.velocity.dy, droplet.velocity.dx)))
            .shadow(color: .black.opacity(0.08), radius: 6, y: 3)
            .position(droplet.position)
    }

    /// Mostly-transparent angular gradient with two short slivers drawn from
    /// the app's category palette, rotated slowly over time so the glints
    /// wander around the rim.
    private func glintGradient(at time: TimeInterval) -> AngularGradient {
        AngularGradient(
            stops: [
                .init(color: .clear, location: 0),
                .init(color: .clear, location: 0.50),
                .init(color: LabCategory.bloodGas.color.opacity(0.55), location: 0.54),
                .init(color: LabCategory.electrolytes.color.opacity(0.45), location: 0.57),
                .init(color: .clear, location: 0.61),
                .init(color: .clear, location: 0.84),
                .init(color: LabCategory.endocrine.color.opacity(0.45), location: 0.87),
                .init(color: LabCategory.hematology.color.opacity(0.40), location: 0.89),
                .init(color: .clear, location: 0.92),
                .init(color: .clear, location: 1)
            ],
            center: .center,
            angle: .radians(time * 0.25)
        )
    }
}

/// The wobbling outline of the drop: a circle whose radius oscillates with a
/// mode-2 (elliptical squash/stretch) and a mode-3 (triangular ripple) wave —
/// the dominant oscillation modes of a real liquid drop, which is why the
/// result reads as water rather than as a morphing ellipse. Must stay in
/// lockstep with the boundary formula in `WaterDrop.metal`.
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

/// Minimal spring simulation behind `ImportWaterDropView`: the drop's center
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
        /// Phase offset for this droplet's wander and breathing, so no two
        /// droplets move in lockstep.
        let phase = Double.random(in: 0..<(2 * .pi))
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
                // Copy out/in: inout projections into the same array element
                // would overlap exclusive access to `droplets`.
                var droplet = droplets[index]
                integrateDroplet(&droplet, time: time, delta: delta)
                droplets[index] = droplet
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
        let spawn = CGPoint(x: center.x + cos(angle) * distance, y: center.y + sin(angle) * distance)
        // Launch with mostly *tangential* velocity so the droplet arcs around
        // toward the drop instead of beelining at it — water curls, it
        // doesn't travel in straight rays.
        let inwardX = (center.x - spawn.x) / distance
        let inwardY = (center.y - spawn.y) / distance
        let tangential = (Bool.random() ? 1 : -1) * CGFloat.random(in: 220...420)
        droplets.append(Droplet(
            position: spawn,
            velocity: CGVector(
                dx: inwardX * 140 - inwardY * tangential,
                dy: inwardY * 140 + inwardX * tangential
            ),
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

    /// Water-like droplet motion: a lazy, slightly serpentine drift while far
    /// from the drop, then a surface-tension "grab" whose pull ramps up
    /// steeply as the droplet nears the surface and swoops it in. The wander
    /// fades out as the grab takes over so the final approach is clean.
    private func integrateDroplet(_ droplet: inout Droplet, time: TimeInterval, delta: CGFloat) {
        let toCoreX = position.x - droplet.position.x
        let toCoreY = position.y - droplet.position.y
        let distance = max(hypot(toCoreX, toCoreY), 1)
        let surface = Self.coreRadius + growth
        let proximity = max(0, 1 - max(0, distance - surface) / (surface * 1.8))

        let drive = (8 + 110 * proximity * proximity) * min(distance, 280)
        let wander = CGFloat(sin(time * 2.1 + droplet.phase)) * 1400 * (1 - proximity)
        let dampingDrag: CGFloat = 5

        let directionX = toCoreX / distance
        let directionY = toCoreY / distance
        droplet.velocity.dx += (directionX * drive - directionY * wander - droplet.velocity.dx * dampingDrag) * delta
        droplet.velocity.dy += (directionY * drive + directionX * wander - droplet.velocity.dy * dampingDrag) * delta
        droplet.position.x += droplet.velocity.dx * delta
        droplet.position.y += droplet.velocity.dy * delta
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
    ImportWaterDropView(
        phase: .analyzing,
        statusText: Text(verbatim: "7 values \u{00B7} Kreatinin"),
        parseProgress: ParseProgress(entryCount: 7, latestName: "Kreatinin")
    )
}

#Preview("Water drop — Dark") {
    ImportWaterDropView(
        phase: .analyzing,
        statusText: Text(verbatim: "12 values \u{00B7} HbA1c"),
        parseProgress: ParseProgress(entryCount: 12, latestName: "HbA1c")
    )
    .preferredColorScheme(.dark)
}
