import SwiftUI
import VisionKit

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
    @State private var replacingReport: LabReport?

    @State private var showAddValue = false
    @State private var importEngine = LabImportEngine()
    @State private var didSeedMetadata = false

    // Snapshot the sheet opened with, to warn only about discarding real edits.
    // @State (not let) so it's captured once and preserved across re-inits, paired
    // with `labValues` — else a re-render re-derives it (fresh `asLabValues` UUIDs).
    @State private var initialLabValues: [LabValue]
    @State private var initialReportDate: Date

    /// Called after a successful save (before dismiss) so a presenter can react.
    private let onSaved: (() -> Void)?

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

    // Values carrying a LOINC mapping — eligible for the categorized list & export.
    private var supportedCount: Int {
        labValues.filter { LabMapping.loincCode(for: $0.code) != nil }.count
    }

    private struct ValueGroup {
        let category: LabCategory
        let indices: [Int]
    }

    // Indices into `labValues` for LOINC-mapped values, grouped by clinical
    // category in canonical order. Indices (not copies) keep rows live bindings.
    private var valueGroups: [ValueGroup] {
        let supported = labValues.indices.filter { LabMapping.loincCode(for: labValues[$0].code) != nil }
        let grouped = Dictionary(grouping: supported) { LabCategory.forCode(labValues[$0].code) }
        return LabCategory.allCases.compactMap { category in
            guard let idxs = grouped[category], !idxs.isEmpty else { return nil }
            return ValueGroup(category: category, indices: idxs)
        }
    }

    // Up to three category colors for the soft background wash.
    private var backgroundColors: [Color] {
        valueGroups
            .sorted { $0.indices.count > $1.indices.count }
            .prefix(3)
            .map { $0.category.color }
    }

    private var dominantColor: Color {
        valueGroups.max(by: { $0.indices.count < $1.indices.count })?.category.color ?? .accentColor
    }

    init(
        labValues: [LabValue],
        reportDate: Date = Date(),
        extractedPatientName: String? = nil,
        extractedAuthorName: String? = nil,
        replacingReport: LabReport? = nil,
        onSaved: (() -> Void)? = nil
    ) {
        _labValues = State(initialValue: labValues)
        _reportDate = State(initialValue: reportDate)
        _extractedPatientName = State(initialValue: extractedPatientName)
        _extractedAuthorName = State(initialValue: extractedAuthorName)
        _replacingReport = State(initialValue: replacingReport)
        _initialLabValues = State(initialValue: labValues)
        _initialReportDate = State(initialValue: reportDate)
        self.onSaved = onSaved
    }

    var body: some View {
        List {
            headerSection
            patientSection
            authorSection
            dateSection
            valuesSection
            addValueSection
            unsupportedSection
        }
        .listStyle(.insetGrouped)
        .scrollDismissesKeyboard(.interactively)
        .scrollContentBackground(.hidden)
        .background { CategoryBackground(colors: backgroundColors) }
        .navigationTitle("Lab Report")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            keyboardDoneButton
            ToolbarItem(placement: .topBarLeading) {
                Button(role: .close) {
                    if hasEdits { showDiscardAlert = true } else { dismiss() }
                }
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
        .labImport(engine: importEngine)
        .task { await loadHKCharacteristics() }
        .onAppear {
            configureImportEngine()
            seedMetadataFromReport()
        }
        .sheet(isPresented: Binding(
            get: { cdaShareURL != nil },
            set: { if !$0 { cdaShareURL = nil } }
        )) {
            if let url = cdaShareURL {
                CDAShareSheet(url: url)
                    .ignoresSafeArea()
            }
        }
        .sheet(isPresented: $showAddValue) {
            AddValueSheet { labValues.append($0) }
        }
        .alert("Export Error", isPresented: .constant(cdaError != nil)) {
            Button("OK") { cdaError = nil }
        } message: {
            Text(cdaError ?? "")
        }
    }

    // MARK: - Sections

    private var categoryCounts: [CategoryCount] {
        valueGroups.map { CategoryCount(category: $0.category, count: $0.indices.count) }
    }

    private var headerSection: some View {
        Section {
            ReviewHeaderCard(
                supportedCount: supportedCount,
                exportableCount: exportableCount,
                groups: categoryCounts,
                dominantColor: dominantColor
            )
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
    }

    private var patientSection: some View {
        Section("Patient") {
            TextField("Full Name (optional)", text: $patientName)
                .autocorrectionDisabled()
                .textContentType(.name)
                .focused($anyFieldFocused)

            if let extracted = extractedPatientName,
               !extracted.isEmpty,
               extracted != patientName {
                SuggestionRow(label: "Detected in report: \"\(extracted)\"") {
                    patientName = extracted
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
                SuggestionRow(label: "Detected in Health: \"\(hkDob.formatted(date: .abbreviated, time: .omitted))\"") {
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
                SuggestionRow(label: "Detected in Health: \"\(hkSexName(hkSexRaw))\"") {
                    patientSexRaw = hkSexRaw
                }
            }
        }
        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
    }

    private var authorSection: some View {
        Section("Lab / Doctor") {
            TextField("Lab / Doctor (optional)", text: $authorName)
                .autocorrectionDisabled()
                .textContentType(.organizationName)
                .focused($anyFieldFocused)

            if let extracted = extractedAuthorName,
               !extracted.isEmpty,
               extracted != authorName {
                SuggestionRow(label: "Detected in report: \"\(extracted)\"") {
                    authorName = extracted
                }
            }
        }
        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
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
        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
    }

    @ViewBuilder
    private var valuesSection: some View {
        ForEach(valueGroups, id: \.category) { group in
            Section {
                ForEach(group.indices, id: \.self) { idx in
                    LabValueRowView(value: $labValues[idx])
                        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                labValues.remove(at: idx)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                }
            } header: {
                valuesGroupHeader(group)
            }
        }
    }

    /// The first category group carries the "tap to correct" hint above its
    /// header so the instruction appears once, just before the values begin.
    @ViewBuilder
    private func valuesGroupHeader(_ group: ValueGroup) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if group.category == valueGroups.first?.category {
                Text("Lab Values — tap values to correct them")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .textCase(nil)
            }
            CategorySectionHeader(category: group.category, count: group.indices.count)
        }
    }

    private var addValueSection: some View {
        Section {
            addValueMenu
                .listRowBackground(Rectangle().fill(.ultraThinMaterial))
        }
    }

    @ViewBuilder
    private var unsupportedSection: some View {
        if !unsupportedValues.isEmpty {
            UnsupportedValuesSection(values: unsupportedValues) { value in
                labValues.removeAll { $0.id == value.id }
            }
        }
    }

}

// MARK: - Views

private extension ReviewView {

    /// Lets the user grow the open report with the same "known methods" used to
    /// create one — scan, file, paste — plus manual entry. Imported values are
    /// appended to the current set (see `configureImportEngine`).
    var addValueMenu: some View {
        Menu {
            Button {
                importEngine.scan()
            } label: {
                Label("Scan Document", systemImage: "doc.viewfinder")
            }
            .disabled(!VNDocumentCameraViewController.isSupported)

            Button {
                importEngine.pickFile()
            } label: {
                Label("Choose File", systemImage: "folder")
            }

            Button {
                importEngine.paste()
            } label: {
                Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
            }
            .disabled(!clipboardHasContent)

            Divider()

            Button {
                showAddValue = true
            } label: {
                Label("Enter Manually", systemImage: "square.and.pencil")
            }
        } label: {
            Label("Add Value", systemImage: "plus.circle")
        }
        .foregroundStyle(Color.accentColor)
    }

    var bottomButtons: some View {
        ReviewActionBar(
            isEnabled: exportableCount > 0,
            onSave: { Task { await performCDAImport() } },
            onShare: { shareCDA() }
        )
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

    var clipboardHasContent: Bool {
        let pasteboard = UIPasteboard.general
        return pasteboard.hasImages || pasteboard.hasStrings
    }

    /// Whether the report date or any lab value differs from what the sheet
    /// opened with — drives the discard confirmation. Patient/author/biometrics
    /// live in `@AppStorage` and persist, so they aren't discardable edits.
    var hasEdits: Bool {
        guard reportDate == initialReportDate,
              labValues.count == initialLabValues.count else { return true }
        return zip(labValues, initialLabValues).contains { !$0.matchesSavedData(of: $1) }
    }

    /// Appends freshly parsed values to the open report rather than replacing it,
    /// so scan/file/paste add to what the user is already reviewing or editing.
    func configureImportEngine() {
        importEngine.onParsed = { result in
            labValues.append(contentsOf: result.values)
        }
    }

    /// When editing a saved report, populate the patient/author fields from that
    /// report (they otherwise default to the global `@AppStorage` values, which
    /// would both hide the report's author and silently overwrite it on save).
    /// Runs once; new reports keep the stored defaults.
    func seedMetadataFromReport() {
        guard !didSeedMetadata else { return }
        didSeedMetadata = true
        guard let report = replacingReport else { return }
        if !report.patientName.isEmpty { patientName = report.patientName }
        if !report.authorName.isEmpty { authorName = report.authorName }
    }

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
        case 1: return String(localized: "Female")
        case 2: return String(localized: "Male")
        case 3: return String(localized: "Other")
        default: return "–"
        }
    }

    func loadHKCharacteristics() async {
        guard let chars = try? await HealthKitService.shared.readPatientCharacteristics() else { return }
        hkBirthdate = chars.dateOfBirth
        hkSex = chars.biologicalSexRaw
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
            if let old = replacingReport {
                try? await HealthKitService.shared.deleteCDADocument(id: old.id)
            }
            onSaved?()
            dismiss()
        } catch {
            cdaError = error.localizedDescription
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        ReviewView(
            labValues: [
                LabValue(code: "BZ", name: "Blood Glucose", displayValue: "95", numericValue: 95, unit: "mg/dl"),
                LabValue(code: "KREA", name: "Creatinine", displayValue: "0.91", numericValue: 0.91, unit: "mg/dl"),
                LabValue(code: "HB-A1C", name: "HbA1c (%)", displayValue: "6.5", numericValue: 6.5, unit: "%"),
                LabValue(code: "CHOL", name: "Total Cholesterol", displayValue: "162", numericValue: 162, unit: "mg/dl"),
            ],
            extractedPatientName: "Max Mustermann"
        )
    }
}
