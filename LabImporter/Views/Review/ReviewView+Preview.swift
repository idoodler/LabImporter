import SwiftUI

#if DEBUG
#Preview("New Report") {
    NavigationStack {
        ReviewView(
            labValues: LabValue.sampleValues,
            extractedPatientName: "Max Mustermann"
        )
    }
}

#Preview("Editing Saved Report") {
    NavigationStack {
        ReviewView(
            labValues: LabReport.sample.asLabValues,
            reportDate: LabReport.sample.date,
            replacingReport: .sample
        )
    }
}
#endif
