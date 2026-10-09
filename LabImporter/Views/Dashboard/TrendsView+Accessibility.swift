import Accessibility
import SwiftUI

// MARK: - Accessibility chart descriptor

/// Backs `.accessibilityChartDescriptor(self)` so VoiceOver can navigate the
/// trend data point-by-point (via the rotor's "Charts" option) in addition to
/// the per-mark labels set on `TrendsView.chartBody`'s `PointMark`s.
extension TrendsView: @MainActor AXChartDescriptorRepresentable {
    func makeChartDescriptor() -> AXChartDescriptor {
        let xValues = dataPoints.map { $0.date.timeIntervalSinceReferenceDate }
        let yValues = dataPoints.map(\.value)

        let xAxis = AXNumericDataAxisDescriptor(
            title: String(localized: "Date"),
            range: (xValues.min() ?? 0)...(xValues.max() ?? 1),
            gridlinePositions: []
        ) { value in
            Date(timeIntervalSinceReferenceDate: value).formatted(date: .abbreviated, time: .omitted)
        }

        let yAxis = AXNumericDataAxisDescriptor(
            title: selectedName,
            range: (yValues.min() ?? 0)...(yValues.max() ?? 1),
            gridlinePositions: []
        ) { [currentUnit] value in
            currentUnit.isEmpty ? self.formatValue(value) : "\(self.formatValue(value)) \(currentUnit)"
        }

        let series = AXDataSeriesDescriptor(
            name: selectedName,
            isContinuous: true,
            dataPoints: dataPoints.map {
                AXDataPoint(x: $0.date.timeIntervalSinceReferenceDate, y: $0.value)
            }
        )

        return AXChartDescriptor(
            title: selectedName,
            summary: nil,
            xAxis: xAxis,
            yAxis: yAxis,
            additionalAxes: [],
            series: [series]
        )
    }
}
