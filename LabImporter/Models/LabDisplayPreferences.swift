import Foundation

struct LabDisplayPreferences: RawRepresentable {
    var pinnedCodes: [String] = []
    var orderedCodes: [String] = []
    var hiddenCodes: [String] = []

    init() {}

    init?(rawValue: String) {
        guard let data = rawValue.data(using: .utf8),
              let decoded = try? JSONDecoder().decode(Payload.self, from: data)
        else { self = LabDisplayPreferences(); return }
        pinnedCodes = decoded.pinnedCodes
        orderedCodes = decoded.orderedCodes
        hiddenCodes = decoded.hiddenCodes
    }

    var rawValue: String {
        let payload = Payload(pinnedCodes: pinnedCodes, orderedCodes: orderedCodes, hiddenCodes: hiddenCodes)
        return (try? JSONEncoder().encode(payload)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    var pinnedSet: Set<String> { Set(pinnedCodes) }
    var hiddenSet: Set<String> { Set(hiddenCodes) }

    // Separate Codable type breaks the Codable+RawRepresentable encoding cycle.
    // If LabDisplayPreferences itself were Codable, the stdlib's RawRepresentable
    // default encode(to:) would call self.rawValue → JSONEncoder.encode(self) → infinite recursion.
    private struct Payload: Codable {
        var pinnedCodes: [String]
        var orderedCodes: [String]
        var hiddenCodes: [String]
    }
}
