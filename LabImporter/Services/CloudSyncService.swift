import Foundation

/// Roams the dashboard card layout (sorting / pinning / hiding) across the
/// user's devices via the iCloud key-value store (`NSUbiquitousKeyValueStore`).
///
/// **Opt-in.** Nothing syncs until the user turns it on — the persisted flag
/// `iCloudSyncEnabled` gates everything. The onboarding flow forces an explicit
/// decision (see `CloudSyncOptInView`) and Settings lets the user change it.
///
/// **What syncs:** only `labDisplayPrefs` (the dashboard card layout).
/// **What never syncs:** lab values — those live in Apple Health by design (see
/// CLAUDE.md) — and patient metadata, which stays device-local. No network call
/// here ever carries lab data; the iCloud KVS holds only the tiny layout blob.
///
/// ### Mechanism
/// The synced value lives in `UserDefaults.standard` behind an `@AppStorage`
/// key, so while enabled we keep `UserDefaults.standard` and the iCloud store in
/// lock-step in both directions:
/// - local edit  → `UserDefaults.didChangeNotification`            → push up.
/// - remote edit → `NSUbiquitousKeyValueStore.didChangeExternallyNotification`
///   → write into `UserDefaults.standard`, which refreshes every `@AppStorage`
///   view live.
///
/// The same local-defaults observer also watches `iCloudSyncEnabled` and
/// activates/deactivates the remote half when the user flips the toggle, so the
/// UI only has to write the flag. `isApplyingRemoteChange` breaks the feedback
/// loop. Conflicts resolve last-writer-wins per key (the KVS default); on a
/// fresh device any value iCloud already knows wins over local defaults when
/// sync is first switched on.
@MainActor
final class CloudSyncService {
    static let shared = CloudSyncService()

    /// The `@AppStorage` flag that gates syncing. Written by onboarding/Settings.
    static let enabledKey = "iCloudSyncEnabled"

    /// The exact `@AppStorage` keys we roam. Layout only — never lab data or
    /// patient metadata.
    static let syncedKeys: Set<String> = [
        "labDisplayPrefs"           // dashboard card sorting / pinning / hiding
    ]

    private let store = NSUbiquitousKeyValueStore.default
    private let defaults = UserDefaults.standard
    private var remoteObserver: NSObjectProtocol?
    private var localObserver: NSObjectProtocol?
    private var isApplyingRemoteChange = false
    private var isActive = false
    private var started = false

    private init() {}

    /// Begin observing local defaults. Safe to call repeatedly; only the first
    /// call wires up. Reflects the persisted opt-in state, so a user who enabled
    /// sync on a previous launch keeps syncing without re-deciding.
    func start() {
        guard !started else { return }
        started = true

        localObserver = NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: defaults, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.localDefaultsChanged() }
        }

        reconcileEnabledState()
    }

    // MARK: Enable / disable

    private var isEnabled: Bool { defaults.bool(forKey: Self.enabledKey) }

    private func localDefaultsChanged() {
        guard !isApplyingRemoteChange else { return }
        reconcileEnabledState()
        if isActive { pushLocalValues() }
    }

    /// Brings the remote half in line with the persisted `iCloudSyncEnabled`
    /// flag — activating on opt-in, tearing down on opt-out.
    private func reconcileEnabledState() {
        switch (isEnabled, isActive) {
        case (true, false): activateRemoteSync()
        case (false, true): deactivateRemoteSync()
        default: break
        }
    }

    private func activateRemoteSync() {
        isActive = true
        remoteObserver = NotificationCenter.default.addObserver(
            forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: store, queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated { self?.remoteStoreChanged(note) }
        }

        // Pull what iCloud already has, then push anything that only exists
        // locally. `synchronize()` schedules the initial download.
        store.synchronize()
        applyRemoteValues(for: Self.syncedKeys)
        pushLocalValues()
    }

    private func deactivateRemoteSync() {
        isActive = false
        if let remoteObserver {
            NotificationCenter.default.removeObserver(remoteObserver)
        }
        remoteObserver = nil
        // Leave whatever is already in the KVS untouched; re-enabling re-syncs.
    }

    // MARK: iCloud → local

    private func remoteStoreChanged(_ note: Notification) {
        let info = note.userInfo
        if let reason = info?[NSUbiquitousKeyValueStoreChangeReasonKey] as? Int,
           reason == NSUbiquitousKeyValueStoreQuotaViolationChange {
            return
        }
        let changed = (info?[NSUbiquitousKeyValueStoreChangedKeysKey] as? [String])
            .map(Set.init) ?? Self.syncedKeys
        applyRemoteValues(for: changed.intersection(Self.syncedKeys))
    }

    private func applyRemoteValues(for keys: Set<String>) {
        guard !keys.isEmpty else { return }
        isApplyingRemoteChange = true
        defer { isApplyingRemoteChange = false }
        for key in keys {
            if let value = store.object(forKey: key) {
                defaults.set(value, forKey: key)
            }
        }
    }

    // MARK: local → iCloud

    private func pushLocalValues() {
        var didChange = false
        for key in Self.syncedKeys {
            let local = defaults.object(forKey: key)
            let remote = store.object(forKey: key)
            if let local {
                if !plistValuesEqual(local, remote) {
                    store.set(local, forKey: key)
                    didChange = true
                }
            } else if remote != nil {
                store.removeObject(forKey: key)
                didChange = true
            }
        }
        if didChange { store.synchronize() }
    }

    private func plistValuesEqual(_ lhs: Any, _ rhs: Any?) -> Bool {
        guard let rhs else { return false }
        return (lhs as? NSObject)?.isEqual(rhs as? NSObject) ?? false
    }
}
