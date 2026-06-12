import SwiftUI

// MARK: - Water drop physics

/// Minimal spring simulation behind `ImportWaterDropView`: the drop's center
/// springs toward the finger (or screen center), incoming droplets chase the
/// drop until absorbed, and surface-wobble energy decays over time. A plain
/// class mutated on each `TimelineView` tick — nothing observes it; the
/// timeline drives the redraws.
final class BubblePhysics {
    struct Droplet: Identifiable {
        let id = UUID()
        var position: CGPoint
        var velocity: CGVector = .zero
        var radius: CGFloat
        /// Phase offset for this droplet's wander and breathing, so no two
        /// droplets move in lockstep.
        let phase = Double.random(in: 0..<(2 * .pi))
    }

    /// A local outward bulge on the drop's surface where a droplet is
    /// arriving or has just merged — the visible half of surface tension.
    /// `angle` is the bump's bearing from the drop's center, `height` is in
    /// screen points, `width` is the angular half-width in radians.
    struct SurfaceBump {
        var angle: CGFloat
        var height: CGFloat
        var width: CGFloat
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
        var bumps: [SurfaceBump]
    }

    private var position: CGPoint = .zero
    private var velocity: CGVector = .zero
    private var droplets: [Droplet] = []
    /// Extra radius earned by absorbed droplets.
    private var growth: CGFloat = 0
    /// Surface-wobble energy kicked up by merges; decays exponentially.
    private var wobble: CGFloat = 0
    /// Bumps left behind by absorbed droplets, relaxing away exponentially.
    private var residualBumps: [SurfaceBump] = []
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
            for index in residualBumps.indices {
                residualBumps[index].height *= CGFloat(exp(-3.0 * Double(delta)))
            }
            residualBumps.removeAll { $0.height < 0.5 }
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
            droplets: droplets,
            bumps: surfaceBumps(surface: radius)
        )
    }

    /// The surface reaching out: every droplet near the rim raises a local
    /// bulge that grows as it closes in, plus the relaxing residuals of
    /// droplets already swallowed.
    private func surfaceBumps(surface: CGFloat) -> [SurfaceBump] {
        var bumps = residualBumps
        for droplet in droplets {
            let offsetX = droplet.position.x - position.x
            let offsetY = droplet.position.y - position.y
            let distance = max(hypot(offsetX, offsetY), 1)
            let contact = max(0, min(1, 1 - (distance - surface) / (droplet.radius * 3.5)))
            guard contact > 0 else { continue }
            bumps.append(SurfaceBump(
                angle: atan2(offsetY, offsetX),
                height: droplet.radius * 1.3 * contact * contact,
                width: bumpWidth(for: droplet.radius, surface: surface)
            ))
        }
        return bumps
    }

    private func bumpWidth(for radius: CGFloat, surface: CGFloat) -> CGFloat {
        max(0.18, min(0.6, radius * 2.4 / surface))
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
        let surface = Self.coreRadius + growth
        droplets.removeAll { droplet in
            let distance = hypot(droplet.position.x - position.x, droplet.position.y - position.y)
            // Absorbed once fully submerged — by which point the droplet has
            // faded out and its bump hands off to a relaxing residual.
            guard distance < surface - droplet.radius else { return false }
            growth = min(growth + 3, Self.maxGrowth)
            // Each merge kicks the surface so the drop visibly gulps.
            wobble = min(wobble + 0.12, 0.3)
            residualBumps.append(SurfaceBump(
                angle: atan2(droplet.position.y - position.y, droplet.position.x - position.x),
                height: droplet.radius * 1.3,
                width: bumpWidth(for: droplet.radius, surface: surface)
            ))
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
