import Foundation

// Reference range printed on a lab report row. Carried per-entry, end-to-end,
// from the parser → LabValue → LabReport.Entry → CDA `<referenceRange>` and
// back. Drives the status pill on the dashboard when the report supplied any
// bounds; otherwise no status is shown.
struct ParsedRange: Codable, Equatable, Hashable {
    var normalLow: Double?
    var normalHigh: Double?
    var borderlineLow: Double?
    var borderlineHigh: Double?
}
