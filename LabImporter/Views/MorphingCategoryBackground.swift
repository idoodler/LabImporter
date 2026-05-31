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
/// ## Seamless mode
/// `NavigationSplitView` paints its columns opaquely, so a single background
/// placed *behind* the split view never shows through the detail column. To span
/// the whole window (sidebar **and** detail) the wash is instead painted as each
/// column's own content background. To keep those independent instances looking
/// like one continuous field — with no seam at the sidebar/detail divider — pass
/// `seamless: true`: the gradient is then sized to the whole window
/// (`\.appWindowSize`) and offset by the view's position within a shared
/// `"appWindow"` coordinate space, so every instance samples the same field.
///
/// Respects Reduce Motion: when enabled, it renders a single static frame of the
/// same gradient (no `TimelineView`), so the look stays consistent while the
/// animation is dropped.
struct MorphingCategoryBackground: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appWindowSize) private var windowSize

    /// How wide the wash reads. Onboarding can afford a touch more presence than
    /// the content-dense Dashboard, but it stays subtle by design.
    var intensity: Double = 0.20

    /// When true, align the field to the shared window coordinate space so
    /// instances in different panes (sidebar / detail) line up seamlessly. When
    /// false (the default), the gradient simply fills its own bounds — correct
    /// for a single full-screen host like the Welcome cover.
    var seamless = false

    /// Name of the coordinate space (declared by `HomeView`) the seamless layout
    /// measures against.
    static let windowSpace = "appWindow"

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
    /// visible area (see `layout`).
    private let blurRadius: CGFloat = 60

    var body: some View {
        GeometryReader { proxy in
            // In seamless mode size the gradient to the whole window and offset
            // it by this view's origin within the window, so separate instances
            // (sidebar + detail) sample one shared field. Otherwise just fill the
            // local bounds.
            let useWindow = seamless && windowSize != .zero
            let canvas = useWindow ? windowSize : proxy.size
            let origin = useWindow ? proxy.frame(in: .named(Self.windowSpace)).origin : .zero
            layout(canvas: canvas, origin: origin, pane: proxy.size)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    /// Draws the (oversized, blurred) gradient for a `canvas`-sized field, shifted
    /// so the window origin lands at `origin` within this pane, then clips to the
    /// pane. Oversizing by the blur radius keeps the faded blur edges off the
    /// visible area so the color reaches every edge.
    private func layout(canvas: CGSize, origin: CGPoint, pane: CGSize) -> some View {
        let margin = blurRadius * 4
        return ZStack {
            Color(.systemBackground)
            if reduceMotion {
                mesh(at: 0)
            } else {
                TimelineView(.animation) { context in
                    mesh(at: context.date.timeIntervalSinceReferenceDate)
                }
            }
        }
        .frame(width: canvas.width + margin, height: canvas.height + margin)
        .offset(x: -origin.x - margin / 2, y: -origin.y - margin / 2)
        .frame(width: pane.width, height: pane.height, alignment: .topLeading)
        .clipped()
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

// MARK: - Window size environment

/// The size of the app's top-level window, published by `HomeView` so the
/// seamless background can size itself to the whole window from within an
/// individual split-view column.
private struct AppWindowSizeKey: EnvironmentKey {
    static let defaultValue: CGSize = .zero
}

extension EnvironmentValues {
    var appWindowSize: CGSize {
        get { self[AppWindowSizeKey.self] }
        set { self[AppWindowSizeKey.self] = newValue }
    }
}

#Preview {
    MorphingCategoryBackground()
}
