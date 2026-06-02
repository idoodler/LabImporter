import SwiftUI

// MARK: - LoincLicenseView

/// Displays the Regenstrief LOINC license bundled in loinc.db, satisfying the
/// requirement that the license travels with the LOINC data.
struct LoincLicenseView: View {
    var body: some View {
        LicenseDocumentView(title: "LOINC License", header: header, text: licenseText)
    }

    private var header: String? {
        let version = LoincDirectory.shared.version
        return version.isEmpty ? nil : "LOINC® \(version)"
    }

    private var licenseText: String {
        let text = LoincDirectory.shared.license
        return text.isEmpty ? String(localized: "The LOINC license is unavailable in this build.") : text
    }
}

// MARK: - LoincCatalogView

/// Browse and search the full bundled LOINC catalog — the same search used when
/// manually adding a value, here in read-only form.
struct LoincCatalogView: View {
    @State private var query = ""
    @State private var results: [LoincTerm] = []
    @State private var reachedEnd = false

    private let pageSize = 100

    var body: some View {
        List {
            ForEach(results) { term in
                NavigationLink {
                    LoincTermDetailView(term: term)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(term.name)
                        if let description = term.description, !description.isEmpty {
                            Text(description)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                        Text(term.code)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
                .onAppear {
                    if term.id == results.last?.id { loadMore() }
                }
            }
        }
        .searchable(text: $query, prompt: Text("Search lab tests"))
        .navigationTitle("LOINC catalog")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if results.isEmpty && !query.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .task(id: query) { await reload() }
    }

    private func reload() async {
        let current = query
        let size = pageSize
        let page = await Task.detached(priority: .userInitiated) {
            LoincDirectory.shared.search(current, limit: size, offset: 0)
        }.value
        guard current == query else { return }
        results = page
        reachedEnd = page.count < size
    }

    private func loadMore() {
        guard !reachedEnd else { return }
        let current = query
        let size = pageSize
        let offset = results.count
        Task {
            let page = await Task.detached(priority: .userInitiated) {
                LoincDirectory.shared.search(current, limit: size, offset: offset)
            }.value
            guard current == query, offset == results.count else { return }
            results.append(contentsOf: page)
            reachedEnd = page.count < size
        }
    }
}

// MARK: - LoincTermDetailView

/// The structured attributes of one LOINC term — the six-part name plus class,
/// status, names and units — mirroring a loinc.org details page, offline.
struct LoincTermDetailView: View {
    let term: LoincTerm
    @State private var detail: LoincDetail?
    @State private var browserURL: IdentifiedURL?
    @AppStorage("labDisplayPrefs") private var prefs = LabDisplayPreferences()
    @State private var renamingCode: String?
    @State private var renameDraft = ""

    var body: some View {
        List {
            Section {
                Text(detail?.name ?? term.name).font(.headline)
                if let custom = prefs.customName(for: term.code) {
                    Label(custom, systemImage: "pencil")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if let description = detail?.description ?? term.description, !description.isEmpty {
                    Text(description).foregroundStyle(.secondary)
                }
            }
            if let detail {
                Section("Details") {
                    attribute("LOINC Code", detail.code)
                    attribute("Component", detail.component)
                    attribute("Property", detail.property)
                    attribute("Timing", detail.timing)
                    attribute("System", detail.system)
                    attribute("Scale", detail.scale)
                    attribute("Method", detail.method)
                    attribute("Class", detail.loincClass)
                    attribute("Status", detail.status)
                }
                Section {
                    attribute("Long name", detail.longName)
                    attribute("Short name", detail.shortName)
                    attribute("Units", detail.ucum)
                }
            }
            if let url = LabMapping.loincURL(for: term.code) {
                Section {
                    Button {
                        browserURL = IdentifiedURL(url: url)
                    } label: {
                        Label("View on loinc.org", systemImage: "safari")
                    }
                } footer: {
                    Text("Opens the full description and references on loinc.org.")
                }
            }
        }
        .navigationTitle(Text(verbatim: term.code))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    renameDraft = prefs.customName(for: term.code) ?? ""
                    renamingCode = term.code
                } label: {
                    Label("Rename", systemImage: "pencil")
                }
            }
        }
        .renameLabAlert(code: $renamingCode, draft: $renameDraft, prefs: $prefs)
        .task { detail = LoincDirectory.shared.detail(for: term.code) }
        .sheet(item: $browserURL) { item in
            SafariView(url: item.url)
                .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func attribute(_ label: LocalizedStringKey, _ value: String) -> some View {
        if !value.isEmpty {
            LabeledContent(label) { Text(value) }
        }
    }
}

// MARK: - Previews

#Preview("Term Detail") {
    NavigationStack {
        LoincTermDetailView(term: .sample)
    }
}

#Preview("Catalog") {
    NavigationStack {
        LoincCatalogView()
    }
}

#Preview("License") {
    NavigationStack {
        LoincLicenseView()
    }
}
