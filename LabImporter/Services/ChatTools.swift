import Foundation
import FoundationModels

// Read-only tools the AI chat calls to ground its answers in the user's own
// data. Each conforms to Foundation Models' `Tool`: the model decides when to
// call them, the framework fills the `@Generable` arguments, and the returned
// string is fed back into the conversation. Nothing here writes Health data,
// makes a network request, or leaves the device.
//
// The tools are handed a snapshot of the already-loaded `[LabReport]` (the same
// reports the dashboard shows) and reach the live HealthKit store through
// `HealthKitService.shared` for vitals/glucose. The set of tools a session gets
// is assembled per-persona in `LabChatService`.

// MARK: - Shared helpers

private enum ToolFormat {
    static func day(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter.string(from: date)
    }

    /// Trims a number to a compact string (no trailing ".0").
    static func number(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0
            ? String(format: "%.0f", value)
            : String(format: "%.4g", value)
    }
}

// MARK: - Lab history

/// Looks up the history of one lab test across all of the user's saved reports.
struct LabHistoryTool: Tool {
    let name = "getLabHistory"
    let description = """
    Look up the dated history of a single laboratory test (e.g. "HbA1c", \
    "glucose", "LDL cholesterol", "creatinine") from the user's saved lab \
    reports. Returns each measurement with its date, value and unit, oldest to \
    newest. Call this to discuss how a specific lab value has changed.
    """

    let reports: [LabReport]

    @Generable
    struct Arguments {
        @Guide(description: "The lab test to look up, e.g. 'HbA1c', 'fasting glucose', 'creatinine'.")
        var testName: String
    }

    func call(arguments: Arguments) async throws -> String {
        let query = arguments.testName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return "No test name was provided." }

        // Resolve the free-text query to one or more LOINC codes via the catalog,
        // then collect every matching entry across the reports.
        let directory = LoincDirectory.shared
        var codes = Set<String>()
        if directory.isKnownLoinc(query) {
            codes.insert(query)
        } else {
            for term in directory.search(query, limit: 5) { codes.insert(term.code) }
        }

        struct Point { let date: Date; let value: String; let unit: String; let name: String }
        var points: [Point] = []
        let lowerQuery = query.lowercased()
        for report in reports {
            for entry in report.entries {
                let matchesCode = codes.contains(entry.code)
                let matchesName = entry.resolvedName.lowercased().contains(lowerQuery)
                guard matchesCode || matchesName else { continue }
                let shown = entry.numericValue.map(ToolFormat.number) ?? entry.displayValue
                points.append(Point(date: report.date, value: shown, unit: entry.unit, name: entry.resolvedName))
            }
        }

        guard !points.isEmpty else {
            return "No measurements for '\(query)' were found in the user's saved reports."
        }
        points.sort { $0.date < $1.date }
        let label = points.last?.name ?? query
        let lines = points.map { "\(ToolFormat.day($0.date)): \($0.value) \($0.unit)".trimmingCharacters(in: .whitespaces) }
        return "History for \(label) (oldest to newest):\n" + lines.joined(separator: "\n")
    }
}

// MARK: - Latest panel

/// Returns the values from the user's most recent lab report.
struct LatestLabsTool: Tool {
    let name = "getLatestLabResults"
    let description = """
    Get the values from the user's most recent lab report — every test with its \
    value, unit and the report date. Call this for an overview, or when the user \
    asks "what are my latest results".
    """

    let reports: [LabReport]
    /// The persona's focus areas, used only to note which results are most
    /// relevant to this specialist. Empty for the general practitioner.
    let focusDomains: [LabCategory]

    @Generable
    struct Arguments {
        @Guide(description: "Optional clinical area to filter by, e.g. 'glucose', 'lipids', 'kidney'. Empty string returns all results.")
        var category: String
    }

    func call(arguments: Arguments) async throws -> String {
        guard let latest = reports.max(by: { $0.date < $1.date }) else {
            return "The user has no saved lab reports yet."
        }
        let filter = arguments.category.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()

        var lines: [String] = []
        for entry in latest.entries {
            let category = LabCategory.forCode(entry.code)
            if !filter.isEmpty {
                let matches = category.displayName.lowercased().contains(filter)
                    || entry.resolvedName.lowercased().contains(filter)
                guard matches else { continue }
            }
            let shown = entry.numericValue.map(ToolFormat.number) ?? entry.displayValue
            let focusMark = focusDomains.contains(category) ? " *" : ""
            lines.append("\(entry.resolvedName): \(shown) \(entry.unit)".trimmingCharacters(in: .whitespaces) + focusMark)
        }

        guard !lines.isEmpty else {
            return "No results in the most recent report match '\(arguments.category)'."
        }
        let focusNote = focusDomains.isEmpty ? "" : "\n(* = within this specialist's focus area.)"
        return "Most recent report, dated \(ToolFormat.day(latest.date)):\n" + lines.joined(separator: "\n") + focusNote
    }
}

// MARK: - Vitals

/// Reads recent vitals and glucose readings from Apple Health.
struct VitalsTool: Tool {
    let name = "getVitals"
    let description = """
    Read the user's recent vital signs and metrics logged in Apple Health that \
    are relevant to this specialist (e.g. blood glucose, insulin and carbs for \
    diabetes; blood pressure, heart-rate variability and VO2 max for the heart). \
    Returns the latest reading and the range over the window for each metric \
    with data. ALWAYS call this for any question about blood sugar/glucose, \
    weight, blood pressure, heart rate or other vitals — that data lives in \
    Apple Health, not the lab reports.
    """

    /// The Apple Health metrics this specialist reads, derived from its domains.
    let kinds: [HealthKitService.VitalKind]

    @Generable
    struct Arguments {
        @Guide(description: "How many days back to look. Use 90 for a recent picture; up to 365.")
        var days: Int
    }

    func call(arguments: Arguments) async throws -> String {
        let days = min(max(arguments.days, 1), 365)
        let service = HealthKitService.shared
        // Ask for read access to just this specialist's metrics, on demand, the
        // first time the model reaches for them. HealthKit only surfaces the
        // system sheet for types the user hasn't decided on yet.
        await service.requestAuthorization(for: kinds)
        var blocks: [String] = []

        for kind in kinds {
            let readings = (try? await service.readings(for: kind, days: days)) ?? []
            guard let latest = readings.first else { continue }
            let values = readings.map(\.value)
            let minV = values.min() ?? latest.value
            let maxV = values.max() ?? latest.value
            var line = "\(kind.label): latest \(ToolFormat.number(latest.value)) \(latest.unit)"
                + " on \(ToolFormat.day(latest.date))"
            if readings.count > 1 {
                line += "; range over \(days)d: \(ToolFormat.number(minV))–\(ToolFormat.number(maxV)) \(latest.unit)"
                    + " (\(readings.count) readings)"
            }
            blocks.append(line)
        }

        guard !blocks.isEmpty else {
            return "No vitals or glucose readings are available in Apple Health for the last \(days) days."
        }
        return "Recent vitals from Apple Health:\n" + blocks.joined(separator: "\n")
    }
}

// MARK: - Profile

/// Returns the user's age and biological sex for context.
struct ProfileTool: Tool {
    let name = "getProfile"
    let description = """
    Get the user's age and biological sex from Apple Health, for context when \
    interpreting values whose typical ranges depend on age or sex.
    """

    @Generable
    struct Arguments {
        @Guide(description: "Pass any short reason you need the profile, e.g. 'age for reference range'.")
        var reason: String
    }

    func call(arguments: Arguments) async throws -> String {
        let characteristics = try await HealthKitService.shared.readPatientCharacteristics()
        var parts: [String] = []
        if let dob = characteristics.dateOfBirth {
            let years = Calendar.current.dateComponents([.year], from: dob, to: Date()).year
            if let years { parts.append("age \(years)") }
        }
        switch characteristics.biologicalSexRaw {
        case 1: parts.append("female")
        case 2: parts.append("male")
        default: break
        }
        guard !parts.isEmpty else {
            return "The user's age and biological sex are not available in Apple Health."
        }
        return "User profile: " + parts.joined(separator: ", ") + "."
    }
}
