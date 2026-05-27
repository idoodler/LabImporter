import SwiftUI

struct LabValueRowView: View {
    @Binding var value: LabValue
    var anyFieldFocused: FocusState<Bool>.Binding

    @State private var editedValue: String = ""
    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Toggle("", isOn: $value.isSelected)
                .labelsHidden()

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(value.name)
                        .font(.body)

                    if !value.canImportToHealth {
                        Image(systemName: "cross.case")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .help("Not supported by Apple Health — included in CDA only")
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
        .onAppear { editedValue = value.displayValue }
        .onChange(of: value.displayValue) { _, new in
            if editedValue != new { editedValue = new }
        }
        .onChange(of: isFocused) { _, focused in
            // Mirror local focus state up so the toolbar Done button works
            if focused { anyFieldFocused.wrappedValue = true }
        }
        .onChange(of: anyFieldFocused.wrappedValue) { _, globalFocused in
            if !globalFocused { isFocused = false }
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
    @Previewable @FocusState var focused: Bool
    List {
        LabValueRowView(value: $value, anyFieldFocused: $focused)
    }
}
