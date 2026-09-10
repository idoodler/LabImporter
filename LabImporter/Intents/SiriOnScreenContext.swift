import Foundation
import SwiftUI

/// Bridges "what's currently visible in the Review sheet" to the Siri entity
/// query so App Intents' on-screen-awareness suggestions
/// (`LabMetricEntityQuery.suggestedEntities()`) can reflect what's actually on
/// screen. `ReviewView` writes `visibleCodes` only while
/// `SiriExposurePreferences.allowOnScreenAwareness` is on; entity queries run
/// off the main actor, so access is guarded by a lock rather than
/// `@MainActor`, mirroring `LabDisplayPreferences`'s cache.
final class SiriOnScreenContext: @unchecked Sendable {
    static let shared = SiriOnScreenContext()
    private init() {}

    private let lock = NSLock()
    private var storedCodes: [String] = []

    var visibleCodes: [String] {
        get { lock.lock(); defer { lock.unlock() }; return storedCodes }
        set { lock.lock(); defer { lock.unlock() }; storedCodes = newValue }
    }

    /// Publishes the values currently shown in the Review sheet — but only
    /// codes the user has both selected for export *and* explicitly allowed
    /// Siri to access, and only while on-screen awareness is turned on in
    /// Settings. Called by `ReviewView` on appear and on every edit.
    func update(from values: [LabValue]) {
        let prefs = SiriExposurePreferences.current()
        guard prefs.allowOnScreenAwareness else { visibleCodes = []; return }
        visibleCodes = values
            .filter { $0.isSelected && $0.numericValue != nil && prefs.isValueExposed($0.code) }
            .map(\.code)
    }
}

extension View {
    /// Keeps `SiriOnScreenContext` in sync with `values` for as long as this
    /// view is on screen, clearing it on disappear — the Review sheet's hook
    /// into Siri's on-screen-awareness suggestions.
    func reportsToSiriOnScreenContext(_ values: [LabValue]) -> some View {
        onAppear { SiriOnScreenContext.shared.update(from: values) }
            .onChange(of: values) { _, newValues in SiriOnScreenContext.shared.update(from: newValues) }
            .onDisappear { SiriOnScreenContext.shared.visibleCodes = [] }
    }
}
