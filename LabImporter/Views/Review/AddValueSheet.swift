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
                    // so the picker drives the name and the canonical unit. The chosen
                    // test is shown as a rich, category-tinted row (the user's alias
                    // when set, else the catalog name); renaming lives in Sort &
                    // Visibility, not here.
                    NavigationLink {
                        AddCodePickerPage(code: $code, name: $name)
                    } label: {
                        testRow
                    }
                    .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                }
                Section {
                    HStack(spacing: 12) {
                        Image(systemName: "number")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(accent)
                            .frame(width: 24)
                        TextField("Value", text: $displayValue)
                            .keyboardType(.decimalPad)
                    }
                    HStack(spacing: 12) {
                        Image(systemName: "ruler")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(accent)
                            .frame(width: 24)
                        TextField("Unit (optional)", text: $unit)
                            .autocorrectionDisabled()
                    }
                } header: {
                    Text("Measurement")
                }
                .listRowBackground(Rectangle().fill(.ultraThinMaterial))
            }
            .scrollContentBackground(.hidden)
            .background { CategoryBackground(colors: backgroundColors) }
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

    // MARK: - Test row

    /// The chosen lab test, or a prompt to pick one — a category-tinted icon with
    /// the resolved name and a category chip, matching the term-detail and history
    /// rows so a test reads the same everywhere.
    private var testRow: some View {
        HStack(spacing: 14) {
            CategoryIcon(color: accent, size: 44)
            VStack(alignment: .leading, spacing: 4) {
                if code.isEmpty {
                    Text("Choose a lab test")
                        .font(.headline)
                    Text("Required")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text(LabMapping.displayName(for: code))
                        .font(.headline)
                        .lineLimit(2)
                    HStack(spacing: 8) {
                        if let category {
                            categoryChip(category)
                        }
                        Text(code)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                            .textCase(.uppercase)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    private func categoryChip(_ category: LabCategory) -> some View {
        HStack(spacing: 5) {
            Circle()
                .fill(category.color)
                .frame(width: 7, height: 7)
            Text(category.displayName)
                .font(.caption2.weight(.medium))
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(category.color.opacity(0.12), in: Capsule())
        .overlay(Capsule().stroke(category.color.opacity(0.25), lineWidth: 0.5))
    }

    // The chosen test's clinical category (nil until a code is picked) drives the
    // icon tint, the chip and the background wash.
    private var category: LabCategory? {
        code.isEmpty ? nil : LabCategory.forCode(code)
    }

    private var accent: Color {
        category?.color ?? .accentColor
    }

    private var backgroundColors: [Color] {
        category.map { [$0.color] } ?? []
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
