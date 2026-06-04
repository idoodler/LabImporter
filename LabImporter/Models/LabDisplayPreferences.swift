import Foundation

struct LabDisplayPreferences: RawRepresentable {
    var pinnedCodes: [String] = []
    var orderedCodes: [String] = []
    var hiddenCodes: [String] = []
    /// User-chosen nicknames that override the catalog name for a LOINC code,
    /// keyed by code (e.g. `"4548-8"` → `"HbA1c %"`). Empty until the user renames
    /// something. Applied centrally in `LabMapping.displayName(for:)`, so a nickname
    /// flows to every screen (dashboard, trends, detail, review, history) — but it
    /// is purely cosmetic and never reaches the exported CDA, which keeps the
    /// standard LOINC English display (see `LabMapping.loincCode(for:)`).
    /// Persisted under the legacy JSON key `customNames` (see `Payload`).
    var nicknames: [String: String] = [:]
    /// User-defined reference (normal) ranges, keyed by LOINC code. Empty until
    /// the user sets one in Sort & Visibility (or the trends screen). Like
    /// nicknames these are cosmetic — they drive the out-of-range badges across
    /// the app via `LabMapping.referenceRange(for:)` but never reach the exported
    /// CDA. There is no bundled default: LOINC ships no ranges (see
    /// `ReferenceRange`), so clearing one simply removes the flagging.
    var referenceRanges: [String: ReferenceRange] = [:]

    init() {}

    init?(rawValue: String) {
        guard let data = rawValue.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(Payload.self, from: data)
        else { self = LabDisplayPreferences(); return }
        pinnedCodes = decoded.pinnedCodes
        orderedCodes = decoded.orderedCodes
        hiddenCodes = decoded.hiddenCodes
        nicknames = decoded.customNames ?? [:]
        referenceRanges = decoded.referenceRanges ?? [:]
    }

    var rawValue: String {
        let payload = Payload(pinnedCodes: pinnedCodes, orderedCodes: orderedCodes,
                              hiddenCodes: hiddenCodes, customNames: nicknames,
                              referenceRanges: referenceRanges)
        return (try? JSONEncoder().encode(payload)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    var pinnedSet: Set<String> { Set(pinnedCodes) }
    var hiddenSet: Set<String> { Set(hiddenCodes) }

    /// The user's nickname for `code`, or `nil` if they haven't set one
    /// (or set it to blank). Whitespace is trimmed so a stray space never masks a
    /// catalog name.
    func nickname(for code: String) -> String? {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard let name = nicknames[trimmed]?.trimmingCharacters(in: .whitespaces),
              !name.isEmpty else { return nil }
        return name
    }

    /// Sets (or, when `name` is `nil`/blank, clears) the nickname for
    /// `code`. Clearing falls the display back to the catalog name.
    mutating func setNickname(_ name: String?, for code: String) {
        let trimmedCode = code.trimmingCharacters(in: .whitespaces)
        let trimmedName = name?.trimmingCharacters(in: .whitespaces)
        if let trimmedName, !trimmedName.isEmpty {
            nicknames[trimmedCode] = trimmedName
        } else {
            nicknames.removeValue(forKey: trimmedCode)
        }
    }

    /// The user's reference range for `code`, or `nil` if none is set (or the
    /// stored one is empty, i.e. constrains nothing).
    func referenceRange(for code: String) -> ReferenceRange? {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard let range = referenceRanges[trimmed], !range.isEmpty else { return nil }
        return range
    }

    /// Sets (or, when `range` is `nil`/empty, clears — the per-code "reset") the
    /// reference range for `code`. Clearing removes out-of-range flagging.
    mutating func setReferenceRange(_ range: ReferenceRange?, for code: String) {
        let trimmedCode = code.trimmingCharacters(in: .whitespaces)
        if let range, !range.isEmpty {
            referenceRanges[trimmedCode] = range
        } else {
            referenceRanges.removeValue(forKey: trimmedCode)
        }
    }

    // Separate Codable type breaks the Codable+RawRepresentable encoding cycle.
    // If LabDisplayPreferences itself were Codable, the stdlib's RawRepresentable
    // default encode(to:) would call self.rawValue → JSONEncoder.encode(self) → infinite recursion.
    private struct Payload: Codable {
        var pinnedCodes: [String]
        var orderedCodes: [String]
        var hiddenCodes: [String]
        // The on-disk/iCloud key stays `customNames` — it predates the "nickname"
        // rename — for backward compatibility. Optional so blobs written before
        // nicknames existed, or synced from a device on an older build, still
        // decode (older builds likewise ignore this unknown key, so the layout
        // keeps roaming both ways).
        var customNames: [String: String]?
        // Optional for the same forward/backward-compat reason as `customNames`:
        // blobs written before ranges existed (or synced from an older build)
        // decode fine, and older builds ignore this unknown key when roaming.
        var referenceRanges: [String: ReferenceRange]?
    }
}

extension LabDisplayPreferences {
    /// The `@AppStorage` key the dashboard preferences are persisted under. Shared
    /// so non-View code (e.g. `LabMapping`) can read the same blob the UI binds to,
    /// keeping nicknames in lockstep with the binding — including iCloud sync.
    static let storageKey = "labDisplayPrefs"

    /// Loads the current preferences from `UserDefaults` — the very blob the
    /// `@AppStorage("labDisplayPrefs")` views bind to. A one-entry cache keyed on
    /// the raw string avoids re-decoding on every `displayName` lookup (called per
    /// row and inside sort comparators). Thread-safe via `cacheLock`: lab names are
    /// resolved both from `@MainActor` views and from the OCR/parser/HealthKit
    /// actors, so this may be called from any isolation domain.
    static func current() -> LabDisplayPreferences {
        let raw = UserDefaults.standard.string(forKey: storageKey) ?? ""
        cacheLock.lock(); defer { cacheLock.unlock() }
        if raw == cachedRaw, let cached { return cached }
        let prefs = LabDisplayPreferences(rawValue: raw) ?? LabDisplayPreferences()
        cachedRaw = raw
        cached = prefs
        return prefs
    }

    private nonisolated(unsafe) static var cachedRaw: String?
    private nonisolated(unsafe) static var cached: LabDisplayPreferences?
    private static let cacheLock = NSLock()
}
