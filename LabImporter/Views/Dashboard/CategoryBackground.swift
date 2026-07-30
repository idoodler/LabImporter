import SwiftUI

// MARK: - CategoryBackground

/// A soft, low-opacity wash of the dashboard's metric category colors — a subtle
/// tint behind the content in the spirit of the Health app. Falls back to a
/// gentle accent tint when there are no metrics yet.
struct CategoryBackground: View {
    let colors: [Color]

    private let anchors: [UnitPoint] = [.topLeading, .topTrailing, .bottomLeading]

    var body: some View {
        ZStack {
            ForEach(Array(palette.enumerated()), id: \.offset) { index, color in
                RadialGradient(
                    colors: [color.opacity(0.16), .clear],
                    center: anchors[index % anchors.count],
                    startRadius: 0,
                    endRadius: 480
                )
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    private var palette: [Color] {
        colors.isEmpty ? [Color.accentColor.opacity(0.6)] : colors
    }
}

// MARK: - Previews

#if DEBUG
#Preview("Multiple Categories") {
    CategoryBackground(colors: [LabCategory.glycemic.color, LabCategory.lipids.color, LabCategory.renal.color])
}

#Preview("Fallback") {
    CategoryBackground(colors: [])
}

#Preview("Dark") {
    CategoryBackground(colors: [LabCategory.glycemic.color, LabCategory.lipids.color, LabCategory.renal.color])
        .preferredColorScheme(.dark)
}
#endif
