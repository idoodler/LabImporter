import Foundation

/// Roams a small allow-list of preference values across the user's devices via
/// the iCloud key-value store (`NSUbiquitousKeyValueStore`).
///
/// **What syncs:** the dashboard card layout (sorting / pinning / hiding) and
/// the patient metadata entered in Settings. **What never syncs:** lab values
/// themselves — those live in Apple Health by design (see CLAUDE.md), and no
/// network call ever carries lab data. The iCloud KVS holds only the same tiny
/// preference values that already live in `UserDefaults` behind `@AppStorage`.
///
/// ### Mechanism
/// Every synced value already lives in `UserDefaults.standard` behind an
/// `@AppStorage` key, so we keep `UserDefaults.standard` and the iCloud store
/// in lock-step in both directions:
/// - local edit  → `UserDefaults.didChangeNotification`            → push up.
/// - remote edit → `NSUbiquitousKeyValueStore.didChangeExternallyNotification`
///   → write into `UserDefaults.standard`, which refreshes every `@AppStorage`
///   view live.
///
/// `isApplyingRemoteChange` breaks the feedback loop between the two directions.
/// Conflicts resolve last-writer-wins per key (the KVS default); on a fresh
/// device any values iCloud already knows win over local defaults at launch.
@MainActor
final class CloudSyncService {
    static let shared = CloudSyncService()

    /// The exact `@AppStorage` keys we roam. Mirror any future *preference* key
    /// here to have it sync — never add keys that reference lab data.
    static let syncedKeys: Set<String> = [
        "labDisplayPrefs",          // dashboard card sorting / pinning / hiding
        "patientName",
        "authorName",
        "patientBirthdateInterval",
        "patientSexRaw"
    ]

    private let store = NSUbiquitousKeyValueStore.default
    private let defaults = UserDefaults.standard
    private var observers: [NSObjectProtocol] = []
    private var isApplyingRemoteChange = false
    private var started = false

    private init() {}

    /// Begin syncing. Safe to call repeatedly; only the first call wires up.
    func start() {
        guard !started else { return }
        started = true

        observers.append(NotificationCenter.default.addObserver(
            forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
            object: store, queue: .main
        ) { [weak self] note in
            MainActor.assumeIsolated { self?.remoteStoreChanged(note) }
        })

        observers.append(NotificationCenter.default.addObserver(
            forName: UserDefaults.didChangeNotification,
            object: defaults, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.localDefaultsChanged() }
        })

        // Pull what iCloud already has, then push anything that only exists
        // locally. `synchronize()` schedules the initial download.
        store.synchronize()
        applyRemoteValues(for: Self.syncedKeys)
        pushLocalValues()
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

    private func localDefaultsChanged() {
        guard !isApplyingRemoteChange else { return }
        pushLocalValues()
    }

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
