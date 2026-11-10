import Foundation
import Observation

/// One save at a time from a screen: a second tap on Save while the first is still writing
/// would write the report twice (two documents in Apple Health, or two new versions of an
/// edited one). Review disables Save and Share while `isSaving`, and ignores a repeat that
/// still gets through (two taps in one frame).
@MainActor @Observable
final class SaveInFlight {
    private(set) var isSaving = false
    @ObservationIgnored private var task: Task<Void, Never>?

    /// Starts `save` unless one is already running. Returns whether it started.
    @discardableResult
    func start(_ save: @escaping @MainActor () async -> Void) -> Bool {
        guard !isSaving else { return false }
        isSaving = true
        task = Task {
            await save()
            isSaving = false
        }
        return true
    }

    /// Waits for the running save, if any.
    func finish() async {
        await task?.value
    }
}
