import SwiftUI

/// Manual single-value entry, presented from the review/edit screen's "Add Value"
/// menu. Assembles a `LabValue` from the form and hands it back to the caller; the
/// form resets on its own because SwiftUI recreates the sheet on each presentation.
struct AddValueSheet: View {
    let onAdd: (LabValue) -> Void

    @State private var name = ""
    @State private var code = ""
    @State private var displayValue = ""
    @State private var unit = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    // A LOINC code must be chosen first — every value is LOINC-based,
                    // so the picker drives the name (the user's alias when set, else
                    // the catalog name) and the canonical unit.
                    NavigationLink {
                        AddCodePickerPage(code: $code, name: $name)
                    } label: {
                        HStack {
                            Text("Lab Test")
                            Spacer()
                            Text(code.isEmpty ? "Required" : code)
                                .foregroundStyle(code.isEmpty ? .secondary : .primary)
                        }
                    }
                    TextField("Name", text: $name)
                        .autocorrectionDisabled()
                        .disabled(code.isEmpty)
                }
                Section {
                    TextField("Value", text: $displayValue)
                        .keyboardType(.decimalPad)
                    TextField("Unit (optional)", text: $unit)
                        .autocorrectionDisabled()
                }
            }
            .navigationTitle("Add Value")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(role: .close) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Add") { commit() }
                        .fontWeight(.semibold)
                        .disabled(code.isEmpty || displayValue.isEmpty)
                }
            }
            .onChange(of: code) { _, newCode in
                fillUnit(for: newCode)
            }
        }
    }

    /// Prefills the unit from the LOINC catalog's example UCUM unit when a lab
    /// test is chosen, so manual entries inherit the canonical unit. A previously
    /// entered unit is kept when the catalog has no example unit for the code.
    private func fillUnit(for code: String) {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty,
              let term = LoincDirectory.shared.term(for: trimmed),
              !term.ucum.isEmpty else { return }
        unit = term.ucum
    }

    private func commit() {
        // A LOINC code is required (the Add button is disabled until one is picked),
        // so there is no unmapped/"MANUAL" fallback — everything stays LOINC-based.
        let resolvedCode = code.uppercased().trimmingCharacters(in: .whitespaces)
        guard !resolvedCode.isEmpty else { return }
        let normalized = displayValue.replacingOccurrences(of: ",", with: ".")
        let value = LabValue(
            code: resolvedCode,
            name: name.trimmingCharacters(in: .whitespaces),
            displayValue: displayValue,
            numericValue: Double(normalized),
            unit: unit.trimmingCharacters(in: .whitespaces)
        )
        onAdd(value)
        dismiss()
    }
}

// MARK: - Preview

#Preview {
    AddValueSheet { _ in }
}
