import Foundation

// @unchecked Sendable: struct is passed by value across actor boundaries;
// all mutation stays within @MainActor, so concurrent access cannot occur.
struct LabValue: Identifiable, Equatable, @unchecked Sendable {
    let id: UUID
    var code: String
    var name: String
    var displayValue: String
    var numericValue: Double?
    var unit: String
    var isSelected: Bool
    /// True when `code` was resolved by fuzzy catalog search (from the AI's
    /// normalized name) rather than a confident curated/manual mapping — the
    /// user should confirm it before saving. Cleared once they review it.
    var isSuggestedCode: Bool

    init(
        id: UUID = UUID(),
        code: String,
        name: String,
        displayValue: String,
        numericValue: Double?,
        unit: String,
        isSelected: Bool = true,
        isSuggestedCode: Bool = false
    ) {
        self.id = id
        self.code = code
        self.name = name
        self.displayValue = displayValue
        self.numericValue = numericValue
        self.unit = unit
        self.isSelected = isSelected
        self.isSuggestedCode = isSuggestedCode
    }

    var resolvedName: String {
        let mapped = LabMapping.displayName(for: code)
        return mapped == code ? name : mapped
    }

    static func == (lhs: LabValue, rhs: LabValue) -> Bool {
        lhs.id == rhs.id
            && lhs.isSelected == rhs.isSelected
            && lhs.displayValue == rhs.displayValue
            && lhs.numericValue == rhs.numericValue
            && lhs.isSuggestedCode == rhs.isSuggestedCode
    }

    /// Whether two values are identical in every *saved* field. Unlike `==` it
    /// compares `code`/`name`/`unit` (so a re-mapped code is detected), but it
    /// deliberately ignores `displayValue`: that string is re-derived from
    /// `numericValue` + `unit` when a row appears (unit stripped, decimal
    /// separator normalized), so comparing it would report phantom edits.
    /// `numericValue` is the canonical value and is stable across that step.
    func matchesSavedData(of other: LabValue) -> Bool {
        id == other.id
            && code == other.code
            && name == other.name
            && numericValue == other.numericValue
            && unit == other.unit
            && isSelected == other.isSelected
    }

    /// Dedup ranking: a value that will actually be saved (selected *and*
    /// carrying a numeric result) outranks one that won't, so when two rows
    /// share a LOINC the one holding real, exportable data survives. Ties keep
    /// the earlier row (see `deduplicatedByLoinc`).
    fileprivate var dedupRank: Int {
        if isSelected && numericValue != nil { return 2 }
        if numericValue != nil { return 1 }
        return 0
    }
}

extension Array where Element == LabValue {
    /// Collapses entries that share the same LOINC code so a report never holds
    /// more than one value per LOINC — the app's canonical identity for a test.
    ///
    /// Only entries carrying a *valid* LOINC mapping are deduplicated; rows whose
    /// code is an unmapped mnemonic, `"MANUAL"`, or empty are genuinely distinct
    /// until the user maps them, so they pass through untouched (collapsing them
    /// would merge unrelated tests). Codes are compared by their resolved LOINC
    /// (`LabMapping.loincCode`) so equivalent spellings count as one. Within a
    /// collapsed group the most useful row wins (selected + numeric beats the
    /// rest, see `dedupRank`), and each kept row stays in its first-seen slot.
    func deduplicatedByLoinc() -> [LabValue] {
        var slotForLoinc: [String: Int] = [:]
        var result: [LabValue] = []
        for value in self {
            guard let loinc = LabMapping.loincCode(for: value.code)?.loinc else {
                result.append(value)
                continue
            }
            if let slot = slotForLoinc[loinc] {
                if value.dedupRank > result[slot].dedupRank {
                    result[slot] = value
                }
            } else {
                slotForLoinc[loinc] = result.count
                result.append(value)
            }
        }
        return result
    }
}
