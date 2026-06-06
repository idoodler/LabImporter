import SwiftUI

/// Configuration sheet for a PDF export: pick the colour treatment, which
/// sections to include, the trend time range, and exactly which values appear.
/// On generate it builds the PDF off the loaded reports and hands the file to a
/// share sheet.
struct PDFExportView: View {
    let reports: [LabReport]

    @Environment(\.dismiss) private var dismiss
    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @AppStorage("patientName") private var patientName = ""

    @State private var options: PDFExportOptions
    @State private var patient: HealthKitService.PatientCharacteristics?
    @State private var shareURL: IdentifiedURL?
    @State private var isGenerating = false
    @State private var errorMessage: String?

    /// A selectable metric: a distinct LOINC code that has at least one numeric
    /// reading across the loaded reports.
    private struct Available: Identifiable {
        let code: String
        let name: String
        let category: LabCategory
        var id: String { code }
    }

    init(reports: [LabReport]) {
        self.reports = reports
        let codes = Self.availableCodes(in: reports)
        _options = State(initialValue: PDFExportOptions(selectedCodes: Set(codes)))
    }

    var body: some View {
        NavigationStack {
            Form {
                appearanceSection
                sectionsSection
                if options.includeTrends { timeRangeSection }
                valuesSection
            }
            .navigationTitle("Export PDF")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(role: .close) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if isGenerating {
                        ProgressView()
                    } else {
                        Button {
                            generate()
                        } label: {
                            Image(systemName: "checkmark")
                        }
                        .accessibilityLabel(Text("Generate"))
                        .disabled(options.selectedCodes.isEmpty)
                    }
                }
            }
            .task {
                patient = try? await HealthKitService.shared.readPatientCharacteristics()
            }
            .alert("Export Error", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
            .sheet(item: $shareURL) { item in
                PDFShareSheet(url: item.url)
            }
        }
    }

    // MARK: - Sections

    private var appearanceSection: some View {
        Section("Appearance") {
            Picker("Color Mode", selection: $options.colorMode) {
                ForEach(PDFColorMode.allCases) { mode in
                    Text(mode.label).tag(mode)
                }
            }
            .pickerStyle(.segmented)

            Picker("Page Size", selection: $options.pageFormat) {
                ForEach(PDFPageFormat.allCases) { format in
                    Text(format.label).tag(format)
                }
            }
        }
    }

    private var sectionsSection: some View {
        Section {
            Toggle("Summary Page", isOn: $options.includeSummary)
            Toggle("Latest Results", isOn: $options.includeLatestResults)
            Toggle("Trend Charts", isOn: $options.includeTrends)
        } header: {
            Text("Sections")
        } footer: {
            Text("Trend charts appear for values with at least two readings in the selected range.")
        }
    }

    private var timeRangeSection: some View {
        Section("Trend Time Range") {
            Picker("Time Range", selection: $options.timeRange) {
                ForEach(PDFTimeRange.allCases) { range in
                    Text(range.label).tag(range)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    private var valuesSection: some View {
        Section {
            ForEach(available) { item in
                Button {
                    toggle(item.code)
                } label: {
                    HStack(spacing: 12) {
                        Circle()
                            .fill(item.category.color)
                            .frame(width: 9, height: 9)
                        Text(item.name)
                            .foregroundStyle(.primary)
                        Spacer()
                        if options.selectedCodes.contains(item.code) {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.accentColor)
                                .fontWeight(.semibold)
                        }
                    }
                }
            }
        } header: {
            HStack {
                Text("Included Values")
                Spacer()
                Button(allSelected ? "Deselect All" : "Select All") {
                    options.selectedCodes = allSelected ? [] : Set(available.map(\.code))
                }
                .font(.caption.weight(.medium))
                .textCase(nil)
            }
        } footer: {
            Text("\(options.selectedCodes.count) of \(available.count) selected")
        }
    }

    // MARK: - Derived data

    private var available: [Available] {
        var seen = Set<String>()
        var result: [Available] = []
        for report in reports {
            for entry in report.entries where entry.numericValue != nil && seen.insert(entry.code).inserted {
                result.append(Available(code: entry.code, name: entry.resolvedName,
                                        category: LabCategory.forCode(entry.code)))
            }
        }
        // Mirror the dashboard order: pinned first, then the user's explicit
        // order, then alphabetical — so the export list matches what they see.
        let pinned = prefs.pinnedSet
        var orderMap: [String: Int] = [:]
        for (index, code) in prefs.orderedCodes.enumerated() where orderMap[code] == nil {
            orderMap[code] = index
        }
        return result.sorted { lhs, rhs in
            let lPin = pinned.contains(lhs.code)
            let rPin = pinned.contains(rhs.code)
            if lPin != rPin { return lPin }
            let lOrd = orderMap[lhs.code] ?? Int.max
            let rOrd = orderMap[rhs.code] ?? Int.max
            if lOrd != rOrd { return lOrd < rOrd }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    private var allSelected: Bool {
        !available.isEmpty && options.selectedCodes.count == available.count
    }

    /// Distinct LOINC codes with numeric data — the default selection.
    private static func availableCodes(in reports: [LabReport]) -> [String] {
        var seen = Set<String>()
        var codes: [String] = []
        for report in reports {
            for entry in report.entries where entry.numericValue != nil && seen.insert(entry.code).inserted {
                codes.append(entry.code)
            }
        }
        return codes
    }

    // MARK: - Actions

    private func toggle(_ code: String) {
        if options.selectedCodes.contains(code) {
            options.selectedCodes.remove(code)
        } else {
            options.selectedCodes.insert(code)
        }
    }

    private func generate() {
        isGenerating = true
        let reports = reports
        let options = options
        let patient = patient
        let fallbackName = patientName
        Task {
            let data = PDFExportService.buildData(reports: reports, options: options,
                                                  patient: patient, fallbackPatientName: fallbackName)
            do {
                let url = try await MainActor.run { try PDFExportService().export(data: data, options: options) }
                shareURL = IdentifiedURL(url: url)
            } catch {
                errorMessage = error.localizedDescription
            }
            isGenerating = false
        }
    }
}

// MARK: - Share sheet

/// `UIActivityViewController` wrapper for sharing the generated PDF. Mirrors the
/// popover anchoring used elsewhere so it stays valid on iPad.
struct PDFShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        if let popover = controller.popoverPresentationController {
            popover.sourceView = controller.view
            popover.permittedArrowDirections = []
        }
        return controller
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {
        if let popover = controller.popoverPresentationController, let source = popover.sourceView {
            popover.sourceRect = CGRect(x: source.bounds.midX, y: source.bounds.midY, width: 0, height: 0)
        }
    }
}
