import SwiftUI

/// A screen with nothing to list: why, and what to do about it. The app's text colours
/// and a scroll view are why this isn't `ContentUnavailableView`, whose secondary text
/// falls short of 4.5:1 over a wash and which cuts off at the largest text sizes.
struct EmptyStateView<Actions: View>: View {
    let title: LocalizedStringKey
    let systemImage: String
    let message: LocalizedStringKey
    @ViewBuilder let actions: Actions

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 12) {
                    Image(systemName: systemImage)
                        .font(.largeTitle)
                        .foregroundStyle(.textSecondary)
                        .accessibilityHidden(true)
                    Text(title)
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.textSecondary)
                        .multilineTextAlignment(.center)
                    actions
                        .padding(.top, 8)
                }
                .padding(.horizontal, 32)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
    }
}

#Preview("With Action") {
    EmptyStateView(title: "Couldn't Load Your Reports", systemImage: "exclamationmark.triangle",
                   message: "Your saved reports weren't changed, but they can't be read right now.") {
        Button("Try Again") {}
            .buttonStyle(.borderedProminent)
            .tint(.prominentFill)
    }
}

#Preview("Without Action") {
    EmptyStateView(title: "Not Enough Data", systemImage: "chart.xyaxis.line",
                   message: "Import at least two reports containing this value to see a trend.") { EmptyView() }
}

#Preview("Dark") {
    EmptyStateView(title: "No Reports Yet", systemImage: "doc.text.magnifyingglass",
                   message: "Import a lab report and save it to Apple Health to see it here.") { EmptyView() }
        .preferredColorScheme(.dark)
}

#Preview("Largest Text") {
    EmptyStateView(title: "No Reports Yet", systemImage: "doc.text.magnifyingglass",
                   message: "Import a lab report and save it to Apple Health to see it here.") {
        Button("Try Again") {}
            .buttonStyle(.borderedProminent)
            .tint(.prominentFill)
    }
    .dynamicTypeSize(.accessibility5)
}

#Preview("RTL") {
    EmptyStateView(title: "No Reports Yet", systemImage: "doc.text.magnifyingglass",
                   message: "Import a lab report and save it to Apple Health to see it here.") { EmptyView() }
        .environment(\.layoutDirection, .rightToLeft)
}
