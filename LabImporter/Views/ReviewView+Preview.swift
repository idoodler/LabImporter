import SwiftUI

#Preview {
    NavigationStack {
        ReviewView(
            labValues: [
                LabValue(code: "2345-7", name: "Blood Glucose", displayValue: "95", numericValue: 95, unit: "mg/dl"),
                LabValue(code: "2160-0", name: "Creatinine", displayValue: "0.91", numericValue: 0.91, unit: "mg/dl"),
                LabValue(code: "4548-4", name: "HbA1c (%)", displayValue: "6.5", numericValue: 6.5, unit: "%"),
                LabValue(code: "2093-3", name: "Total Cholesterol", displayValue: "162", numericValue: 162, unit: "mg/dl"),
            ],
            extractedPatientName: "Max Mustermann"
        )
    }
}
