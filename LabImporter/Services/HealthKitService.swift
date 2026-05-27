import HealthKit
import Foundation

actor HealthKitService {

    private let store = HKHealthStore()

    static var isHealthDataAvailable: Bool {
        HKHealthStore.isHealthDataAvailable()
    }

    func importCDADocument(_ xmlString: String, date: Date) async throws {
        guard let data = xmlString.data(using: .utf8) else { return }
        guard let documentType = HKObjectType.documentType(forIdentifier: .CDA) else { return }
        try await store.requestAuthorization(toShare: [documentType], read: [])
        let sample = try HKCDADocumentSample(data: data, start: date, end: date, metadata: nil)
        try await store.save(sample)
    }
}
