import SwiftUI
import UIKit

struct CodeName: Identifiable, Hashable {
    var id: String { code }
    let code: String
    let name: String
}

struct SettingsView: View {
    let visibleCodes: [CodeName]

    @AppStorage(LabMapping.overridesUserDefaultsKey) private var overrides = ReferenceRangeOverrides()
    @State private var showResetAllConfirm = false
    @State private var searchText = ""
    @Environment(\.dismiss) private var dismiss

    init(visibleCodes: [CodeName] = []) {
        self.visibleCodes = visibleCodes
    }

    var body: some View {
        NavigationStack {
            List {
                orderSection
                referenceRangesContent
                resetSection
                aboutSection
            }
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: Text("Search LOINC code or name"))
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
            .confirmationDialog(
                "Reset all reference ranges to defaults?",
                isPresented: $showResetAllConfirm,
                titleVisibility: .visible
            ) {
                Button("Reset All", role: .destructive) {
                    overrides = ReferenceRangeOverrides()
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Your custom boundaries for every lab code will be cleared.")
            }
        }
    }

    // MARK: - Order & Visibility

    @ViewBuilder
    private var orderSection: some View {
        if !visibleCodes.isEmpty {
            Section {
                NavigationLink(destination: LabOrderEditorView(allCodes: visibleCodes)) {
                    Label("Order & Visibility", systemImage: "slider.horizontal.3")
                }
            } footer: {
                Text("Pin metrics to the top, reorder them, or hide ones you don't track.")
            }
        }
    }

    // MARK: - About

    @ViewBuilder
    private var aboutSection: some View {
        Section("About") {
            HStack {
                Text("Version")
                Spacer()
                Text(versionString)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Text("LOINC Data")
                Spacer()
                Text(loincDataString)
                    .foregroundStyle(.secondary)
            }
            NavigationLink(destination: LicenseView()) {
                Text("License")
            }
        }
    }

    private var versionString: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "—"
        let build = info?["CFBundleVersion"] as? String ?? "—"
        return "\(version) (\(build))"
    }

    private var loincDataString: String {
        let directory = LoincDirectory.shared
        guard directory.isAvailable, let version = directory.version else {
            return String(localized: "Not loaded")
        }
        return "v\(version) · \(directory.codeCount.formatted()) codes"
    }

    // MARK: - Reference Ranges

    @ViewBuilder
    private var referenceRangesContent: some View {
        if searchText.isEmpty {
            browseSections
        } else {
            searchResultsSection
        }
    }

    @ViewBuilder
    private var browseSections: some View {
        let yourCodes = visibleCodes.map { CodeName(code: $0.code.uppercased(), name: $0.name) }
        let yourCodesSet = Set(yourCodes.map(\.code))
        let common = LabMapping.allKnownCodes
            .map { CodeName(code: $0.code.uppercased(), name: $0.name) }
            .filter { !yourCodesSet.contains($0.code) }

        if !yourCodes.isEmpty {
            Section {
                ForEach(yourCodes) { item in rangeRow(code: item.code, displayName: item.name) }
            } header: {
                Text("Your Codes")
            } footer: {
                Text("Customise the Normal and Borderline boundaries used to flag values on the dashboard.")
            }
        }
        if !common.isEmpty {
            Section(yourCodes.isEmpty ? "Reference Ranges" : "Other Common Codes") {
                ForEach(common) { item in rangeRow(code: item.code, displayName: item.name) }
            }
        }
    }

    @ViewBuilder
    private var searchResultsSection: some View {
        let directoryHits = LoincDirectory.shared.search(searchText, limit: 100)
        let trimmed = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let legacyHits = LabMapping.allKnownCodes.filter {
            $0.code.lowercased().contains(trimmed) || $0.name.lowercased().contains(trimmed)
        }

        Section("Results") {
            if directoryHits.isEmpty && legacyHits.isEmpty {
                if LoincDirectory.shared.isAvailable {
                    Text("No matches")
                        .foregroundStyle(.secondary)
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("LOINC database not loaded")
                            .font(.subheadline.weight(.medium))
                        Text("Run `python3 tools/build_loinc_db.py` against a Regenstrief LOINC release to enable full search.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            } else {
                ForEach(legacyHits, id: \.code) { item in
                    rangeRow(code: item.code.uppercased(), displayName: item.name)
                }
                ForEach(directoryHits) { entry in
                    rangeRow(code: entry.loinc, displayName: LoincDirectory.shared.displayName(for: entry))
                }
            }
        }
    }

    @ViewBuilder
    private func rangeRow(code: String, displayName: String) -> some View {
        NavigationLink(destination: ReferenceRangeEditorView(code: code, displayName: displayName)) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(displayName)
                        .lineLimit(2)
                    Text(code)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.tertiary)
                }
                Spacer()
                if let range = LabMapping.referenceRange(for: code) {
                    Text(range.normalSummary)
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                if overrides.range(for: code) != nil {
                    Image(systemName: "pencil.circle.fill")
                        .font(.caption)
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
    }

    @ViewBuilder
    private var resetSection: some View {
        if !overrides.ranges.isEmpty {
            Section {
                Button(role: .destructive) {
                    showResetAllConfirm = true
                } label: {
                    Text("Reset All Reference Ranges")
                }
            }
        }
    }
}

// MARK: - Reference range editor

struct ReferenceRangeEditorView: View {
    let code: String
    let displayName: String

    @AppStorage(LabMapping.overridesUserDefaultsKey) private var overrides = ReferenceRangeOverrides()
    @Environment(\.dismiss) private var dismiss

    @State private var normalLowText: String = ""
    @State private var normalHighText: String = ""
    @State private var borderlineLowText: String = ""
    @State private var borderlineHighText: String = ""
    @State private var showResetConfirm = false
    @State private var showCopiedHint = false

    private var defaultRange: ReferenceRange? {
        LabMapping.defaultReferenceRange(for: code)
    }

    var body: some View {
        Form {
            Section {
                rangeField("Normal Low", text: $normalLowText, defaultValue: defaultRange?.normalLow)
                rangeField("Normal High", text: $normalHighText, defaultValue: defaultRange?.normalHigh)
            } header: {
                Text("Normal Range")
            } footer: {
                Text("Values inside this range are considered Normal.")
            }

            Section {
                rangeField("Borderline Low", text: $borderlineLowText, defaultValue: defaultRange?.borderlineLow)
                rangeField("Borderline High", text: $borderlineHighText, defaultValue: defaultRange?.borderlineHigh)
            } header: {
                Text("Borderline Range")
            } footer: {
                Text("Values between Normal and Borderline are flagged as Borderline. Leave a field empty for no bound.")
            }

            loincSection

            if overrides.range(for: code) != nil {
                Section {
                    Button(role: .destructive) {
                        showResetConfirm = true
                    } label: {
                        Text("Reset to Default")
                    }
                }
            }
        }
        .navigationTitle(displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { save() }
                    .fontWeight(.semibold)
            }
        }
        .confirmationDialog(
            "Reset \(displayName) to default?",
            isPresented: $showResetConfirm,
            titleVisibility: .visible
        ) {
            Button("Reset", role: .destructive) {
                overrides.setRange(nil, for: code)
                loadFields()
            }
            Button("Cancel", role: .cancel) {}
        }
        .onAppear { loadFields() }
    }

    private var loincDisplay: (loinc: String, display: String)? {
        if let mapped = LabMapping.loincCode(for: code) {
            return (mapped.loinc, mapped.display)
        }
        if let entry = LoincDirectory.shared.entry(for: code) {
            return (entry.loinc, LoincDirectory.shared.displayName(for: entry))
        }
        return nil
    }

    @ViewBuilder
    private var loincSection: some View {
        if let loinc = loincDisplay {
            Section {
                HStack {
                    Text("Code")
                    Spacer()
                    Text(loinc.loinc)
                        .font(.body.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                if !loinc.display.isEmpty {
                    Text(loinc.display)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
                Button {
                    UIPasteboard.general.string = loinc.loinc
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    showCopiedHint = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                        showCopiedHint = false
                    }
                } label: {
                    Label(showCopiedHint ? "Copied" : "Copy LOINC Code",
                          systemImage: showCopiedHint ? "checkmark" : "doc.on.doc")
                }
                if let url = URL(string: "https://loinc.org/\(loinc.loinc)/") {
                    Link(destination: url) {
                        Label("View on loinc.org", systemImage: "safari")
                    }
                }
            } header: {
                Text("LOINC")
            } footer: {
                Text("Universal identifier published by Regenstrief Institute. Free to look up at loinc.org.")
            }
        }
    }

    @ViewBuilder
    private func rangeField(_ label: LocalizedStringKey, text: Binding<String>, defaultValue: Double?) -> some View {
        HStack {
            Text(label)
            Spacer()
            TextField(defaultPlaceholder(defaultValue), text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .font(.body.monospacedDigit())
                .frame(maxWidth: 120)
        }
    }

    private func defaultPlaceholder(_ value: Double?) -> String {
        guard let value else { return String(localized: "None") }
        if value == value.rounded() { return String(Int(value)) }
        return String(format: "%g", value)
    }

    private func loadFields() {
        let effective = overrides.range(for: code).map { override in
            ReferenceRangeOverrides.StoredRange(
                normalLow: override.normalLow,
                normalHigh: override.normalHigh,
                borderlineLow: override.borderlineLow,
                borderlineHigh: override.borderlineHigh
            )
        } ?? ReferenceRangeOverrides.StoredRange(
            normalLow: defaultRange?.normalLow,
            normalHigh: defaultRange?.normalHigh,
            borderlineLow: defaultRange?.borderlineLow,
            borderlineHigh: defaultRange?.borderlineHigh
        )
        normalLowText = effective.normalLow.map { stringify($0) } ?? ""
        normalHighText = effective.normalHigh.map { stringify($0) } ?? ""
        borderlineLowText = effective.borderlineLow.map { stringify($0) } ?? ""
        borderlineHighText = effective.borderlineHigh.map { stringify($0) } ?? ""
    }

    private func stringify(_ value: Double) -> String {
        if value == value.rounded() { return String(Int(value)) }
        return String(format: "%g", value)
    }

    private func parse(_ text: String) -> Double? {
        let trimmed = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        guard !trimmed.isEmpty else { return nil }
        return Double(trimmed)
    }

    private func save() {
        let stored = ReferenceRangeOverrides.StoredRange(
            normalLow: parse(normalLowText),
            normalHigh: parse(normalHighText),
            borderlineLow: parse(borderlineLowText),
            borderlineHigh: parse(borderlineHighText)
        )
        overrides.setRange(stored, for: code)
        dismiss()
    }
}

#Preview {
    SettingsView()
}
