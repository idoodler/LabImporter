import Foundation

/// The user's opt-in choices for what LabImporter exposes to Siri via App
/// Intents — the framework Apple has made the only path into Siri now that
/// SiriKit is retired. Nothing here is on by default beyond the scan
/// shortcut, which never touches a health value: every value Siri may read
/// back requires an explicit, per-metric opt-in (`allowedCodes`), chosen in
/// Settings (`SiriAccessEditor`), never assumed from the master switch alone.
struct SiriExposurePreferences: RawRepresentable, Equatable {
    /// Master switch, set during onboarding (`SiriIntelligenceOptInView`) or
    /// later in Settings. `false` means every intent returns a "not enabled"
    /// dialog instead of silently doing nothing, so a Shortcut the user
    /// already built stays predictable rather than quietly breaking.
    var isEnabled = false
    /// LOINC codes the user has explicitly allowed Siri to read and speak a
    /// reading for. Empty by default — turning on `isEnabled` alone exposes no
    /// value; the user opts in per metric.
    var allowedCodes: [String] = []
    /// Lets Siri open the scanner ("Scan a lab report in LabImporter"). Never
    /// touches a health value, so it's the one thing on by default once the
    /// master switch is on.
    var allowScanShortcut = true
    /// Lets the values currently shown in the Review sheet be suggested to
    /// Siri while that screen is open ("on-screen awareness"). Off by
    /// default: it's the most sensitive surface, since it can include
    /// unsaved edits the user hasn't confirmed yet.
    var allowOnScreenAwareness = false
    /// Lets allowed metrics appear in Siri's own proactive suggestions
    /// (names only, never a reading). Deliberately separate from Spotlight —
    /// see `SpotlightSearch` for the dedicated Spotlight feature. Off by
    /// default.
    var allowKnowledgeIndexing = false
    /// Lets Siri/Spotlight's "Search in LabImporter" system search
    /// (`SearchLabValuesIntent`) open the app straight to a matching tracked
    /// value's trend. Never speaks or returns a reading — it only navigates,
    /// the same as tapping the app icon and searching by hand — so like
    /// `allowScanShortcut` it's on by default once the master switch is on.
    var allowInAppSearch = true

    init() {}

    init?(rawValue: String) {
        guard let data = rawValue.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(Payload.self, from: data)
        else { self = SiriExposurePreferences(); return }
        isEnabled = decoded.isEnabled
        allowedCodes = decoded.allowedCodes
        allowScanShortcut = decoded.allowScanShortcut
        allowOnScreenAwareness = decoded.allowOnScreenAwareness
        allowKnowledgeIndexing = decoded.allowKnowledgeIndexing
        allowInAppSearch = decoded.allowInAppSearch
    }

    var rawValue: String {
        let payload = Payload(isEnabled: isEnabled, allowedCodes: allowedCodes,
                               allowScanShortcut: allowScanShortcut,
                               allowOnScreenAwareness: allowOnScreenAwareness,
                               allowKnowledgeIndexing: allowKnowledgeIndexing,
                               allowInAppSearch: allowInAppSearch)
        return (try? JSONEncoder().encode(payload)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    var allowedSet: Set<String> { Set(allowedCodes) }

    /// Whether Siri may read and speak `code`'s latest value out loud.
    func isValueExposed(_ code: String) -> Bool {
        isEnabled && allowedSet.contains(code)
    }

    /// Whether the scan shortcut is available to Siri.
    var canStartScan: Bool { isEnabled && allowScanShortcut }

    /// Whether Siri/Spotlight's in-app search may open the app.
    var canSearch: Bool { isEnabled && allowInAppSearch }

    /// Whether `code` may be proactively suggested to / indexed by Siri.
    func isKnowledgeIndexed(_ code: String) -> Bool {
        isEnabled && allowKnowledgeIndexing && allowedSet.contains(code)
    }

    private struct Payload: Codable {
        var isEnabled: Bool
        var allowedCodes: [String]
        var allowScanShortcut: Bool
        var allowOnScreenAwareness: Bool
        var allowKnowledgeIndexing: Bool
        var allowInAppSearch: Bool

        init(isEnabled: Bool, allowedCodes: [String], allowScanShortcut: Bool,
             allowOnScreenAwareness: Bool, allowKnowledgeIndexing: Bool, allowInAppSearch: Bool) {
            self.isEnabled = isEnabled
            self.allowedCodes = allowedCodes
            self.allowScanShortcut = allowScanShortcut
            self.allowOnScreenAwareness = allowOnScreenAwareness
            self.allowKnowledgeIndexing = allowKnowledgeIndexing
            self.allowInAppSearch = allowInAppSearch
        }

        // Custom decoding so a blob persisted before `allowInAppSearch` existed
        // defaults it to `true` instead of failing to decode — which would
        // otherwise reset every other Siri preference via the `try?` in
        // `init?(rawValue:)`.
        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            isEnabled = try container.decode(Bool.self, forKey: .isEnabled)
            allowedCodes = try container.decode([String].self, forKey: .allowedCodes)
            allowScanShortcut = try container.decode(Bool.self, forKey: .allowScanShortcut)
            allowOnScreenAwareness = try container.decode(Bool.self, forKey: .allowOnScreenAwareness)
            allowKnowledgeIndexing = try container.decode(Bool.self, forKey: .allowKnowledgeIndexing)
            allowInAppSearch = try container.decodeIfPresent(Bool.self, forKey: .allowInAppSearch) ?? true
        }
    }
}

extension SiriExposurePreferences {
    /// The `@AppStorage` key these preferences are persisted under.
    static let storageKey = "siriExposurePrefs"

    /// Loads the current preferences directly from `UserDefaults` — the same
    /// blob the `@AppStorage(SiriExposurePreferences.storageKey)` bindings use.
    /// App Intents run outside SwiftUI's environment (often before any view
    /// exists), so they read this instead of a binding.
    static func current() -> SiriExposurePreferences {
        let raw = UserDefaults.standard.string(forKey: storageKey) ?? ""
        return SiriExposurePreferences(rawValue: raw) ?? SiriExposurePreferences()
    }
}
