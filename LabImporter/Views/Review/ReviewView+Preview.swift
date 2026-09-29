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

#Preview("New Report – Landscape", traits: .landscapeLeft) {
    NavigationStack {
        ReviewView(
            labValues: LabValue.sampleValues,
            extractedPatientName: "Max Mustermann"
        )
    }
}

#Preview("New Report – Dark") {
    NavigationStack {
        ReviewView(
            labValues: LabValue.sampleValues,
            extractedPatientName: "Max Mustermann"
        )
    }
    .preferredColorScheme(.dark)
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
