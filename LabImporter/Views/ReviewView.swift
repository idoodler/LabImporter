import SwiftUI
import HealthKit

struct ReviewView: View {
    @State var labValues: [LabValue]
    @State private var reportDate = Date()
    @State private var isImporting = false
    @State private var importResult: ImportResult?
    @State private var importError: Error?
    @State private var unsupportedCopied = false

    @FocusState private var anyFieldFocused: Bool
    @Environment(\.dismiss) private var dismiss

    private let healthKitService = HealthKitService()

    private var importableCount: Int {
        labValues.filter { $0.isSelected && $0.canImportToHealth }.count
    }

    var body: some View {
        List {
            dateSection
            valuesSection
            infoSection
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Review Values")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            importButton
            keyboardDoneButton
        }
        .overlay { if isImporting { ProcessingView(message: "Importing to Apple Health…") } }
        .sheet(item: $importResult) { result in
            ImportResultView(result: result)
        }
        .alert("Import Error", isPresented: .constant(importError != nil)) {
            Button("OK") { importError = nil }
        } message: {
            Text(importError?.localizedDescription ?? "")
        }
    }

    // MARK: - Sections

    private var dateSection: some View {
        Section {
            DatePicker(
                "Report Date",
                selection: $reportDate,
                in: ...Date.now,
                displayedComponents: .date
            )
        }
    }

    private var valuesSection: some View {
        Section {
            ForEach($labValues) { $value in
                LabValueRowView(value: $value, anyFieldFocused: $anyFieldFocused)
            }
        } header: {
            Text("Lab Values — tap values to correct them")
        }
    }

    @ViewBuilder
    private var infoSection: some View {
        let unsupported = labValues.filter { !$0.canImportToHealth }
        if !unsupported.isEmpty {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Label(
                        "\(unsupported.count) value\(unsupported.count == 1 ? "" : "s") not supported by Apple Health",
                        systemImage: "info.circle"
                    )
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(.secondary)

                    Text(unsupported.map(\.name).joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.tertiary)

                    Text("Apple Health's writable API currently supports only a limited set of lab values. The values above are recorded in this report but cannot be imported.")
                        .font(.caption)
                        .foregroundStyle(.tertiary)

                    Button {
                        copyUnsupportedToClipboard(unsupported)
                    } label: {
                        Label(
                            unsupportedCopied ? "Copied!" : "Copy as Text",
                            systemImage: unsupportedCopied ? "checkmark" : "doc.on.doc"
                        )
                        .font(.caption.weight(.medium))
                    }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.blue)
                }
                .padding(.vertical, 4)
            }
        }
    }

    // MARK: - Toolbar

    private var importButton: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Button {
                Task { await performImport() }
            } label: {
                Text(importableCount > 0 ? "Import \(importableCount)" : "Import")
            }
            .disabled(importableCount == 0 || isImporting)
            .fontWeight(.semibold)
        }
    }

    private var keyboardDoneButton: some ToolbarContent {
        ToolbarItem(placement: .keyboard) {
            HStack {
                Spacer()
                Button("Done") { anyFieldFocused = false }
                    .fontWeight(.semibold)
            }
        }
    }

    // MARK: - Clipboard

    private func copyUnsupportedToClipboard(_ values: [LabValue]) {
        let lines = values.map { v -> String in
            let val = v.displayValue == "-" ? "negative" : "\(v.displayValue) \(v.unit)".trimmingCharacters(in: .whitespaces)
            return "\(v.name): \(val)"
        }
        UIPasteboard.general.string = lines.joined(separator: "\n")
        unsupportedCopied = true
        Task {
            try? await Task.sleep(for: .seconds(2))
            unsupportedCopied = false
        }
    }

    // MARK: - Import

    private func performImport() async {
        isImporting = true
        defer { isImporting = false }

        do {
            try await healthKitService.requestAuthorization(for: labValues)
            let result = try await healthKitService.importValues(labValues, date: reportDate)
            importResult = result
        } catch {
            importError = error
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ReviewView(labValues: [
            LabValue(code: "BZ", name: "Blood Glucose", displayValue: "95", numericValue: 95, unit: "mg/dl",
                     healthKitMapping: HealthKitMapping(identifier: .bloodGlucose, unit: HKUnit(from: "mg/dL"))),
            LabValue(code: "KREA", name: "Creatinine", displayValue: "0.91", numericValue: 0.91, unit: "mg/dl"),
            LabValue(code: "HB-A1C", name: "HbA1c (%)", displayValue: "6.5", numericValue: 6.5, unit: "%"),
            LabValue(code: "DIABOL", name: "Diabetes Screening", displayValue: "-", numericValue: nil, unit: ""),
            LabValue(code: "CHOL", name: "Total Cholesterol", displayValue: "162", numericValue: 162, unit: "mg/dl"),
        ])
    }
}
