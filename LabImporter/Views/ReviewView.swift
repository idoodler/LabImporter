import SwiftUI
import HealthKit

struct ReviewView: View {
    @State var labValues: [LabValue]
    @State private var reportDate: Date
    @AppStorage("patientName") private var patientName: String = ""

    @State private var isImporting = false
    @State private var importResult: ImportResult?
    @State private var importError: Error?
    @State private var unsupportedCopied = false
    @State private var cdaShareURL: URL?
    @State private var cdaError: String?

    @FocusState private var anyFieldFocused: Bool

    private let healthKitService = HealthKitService()
    private let cdaService = CDAExportService()

    init(labValues: [LabValue], reportDate: Date = Date()) {
        _labValues = State(initialValue: labValues)
        _reportDate = State(initialValue: reportDate)
    }

    private var importableCount: Int {
        labValues.filter { $0.isSelected && $0.canImportToHealth }.count
    }

    var body: some View {
        List {
            patientSection
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
        .sheet(isPresented: Binding(
            get: { cdaShareURL != nil },
            set: { if !$0 { cdaShareURL = nil } }
        )) {
            if let url = cdaShareURL {
                ShareSheet(url: url)
                    .ignoresSafeArea()
            }
        }
        .alert("Import Error", isPresented: .constant(importError != nil)) {
            Button("OK") { importError = nil }
        } message: {
            Text(importError?.localizedDescription ?? "")
        }
        .alert("Export Error", isPresented: .constant(cdaError != nil)) {
            Button("OK") { cdaError = nil }
        } message: {
            Text(cdaError ?? "")
        }
    }

    // MARK: - Sections

    private var patientSection: some View {
        Section("Patient") {
            TextField("Full Name (optional)", text: $patientName)
                .autocorrectionDisabled()
                .textContentType(.name)
        }
    }

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

                    // swiftlint:disable:next line_length
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
            Menu {
                Button {
                    Task { await performImport() }
                } label: {
                    Label("Import to Apple Health", systemImage: "heart.fill")
                }
                .disabled(importableCount == 0 || isImporting)

                Divider()

                Button {
                    Task { await performCDAImport() }
                } label: {
                    Label("Save to Health Records (CDA)", systemImage: "doc.badge.plus")
                }

                Button {
                    shareCDA()
                } label: {
                    Label("Share XML File", systemImage: "doc.badge.arrow.up")
                }
            } label: {
                Text(importableCount > 0 ? "Import \(importableCount)" : "Import")
                    .fontWeight(.semibold)
            }
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
        let lines = values.map { labValue in
            let val = labValue.displayValue == "-"
                ? "negative"
                : "\(labValue.displayValue) \(labValue.unit)".trimmingCharacters(in: .whitespaces)
            return "\(labValue.name): \(val)"
        }
        UIPasteboard.general.string = lines.joined(separator: "\n")
        unsupportedCopied = true
        Task {
            try? await Task.sleep(for: .seconds(2))
            unsupportedCopied = false
        }
    }

    // MARK: - CDA

    private func shareCDA() {
        do {
            cdaShareURL = try cdaService.exportToTempFile(
                labValues: labValues,
                date: reportDate,
                patientName: patientName
            )
        } catch {
            cdaError = error.localizedDescription
        }
    }

    private func performCDAImport() async {
        let xml = cdaService.generateCDA(labValues: labValues, date: reportDate, patientName: patientName)
        do {
            try await healthKitService.importCDADocument(xml, date: reportDate)
        } catch {
            cdaError = error.localizedDescription
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

// MARK: - Share sheet wrapper

private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
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
