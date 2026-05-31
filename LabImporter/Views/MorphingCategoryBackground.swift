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

    var body: some View {
        ZStack {
            Color(.systemBackground)
            if reduceMotion {
                mesh(at: 0)
                    .opacity(intensity)
            } else {
                TimelineView(.animation) { context in
                    mesh(at: context.date.timeIntervalSinceReferenceDate)
                        .opacity(intensity)
                }
            }
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
        // Greedily fill whatever space we're given so the wash tracks window
        // resizes (iPad multitasking / Stage Manager) and large canvases.
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // A wide blur melts the nine color zones into one another so the result
        // reads as an organic wash rather than a visible grid.
        .blur(radius: 60)
        // Blurring a frame-clipped gradient fades its edges toward transparent,
        // which on a large canvas reads as a dark vignette. Scaling the blurred
        // result up pushes those faded margins off-screen so the color fills
        // edge to edge.
        .scaleEffect(1.4)
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
