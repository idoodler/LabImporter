import SwiftUI

struct LabValueRowView: View {
    @Binding var value: LabValue
    /// True when another exportable row carries the same LOINC code; surfaces a
    /// "Duplicate" badge so the user can resolve the collision before saving.
    var isDuplicate: Bool = false

    @State private var showCodePicker = false
    @FocusState private var isFocused: Bool

    private var hasLoincCode: Bool {
        LabMapping.loincCode(for: value.code) != nil
    }

    /// Where this row's value sits relative to the user's reference range for the
    /// code, or `nil` when there is no value or no range (so no badge shows).
    private var rangeStatus: RangeStatus? {
        LabMapping.rangeStatus(for: value.numericValue, code: value.code)
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
                    Text(value.resolvedName)
                        .font(.body)

                    if value.isSuggestedCode {
                        Text("Suggested")
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.accentColor.opacity(0.15), in: Capsule())
                            .foregroundStyle(Color.accentColor)
                    } else if !hasLoincCode {
                        Image(systemName: "doc.badge.minus")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .help("No LOINC code — excluded from CDA export")
                    }

                    if isDuplicate {
                        Label("Duplicate", systemImage: "exclamationmark.triangle.fill")
                            .labelStyle(.titleAndIcon)
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.orange.opacity(0.15), in: Capsule())
                            .foregroundStyle(.orange)
                            .help("Another value uses the same LOINC code — keep only one")
                    }

                    if let rangeStatus {
                        RangeStatusBadge(status: rangeStatus,
                                         range: LabMapping.referenceRange(for: value.code),
                                         unit: value.unit)
                    }
                }

                Button { showCodePicker = true } label: {
                    HStack(spacing: 3) {
                        Text(value.code)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .textCase(.uppercase)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 7))
                            .foregroundStyle(.quaternary)
                    }
                }
                .buttonStyle(.plain)
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
        .sheet(isPresented: $showCodePicker, onDismiss: { value.isSuggestedCode = false }, content: {
            CodePickerSheet(code: $value.code, name: $value.name)
        })
    }

    @ViewBuilder
    private var valueField: some View {
        if value.displayValue == "-" {
            Text("–")
                .font(.body)
                .foregroundStyle(.secondary)
        } else {
            TextField("Value", text: valueText)
                .multilineTextAlignment(.trailing)
                .keyboardType(.decimalPad)
                .focused($isFocused)
                .frame(minWidth: 44, maxWidth: 80)
        }
    }

    // A binding over the model's value, stripped of its unit for editing. Reading
    // derives the string on demand and writing propagates the user's edit — so we
    // never mutate the model just because the row appeared (which previously
    // re-parsed a lossily-formatted value and looked like an edit).
    private var valueText: Binding<String> {
        Binding(
            get: { strippedValue(value.displayValue) },
            set: { newRaw in
                value.displayValue = newRaw
                let normalized = newRaw.replacingOccurrences(of: ",", with: ".")
                value.numericValue = Double(normalized)
            }
        )
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
