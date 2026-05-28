import Foundation

// Parses printed reference-range strings from German/English lab reports.
// Recognised formats:
//   "70-100"        → low/high
//   "70 - 100"      → low/high (with spaces)
//   "0,4 - 4,0"     → comma decimals normalised to dot
//   "< 200"         → normalHigh only
//   "<= 5.7"        → normalHigh only
//   "> 40"          → normalLow only
//   ">= 90"         → normalLow only
//   "bis 5.7"       → German "up to" → normalHigh
//   "ab 40"         → German "from"  → normalLow
//   "—" / "" / "n/a" → nil
enum ReferenceRangeParser {

    // swiftlint:disable:next cyclomatic_complexity
    static func parse(_ raw: String) -> ParsedRange? {
        let cleaned = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "—", with: "")
            .replacingOccurrences(of: "\u{2013}", with: "-")  // en-dash → hyphen
            .replacingOccurrences(of: "\u{2014}", with: "-")  // em-dash
            .replacingOccurrences(of: "\u{2212}", with: "-")  // unicode minus
        guard !cleaned.isEmpty else { return nil }

        // "(70-100)" → "70-100"
        let stripped = cleaned.trimmingCharacters(in: CharacterSet(charactersIn: "()[]{} \t"))
        if stripped.isEmpty { return nil }

        let normalised = stripped.replacingOccurrences(of: ",", with: ".")

        // <= / < followed by number
        if let match = normalised.firstMatch(of: /^\s*(?:<=?|≤)\s*([0-9]+(?:\.[0-9]+)?)/) {
            guard let high = Double(match.1) else { return nil }
            return .init(normalLow: nil, normalHigh: high, borderlineLow: nil, borderlineHigh: nil)
        }

        // >= / > followed by number
        if let match = normalised.firstMatch(of: /^\s*(?:>=?|≥)\s*([0-9]+(?:\.[0-9]+)?)/) {
            guard let low = Double(match.1) else { return nil }
            return .init(normalLow: low, normalHigh: nil, borderlineLow: nil, borderlineHigh: nil)
        }

        // German "bis X" (up to)
        if let match = normalised.firstMatch(of: /^\s*(?i)bis\s+([0-9]+(?:\.[0-9]+)?)/) {
            guard let high = Double(match.1) else { return nil }
            return .init(normalLow: nil, normalHigh: high, borderlineLow: nil, borderlineHigh: nil)
        }

        // German "ab X" (from)
        if let match = normalised.firstMatch(of: /^\s*(?i)ab\s+([0-9]+(?:\.[0-9]+)?)/) {
            guard let low = Double(match.1) else { return nil }
            return .init(normalLow: low, normalHigh: nil, borderlineLow: nil, borderlineHigh: nil)
        }

        // "low-high" or "low - high"
        if let match = normalised.firstMatch(of: /^\s*([0-9]+(?:\.[0-9]+)?)\s*-\s*([0-9]+(?:\.[0-9]+)?)/) {
            guard let low = Double(match.1), let high = Double(match.2), low <= high else { return nil }
            return .init(normalLow: low, normalHigh: high, borderlineLow: nil, borderlineHigh: nil)
        }

        return nil
    }

    // Splits a trailing string like "mg/dl 70-100" into (unit, range) by looking
    // for the first character that starts a recognised range expression.
    static func splitUnitAndRange(_ trailing: String) -> (unit: String, range: String) {
        let rangeStartPattern = /(\s|^)(?i)(\(|\[|<|≤|>|≥|bis\s|ab\s|[0-9]+(?:[,.][0-9]+)?\s*-\s*[0-9])/
        if let match = trailing.firstMatch(of: rangeStartPattern) {
            let splitIndex = match.range.lowerBound
            let unit = String(trailing[..<splitIndex]).trimmingCharacters(in: .whitespacesAndNewlines)
            let range = String(trailing[splitIndex...]).trimmingCharacters(in: .whitespacesAndNewlines)
            return (unit, range)
        }
        return (trailing.trimmingCharacters(in: .whitespacesAndNewlines), "")
    }
}
