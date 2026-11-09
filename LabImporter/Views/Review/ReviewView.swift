import SwiftUI
import VisionKit

struct ReviewView: View {
    @State var labValues: [LabValue]
    @State private var reportDate: Date
    /// The import found no date the report prints, so `reportDate` is only a
    /// starting point; the date row says so until the user changes it.
    @State private var isDateMissing: Bool
    /// The report prints the date, but it may be misread (day and month either way, a
    /// birth date); the date row says so until the user changes it.
    @State private var isDateToCheck: Bool
    /// This report's names. Only saving a new report remembers them for the next one.
    @State var names: ReviewNames
    @AppStorage("patientBirthdateInterval") var birthdateInterval: Double = 0
    @AppStorage("patientSexRaw") var patientSexRaw: Int = 0
    @State var extractedPatientName: String?
    @State var extractedAuthorName: String?
    @State var hkBirthdate: Date?
    @State var hkSex: Int?
    /// Patient and lab come after the values, folded away: comparing values with the report is the job here.
    @State var showsDetails = false

    @State private var cdaShareURL: URL?
    @State private var cdaError: String?
    @State private var replaceWarning: String?
    @State private var showDiscardAlert = false
    @State private var replacingReport: LabReport?
    /// Asked before saving when a value is flagged or would be left out: flag, don't block.
    @State private var saveConfirmation: SaveConfirmation?
    /// The row Go Back scrolls to (see ReviewView+FoldLayout).
    @State var scrollTarget: UUID?

    @State private var showAddValue = false
    @State private var importEngine = LabImportEngine()
    @AppStorage(SiriExposurePreferences.storageKey) private var siriPrefs = SiriExposurePreferences()
    @State private var siriPromptCodes: [CodeName] = []
    @State var paneGeometry = ReviewPaneGeometry()

    // Snapshot the sheet opened with, to warn only about discarding real edits.
    // @State (not let): captured once and preserved across re-inits, unlike a re-derived `asLabValues`.
    @State private var initialLabValues: [LabValue]
    @State private var initialReportDate: Date
    @State private var initialNames: ReviewNames

    /// Called after a successful save (before dismiss) so a presenter can react.
    private let onSaved: (() -> Void)?

    @FocusState var anyFieldFocused: Bool
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private let cdaService = CDAExportService()

    private var exportableCount: Int {
        labValues.filter(\.isExportable).count
    }

    // `labValues` indices without a LOINC mapping, so their rows stay live bindings like the categorized ones.
    private var unsupportedIndices: [Int] {
        labValues.indices.filter { LabMapping.loincCode(for: labValues[$0].code) == nil }
    }

    // Values carrying a LOINC mapping — eligible for the categorized list & export.
    private var supportedCount: Int {
        labValues.filter { LabMapping.loincCode(for: $0.code) != nil }.count
    }

    // LOINC codes claimed by 2+ exportable rows — duplicates the user must resolve.
    private var duplicateLoincCodes: Set<String> { labValues.duplicateLoincCodes() }

    private struct ValueGroup {
        let category: LabCategory
        let indices: [Int]
    }

    // LOINC-mapped `labValues` indices (not copies, so rows stay live bindings), grouped by category.
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
        valueGroups.sorted { $0.indices.count > $1.indices.count }
            .prefix(3).map { $0.category.color }
    }

    private var dominantColor: Color {
        valueGroups.max(by: { $0.indices.count < $1.indices.count })?.category.color ?? .accentColor
    }

    init(
        labValues: [LabValue],
        reportDate: Date = Date(),
        isDateMissing: Bool = false,
        isDateToCheck: Bool = false,
        extractedPatientName: String? = nil,
        extractedAuthorName: String? = nil,
        replacingReport: LabReport? = nil,
        onSaved: (() -> Void)? = nil
    ) {
        _labValues = State(initialValue: labValues)
        _reportDate = State(initialValue: reportDate)
        _isDateMissing = State(initialValue: isDateMissing)
        _isDateToCheck = State(initialValue: isDateToCheck)
        let names = ReviewNames(replacing: replacingReport)
        _names = State(initialValue: names)
        _initialNames = State(initialValue: names)
        _extractedPatientName = State(initialValue: extractedPatientName)
        _extractedAuthorName = State(initialValue: extractedAuthorName)
        _replacingReport = State(initialValue: replacingReport)
        _initialLabValues = State(initialValue: labValues)
        _initialReportDate = State(initialValue: reportDate)
        self.onSaved = onSaved
    }

    var body: some View {
        reviewLists // One list, or two panes either side of an iPhone Duo's fold — see ReviewView+FoldLayout.
        .listStyle(.insetGrouped)
        .scrollDismissesKeyboard(.interactively)
        .listSectionSpacing(.compact)
        .scrollContentBackground(.hidden)
        .background { CategoryBackground(colors: backgroundColors) }
        .interactiveDismissDisabled(hasEdits) // Avoids a swipe bypassing the discard confirmation below.
        .navigationTitle("Lab Report")
        .navigationBarTitleDisplayMode(.inline) // A large title would push the first value off a small iPhone.
        .toolbar {
            keyboardDoneButton
            ToolbarItem(placement: .topBarLeading) {
                Button(role: .close) { attemptClose() }
                    .disabled(importEngine.isProcessing) // HUD overlay can't cover this bar.
            }
        }
        .alert("Discard Report?", isPresented: $showDiscardAlert) {
            Button("Discard", role: .destructive) { dismiss() }
            Button("Continue Editing", role: .cancel) { }
        } message: {
            Text("Any entered values will not be saved.")
        }
        .labImport(engine: importEngine)
        .task { await loadHKCharacteristics() }
        .onAppear { configureImportEngine() }
        .reportsToSiriOnScreenContext(labValues)
        .registersAsSearchEditor(onClose: attemptClose)
        .sheet(isPresented: Binding(
            get: { cdaShareURL != nil },
            set: { if !$0 { cdaShareURL = nil } }
        ), onDismiss: { ExportFiles.removeAll() }, content: {
            if let url = cdaShareURL {
                CDAShareSheet(url: url)
                    .ignoresSafeArea()
            }
        })
        .sheet(isPresented: $showAddValue) {
            AddValueSheet { labValues.append($0) }
        }
        .promptsForNewSiriValues(codes: $siriPromptCodes, prefs: $siriPrefs, onDismiss: finishSave)
        .alert("Export Error", isPresented: .constant(cdaError != nil)) {
            Button("OK") { cdaError = nil }
        } message: { Text(cdaError ?? "") }
        .alert(
            saveConfirmation?.title ?? "",
            isPresented: Binding(get: { saveConfirmation != nil }, set: { if !$0 { saveConfirmation = nil } }),
            presenting: saveConfirmation
        ) { confirmation in
            Button(confirmation.proceedTitle) { perform(confirmation.action) }
            Button("Go Back", role: .cancel) { scrollTarget = confirmation.firstID }
        } message: { confirmation in
            Text(confirmation.message)
        }
        .alert("Report Saved", isPresented: .constant(replaceWarning != nil)) {
            Button("OK") { finishSave() }
        } message: { Text(replaceWarning ?? "") }
    }

    /// Pinned, the Save/Share bar would fill the screen at accessibility text sizes and
    /// hide the values it's there to save; it follows them as the list's last row instead.
    var pinsActionBar: Bool { !dynamicTypeSize.isAccessibilitySize }

    /// The Save/Share bar, for the layout in ReviewView+FoldLayout.
    @ViewBuilder
    var actionBar: some View {
        if pinsActionBar { bottomButtons().hiddenWhileKeyboardVisible() }
    }

    @ViewBuilder
    var actionBarRow: some View {
        if !pinsActionBar {
            Section {
                bottomButtons(isPinned: false)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 8, trailing: 0))
            }
        }
    }

    // MARK: - Sections

    private var categoryCounts: [CategoryCount] {
        valueGroups.map { CategoryCount(category: $0.category, count: $0.indices.count) }
    }

    var headerSection: some View {
        Section {
            ReviewHeaderCard(
                supportedCount: supportedCount,
                exportableCount: exportableCount,
                duplicateCount: duplicateLoincCodes.count,
                valuesToCorrect: labValues.valuesToCorrectCount(),
                valuesToCheck: labValues.valuesToCheckCount(),
                groups: categoryCounts,
                dominantColor: dominantColor
            )
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        }
    }

    var dateSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                DatePicker(
                    "Report Date",
                    selection: Binding(
                        get: { reportDate },
                        set: {
                            reportDate = $0
                            isDateMissing = false
                            isDateToCheck = false
                        }
                    ),
                    in: ...Date.now,
                    displayedComponents: .date
                )
                if isDateMissing {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Image(systemName: "exclamationmark.magnifyingglass")
                            .accessibilityHidden(true)
                        Text("No date found in the report. Check that this date is right.")
                    }
                    .font(.footnote)
                    .foregroundStyle(.statusWarning)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("review-date-missing")
                } else if isDateToCheck {
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Image(systemName: "exclamationmark.magnifyingglass")
                            .accessibilityHidden(true)
                        Text("This date may be misread. Check that it matches the report.")
                    }
                    .font(.footnote)
                    .foregroundStyle(.statusWarning)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("review-date-to-check")
                }
            }
        }
        .listRowBackground(Rectangle().fill(.ultraThinMaterial))
    }

    @ViewBuilder
    var valuesSection: some View {
        ForEach(valueGroups, id: \.category) { group in
            Section {
                ForEach(group.indices, id: \.self) { idx in
                    LabValueRowView(value: $labValues[idx], isDuplicate: labValues[idx].isDuplicate(in: duplicateLoincCodes))
                        .id(labValues[idx].id)
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
                    .foregroundStyle(.textSecondary)
                    .textCase(nil)
            }
            CategorySectionHeader(category: group.category, count: group.indices.count)
        }
    }

    var addValueSection: some View {
        Section {
            addValueMenu
                .listRowBackground(Rectangle().fill(.ultraThinMaterial))
        }
    }

    @ViewBuilder
    var unsupportedSection: some View {
        let indices = unsupportedIndices
        if !indices.isEmpty {
            UnsupportedValuesSection(labValues: $labValues, indices: indices)
        }
    }
}

// MARK: - Views

private extension ReviewView {
    /// Lets the user grow the open report with the same "known methods" used to
    /// create one — scan, file, paste, manual entry — appending to the current
    /// set (see `configureImportEngine`).
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

    func bottomButtons(isPinned: Bool = true) -> some View {
        ReviewActionBar(
            isEnabled: exportableCount > 0 && duplicateLoincCodes.isEmpty && labValues.valuesToCorrectCount() == 0,
            hasDuplicates: !duplicateLoincCodes.isEmpty,
            valuesToCorrect: labValues.valuesToCorrectCount(),
            onSave: { request(.save) },
            onShare: { request(.share) },
            isPinned: isPinned
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

    func attemptClose() { if hasEdits { showDiscardAlert = true } else { dismiss() } }

    /// Whether the report date, its names or any lab value differs from what the sheet opened with — drives the
    /// discard confirmation. Birthday and gender live in `@AppStorage` and persist, so they aren't discardable edits.
    var hasEdits: Bool {
        guard reportDate == initialReportDate,
              names == initialNames,
              labValues.count == initialLabValues.count else { return true }
        return zip(labValues, initialLabValues).contains { !$0.matchesSavedData(of: $1) }
    }

    /// Appends parsed values (scan/file/paste); re-imported tests surface as flagged duplicates.
    func configureImportEngine() {
        importEngine.onParsed = { result in
            labValues.append(contentsOf: result.values)
        }
    }

    func loadHKCharacteristics() async {
        guard !ScreenshotMode.isActive else { return }
        // Only a suggestion beside the fields: when Health doesn't answer, Review offers none and the user types them.
        guard let chars = try? await HealthKitService.shared.readPatientCharacteristics() else { return }
        hkBirthdate = chars.dateOfBirth
        hkSex = chars.biologicalSexRaw
    }

    func shareCDA() {
        do {
            cdaShareURL = try cdaService.exportToTempFile(
                labValues: labValues,
                date: reportDate,
                patientName: names.patient,
                authorName: names.author
            )
        } catch {
            cdaError = error.localizedDescription
        }
    }

    /// Saves or shares the report file, or first asks when a value is flagged "Compare with report" or would be left out.
    func request(_ action: SaveConfirmation.Action) {
        if let confirmation = labValues.saveConfirmation(for: action) {
            saveConfirmation = confirmation
        } else {
            perform(action)
        }
    }

    func perform(_ action: SaveConfirmation.Action) {
        switch action {
        case .save: Task { await performCDAImport() }
        case .share: shareCDA()
        }
    }

    /// `ReportWriter` saves first and removes a replaced version only then, so a failure never loses a report.
    func performCDAImport() async {
        let outcome: ReportWriter.SaveOutcome
        do {
            outcome = try await ReportWriter(store: ScreenshotMode.reportStore ?? HealthKitService.shared).save(
                labValues, date: reportDate, patientName: names.patient, authorName: names.author, replacing: replacingReport
            )
        } catch {
            cdaError = error.localizedDescription
            return
        }
        names.rememberForNextReport(replacing: replacingReport)
        if outcome == .savedOldVersionLeft {
            replaceWarning = String(localized:
                "The updated report was saved, but the previous version couldn't be removed from Apple Health. You can delete it from the Reports list.")
            return
        }
        finishSave()
    }
    func finishSave() {
        replaceWarning = nil
        siriPromptCodes = labValues.undecidedSiriCodes(prefs: siriPrefs)
        guard siriPromptCodes.isEmpty else { return }
        onSaved?()
        dismiss()
    }
}
