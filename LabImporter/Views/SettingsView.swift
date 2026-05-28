import SwiftUI

struct CodeName: Identifiable, Hashable {
    var id: String { code }
    let code: String
    let name: String
}

struct SettingsView: View {
    let visibleCodes: [CodeName]

    @AppStorage(LabMapping.overridesUserDefaultsKey) private var overrides = ReferenceRangeOverrides()
    @State private var showResetAllConfirm = false
    @Environment(\.dismiss) private var dismiss

    init(visibleCodes: [CodeName] = []) {
        self.visibleCodes = visibleCodes
    }

    var body: some View {
        NavigationStack {
            List {
                orderSection
                referenceRangesSection
                resetSection
                aboutSection
            }
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

    // MARK: - Reference Ranges

    @ViewBuilder
    private var referenceRangesSection: some View {
        Section {
            ForEach(LabMapping.allKnownCodes, id: \.code) { item in
                NavigationLink(destination: ReferenceRangeEditorView(code: item.code, displayName: item.name)) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                            Text(item.code)
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        Spacer()
                        if let range = LabMapping.referenceRange(for: item.code) {
                            Text(range.normalSummary)
                                .font(.subheadline.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        if overrides.range(for: item.code) != nil {
                            Image(systemName: "pencil.circle.fill")
                                .font(.caption)
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                }
            }
        } header: {
            Text("Reference Ranges")
        } footer: {
            Text("Customise the Normal and Borderline boundaries used to flag values on the dashboard.")
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

// MARK: - Order & visibility editor

struct LabOrderEditorView: View {
    let allCodes: [CodeName]

    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @Environment(\.dismiss) private var dismiss

    @State private var visibleOrdered: [CodeName] = []
    @State private var hiddenSet: Set<String> = []
    @State private var pinnedSet: Set<String> = []
    @State private var didLoad = false

    var body: some View {
        List {
            visibleSection
            hiddenSection
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Order & Visibility")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") { save(); dismiss() }
                    .fontWeight(.semibold)
            }
        }
        .onAppear { loadInitialState() }
    }

    @ViewBuilder
    private var visibleSection: some View {
        Section("Visible") {
            ForEach(visibleOrdered) { item in
                HStack(spacing: 12) {
                    Button { togglePin(item.code) } label: {
                        Image(systemName: pinnedSet.contains(item.code) ? "pin.fill" : "pin")
                            .foregroundStyle(pinnedSet.contains(item.code) ? Color.yellow : Color.secondary)
                            .frame(width: 20)
                    }
                    .buttonStyle(.plain)
                    Text(item.name)
                    Spacer()
                    Button { hideCode(item.code) } label: {
                        Image(systemName: "eye.slash")
                            .foregroundStyle(Color.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }
            .onMove { from, dest in visibleOrdered.move(fromOffsets: from, toOffset: dest) }
        }
    }

    @ViewBuilder
    private var hiddenSection: some View {
        let hiddenItems = allCodes.filter { hiddenSet.contains($0.code) }
        if !hiddenItems.isEmpty {
            Section("Hidden") {
                ForEach(hiddenItems) { item in
                    HStack {
                        Text(item.name)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("Restore") { restoreCode(item.code) }
                            .buttonStyle(.plain)
                            .foregroundStyle(Color.accentColor)
                            .fontWeight(.medium)
                    }
                    .moveDisabled(true)
                }
            }
        }
    }

    private func loadInitialState() {
        guard !didLoad else { return }
        didLoad = true
        let hidden = prefs.hiddenSet
        var seen = Set<String>()
        var initial: [CodeName] = []
        for code in prefs.orderedCodes where !hidden.contains(code) {
            guard let item = allCodes.first(where: { $0.code == code }),
                  seen.insert(code).inserted else { continue }
            initial.append(item)
        }
        for item in allCodes where !hidden.contains(item.code) && seen.insert(item.code).inserted {
            initial.append(item)
        }
        visibleOrdered = initial
        hiddenSet = hidden
        pinnedSet = prefs.pinnedSet
    }

    private func togglePin(_ code: String) {
        if pinnedSet.contains(code) {
            pinnedSet.remove(code)
        } else {
            pinnedSet.insert(code)
        }
    }

    private func hideCode(_ code: String) {
        hiddenSet.insert(code)
        visibleOrdered.removeAll { $0.code == code }
    }

    private func restoreCode(_ code: String) {
        hiddenSet.remove(code)
        if let item = allCodes.first(where: { $0.code == code }) {
            visibleOrdered.append(item)
        }
    }

    private func save() {
        let hiddenOrdered = allCodes.filter { hiddenSet.contains($0.code) }
        var updated = prefs
        updated.orderedCodes = visibleOrdered.map(\.code) + hiddenOrdered.map(\.code)
        updated.pinnedCodes = Array(pinnedSet)
        updated.hiddenCodes = Array(hiddenSet)
        prefs = updated
    }
}

// MARK: - License view

struct LicenseView: View {
    var body: some View {
        ScrollView {
            Text(Self.mitLicenseText)
                .font(.system(.footnote, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
                .textSelection(.enabled)
        }
        .navigationTitle("License")
        .navigationBarTitleDisplayMode(.inline)
    }

    private static let mitLicenseText: String = """
    MIT License

    Copyright (c) 2026 idoodler

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
    """
}

#Preview {
    SettingsView()
}
