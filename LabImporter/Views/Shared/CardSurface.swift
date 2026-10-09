import SwiftUI

/// The app's standard content-layer card surface: a translucent material
/// background with a hairline stroke. Deliberately a *standard* material,
/// not `.glassEffect` — Apple's Liquid Glass guidance reserves that for the
/// navigation/control layer (tab bars, sidebars, toolbars) and transient
/// interactive controls, never for content-layer cards like these.
///
/// Falls back to an opaque background when Reduce Transparency is on, since
/// every call site previously ignored that setting despite relying on the
/// material for legibility of the text sitting on top of it.
private struct CardSurface: ViewModifier {
    var cornerRadius: CGFloat

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        content
            .background(backgroundStyle, in: RoundedRectangle(cornerRadius: cornerRadius))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius)
                    .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
            )
    }

    private var backgroundStyle: AnyShapeStyle {
        reduceTransparency
            ? AnyShapeStyle(Color(.secondarySystemBackground))
            : AnyShapeStyle(.ultraThinMaterial)
    }
}

extension View {
    /// Applies the app's standard rounded card surface — see `CardSurface`.
    func cardSurface(cornerRadius: CGFloat = 20) -> some View {
        modifier(CardSurface(cornerRadius: cornerRadius))
    }
}
