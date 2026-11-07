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

#Preview("With Qualitative Results") {
    NavigationStack {
        ReviewView(
            labValues: LabValue.sampleValues + LabValue.sampleQualitative,
            extractedPatientName: "Max Mustermann"
        )
    }
}

#Preview("Values to Check, No Date") {
    NavigationStack {
        ReviewView(
            labValues: LabValue.sampleToCheck + LabValue.sampleValues,
            isDateMissing: true,
            extractedPatientName: "Max Mustermann",
            extractedAuthorName: "Laborzentrum München"
        )
    }
}

#Preview("Values to Check, No Date – Dark") {
    NavigationStack {
        ReviewView(
            labValues: LabValue.sampleToCheck + LabValue.sampleValues,
            isDateMissing: true,
            extractedPatientName: "Max Mustermann",
            extractedAuthorName: "Laborzentrum München"
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

#Preview("Header Card – To Correct") {
    ReviewHeaderCard(
        supportedCount: 10,
        exportableCount: 8,
        valuesToCorrect: 1,
        valuesToCheck: 1,
        groups: CategoryCount.sampleGroups,
        dominantColor: LabCategory.lipids.color
    )
    .padding()
    .background(Color(.systemGroupedBackground))
}

#Preview("Header Card – To Correct, Dark") {
    ReviewHeaderCard(
        supportedCount: 10,
        exportableCount: 8,
        valuesToCorrect: 2,
        groups: CategoryCount.sampleGroups,
        dominantColor: LabCategory.lipids.color
    )
    .padding()
    .background(Color(.systemGroupedBackground))
    .preferredColorScheme(.dark)
}

#Preview("Action Bar") {
    VStack {
        Spacer()
        ReviewActionBar(isEnabled: true, onSave: {}, onShare: {})
    }
}

#Preview("Action Bar – Landscape", traits: .landscapeLeft) {
    VStack {
        Spacer()
        ReviewActionBar(isEnabled: true, onSave: {}, onShare: {})
    }
}

#Preview("Action Bar – Dark") {
    VStack {
        Spacer()
        ReviewActionBar(isEnabled: true, onSave: {}, onShare: {})
    }
    .preferredColorScheme(.dark)
}

#Preview("Action Bar – Duplicates") {
    VStack {
        Spacer()
        ReviewActionBar(isEnabled: false, hasDuplicates: true, onSave: {}, onShare: {})
    }
}

#Preview("Action Bar – To Correct") {
    VStack {
        Spacer()
        ReviewActionBar(isEnabled: false, valuesToCorrect: 2, onSave: {}, onShare: {})
    }
}

#Preview("Action Bar – To Correct, Dark") {
    VStack {
        Spacer()
        ReviewActionBar(isEnabled: false, valuesToCorrect: 1, onSave: {}, onShare: {})
    }
    .preferredColorScheme(.dark)
}
#endif
