import Foundation
import LabImporterCore

/// The shared chart rule (`ChartAxis.scale`, zero-based y and time-proportional
/// x; its comment says why) as Swift Charts domains, so a history reads the same
/// on iPhone and on Android, in the trend chart and (`ChartAxis.sparkline`) on the cards.
struct ChartDomain {
    let xDomain: ClosedRange<Date>
    let yDomain: ClosedRange<Double>
    let yTicks: [Double]

    /// The axis' first and last date, or the one date all readings share: what the x axis labels.
    let xLabels: [Date]

    /// `nil` without readings.
    init?(dates: [Date], values: [Double], range: ReferenceRange?) {
        SharedCore.start()
        let points = zip(dates, values).map { date, value in
            LabImporterCore.MetricPoint(date: date.sharedDate, value: value, unit: "", reportId: "")
        }
        guard let scale = LabImporterCore.ChartAxis.shared.scale(points: points, range: range?.shared) else { return nil }
        xDomain = Date(shared: scale.xStart)...Date(shared: scale.xEnd)
        yDomain = scale.yLow...scale.yHigh
        yTicks = scale.yTicks.map(\.doubleValue)
        xLabels = scale.xLabels.map { Date(shared: $0) }
    }

    /// A card's sparkline (`ChartAxis.sparkline`): no axis, so a flat series lies in the middle and a
    /// small change stays small. `nil` without readings.
    init?(sparkline dates: [Date], values: [Double]) {
        SharedCore.start()
        let points = zip(dates, values).map { date, value in
            LabImporterCore.MetricPoint(date: date.sharedDate, value: value, unit: "", reportId: "")
        }
        guard let scale = LabImporterCore.ChartAxis.shared.sparkline(points: points) else { return nil }
        xDomain = Date(shared: scale.xStart)...Date(shared: scale.xEnd)
        yDomain = scale.yLow...scale.yHigh
        yTicks = []
        xLabels = scale.xLabels.map { Date(shared: $0) }
    }
}
