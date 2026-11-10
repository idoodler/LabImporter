import Foundation

/// Reads the saved reports for a screen that may start several reads at once (becoming active
/// while a save's reload is still running). Only the read started last is used: an older one
/// that finishes later would put back a list without the report just saved.
@MainActor
final class LatestReportLoad {
    private var generation = 0

    /// What Apple Health holds, `.failure` when it couldn't be read, or nil when a newer read
    /// started meanwhile: that one's result is the one to show.
    func load(from store: any ReportDocumentStore) async -> Result<StoredReports, any Error>? {
        generation += 1
        let mine = generation
        let result: Result<StoredReports, any Error>
        do {
            result = .success(try await store.loadStoredReports())
        } catch {
            result = .failure(error)
        }
        return mine == generation ? result : nil
    }
}
