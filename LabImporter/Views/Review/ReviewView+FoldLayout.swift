import SwiftUI

/// The Review screen's width and any vertical fold through it (an unfolded
/// iPhone Duo's hinge), measured to decide between one list and two panes.
struct ReviewPaneGeometry: Equatable {
    var width: CGFloat = 0
    var fold: ClosedRange<CGFloat>?

    /// Each pane needs room for a readable form row.
    static let minimumPaneWidth: CGFloat = 280

    /// Widths of the panes before and after the fold, when it runs through the
    /// screen with room for a pane on both sides; `nil` otherwise.
    var split: Split? {
        guard let fold, fold.lowerBound > 0, fold.upperBound < width else { return nil }
        let leading = fold.lowerBound
        let trailing = width - fold.upperBound
        guard leading >= Self.minimumPaneWidth, trailing >= Self.minimumPaneWidth else { return nil }
        return Split(leading: leading, gap: fold.upperBound - fold.lowerBound, trailing: trailing)
    }

    struct Split: Equatable {
        let leading: CGFloat
        let gap: CGFloat
        let trailing: CGFloat
    }
}

extension ReviewView {
    /// One list — or, when an iPhone Duo's fold runs through the screen, two
    /// panes beside it: the report (summary, patient, author, date) before the
    /// fold and its values, with the Save/Share bar, after it. Without this the
    /// centered form and its buttons sit right across the hinge.
    @ViewBuilder
    var reviewLists: some View {
        Group {
            if let split = paneGeometry.split {
                HStack(spacing: 0) {
                    List {
                        headerSection
                        patientSection
                        authorSection
                        dateSection
                    }
                    .frame(width: split.leading)
                    Spacer(minLength: 0)
                        .frame(width: split.gap)
                    List {
                        valuesSection
                        addValueSection
                        unsupportedSection
                    }
                    .frame(width: split.trailing)
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        actionBar
                    }
                }
            } else {
                List {
                    headerSection
                    patientSection
                    authorSection
                    dateSection
                    valuesSection
                    addValueSection
                    unsupportedSection
                }
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    actionBar
                }
            }
        }
        .onGeometryChange(for: ReviewPaneGeometry.self) { proxy in
            ReviewPaneGeometry(width: proxy.size.width, fold: proxy.verticalFold)
        } action: { paneGeometry = $0 }
    }
}
