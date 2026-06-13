import Foundation
import FoundationModels

// Read-only data access for the AI chat. The actual fetching/formatting lives in
// `ChatData` so it can be used two ways without diverging:
//   1. proactively, to seed a data snapshot into the session instructions at
//      conversation start (see `LabChatService`), so the specialist always has
//      the user's data even if the on-device model doesn't think to call a tool;
//   2. on demand, through the Foundation Models `Tool`s below, for follow-up
//      look-ups (a single test's full history, a different metric, …).
//
// Everything only reads — already-loaded `[LabReport]` plus the local HealthKit
// store via `HealthKitService.shared`. Nothing writes Health or leaves the device.

// MARK: - Activity reporting

/// Collects which of the user's data was accessed, so the chat UI can show it
/// transparently. Thread-safe because tools call it from background executors;
/// it forwards each event to a handler the view model installs (hopping to the
/// main actor there).
final class ChatToolReporter: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (@Sendable (ChatToolActivity) -> Void)?

    func setHandler(_ handler: (@Sendable (ChatToolActivity) -> Void)?) {
        lock.lock(); defer { lock.unlock() }
        self.handler = handler
    }

    func report(_ activity: ChatToolActivity) {
        lock.lock(); let handler = self.handler; lock.unlock()
        handler?(activity)
    }
}

// MARK: - Shared data + formatting

enum ChatData {
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

    /// The values from the user's most recent report, optionally filtered to a
    /// clinical area, with the specialist's focus areas marked.
    static func latestLabs(reports: [LabReport], focusDomains: [LabCategory], category: String) -> String {
        guard let latest = reports.max(by: { $0.date < $1.date }) else {
            return "The user has no saved lab reports yet."
        }
        let filter = category.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        var lines: [String] = []
        for entry in latest.entries {
            let cat = LabCategory.forCode(entry.code)
            if !filter.isEmpty {
                let matches = cat.displayName.lowercased().contains(filter)
                    || entry.resolvedName.lowercased().contains(filter)
                guard matches else { continue }
            }
            let shown = entry.numericValue.map(number) ?? entry.displayValue
            let mark = focusDomains.contains(cat) ? " *" : ""
            lines.append("\(entry.resolvedName): \(shown) \(entry.unit)".trimmingCharacters(in: .whitespaces) + mark)
        }
        guard !lines.isEmpty else {
            return filter.isEmpty
                ? "The most recent report contains no values."
                : "No results in the most recent report match '\(category)'."
        }
        let focusNote = focusDomains.isEmpty ? "" : "\n(* = within this specialist's focus area.)"
        return "Most recent report, dated \(day(latest.date)):\n" + lines.joined(separator: "\n") + focusNote
    }

    /// The dated history of a single test across all reports, oldest to newest.
    static func labHistory(reports: [LabReport], testName: String) -> String {
        let query = testName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return "No test name was provided." }

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
                let shown = entry.numericValue.map(number) ?? entry.displayValue
                points.append(Point(date: report.date, value: shown, unit: entry.unit, name: entry.resolvedName))
            }
        }
        guard !points.isEmpty else {
            return "No measurements for '\(query)' were found in the user's saved reports."
        }
        points.sort { $0.date < $1.date }
        let label = points.last?.name ?? query
        let lines = points.map { "\(day($0.date)): \($0.value) \($0.unit)".trimmingCharacters(in: .whitespaces) }
        return "History for \(label) (oldest to newest):\n" + lines.joined(separator: "\n")
    }

    /// Recent readings from Apple Health for the given metrics. Requests read
    /// access first (scoped to those metrics) when `requestAccess` is true.
    static func vitals(kinds: [HealthKitService.VitalKind], days: Int, requestAccess: Bool) async -> String {
        let service = HealthKitService.shared
        if requestAccess { await service.requestAuthorization(for: kinds) }
        var blocks: [String] = []
        for kind in kinds {
            let readings = (try? await service.readings(for: kind, days: days)) ?? []
            guard let latest = readings.first else { continue }
            let values = readings.map(\.value)
            let minV = values.min() ?? latest.value
            let maxV = values.max() ?? latest.value
            var line = "\(kind.label): latest \(number(latest.value)) \(latest.unit) on \(day(latest.date))"
            if readings.count > 1 {
                line += "; range over \(days)d: \(number(minV))–\(number(maxV)) \(latest.unit) (\(readings.count) readings)"
            }
            blocks.append(line)
        }
        guard !blocks.isEmpty else {
            return "No vitals or glucose readings are available in Apple Health for the last \(days) days."
        }
        return "Recent vitals from Apple Health:\n" + blocks.joined(separator: "\n")
    }

    /// The user's age and biological sex from Apple Health, for context.
    static func profile() async -> String {
        let characteristics = (try? await HealthKitService.shared.readPatientCharacteristics())
            ?? .init(dateOfBirth: nil, biologicalSexRaw: nil)
        var parts: [String] = []
        if let dob = characteristics.dateOfBirth,
           let years = Calendar.current.dateComponents([.year], from: dob, to: Date()).year {
            parts.append("age \(years)")
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

// MARK: - Lab history tool

/// Looks up the history of one lab test across all of the user's saved reports.
struct LabHistoryTool: Tool {
    let name = "getLabHistory"
    let description = """
    Look up the dated history of a single laboratory test (e.g. "HbA1c", \
    "glucose", "LDL cholesterol", "creatinine") from the user's saved lab \
    reports. Returns each measurement with its date, value and unit, oldest to \
    newest. Call this to discuss how a specific lab value has changed over time.
    """

    let reports: [LabReport]
    let reporter: ChatToolReporter

    @Generable
    struct Arguments {
        @Guide(description: "The lab test to look up, e.g. 'HbA1c', 'fasting glucose', 'creatinine'.")
        var testName: String
    }

    func call(arguments: Arguments) async throws -> String {
        reporter.report(.labHistory)
        return ChatData.labHistory(reports: reports, testName: arguments.testName)
    }
}

// MARK: - Latest panel tool

/// Returns the values from the user's most recent lab report.
struct LatestLabsTool: Tool {
    let name = "getLatestLabResults"
    let description = """
    Get the values from the user's most recent lab report — every test with its \
    value, unit and the report date. Call this for an overview, or when the user \
    asks "what are my latest results".
    """

    let reports: [LabReport]
    let focusDomains: [LabCategory]
    let reporter: ChatToolReporter

    @Generable
    struct Arguments {
        @Guide(description: "Optional clinical area to filter by, e.g. 'glucose', 'lipids', 'kidney'. Empty string returns all results.")
        var category: String
    }

    func call(arguments: Arguments) async throws -> String {
        reporter.report(.latestLabs)
        return ChatData.latestLabs(reports: reports, focusDomains: focusDomains, category: arguments.category)
    }
}

// MARK: - Vitals tool

/// Reads recent vitals and glucose readings from Apple Health.
struct VitalsTool: Tool {
    let name = "getVitals"
    let description = """
    Read the user's recent vital signs and metrics logged in Apple Health that \
    are relevant to this specialist (e.g. blood glucose, insulin and carbs for \
    diabetes; blood pressure, heart-rate variability and VO2 max for the heart). \
    Returns the latest reading and the range over the window for each metric \
    with data. Call this for any question about blood sugar/glucose, weight, \
    blood pressure, heart rate or other vitals.
    """

    /// The Apple Health metrics this specialist reads, derived from its domains.
    let kinds: [HealthKitService.VitalKind]
    let reporter: ChatToolReporter

    @Generable
    struct Arguments {
        @Guide(description: "How many days back to look. Use 90 for a recent picture; up to 365.")
        var days: Int
    }

    func call(arguments: Arguments) async throws -> String {
        reporter.report(.vitals)
        return await ChatData.vitals(kinds: kinds, days: min(max(arguments.days, 1), 365), requestAccess: true)
    }
}

// MARK: - Profile tool

/// Returns the user's age and biological sex for context.
struct ProfileTool: Tool {
    let name = "getProfile"
    let description = """
    Get the user's age and biological sex from Apple Health, for context when \
    interpreting values whose typical ranges depend on age or sex.
    """

    let reporter: ChatToolReporter

    @Generable
    struct Arguments {
        @Guide(description: "Pass any short reason you need the profile, e.g. 'age for reference range'.")
        var reason: String
    }

    func call(arguments: Arguments) async throws -> String {
        reporter.report(.profile)
        return await ChatData.profile()
    }
}
