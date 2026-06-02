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
    @State private var backgroundColors: [Color] = []
    @FocusState private var searchFocused: Bool

    private let pageSize = 100

    var body: some View {
        List {
            ForEach(results) { term in
                let category = LabCategory.forCode(term.code)
                NavigationLink {
                    LoincTermDetailView(term: term)
                } label: {
                    HStack(spacing: 14) {
                        CategoryIcon(color: category.color)

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
                    .padding(.vertical, 2)
                }
                .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                .onAppear {
                    if term.id == results.last?.id { loadMore() }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background { CategoryBackground(colors: backgroundColors) }
        .searchable(text: $query, prompt: Text("Search lab tests"))
        .searchFocused($searchFocused)
        .onAppear { searchFocused = true }
        .navigationTitle("LOINC catalog")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if results.isEmpty && !query.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .task(id: query) { await reload() }
    }

    // Up to three distinct category colors from the loaded terms — the same
    // background wash the Dashboard and History screens use. Recomputed only when
    // the result set changes (reload / loadMore), not on every body pass.
    private static func washColors(for terms: [LoincTerm]) -> [Color] {
        var seen = Set<LabCategory>()
        var result: [Color] = []
        for term in terms {
            let category = LabCategory.forCode(term.code)
            if seen.insert(category).inserted { result.append(category.color) }
            if result.count == 3 { break }
        }
        return result
    }

    private func reload() async {
        let current = query
        let size = pageSize
        let page = await Task.detached(priority: .userInitiated) {
            LoincDirectory.shared.search(current, limit: size, offset: 0)
        }.value
        guard current == query else { return }
        results = page
        backgroundColors = Self.washColors(for: page)
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
            // The wash only needs three distinct colors; once it has them, later
            // pages can't change it, so skip the rescan in the common case.
            if backgroundColors.count < 3 {
                backgroundColors = Self.washColors(for: results)
            }
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

    // The clinical category drives the accent color used for the header icon,
    // the category chip and the background wash — the same palette charts use.
    private var category: LabCategory { LabCategory.forCode(term.code) }

    var body: some View {
        List {
            Section {
                headerCard
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
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
                .listRowBackground(Rectangle().fill(.ultraThinMaterial))
                Section {
                    attribute("Long name", detail.longName)
                    attribute("Short name", detail.shortName)
                    attribute("Units", detail.ucum)
                }
                .listRowBackground(Rectangle().fill(.ultraThinMaterial))
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
                .listRowBackground(Rectangle().fill(.ultraThinMaterial))
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background { CategoryBackground(colors: [category.color]) }
        .navigationTitle(Text(verbatim: term.code))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    renameDraft = prefs.nickname(for: term.code) ?? ""
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

    // MARK: - Header

    private var headerCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 14) {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [category.color, category.color.opacity(0.65)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Image(systemName: "testtube.2")
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(.white)
                }
                .frame(width: 54, height: 54)
                .shadow(color: category.color.opacity(0.35), radius: 5, x: 0, y: 2)

                VStack(alignment: .leading, spacing: 6) {
                    Text(detail?.name ?? term.name)
                        .font(.headline)
                    categoryChip
                }
                Spacer(minLength: 0)
            }

            if let nickname = prefs.nickname(for: term.code) {
                Divider()
                nicknameRow(nickname)
            }

            if let description = detail?.description ?? term.description, !description.isEmpty {
                Divider()
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 22))
        .overlay(
            RoundedRectangle(cornerRadius: 22)
                .stroke(Color.primary.opacity(0.1), lineWidth: 0.5)
        )
    }

    private var categoryChip: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(category.color)
                .frame(width: 8, height: 8)
            Text(category.displayName)
                .font(.caption.weight(.medium))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(category.color.opacity(0.12), in: Capsule())
        .overlay(
            Capsule().stroke(category.color.opacity(0.25), lineWidth: 0.5)
        )
    }

    // The user's nickname for this test, shown as a plainly-labelled read-only
    // field (renaming happens via the toolbar) — a "tag" glyph, not a pencil, so
    // it never reads as an inline edit button.
    private func nicknameRow(_ name: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "tag.fill")
                .font(.subheadline)
                .foregroundStyle(category.color)
                .frame(width: 20)
            VStack(alignment: .leading, spacing: 1) {
                Text("Nickname")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .textCase(.uppercase)
                Text(name)
                    .font(.subheadline.weight(.medium))
            }
            Spacer(minLength: 0)
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
