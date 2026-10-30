import SwiftUI
import Testing
@testable import LabImporter

struct MetricGridLayoutTests {
    private func widths(_ columns: [GridItem]) -> [CGFloat] {
        columns.compactMap { if case .fixed(let width) = $0.size { width } else { nil } }
    }

    @Test func compactWidthsUseTwoColumnsOrOneForFewMetrics() {
        let layout = MetricGridLayout(width: 434)
        #expect(layout.columns(isRegularWidth: false, metricCount: 6).count == 2)
        #expect(layout.columns(isRegularWidth: false, metricCount: 2).count == 1)
    }

    @Test func regularWidthsUseAnEvenColumnCountOfAtLeastTwo() {
        // A half-open Duo's detail column: too narrow for two 200 pt cards, still two columns.
        #expect(MetricGridLayout(width: 339).columns(isRegularWidth: true, metricCount: 6).count == 2)
        // Fits three cards, rounded down to two.
        #expect(MetricGridLayout(width: 660).columns(isRegularWidth: true, metricCount: 6).count == 2)
        // An unfolded Duo with the sidebar hidden fits four.
        #expect(MetricGridLayout(width: 919).columns(isRegularWidth: true, metricCount: 6).count == 4)
    }

    @Test func columnsSitOnEitherSideOfAFold() {
        // Measured on the iPhone Duo simulator, unfolded, sidebar showing:
        // a 555 pt grid with the fold's 40 pt strip 159.5 pt from its leading edge.
        let columns = MetricGridLayout(width: 555, fold: 159.5...199.5)
            .columns(isRegularWidth: true, metricCount: 6)
        #expect(columns.count == 3)
        let widths = widths(columns)
        #expect(widths.count == 3)
        // One column fills the space before the fold, its gap covers the fold…
        #expect(abs(widths[0] - 159.5) < 0.01)
        #expect(columns[0].spacing == 40)
        // …and two fill the space after it.
        #expect(abs(widths[1] + MetricGridLayout.spacing + widths[2] - 355.5) < 0.01)
    }

    @Test func aFoldOutsideTheGridOrBesideTooLittleSpaceIsIgnored() {
        // Half-open: the fold lies left of the detail column.
        let outside = MetricGridLayout(width: 339, fold: -56...(-16)).columns(isRegularWidth: true, metricCount: 6)
        #expect(outside.count == 2)
        #expect(widths(outside).isEmpty)
        // Not even one card fits before the fold.
        let narrow = MetricGridLayout(width: 555, fold: 60...100).columns(isRegularWidth: true, metricCount: 6)
        #expect(narrow.count == 2)
        #expect(widths(narrow).isEmpty)
    }
}
