import Foundation

/// Persisted set of user-created chat personas plus the last selected persona.
/// Built-in personas are not stored here — they are rebuilt on launch — so this
/// only carries what the user authored.
///
/// `RawRepresentable` over JSON so it can live in `@AppStorage`. When the user
/// has iCloud sync enabled, this blob roams across their devices via
/// `CloudSyncService` (its key is in `CloudSyncService.syncedKeys`), so custom
/// specialists and the current selection follow them to their other devices —
/// just like the dashboard layout. It uses the same deliberate separate
/// `Payload` type as `LabDisplayPreferences` to avoid the
/// Codable + RawRepresentable `encode(to:)` recursion trap — see the note there;
/// don't collapse it back into a single Codable conformance.
struct PersonaStore: RawRepresentable, Equatable {
    /// User-created personas, in display order.
    var customPersonas: [MedicalPersona] = []
    /// The id of the persona the user last talked to (built-in or custom), so
    /// the chat reopens where they left off. `nil` until they pick one.
    var selectedID: String?

    init() {}

    init?(rawValue: String) {
        guard let data = rawValue.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(Payload.self, from: data)
        else { self = PersonaStore(); return }
        customPersonas = decoded.customPersonas ?? []
        selectedID = decoded.selectedID
    }

    var rawValue: String {
        let payload = Payload(customPersonas: customPersonas, selectedID: selectedID)
        return (try? JSONEncoder().encode(payload)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    /// All personas the picker shows: the bundled presets followed by the user's.
    var allPersonas: [MedicalPersona] { MedicalPersona.builtIns + customPersonas }

    /// Resolves a persona by id across built-ins and custom ones.
    func persona(id: String?) -> MedicalPersona? {
        guard let id else { return nil }
        return allPersonas.first { $0.id == id }
    }

    /// The currently selected persona, falling back to the default when nothing
    /// is selected yet or the stored id no longer resolves (e.g. a custom one
    /// was deleted on another device).
    var selectedPersona: MedicalPersona {
        persona(id: selectedID) ?? .defaultPersona
    }

    /// Inserts a new custom persona or replaces the existing one with the same id.
    mutating func upsert(_ persona: MedicalPersona) {
        guard !persona.isBuiltIn else { return }
        if let index = customPersonas.firstIndex(where: { $0.id == persona.id }) {
            customPersonas[index] = persona
        } else {
            customPersonas.append(persona)
        }
    }

    /// Removes a custom persona. If it was selected, the selection clears so the
    /// chat falls back to the default persona.
    mutating func delete(id: String) {
        customPersonas.removeAll { $0.id == id }
        if selectedID == id { selectedID = nil }
    }

    // Separate Codable type breaks the Codable + RawRepresentable encoding cycle
    // (see `LabDisplayPreferences.Payload`). Fields are optional so older or
    // newer blobs still decode when roaming across app versions.
    private struct Payload: Codable {
        var customPersonas: [MedicalPersona]?
        var selectedID: String?
    }
}

extension PersonaStore {
    /// The `@AppStorage` key the persona store is persisted under.
    static let storageKey = "medicalPersonas"
}
