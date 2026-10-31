#if DEBUG
import Foundation

/// A stand-in for the on-device model, for the simulator and tests (which
/// have none): a Function Health–style report and a "model" that reads its
/// rows the way the real one does — copying the status label ("In Range· "),
/// a flag ("4.5 *") and a section heading along with the values. Everything
/// after the model (the shared post-processing, LOINC resolution, review,
/// export) runs for real. Never part of a release build.
enum StandInReport {
    /// One printed row, the test name that identifies it in a part, and what the model reads from it.
    struct Row {
        let printed: String
        let key: String
        let entry: AILabEntry
    }

    static let rows: [Row] = [
        row("Uric Acid", "In Range· 5.1", "mg/dL"),
        row("Carbon Dioxide", "24", "mmol/L"),
        row("Platelet Count", "263", "Thousand/uL"),
        row("Thyroid-Stimulating Hormone (TSH)", "1.9", "mIU/L"),
        row("Lead", "In Range· <1.0", "mcg/dL"),
        row("Celiac – Deamidated Gliadin IgA", "4.5 *", "U/mL"),
        // A section heading the model reads as a test: it must never be saved.
        row("HEPATITIS PANEL", "NON-REACTIVE", "", printedValue: ""),
        row("Hepatitis B Surface Antigen", "NON-REACTIVE", ""),
    ]

    /// The report, long enough to be read in more than one part.
    static var text: String {
        let header = "Function Health · Quest Diagnostics\nPatient: Jane Roe     DOB: 02/11/1984\nCollected: 08/12/2026"
        let prose = (1...24).map { index in
            "Note \(index): This marker reflects how your body is doing over time; discuss changes with your clinician."
        }
        let half = rows.count / 2
        return ([header] + rows[..<half].map(\.printed) + prose + rows[half...].map(\.printed)).joined(separator: "\n")
    }

    /// Reads the rows printed in `part` — what the model returns for that part.
    static let read: LabParserService.PartReader = { part, onProgress in
        let entries = rows.filter { part.contains($0.key) }.map(\.entry)
        onProgress(ParseProgress(entryCount: entries.count, latestName: entries.last?.name))
        return AILabReport(
            entries: entries, reportDate: "2026-08-12", patientName: "Jane Roe",
            authorName: "Function Health", reportLanguage: "en"
        )
    }

    /// A row printed as "name  value unit" — the value as the model copies it, unless printed differently.
    private static func row(_ name: String, _ value: String, _ unit: String, printedValue: String? = nil) -> Row {
        let printed = [name.padding(toLength: max(name.count, 34), withPad: " ", startingAt: 0), printedValue ?? value, unit]
            .filter { !$0.isEmpty }.joined(separator: " ").trimmingCharacters(in: .whitespaces)
        return Row(printed: printed, key: name, entry: AILabEntry(code: name, name: name, rawValue: value, unit: unit))
    }
}
#endif
