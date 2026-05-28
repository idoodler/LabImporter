import SwiftUI

struct LabValueRowView: View {
    @Binding var value: LabValue

    @State private var editedValue: String = ""
    @FocusState private var isFocused: Bool

    private var hasLoincCode: Bool {
        LabMapping.loincCode(for: value.code) != nil
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Button {
                value.isSelected = !value.isSelected
            } label: {
                Image(systemName: value.isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(value.isSelected ? Color.accentColor : Color.secondary)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(value.name)
                        .font(.body)

                    if !hasLoincCode {
                        Image(systemName: "doc.badge.minus")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .help("No LOINC code — excluded from CDA export")
                    }
                }

                Text(value.code)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .textCase(.uppercase)
            }

            Spacer()

            HStack(spacing: 6) {
                valueField

                if !value.unit.isEmpty {
                    Text(value.unit)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .fixedSize()
                }
            }
        }
        .padding(.vertical, 2)
        .onAppear { editedValue = strippedDisplayValue }
        .onChange(of: value.displayValue) { _, new in
            let stripped = strippedValue(new)
            if editedValue != stripped { editedValue = stripped }
        }
    }

    @ViewBuilder
    private var valueField: some View {
        if value.displayValue == "-" {
            Text("–")
                .font(.body)
                .foregroundStyle(.secondary)
        } else {
            TextField("Value", text: $editedValue)
                .multilineTextAlignment(.trailing)
                .keyboardType(.decimalPad)
                .focused($isFocused)
                .frame(minWidth: 44, maxWidth: 80)
                .onChange(of: editedValue) { _, newRaw in
                    value.displayValue = newRaw
                    let normalized = newRaw.replacingOccurrences(of: ",", with: ".")
                    value.numericValue = Double(normalized)
                }
        }
    }

    // Strips the unit suffix from displayValue so it shows only the number.
    private var strippedDisplayValue: String {
        strippedValue(value.displayValue)
    }

    private func strippedValue(_ raw: String) -> String {
        guard !value.unit.isEmpty else { return raw }
        for suffix in [" \(value.unit)", value.unit] where raw.lowercased().hasSuffix(suffix.lowercased()) {
            return String(raw.dropLast(suffix.count)).trimmingCharacters(in: .whitespaces)
        }
        return raw
    }
}

// MARK: - Preview

#Preview {
    @Previewable @State var value = LabValue(
        code: "KREA",
        name: "Creatinine",
        displayValue: "0.91",
        numericValue: 0.91,
        unit: "mg/dl"
    )
    List {
        LabValueRowView(value: $value)
    }
}
