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
    // category in canonical order so the list reads like the saved report.
    // Indices (not copies) keep each row a live binding for editing.
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
        replacingReport: LabReport? = nil
    ) {
        _labValues = State(initialValue: labValues)
        _reportDate = State(initialValue: reportDate)
        _extractedPatientName = State(initialValue: extractedPatientName)
        _extractedAuthorName = State(initialValue: extractedAuthorName)
        _replacingReport = State(initialValue: replacingReport)
    }

    var body: some View {
        List {
            headerSection
            patientSection
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
                Button(role: .close) { showDiscardAlert = true }
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
        .onAppear { configureImportEngine() }
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
        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
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

    var clipboardHasContent: Bool {
        let pasteboard = UIPasteboard.general
        return pasteboard.hasImages || pasteboard.hasStrings
    }

    /// Appends freshly parsed values to the open report rather than replacing it,
    /// so scan/file/paste add to what the user is already reviewing or editing.
    func configureImportEngine() {
        importEngine.onParsed = { result in
            labValues.append(contentsOf: result.values)
        }
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
