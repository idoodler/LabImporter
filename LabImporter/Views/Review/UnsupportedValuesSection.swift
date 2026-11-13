import SwiftUI

/// Section listing values that carry no LOINC code and therefore can't be saved
/// to Apple Health. Shown at the bottom of the review screen. Each is a full
/// review row: the user can pick its code (it then joins its category above),
/// leave it out with its include check, or delete it — so the question before
/// saving, which lists selected values left out, has an answer here too.
struct UnsupportedValuesSection: View {
    @Binding var labValues: [LabValue]
    /// The `labValues` indices to list, in screen order.
    let indices: [Int]

    var body: some View {
        Section {
            ForEach(indices, id: \.self) { idx in
                LabValueRowView(value: $labValues[idx])
                    .id(labValues[idx].id)
                    .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            labValues.remove(at: idx)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
            }
        } header: {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.statusWarning)
                Text("Not supported for export")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
            }
            .textCase(nil)
        } footer: {
            Text("These values don't have a LOINC code and won't be saved to Apple Health.")
        }
    }
}

// MARK: - Previews

private struct UnsupportedValuesPreview: View {
    @State var values: [LabValue]

    var body: some View {
        List {
            UnsupportedValuesSection(labValues: $values, indices: Array(values.indices))
        }
    }
}

#Preview("Unsupported Values") {
    UnsupportedValuesPreview(values: [
        LabValue(code: "CRP-X", name: "C-Reactive Protein",
                 displayValue: "3.1", numericValue: 3.1, unit: "mg/L"),
        LabValue(code: "FOO", name: "Unknown Marker",
                 displayValue: "1.2", numericValue: 1.2, unit: ""),
    ])
}

#Preview("Unsupported Values – Left Out") {
    UnsupportedValuesPreview(values: [
        LabValue(code: "KREA", name: "Kreatinin", displayValue: "0.9", numericValue: 0.9, unit: "mg/dL", isSelected: false),
    ])
}

#Preview("Unsupported Values – Dark") {
    UnsupportedValuesPreview(values: [
        LabValue(code: "KREA", name: "Kreatinin", displayValue: "0.9", numericValue: 0.9, unit: "mg/dL"),
    ])
    .preferredColorScheme(.dark)
}
