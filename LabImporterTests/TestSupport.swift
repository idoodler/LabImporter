import Foundation
@testable import LabImporter

import Testing

private final class BundleToken {}

/// Shared fixtures for the unit tests.
enum Fixtures {
    /// A golden fixture shared with the Kotlin tests (shared/src/jvmTest/golden),
    /// bundled into this test target by tools/sync_xcode_project.rb.
    static func golden(_ name: String) throws -> String {
        let url = try #require(Bundle(for: BundleToken.self).url(forResource: name, withExtension: nil))
        return try String(contentsOf: url, encoding: .utf8)
    }

    /// Local midnight of a calendar day, the way reports carry their date.
    static func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        Calendar.current.date(from: DateComponents(year: year, month: month, day: day))!
    }

    /// Same fixture as `GoldenCda.values` in the Kotlin tests.
    static let goldenValues: [LabValue] = [
        LabValue(code: "2160-0", name: "Creatinine", displayValue: "0,8", numericValue: 0.8, unit: "mg/dl"),
        LabValue(code: "2823-3", name: "Potassium", displayValue: "4,1", numericValue: 4.1, unit: "mmol/l"),
        LabValue(code: "4548-4", name: "HbA1c", displayValue: "6,53", numericValue: 6.53, unit: "%"),
        LabValue(code: "1742-6", name: "ALT", displayValue: "12345,6", numericValue: 12_345.6, unit: "U/l"),
        LabValue(code: "KREA", name: "unmapped", displayValue: "1", numericValue: 1, unit: ""),
        LabValue(code: "2093-3", name: "deselected", displayValue: "198", numericValue: 198, unit: "mg/dl", isSelected: false),
        LabValue(code: "2951-2", name: "no value", displayValue: "-", numericValue: nil, unit: "mmol/l")
    ]

    /// Random document/observation ids and the build-specific identity → fixed tokens.
    static func normalizeCDA(_ xml: String) -> String {
        xml.replacingOccurrences(of: #"root="[0-9a-f-]{36}""#, with: #"root="UUID""#, options: .regularExpression)
            .replacingOccurrences(
                of: #"<manufacturerModelName>[^<]*</manufacturerModelName>"#,
                with: "<manufacturerModelName>IDENTITY</manufacturerModelName>",
                options: .regularExpression
            )
    }
}
