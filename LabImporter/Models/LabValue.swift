import Foundation
import HealthKit

// @unchecked Sendable: struct is passed by value across actor boundaries;
// all mutation stays within @MainActor, so concurrent access cannot occur.
struct LabValue: Identifiable, Equatable, @unchecked Sendable {
    let id: UUID
    var code: String
    var name: String
    var displayValue: String
    var numericValue: Double?
    var unit: String
    var healthKitMapping: HealthKitMapping?
    var isSelected: Bool

    var canImportToHealth: Bool {
        healthKitMapping != nil && numericValue != nil
    }

    init(
        id: UUID = UUID(),
        code: String,
        name: String,
        displayValue: String,
        numericValue: Double?,
        unit: String,
        healthKitMapping: HealthKitMapping? = nil,
        isSelected: Bool = true
    ) {
        self.id = id
        self.code = code
        self.name = name
        self.displayValue = displayValue
        self.numericValue = numericValue
        self.unit = unit
        self.healthKitMapping = healthKitMapping
        self.isSelected = isSelected
    }

    static func == (lhs: LabValue, rhs: LabValue) -> Bool {
        lhs.id == rhs.id
    }
}

struct HealthKitMapping: Equatable, @unchecked Sendable {
    let quantityTypeIdentifier: HKQuantityTypeIdentifier
    let unit: HKUnit
    let valueConversion: (@Sendable (Double) -> Double)?

    init(
        identifier: HKQuantityTypeIdentifier,
        unit: HKUnit,
        conversion: (@Sendable (Double) -> Double)? = nil
    ) {
        self.quantityTypeIdentifier = identifier
        self.unit = unit
        self.valueConversion = conversion
    }

    static func == (lhs: HealthKitMapping, rhs: HealthKitMapping) -> Bool {
        lhs.quantityTypeIdentifier == rhs.quantityTypeIdentifier
    }
}
