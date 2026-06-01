import SafariServices
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

    /// Web URL of the repository this build came from, stamped into `Info.plist`
    /// at build time (`GitRepositoryURL`) so forks open their own repo. Returns
    /// `nil` when the build did not stamp a URL (e.g. local Xcode builds), in
    /// which case the GitHub buttons are hidden.
    static var repositoryURL: URL? {
        guard let value = string("GitRepositoryURL") else { return nil }
        return webURL(from: value)
    }

    /// URL that opens the "new issue" composer for `repositoryURL`, pre-filling
    /// the body with build metadata to help triage reports.
    static var newIssueURL: URL? {
        guard let base = repositoryURL?.appendingPathComponent("issues/new"),
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else { return nil }
        let body = """


        ---
        Version: \(version) (\(build))
        Branch: \(branch)
        Commit: \(commit)
        """
        components.queryItems = [URLQueryItem(name: "body", value: body)]
        return components.url
    }

    /// Normalizes a git remote string (`https`, `.git` suffix, or `git@host:owner/repo`
    /// SSH form) into a browsable `https` web URL.
    private static func webURL(from remote: String) -> URL? {
        var value = remote.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }

        if let range = value.range(of: "git@") {
            // git@github.com:owner/repo(.git) -> https://github.com/owner/repo
            let hostAndPath = value[range.upperBound...].replacingOccurrences(of: ":", with: "/")
            value = "https://" + hostAndPath
        }
        if value.hasSuffix(".git") {
            value = String(value.dropLast(4))
        }
        return URL(string: value)
    }
}

// MARK: - SettingsView

struct SettingsView: View {
    @Binding var prefs: LabDisplayPreferences
    let allCodes: [CodeName]
    /// `true` when presented as a sheet (iPhone) — shows a Close button. `false`
    /// when hosted as the detail pane of the iPad sidebar, where the split view
    /// owns dismissal and a Close button would be out of place.
    var isModal = true
    @Environment(\.dismiss) private var dismiss
    @State private var browserURL: IdentifiedURL?
    @AppStorage(CloudSyncService.enabledKey) private var iCloudSyncEnabled = false

    var body: some View {
        NavigationStack {
            List {
                Section("Dashboard") {
                    NavigationLink {
                        LabSortEditor(prefs: $prefs, allCodes: allCodes)
                    } label: {
                        SettingsRowLabel("Sort & Visibility",
                                         systemImage: "arrow.up.arrow.down", color: .blue)
                    }
                }

                Section {
                    Toggle(isOn: $iCloudSyncEnabled) {
                        SettingsRowLabel("iCloud Sync",
                                         systemImage: "arrow.triangle.2.circlepath", color: .blue)
                    }
                } footer: {
                    Text("Sync your dashboard layout — the card order plus what you pin and hide — across your devices. Your lab values stay in Apple Health.")
                }

                Section("LOINC") {
                    NavigationLink {
                        LoincCatalogView()
                    } label: {
                        SettingsRowLabel("Browse Catalog",
                                         systemImage: "magnifyingglass", color: .indigo)
                    }
                    NavigationLink {
                        LoincLicenseView()
                    } label: {
                        SettingsRowLabel("LOINC License", systemImage: "doc.text", color: .teal)
                    }
                    if !LoincDirectory.shared.version.isEmpty {
                        LabeledContent {
                            Text(verbatim: LoincDirectory.shared.version)
                        } label: {
                            SettingsRowLabel("Version", systemImage: "number.square", color: .gray)
                        }
                    }
                }

                Section("About") {
                    LabeledContent {
                        Text(AppInfo.branch)
                    } label: {
                        SettingsRowLabel("Branch",
                                         systemImage: "arrow.triangle.branch", color: .orange)
                    }
                    LabeledContent {
                        Text(AppInfo.commit)
                    } label: {
                        SettingsRowLabel("Commit", systemImage: "number", color: .purple)
                    }
                    NavigationLink {
                        LicenseView()
                    } label: {
                        SettingsRowLabel("License", systemImage: "doc.text", color: .pink)
                    }
                }

                Section {
                    SettingsRowLabel("Not Medical Advice",
                                     systemImage: "cross.case", color: .red)
                } footer: {
                    Text("""
                    LabImporter is not a medical device and does not provide medical advice, \
                    diagnosis, or treatment. Extracted values may be inaccurate or incomplete — \
                    always verify them against your original report. Never make medical decisions \
                    based on this app; consult a qualified healthcare professional about your results.
                    """)
                }

                Section {
                    if let repository = AppInfo.repositoryURL {
                        linkRow("View on GitHub",
                                systemImage: "chevron.left.forwardslash.chevron.right",
                                color: .gray, url: repository)
                    }
                    if let newIssue = AppInfo.newIssueURL {
                        linkRow("Report an Issue",
                                systemImage: "exclamationmark.bubble",
                                color: .red, url: newIssue)
                    }
                } footer: {
                    Text("Version \(AppInfo.version) (\(AppInfo.build))")
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, 8)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                if isModal {
                    ToolbarItem(placement: .topBarLeading) {
                        Button(role: .close) { dismiss() }
                    }
                }
            }
            .sheet(item: $browserURL) { item in
                SafariView(url: item.url)
                    .ignoresSafeArea()
            }
        }
    }

    /// A standard settings row that opens a web URL in the in-app browser, with a
    /// trailing external-link glyph to signal it leaves the app.
    private func linkRow(_ titleKey: LocalizedStringKey,
                         systemImage: String,
                         color: Color,
                         url: URL) -> some View {
        Button {
            browserURL = IdentifiedURL(url: url)
        } label: {
            HStack {
                SettingsRowLabel(titleKey, systemImage: systemImage, color: color)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - SettingsRowLabel

/// A list-row label with a rounded, color-filled icon tile in the style of the
/// iOS Settings app — used to give the otherwise plain settings screens some life.
struct SettingsRowLabel: View {
    let title: LocalizedStringKey
    let systemImage: String
    let color: Color

    init(_ title: LocalizedStringKey, systemImage: String, color: Color) {
        self.title = title
        self.systemImage = systemImage
        self.color = color
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: systemImage)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(color.gradient, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
    }
}

// MARK: - In-app browser

/// Wraps a `URL` so it can drive a `.sheet(item:)` presentation.
struct IdentifiedURL: Identifiable {
    let id = UUID()
    let url: URL
}

/// SwiftUI wrapper around `SFSafariViewController` for in-app web browsing.
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }

    func updateUIViewController(_ controller: SFSafariViewController, context: Context) {}
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
            pinnedSection
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

    /// Visible codes split by pin state. Both partitions preserve their relative
    /// order within `visibleOrdered`, which is normalized pinned-first so the
    /// editor order matches how the dashboard groups the cards.
    private var pinnedItems: [CodeName] { visibleOrdered.filter { pinnedSet.contains($0.code) } }
    private var unpinnedItems: [CodeName] { visibleOrdered.filter { !pinnedSet.contains($0.code) } }

    @ViewBuilder
    private var pinnedSection: some View {
        if !pinnedItems.isEmpty {
            Section("Pinned") {
                ForEach(pinnedItems) { item in row(for: item) }
                    .onMove { from, dest in
                        var pinned = pinnedItems
                        pinned.move(fromOffsets: from, toOffset: dest)
                        visibleOrdered = pinned + unpinnedItems
                    }
            }
        }
    }

    @ViewBuilder
    private var visibleSection: some View {
        Section("Visible") {
            ForEach(unpinnedItems) { item in row(for: item) }
                .onMove { from, dest in
                    var unpinned = unpinnedItems
                    unpinned.move(fromOffsets: from, toOffset: dest)
                    visibleOrdered = pinnedItems + unpinned
                }
        }
    }

    private func row(for item: CodeName) -> some View {
        HStack(spacing: 12) {
            Button { togglePin(item.code) } label: {
                Image(systemName: pinnedSet.contains(item.code) ? "pin.fill" : "pin")
                    .foregroundStyle(pinnedSet.contains(item.code) ? Color.yellow : Color.secondary)
                    .frame(width: 20)
            }
            .buttonStyle(.plain)
            Circle()
                .fill(LabCategory.forCode(item.code).color.gradient)
                .frame(width: 9, height: 9)
            Text(item.name)
            Spacer()
            Button { hideCode(item.code) } label: {
                Image(systemName: "eye.slash")
                    .foregroundStyle(Color.secondary)
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private var hiddenSection: some View {
        let hiddenItems = allCodes.filter { hiddenSet.contains($0.code) }
        if !hiddenItems.isEmpty {
            Section("Hidden") {
                ForEach(hiddenItems) { item in
                    HStack(spacing: 12) {
                        Circle()
                            .fill(LabCategory.forCode(item.code).color.opacity(0.4))
                            .frame(width: 9, height: 9)
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
        // Keep the backing order pinned-first so a toggled row moves between the
        // two sections in place, matching the dashboard's grouping.
        visibleOrdered = pinnedItems + unpinnedItems
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

// MARK: - Previews

#Preview("Settings") {
    @Previewable @State var prefs = LabDisplayPreferences()
    SettingsView(prefs: $prefs, allCodes: CodeName.sampleCodes)
}

#Preview("Sort & Visibility") {
    @Previewable @State var prefs = LabDisplayPreferences()
    NavigationStack {
        LabSortEditor(prefs: $prefs, allCodes: CodeName.sampleCodes)
    }
}
