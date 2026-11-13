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
    /// True when the model returned this result but the report text doesn't
    /// print it — the user should compare it with the report. Doesn't block
    /// saving. Set only by an import (shared rule); editing the result clears it.
    var needsSourceCheck: Bool

    init(
        id: UUID = UUID(),
        code: String,
        name: String,
        displayValue: String,
        numericValue: Double?,
        unit: String,
        isSelected: Bool = true,
        isSuggestedCode: Bool = false,
        needsSourceCheck: Bool = false
    ) {
        self.id = id
        self.code = code
        self.name = name
        self.displayValue = displayValue
        self.numericValue = numericValue
        self.unit = unit
        self.isSelected = isSelected
        self.isSuggestedCode = isSuggestedCode
        self.needsSourceCheck = needsSourceCheck
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
            && lhs.needsSourceCheck == rhs.needsSourceCheck
    }

    /// A typed result as a number, by the shared rule: "inf" and "NaN" aren't numbers.
    static func number(from text: String) -> Double? {
        NumberFormatting.shared.parse(raw: text)?.doubleValue
    }

    /// The result as the user just typed it: the user has now looked at it, so
    /// what the model read no longer needs comparing. Shared rule.
    mutating func applyEditedResult(_ text: String) {
        self = LabValue(shared: shared.withEditedResult(text: text))
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
            && qualitativeResult == other.qualitativeResult
            && unit == other.unit
            && isSelected == other.isSelected
    }

    /// The result as printed when it isn't a number ("Negativ", "1:160",
    /// "<0.5"), or nil. Shared rule (LabImporterCore `QualitativeResult`).
    var qualitativeResult: String? {
        numericValue == nil ? QualitativeResult.shared.of(text: displayValue) : nil
    }

    /// Whether this row will be saved: selected, with a number or a
    /// qualitative result, and LOINC-mapped. Shared rule.
    var isExportable: Bool {
        shared.isExportable
    }

    /// Selected and mapped, with text that can't be saved as written ("neg 1"):
    /// Review holds back saving until it's corrected or deselected. Shared rule.
    var needsCorrection: Bool {
        shared.needsCorrection
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

    /// How many values about to be saved the report text doesn't print. Shared rule.
    func valuesToCheckCount() -> Int {
        Int(LabValueKt.valuesToCheckCount(map(\.shared)))
    }

    /// How many values can't be saved as written. Shared rule.
    func valuesToCorrectCount() -> Int {
        Int(LabValueKt.valuesToCorrectCount(map(\.shared)))
    }

    /// What Review asks before saving or sharing the report file, or `nil` when nothing is flagged or left out. Shared rule.
    func saveConfirmation(for action: SaveConfirmation.Action = .save) -> SaveConfirmation? {
        LabValueKt.saveConfirmation(map(\.shared)).map { SaveConfirmation(shared: $0, action: action) }
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

/// Review's question before saving: flag, don't block. Values to compare with the report and
/// selected values without a LOINC code, which saving leaves out (shared `SaveConfirmation`).
struct SaveConfirmation: Equatable {
    /// What the question guards. The report file carries and leaves out the same values a save does.
    enum Action: Equatable {
        case save, share
    }

    /// "Glucose 922 mg/dL", for each value the report text doesn't print.
    let toCheck: [String]
    /// The same for each selected value saving leaves out.
    let leftOut: [String]
    /// The row Go Back scrolls to.
    let firstID: UUID?
    let action: Action

    init(shared: LabImporterCore.SaveConfirmation, action: Action = .save) {
        toCheck = shared.toCheck.map(\.confirmationLine)
        leftOut = shared.leftOut.map(\.confirmationLine)
        firstID = UUID(uuidString: shared.firstId)
        self.action = action
    }

    var title: String {
        switch action {
        case .save: String(localized: "Check Before Saving")
        case .share: String(localized: "Check Before Sharing")
        }
    }

    /// The button that goes ahead.
    var proceedTitle: String {
        switch action {
        case .save: String(localized: "Save Anyway")
        case .share: String(localized: "Share Anyway")
        }
    }

    /// Each list under its heading, one value a line.
    var message: String {
        var parts: [String] = []
        if !toCheck.isEmpty {
            parts.append(([String(localized: "Compare these with your report:")] + toCheck).joined(separator: "\n"))
        }
        if !leftOut.isEmpty {
            let heading = switch action {
            case .save: String(localized: "These have no LOINC code and won't be saved:")
            case .share: String(localized: "These have no LOINC code and won't be shared:")
            }
            parts.append(([heading] + leftOut).joined(separator: "\n"))
        }
        return parts.joined(separator: "\n\n")
    }
}
