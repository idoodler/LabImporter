import Foundation
import LabImporterCore

/// LabImporter's business logic lives once, in the shared Kotlin Multiplatform
/// module (`shared/`, linked as the static `LabImporterCore` framework) and is
/// used by both the iOS and the Android app: the LOINC catalog and mapping,
/// categories, CDA export/read-back with schema migrations, the AI parse
/// post-processing, the dashboard rules and the cross-platform transfer file.
///
/// This file starts that core and converts between its types and the app's
/// SwiftUI-friendly value types at the boundary. Swift keeps only what is
/// genuinely platform-specific (UI, HealthKit, Vision, Foundation Models,
/// Siri, Spotlight).
enum SharedCore {
    /// Starts the core exactly once: the bundled catalog, the app language and
    /// the user's display preferences (nicknames, reference ranges).
    private static let started: Void = {
        let path = Bundle.main.url(forResource: "loinc", withExtension: "db")?.path ?? ""
        let language = Bundle.main.preferredLocalizations.first ?? "en"
        LabCore.shared.start(databasePath: path, appLanguage: language) {
            LabDisplayPreferences.currentShared()
        }
    }()

    static func start() { _ = started }

    /// The shared LOINC catalog (nil only if the bundled database is missing).
    static var loinc: LabImporterCore.LoincDirectory? {
        start()
        return LabCore.shared.loinc
    }
}

// MARK: - Dates

extension Date {
    /// This date's calendar day in the current time zone (reports are dated by day).
    var sharedDate: Kotlinx_datetimeLocalDate {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: self)
        return Kotlinx_datetimeLocalDate(year: Int32(parts.year ?? 1970), month: Int32(parts.month ?? 1), day: Int32(parts.day ?? 1))
    }

    /// Local midnight of a shared calendar day.
    init(shared date: Kotlinx_datetimeLocalDate) {
        // LocalDate.toString() is ISO-8601 (yyyy-MM-dd).
        let parts = date.description().split(separator: "-").compactMap { Int($0) }
        let components = DateComponents(year: parts.first, month: parts.dropFirst().first, day: parts.dropFirst(2).first)
        self = Calendar.current.date(from: components) ?? .distantPast
    }
}

// MARK: - Values and reports

extension LabValue {
    var shared: LabImporterCore.LabValue {
        LabImporterCore.LabValue(
            id: id.uuidString, code: code, name: name, displayValue: displayValue,
            numericValue: numericValue.map { KotlinDouble(double: $0) }, unit: unit,
            isSelected: isSelected, isSuggestedCode: isSuggestedCode
        )
    }

    init(shared value: LabImporterCore.LabValue) {
        self.init(
            id: UUID(uuidString: value.id) ?? UUID(), code: value.code, name: value.name,
            displayValue: value.displayValue, numericValue: value.numericValue?.doubleValue, unit: value.unit,
            isSelected: value.isSelected, isSuggestedCode: value.isSuggestedCode
        )
    }
}

extension LabReport.Entry {
    var shared: LabImporterCore.LabReport.Entry {
        LabImporterCore.LabReport.Entry(
            id: id.uuidString, code: code, name: name, displayValue: displayValue,
            numericValue: numericValue.map { KotlinDouble(double: $0) }, unit: unit
        )
    }

    init(shared entry: LabImporterCore.LabReport.Entry) {
        self.init(
            id: UUID(uuidString: entry.id) ?? UUID(), code: entry.code, name: entry.name,
            displayValue: entry.displayValue, numericValue: entry.numericValue?.doubleValue, unit: entry.unit
        )
    }
}

extension LabReport {
    var shared: LabImporterCore.LabReport {
        LabImporterCore.LabReport(
            id: id.uuidString, date: date.sharedDate, patientName: patientName,
            authorName: authorName, entries: entries.map(\.shared)
        )
    }

    init(shared report: LabImporterCore.LabReport) {
        self.init(
            id: UUID(uuidString: report.id) ?? UUID(), date: Date(shared: report.date),
            patientName: report.patientName, authorName: report.authorName,
            entries: report.entries.map(LabReport.Entry.init(shared:))
        )
    }
}

extension [LabReport] {
    /// Every code with a reading and its display name, first-seen order — the
    /// Settings sort/visibility and Siri editors' list (shared rule).
    var codeNames: [CodeName] {
        SharedCore.start()
        return LabImporterCore.Dashboard.shared.codeNames(reports: map(\.shared))
            .map { CodeName(code: $0.code, name: $0.name) }
    }
}

// MARK: - Ranges and categories

extension ReferenceRange {
    var shared: LabImporterCore.ReferenceRange {
        LabImporterCore.ReferenceRange(low: low.map { KotlinDouble(double: $0) }, high: high.map { KotlinDouble(double: $0) })
    }

    init(shared range: LabImporterCore.ReferenceRange) {
        self.init(low: range.low?.doubleValue, high: range.high?.doubleValue)
    }
}

extension RangeStatus {
    init(shared status: LabImporterCore.RangeStatus) {
        switch status {
        case .low: self = .low
        case .high: self = .high
        default: self = .normal
        }
    }
}
