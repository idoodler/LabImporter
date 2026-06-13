import HealthKit
import Foundation

// Read-only vitals & glucose access used by the AI chat to contextualize a
// conversation (see `ChatTools`/`LabChatService`). Kept in its own file so
// `HealthKitService.swift` stays focused on the CDA round-trip. Everything here
// only ever reads — the chat never writes Health data.
extension HealthKitService {

    /// A single quantity reading, flattened into a `Sendable` value so it can
    /// cross into the chat tools (and the `LanguageModelSession` actor) without
    /// dragging HealthKit types along.
    struct HealthReading: Sendable {
        let date: Date
        let value: Double
        let unit: String
    }

    /// The non-lab Health metrics the AI chat can read — vitals plus the
    /// diabetes-relevant glucose/weight signals.
    enum VitalKind: String, CaseIterable, Sendable {
        case bloodGlucose
        case bodyMass
        case bodyMassIndex
        case systolicBP
        case diastolicBP
        case restingHeartRate

        var identifier: HKQuantityTypeIdentifier {
            switch self {
            case .bloodGlucose:     return .bloodGlucose
            case .bodyMass:         return .bodyMass
            case .bodyMassIndex:    return .bodyMassIndex
            case .systolicBP:       return .bloodPressureSystolic
            case .diastolicBP:      return .bloodPressureDiastolic
            case .restingHeartRate: return .restingHeartRate
            }
        }

        /// The HealthKit unit values are read in. Kept stable (not locale-driven)
        /// so the figures handed to the model are predictable.
        var unitString: String {
            switch self {
            case .bloodGlucose:     return "mg/dL"
            case .bodyMass:         return "kg"
            case .bodyMassIndex:    return "count"
            case .systolicBP, .diastolicBP: return "mmHg"
            case .restingHeartRate: return "count/min"
            }
        }

        /// Human-facing unit label used when describing the reading to the model.
        var unitLabel: String {
            switch self {
            case .bloodGlucose:     return "mg/dL"
            case .bodyMass:         return "kg"
            case .bodyMassIndex:    return "BMI"
            case .systolicBP, .diastolicBP: return "mmHg"
            case .restingHeartRate: return "bpm"
            }
        }

        /// Plain-English label used to introduce the metric in tool output.
        var label: String {
            switch self {
            case .bloodGlucose:     return "Blood glucose"
            case .bodyMass:         return "Body weight"
            case .bodyMassIndex:    return "Body mass index"
            case .systolicBP:       return "Systolic blood pressure"
            case .diastolicBP:      return "Diastolic blood pressure"
            case .restingHeartRate: return "Resting heart rate"
            }
        }
    }

    private var vitalTypes: Set<HKObjectType> {
        Set(VitalKind.allCases.compactMap { HKObjectType.quantityType(forIdentifier: $0.identifier) })
    }

    /// Requests read access to the vitals/metrics + characteristics the chat
    /// uses. Best-effort and silent: HealthKit hides read-permission status, so
    /// the chat simply degrades (a tool returns nothing) when a type is denied.
    func requestVitalsAuthorization() async {
        var read = vitalTypes
        if let dob = HKObjectType.characteristicType(forIdentifier: .dateOfBirth) { read.insert(dob) }
        if let sex = HKObjectType.characteristicType(forIdentifier: .biologicalSex) { read.insert(sex) }
        try? await store.requestAuthorization(toShare: [], read: read)
    }

    /// Recent readings for a metric, newest first, over the last `days` days.
    func readings(for kind: VitalKind, days: Int, limit: Int = 60) async throws -> [HealthReading] {
        guard let type = HKObjectType.quantityType(forIdentifier: kind.identifier) else { return [] }
        let start = Calendar.current.date(byAdding: .day, value: -max(days, 1), to: Date())
        let predicate = HKQuery.predicateForSamples(withStart: start, end: nil, options: [])

        return try await withCheckedThrowingContinuation { continuation in
            var finished = false
            let query = HKSampleQuery(
                sampleType: type,
                predicate: predicate,
                limit: limit,
                sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: false)]
            ) { _, samples, error in
                guard !finished else { return }
                finished = true
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                // Build the unit inside the handler so only the Sendable `kind`
                // is captured, not a non-Sendable HKUnit.
                let unit = HKUnit(from: kind.unitString)
                let readings = (samples as? [HKQuantitySample] ?? []).map {
                    HealthReading(date: $0.startDate, value: $0.quantity.doubleValue(for: unit), unit: kind.unitLabel)
                }
                continuation.resume(returning: readings)
            }
            store.execute(query)
        }
    }
}
