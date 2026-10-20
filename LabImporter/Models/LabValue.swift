import Foundation
import LabImporterCore

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

    /// Whether this row would collide with another exportable row on the same
    /// LOINC, given the report's set of duplicated codes (`duplicateLoincCodes`).
    /// Only selected, numeric, LOINC-mapped rows can collide. Drives the
    /// "Duplicate" badge in `LabValueRowView`. (Shared rule — see LabImporterCore.)
    func isDuplicate(in duplicateLoincCodes: Set<String>) -> Bool {
        shared.isDuplicate(duplicateLoincCodes: duplicateLoincCodes)
    }
}

extension Array where Element == LabValue {
    /// The LOINC codes carried by more than one *exportable* entry — rows that
    /// are selected, numeric and LOINC-mapped, exactly what would be written to
    /// the CDA. Two such rows sharing a LOINC is a duplicate the user must
    /// resolve before saving; the review screen surfaces them instead of
    /// silently merging. Shared with Android (`LabImporterCore`).
    func duplicateLoincCodes() -> Set<String> {
        LabValueKt.duplicateLoincCodes(map(\.shared))
    }

    /// Collapses entries that share a LOINC so a report never holds more than
    /// one value per LOINC — the **last-resort guarantee** at the CDA save/share
    /// boundary (the review UI blocks saving duplicates, so normally nothing
    /// collapses). Unmapped rows pass through; within a group the row holding
    /// real, exportable data wins and keeps the first-seen slot. Shared rule.
    func deduplicatedByLoinc() -> [LabValue] {
        LabValueKt.deduplicatedByLoinc(map(\.shared)).map(LabValue.init(shared:))
    }

    /// Exportable codes (selected, numeric, LOINC-mapped) this save is about to
    /// introduce that Siri hasn't been asked about yet — the set
    /// `NewValuesSiriPromptView` should offer. Empty whenever there's nothing
    /// to decide: Siri is off, or `allowAllValues` already covers everything.
    func undecidedSiriCodes(prefs: SiriExposurePreferences) -> [CodeName] {
        guard prefs.isEnabled, !prefs.allowAllValues else { return [] }
        let decided = Set(prefs.decidedCodes)
        var seen = Set<String>()
        return filter { $0.isSelected && $0.numericValue != nil && LabMapping.loincCode(for: $0.code) != nil }
            .filter { !decided.contains($0.code) && seen.insert($0.code).inserted }
            .map { CodeName(code: $0.code, name: $0.resolvedName) }
    }
}
