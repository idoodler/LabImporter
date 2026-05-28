import SwiftUI

struct CodeName: Identifiable, Hashable {
    var id: String { code }
    let code: String
    let name: String
}

struct SettingsView: View {
    let visibleCodes: [CodeName]

    @State private var searchText = ""
    @Environment(\.dismiss) private var dismiss

    init(visibleCodes: [CodeName] = []) {
        self.visibleCodes = visibleCodes
    }

    var body: some View {
        NavigationStack {
            List {
                orderSection
                codesContent
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

    // MARK: - LOINC codes (browse / search)

    @ViewBuilder
    private var codesContent: some View {
        if searchText.isEmpty {
            yourCodesSection
        } else {
            searchResultsSection
        }
    }

    @ViewBuilder
    private var yourCodesSection: some View {
        if !visibleCodes.isEmpty {
            Section("Your Codes") {
                ForEach(visibleCodes) { item in
                    codeRow(code: item.code, displayName: item.name)
                }
            }
        }
    }

    @ViewBuilder
    private var searchResultsSection: some View {
        let directoryHits = LoincDirectory.shared.search(searchText, limit: 100)

        Section("Results") {
            if directoryHits.isEmpty {
                if LoincDirectory.shared.isAvailable {
                    Text("No matches")
                        .foregroundStyle(.secondary)
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("LOINC database not loaded")
                            .font(.subheadline.weight(.medium))
                        Text("Run the build to fetch LOINC data from Wikidata, or rebuild after going online.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            } else {
                ForEach(directoryHits) { entry in
                    codeRow(code: entry.loinc, displayName: LoincDirectory.shared.displayName(for: entry))
                }
            }
        }
    }

    @ViewBuilder
    private func codeRow(code: String, displayName: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(displayName)
                    .lineLimit(2)
                Text(code)
                    .font(.caption2.monospaced())
                    .foregroundStyle(.tertiary)
            }
            Spacer()
        }
    }
}

#Preview {
    SettingsView()
}
