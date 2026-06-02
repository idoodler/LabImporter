import Foundation

// Shared sample data for SwiftUI previews. The codes are real LOINC numbers so
// the previews read realistically, but the names and units are supplied inline:
// the bundled `loinc.db` is a build product that isn't present in the preview
// canvas, so `LabMapping`/`LabCategory` fall back to these values (and a neutral
// category color). Keeping the fixtures in one place lets every preview tell the
// same coherent story — one patient, one lab, values that drift over time.

extension LabReport {
    /// The most recent multi-panel report — used by `ReportDetailView` and as a
    /// row in `HistoryView`.
    static var sample: LabReport { sampleHistory[0] }

    /// Four reports spread across roughly a year (newest first) so trend charts
    /// and dashboard sparklines have a real series to draw.
    static var sampleHistory: [LabReport] {
        let day: TimeInterval = 86_400
        // A fixed anchor (~2026-05) keeps previews deterministic across runs.
        let anchor = Date(timeIntervalSince1970: 1_780_000_000)
        return [0, 92, 184, 276].enumerated().map { index, daysAgo in
            LabReport(
                id: UUID(),
                date: anchor.addingTimeInterval(-Double(daysAgo) * day),
                patientName: "Max Mustermann",
                authorName: "Laborzentrum München",
                entries: sampleEntries(step: Double(index))
            )
        }
    }

    /// One panel of entries; `step` nudges a few values so successive reports
    /// differ and the charts show movement rather than flat lines.
    private static func sampleEntries(step: Double) -> [Entry] {
        func entry(_ code: String, _ name: String, _ value: Double, _ unit: String) -> Entry {
            let shown = value.truncatingRemainder(dividingBy: 1) == 0
                ? String(format: "%.0f", value)
                : String(format: "%.2f", value)
            return Entry(id: UUID(), code: code, name: name,
                         displayValue: shown, numericValue: value, unit: unit)
        }
        let drift = 1 + step * 0.04
        return [
            entry("2345-7", "Glucose", 92 * drift, "mg/dL"),
            entry("4548-4", "HbA1c", 5.4 + step * 0.1, "%"),
            entry("2093-3", "Cholesterol", 188 * drift, "mg/dL"),
            entry("2085-9", "HDL Cholesterol", 58, "mg/dL"),
            entry("13457-7", "LDL Cholesterol", 110 * drift, "mg/dL"),
            entry("2571-8", "Triglycerides", 130 * drift, "mg/dL"),
            entry("2160-0", "Creatinine", 0.91, "mg/dL"),
            entry("2951-2", "Sodium", 140, "mmol/L"),
            entry("2823-3", "Potassium", 4.3, "mmol/L"),
            entry("718-7", "Hemoglobin", 14.6, "g/dL"),
            entry("3016-3", "TSH", 1.8 + step * 0.05, "mIU/L"),
            entry("1742-6", "ALT", 24, "U/L"),
            entry("1989-3", "Vitamin D", 32 + step, "ng/mL"),
        ]
    }
}

extension LabValue {
    /// A short, mixed set for review/edit previews: a few ordinary values, one
    /// fuzzily-resolved suggestion to confirm, and one value with no LOINC code
    /// (so the "not supported for export" UI appears).
    static var sampleValues: [LabValue] {
        [
            LabValue(code: "2345-7", name: "Glucose", displayValue: "92",
                     numericValue: 92, unit: "mg/dL"),
            LabValue(code: "4548-4", name: "HbA1c", displayValue: "5.4",
                     numericValue: 5.4, unit: "%"),
            LabValue(code: "2093-3", name: "Cholesterol", displayValue: "188",
                     numericValue: 188, unit: "mg/dL"),
            LabValue(code: "2160-0", name: "Creatinine", displayValue: "0.91",
                     numericValue: 0.91, unit: "mg/dL", isSuggestedCode: true),
            LabValue(code: "CRP-X", name: "C-Reactive Protein", displayValue: "3.1",
                     numericValue: 3.1, unit: "mg/L"),
        ]
    }
}

extension LoincTerm {
    /// A representative term for `LoincTermDetailView` / description-card previews.
    static var sample: LoincTerm {
        LoincTerm(
            code: "4548-4",
            name: "Hemoglobin A1c",
            englishName: "Hemoglobin A1c/Hemoglobin.total in Blood",
            description: "Glycated hemoglobin — reflects average blood glucose over the past ~3 months.",
            ucum: "%",
            rank: 12
        )
    }
}

extension CodeName {
    /// Code/name pairs drawn from the sample report, for the sort/visibility and
    /// settings previews.
    static var sampleCodes: [CodeName] {
        LabReport.sample.entries.map { CodeName(code: $0.code, name: $0.name) }
    }
}

extension CategoryCount {
    /// A spread of clinical categories for the review header card preview.
    static var sampleGroups: [CategoryCount] {
        [
            CategoryCount(category: .glycemic, count: 2),
            CategoryCount(category: .lipids, count: 4),
            CategoryCount(category: .renal, count: 1),
            CategoryCount(category: .electrolytes, count: 2),
            CategoryCount(category: .hematology, count: 1),
        ]
    }
}
