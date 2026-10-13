import SwiftUI

/// Softly fades out the leading/trailing edge of a horizontal `ScrollView`
/// wherever more content is hidden beyond it, hinting that the row scrolls.
/// An edge whose content is fully visible stays crisp, so a row that fits
/// (or is scrolled all the way to one end) never looks clipped.
private struct EdgeFadeScroll: ViewModifier {
    var fadeWidth: CGFloat

    @State private var edges = HiddenEdges()

    func body(content: Content) -> some View {
        content
            .onScrollGeometryChange(for: HiddenEdges.self) { geometry in
                let offset = geometry.contentOffset.x
                let minOffset = -geometry.contentInsets.leading
                let maxOffset = geometry.contentSize.width + geometry.contentInsets.trailing
                    - geometry.containerSize.width
                return HiddenEdges(leading: offset > minOffset + 1, trailing: offset < maxOffset - 1)
            } action: { _, newValue in
                withAnimation(.easeOut(duration: 0.2)) { edges = newValue }
            }
            .mask {
                HStack(spacing: 0) {
                    LinearGradient(
                        colors: [.black.opacity(edges.leading ? 0 : 1), .black],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: fadeWidth)
                    Rectangle()
                    LinearGradient(
                        colors: [.black, .black.opacity(edges.trailing ? 0 : 1)],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                    .frame(width: fadeWidth)
                }
            }
    }
}

private struct HiddenEdges: Equatable {
    var leading = false
    var trailing = false
}

extension View {
    /// Fades the edges of a horizontal scroll view where content continues
    /// off-screen — see `EdgeFadeScroll`.
    func edgeFadeScroll(width: CGFloat = 24) -> some View {
        modifier(EdgeFadeScroll(fadeWidth: width))
    }
}

private struct EdgeFadePreviewRow: View {
    var count: Int

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(LabCategory.allCases.prefix(count), id: \.self) { category in
                    CategoryChip(category: category, count: 2)
                }
            }
        }
        .edgeFadeScroll()
        .padding(18)
        .cardSurface(cornerRadius: 22)
        .padding()
    }
}

#Preview("Overflowing") {
    EdgeFadePreviewRow(count: .max)
}

#Preview("Fits") {
    EdgeFadePreviewRow(count: 2)
}

#Preview("Dark") {
    EdgeFadePreviewRow(count: .max)
        .preferredColorScheme(.dark)
}

#Preview("RTL") {
    EdgeFadePreviewRow(count: .max)
        .environment(\.layoutDirection, .rightToLeft)
}
