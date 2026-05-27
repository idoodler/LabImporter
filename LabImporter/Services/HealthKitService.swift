import HealthKit
import Foundation

struct ImportResult: Identifiable {
    let id = UUID()

    struct Entry {
        let value: LabValue
        let error: Error?

        var succeeded: Bool { error == nil }
    }

    let entries: [Entry]

    var imported: [LabValue] { entries.filter(\.succeeded).map(\.value) }
    var failed: [Entry] { entries.filter { !$0.succeeded } }
}

actor HealthKitService {

    private let store = HKHealthStore()

    static var isHealthDataAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    func requestAuthorization(for values: [LabValue]) async throws {
        let typesToWrite: Set<HKSampleType> = Set(
            values
                .compactMap(\.healthKitMapping)
                .compactMap { HKQuantityType($0.quantityTypeIdentifier) }
        )
        guard !typesToWrite.isEmpty else { return }
        try await store.requestAuthorization(toShare: typesToWrite, read: [])
    }

    func importValues(_ values: [LabValue], date: Date) async throws -> ImportResult {
        var entries: [ImportResult.Entry] = []

        for value in values where value.isSelected && value.canImportToHealth {
            guard
                let mapping = value.healthKitMapping,
                let numeric = value.numericValue
            else { continue }

            let finalValue = mapping.valueConversion?(numeric) ?? numeric
            let quantityType = HKQuantityType(mapping.quantityTypeIdentifier)
            let quantity = HKQuantity(unit: mapping.unit, doubleValue: finalValue)
            let sample = HKQuantitySample(
                type: quantityType,
                quantity: quantity,
                start: date,
                end: date,
                metadata: [HKMetadataKeyWasUserEntered: true]
            )

            do {
                try await store.save(sample)
                entries.append(.init(value: value, error: nil))
            } catch {
                entries.append(.init(value: value, error: error))
            }
        }

        return ImportResult(entries: entries)
    }

    func importCDADocument(_ xmlString: String, date: Date) async throws {
        guard let data = xmlString.data(using: .utf8) else { return }

        guard let documentType = HKObjectType.documentType(forIdentifier: .CDA) else { return }
        try await store.requestAuthorization(toShare: [documentType], read: [])

        let sample = try HKCDADocumentSample(data: data, start: date, end: date, device: nil, metadata: nil)
        try await store.save(sample)
    }
}
