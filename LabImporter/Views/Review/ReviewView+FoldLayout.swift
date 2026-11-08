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

        /// The values get the wider pane: comparing them is what the screen is for.
        /// With an even fold they stay after the summary, in reading order.
        var valuesLead: Bool { leading > trailing }
    }
}

extension ReviewView {
    /// One list in the order of the job — summary and date, the values to compare
    /// with the report, then patient and lab folded away — or, when an iPhone
    /// Duo's fold runs through the screen, two panes beside it: the report
    /// (summary, date, patient, author) and its values with the Save/Share bar.
    /// Without this the centered form and its buttons sit right across the hinge.
    @ViewBuilder
    var reviewLists: some View {
        Group {
            if let split = paneGeometry.split {
                HStack(spacing: 0) {
                    if split.valuesLead {
                        valuesPane.frame(width: split.leading)
                        Spacer(minLength: 0).frame(width: split.gap)
                        reportPane.frame(width: split.trailing)
                    } else {
                        reportPane.frame(width: split.leading)
                        Spacer(minLength: 0).frame(width: split.gap)
                        valuesPane.frame(width: split.trailing)
                    }
                }
            } else {
                List {
                    headerSection
                    dateSection
                    valuesSection
                    addValueSection
                    unsupportedSection
                    detailsSection
                    actionBarRow
                }
                // On the lists, not around them: it would turn the bar's prominent button's label dark too.
                .readableListText()
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    actionBar
                }
            }
        }
        .onGeometryChange(for: ReviewPaneGeometry.self) { proxy in
            ReviewPaneGeometry(width: proxy.size.width, fold: proxy.verticalFold)
        } action: { paneGeometry = $0 }
    }

    /// There's room for patient and lab here, so they aren't folded away.
    private var reportPane: some View {
        List {
            headerSection
            dateSection
            patientSection
            authorSection
        }
        .readableListText()
    }

    private var valuesPane: some View {
        List {
            valuesSection
            addValueSection
            unsupportedSection
            actionBarRow
        }
        .readableListText()
        .safeAreaInset(edge: .bottom, spacing: 0) {
            actionBar
        }
    }
}
