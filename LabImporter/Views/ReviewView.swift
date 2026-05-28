import SwiftUI

struct ReviewView: View {
    @State var labValues: [LabValue]
    @State private var reportDate: Date
    @AppStorage("patientName") private var patientName: String = ""
    @AppStorage("authorName") private var authorName: String = ""
    @AppStorage("patientBirthdateInterval") private var birthdateInterval: Double = 0
    @AppStorage("patientSexRaw") private var patientSexRaw: Int = 0
    @State private var extractedPatientName: String?
    @State private var extractedAuthorName: String?
    @State private var hkBirthdate: Date?
    @State private var hkSex: Int?

    @State private var cdaShareURL: URL?
    @State private var cdaError: String?
    @State private var showDiscardAlert = false

    @State private var showAddValue = false
    @State private var addName = ""
    @State private var addCode = ""
    @State private var addDisplayValue = ""
    @State private var addUnit = ""

    @FocusState private var anyFieldFocused: Bool
    @Environment(\.dismiss) private var dismiss

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
        .navigationTitle("Lab Report")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            keyboardDoneButton
            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") { showDiscardAlert = true }
            }
        }
        .alert("Discard Report?", isPresented: $showDiscardAlert) {
            Button("Discard", role: .destructive) { dismiss() }
            Button("Continue Editing", role: .cancel) { }
        } message: {
            Text("Any entered values will not be saved.")
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            bottomButtons
        }
        .task { await loadHKCharacteristics() }
        .sheet(isPresented: Binding(
            get: { cdaShareURL != nil },
            set: { if !$0 { cdaShareURL = nil } }
        )) {
            if let url = cdaShareURL {
                ShareSheet(url: url)
                    .ignoresSafeArea()
            }
        }
        .sheet(isPresented: $showAddValue) {
            addValueSheet
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

            if birthdateInterval != 0 {
                HStack {
                    DatePicker(
                        "Birthday",
                        selection: birthdateBinding,
                        in: ...Date.now,
                        displayedComponents: .date
                    )
                    Button { birthdateInterval = 0 } label: {
                        Image(systemName: "xmark.circle.fill")
                    }
                    .foregroundStyle(Color.secondary)
                    .buttonStyle(.plain)
                }
            }

            if let hkDob = hkBirthdate, hkBirthdateDiffers {
                suggestionRow(label: "Detected in Health: \"\(hkDob.formatted(date: .abbreviated, time: .omitted))\"") {
                    birthdateInterval = hkDob.timeIntervalSinceReferenceDate
                }
            }

            Picker("Gender", selection: $patientSexRaw) {
                Text("–").tag(0)
                Text("Female").tag(1)
                Text("Male").tag(2)
                Text("Other").tag(3)
            }

            if let hkSexRaw = hkSex, hkSexRaw != 0, hkSexRaw != patientSexRaw {
                suggestionRow(label: "Detected in Health: \"\(hkSexName(hkSexRaw))\"") {
                    patientSexRaw = hkSexRaw
                }
            }
        }
    }

    private func suggestionRow(label: LocalizedStringKey, onUse: @escaping () -> Void) -> some View {
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
            ForEach(
                labValues.indices.filter { LabMapping.loincCode(for: labValues[$0].code) != nil },
                id: \.self
            ) { idx in
                LabValueRowView(value: $labValues[idx])
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            labValues.remove(at: idx)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
            }
            Button {
                showAddValue = true
            } label: {
                Label("Add Value", systemImage: "plus.circle")
            }
            .foregroundStyle(Color.accentColor)
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
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            labValues.removeAll { $0.id == value.id }
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            } header: {
                Text("Not supported for export")
            } footer: {
                Text("These values don't have a LOINC code and won't be saved to Apple Health.")
            }
        }
    }

}

// MARK: - Views

private extension ReviewView {

    var addValueSheet: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $addName)
                        .autocorrectionDisabled()
                    NavigationLink {
                        AddCodePickerPage(code: $addCode, name: $addName)
                    } label: {
                        HStack {
                            Text("Lab Test")
                            Spacer()
                            Text(addCode.isEmpty ? "Any" : addCode)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    TextField("Value", text: $addDisplayValue)
                        .keyboardType(.decimalPad)
                    TextField("Unit (optional)", text: $addUnit)
                        .autocorrectionDisabled()
                }
            }
            .navigationTitle("Add Value")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        showAddValue = false
                        resetAddForm()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Add") {
                        commitAddValue()
                    }
                    .fontWeight(.semibold)
                    .disabled(addName.isEmpty || addDisplayValue.isEmpty)
                }
            }
        }
    }

    var bottomButtons: some View {
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
                .ignoresSafeArea(edges: .bottom)
        }
    }

    var keyboardDoneButton: some ToolbarContent {
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
}

// MARK: - Helpers

private extension ReviewView {

    var birthdateBinding: Binding<Date> {
        Binding(
            get: { Date(timeIntervalSinceReferenceDate: birthdateInterval) },
            set: { birthdateInterval = $0.timeIntervalSinceReferenceDate }
        )
    }

    var hkBirthdateDiffers: Bool {
        guard let hkDob = hkBirthdate else { return false }
        guard birthdateInterval != 0 else { return true }
        let stored = Date(timeIntervalSinceReferenceDate: birthdateInterval)
        return !Calendar.current.isDate(stored, inSameDayAs: hkDob)
    }

    func hkSexName(_ raw: Int) -> String {
        switch raw {
        case 1: return "Female"
        case 2: return "Male"
        case 3: return "Other"
        default: return "–"
        }
    }

    func loadHKCharacteristics() async {
        guard let chars = try? await HealthKitService.shared.readPatientCharacteristics() else { return }
        hkBirthdate = chars.dateOfBirth
        hkSex = chars.biologicalSexRaw
    }

    func resetAddForm() {
        addName = ""
        addCode = ""
        addDisplayValue = ""
        addUnit = ""
    }

    func commitAddValue() {
        let code = addCode.isEmpty
            ? "MANUAL"
            : addCode.uppercased().trimmingCharacters(in: .whitespaces)
        let normalized = addDisplayValue.replacingOccurrences(of: ",", with: ".")
        let num = Double(normalized)
        let newValue = LabValue(
            code: code,
            name: addName.trimmingCharacters(in: .whitespaces),
            displayValue: addDisplayValue,
            numericValue: num,
            unit: addUnit.trimmingCharacters(in: .whitespaces)
        )
        labValues.append(newValue)
        showAddValue = false
        resetAddForm()
    }

    func shareCDA() {
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

    func performCDAImport() async {
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
            dismiss()
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
