import Foundation

// @unchecked Sendable: struct is passed by value across actor boundaries;
// all mutation stays within @MainActor, so concurrent access cannot occur.
struct LabValue: Identifiable, Equatable, @unchecked Sendable {
    let id: UUID
    var code: String
    var name: String
    var displayValue: String
    var numericValue: Double?
    var unit: String
    var isSelected: Bool

    init(
        id: UUID = UUID(),
        code: String,
        name: String,
        displayValue: String,
        numericValue: Double?,
        unit: String,
        isSelected: Bool = true
    ) {
        self.id = id
        self.code = code
        self.name = name
        self.displayValue = displayValue
        self.numericValue = numericValue
        self.unit = unit
        self.isSelected = isSelected
    }

    static func == (lhs: LabValue, rhs: LabValue) -> Bool {
        lhs.id == rhs.id
    }
}
