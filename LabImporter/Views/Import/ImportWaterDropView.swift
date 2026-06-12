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
            .float(0.15),
            .floatArray(state.bumps.flatMap { [Float($0.angle), Float($0.height), Float($0.width)] })
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
            mode3Phase: state.mode3Phase,
            bumps: state.bumps
        )
        return ZStack {
            halo(for: state)
            ForEach(state.droplets) { dropletView($0, state: state, time: time) }
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

    /// An incoming streamed-value droplet: a small bead in the brand blue
    /// with a falling drop's squash-and-stretch. Near the drop, surface
    /// tension takes over: the stretch axis swings from the travel direction
    /// toward the surface (necking), the elongation grows, and the bead sinks
    /// in — fully faded by the moment the physics absorbs it, while the
    /// drop's boundary raises a matching bump to meet it.
    private func dropletView(
        _ droplet: BubblePhysics.Droplet,
        state: BubblePhysics.DropState,
        time: TimeInterval
    ) -> some View {
        let toCoreX = state.position.x - droplet.position.x
        let toCoreY = state.position.y - droplet.position.y
        let distance = max(hypot(toCoreX, toCoreY), 1)
        // 0 approaching → 1 touching the surface (mirrors the physics bump).
        let contact = max(0, min(1, 1 - (distance - state.radius) / (droplet.radius * 3.5)))
        let speed = max(hypot(droplet.velocity.dx, droplet.velocity.dy), 1)
        let stretch = 1 + min(speed / 900, 0.55) + 0.45 * contact * contact
        let axisX = droplet.velocity.dx / speed * (1 - contact) + toCoreX / distance * contact
        let axisY = droplet.velocity.dy / speed * (1 - contact) + toCoreY / distance * contact
        let breathe = 1 + 0.06 * sin(time * 3.2 + droplet.phase)
        let submersion = max(0, min(1, (state.radius + droplet.radius - distance) / (2 * droplet.radius)))
        return Ellipse()
            .fill(LabCategory.bloodGas.color.opacity(0.16))
            .overlay(Ellipse().strokeBorder(LabCategory.bloodGas.color.opacity(0.45), lineWidth: 1))
            .frame(
                width: droplet.radius * 2 * stretch * breathe,
                height: droplet.radius * 2 / stretch * breathe
            )
            .rotationEffect(.radians(atan2(axisY, axisX)))
            .opacity(1 - submersion)
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
    /// Local surface-tension bulges (arriving/merging droplets), in points.
    var bumps: [BubblePhysics.SurfaceBump] = []

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
            let radius = base * (1 + wave2 + wave3) + bumpHeight(at: theta)
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

    /// Sum of the Gaussian bumps at `theta`, wrap-aware. Must stay in
    /// lockstep with the bump term in `WaterDrop.metal`.
    private func bumpHeight(at theta: CGFloat) -> CGFloat {
        var total: CGFloat = 0
        for bump in bumps {
            let raw = theta - bump.angle
            let wrapped = atan2(sin(raw), cos(raw))
            total += bump.height * exp(-(wrapped * wrapped) / (2 * bump.width * bump.width))
        }
        return total
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
