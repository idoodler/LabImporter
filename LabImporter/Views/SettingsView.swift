import SwiftUI

// MARK: - App info

enum AppInfo {
    private static func string(_ key: String) -> String? {
        guard let value = Bundle.main.infoDictionary?[key] as? String,
              !value.isEmpty else { return nil }
        return value
    }

    static var version: String { string("CFBundleShortVersionString") ?? "—" }
    static var build: String { string("CFBundleVersion") ?? "—" }
    static var branch: String { string("GitBranch") ?? "unknown" }
    static var commit: String { string("GitCommit") ?? "unknown" }
}

// MARK: - SettingsView

struct SettingsView: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Dashboard") {
                    NavigationLink {
                        LabSortEditor(prefs: $prefs, allCodes: allCodes)
                    } label: {
                        Label("Sort & Visibility", systemImage: "arrow.up.arrow.down")
                    }
                }

                Section("About") {
                    LabeledContent("Version", value: "\(AppInfo.version) (\(AppInfo.build))")
                    LabeledContent("Branch", value: AppInfo.branch)
                    LabeledContent("Commit", value: AppInfo.commit)
                    NavigationLink {
                        LicenseView()
                    } label: {
                        Label("License", systemImage: "doc.text")
                    }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }
}

// MARK: - CodeName

struct CodeName: Identifiable {
    var id: String { code }
    let code: String
    let name: String
}

// MARK: - LabSortEditor

struct LabSortEditor: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]

    @State private var visibleOrdered: [CodeName]
    @State private var hiddenSet: Set<String>
    @State private var pinnedSet: Set<String>

    init(prefs: Binding<LabDisplayPreferences>, allCodes: [CodeName]) {
        _prefs = prefs
        self.allCodes = allCodes

        let currentPrefs = prefs.wrappedValue
        let hidden = currentPrefs.hiddenSet

        var seen = Set<String>()
        var initial: [CodeName] = []
        for code in currentPrefs.orderedCodes where !hidden.contains(code) {
            guard let item = allCodes.first(where: { $0.code == code }),
                  seen.insert(code).inserted else { continue }
            initial.append(item)
        }
        for item in allCodes where !hidden.contains(item.code) && seen.insert(item.code).inserted {
            initial.append(item)
        }

        _visibleOrdered = State(initialValue: initial)
        _hiddenSet = State(initialValue: hidden)
        _pinnedSet = State(initialValue: currentPrefs.pinnedSet)
    }

    var body: some View {
        List {
            visibleSection
            hiddenSection
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Sort & Visibility")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: visibleOrdered.map(\.code)) { save() }
        .onChange(of: hiddenSet) { save() }
        .onChange(of: pinnedSet) { save() }
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

// MARK: - LicenseView

struct LicenseView: View {
    var body: some View {
        ScrollView {
            Text(Self.licenseText)
                .font(.footnote)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle("License")
        .navigationBarTitleDisplayMode(.inline)
    }

    private static let licenseText = """
    MIT License

    Copyright (c) 2026 idoodler

    Permission is hereby granted, free of charge, to any person obtaining a copy \
    of this software and associated documentation files (the "Software"), to deal \
    in the Software without restriction, including without limitation the rights \
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell \
    copies of the Software, and to permit persons to whom the Software is \
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all \
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR \
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, \
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE \
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER \
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, \
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE \
    SOFTWARE.
    """
}
