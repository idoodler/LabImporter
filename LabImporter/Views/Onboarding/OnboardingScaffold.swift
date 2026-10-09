import SwiftUI

/// Shared adaptive layout for the full-screen onboarding steps (Welcome,
/// Disclaimer, Health permission, the combined Extras opt-in, Unsupported
/// device).
///
/// All of those screens are built from the same three pieces — a `hero`
/// (large icon + title + subtitle), a `card` of feature/benefit rows, and a
/// `footer` (optional note + action buttons). This container arranges them so
/// they never clip:
///
/// - **Regular height** (portrait, and iPad in any orientation): the original
///   single centred column — hero on top, card in the middle, footer pinned to
///   the bottom.
/// - **Compact height** (`verticalSizeClass == .compact`, i.e. iPhone
///   landscape): a two-column split — the hero on the left, the card + footer
///   on the right — so the short landscape height no longer squeezes the
///   bottom button off-screen.
///
/// Both layouts fall back to scrolling when the content still doesn't fit, so
/// even the smallest devices keep every control reachable. Entrance animations
/// live in the slot views, so they keep working regardless of which layout is
/// active.
struct OnboardingScaffold<Hero: View, Card: View, Footer: View>: View {
    private let step: Int?
    private let totalSteps: Int
    private let onBack: (() -> Void)?
    private let hero: Hero
    private let card: Card
    private let footer: Footer

    @Environment(\.verticalSizeClass) private var verticalSizeClass

    /// - Parameters:
    ///   - step: This screen's 1-based position in the mandatory onboarding
    ///     sequence, shown as "Step `step` of `totalSteps`". `nil` (the
    ///     default) hides the indicator entirely — most callers outside the
    ///     mandatory flow (e.g. `UnsupportedDeviceView`) want that.
    ///   - onBack: When non-`nil`, shows a leading back button above `hero`
    ///     that calls it. `nil` (the default, and always on the first step)
    ///     hides the button rather than disabling it, since there's nowhere
    ///     to go back to.
    init(
        step: Int? = nil,
        totalSteps: Int = 1,
        onBack: (() -> Void)? = nil,
        @ViewBuilder hero: () -> Hero,
        @ViewBuilder card: () -> Card,
        @ViewBuilder footer: () -> Footer = { EmptyView() }
    ) {
        self.step = step
        self.totalSteps = totalSteps
        self.onBack = onBack
        self.hero = hero()
        self.card = card()
        self.footer = footer()
    }

    private var isCompactHeight: Bool { verticalSizeClass == .compact }

    var body: some View {
        if isCompactHeight {
            landscapeLayout
        } else {
            portraitLayout
        }
    }

    // MARK: - Progress / back

    /// Reserves its own layout space above `hero` (rather than an overlay) so
    /// the back button and step indicator never compete with the hero icon/
    /// title for the same pixels. Within the mandatory flow this always
    /// renders — even on step 1, with no back button — so its height stays
    /// constant and `hero` doesn't jump as `onBack` appears/disappears across
    /// steps; callers that pass neither `step` nor `onBack` get no row at all,
    /// unchanged from before this existed.
    @ViewBuilder
    private var progressRow: some View {
        if step != nil || onBack != nil {
            HStack {
                Group {
                    if let onBack {
                        Button(action: onBack) {
                            Image(systemName: "chevron.backward")
                                .font(.body.weight(.semibold))
                        }
                        .accessibilityLabel("Back")
                    } else {
                        Color.clear
                    }
                }
                .frame(width: 44, height: 44)

                Spacer(minLength: 0)

                if let step {
                    Text("Step \(step) of \(totalSteps)")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)
                Color.clear.frame(width: 44, height: 44)
            }
            .padding(.horizontal, 8)
        }
    }

    // MARK: - Portrait (regular height)

    private var portraitLayout: some View {
        GeometryReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 0) {
                    progressRow
                    Spacer(minLength: 16)
                    hero
                    Spacer(minLength: 16)
                    card
                        .frame(maxWidth: 480)
                        .padding(.horizontal, 24)
                    Spacer(minLength: 16)
                    footer
                        .frame(maxWidth: 480)
                        .padding(.horizontal, 24)
                        .padding(.bottom, 48)
                }
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
    }

    // MARK: - Landscape (compact height)

    private var landscapeLayout: some View {
        GeometryReader { proxy in
            HStack(spacing: 0) {
                column(centredIn: proxy.size.height) {
                    VStack(spacing: 0) {
                        progressRow
                        hero
                            .padding(.horizontal, 16)
                    }
                }
                column(centredIn: proxy.size.height) {
                    VStack(spacing: 20) {
                        card
                        footer
                    }
                    .frame(maxWidth: 480)
                    .padding(.horizontal, 24)
                }
            }
        }
    }

    /// A scrolling half-width column whose content is vertically centred while
    /// it fits, and scrolls once it doesn't.
    private func column<Content: View>(
        centredIn height: CGFloat,
        @ViewBuilder _ content: () -> Content
    ) -> some View {
        ScrollView(.vertical, showsIndicators: false) {
            content()
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity, minHeight: height)
        }
        .frame(maxWidth: .infinity)
    }
}
