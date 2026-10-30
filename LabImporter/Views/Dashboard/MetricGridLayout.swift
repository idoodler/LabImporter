import SwiftUI

/// Column layout of the dashboard's metric grid.
///
/// - Compact widths (iPhone, folded iPhone Duo): two flexible columns, or one
///   full-width column when there are only one or two metrics, so a lone card
///   isn't stranded in a half-empty row.
/// - Regular widths (iPad, unfolded Duo): as many columns as fit cards of at
///   least `minimumCardWidth`, rounded down to an even count and never fewer
///   than two, so a column gap — not a card — sits in the middle of the grid.
/// - A fold crossing the grid (an unfolded Duo whose grid spans the hinge):
///   the columns are fitted to each side of it separately and the gap between
///   the two sides covers the fold, so no card ever straddles the hinge.
struct MetricGridLayout: Equatable {
    /// Width the grid gets, in points.
    var width: CGFloat = 0
    /// The display's division (an iPhone Duo's fold) in the grid's own
    /// coordinates, if one runs vertically through it.
    var fold: ClosedRange<CGFloat>?

    static let spacing: CGFloat = 14
    static let minimumCardWidth: CGFloat = 200
    /// Cards beside a fold may be narrower than `minimumCardWidth`: a side
    /// that fits one smaller card beats an empty strip or a straddled hinge.
    static let minimumCardWidthBesideFold: CGFloat = 150

    func columns(isRegularWidth: Bool, metricCount: Int) -> [GridItem] {
        guard isRegularWidth else {
            return metricCount <= 2 ? [GridItem(.flexible())] : [GridItem(.flexible()), GridItem(.flexible())]
        }
        if let split = foldSplit {
            return split
        }
        let fitting = Self.columnCount(fitting: width, minimum: Self.minimumCardWidth)
        let even = max(2, fitting - fitting % 2)
        return Array(repeating: GridItem(.flexible(), spacing: Self.spacing), count: even)
    }

    /// Fixed columns on both sides of the fold, the last one before it spaced
    /// by the fold's width. `nil` when there's no fold inside the grid or a
    /// side is too narrow for even one card.
    private var foldSplit: [GridItem]? {
        guard let fold, fold.lowerBound > 0, fold.upperBound < width else { return nil }
        let leading = fold.lowerBound
        let trailing = width - fold.upperBound
        let leadingCount = Self.columnCount(fitting: leading, minimum: Self.minimumCardWidthBesideFold)
        let trailingCount = Self.columnCount(fitting: trailing, minimum: Self.minimumCardWidthBesideFold)
        guard leadingCount > 0, trailingCount > 0 else { return nil }
        let leadingColumns = Self.fixedColumns(count: leadingCount, filling: leading, lastSpacing: fold.upperBound - fold.lowerBound)
        let trailingColumns = Self.fixedColumns(count: trailingCount, filling: trailing, lastSpacing: Self.spacing)
        return leadingColumns + trailingColumns
    }

    private static func columnCount(fitting width: CGFloat, minimum: CGFloat) -> Int {
        max(0, Int((width + spacing) / (minimum + spacing)))
    }

    private static func fixedColumns(count: Int, filling width: CGFloat, lastSpacing: CGFloat) -> [GridItem] {
        let columnWidth = (width - CGFloat(count - 1) * spacing) / CGFloat(count)
        return (0..<count).map { index in
            GridItem(.fixed(columnWidth), spacing: index == count - 1 ? lastSpacing : spacing)
        }
    }
}
