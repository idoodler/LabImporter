import Foundation

/// A user-defined reference (normal) range for a single LOINC code, used to flag
/// results as out of range across the app. LOINC deliberately ships **no**
/// reference ranges — they depend on the lab's instrument, method, population,
/// age and sex — so there is no bundled default to fall back to. Ranges are
/// therefore purely the user's own: set per code in the dashboard's Sort &
/// Visibility editor (or the trends screen), stored alongside nicknames in
/// `LabDisplayPreferences`, and synced via iCloud. Like nicknames, a range is a
/// cosmetic display preference — it is **never** written to the exported CDA.
///
/// Either bound may be `nil` to express a one-sided limit (e.g. only an upper
/// bound for a marker that has no meaningful floor). An all-`nil` range is
/// "empty" and is treated as no range at all (see `LabDisplayPreferences`).
struct ReferenceRange: Codable, Equatable {
    /// Lower bound, inclusive. `nil` means "no lower limit".
    var low: Double?
    /// Upper bound, inclusive. `nil` means "no upper limit".
    var high: Double?

    /// A range that constrains nothing — equivalent to having no range. Used to
    /// decide whether to persist or clear an entry.
    var isEmpty: Bool { low == nil && high == nil }

    /// Classifies `value` against this range. Bounds are inclusive, so a value
    /// equal to a limit counts as in range.
    func status(for value: Double) -> RangeStatus {
        if let high, value > high { return .high }
        if let low, value < low { return .low }
        return .normal
    }

    /// A localized, human-readable rendering of the range for subtitles and chart
    /// labels: `3.5–5.3`, `≤ 5.3` (upper bound only), or `≥ 3.5` (lower only),
    /// with `unit` appended when given. Numbers use the current locale's decimal
    /// separator. Returns an empty string for an empty range.
    func formatted(unit: String = "") -> String {
        let suffix = unit.isEmpty ? "" : " \(unit)"
        let body: String
        switch (low, high) {
        case let (low?, high?): body = "\(Self.number(low))–\(Self.number(high))"
        case let (nil, high?):  body = "≤ \(Self.number(high))"
        case let (low?, nil):   body = "≥ \(Self.number(low))"
        case (nil, nil):        return ""
        }
        return body + suffix
    }

    private static let formatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 4
        return formatter
    }()

    private static func number(_ value: Double) -> String {
        formatter.string(from: value as NSNumber) ?? String(value)
    }
}

/// Where a result sits relative to its reference range. `.normal` covers both an
/// in-range value and the absence of either bound on that side.
enum RangeStatus: Equatable {
    case low
    case normal
    case high

    /// True for `.low`/`.high` — the cases the UI surfaces with a badge.
    var isOutOfRange: Bool { self != .normal }
}
