import SwiftUI

/// A slow, gently morphing wash of the app's `LabCategory` palette — the same
/// clinical colors that later tint the Dashboard's metric cards and charts. Used
/// behind onboarding (`WelcomeView`) and the empty-state import screen
/// (`ImportLandingView`) so the very first thing a user sees previews the app's
/// color system.
///
/// The effect is a 3×3 `MeshGradient` with fixed vertices and *animated colors*:
/// each vertex slowly cross-fades through the palette with a per-vertex phase
/// offset, producing a calm drift rather than motion. It is deliberately
/// low-opacity over `Color(.systemBackground)` — in the same restrained spirit
/// as `CategoryBackground` on the Dashboard.
///
/// Respects Reduce Motion: when enabled, it renders a single static frame of the
/// same gradient (no `TimelineView`), so the look stays consistent while the
/// animation is dropped.
struct MorphingCategoryBackground: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// How wide the wash reads. Onboarding can afford a touch more presence than
    /// the content-dense Dashboard, but it stays subtle by design.
    var intensity: Double = 0.20

    /// A curated, color-wheel-ordered subset of the clinical palette so adjacent
    /// frames blend smoothly (blue → teal → green → orange → pink → purple).
    /// Sourced from `LabCategory.color` to keep a single source of truth.
    private static let palette: [Color] = [
        LabCategory.bloodGas.color,     // blue
        LabCategory.electrolytes.color, // teal
        LabCategory.hepatic.color,      // green
        LabCategory.glycemic.color,     // orange
        LabCategory.hematology.color,   // pink
        LabCategory.endocrine.color     // purple
    ]

    /// Static mesh vertices — a regular 3×3 grid. Only the colors animate.
    private static let points: [SIMD2<Float>] = [
        .init(0, 0), .init(0.5, 0), .init(1, 0),
        .init(0, 0.5), .init(0.5, 0.5), .init(1, 0.5),
        .init(0, 1), .init(0.5, 1), .init(1, 1)
    ]

    /// Radius of the softening blur. The mesh is drawn larger than the canvas by
    /// a comfortable multiple of this so the blur's faded edges fall outside the
    /// visible area (see `body`).
    private let blurRadius: CGFloat = 60

    var body: some View {
        // Measure the actual canvas (which, thanks to `ignoresSafeArea` below,
        // is the full screen / window pane) so we can oversize the gradient
        // precisely instead of relying on a fixed scale factor.
        GeometryReader { proxy in
            let size = proxy.size
            ZStack {
                Color(.systemBackground)
                if reduceMotion {
                    mesh(at: 0)
                } else {
                    TimelineView(.animation) { context in
                        mesh(at: context.date.timeIntervalSinceReferenceDate)
                    }
                }
            }
            // Draw the gradient larger than the canvas on every side, then clip
            // back to it. Blurring a gradient fades its edges toward transparent;
            // pushing those edges well beyond the visible bounds means only the
            // fully-saturated interior shows, so the wash reaches every edge.
            .frame(
                width: size.width + blurRadius * 4,
                height: size.height + blurRadius * 4
            )
            .frame(width: size.width, height: size.height)
            .clipped()
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    private func mesh(at time: TimeInterval) -> some View {
        MeshGradient(
            width: 3,
            height: 3,
            points: Self.points,
            colors: (0..<9).map { color(forVertex: $0, time: time) }
        )
        // A wide blur melts the nine color zones into one another so the result
        // reads as an organic wash rather than a visible grid.
        .blur(radius: blurRadius)
        .opacity(intensity)
    }

    /// The color for a given mesh vertex at a moment in time: a continuous
    /// position walked through `palette`, offset per vertex so the nine corners
    /// drift out of phase with one another.
    private func color(forVertex index: Int, time: TimeInterval) -> Color {
        let palette = Self.palette
        let count = palette.count
        // ~90s for a full cycle through the palette — calm, not distracting.
        let speed = 1.0 / 90.0
        let phase = time * speed * Double(count) + Double(index) * 0.6
        let pos = phase.truncatingRemainder(dividingBy: Double(count))
        let lower = Int(pos.rounded(.down)) % count
        let upper = (lower + 1) % count
        let fraction = pos - pos.rounded(.down)
        return palette[lower].mix(with: palette[upper], by: fraction)
    }
}

#Preview {
    MorphingCategoryBackground()
}
