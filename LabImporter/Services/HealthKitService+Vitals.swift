import HealthKit
import Foundation

// Read-only vitals & metrics access used by the AI chat to contextualize a
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

    /// The non-lab Health metrics the AI chat can read. Each kind declares which
    /// clinical `domains` it's relevant to, so a specialist only ever requests
    /// and reads the Apple Health data that matters to it (see `relevant(for:)`).
    enum VitalKind: String, CaseIterable, Sendable {
        case bloodGlucose
        case insulinDelivery
        case dietaryCarbohydrates
        case bodyMass
        case bodyMassIndex
        case systolicBP
        case diastolicBP
        case restingHeartRate
        case heartRateVariability
        case vo2Max
        case bodyTemperature
        case dietarySodium

        var identifier: HKQuantityTypeIdentifier {
            switch self {
            case .bloodGlucose:         return .bloodGlucose
            case .insulinDelivery:      return .insulinDelivery
            case .dietaryCarbohydrates: return .dietaryCarbohydrates
            case .bodyMass:             return .bodyMass
            case .bodyMassIndex:        return .bodyMassIndex
            case .systolicBP:           return .bloodPressureSystolic
            case .diastolicBP:          return .bloodPressureDiastolic
            case .restingHeartRate:     return .restingHeartRate
            case .heartRateVariability: return .heartRateVariabilitySDNN
            case .vo2Max:               return .vo2Max
            case .bodyTemperature:      return .bodyTemperature
            case .dietarySodium:        return .dietarySodium
            }
        }

        /// The unit readings are returned in. Built programmatically (rather than
        /// from a string) so the exotic ones — VO2 max, HRV — are unambiguous.
        var hkUnit: HKUnit {
            switch self {
            case .bloodGlucose:
                return HKUnit.gramUnit(with: .milli).unitDivided(by: .literUnit(with: .deci))
            case .insulinDelivery:      return .internationalUnit()
            case .dietaryCarbohydrates: return .gram()
            case .bodyMass:             return .gramUnit(with: .kilo)
            case .bodyMassIndex:        return .count()
            case .systolicBP, .diastolicBP: return .millimeterOfMercury()
            case .restingHeartRate:     return HKUnit.count().unitDivided(by: .minute())
            case .heartRateVariability: return .secondUnit(with: .milli)
            case .vo2Max:
                let perKgMin = HKUnit.gramUnit(with: .kilo).unitMultiplied(by: .minute())
                return HKUnit.literUnit(with: .milli).unitDivided(by: perKgMin)
            case .bodyTemperature:      return .degreeCelsius()
            case .dietarySodium:        return .gramUnit(with: .milli)
            }
        }

        /// Human-facing unit label used when describing the reading to the model.
        var unitLabel: String {
            switch self {
            case .bloodGlucose:         return "mg/dL"
            case .insulinDelivery:      return "IU"
            case .dietaryCarbohydrates, .dietarySodium: return self == .dietarySodium ? "mg" : "g"
            case .bodyMass:             return "kg"
            case .bodyMassIndex:        return "BMI"
            case .systolicBP, .diastolicBP: return "mmHg"
            case .restingHeartRate:     return "bpm"
            case .heartRateVariability: return "ms"
            case .vo2Max:               return "mL/kg·min"
            case .bodyTemperature:      return "°C"
            }
        }

        /// Plain-English label used to introduce the metric in tool output.
        var label: String {
            switch self {
            case .bloodGlucose:         return "Blood glucose"
            case .insulinDelivery:      return "Insulin delivered"
            case .dietaryCarbohydrates: return "Carbohydrates"
            case .bodyMass:             return "Body weight"
            case .bodyMassIndex:        return "Body mass index"
            case .systolicBP:           return "Systolic blood pressure"
            case .diastolicBP:          return "Diastolic blood pressure"
            case .restingHeartRate:     return "Resting heart rate"
            case .heartRateVariability: return "Heart rate variability (SDNN)"
            case .vo2Max:               return "VO2 max"
            case .bodyTemperature:      return "Body temperature"
            case .dietarySodium:        return "Dietary sodium"
            }
        }

        /// Clinical domains this metric informs. Drives which specialist requests
        /// and reads it.
        var domains: Set<LabCategory> {
            switch self {
            case .bloodGlucose, .insulinDelivery:
                return [.glycemic]
            case .dietaryCarbohydrates:
                return [.glycemic, .nutrition]
            case .bodyMass:
                return [.glycemic, .cardiac, .lipids, .renal, .nutrition, .endocrine]
            case .bodyMassIndex:
                return [.glycemic, .cardiac, .lipids]
            case .systolicBP, .diastolicBP:
                return [.cardiac, .renal]
            case .restingHeartRate, .heartRateVariability, .vo2Max:
                return [.cardiac]
            case .bodyTemperature:
                return [.endocrine]
            case .dietarySodium:
                return [.cardiac, .renal]
            }
        }

        /// The metrics a specialist with these focus `domains` should query. A
        /// general practitioner (no domains) gets a broad everyday core set.
        static func relevant(for domains: [LabCategory]) -> [VitalKind] {
            guard !domains.isEmpty else {
                return [.bloodGlucose, .bodyMass, .bodyMassIndex,
                        .systolicBP, .diastolicBP, .restingHeartRate]
            }
            let focus = Set(domains)
            return allCases.filter { !$0.domains.isDisjoint(with: focus) }
        }
    }

    /// Requests read access to a specific set of metrics (plus the basic
    /// characteristics). Best-effort and silent: HealthKit hides read-permission
    /// status, so the chat simply degrades (a tool returns nothing) when a type
    /// is denied. Scoping to the specialist's metrics means each one only ever
    /// asks for the Apple Health data it actually uses.
    func requestAuthorization(for kinds: [VitalKind]) async {
        var read = Set(kinds.compactMap { HKObjectType.quantityType(forIdentifier: $0.identifier) as HKObjectType? })
        if let dob = HKObjectType.characteristicType(forIdentifier: .dateOfBirth) { read.insert(dob) }
        if let sex = HKObjectType.characteristicType(forIdentifier: .biologicalSex) { read.insert(sex) }
        guard !read.isEmpty else { return }
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
                let unit = kind.hkUnit
                let readings = (samples as? [HKQuantitySample] ?? []).map {
                    HealthReading(date: $0.startDate, value: $0.quantity.doubleValue(for: unit), unit: kind.unitLabel)
                }
                continuation.resume(returning: readings)
            }
            store.execute(query)
        }
    }
}
