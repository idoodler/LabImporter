import SwiftUI

/// Shared adaptive layout for the full-screen onboarding steps (Welcome,
/// Disclaimer, Health permission, iCloud sync, Unsupported device).
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
    private let hero: Hero
    private let card: Card
    private let footer: Footer

    @Environment(\.verticalSizeClass) private var verticalSizeClass

    init(
        @ViewBuilder hero: () -> Hero,
        @ViewBuilder card: () -> Card,
        @ViewBuilder footer: () -> Footer = { EmptyView() }
    ) {
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

    // MARK: - Portrait (regular height)

    private var portraitLayout: some View {
        GeometryReader { proxy in
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 0) {
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
                    hero
                        .padding(.horizontal, 16)
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
