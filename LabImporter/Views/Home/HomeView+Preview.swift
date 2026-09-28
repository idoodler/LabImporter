import SwiftUI

// Size classes are overridden so each layout can be inspected on any preview
// device. Onboarding still covers these until its gates are cleared.
#Preview("Compact (iPhone, Duo folded, narrow iPad)") {
    HomeView()
        .environment(\.horizontalSizeClass, .compact)
        .environment(\.verticalSizeClass, .regular)
}

#Preview("Compact – Dark") {
    HomeView()
        .environment(\.horizontalSizeClass, .compact)
        .environment(\.verticalSizeClass, .regular)
        .preferredColorScheme(.dark)
}

#Preview("Compact – Landscape iPhone") {
    HomeView()
        .environment(\.horizontalSizeClass, .regular)
        .environment(\.verticalSizeClass, .compact)
}

#Preview("Regular (iPad, Duo unfolded)") {
    HomeView()
        .environment(\.horizontalSizeClass, .regular)
        .environment(\.verticalSizeClass, .regular)
}

#Preview("Regular – Dark") {
    HomeView()
        .environment(\.horizontalSizeClass, .regular)
        .environment(\.verticalSizeClass, .regular)
        .preferredColorScheme(.dark)
}

#Preview("Regular – RTL") {
    HomeView()
        .environment(\.horizontalSizeClass, .regular)
        .environment(\.verticalSizeClass, .regular)
        .environment(\.layoutDirection, .rightToLeft)
}
