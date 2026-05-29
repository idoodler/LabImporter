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

    /// Whether two values are identical in every user-editable field — unlike
    /// `==`, this also compares `code`/`name`/`unit`, so it detects a re-mapped
    /// code. Used to tell whether a report was actually edited.
    func matchesContent(of other: LabValue) -> Bool {
        id == other.id
            && code == other.code
            && name == other.name
            && displayValue == other.displayValue
            && numericValue == other.numericValue
            && unit == other.unit
            && isSelected == other.isSelected
    }
}
