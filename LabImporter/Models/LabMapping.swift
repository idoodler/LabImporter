import Foundation
import LabImporterCore

// LOINC is the canonical identity for every lab value in the app: the parser
// resolves printed report codes to LOINC, and storage and display names all key
// off the LOINC code. The mapping itself is shared with Android
// (`LabImporterCore.LabMapping`); this enum is its Swift face.
enum LabMapping {
    private static var core: LabImporterCore.LabMapping {
        SharedCore.start()
        return LabImporterCore.LabMapping.shared
    }

    // Display name for a LOINC code: a user's nickname (set in Sort &
    // Visibility) wins, then the localized catalog name, finally the raw code
    // (e.g. an as-yet-unmapped value). Nicknames are cosmetic only — CDA export
    // keeps the standard LOINC English display (see `loincCode(for:)`).
    static func displayName(for code: String) -> String {
        core.displayName(code: code)
    }

    // The catalog (non-overridden) display name for a code, ignoring any nickname.
    static func catalogName(for code: String) -> String {
        core.catalogName(code: code)
    }

    // The loinc.org details page for a code, e.g. https://loinc.org/2160-0/.
    // Returns nil for anything not shaped like a LOINC number.
    static func loincURL(for code: String) -> URL? {
        core.loincUrl(code: code).flatMap(URL.init(string:))
    }

    // Validates that `code` is a real LOINC code and returns it with an English
    // display name for CDA export. Returns nil for unmapped/unknown codes, which
    // is how the UI decides a value cannot yet be saved to Health.
    static func loincCode(for code: String) -> (loinc: String, display: String)? {
        guard let export = core.loincCode(code: code) else { return nil }
        return (export.loinc, export.display)
    }

    // The user's reference range for a code, or nil if they haven't set one.
    // Ranges are a display preference only and never reach the CDA.
    static func referenceRange(for code: String) -> ReferenceRange? {
        core.referenceRange(code: code).map(ReferenceRange.init(shared:))
    }

    // Classifies `value` against the user's range for `code`. Returns nil when
    // there is no value or no range, so callers can skip the badge entirely.
    static func rangeStatus(for value: Double?, code: String) -> RangeStatus? {
        core.rangeStatus(value: value.map { KotlinDouble(double: $0) }, code: code).map(RangeStatus.init(shared:))
    }
}
