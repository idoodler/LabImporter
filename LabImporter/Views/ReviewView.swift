import SwiftUI

struct ReviewView: View {
    @State var labValues: [LabValue]
    @State private var reportDate: Date
    @AppStorage("patientName") private var patientName: String = ""
    @AppStorage("authorName") private var authorName: String = ""
    @State private var extractedPatientName: String?
    @State private var extractedAuthorName: String?

    @State private var cdaShareURL: URL?
    @State private var cdaError: String?
    @State private var cdaImportSuccess = false

    @FocusState private var anyFieldFocused: Bool

    private let cdaService = CDAExportService()

    private var exportableCount: Int {
        labValues.filter {
            $0.isSelected && $0.numericValue != nil && LabMapping.loincCode(for: $0.code) != nil
        }.count
    }

    private var unsupportedValues: [LabValue] {
        labValues.filter { LabMapping.loincCode(for: $0.code) == nil }
    }

    init(
        labValues: [LabValue],
        reportDate: Date = Date(),
        extractedPatientName: String? = nil,
        extractedAuthorName: String? = nil
    ) {
        _labValues = State(initialValue: labValues)
        _reportDate = State(initialValue: reportDate)
        _extractedPatientName = State(initialValue: extractedPatientName)
        _extractedAuthorName = State(initialValue: extractedAuthorName)
    }

    var body: some View {
        List {
            patientSection
            dateSection
            valuesSection
            unsupportedSection
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollContentBackground(.hidden)
        .background(.ultraThinMaterial)
        .navigationTitle("Review Values")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            keyboardDoneButton
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            bottomButtons
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

            if let extracted = extractedPatientName,
               !extracted.isEmpty,
               extracted != patientName {
                suggestionRow(label: "Detected in report: \"\(extracted)\"") {
                    patientName = extracted
                }
            }

            TextField("Lab / Doctor (optional)", text: $authorName)
                .autocorrectionDisabled()
                .textContentType(.organizationName)
                .focused($anyFieldFocused)

            if let extracted = extractedAuthorName,
               !extracted.isEmpty,
               extracted != authorName {
                suggestionRow(label: "Detected in report: \"\(extracted)\"") {
                    authorName = extracted
                }
            }
        }
    }

    private func suggestionRow(label: String, onUse: @escaping () -> Void) -> some View {
        HStack {
            Text(label)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            Spacer()
            Button("Use", action: onUse)
                .buttonStyle(.plain)
                .foregroundStyle(Color.accentColor)
                .fontWeight(.semibold)
                .font(.footnote)
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
                if LabMapping.loincCode(for: value.code) != nil {
                    LabValueRowView(value: $value, anyFieldFocused: $anyFieldFocused)
                }
            }
        } header: {
            Text("Lab Values — tap values to correct them")
        }
    }

    @ViewBuilder
    private var unsupportedSection: some View {
        if !unsupportedValues.isEmpty {
            Section {
                ForEach(unsupportedValues) { value in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(value.name).font(.body)
                            Text(value.code)
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                                .textCase(.uppercase)
                        }
                        Spacer()
                        HStack(spacing: 6) {
                            Text(value.displayValue).font(.body)
                            if !value.unit.isEmpty {
                                Text(value.unit)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }
            } header: {
                Text("Not supported for export")
            } footer: {
                Text("These values don't have a LOINC code and won't be saved to Apple Health.")
            }
        }
    }

    // MARK: - Bottom buttons

    private var bottomButtons: some View {
        VStack(spacing: 10) {
            Button("Save to Health Records") {
                Task { await performCDAImport() }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)

            Button("Share CDA File") { shareCDA() }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
        }
        .opacity(exportableCount == 0 ? 0.4 : 1)
        .allowsHitTesting(exportableCount > 0)
        .padding(.horizontal)
        .padding(.bottom)
        .padding(.top, 16)
        .background {
            Rectangle()
                .fill(.regularMaterial)
                .mask {
                    LinearGradient(
                        colors: [.clear, .black],
                        startPoint: .top,
                        endPoint: UnitPoint(x: 0.5, y: 0.3)
                    )
                }
        }
    }

    // MARK: - Toolbar

    private var keyboardDoneButton: some ToolbarContent {
        ToolbarItem(placement: .keyboard) {
            HStack {
                Spacer()
                Button("Done") {
                    UIApplication.shared.sendAction(
                        #selector(UIResponder.resignFirstResponder),
                        to: nil, from: nil, for: nil
                    )
                }
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
        } catch {
            cdaError = error.localizedDescription
        }
    }

    private func performCDAImport() async {
        guard exportableCount > 0 else {
            cdaError = CDAExportError.noExportableValues.errorDescription
            return
        }
        let xml = cdaService.generateCDA(
            labValues: labValues,
            date: reportDate,
            patientName: patientName,
            authorName: authorName
        )
        do {
            try await HealthKitService.shared.importCDADocument(xml, date: reportDate)
            cdaImportSuccess = true
        } catch {
            cdaError = error.localizedDescription
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
        ReviewView(
            labValues: [
                LabValue(code: "BZ", name: "Blood Glucose", displayValue: "95", numericValue: 95, unit: "mg/dl"),
                LabValue(code: "KREA", name: "Creatinine", displayValue: "0.91", numericValue: 0.91, unit: "mg/dl"),
                LabValue(code: "HB-A1C", name: "HbA1c (%)", displayValue: "6.5", numericValue: 6.5, unit: "%"),
                LabValue(code: "DIABOL", name: "Diabetes Screening", displayValue: "-", numericValue: nil, unit: ""),
                LabValue(code: "CHOL", name: "Total Cholesterol", displayValue: "162", numericValue: 162, unit: "mg/dl"),
            ],
            extractedPatientName: "Max Mustermann",
            extractedAuthorName: "Labor München"
        )
    }
}
