import AppIntents
import Foundation

/// The saved reports, read for Siri. Apple Health refuses reads while the phone is locked, and a
/// read can fail like any other: Siri then says it couldn't read Apple Health, never that there's
/// no reading or no such value.
enum SiriHealthRead {
    static let failureMessage: LocalizedStringResource = "I couldn't read Apple Health just now. Try again in a moment."

    /// Thrown by the entity queries, so Siri shows the failure instead of an empty list.
    struct Failure: Error, CustomLocalizedStringResourceConvertible {
        var localizedStringResource: LocalizedStringResource { SiriHealthRead.failureMessage }
    }

    /// The saved reports; throws `Failure` when Apple Health couldn't be read.
    static func reports(from store: any ReportDocumentStore) async throws -> [LabReport] {
        do {
            return try await store.loadStoredReports().reports
        } catch {
            // Rethrown as the one failure Siri explains; the cause doesn't change what the user can do.
            throw Failure()
        }
    }
}
