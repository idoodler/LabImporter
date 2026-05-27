import SwiftUI
import HealthKit

struct ReviewView: View {
    @State var labValues: [LabValue]
    @State private var reportDate: Date
    @AppStorage("patientName") private var patientName: String = ""
    @AppStorage("authorName") private var authorName: String = ""

    @State private var cdaShareURL: URL?
    @State private var cdaError: String?
    @State private var cdaImportSuccess = false

    @FocusState private var anyFieldFocused: Bool

    private let healthKitService = HealthKitService()
    private let cdaService = CDAExportService()

    init(labValues: [LabValue], reportDate: Date = Date()) {
        _labValues = State(initialValue: labValues)
        _reportDate = State(initialValue: reportDate)
    }

    var body: some View {
        List {
            patientSection
            dateSection
            valuesSection
            infoSection
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollContentBackground(.hidden)
        .background(.ultraThinMaterial)
        .navigationTitle("Review Values")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            exportButton
            keyboardDoneButton
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
        .alert("Saved to Health Records", isPresented: $cdaImportSuccess) {
            Button("OK") {}
        } message: {
            Text("The lab report has been saved as a CDA document in Apple Health.")
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
                .focused($anyFieldFocused)
            TextField("Lab / Doctor (optional)", text: $authorName)
                .autocorrectionDisabled()
                .textContentType(.organizationName)
                .focused($anyFieldFocused)
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
        let excluded = labValues.filter { LabMapping.loincCode(for: $0.code) == nil }
        if !excluded.isEmpty {
            Section {
                Label(
                    // swiftlint:disable:next line_length
                    "\(excluded.count) value\(excluded.count == 1 ? "" : "s") without a LOINC code — not included in CDA export",
                    systemImage: "info.circle"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Toolbar

    private var exportButton: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button {
                    Task { await performCDAImport() }
                } label: {
                    Label("Save to Health Records", systemImage: "doc.badge.plus")
                }

                Button {
                    shareCDA()
                } label: {
                    Label("Share CDA File", systemImage: "doc.badge.arrow.up")
                }
            } label: {
                Image(systemName: "square.and.arrow.up")
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

    // MARK: - CDA

    private func shareCDA() {
        do {
            cdaShareURL = try cdaService.exportToTempFile(
                labValues: labValues,
                date: reportDate,
                patientName: patientName,
                authorName: authorName
            )
            saveToHistory()
        } catch {
            cdaError = error.localizedDescription
        }
    }

    private func performCDAImport() async {
        // swiftlint:disable:next line_length
        let xml = cdaService.generateCDA(labValues: labValues, date: reportDate, patientName: patientName, authorName: authorName)
        do {
            try await healthKitService.importCDADocument(xml, date: reportDate)
            saveToHistory()
            cdaImportSuccess = true
        } catch {
            cdaError = error.localizedDescription
        }
    }

    // MARK: - History

    private func saveToHistory() {
        let entries = labValues.map { value in
            LabReport.Entry(
                id: UUID(),
                code: value.code,
                name: value.name,
                displayValue: value.displayValue,
                numericValue: value.numericValue,
                unit: value.unit
            )
        }
        let report = LabReport(
            id: UUID(),
            date: reportDate,
            patientName: patientName,
            authorName: authorName,
            entries: entries
        )
        Task {
            try? await ReportHistoryService.shared.save(report)
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
            LabValue(code: "BZ", name: "Blood Glucose", displayValue: "95", numericValue: 95, unit: "mg/dl"),
            LabValue(code: "KREA", name: "Creatinine", displayValue: "0.91", numericValue: 0.91, unit: "mg/dl"),
            LabValue(code: "HB-A1C", name: "HbA1c (%)", displayValue: "6.5", numericValue: 6.5, unit: "%"),
            LabValue(code: "DIABOL", name: "Diabetes Screening", displayValue: "-", numericValue: nil, unit: ""),
            LabValue(code: "CHOL", name: "Total Cholesterol", displayValue: "162", numericValue: 162, unit: "mg/dl"),
        ])
    }
}
