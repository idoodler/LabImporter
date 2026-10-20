import Foundation
import LabImporterCore

/// Dashboard layout (pinned / ordered / hidden codes) plus per-code nicknames
/// and reference ranges — persisted as one JSON blob under `storageKey`
/// (`@AppStorage("labDisplayPrefs")`, roamed via iCloud).
///
/// The JSON format and the rules (trimming, clearing a blank nickname or an
/// empty range) are implemented once in the shared module
/// (`LabImporterCore.LabDisplayPreferences`, also used by Android); this struct
/// keeps plain stored properties so SwiftUI bindings stay simple. Nicknames
/// and ranges are cosmetic — they never reach the exported CDA.
struct LabDisplayPreferences: RawRepresentable {
    var pinnedCodes: [String] = []
    var orderedCodes: [String] = []
    var hiddenCodes: [String] = []
    /// User nicknames overriding the catalog name, keyed by LOINC code. Stored
    /// under the legacy JSON key `customNames` for compatibility.
    var nicknames: [String: String] = [:]
    /// User reference (normal) ranges, keyed by LOINC code.
    var referenceRanges: [String: ReferenceRange] = [:]

    init() {}

    init(shared prefs: LabImporterCore.LabDisplayPreferences) {
        pinnedCodes = prefs.pinnedCodes
        orderedCodes = prefs.orderedCodes
        hiddenCodes = prefs.hiddenCodes
        nicknames = prefs.nicknames
        referenceRanges = prefs.referenceRanges.mapValues(ReferenceRange.init(shared:))
    }

    var shared: LabImporterCore.LabDisplayPreferences {
        LabImporterCore.LabDisplayPreferences(
            pinnedCodes: pinnedCodes, orderedCodes: orderedCodes, hiddenCodes: hiddenCodes,
            nicknames: nicknames, referenceRanges: referenceRanges.mapValues(\.shared)
        )
    }

    /// Unreadable or legacy blobs decode to the defaults (never nil).
    init?(rawValue: String) {
        self.init(shared: LabImporterCore.LabDisplayPreferences.companion.decode(raw: rawValue))
    }

    var rawValue: String { shared.encode() }

    var pinnedSet: Set<String> { Set(pinnedCodes) }
    var hiddenSet: Set<String> { Set(hiddenCodes) }

    /// The nickname for `code`, or `nil` if unset (or blank).
    func nickname(for code: String) -> String? {
        shared.nickname(code: code)
    }

    /// Sets (or, when `name` is `nil`/blank, clears) the nickname for `code`.
    mutating func setNickname(_ name: String?, for code: String) {
        self = LabDisplayPreferences(shared: shared.withNickname(name: name, code: code))
    }

    /// The user's reference range for `code`, or `nil` if none (or an empty one) is set.
    func referenceRange(for code: String) -> ReferenceRange? {
        shared.referenceRange(code: code).map(ReferenceRange.init(shared:))
    }

    /// Sets (or, when `range` is `nil`/empty, clears) the reference range for `code`.
    mutating func setReferenceRange(_ range: ReferenceRange?, for code: String) {
        self = LabDisplayPreferences(shared: shared.withReferenceRange(range: range?.shared, code: code))
    }
}

extension LabDisplayPreferences {
    /// The `@AppStorage` key the preferences are persisted under. Shared so
    /// non-View code reads the same blob the UI binds to, including iCloud sync.
    static let storageKey = "labDisplayPrefs"

    /// The current preferences, as the views see them.
    static func current() -> LabDisplayPreferences {
        LabDisplayPreferences(shared: currentShared())
    }

    /// The current preferences in the shared module's type — what the shared
    /// LOINC mapping consults for nicknames and ranges. A one-entry cache keyed
    /// on the raw blob avoids re-decoding on every name lookup; guarded by a lock
    /// because names are resolved from views and background actors alike.
    static func currentShared() -> LabImporterCore.LabDisplayPreferences {
        let raw = UserDefaults.standard.string(forKey: storageKey) ?? ""
        cacheLock.lock(); defer { cacheLock.unlock() }
        if raw == cachedRaw, let cached { return cached }
        let prefs = LabImporterCore.LabDisplayPreferences.companion.decode(raw: raw)
        cachedRaw = raw
        cached = prefs
        return prefs
    }

    private nonisolated(unsafe) static var cachedRaw: String?
    private nonisolated(unsafe) static var cached: LabImporterCore.LabDisplayPreferences?
    private static let cacheLock = NSLock()
}
